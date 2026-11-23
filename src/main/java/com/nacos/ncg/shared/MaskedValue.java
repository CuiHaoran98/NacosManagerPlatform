package com.nacos.ncg.shared;

/**
 * 脱敏值（详细设计 v1.1 §4 值对象 / C-ARC-12、R-AUD-05）。
 * 敏感字段在审计、Diff、界面、通知中一律以脱敏形态出现；明文只经
 * POST /configs/{mappingId}/sensitive-view（需 VIEW_SENSITIVE+原因≥10 字+审计）。
 *
 * @param masked     脱敏后的显示值（如 "jdbc:****@host/db"）
 * @param sensitive  是否敏感（非敏感时 masked 即原值）
 */
public record MaskedValue(String masked, boolean sensitive) {

    private static final String MASK = "****";

    /** 按类型粗脱敏：长值截断保留首尾，短值整体掩蔽。 */
    public static MaskedValue of(String raw, boolean sensitive) {
        if (!sensitive) {
            return new MaskedValue(raw, false);
        }
        if (raw == null || raw.length() <= 8) {
            return new MaskedValue(MASK, true);
        }
        return new MaskedValue(raw.substring(0, 2) + MASK + raw.substring(raw.length() - 2), true);
    }
}
