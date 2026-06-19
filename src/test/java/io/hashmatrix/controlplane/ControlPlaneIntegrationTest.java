package io.hashmatrix.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hashmatrix.test.fixtures.MockData;
import io.hashmatrix.test.fixtures.MockTenants;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 控制平面端到端集成测试（Testcontainers PostgreSQL + stub 开通适配器）。
 *
 * <p>覆盖 DoD：自助注册 → 审批门控 → 自动 provision，开通 {@code tenant-demo} 全链路（身份 +
 * namespace + schema/db + secrets），并校验状态机非法流转与 key 冲突的边界。无活集群——开通走
 * stub 时序（{@code provisioning.mode=stub} 默认）。
 *
 * <p>鉴权（starter-security）：加 {@code starter-security} 后非放行路径默认需认证，故全部请求带上网关
 * 下发的 {@code X-User}/{@code X-Roles: SUPERADMIN}（经 {@link #asSuperadmin}）——既满足只读端点的
 * {@code authenticated()}，也满足高危端点的 {@code SUPERADMIN} 门控；细粒度鉴权矩阵（401/403）由
 * Docker-free 的 {@code TenantApiSecurityTest} 守护。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ControlPlaneIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("control_plane")
                    .withUsername("control_plane")
                    .withPassword("control_plane");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        // 用字面回环 IP，避免依赖 JVM 对 "localhost" 的名称解析（某些 CI/容器环境解析受限）。
        registry.add(
                "spring.datasource.url", () -> POSTGRES.getJdbcUrl().replace("localhost", "127.0.0.1"));
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    /** 给请求盖上网关下发的平台管理员身份头（脱敏占位用户名 + SUPERADMIN 角色）。 */
    private static MockHttpServletRequestBuilder asSuperadmin(MockHttpServletRequestBuilder builder) {
        return builder.header("X-User", "ops-admin").header("X-Roles", "SUPERADMIN");
    }

    private String registerBody(String tenantKey) throws Exception {
        return json.writeValueAsString(
                Map.of(
                        "tenantKey", tenantKey,
                        "displayName", "Demo 部门",
                        "deliveryMode", "PRIVATE",
                        "adminEmail", MockData.email("admin")));
    }

    @Test
    void selfRegisterThenApproveProvisionsTenantEndToEnd() throws Exception {
        // 自助注册 → REGISTERED
        String location =
                mvc.perform(
                                asSuperadmin(
                                        post("/api/v1/tenants")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(registerBody(MockTenants.TENANT_DEMO))))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.code").value("0"))
                        .andExpect(jsonPath("$.data.status").value("REGISTERED"))
                        .andExpect(jsonPath("$.data.tenantKey").value("tenant-demo"))
                        .andReturn()
                        .getResponse()
                        .getHeader("Location");

        String id = location.substring(location.lastIndexOf('/') + 1);

        // 审批通过 → 同步开通 → ACTIVE，且回写接入信息（身份/namespace/schema）
        mvc.perform(asSuperadmin(post("/api/v1/tenants/" + id + "/approve")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.keycloakOrgId").value("org-tenant-demo"))
                .andExpect(jsonPath("$.data.namespace").value("tenant-tenant-demo"))
                .andExpect(jsonPath("$.data.dbSchema").value("tenant-demo"));

        // 注销 → DELETED 终态
        mvc.perform(asSuperadmin(delete("/api/v1/tenants/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELETED"));
    }

    @Test
    void duplicateTenantKeyIsRejected() throws Exception {
        mvc.perform(
                        asSuperadmin(
                                post("/api/v1/tenants")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(registerBody(MockTenants.ACME))))
                .andExpect(status().isCreated());

        mvc.perform(
                        asSuperadmin(
                                post("/api/v1/tenants")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(registerBody(MockTenants.ACME))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_KEY_CONFLICT"));
    }

    @Test
    void illegalTransitionIsRejected() throws Exception {
        String location =
                mvc.perform(
                                asSuperadmin(
                                        post("/api/v1/tenants")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(registerBody(MockTenants.BETA))))
                        .andReturn()
                        .getResponse()
                        .getHeader("Location");
        String id = location.substring(location.lastIndexOf('/') + 1);

        // REGISTERED 直接 resume（→ACTIVE）非法 → 409
        mvc.perform(asSuperadmin(post("/api/v1/tenants/" + id + "/resume")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TENANT_TRANSITION"));
    }

    @Test
    void invalidTenantKeyFailsValidation() throws Exception {
        mvc.perform(
                        asSuperadmin(
                                post("/api/v1/tenants")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(registerBody("Bad_Key"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getUnknownTenantReturns404() throws Exception {
        mvc.perform(asSuperadmin(get("/api/v1/tenants/00000000-0000-4000-8000-000000000000")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TENANT_NOT_FOUND"));
    }

    @Test
    void probeEndpointsArePermittedWithoutAuth() throws Exception {
        // permitPaths（探针）免认证放行——无网关身份头仍 200，守护 WP2「探针放行」验收。
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void highRiskEndpointRejectsNonSuperadminUnderRealWiring() throws Exception {
        // 真实生产装配下（方法安全来自 starter 的 @EnableMethodSecurity，本仓 SecurityConfiguration 仅补
        // 401 entryPoint）守护：已认证但非 superadmin 调高危端点 → 403。授权在 controller 之前完成，
        // 无需库中真实租户。补 TenantApiSecurityTest（切片用测试类自带 @EnableMethodSecurity）未覆盖的
        // 「生产装配链路」回归——若 starter 方法安全装配漂移致门控静默失效，本断言兜底。
        mvc.perform(
                        post("/api/v1/tenants/00000000-0000-4000-8000-000000000000/approve")
                                .header("X-User", "alice")
                                .header("X-Roles", "USER"))
                .andExpect(status().isForbidden());
    }
}
