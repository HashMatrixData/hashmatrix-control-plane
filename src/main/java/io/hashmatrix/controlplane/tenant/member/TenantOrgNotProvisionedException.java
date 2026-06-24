package io.hashmatrix.controlplane.tenant.member;

/**
 * 租户尚未完成身份开通——{@code keycloak_org_id} 仍为空，无法管理成员。
 *
 * <p>仅当租户处于「已注册未开通」等中间态时出现；正常 {@code active} 租户在开通时已回写 org id
 * （见 {@code Tenant.recordProvisioningOutcome}）。Web 层（ST2）映射为 409/422，提示先完成开通。
 */
public class TenantOrgNotProvisionedException extends RuntimeException {

    public TenantOrgNotProvisionedException(String tenantKey) {
        super("租户尚未开通身份组织，无法管理成员：" + tenantKey);
    }
}
