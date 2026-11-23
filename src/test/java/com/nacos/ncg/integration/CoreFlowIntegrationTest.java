package com.nacos.ncg.integration;

import com.nacos.ncg.NcgWebApplication;
import com.nacos.ncg.adapter.nacos.fake.FakeNacosConfigAdapter;
import com.nacos.ncg.adapter.nacos.port.ConfigRef;
import com.nacos.ncg.adapter.nacos.port.EnvId;
import com.nacos.ncg.adapter.nacos.port.NacosConfigPort;
import com.nacos.ncg.approval.app.ApprovalService;
import com.nacos.ncg.audit.app.AuditChainService;
import com.nacos.ncg.auth.app.AccountService;
import com.nacos.ncg.auth.domain.Account;
import com.nacos.ncg.catalog.domain.App;
import com.nacos.ncg.catalog.domain.ConfigMapping;
import com.nacos.ncg.catalog.domain.Env;
import com.nacos.ncg.catalog.repo.AppRepository;
import com.nacos.ncg.catalog.repo.ConfigMappingRepository;
import com.nacos.ncg.catalog.repo.EnvRepository;
import com.nacos.ncg.change.app.ChangeDraftService;
import com.nacos.ncg.change.repo.ChangeRequestRepository;
import com.nacos.ncg.change.domain.ChangeRequest;
import com.nacos.ncg.release.app.ReleaseService;
import com.nacos.ncg.shared.ContentDigest;
import com.nacos.ncg.shared.Ulid;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 核心链路集成测试（AC-01~04 主干，Testcontainers PostgreSQL 16）：
 * 建单→提交（冻结基线）→审批（职责分离）→发布（写+读回）→审计链校验。
 * 无 Docker 环境自动跳过（disabledWithoutDocker）。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = NcgWebApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "ncg.crypto.key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "ncg.crypto.key-id=test-key",
                "ncg.nacos.mode=fake"
        })
@ActiveProfiles("local")
class CoreFlowIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired AccountService accountService;
    @Autowired ChangeDraftService drafts;
    @Autowired ChangeRequestRepository changeRequests;
    @Autowired ApprovalService approvals;
    @Autowired ReleaseService releases;
    @Autowired AuditChainService auditChainService;
    @Autowired AppRepository apps;
    @Autowired EnvRepository envs;
    @Autowired ConfigMappingRepository mappings;
    @Autowired NacosConfigPort port;

    @Test
    void changeToReleaseHappyPath() {
        // 装配：应用/环境/映射 + 假件基线
        App app = new App();
        app.setAppId(Ulid.generate());
        app.setAppCode("demo-app");
        app.setName("Demo");
        app.setOwnerId("owner-1");
        app.setStatus("ACTIVE");
        app.setCreatedAt(java.time.Instant.now());
        app.setUpdatedAt(java.time.Instant.now());
        apps.save(app);

        Env env = new Env();
        env.setEnvId(Ulid.generate());
        env.setEnvCode("dev");
        env.setDisplayName("dev");
        env.setSortOrder(1);
        env.setStatus("ACTIVE");
        env.setCreatedAt(java.time.Instant.now());
        env.setUpdatedAt(java.time.Instant.now());
        envs.save(env);

        ConfigMapping mapping = new ConfigMapping();
        mapping.setMappingId(Ulid.generate());
        mapping.setAppId(app.getAppId());
        mapping.setEnvId(env.getEnvId());
        mapping.setNamespace("public");
        mapping.setGroupName("DEFAULT_GROUP");
        mapping.setDataId("demo.yaml");
        mapping.setSensitive(false);
        mapping.setStatus("MANAGED");
        mapping.setCreatedAt(java.time.Instant.now());
        mapping.setUpdatedAt(java.time.Instant.now());
        mappings.save(mapping);

        FakeNacosConfigAdapter fake = (FakeNacosConfigAdapter) port;
        ConfigRef ref = new ConfigRef("public", "DEFAULT_GROUP", "demo.yaml");
        fake.seed(new EnvId("dev"), ref, "key: v0");

        // 账号：提交人/审批人/发布人分离（R-APR-01 / R-REL-01）
        Account submitter = accountService.create("alice", "Alice", "password-12345", false, "test", null);
        Account approver = accountService.create("bob", "Bob", "password-12345", false, "test", null);
        Account publisher = accountService.create("carol", "Carol", "password-12345", false, "test", null);

        // 建单+提交：冻结基线 md5("key: v0")
        ChangeRequest cr = drafts.open(app.getAppId(), env.getEnvId(), mapping.getMappingId(),
                "UPDATE", "Update demo.yaml", "Integration test change reason",
                "key: v1", submitter.getAccountId(), null, null);
        assertEquals("DRAFT", cr.getStatus());
        drafts.submit(cr.getChangeNo(), submitter.getAccountId(), null, null);
        ChangeRequest submitted = changeRequests.findById(cr.getChangeNo()).orElseThrow();
        assertEquals("PENDING_APPROVAL", submitted.getStatus());
        assertEquals(ContentDigest.md5Hex("key: v0"), submitted.getBaselineMd5(), "提交冻结基线（R-CR-04）");

        // 审批通过（审批人≠提交人≠发布人）
        approvals.decide(cr.getChangeNo(), "APPROVE", null, null, approver.getAccountId(), null);
        assertEquals("APPROVED", changeRequests.findById(cr.getChangeNo()).orElseThrow().getStatus());

        // 发布：写+读回（C-ARC-03 唯一写入口、写后读回）
        releases.release(cr.getChangeNo(), "v2", publisher.getAccountId(), null);
        ChangeRequest released = changeRequests.findById(cr.getChangeNo()).orElseThrow();
        assertEquals("SUCCEEDED", released.getStatus());
        assertEquals("key: v1", fake.read(new EnvId("dev"), ref).content(), "Nacos 内容已更新");

        // 审计链完整（C-ARC-11）
        AuditChainService.Report report = auditChainService
                .verify(LocalDate.now(ZoneId.of("UTC")));
        assertTrue(report.ok(), "审计哈希链校验通过");
    }
}
