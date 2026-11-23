package com.nacos.ncg.adapter.nacos;

import com.nacos.ncg.adapter.nacos.fake.FakeNacosConfigAdapter;
import com.nacos.ncg.adapter.nacos.port.*;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * fake store 磁盘持久化测试（D16）：进程重启内容不丢。
 * 场景：round-trip（内容/历史/订阅者）/ 文件缺失空启动 / persist-path 未配置。
 */
class FakeNacosPersistTest {

    private final EnvId env = new EnvId("dev");
    private final ConfigRef ref = new ConfigRef("public", "DEFAULT_GROUP", "app.yaml");

    @Test
    void roundTripRestoresContentHistoryAndSubscribers(@TempDir Path tmp) {
        Path file = tmp.resolve("fake-store.json");
        FakeNacosConfigAdapter a1 = new FakeNacosConfigAdapter(file);
        a1.seed(env, ref, "v0");
        PublishOutcome out = a1.publish(env, ref, "v1", null,
                new IdempotencyKey("CR-20261123-0001", "1"));
        assertInstanceOf(PublishOutcome.Verified.class, out);
        a1.setSubscribers(env, ref, List.of(new Subscriber("10.0.0.1:8080", "order-app")));
        a1.persistNow();

        // 模拟进程重启：同路径新实例从磁盘恢复
        FakeNacosConfigAdapter a2 = new FakeNacosConfigAdapter(file);
        assertEquals("v1", a2.read(env, ref).content(), "内容恢复");
        assertFalse(a2.history(env, ref, null, null).entries().isEmpty(), "历史恢复");
        assertEquals(1, a2.subscribers(env, ref).subscribers().size(), "订阅者恢复");
    }

    @Test
    void missingFileStartsEmpty(@TempDir Path tmp) {
        FakeNacosConfigAdapter adapter = new FakeNacosConfigAdapter(tmp.resolve("absent.json"));
        NcgException ex = assertThrows(NcgException.class, () -> adapter.read(env, ref));
        assertEquals(NcgErrorCode.SYS_404_001, ex.errorCode());
    }

    @Test
    void persistNowWithoutPathReturnsFalse() {
        FakeNacosConfigAdapter adapter = new FakeNacosConfigAdapter();
        adapter.seed(env, ref, "v0");
        assertFalse(adapter.persistNow(), "persist-path 未配置→不落盘返回 false");
    }

    @Test
    void historySeqContinuesAfterRestore(@TempDir Path tmp) {
        Path file = tmp.resolve("fake-store.json");
        FakeNacosConfigAdapter a1 = new FakeNacosConfigAdapter(file);
        a1.seed(env, ref, "v0");
        a1.publish(env, ref, "v1", null, new IdempotencyKey("CR-20261123-0002", "1"));

        FakeNacosConfigAdapter a2 = new FakeNacosConfigAdapter(file);
        a2.publish(env, ref, "v2", null, new IdempotencyKey("CR-20261123-0002", "2"));
        List<String> ids = a2.history(env, ref, null, null).entries().stream()
                .map(HistoryEntry::historyId).toList();
        assertEquals(2, ids.size());
        assertEquals(2, ids.stream().distinct().count(), "恢复后 historyId 不重复（seq 续跑）");
    }
}
