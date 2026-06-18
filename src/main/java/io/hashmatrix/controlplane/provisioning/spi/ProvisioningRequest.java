package io.hashmatrix.controlplane.provisioning.spi;

import io.hashmatrix.controlplane.tenant.domain.DeliveryMode;
import io.hashmatrix.controlplane.tenant.domain.Tenant;
import io.hashmatrix.controlplane.tenant.domain.TenantQuota;
import java.util.UUID;

/**
 * 开通编排的不可变入参 —— 各 provisioner 端口的统一上下文。
 *
 * <p>从 {@link Tenant} 聚合派生快照，使端口实现不直接耦合持久化实体。
 */
public record ProvisioningRequest(
        UUID tenantId,
        String tenantKey,
        String displayName,
        DeliveryMode deliveryMode,
        String adminEmail,
        TenantQuota quota) {

    public static ProvisioningRequest from(Tenant tenant) {
        return new ProvisioningRequest(
                tenant.getId(),
                tenant.getTenantKey(),
                tenant.getDisplayName(),
                tenant.getDeliveryMode(),
                tenant.getAdminEmail(),
                tenant.getQuota());
    }
}
