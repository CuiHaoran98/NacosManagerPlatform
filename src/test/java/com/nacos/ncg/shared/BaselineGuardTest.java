package com.nacos.ncg.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BaselineGuardTest {

    private final BaselineGuard guard = new BaselineGuard();

    @Test
    void okWhenMd5MatchesIgnoreCase() {
        assertEquals(BaselineGuard.Check.OK, guard.precheck("abc123", "ABC123"));
    }

    @Test
    void driftedWhenMd5Differs() {
        assertEquals(BaselineGuard.Check.DRIFTED, guard.precheck("abc123", "def456"));
    }

    @Test
    void notFrozenWhenBaselineMissing() {
        assertEquals(BaselineGuard.Check.NOT_FROZEN, guard.precheck(null, "abc123"));
    }

    @Test
    void readbackVerifiedComparesIgnoreCase() {
        assertTrue(guard.readbackVerified("ABC", "abc"));
        assertFalse(guard.readbackVerified("abc", "abd"));
    }
}
