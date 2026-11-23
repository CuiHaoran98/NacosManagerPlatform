package com.nacos.ncg.shared.json;

/**
 * jsonb 列的包装类型（§5：impact_snapshot / risk_factors / before_masked / after_masked /
 * payload_masked / diff_summary / nacos_receipt 均为 jsonb）。
 * Spring Data JDBC 的转换器按值类型选择，用独立包装类型避免影响普通 String 列。
 */
public record Jsonb(String json) {

    public static Jsonb of(String json) {
        return new Jsonb(json);
    }

    /** 空对象占位（NOT NULL 列用）。 */
    public static Jsonb empty() {
        return new Jsonb("{}");
    }

    public boolean isEmpty() {
        return json == null || json.isBlank();
    }
}
