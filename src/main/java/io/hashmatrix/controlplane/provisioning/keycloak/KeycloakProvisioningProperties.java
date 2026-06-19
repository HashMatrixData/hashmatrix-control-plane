package io.hashmatrix.controlplane.provisioning.keycloak;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 真实 Keycloak 身份开通适配器的连接与命名配置。
 *
 * <p>仅当 {@code hashmatrix.control-plane.provisioning.identity=keycloak} 时装配
 * {@link KeycloakProvisioningConfiguration} 才会绑定本配置；默认 stub 路径不加载。
 *
 * <p>🔴 红线：本类不内置任何真实凭据；{@link #username}/{@link #password} 仅为对接 compose 本地
 * Keycloak 的脱敏占位，生产经环境变量 / ESO 注入覆盖，严禁把真实凭据写入仓库。
 */
@ConfigurationProperties(prefix = "hashmatrix.control-plane.provisioning.keycloak")
public class KeycloakProvisioningProperties {

    /** Keycloak 服务端地址。默认对齐端口基线 Keycloak=8180（compose 端口对齐由 WP4 落地）。 */
    private String serverUrl = "http://localhost:8180";

    /** 管理客户端登录所在 realm（password 授权），通常为 {@code master}。 */
    private String authRealm = "master";

    /** 开通目标 realm —— 租户 Organization 与用户创建于此（单 realm 多 org，对齐架构 05）。 */
    private String targetRealm = "hashmatrix";

    /** 管理客户端 id，本地默认公共客户端 {@code admin-cli}（password 授权）。 */
    private String clientId = "admin-cli";

    /** 管理用户名（🔴 本地占位，经环境变量覆盖）。 */
    private String username = "admin";

    /** 管理口令（🔴 本地占位，经环境变量覆盖；勿入真实凭据）。 */
    private String password = "admin";

    /** 赋予租户首个管理员的 realm 角色名；不存在时由适配器幂等创建。 */
    private String tenantAdminRole = "tenant-admin";

    /** 组织域名后缀 —— 组织域 = {@code <tenantKey>.<domainSuffix>}（脱敏占位，唯一即可）。 */
    private String domainSuffix = "example.com";

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getAuthRealm() {
        return authRealm;
    }

    public void setAuthRealm(String authRealm) {
        this.authRealm = authRealm;
    }

    public String getTargetRealm() {
        return targetRealm;
    }

    public void setTargetRealm(String targetRealm) {
        this.targetRealm = targetRealm;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getTenantAdminRole() {
        return tenantAdminRole;
    }

    public void setTenantAdminRole(String tenantAdminRole) {
        this.tenantAdminRole = tenantAdminRole;
    }

    public String getDomainSuffix() {
        return domainSuffix;
    }

    public void setDomainSuffix(String domainSuffix) {
        this.domainSuffix = domainSuffix;
    }

    /** 组织域名：{@code <tenantKey>.<domainSuffix>}，每租户唯一。 */
    public String domainFor(String tenantKey) {
        return tenantKey + "." + domainSuffix;
    }
}
