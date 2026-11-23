package com.nacos.ncg.adapter.nacos.port;

/**
 * 发布幂等键（详细设计 v1.1 §4.3 / C-ARC-20）：release_key = changeNo + ':' + targetVersion。
 * 同键重放必须返回既有记录，不得重复写 Nacos（R-REL-04）。
 */
public record IdempotencyKey(String changeNo, String targetVersion) {

    public IdempotencyKey {
        if (changeNo == null || changeNo.isBlank() || targetVersion == null || targetVersion.isBlank()) {
            throw new IllegalArgumentException("IdempotencyKey requires changeNo and targetVersion");
        }
    }

    /** 规范化键值，与 ncg_release_record.release_key 一致。 */
    public String value() {
        return changeNo + ":" + targetVersion;
    }
}
