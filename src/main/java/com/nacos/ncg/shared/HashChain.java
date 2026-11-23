package com.nacos.ncg.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * 审计哈希链（详细设计 v1.1 §5.5 / spec R-AUD-03、C-ARC-11）。
 * 规则：
 * 1) 按 UTC 日分链，seq 当日从 1 递增；
 * 2) 当日首条 prev_hash = 64 个 '0'；
 * 3) event_hash = SHA-256(prev_hash + '|' + canonical_json(event))，canonical_json 不含 event_hash 自身；
 * 4) canonical_json：键名字典序、UTF-8、null 值键省略、时间用 ISO-8601 UTC。
 */
public final class HashChain {

    public static final String GENESIS = "0".repeat(64);

    /** 计算事件哈希。fields 传入事件的规范化字段（不含 event_hash）。 */
    public static String eventHash(String prevHash, Map<String, String> fields) {
        String canonical = canonicalJson(fields);
        return sha256Hex(prevHash + "|" + canonical);
    }

    /** 规范化 JSON：键名字典序，key:value 拼接，值内特殊字符转义；null 键省略。 */
    static String canonicalJson(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : new TreeMap<>(fields).entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append('"').append(':')
              .append('"').append(escape(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                  .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 审计事件字段拼装辅助（供 AuditWriter 使用）。 */
    public static Map<String, String> eventFields(
            String chainDate, long seq, Instant occurredAt,
            String actorType, String actorId, String action,
            String objectType, String objectId, String result, String traceId) {
        Map<String, String> m = new TreeMap<>();
        m.put("chain_date", chainDate);
        m.put("seq", String.valueOf(seq));
        m.put("occurred_at", occurredAt.toString());
        m.put("actor_type", actorType);
        m.put("actor_id", actorId);
        m.put("action", action);
        m.put("object_type", objectType);
        m.put("object_id", objectId);
        m.put("result", result);
        m.put("trace_id", traceId);
        return m;
    }
}
