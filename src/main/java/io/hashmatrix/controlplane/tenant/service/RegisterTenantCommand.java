package io.hashmatrix.controlplane.tenant.service;

import io.hashmatrix.controlplane.tenant.domain.DeliveryMode;
import io.hashmatrix.controlplane.tenant.domain.TenantQuota;

/**
 * 自助注册命令（应用层入参，已脱离 Web DTO）。
 *
 * @param quota 业务配额；{@code null} 时由 {@link TenantQuota#defaults()} 兜底
 */
public record RegisterTenantCommand(
        String tenantKey,
        String displayName,
        DeliveryMode deliveryMode,
        String adminEmail,
        TenantQuota quota) {}
