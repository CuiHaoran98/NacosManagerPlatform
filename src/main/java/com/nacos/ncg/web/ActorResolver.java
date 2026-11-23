package com.nacos.ncg.web;

import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;

import java.security.Principal;

/**
 * 操作者解析（R-AUTH-10 fail-closed）：无会话即拒绝（AUTH-401-001），
 * 绝不匿名代入。权限点校验在各服务内完成（职责分离在 PermissionPolicy）。
 */
public final class ActorResolver {

    private ActorResolver() { }

    public static String requireActor(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new NcgException(NcgErrorCode.AUTH_401_001, "authentication required (R-AUTH-10)");
        }
        return principal.getName();
    }
}
