package com.nacos.ncg.adapter.nacos.fake;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nacos.ncg.shared.ContentDigest;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Nacos 假件（profile local / 测试用）：内存实现，与真实实现返回同构结果（§8.6 契约测试要求）。
 * 支持注入故障模式以覆盖契约场景：成功、基线冲突、认证失败、不可达、超时（indeterminate）、
 * 格式错误、历史缺口、订阅者为空、订阅者映射缺失。
 */
public class FakeNacosConfigAdapter implements NacosConfigPort {

    /** 故障注入：NONE=正常。 */
    public enum FailureMode { NONE, AUTH, UNAVAILABLE, INVALID, INDETERMINATE }

    private final Map<String, Map<ConfigRef, Stored>> envs = new ConcurrentHashMap<>();
    private final Map<String, FailureMode> failureByEnv = new ConcurrentHashMap<>();
    private final Map<String, Boolean> envDown = new ConcurrentHashMap<>();
    private final Map<String, PublishOutcome.Verified> idempotency = new ConcurrentHashMap<>();
    private final AtomicLong historySeq = new AtomicLong();

    /** 可选磁盘持久化：非空=每次变更落盘、启动恢复（进程重启内容不丢，D16）；null=纯内存（单测默认）。 */
    private final Path persistPath;

    private record Stored(String content, String md5, List<HistoryEntry> history, List<Subscriber> subscribers) {
    }

    // ---------- 磁盘持久化（fake store 快照，JSON） ----------

    private record PersistedSub(String address, String appName) {
    }

    private record PersistedHistory(String historyId, String namespace, String group, String dataId, String md5,
                                    String modifiedAt, String modifiedBy, String ops) {
    }

    private record PersistedRef(String content, String md5, List<PersistedHistory> history, List<PersistedSub> subscribers) {
    }

    private record PersistedState(long historySeq, Map<String, Map<String, PersistedRef>> envs) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public FakeNacosConfigAdapter() {
        this(null);
    }

    /** 构造时若路径已存在则从磁盘恢复既有内容（文件缺失=空启动）。 */
    public FakeNacosConfigAdapter(Path persistPath) {
        this.persistPath = persistPath;
        if (persistPath != null) {
            restore();
        }
    }

    /** 显式落盘（POST /api/v1/_fixture/persist）。返回是否已写（persist-path 未配置=false）。 */
    public boolean persistNow() {
        return persist();
    }

    public Path persistPath() {
        return persistPath;
    }

