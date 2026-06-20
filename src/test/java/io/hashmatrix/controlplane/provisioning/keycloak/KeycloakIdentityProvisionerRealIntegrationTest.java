package io.hashmatrix.controlplane.provisioning.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import io.hashmatrix.controlplane.provisioning.spi.ProvisioningRequest;
import io.hashmatrix.controlplane.tenant.domain.DeliveryMode;
import io.hashmatrix.controlplane.tenant.domain.TenantQuota;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestFilter;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 真实 Keycloak 身份开通集成测试（Testcontainers）—— 把 {@link KeycloakIdentityProvisionerTest} 的
 * deep-stub 断言抬升为<b>真实 Keycloak</b> 验证，兑现里程碑 M1 / epic #3 §6 第一判据：
 * 「真实开通一个租户 → Keycloak 后台落地 org + 租户管理员 user + 角色 + 组织成员，适配器返回<b>真实 org id</b>」。
 *
 * <p><b>测试边界</b>：本 IT 覆盖<b>适配器层</b>（创建身份资源并返回真实 org id）；把该 id 回写
 * {@code tenant.keycloak_org_id} 属上层 service 编排职责，由 {@code ControlPlaneIntegrationTest}
 * （断言 approve 后 {@code organization.orgId} 已回写）守护，二者合起来闭环判据。
 *
 * <p>容器对齐本地 {@code docker-compose.local.yml}：{@code quay.io/keycloak/keycloak:25.0} +
 * {@code --features=organization}（Organizations 在 25.x 为 preview，须显式开启，否则 organizations
 * Admin API 404）。目标 realm {@code hashmatrix} 由本测试预置（启用 {@code organizationsEnabled}），
 * 经 {@code master} 管理客户端（{@code admin-cli} / password 授权）创建——与生产
 * {@link KeycloakProvisioningConfiguration} 同款 {@link KeycloakBuilder} 连接路径。
 *
 * <p>🔴 红线：仅用脱敏占位（{@code admin/admin} 本地口令、{@code *.example.com} 域、{@code tenant-*} key），
 * 无任何真实凭据 / 主机 / 客户信息。各用例用独立 tenantKey/email，规避共享 realm 内的跨用例干扰。
 */
@Testcontainers
class KeycloakIdentityProvisionerRealIntegrationTest {

    private static final String TARGET_REALM = "hashmatrix";
    private static final String TENANT_ADMIN_ROLE = "tenant-admin";
    private static final String DOMAIN_SUFFIX = "example.com";

    @Container
    static final GenericContainer<?> KEYCLOAK =
            new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:25.0"))
                    .withExposedPorts(8080, 9000)
                    // KC 25 初始管理员用 KEYCLOAK_ADMIN(_PASSWORD)；KC_BOOTSTRAP_ADMIN_* 是 26 的形态、25 不认。
                    .withEnv("KEYCLOAK_ADMIN", "admin")
                    .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
                    .withEnv("KC_HEALTH_ENABLED", "true")
                    // 经 Docker 端口映射访问时 KC 视来源为非本地 → realm sslRequired 拦截 HTTP token（"HTTPS required"）。
                    // 信任转发头 + 客户端发 X-Forwarded-Proto:https，使请求被判为安全（仅测试环境；生产走真实 TLS）。
                    .withEnv("KC_PROXY_HEADERS", "xforwarded")
                    .withCommand("start-dev", "--features=organization")
                    .waitingFor(
                            Wait.forHttp("/health/ready")
                                    .forPort(9000)
                                    .forStatusCode(200)
                                    .withStartupTimeout(Duration.ofMinutes(3)));

    private static Keycloak kc;
    private static KeycloakProvisioningProperties props;
    private static KeycloakIdentityProvisioner provisioner;

