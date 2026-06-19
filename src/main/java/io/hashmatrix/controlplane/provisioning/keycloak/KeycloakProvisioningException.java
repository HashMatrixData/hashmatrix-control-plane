package io.hashmatrix.controlplane.provisioning.keycloak;

/**
 * Keycloak 身份开通适配器内部失败 —— 建组织 / 建用户 / 入组织 / 赋角色任一步未成功。
 *
 * <p>未受检：抛给上层 {@link io.hashmatrix.controlplane.provisioning.ProvisioningOrchestrator}，由其 {@code
 * step("identity", …)} 统一包装为 {@code ProvisioningException}（携带步骤名），适配器自身不耦合编排器命名。
 */
public class KeycloakProvisioningException extends RuntimeException {

    public KeycloakProvisioningException(String message, Throwable cause) {
        super(message, cause);
    }
}
