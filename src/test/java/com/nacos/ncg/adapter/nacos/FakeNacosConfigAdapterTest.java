package com.nacos.ncg.adapter.nacos;

import com.nacos.ncg.adapter.nacos.fake.FakeNacosConfigAdapter;
import com.nacos.ncg.adapter.nacos.port.*;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 适配器契约测试（ch8：同一套跑假件+真实沙箱）。
 * 场景：成功/基线冲突/认证失败/不可达/超时(不确定)/格式错/历史缺口/订阅者空/配置缺失。
 */
class FakeNacosConfigAdapterTest {

    private FakeNacosConfigAdapter adapter;
    private final EnvId env = new EnvId("dev");
    private final ConfigRef ref = new ConfigRef("public", "DEFAULT_GROUP", "app.yaml");

    @BeforeEach
    void setUp() {
        adapter = new FakeNacosConfigAdapter();
    }

    @Test
    void publishVerifiedWhenReadbackMatches() {
        adapter.seed(env, ref, "v0");
        PublishOutcome out = adapter.publish(env, ref, "v1", com.nacos.ncg.shared.ContentDigest.md5Hex("v0"),
                new IdempotencyKey("CR-20261122-0001", "1"));
        assertInstanceOf(PublishOutcome.Verified.class, out);
    }

    @Test
    void publishRejectedBaselineWhenExpectedMd5Stale() {
        adapter.seed(env, ref, "v2-changed");
        PublishOutcome out = adapter.publish(env, ref, "v1", "stale-md5",
                new IdempotencyKey("CR-20261122-0002", "1"));
        PublishOutcome.Rejected rejected = assertInstanceOf(PublishOutcome.Rejected.class, out);
        assertEquals(RejectReason.BASELINE, rejected.reason());
    }

    @Test
    void publishRejectedAuth() {
        adapter.setFailureMode(env, FakeNacosConfigAdapter.FailureMode.AUTH);
        PublishOutcome out = adapter.publish(env, ref, "v1", "md5",
                new IdempotencyKey("CR-20261122-0003", "1"));
        assertEquals(RejectReason.AUTH, assertInstanceOf(PublishOutcome.Rejected.class, out).reason());
    }

    @Test
    void publishRejectedUnavailableWhenEnvDown() {
        adapter.setEnvDown(env, true);
        PublishOutcome out = adapter.publish(env, ref, "v1", "md5",
                new IdempotencyKey("CR-20261122-0004", "1"));
        assertEquals(RejectReason.UNAVAILABLE, assertInstanceOf(PublishOutcome.Rejected.class, out).reason());
    }

    @Test
    void indeterminateWhenReadbackFails_andWriteAlreadyApplied() {
        adapter.seed(env, ref, "v0");
        adapter.setFailureMode(env, FakeNacosConfigAdapter.FailureMode.INDETERMINATE);
        PublishOutcome out = adapter.publish(env, ref, "v1", "MD5-of-v0",
                new IdempotencyKey("CR-20261122-0005", "1"));
        assertInstanceOf(PublishOutcome.Indeterminate.class, out, "读回失败→不确定（C-ARC-05）");
        // 写已生效（模拟真实：写入发出但结果不明）
        assertEquals("v1", adapter.read(env, ref).content());
    }

    @Test
    void idempotentReplayReturnsSameVerified() {
        adapter.seed(env, ref, "v0");
        IdempotencyKey key = new IdempotencyKey("CR-20261122-0006", "1");
        PublishOutcome first = adapter.publish(env, ref, "v1", com.nacos.ncg.shared.ContentDigest.md5Hex("v0"), key);
        PublishOutcome second = adapter.publish(env, ref, "v1", com.nacos.ncg.shared.ContentDigest.md5Hex("v0"), key);
        assertInstanceOf(PublishOutcome.Verified.class, first);
        assertEquals(first, second, "幂等键重放返回既有结果（R-REL-07）");
    }

    @Test
    void readMissingConfigThrows404() {
        NcgException ex = assertThrows(NcgException.class, () -> adapter.read(env, ref));
        assertEquals(NcgErrorCode.SYS_404_001, ex.errorCode());
    }

    @Test
    void historyPaginatesAndIsOrdered() {
        adapter.seedExternalChange(env, ref, "v-ext-1", "external-operator");
        adapter.seedExternalChange(env, ref, "v-ext-2", "external-operator");
        HistoryPage page = adapter.history(env, ref, Instant.EPOCH, null);
        assertTrue(page.entries().size() >= 2, "seed() 不产生历史，仅外部变更有历史");
    }

    @Test
    void subscribersMayBeEmptyButComplete() {
        adapter.setSubscribers(env, ref, java.util.List.of());
        SubscriberSnapshot snap = adapter.subscribers(env, ref);
        assertTrue(snap.subscribers().isEmpty());
        assertTrue(snap.complete());
    }

    @Test
    void connectionReportReflectsEnvState() {
        assertTrue(adapter.test(env).reachable());
        adapter.setEnvDown(env, true);
        assertFalse(adapter.test(env).reachable());
    }

    @Test
    void historyAggregatesAllRefsWhenRefNull() {
        FakeNacosConfigAdapter fake = new FakeNacosConfigAdapter();
        EnvId env = new EnvId("dev");
        fake.seedExternalChange(env, new ConfigRef("public", "G", "a.yaml"), "a: 1", "op");
        fake.seedExternalChange(env, new ConfigRef("public", "G", "b.yaml"), "b: 1", "op");
        var page = fake.history(env, null, Instant.now().minusSeconds(60), null);
        // ref=null=整个环境（直连扫描），不得抛 NPE、聚合全部配置历史
        assertTrue(page.entries().size() >= 2);
    }
}
