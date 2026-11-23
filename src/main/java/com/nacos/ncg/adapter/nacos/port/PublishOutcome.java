package com.nacos.ncg.adapter.nacos.port;

import java.util.Collections;
import java.util.Map;

/**
 * 发布三态结果（详细设计 v1.1 §8.1-8.2 / C-ARC-05）：
 * verified=写入成功且读回 MD5 一致；rejected=写入被拒（四种原因）；
 * indeterminate=写入已发出但结果不确定（超时/读回失败），禁止自动重试，转人工对账。
 */
public sealed interface PublishOutcome permits PublishOutcome.Verified, PublishOutcome.Rejected, PublishOutcome.Indeterminate {

    /** 适配器回执，脱敏后存 ncg_release_record.receipt（C-ARC-05）。 */
    Map<String, Object> receipt();

    record Verified(String expectedMd5, String readbackMd5, Map<String, Object> receipt) implements PublishOutcome {
        public Verified {
            receipt = receipt == null ? Collections.emptyMap() : receipt;
        }
    }

    record Rejected(RejectReason reason, String detail, Map<String, Object> receipt) implements PublishOutcome {
        public Rejected {
            if (reason == null) {
                throw new IllegalArgumentException("reason is required");
            }
            receipt = receipt == null ? Collections.emptyMap() : receipt;
        }
    }

    record Indeterminate(String detail, Map<String, Object> receipt) implements PublishOutcome {
        public Indeterminate {
            receipt = receipt == null ? Collections.emptyMap() : receipt;
        }
    }
}
