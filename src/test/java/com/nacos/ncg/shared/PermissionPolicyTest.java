package com.nacos.ncg.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PermissionPolicyTest {

    private final PermissionPolicy policy = new PermissionPolicy();

    @Test
    void submitterCannotApproveOwnChange() {
        assertEquals(PermissionPolicy.Decision.DENY_SAME_ACTOR,
                policy.canApprove("alice", "alice", "bob", false, "carol", false));
    }

    @Test
    void highRiskRequiresOwnerOrProxy() {
        assertEquals(PermissionPolicy.Decision.DENY_NOT_OWNER_FOR_HIGH,
                policy.canApprove("dave", "alice", "bob", true, "carol", false));
        assertEquals(PermissionPolicy.Decision.ALLOW,
                policy.canApprove("carol", "alice", "bob", true, "carol", false));
    }

    @Test
    void publisherCannotApproveUnlessOwner() {
        assertEquals(PermissionPolicy.Decision.DENY_SEPARATION,
                policy.canApprove("bob", "alice", "bob", false, "carol", false));
        assertEquals(PermissionPolicy.Decision.ALLOW,
                policy.canApprove("bob", "alice", "bob", false, "bob", false));
    }

    @Test
    void normalApproveAllowed() {
        assertEquals(PermissionPolicy.Decision.ALLOW,
                policy.canApprove("dave", "alice", "bob", false, "carol", false));
    }

    @Test
    void publisherSeparation() {
        assertTrue(policy.canPublish("alice", "bob", "carol"));
        assertFalse(policy.canPublish("alice", "alice", "carol"));
        assertTrue(policy.canPublish("carol", "carol", "carol"), "发布人是负责人可发布");
    }
}
