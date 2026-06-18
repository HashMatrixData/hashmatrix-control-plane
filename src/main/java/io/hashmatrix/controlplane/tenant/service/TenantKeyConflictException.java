package io.hashmatrix.controlplane.tenant.service;

/** 租户 key 已被占用（必须全局唯一，是隔离路由键）。由 Web 层映射为 409。 */
public class TenantKeyConflictException extends RuntimeException {

    public TenantKeyConflictException(String tenantKey) {
        super("租户 key 已存在：" + tenantKey);
    }
}
