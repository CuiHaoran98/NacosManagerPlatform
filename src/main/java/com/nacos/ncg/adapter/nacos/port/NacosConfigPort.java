package com.nacos.ncg.adapter.nacos.port;

import java.time.Instant;

/**
 * 配置中心适配器端口（详细设计 v1.1 §8.1；C-ARC-04：只此五方法，不透出 CRUD）。
 * 上游文档称 TongNcsConfigPort，底层为真实 Nacos（m00232 决策）。
 *
 * <p>语义契约（§8.2-8.5）：
 * <ul>
 *   <li>{@code publish}：写入前计算 expectedMd5，写入后读回比对；只有读回 MD5 相同才 verified；
 *       写入已发出但超时/读回失败 → indeterminate，禁止自动重试（C-ARC-05）。</li>
 *   <li>只读方法（read/history/subscribers）最多重试 1 次；publish 与读回不自动重试。</li>
 *   <li>历史/订阅快照带 fetchedAt，只能标“可能过期”，不得作为事实源（C-ARC-16）。</li>
 *   <li>超时：连接 5s；read/history/subscribers 10s；publish 写入 10s；读回 5s；test 10s。</li>
 * </ul>
 */
public interface NacosConfigPort {

    /** 读取当前配置内容。 */
    Snapshot read(EnvId envId, ConfigRef ref);

    /**
     * 发布配置内容并读回校验。
     *
     * @param expectedMd5 写入前的基线 MD5（写前 CAS + 写后读回比对）
     * @param key         发布幂等键 = changeNo:targetVersion（R-REL-04）
     */
    PublishOutcome publish(EnvId envId, ConfigRef ref, String content, String expectedMd5, IdempotencyKey key);

    /** 拉取历史记录（直连检测增量扫描用；since/cursor 分页）。 */
    HistoryPage history(EnvId envId, ConfigRef ref, Instant since, String cursor);

    /** 拉取订阅者（监听实例）快照。 */
    SubscriberSnapshot subscribers(EnvId envId, ConfigRef ref);

    /** 环境连接测试。 */
    ConnectionReport test(EnvId envId);
}
