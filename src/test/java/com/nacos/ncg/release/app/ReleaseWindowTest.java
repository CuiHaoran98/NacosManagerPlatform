package com.nacos.ncg.release.app;

import com.nacos.ncg.catalog.domain.Env;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/** 发布窗口（R-REL-01/R-REL-05）：空=全天、窗口内放行、窗口外阻断 REL-422-001。 */
class ReleaseWindowTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private Env envWith(LocalTime start, LocalTime end) {
        Env env = new Env();
        env.setEnvCode("dev");
        env.setReleaseWindowStart(start);
        env.setReleaseWindowEnd(end);
        return env;
    }

    @Test
    void allDayWhenBothNull() {
        assertDoesNotThrow(() -> ReleaseService.checkReleaseWindow(envWith(null, null)));
    }

    @Test
    void passesInsideWindow() {
        LocalTime now = LocalTime.now(ZONE);
        assertDoesNotThrow(() -> ReleaseService.checkReleaseWindow(
                envWith(now.minusHours(1), now.plusHours(1))));
    }

    @Test
    void blockedOutsideWindowWithRel422() {
        LocalTime now = LocalTime.now(ZONE);
        NcgException ex = assertThrows(NcgException.class, () -> ReleaseService.checkReleaseWindow(
                envWith(now.plusHours(2), now.plusHours(3))));
        assertEquals(NcgErrorCode.REL_422_001.code(), ex.errorCode().code());
    }

    @Test
    void midnightWrapWindowAdmitsLateNight() {
        // 23:00–01:00 跨午夜窗口：00:30 在窗口内
        Env env = envWith(LocalTime.of(23, 0), LocalTime.of(1, 0));
        LocalTime now = LocalTime.now(ZONE);
        boolean lateNight = now.isAfter(LocalTime.of(23, 0)) || now.isBefore(LocalTime.of(1, 0));
        if (lateNight) {
            assertDoesNotThrow(() -> ReleaseService.checkReleaseWindow(env));
        } else {
            NcgException ex = assertThrows(NcgException.class, () -> ReleaseService.checkReleaseWindow(env));
            assertEquals(NcgErrorCode.REL_422_001.code(), ex.errorCode().code());
        }
    }
}
