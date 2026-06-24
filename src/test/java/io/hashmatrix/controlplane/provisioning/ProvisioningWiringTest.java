package io.hashmatrix.controlplane.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import io.hashmatrix.controlplane.provisioning.keycloak.KeycloakIdentityProvisioner;
import io.hashmatrix.controlplane.provisioning.keycloak.KeycloakOrgMemberDirectory;
import io.hashmatrix.controlplane.provisioning.keycloak.KeycloakProvisioningConfiguration;
import io.hashmatrix.controlplane.provisioning.spi.ComputeProvisioner;
import io.hashmatrix.controlplane.provisioning.spi.DataProvisioner;
import io.hashmatrix.controlplane.provisioning.spi.IdentityProvisioner;
import io.hashmatrix.controlplane.provisioning.spi.SecretsProvisioner;
import io.hashmatrix.controlplane.provisioning.stub.StubProvisioningConfiguration;
import io.hashmatrix.controlplane.tenant.member.OrgMemberDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 开通端口装配的<b>不可返工决策</b>守护（无需 Docker）：identity 适配器与全局 {@code mode} 解耦、按属性互斥。
 *
 * <p>用 {@link ApplicationContextRunner} 仅加载两类装配，验证三种装配下端口 Bean 唯一且类型正确：
 * 默认全 stub；{@code identity=keycloak} 仅 identity 切真实、compute/data/secrets 仍 stub；
 * 即便显式 {@code mode=stub} 叠加 {@code identity=keycloak} 也能共存不冲突（不丢 Bean、不重复 Bean）。
 */
class ProvisioningWiringTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            StubProvisioningConfiguration.class, KeycloakProvisioningConfiguration.class);

    @Test
    void defaultsToAllStubAdapters() {
        runner.run(
                ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(IdentityProvisioner.class);
                    assertThat(ctx.getBean(IdentityProvisioner.class))
                            .isNotInstanceOf(KeycloakIdentityProvisioner.class);
                    assertThat(ctx).hasSingleBean(ComputeProvisioner.class);
                    assertThat(ctx).hasSingleBean(DataProvisioner.class);
                    assertThat(ctx).hasSingleBean(SecretsProvisioner.class);
                    // 成员目录（ST1）随 identity 同模式互斥：默认 stub（内存实现）。
                    assertThat(ctx).hasSingleBean(OrgMemberDirectory.class);
                    assertThat(ctx.getBean(OrgMemberDirectory.class))
                            .isNotInstanceOf(KeycloakOrgMemberDirectory.class);
                });
    }

    @Test
    void identityKeycloakSwapsOnlyIdentityAndKeepsOtherStubs() {
        runner.withPropertyValues("hashmatrix.control-plane.provisioning.identity=keycloak")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx).hasSingleBean(IdentityProvisioner.class);
                            assertThat(ctx.getBean(IdentityProvisioner.class))
                                    .isInstanceOf(KeycloakIdentityProvisioner.class);
                            // 关键不变量：compute/data/secrets 仍为 stub 且各自唯一（切 identity 不波及其余端口）。
                            assertThat(ctx).hasSingleBean(ComputeProvisioner.class);
                            assertThat(ctx).hasSingleBean(DataProvisioner.class);
                            assertThat(ctx).hasSingleBean(SecretsProvisioner.class);
                            // 成员目录随 identity=keycloak 切真实 KC 适配器，且唯一。
                            assertThat(ctx).hasSingleBean(OrgMemberDirectory.class);
                            assertThat(ctx.getBean(OrgMemberDirectory.class))
                                    .isInstanceOf(KeycloakOrgMemberDirectory.class);
                        });
    }

    @Test
    void explicitModeStubPlusIdentityKeycloakCoexistWithoutConflict() {
        runner.withPropertyValues(
                        "hashmatrix.control-plane.provisioning.mode=stub",
                        "hashmatrix.control-plane.provisioning.identity=keycloak")
                .run(
                        ctx -> {
                            // 「真 identity + stub 其余」共存——既不缺 Bean（启动失败）也不重复（NoUniqueBean）。
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx).hasSingleBean(IdentityProvisioner.class);
                            assertThat(ctx.getBean(IdentityProvisioner.class))
                                    .isInstanceOf(KeycloakIdentityProvisioner.class);
                            assertThat(ctx).hasSingleBean(ComputeProvisioner.class);
                            assertThat(ctx).hasSingleBean(DataProvisioner.class);
                            assertThat(ctx).hasSingleBean(SecretsProvisioner.class);
                            assertThat(ctx).hasSingleBean(OrgMemberDirectory.class);
                            assertThat(ctx.getBean(OrgMemberDirectory.class))
                                    .isInstanceOf(KeycloakOrgMemberDirectory.class);
                        });
    }
}
