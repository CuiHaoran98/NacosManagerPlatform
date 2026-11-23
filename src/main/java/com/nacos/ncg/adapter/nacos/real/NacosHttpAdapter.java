package com.nacos.ncg.adapter.nacos.real;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nacos.ncg.adapter.nacos.config.NacosConnectionRegistry;
import com.nacos.ncg.adapter.nacos.config.NacosConnectionRegistry.NacosConnection;
import com.nacos.ncg.adapter.nacos.port.ConfigRef;
import com.nacos.ncg.adapter.nacos.port.ConnectionReport;
import com.nacos.ncg.adapter.nacos.port.EnvId;
import com.nacos.ncg.adapter.nacos.port.HistoryEntry;
import com.nacos.ncg.adapter.nacos.port.HistoryPage;
import com.nacos.ncg.adapter.nacos.port.IdempotencyKey;
import com.nacos.ncg.adapter.nacos.port.NacosConfigPort;
import com.nacos.ncg.adapter.nacos.port.PublishOutcome;
import com.nacos.ncg.adapter.nacos.port.RejectReason;
import com.nacos.ncg.adapter.nacos.port.Snapshot;
import com.nacos.ncg.adapter.nacos.port.Subscriber;
import com.nacos.ncg.adapter.nacos.port.SubscriberSnapshot;
import com.nacos.ncg.shared.ContentDigest;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 真实 Nacos 适配器：对接 Nacos OpenAPI v1（上游文档旧称 TongNCS，m00232 决策改用 Nacos）。
 *
 * <p>超时与重试遵循 §8.4：连接 5s；read/history/subscribers 10s 且最多重试 1 次（只读）；
 * publish 写入 10s 不自动重试；读回 5s 不自动重试、失败转 indeterminate；test 10s。
 *
 * <p>API 细节 [Assumption, TBD]（Nacos 版本差异待沙箱钉死）：
 * 读 GET /nacos/v1/cs/configs；发布 POST /nacos/v1/cs/configs（casMd5 做写前 CAS）；
 * 历史 GET /nacos/v1/cs/history?search=accurate；订阅 GET /nacos/v1/cs/configs/listeners；
 * 连通性 GET /nacos/v1/ns/operator/metrics。namespace → tenant 参数。
 */
public class NacosHttpAdapter implements NacosConfigPort {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READBACK_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int HISTORY_PAGE_SIZE = 100;

