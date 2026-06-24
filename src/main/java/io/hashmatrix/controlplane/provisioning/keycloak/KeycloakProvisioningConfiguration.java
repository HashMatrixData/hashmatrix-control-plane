package io.hashmatrix.controlplane.provisioning.keycloak;

import io.hashmatrix.controlplane.provisioning.spi.IdentityProvisioner;
import io.hashmatrix.controlplane.tenant.member.OrgMemberDirectory;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 真实 Keycloak 身份适配器装配 —— 仅 {@code hashmatrix.control-plane.provisioning.identity=keycloak} 时生效。
 *
 * <p>⚠️ 不可返工的装配决策（主仓已拍板）：identity 适配器的激活条件与全局 {@code provisioning.mode}
 * <b>解耦</b>。stub 装配（{@code StubProvisioningConfiguration}，gate 于 {@code mode=stub}）整体仍提供
 * compute/data/secrets 三个 stub，而其 identity stub 额外以 {@code identity!=keycloak} 收敛——故 identity=keycloak
 * 时本类提供真实 Bean、stub identity 自动让位，<b>真 identity + stub compute/data/secrets 共存</b>；
 * 默认（identity 未设）保持 stub，现有路径零改动。两者按属性<b>互斥</b>，不依赖 Bean 注册顺序。
 *
 * <p>{@link Keycloak} 客户端按 {@code destroyMethod=close} 随上下文关闭释放连接；其 {@code build()} 不即时
 * 连接（首次调用才鉴权），故无活 Keycloak 也不阻断应用启动——身份开通失败仅在实际开通时暴露。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "hashmatrix.control-plane.provisioning",
        name = "identity",
        havingValue = "keycloak")
@EnableConfigurationProperties(KeycloakProvisioningProperties.class)
public class KeycloakProvisioningConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    Keycloak keycloakAdminClient(KeycloakProvisioningProperties props) {
        return KeycloakBuilder.builder()
                .serverUrl(props.getServerUrl())
                .realm(props.getAuthRealm())
                .clientId(props.getClientId())
                .grantType(OAuth2Constants.PASSWORD)
                .username(props.getUsername())
                .password(props.getPassword())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    IdentityProvisioner keycloakIdentityProvisioner(
            Keycloak keycloak, KeycloakProvisioningProperties props) {
        return new KeycloakIdentityProvisioner(keycloak, props);
    }

    /**
     * 真实组织成员目录（ST1）——与身份开通共用同一 admin client / 目标 realm。{@code identity=keycloak} 时
     * 由本类提供，让位于 {@code StubProvisioningConfiguration} 的内存 stub（按 {@code identity} 属性互斥）。
     */
    @Bean
    @ConditionalOnMissingBean
    OrgMemberDirectory keycloakOrgMemberDirectory(
            Keycloak keycloak, KeycloakProvisioningProperties props) {
        return new KeycloakOrgMemberDirectory(keycloak, props);
    }
}
