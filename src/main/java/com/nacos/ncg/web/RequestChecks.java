package com.nacos.ncg.web;

import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求体必填字段校验（联调 D14 / F1）：空 JSON 对象 {} 或缺必填字段必须
 * 400 CR-400-001，不得让 null 流到查询/枚举解析/落库路径再炸 500。
 * 只拦「缺了会 500」的字段；带既有业务文案的校验（title/reason/permission
 * 归属等）仍留在服务层，保证既有错误路径文案零回归。
 */
final class RequestChecks {

    private RequestChecks() { }

    /** 交替传 name/value：require("appId", appId, "envId", envId, ...)；任一为空即 CR-400-001 并列出缺失字段。 */
    static void require(String... nameValuePairs) {
        List<String> missing = new ArrayList<>();
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            String value = nameValuePairs[i + 1];
            if (value == null || value.isBlank()) {
                missing.add(nameValuePairs[i]);
            }
        }
        if (!missing.isEmpty()) {
            throw new NcgException(NcgErrorCode.CR_400_001, "缺少必填字段：" + String.join("、", missing));
        }
    }
}