    private final NacosConnectionRegistry registry;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public NacosHttpAdapter(NacosConnectionRegistry registry) {
        this.registry = registry;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public Snapshot read(EnvId envId, ConfigRef ref) {
        NacosConnection conn = connection(envId);
        HttpResponse<String> response = sendWithOneRetry(() -> get(conn, csPath("/configs") + refQuery(ref), READ_TIMEOUT));
        if (response.statusCode() == 404) {
            throw new NcgException(NcgErrorCode.SYS_404_001, "config not found: " + ref);
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new NcgException(NcgErrorCode.AUTH_401_001, "nacos auth failed: " + response.statusCode());
        }
        if (response.statusCode() != 200) {
            throw new NcgException(NcgErrorCode.CON_502_001,
                    "nacos read failed: http " + response.statusCode(), Map.of("ref", ref.toString()));
        }
        String content = response.body();
        return new Snapshot(content, ContentDigest.md5Hex(content), Instant.now(), Snapshot.Source.LIVE);
    }

    @Override
    public PublishOutcome publish(EnvId envId, ConfigRef ref, String content, String expectedMd5, IdempotencyKey key) {
        NacosConnection conn = connection(envId);
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("endpoint", conn.endpoint().toString());
        receipt.put("key", key.value());

        // 事务外写入（§7.3）：写入已发出但结果不明 → indeterminate，禁止自动重试（C-ARC-05）
        HttpResponse<String> writeResponse;
        try {
            writeResponse = postForm(conn, csPath("/configs"), publishForm(ref, content, expectedMd5), PUBLISH_TIMEOUT);
        } catch (ConnectException | UnknownHostException e) {
            // 连接失败 = 写入未发出 → rejected(unavailable)
            return new PublishOutcome.Rejected(RejectReason.UNAVAILABLE, e.getMessage(), receipt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PublishOutcome.Indeterminate("publish interrupted: " + e.getMessage(), receipt);
        } catch (IOException e) {
            // 已发起请求但结果不明（超时/中断）→ indeterminate
            return new PublishOutcome.Indeterminate("publish outcome unknown: " + e.getMessage(), receipt);
        }

        int status = writeResponse.statusCode();
        if (status == 401 || status == 403) {
            return new PublishOutcome.Rejected(RejectReason.AUTH, "http " + status, receipt);
        }
        if (status == 400) {
            return new PublishOutcome.Rejected(RejectReason.INVALID, "http 400: " + truncate(writeResponse.body()), receipt);
        }
        if (status != 200) {
            return new PublishOutcome.Indeterminate("unexpected http " + status, receipt);
        }
        String body = writeResponse.body() == null ? "" : writeResponse.body().trim();
        if (!"true".equalsIgnoreCase(body)) {
            // casMd5 不匹配时 Nacos 拒绝发布 [Assumption, TBD]：归为基线不一致
            return new PublishOutcome.Rejected(RejectReason.BASELINE, "publish refused: " + truncate(body), receipt);
        }

        // 写后读回（§8.5）：只比对 MD5，读回内容不落平台库
        try {
            HttpResponse<String> readback = get(conn, csPath("/configs") + refQuery(ref), READBACK_TIMEOUT);
            if (readback.statusCode() != 200) {
                return new PublishOutcome.Indeterminate("readback failed: http " + readback.statusCode(), receipt);
            }
            String readbackMd5 = ContentDigest.md5Hex(readback.body());
            String expected = expectedMd5 == null ? ContentDigest.md5Hex(content) : expectedMd5;
            String writtenMd5 = ContentDigest.md5Hex(content);
            receipt.put("readbackMd5", readbackMd5);
            if (!writtenMd5.equals(readbackMd5)) {
                return new PublishOutcome.Indeterminate("readback md5 mismatch", receipt);
            }
            return new PublishOutcome.Verified(expected, readbackMd5, receipt);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // 写入已发出、读回失败 → indeterminate（结果不确定，人工对账）
            return new PublishOutcome.Indeterminate("readback error: " + e.getMessage(), receipt);
        }
    }

    @Override
    public HistoryPage history(EnvId envId, ConfigRef ref, Instant since, String cursor) {
        NacosConnection conn = connection(envId);
        int pageNo = cursor == null || cursor.isBlank() ? 1 : Integer.parseInt(cursor);
        StringBuilder url = new StringBuilder(csPath("/history"))
                .append("?search=accurate").append(refQuery(ref))
                .append("&pageNo=").append(pageNo).append("&pageSize=").append(HISTORY_PAGE_SIZE);
        if (since != null) {
            url.append("&startTime=").append(URLEncoder.encode(since.toString(), StandardCharsets.UTF_8));
        }
        HttpResponse<String> response = sendWithOneRetry(() -> get(conn, url.toString(), READ_TIMEOUT));
        if (response.statusCode() != 200) {
            throw new NcgException(NcgErrorCode.CON_502_001, "nacos history failed: http " + response.statusCode());
        }
        try {
            JsonNode root = mapper.readTree(response.body());
            JsonNode items = root.path("pageItems");
            List<HistoryEntry> entries = new ArrayList<>();
            if (items.isArray()) {
                for (JsonNode item : items) {
                    entries.add(new HistoryEntry(
                            text(item, "nid"),
                            ref.namespace(), ref.group(), ref.dataId(),
                            text(item, "md5"),
                            parseInstant(text(item, "lastModifiedTime")),
                            text(item, "srcUser"),
                            text(item, "ops")));
                }
            }
            int total = root.path("totalCount").asInt(entries.size());
            int next = pageNo + 1;
            boolean complete = pageNo * HISTORY_PAGE_SIZE >= total;
            return new HistoryPage(entries, complete ? null : String.valueOf(next), complete, Instant.now());
        } catch (IOException e) {
            throw new NcgException(NcgErrorCode.CON_502_001, "nacos history parse failed: " + e.getMessage());
        }
    }

    @Override
    public SubscriberSnapshot subscribers(EnvId envId, ConfigRef ref) {
        NacosConnection conn = connection(envId);
        HttpResponse<String> response = sendWithOneRetry(
                () -> get(conn, csPath("/configs/listeners") + refQuery(ref), READ_TIMEOUT));
        if (response.statusCode() != 200) {
            throw new NcgException(NcgErrorCode.CON_502_001, "nacos listeners failed: http " + response.statusCode());
        }
        try {
            JsonNode root = mapper.readTree(response.body());
            List<Subscriber> subscribers = new ArrayList<>();
            JsonNode listeners = root.path("listeners");
            if (listeners.isArray()) {
                for (JsonNode l : listeners) {
                    // 监听者只有 IP:端口，应用归属由影响面模块映射，映射缺失标未知（R-IMP-02）
                    String address = text(l, "ip") + ":" + text(l, "port");
                    subscribers.add(new Subscriber(address, null));
                }
            }
            return new SubscriberSnapshot(subscribers, true, Instant.now());
        } catch (IOException e) {
            throw new NcgException(NcgErrorCode.CON_502_001, "nacos listeners parse failed: " + e.getMessage());
        }
    }

    @Override
    public ConnectionReport test(EnvId envId) {
        Optional<NacosConnection> found = registry.find(envId);
        if (found.isEmpty()) {
            return new ConnectionReport(false, false, 0, Instant.now(), "no endpoint configured for env " + envId);
        }
        NacosConnection conn = found.get();
        Instant start = Instant.now();
        try {
            HttpResponse<String> response = get(conn, nsPath("/operator/metrics"), TEST_TIMEOUT);
            long latency = Duration.between(start, Instant.now()).toMillis();
            if (response.statusCode() == 200) {
                return new ConnectionReport(true, true, latency, Instant.now(), "ok");
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return new ConnectionReport(true, false, latency, Instant.now(), "http " + response.statusCode());
            }
            return new ConnectionReport(true, true, latency, Instant.now(), "http " + response.statusCode());
        } catch (IOException e) {
            return new ConnectionReport(false, false, Duration.between(start, Instant.now()).toMillis(),
                    Instant.now(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ConnectionReport(false, false, 0, Instant.now(), "interrupted");
        }
    }

    // ---------- HTTP 细节 ----------

    private NacosConnection connection(EnvId envId) {
        return registry.find(envId).orElseThrow(() -> new NcgException(NcgErrorCode.REL_503_001,
                "no Nacos endpoint configured for env " + envId.value()));
    }

    private String csPath(String suffix) {
        return "/nacos/v1/cs" + suffix;
    }

    private String nsPath(String suffix) {
        return "/nacos/v1/ns" + suffix;
    }

    private String refQuery(ConfigRef ref) {
        return "?dataId=" + enc(ref.dataId()) + "&group=" + enc(ref.group()) + "&tenant=" + enc(ref.namespace());
    }

    private String publishForm(ConfigRef ref, String content, String expectedMd5) {
        StringBuilder form = new StringBuilder()
                .append("dataId=").append(enc(ref.dataId()))
                .append("&group=").append(enc(ref.group()))
                .append("&tenant=").append(enc(ref.namespace()))
                .append("&content=").append(enc(content));
        if (expectedMd5 != null && !expectedMd5.isBlank()) {
            form.append("&casMd5=").append(enc(expectedMd5));
        }
        return form.toString();
    }

    private String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> get(NacosConnection conn, String pathAndQuery, Duration timeout)
            throws IOException, InterruptedException {
        URI uri = URI.create(conn.endpoint().toString() + withToken(conn, pathAndQuery));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(NacosConnection conn, String path, String form, Duration timeout)
            throws IOException, InterruptedException {
        String body = conn.token() == null || conn.token().isBlank()
                ? form
                : form + "&accessToken=" + enc(conn.token());
        URI uri = URI.create(conn.endpoint().toString() + path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** GET 时把 accessToken 拼到查询串（Nacos OpenAPI 鉴权）。 */
    private String withToken(NacosConnection conn, String pathAndQuery) {
        if (conn.token() == null || conn.token().isBlank()) {
            return pathAndQuery;
        }
        String sep = pathAndQuery.contains("?") ? "&" : "?";
        return pathAndQuery + sep + "accessToken=" + enc(conn.token());
    }

    /** 只读操作最多重试 1 次（§8.4）；写操作从不走这里。 */
    private HttpResponse<String> sendWithOneRetry(IOCall call) {
        try {
            return call.run();
        } catch (IOException first) {
            try {
                return call.run();
            } catch (IOException second) {
                throw new NcgException(NcgErrorCode.REL_503_001,
                        "nacos unreachable: " + second.getMessage(), Map.of(), second);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NcgException(NcgErrorCode.REL_503_001, "interrupted", Map.of(), e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NcgException(NcgErrorCode.REL_503_001, "interrupted", Map.of(), e);
        }
    }

    @FunctionalInterface
    private interface IOCall {
        HttpResponse<String> run() throws IOException, InterruptedException;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(value));
        } catch (NumberFormatException e) {
            try {
                return Instant.parse(value);
            } catch (Exception ignored) {
                return Instant.EPOCH;
            }
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() > 200 ? s.substring(0, 200) + "…" : s);
    }
}
