package io.hashmatrix.controlplane.provisioning.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.hashmatrix.controlplane.provisioning.spi.ProvisioningRequest;
import io.hashmatrix.controlplane.tenant.domain.DeliveryMode;
import io.hashmatrix.controlplane.tenant.domain.TenantQuota;
import io.hashmatrix.controlplane.tenant.member.MemberUserNotFoundException;
import io.hashmatrix.controlplane.tenant.member.OrgMember;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 真实 Keycloak 成员目录集成测试（Testcontainers · ST3 末端守护）—— 把 {@link KeycloakOrgMemberDirectoryTest}
 * 的 deep-stub 抬升为<b>真实 Keycloak Organizations</b> 验证，兑现 M2 链③验收：
 * 「邀请 → 列表回显 → 移除，<b>真实改 Keycloak Org membership</b>」+ <b>跨租户（跨 org）成员隔离</b>。
 *
 * <p><b>这是本拆分中唯一以真实身份后端守护「跨边界成员隔离」不变量的测试</b>：ST1/ST2 用 mock/切片覆盖适配器
 * 逻辑与 tenant-admin 门控（403/401 见 {@code OrgMemberControllerTest}），本 IT 补真实后端的端到端证明。
 *
 * <p>容器/连接路径与 {@link KeycloakIdentityProvisionerRealIntegrationTest} 同款（KC 25 +
 * {@code --features=organization}，{@code X-Forwarded-Proto:https} 绕行 Docker HTTP 的 sslRequired）。
 * 两个组织经生产 {@link KeycloakIdentityProvisioner} 真实开通，成员经被测 {@link KeycloakOrgMemberDirectory} 读写。
 *
 * <p>🔴 红线：仅脱敏占位（{@code admin/admin} 本地口令、{@code tenant-mem-*.example.com} 域、{@code member@…}），
 * 无任何真实凭据 / 主机 / 客户信息。
 */
@Testcontainers
class KeycloakOrgMemberDirectoryRealIntegrationTest {

    private static final String TARGET_REALM = "hashmatrix";
    private static final String DOMAIN_SUFFIX = "example.com";

    @Container
    static final GenericContainer<?> KEYCLOAK =
            new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:25.0"))
                    .withExposedPorts(8080, 9000)
                    .withEnv("KEYCLOAK_ADMIN", "admin")
                    .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
                    .withEnv("KC_HEALTH_ENABLED", "true")
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
    private static KeycloakOrgMemberDirectory directory;

    @BeforeAll
    static void setUp() {
        String serverUrl = "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
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
        props.setTenantAdminRole("tenant-admin");
        props.setDomainSuffix(DOMAIN_SUFFIX);
        provisioner = new KeycloakIdentityProvisioner(kc, props);
        directory = new KeycloakOrgMemberDirectory(kc, props);
    }

    @AfterAll
    static void tearDown() {
        if (kc != null) {
            kc.close();
        }
    }

    private static void ensureTargetRealm() {
        boolean exists =
                kc.realms().findAll().stream().anyMatch(r -> TARGET_REALM.equals(r.getRealm()));
        if (exists) {
            return;
        }
        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm(TARGET_REALM);
        realm.setEnabled(true);
        realm.setOrganizationsEnabled(true);
        kc.realms().create(realm);
    }

    private static ProvisioningRequest request(String tenantKey) {
        return new ProvisioningRequest(
                UUID.randomUUID(),
                tenantKey,
                "Demo 部门",
                DeliveryMode.PRIVATE,
                "admin@" + tenantKey + "." + DOMAIN_SUFFIX,
                TenantQuota.defaults());
    }

    /** 在 realm 建一个已存在用户（成员候选），email 域与目标 org 域一致以满足 Organizations 成员域约束。 */
    private static String createUser(RealmResource realm, String email) {
        UserRepresentation u = new UserRepresentation();
        u.setUsername(email);
        u.setEmail(email);
        u.setEnabled(true);
        u.setEmailVerified(true);
        try (Response resp = realm.users().create(u)) {
            return CreatedResponseUtil.getCreatedId(resp);
        }
    }

    @Test
    void memberLifecycleAndCrossOrgIsolation() {
        String tenantA = "tenant-mem-a";
        String tenantB = "tenant-mem-b";
        ProvisioningRequest reqA = request(tenantA);
        ProvisioningRequest reqB = request(tenantB);
        String orgA = provisioner.provision(reqA);
        String orgB = provisioner.provision(reqB);
        RealmResource realm = kc.realm(TARGET_REALM);
        String memberEmail = "member@" + tenantA + "." + DOMAIN_SUFFIX;
        String memberId = createUser(realm, memberEmail);

        try {
            // ① 邀请：把已存在用户加入 org A。
            OrgMember added = directory.addExistingUser(orgA, memberEmail);
            assertThat(added.id()).isEqualTo(memberId);
            assertThat(added.email()).isEqualTo(memberEmail);

            // ② 列表回显：org A 含该成员。
            assertThat(directory.list(orgA)).extracting(OrgMember::id).contains(memberId);

            // ③ 跨租户隔离（核心不变量）：org B 的成员列表绝不含 org A 的成员。
            assertThat(directory.list(orgB)).extracting(OrgMember::id).doesNotContain(memberId);

            // ④ 幂等加入：重复加入不报错、不重复。
            directory.addExistingUser(orgA, memberEmail);
            assertThat(directory.list(orgA)).filteredOn(m -> m.id().equals(memberId)).hasSize(1);

            // ⑤ 移除：org A 不再含该成员；重复移除幂等。
            directory.remove(orgA, memberId);
            assertThat(directory.list(orgA)).extracting(OrgMember::id).doesNotContain(memberId);
            directory.remove(orgA, memberId);
        } finally {
            provisioner.deprovision(reqA);
            provisioner.deprovision(reqB);
            realm.users().searchByEmail(memberEmail, true)
                    .forEach(u -> realm.users().delete(u.getId()).close());
            realm.users().searchByEmail("admin@" + tenantA + "." + DOMAIN_SUFFIX, true)
                    .forEach(u -> realm.users().delete(u.getId()).close());
            realm.users().searchByEmail("admin@" + tenantB + "." + DOMAIN_SUFFIX, true)
                    .forEach(u -> realm.users().delete(u.getId()).close());
        }
    }

    @Test
    void addUnknownUserThrowsMemberUserNotFound() {
        String tenant = "tenant-mem-c";
        ProvisioningRequest req = request(tenant);
        String orgId = provisioner.provision(req);
        try {
            assertThatExceptionOfType(MemberUserNotFoundException.class)
                    .isThrownBy(
                            () ->
                                    directory.addExistingUser(
                                            orgId, "ghost@" + tenant + "." + DOMAIN_SUFFIX));
        } finally {
            provisioner.deprovision(req);
            kc.realm(TARGET_REALM)
                    .users()
                    .searchByEmail("admin@" + tenant + "." + DOMAIN_SUFFIX, true)
                    .forEach(u -> kc.realm(TARGET_REALM).users().delete(u.getId()).close());
        }
    }
}
