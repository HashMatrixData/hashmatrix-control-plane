package io.hashmatrix.controlplane.tenant.web.dto;

import io.hashmatrix.controlplane.tenant.domain.TenantQuota;
import io.hashmatrix.controlplane.tenant.service.RegisterTenantCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 自助注册请求体（契约 {@code openapi/control-plane-v1} 的 {@code TenantRegistration}）。
 *
 * <p><b>不含</b> {@code deliveryMode}：交付形态是部署级、由控制平面据部署配置统一推导（见 {@link RegisterTenantCommand}）。
 * 配额字段名对齐契约 {@code requestedQuota}（{@code QuotaSpec}）。
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
        @NotBlank @Email @Size(max = 320) String adminEmail,
        @Valid QuotaRequest requestedQuota) {

    public RegisterTenantCommand toCommand() {
        TenantQuota q = requestedQuota == null ? null : requestedQuota.toQuota();
        return new RegisterTenantCommand(tenantId, displayName, adminEmail, q);
    }
}
