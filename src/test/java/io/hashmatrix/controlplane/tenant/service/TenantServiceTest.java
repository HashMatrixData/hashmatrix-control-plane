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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 租户生命周期服务单测（无 DB/网络）—— 用 Mockito mock 仓储与编排器，聚焦状态流转编排与回退语义。
 * 与 Testcontainers 集成测试互补：本测试在任何环境可跑，IT 验证真实持久化时序。
 */
@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock private TenantRepository repository;
    @Mock private ProvisioningOrchestrator orchestrator;

    private TenantService service;

    @BeforeEach
    void setUp() {
        service = new TenantService(repository, orchestrator);
    }

    private RegisterTenantCommand command() {
        return new RegisterTenantCommand(
                MockTenants.TENANT_DEMO,
                "Demo 部门",
                DeliveryMode.PRIVATE,
                MockData.email("admin"),
                TenantQuota.defaults());
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
        UUID id = tenant.getId();
        when(repository.findById(id)).thenReturn(Optional.of(tenant));
        when(repository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orchestrator.provision(any(ProvisioningRequest.class)))
                .thenReturn(new ProvisioningOutcome("org-demo", "tenant-tenant-demo", "tenant-demo"));

        Tenant result = service.approve(id, "ok");

        assertThat(result.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(result.getKeycloakOrgId()).isEqualTo("org-demo");
        assertThat(result.getNamespace()).isEqualTo("tenant-tenant-demo");
        assertThat(result.getDbSchema()).isEqualTo("tenant-demo");
    }

    /**
     * 对象层断言：失败后聚合根回退 APPROVING 并写 statusReason。
     * 注意：mock 仓储无真实事务，**不**验证「回退态是否真落库」——该持久化保证由
     * {@code ProvisioningFailureIntegrationTest} 在真实事务下守护（防 @Transactional 回滚卷走回退态）。
     */
    @Test
    void approveRevertsToApprovingOnProvisioningFailure() {
        Tenant tenant = registered();
        UUID id = tenant.getId();
        when(repository.findById(id)).thenReturn(Optional.of(tenant));
        when(repository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.saveAndFlush(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orchestrator.provision(any(ProvisioningRequest.class)))
                .thenThrow(new ProvisioningException("compute", "apiserver 不可达", null));

        assertThatExceptionOfType(ProvisioningException.class)
                .isThrownBy(() -> service.approve(id, "ok"));

        // 失败后回退到审批门控，并把失败详情写入 statusReason。
        assertThat(tenant.getStatus()).isEqualTo(TenantStatus.APPROVING);
        assertThat(tenant.getStatusReason()).contains("compute");
    }

    @Test
    void suspendResumeAndDeleteFollowStateMachine() {
        Tenant tenant = registered();
        UUID id = tenant.getId();
        when(repository.findById(id)).thenReturn(Optional.of(tenant));
        when(repository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.saveAndFlush(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orchestrator.provision(any(ProvisioningRequest.class)))
                .thenReturn(new ProvisioningOutcome("org-demo", "tenant-tenant-demo", "tenant-demo"));

        service.approve(id, "ok");
        assertThat(service.suspend(id, "维护").getStatus()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(service.resume(id).getStatus()).isEqualTo(TenantStatus.ACTIVE);

        Tenant deleted = service.delete(id, "下线");
        assertThat(deleted.getStatus()).isEqualTo(TenantStatus.DELETED);
        verify(orchestrator).deprovision(any(ProvisioningRequest.class));
    }

    @Test
    void suspendFromRegisteredIsIllegal() {
        Tenant tenant = registered();
        UUID id = tenant.getId();
        when(repository.findById(id)).thenReturn(Optional.of(tenant));

        assertThatExceptionOfType(TenantStatusTransitionException.class)
                .isThrownBy(() -> service.suspend(id, "x"));
    }

    @Test
    void getUnknownTenantThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatExceptionOfType(TenantNotFoundException.class).isThrownBy(() -> service.get(id));
    }
}
