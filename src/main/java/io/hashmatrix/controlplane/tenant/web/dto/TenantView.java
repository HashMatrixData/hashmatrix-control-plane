package io.hashmatrix.controlplane.tenant.web.dto;

import io.hashmatrix.controlplane.tenant.domain.Tenant;
import io.hashmatrix.controlplane.tenant.domain.TenantQuota;
import io.hashmatrix.controlplane.tenant.domain.TenantStatus;
import java.time.Instant;

/**
 * 租户对外视图（契约 {@code openapi/control-plane-v1} 的 {@code Tenant}）。脱敏：仅暴露目录与接入信息，不含任何凭据。
 *
 * <p><b>结构对齐契约</b>（issue #9）：路由键 {@code tenantId}（= 领域 {@code tenantKey}，<b>非</b>内部 UUID）；
 * 身份接入收拢为嵌套 {@link OrganizationView}（{@code orgId}/{@code orgAlias}）、数据平面接入收拢为嵌套
 * {@link DataPlaneView}；配额 {@link QuotaView} 含嵌套 {@code compute}。
 *
 * <p><b>不含</b>内部 UUID（{@code id}）、{@code adminEmail}（注册输入项、非目录数据）、{@code deliveryMode}
 * （部署级、非按租户）——三者均不在契约 {@code Tenant}。保留 {@code statusReason}（契约 1.2.0 已纳入，审计/排障）。
 */
public record TenantView(
        String tenantId,
        String displayName,
        TenantStatus status,
        OrganizationView organization,
        DataPlaneView dataPlane,
        QuotaView quota,
        String statusReason,
        Instant createdAt,
        Instant updatedAt) {

    public static TenantView from(Tenant t) {
        return new TenantView(
                t.getTenantKey(),
                t.getDisplayName(),
                t.getStatus(),
                OrganizationView.from(t),
                DataPlaneView.from(t),
                QuotaView.from(t.getQuota()),
                t.getStatusReason(),
                t.getCreatedAt(),
                t.getUpdatedAt());
    }

    /** Keycloak Organization 引用（契约 {@code OrganizationRef}）。 */
    public record OrganizationView(String orgId, String orgAlias) {
        static OrganizationView from(Tenant t) {
            // orgId：开通回写的 keycloakOrgId（未开通为 null）。
            // orgAlias：M1 demo 约定与 tenantKey 同值（接入真实 Keycloak org 后分化为独立别名，见 MembershipView）。
            return new OrganizationView(t.getKeycloakOrgId(), t.getTenantKey());
        }
    }

    /** 数据平面接入信息（契约 {@code DataPlaneRef}）。 */
    public record DataPlaneView(
            String namespace, String dbSchema, String dorisCatalog, String helmRelease) {
        static DataPlaneView from(Tenant t) {
            // namespace/dbSchema 开通后回写（未开通为 null）；dorisCatalog/helmRelease 当前开通编排未单独产出快照，
            // M1 留 null（契约二者 optional、消费方 tolerant reader），接入真实编排回写后补全。
            return new DataPlaneView(t.getNamespace(), t.getDbSchema(), null, null);
        }
    }

    /** 业务配额视图（契约 {@code QuotaSpec}；存储以 GiB 计、含嵌套 compute）。 */
    public record QuotaView(
            int maxUsers, int maxStorageGi, int maxConcurrentJobs, ComputeView compute) {
        static QuotaView from(TenantQuota q) {
            return new QuotaView(
                    q.maxUsers(),
                    q.maxStorageGi(),
                    q.maxConcurrentJobs(),
                    new ComputeView(q.computeCpuCores(), q.computeMemoryGi()));
        }
    }

    /** 计算配额（契约 {@code ComputeQuota}）。 */
    public record ComputeView(int cpuCores, int memoryGi) {}
}
