package io.hashmatrix.controlplane.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hashmatrix.controlplane.provisioning.spi.ComputeProvisioner;
import io.hashmatrix.controlplane.provisioning.spi.DataProvisioner;
import io.hashmatrix.controlplane.provisioning.spi.IdentityProvisioner;
import io.hashmatrix.controlplane.provisioning.spi.ProvisioningRequest;
import io.hashmatrix.controlplane.provisioning.spi.SecretsProvisioner;
import io.hashmatrix.controlplane.tenant.domain.DeliveryMode;
import io.hashmatrix.controlplane.tenant.domain.TenantQuota;
import io.hashmatrix.test.fixtures.MockData;
import io.hashmatrix.test.fixtures.MockTenants;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/** 开通编排器单测：时序、产出回传、失败包装与回收尽力而为。 */
@ExtendWith(MockitoExtension.class)
class ProvisioningOrchestratorTest {

    @Mock private IdentityProvisioner identity;
    @Mock private ComputeProvisioner compute;
    @Mock private DataProvisioner data;
    @Mock private SecretsProvisioner secrets;

    private ProvisioningRequest request() {
        return new ProvisioningRequest(
                UUID.randomUUID(),
                MockTenants.TENANT_DEMO,
                "Demo 部门",
                DeliveryMode.PRIVATE,
                MockData.email("admin"),
                TenantQuota.defaults());
    }

    @Test
    void provisionsInOrderAndReturnsOutcome() {
        ProvisioningRequest req = request();
        when(identity.provision(req)).thenReturn("org-demo");
        when(compute.provision(req)).thenReturn("tenant-demo");
        when(data.provision(req)).thenReturn("demo");

        ProvisioningOrchestrator orchestrator =
                new ProvisioningOrchestrator(identity, compute, data, secrets);
        ProvisioningOutcome outcome = orchestrator.provision(req);

        assertThat(outcome.keycloakOrgId()).isEqualTo("org-demo");
        assertThat(outcome.namespace()).isEqualTo("tenant-demo");
        assertThat(outcome.dbSchema()).isEqualTo("demo");

        // 时序：identity → compute → data → secrets（架构 05 §4）。
        InOrder inOrder = Mockito.inOrder(identity, compute, data, secrets);
        inOrder.verify(identity).provision(req);
        inOrder.verify(compute).provision(req);
        inOrder.verify(data).provision(req);
        inOrder.verify(secrets).provision(req);
    }

    @Test
    void wrapsStepFailureAndStopsTimeline() {
        ProvisioningRequest req = request();
        when(identity.provision(req)).thenReturn("org-demo");
        when(compute.provision(req)).thenThrow(new IllegalStateException("apiserver 不可达"));

        ProvisioningOrchestrator orchestrator =
                new ProvisioningOrchestrator(identity, compute, data, secrets);

        assertThatExceptionOfType(ProvisioningException.class)
                .isThrownBy(() -> orchestrator.provision(req))
                .satisfies(e -> assertThat(e.getStep()).isEqualTo("compute"));

        // compute 失败后，data/secrets 不应再被调用。
        verify(data, never()).provision(req);
        verify(secrets, never()).provision(req);
    }

    @Test
    void deprovisionIsBestEffortAndContinuesOnFailure() {
        ProvisioningRequest req = request();
        doThrow(new IllegalStateException("ESO 残留")).when(secrets).deprovision(req);

        ProvisioningOrchestrator orchestrator =
                new ProvisioningOrchestrator(identity, compute, data, secrets);
        orchestrator.deprovision(req);

        // 即便 secrets 回收抛错，其余资源仍逐一回收。
        verify(data).deprovision(req);
        verify(compute).deprovision(req);
        verify(identity).deprovision(req);
    }
}
