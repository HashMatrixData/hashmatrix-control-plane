package io.hashmatrix.controlplane.tenant.web.dto;

import io.hashmatrix.controlplane.tenant.domain.DeliveryMode;
import io.hashmatrix.controlplane.tenant.domain.Tenant;
import io.hashmatrix.controlplane.tenant.domain.TenantStatus;
import java.time.Instant;
import java.util.UUID;

/** 租户对外视图（脱敏：仅暴露目录与接入信息，不含任何凭据）。 */
public record TenantView(
        UUID id,
        String tenantId,
        String displayName,
        DeliveryMode deliveryMode,
        TenantStatus status,
        String adminEmail,
        String keycloakOrgId,
        String namespace,
        String dbSchema,
        QuotaView quota,
        String statusReason,
        Instant createdAt,
        Instant updatedAt) {

    public static TenantView from(Tenant t) {
        return new TenantView(
                t.getId(),
                t.getTenantKey(),
                t.getDisplayName(),
                t.getDeliveryMode(),
                t.getStatus(),
                t.getAdminEmail(),
                t.getKeycloakOrgId(),
                t.getNamespace(),
                t.getDbSchema(),
                QuotaView.from(t.getQuota()),
                t.getStatusReason(),
                t.getCreatedAt(),
                t.getUpdatedAt());
    }

    /** 业务配额视图。 */
    public record QuotaView(int maxUsers, long maxDataBytes, int maxJobs) {
        static QuotaView from(io.hashmatrix.controlplane.tenant.domain.TenantQuota q) {
            return new QuotaView(q.maxUsers(), q.maxDataBytes(), q.maxJobs());
        }
    }
}
