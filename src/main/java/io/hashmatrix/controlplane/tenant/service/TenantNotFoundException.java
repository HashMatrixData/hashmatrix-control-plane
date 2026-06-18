package io.hashmatrix.controlplane.tenant.service;

/** 目标租户不存在。由 Web 层映射为 404。 */
public class TenantNotFoundException extends RuntimeException {

    public TenantNotFoundException(String identifier) {
        super("租户不存在：" + identifier);
    }
}
