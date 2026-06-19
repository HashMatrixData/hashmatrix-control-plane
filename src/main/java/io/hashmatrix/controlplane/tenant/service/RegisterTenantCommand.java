package io.hashmatrix.controlplane.tenant.service;

import io.hashmatrix.controlplane.tenant.domain.TenantQuota;

/**
 * 自助注册命令（应用层入参，已脱离 Web DTO）。
 *
 * <p>不含 {@code deliveryMode}：交付形态是<b>部署级</b>（一个部署整体 SaaS 或私有化，见架构 05 §1），
 * 非按租户由调用方选择；由 {@link TenantService} 据部署配置统一推导后落到聚合根。
 *
 * @param quota 业务配额；{@code null} 时由 {@link TenantQuota#defaults()} 兜底
 */
public record RegisterTenantCommand(
        String tenantKey, String displayName, String adminEmail, TenantQuota quota) {}