    private boolean persist() {
        if (persistPath == null) {
            return false;
        }
        try {
            Map<String, Map<String, PersistedRef>> out = new java.util.LinkedHashMap<>();
            envs.forEach((envCode, refs) -> {
                Map<String, PersistedRef> perRef = new java.util.LinkedHashMap<>();
                refs.forEach((ref, s) -> {
                    List<PersistedHistory> h = s.history().stream()
                            .map(e -> new PersistedHistory(e.historyId(), e.namespace(), e.group(), e.dataId(),
                                    e.md5(), e.modifiedAt().toString(), e.modifiedBy(), e.ops()))
                            .toList();
                    List<PersistedSub> subs = s.subscribers().stream()
                            .map(x -> new PersistedSub(x.address(), x.appName()))
                            .toList();
                    perRef.put(ref.toString(), new PersistedRef(s.content(), s.md5(), h, subs));
                });
                out.put(envCode, perRef);
            });
            PersistedState state = new PersistedState(historySeq.get(), out);
            Path tmp = persistPath.resolveSibling(persistPath.getFileName() + ".tmp");
            Files.createDirectories(persistPath.toAbsolutePath().getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), state);
            try {
                Files.move(tmp, persistPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException moveEx) {
                Files.move(tmp, persistPath, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            System.err.println("[fake-nacos] persist failed: " + e); // 落盘失败不阻断业务写路径
            return false;
        }
    }

    /** 启动恢复：文件缺失=空启动；JSON 非法=stderr 告警后空启动（不阻断启动）。 */
    private void restore() {
        if (persistPath == null || !Files.exists(persistPath)) {
            return;
        }
        try {
            PersistedState state = JSON.readValue(persistPath.toFile(), PersistedState.class);
            historySeq.set(state.historySeq());
            state.envs().forEach((envCode, perRef) -> {
                Map<ConfigRef, Stored> map = store(new EnvId(envCode));
                perRef.forEach((refKey, r) -> {
                    String[] parts = refKey.split("/", 3);
                    ConfigRef ref = new ConfigRef(parts[0], parts[1], parts[2]);
                    List<HistoryEntry> h = new ArrayList<>(r.history().stream()
                            .map(e -> new HistoryEntry(e.historyId(), e.namespace(), e.group(), e.dataId(),
                                    e.md5(), Instant.parse(e.modifiedAt()), e.modifiedBy(), e.ops()))
                            .toList());
                    List<Subscriber> subs = new ArrayList<>(r.subscribers().stream()
                            .map(x -> new Subscriber(x.address(), x.appName()))
                            .toList());
                    map.put(ref, new Stored(r.content(), r.md5(), h, subs));
                });
            });
        } catch (Exception e) {
            System.err.println("[fake-nacos] restore failed, start empty: " + e);
        }
    }

    // ---------- 测试装配 API（仅假件提供） ----------

    /** 预置配置内容（不产生历史记录，用于建基线）。 */
    public void seed(EnvId envId, ConfigRef ref, String content) {
        store(envId).put(ref, new Stored(content, ContentDigest.md5Hex(content), new ArrayList<>(), new ArrayList<>()));
        persist();
    }

    /** 预置一条外部（直连）历史记录，模拟绕过平台的修改。 */
    public void seedExternalChange(EnvId envId, ConfigRef ref, String content, String operator) {
        Map<ConfigRef, Stored> map = store(envId);
        Stored prev = map.get(ref);
        List<HistoryEntry> history = prev == null ? new ArrayList<>() : new ArrayList<>(prev.history());
        String md5 = ContentDigest.md5Hex(content);
        history.add(new HistoryEntry(nextHistoryId(), ref.namespace(), ref.group(), ref.dataId(),
                md5, Instant.now(), operator, "publish"));
        map.put(ref, new Stored(content, md5, history, prev == null ? new ArrayList<>() : prev.subscribers()));
        persist();
    }

    public void setSubscribers(EnvId envId, ConfigRef ref, List<Subscriber> subscribers) {
        Map<ConfigRef, Stored> map = store(envId);
        Stored prev = map.get(ref);
        String content = prev == null ? "" : prev.content();
        String md5 = prev == null ? ContentDigest.md5Hex("") : prev.md5();
        List<HistoryEntry> history = prev == null ? new ArrayList<>() : prev.history();
        map.put(ref, new Stored(content, md5, history, new ArrayList<>(subscribers)));
        persist();
    }

    /** 按环境注入故障模式（契约测试场景切换）。 */
    public void setFailureMode(EnvId envId, FailureMode mode) {
        failureByEnv.put(envId.value(), mode);
    }

    /** 模拟实例不可达（连接测试失败）。 */
    public void setEnvDown(EnvId envId, boolean down) {
        envDown.put(envId.value(), down);
    }

    // ---------- NacosConfigPort ----------

    @Override
    public Snapshot read(EnvId envId, ConfigRef ref) {
        ensureUp(envId);
        Stored stored = store(envId).get(ref);
        if (stored == null) {
            throw new NcgException(NcgErrorCode.SYS_404_001, "config not found: " + ref);
        }
        return new Snapshot(stored.content(), stored.md5(), Instant.now(), Snapshot.Source.LIVE);
    }

    @Override
    public PublishOutcome publish(EnvId envId, ConfigRef ref, String content, String expectedMd5, IdempotencyKey key) {
        FailureMode mode = failureByEnv.getOrDefault(envId.value(), FailureMode.NONE);
        Map<String, Object> receipt = Map.of("fake", true, "key", key.value());

        // 幂等键重放：同键返回既有发布结果（R-REL-04）
        PublishOutcome.Verified replay = idempotency.get(key.value());
        if (replay != null) {
            return replay;
        }

        // 实例不可达→rejected(unavailable)（ch8 契约，与真件一致，不抛异常）
        if (Boolean.TRUE.equals(envDown.get(envId.value()))) {
            return new PublishOutcome.Rejected(RejectReason.UNAVAILABLE, "simulated unreachable", receipt);
        }

        return switch (mode) {
            case AUTH -> new PublishOutcome.Rejected(RejectReason.AUTH, "simulated auth failure", receipt);
            case UNAVAILABLE -> new PublishOutcome.Rejected(RejectReason.UNAVAILABLE, "simulated unreachable", receipt);
            case INVALID -> new PublishOutcome.Rejected(RejectReason.INVALID, "simulated invalid content", receipt);
            case INDETERMINATE -> {
                // 写入已发出但读回失败：内容可能已生效，返回不确定（禁止自动重试）
                applyWrite(envId, ref, content, key);
                yield new PublishOutcome.Indeterminate("simulated readback failure", receipt);
            }
            case NONE -> {
                ensureUp(envId);
                Stored stored = store(envId).get(ref);
                // 写前基线比对（CAS 语义）
                if (stored != null && expectedMd5 != null && !expectedMd5.equals(stored.md5())) {
                    yield new PublishOutcome.Rejected(RejectReason.BASELINE,
                            "baseline mismatch: expected=" + expectedMd5 + " current=" + stored.md5(), receipt);
                }
                applyWrite(envId, ref, content, key);
                String md5 = ContentDigest.md5Hex(content);
                // 写后读回比对
                Stored readback = store(envId).get(ref);
                if (readback == null || !md5.equals(readback.md5())) {
                    yield new PublishOutcome.Indeterminate("readback mismatch", receipt);
                }
                PublishOutcome.Verified verified = new PublishOutcome.Verified(expectedMd5, md5, receipt);
                idempotency.put(key.value(), verified);
                yield verified;
            }
        };
    }

    @Override
    public HistoryPage history(EnvId envId, ConfigRef ref, Instant since, String cursor) {
        ensureUp(envId);
        // ref=null = 整个环境（直连扫描场景），聚合全部配置的历史；ConcurrentHashMap.get(null) 会 NPE
        List<HistoryEntry> all;
        if (ref == null) {
            all = store(envId).values().stream()
                    .flatMap(s -> s.history().stream())
                    .sorted(java.util.Comparator.comparing(HistoryEntry::modifiedAt))
                    .toList();
        } else {
            Stored stored = store(envId).get(ref);
            all = stored == null ? List.of() : stored.history();
        }
        int from = 0;
        if (cursor != null && !cursor.isBlank()) {
            try {
                from = Integer.parseInt(cursor);
            } catch (NumberFormatException ignore) {
                from = 0; // 非数值游标（如 historyId）从头读取，调用方按 historyId 去重
            }
        }
        List<HistoryEntry> page = new ArrayList<>();
        for (HistoryEntry e : all) {
            if (since != null && e.modifiedAt().isBefore(since)) {
                continue;
            }
            page.add(e);
        }
        List<HistoryEntry> slice = page.stream().skip(from).limit(50).toList();
        int next = from + slice.size();
        boolean complete = next >= page.size();
        return new HistoryPage(slice, complete ? null : String.valueOf(next), complete, Instant.now());
    }

    @Override
    public SubscriberSnapshot subscribers(EnvId envId, ConfigRef ref) {
        ensureUp(envId);
        Stored stored = store(envId).get(ref);
        List<Subscriber> list = stored == null ? List.<Subscriber>of() : stored.subscribers();
        return new SubscriberSnapshot(list, true, Instant.now());
    }

    @Override
    public ConnectionReport test(EnvId envId) {
        if (Boolean.TRUE.equals(envDown.get(envId.value()))) {
            return new ConnectionReport(false, false, 0, Instant.now(), "simulated unreachable");
        }
        FailureMode mode = failureByEnv.getOrDefault(envId.value(), FailureMode.NONE);
        if (mode == FailureMode.AUTH) {
            return new ConnectionReport(true, false, 5, Instant.now(), "simulated auth failure");
        }
        return new ConnectionReport(true, true, 5, Instant.now(), "ok");
    }

    // ---------- 内部 ----------

    private void applyWrite(EnvId envId, ConfigRef ref, String content, IdempotencyKey key) {
        Map<ConfigRef, Stored> map = store(envId);
        Stored prev = map.get(ref);
        List<HistoryEntry> history = prev == null ? new ArrayList<>() : new ArrayList<>(prev.history());
        String md5 = ContentDigest.md5Hex(content);
        history.add(new HistoryEntry(nextHistoryId(), ref.namespace(), ref.group(), ref.dataId(),
                md5, Instant.now(), key.changeNo(), "publish"));
        map.put(ref, new Stored(content, md5, history, prev == null ? new ArrayList<>() : prev.subscribers()));
        persist();
    }

    private void ensureUp(EnvId envId) {
        if (Boolean.TRUE.equals(envDown.get(envId.value()))) {
            throw new NcgException(NcgErrorCode.REL_503_001, "env unreachable: " + envId);
        }
    }

    private Map<ConfigRef, Stored> store(EnvId envId) {
        return envs.computeIfAbsent(envId.value(), k -> new ConcurrentHashMap<>());
    }

    private String nextHistoryId() {
        return "H" + historySeq.incrementAndGet();
    }
}
