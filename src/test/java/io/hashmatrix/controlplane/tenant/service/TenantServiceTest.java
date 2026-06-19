package io.hashmatrix.controlplane.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hashmatrix.controlplane.provisioning.ProvisioningException;
import io.hashmatrix.controlplane.provisioning.ProvisioningOrchestrator;
import io.hashmatrix.controlplane.provisioning.ProvisioningOutcome;
import io.hashmatrix.controlplane.provisioning.spi.ProvisioningRequest;
import io.hashmatrix.controlplane.tenant.domain.DeliveryMode;
import io.hashmatrix.controlplane.tenant.domain.Tenant;
import io.hashmatrix.controlplane.tenant.domain.TenantQuota;
import io.hashmatrix.controlplane.tenant.domain.TenantStatus;
import io.hashmatrix.controlplane.tenant.domain.TenantStatusTransitionException;
import io.hashmatrix.controlplane.tenant.repo.TenantRepository;
import io.hashmatrix.test.fixtures.MockData;
import io.hashmatrix.test.fixtures.MockTenants;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 租户生命周期服务单测（无 DB/网络）—— 用 Mockito mock 仓储与编排器，聚焦状态流转编排与回退语义。
 * 与 Testcontainers 集成测试互补：本测试在任何环境可跑，IT 验证真实持久化时序。
 *
 * <p>寻址键为稳定 {@code tenantKey}（对齐契约 {@code tenantId}）；交付形态由部署级配置注入（构造参数），
 * 注册命令不再携带 {@code deliveryMode}。
 */
@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock private TenantRepository repository;
    @Mock private ProvisioningOrchestrator orchestrator;

    private TenantService service;

    @BeforeEach
    void setUp() {
        // 部署级交付形态以构造参数注入（生产经 @Value 绑定）。
        service = new TenantService(repository, orchestrator, DeliveryMode.PRIVATE);
    }

    private RegisterTenantCommand command() {
        return new RegisterTenantCommand(
                MockTenants.TENANT_DEMO, "Demo 部门", MockData.email("admin"), TenantQuota.defaults());
    }

    private Tenant registered() {
        return Tenant.register(
                MockTenants.TENANT_DEMO,
                "Demo 部门",
                DeliveryMode.PRIVATE,
                MockData.email("admin"),
                TenantQuota.defaults());
    }

    @Test
    void registerPersistsNewTenantAsRegistered() {
        when(repository.existsByTenantKey(MockTenants.TENANT_DEMO)).thenReturn(false);
        when(repository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = service.register(command());

        assertThat(result.getStatus()).isEqualTo(TenantStatus.REGISTERED);
        assertThat(result.getTenantKey()).isEqualTo("tenant-demo");
        // 交付形态据部署配置注入（非来自命令）。
        assertThat(result.getDeliveryMode()).isEqualTo(DeliveryMode.PRIVATE);
    }

    @Test
    void registerRejectsDuplicateKey() {
        when(repository.existsByTenantKey(MockTenants.TENANT_DEMO)).thenReturn(true);

        assertThatExceptionOfType(TenantKeyConflictException.class)
                .isThrownBy(() -> service.register(command()));
        verify(repository, never()).save(any());
    }

    @Test
    void approveDrivesProvisioningToActiveAndWritesBackOutcome() {
        Tenant tenant = registered();
        String key = tenant.getTenantKey();
        when(repository.findByTenantKey(key)).thenReturn(Optional.of(tenant));
        when(repository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orchestrator.provision(any(ProvisioningRequest.class)))
                .thenReturn(new ProvisioningOutcome("org-demo", "tenant-tenant-demo", "tenant-demo"));

        Tenant result = service.approve(key, "ok");

        assertThat(result.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(result.getKeycloakOrgId()).isEqualTo("org-demo");
        assertThat(result.getNamespace()).isEqualTo("tenant-tenant-demo");
        assertThat(result.getDbSchema()).isEqualTo("tenant-demo");
    }

    /** 审批驳回 → DELETED 终态并留痕（对齐契约 {@code reject → deleted}）。 */
    @Test
    void rejectTransitionsToDeletedWithAudit() {
        Tenant tenant = registered();
        String key = tenant.getTenantKey();
        when(repository.findByTenantKey(key)).thenReturn(Optional.of(tenant));
        when(repository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = service.reject(key, "材料不全");

        assertThat(result.getStatus()).isEqualTo(TenantStatus.DELETED);
        assertThat(result.getStatusReason()).contains("驳回");
    }

    /**
     * 对象层断言：失败后聚合根回退 APPROVING 并写 statusReason。
     * 注意：mock 仓储无真实事务，**不**验证「回退态是否真落库」——该持久化保证由
     * {@code ProvisioningFailureIntegrationTest} 在真实事务下守护（防 @Transactional 回滚卷走回退态）。
     */
    @Test
    void approveRevertsToApprovingOnProvisioningFailure() {
        Tenant tenant = registered();
        String key = tenant.getTenantKey();
        when(repository.findByTenantKey(key)).thenReturn(Optional.of(tenant));
        when(repository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.saveAndFlush(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orchestrator.provision(any(ProvisioningRequest.class)))
                .thenThrow(new ProvisioningException("compute", "apiserver 不可达", null));

        assertThatExceptionOfType(ProvisioningException.class)
                .isThrownBy(() -> service.approve(key, "ok"));

        // 失败后回退到审批门控，并把失败详情写入 statusReason。
        assertThat(tenant.getStatus()).isEqualTo(TenantStatus.APPROVING);
        assertThat(tenant.getStatusReason()).contains("compute");
    }

    @Test
    void suspendResumeAndDeleteFollowStateMachine() {
        Tenant tenant = registered();
        String key = tenant.getTenantKey();
        when(repository.findByTenantKey(key)).thenReturn(Optional.of(tenant));
        when(repository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.saveAndFlush(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orchestrator.provision(any(ProvisioningRequest.class)))
                .thenReturn(new ProvisioningOutcome("org-demo", "tenant-tenant-demo", "tenant-demo"));

        service.approve(key, "ok");
        assertThat(service.suspend(key, "维护").getStatus()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(service.resume(key).getStatus()).isEqualTo(TenantStatus.ACTIVE);

        Tenant deleted = service.delete(key, "下线");
        assertThat(deleted.getStatus()).isEqualTo(TenantStatus.DELETED);
        verify(orchestrator).deprovision(any(ProvisioningRequest.class));
    }

    @Test
    void suspendFromRegisteredIsIllegal() {
        Tenant tenant = registered();
        String key = tenant.getTenantKey();
        when(repository.findByTenantKey(key)).thenReturn(Optional.of(tenant));

        assertThatExceptionOfType(TenantStatusTransitionException.class)
                .isThrownBy(() -> service.suspend(key, "x"));
    }

    @Test
    void getUnknownTenantThrowsNotFound() {
        when(repository.findByTenantKey("ghost-tenant")).thenReturn(Optional.empty());

        assertThatExceptionOfType(TenantNotFoundException.class)
                .isThrownBy(() -> service.get("ghost-tenant"));
    }
}
