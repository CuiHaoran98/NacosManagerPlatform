package com.nacos.ncg.web;

import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 联调 D14 回归：空 JSON 对象 {} / 缺必填字段必须 400 CR-400-001，
 * 不得让 null 流到查询/枚举/落库路径再炸 500 SYS-500-001。
 * 控制器依赖全 null：断言必须在触达依赖前就抛出（纯校验路径单测）。
 */
class EmptyBodyValidationTest {

    private static final Principal WHO = () -> "tester";

    @Test
    void changeOpenRejectsEmptyBody() {
        ChangeController c = new ChangeController(null, null, null, null);
        ChangeController.OpenRequest req = new ChangeController.OpenRequest(
                null, null, null, null, null, null, null, null, null, null, null);
        NcgException ex = assertThrows(NcgException.class, () -> c.open(WHO, req));
        assertEquals(NcgErrorCode.CR_400_001, ex.errorCode());
    }

    @Test
    void grantRejectsEmptyBody() {
        UserController c = new UserController(null, null, null, null);
        UserController.GrantRequest req = new UserController.GrantRequest(
                null, null, null, null, null, null, null);
        NcgException ex = assertThrows(NcgException.class, () -> c.grant(req, WHO));
        assertEquals(NcgErrorCode.CR_400_001, ex.errorCode());
    }

    @Test
    void grantRejectsInvalidPermission() {
        UserController c = new UserController(null, null, null, null);
        UserController.GrantRequest req = new UserController.GrantRequest(
                "USER", "u1", "a1", "e1", "NOT_A_POINT", null, null);
        NcgException ex = assertThrows(NcgException.class, () -> c.grant(req, WHO));
        assertEquals(NcgErrorCode.CR_400_001, ex.errorCode());
        assertTrue(ex.getMessage().contains("permission invalid"));
    }

    @Test
    void instanceRegisterRejectsEmptyBody() {
        CatalogController c = new CatalogController(null, null, null, null);
        CatalogController.InstanceRegisterRequest req =
                new CatalogController.InstanceRegisterRequest(null, null, null);
        NcgException ex = assertThrows(NcgException.class, () -> c.register(WHO, req));
        assertEquals(NcgErrorCode.CR_400_001, ex.errorCode());
    }

    @Test
    void mappingCreateRejectsEmptyBody() {
        MappingAdminController c = new MappingAdminController(null, null, null, null, null, null, null);
        MappingAdminController.MappingRequest req = new MappingAdminController.MappingRequest(
                null, null, null, null, null, null, null, null, null, null);
        NcgException ex = assertThrows(NcgException.class, () -> c.createMapping(req, WHO));
        assertEquals(NcgErrorCode.CR_400_001, ex.errorCode());
    }

    @Test
    void methodNotSupportedMapsTo400() {
        ApiExceptionHandler h = new ApiExceptionHandler();
        ResponseEntity<ApiError> resp =
                h.handleBadRequest(new HttpRequestMethodNotSupportedException("POST"));
        assertEquals(400, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals("CR-400-001", resp.getBody().code());
    }
}
