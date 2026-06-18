package io.hashmatrix.controlplane.provisioning.stub;

/**
 * 由租户 key 派生隔离资源名的确定性规则（stub 适配器用；真实适配器可复用同一约定）。
 *
 * <p>namespace 形如 {@code tenant-acme}（呼应架构 05 线框图）；schema 直用 tenant key。校验由
 * {@code TenantService} 在注册时前移（key 须为 DNS-1123 标签子集），此处仅做组合。
 */
final class TenantNaming {

    private TenantNaming() {}

    static String namespace(String tenantKey) {
        return "tenant-" + tenantKey;
    }

    static String schema(String tenantKey) {
        return tenantKey;
    }
}
