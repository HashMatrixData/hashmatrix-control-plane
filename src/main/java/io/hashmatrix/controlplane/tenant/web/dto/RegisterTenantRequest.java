package io.hashmatrix.controlplane.tenant.web.dto;

import io.hashmatrix.controlplane.tenant.domain.DeliveryMode;
import io.hashmatrix.controlplane.tenant.domain.TenantQuota;
import io.hashmatrix.controlplane.tenant.service.RegisterTenantCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 自助注册请求体。
 *
 * @param tenantId 稳定租户标识 = 隔离路由键（schema/catalog/namespace）。须为 DNS-1123 标签子集，
 *     给 {@code tenant-} 前缀留头寸，限长 40。对齐契约 {@code TenantRegistration.tenantId}。
 */
public record RegisterTenantRequest(
        @NotBlank
                @Size(max = 40)
                @Pattern(
                        regexp = "^[a-z0-9]([-a-z0-9]*[a-z0-9])?$",
                        message = "tenantId 须为小写字母/数字/连字符（DNS-1123 标签），且不以连字符开头或结尾")
                String tenantId,
        @NotBlank @Size(max = 255) String displayName,
        @NotNull DeliveryMode deliveryMode,
        @NotBlank @Email @Size(max = 320) String adminEmail,
        @Valid QuotaRequest quota) {

    public RegisterTenantCommand toCommand() {
        TenantQuota q = quota == null ? null : quota.toQuota();
        return new RegisterTenantCommand(tenantId, displayName, deliveryMode, adminEmail, q);
    }
}