    @BeforeAll
    static void setUp() {
        String serverUrl = "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
        // 每个出站请求带 X-Forwarded-Proto:https，配合容器 KC_PROXY_HEADERS=xforwarded，规避 Docker HTTP
        // 访问下的 realm sslRequired「HTTPS required」拦截（测试环境绕行，非生产连接方式）。
        Client httpClient =
                ClientBuilder.newBuilder()
                        .register(
                                (ClientRequestFilter)
                                        ctx -> ctx.getHeaders().add("X-Forwarded-Proto", "https"))
                        .build();
        kc =
                KeycloakBuilder.builder()
                        .serverUrl(serverUrl)
                        .realm("master")
                        .clientId("admin-cli")
                        .grantType(OAuth2Constants.PASSWORD)
                        .username("admin")
                        .password("admin")
                        .resteasyClient(httpClient)
                        .build();
        ensureTargetRealm();

        props = new KeycloakProvisioningProperties();
        props.setServerUrl(serverUrl);
        props.setAuthRealm("master");
        props.setTargetRealm(TARGET_REALM);
        props.setClientId("admin-cli");
        props.setUsername("admin");
        props.setPassword("admin");
        props.setTenantAdminRole(TENANT_ADMIN_ROLE);
        props.setDomainSuffix(DOMAIN_SUFFIX);
        // 复用同一 master 管理客户端（全局 admin 可治理任意 realm），与生产「单 Keycloak Bean」一致。
        provisioner = new KeycloakIdentityProvisioner(kc, props);
    }

    @AfterAll
    static void tearDown() {
        if (kc != null) {
            kc.close();
        }
    }

    /** 预置目标 realm 并启用 Organizations（preview 已由容器 {@code --features} 打开）；幂等。 */
    private static void ensureTargetRealm() {
        boolean exists =
                kc.realms().findAll().stream()
                        .anyMatch(r -> TARGET_REALM.equals(r.getRealm()));
        if (exists) {
            return;
        }
        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm(TARGET_REALM);
        realm.setEnabled(true);
        realm.setOrganizationsEnabled(true);
        kc.realms().create(realm);
    }

    private static ProvisioningRequest request(String tenantKey, String adminEmail) {
        return new ProvisioningRequest(
                UUID.randomUUID(),
                tenantKey,
                "Demo 部门",
                DeliveryMode.PRIVATE,
                adminEmail,
                TenantQuota.defaults());
    }

    /** 管理员邮箱域与组织域（{@code <tenantKey>.example.com}）一致，规避 Organizations 成员域约束。 */
    private static String adminEmailFor(String tenantKey) {
        return "admin@" + tenantKey + "." + DOMAIN_SUFFIX;
    }

    @Test
    void provisionCreatesRealOrgAdminUserMembershipAndRole() {
        String tenantKey = "tenant-real";
        String adminEmail = adminEmailFor(tenantKey);
        ProvisioningRequest req = request(tenantKey, adminEmail);

        String orgId = provisioner.provision(req);

        RealmResource realm = kc.realm(TARGET_REALM);
        try {
            assertThat(orgId).isNotBlank();

            // ① Organization 真实落地：按 tenantKey 检索命中，id 即返回值。
            List<OrganizationRepresentation> orgs =
                    realm.organizations().search(tenantKey, Boolean.TRUE, 0, 1);
            assertThat(orgs).hasSize(1);
            assertThat(orgs.get(0).getName()).isEqualTo(tenantKey);
            assertThat(orgs.get(0).getId()).isEqualTo(orgId);

            // ② 租户管理员 user 真实落地（按邮箱精确检索）。
            List<UserRepresentation> users = realm.users().searchByEmail(adminEmail, true);
            assertThat(users).hasSize(1);
            String userId = users.get(0).getId();

            // ③ user 是该组织成员。
            List<UserRepresentation> members = realm.organizations().get(orgId).members().getAll();
            assertThat(members).extracting(UserRepresentation::getId).contains(userId);

            // ④ user 被赋予 realm 级「租户管理员」角色（适配器幂等创建该角色）。
            List<RoleRepresentation> realmRoles =
                    realm.users().get(userId).roles().realmLevel().listAll();
            assertThat(realmRoles).extracting(RoleRepresentation::getName).contains(TENANT_ADMIN_ROLE);
        } finally {
            // ⑤ 回收：删除组织，确认按 tenantKey 检索已空（与生产 deprovision 同路径）。
            provisioner.deprovision(req);
            assertThat(realm.organizations().search(tenantKey, Boolean.TRUE, 0, 1)).isEmpty();
            // deprovision 仅删组织、不删 user；清理本用例新建的 admin user，使隔离不依赖容器是否复用。
            realm.users().searchByEmail(adminEmail, true).forEach(u -> realm.users().delete(u.getId()).close());
        }
    }

    @Test
    void deprovisionIsIdempotentWhenOrgAbsent() {
        ProvisioningRequest req = request("tenant-absent", adminEmailFor("tenant-absent"));
        // 从未开通 → 组织不存在 → 幂等跳过，不抛异常。
        assertThatNoException().isThrownBy(() -> provisioner.deprovision(req));
    }
}
