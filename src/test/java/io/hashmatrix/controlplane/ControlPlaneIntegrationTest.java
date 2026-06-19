package io.hashmatrix.controlplane;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * <p>端点一律以路由键 {@code tenantId}（= tenantKey）寻址；审批走单端点 {@code POST /{tenantId}/approval}
 * （{@code decision=approve|reject}）；视图结构对齐契约（嵌套 {@code organization}/{@code dataPlane}）。
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

    /** 审批通过请求体（单端点 {@code /approval}，对齐契约 {@code ApprovalDecision}）。 */
    private static final String APPROVE_BODY = "{\"decision\":\"approve\"}";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    /** 给请求盖上网关下发的平台管理员身份头（脱敏占位用户名 + SUPERADMIN 角色）。 */
    private static MockHttpServletRequestBuilder asSuperadmin(MockHttpServletRequestBuilder builder) {
        return builder.header("X-User", "ops-admin").header("X-Roles", "SUPERADMIN");
    }

    /** 注册体不含 {@code deliveryMode}（部署级、非按租户）。 */
    private String registerBody(String tenantKey) throws Exception {
        return json.writeValueAsString(
                Map.of(
                        "tenantId", tenantKey,
                        "displayName", "Demo 部门",
                        "adminEmail", MockData.email("admin")));
    }

    @Test
    void selfRegisterThenApproveProvisionsTenantEndToEnd() throws Exception {
        // 自助注册 → REGISTERED；Location 以路由键寻址（/api/v1/tenants/{tenantId}）。
        String location =
                mvc.perform(
                                asSuperadmin(
                                        post("/api/v1/tenants")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(registerBody(MockTenants.TENANT_DEMO))))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.code").value("0"))
                        .andExpect(jsonPath("$.data.status").value("registered"))
                        .andExpect(jsonPath("$.data.tenantId").value("tenant-demo"))
                        .andReturn()
                        .getResponse()
                        .getHeader("Location");

        String tenantId = location.substring(location.lastIndexOf('/') + 1);

        // 审批通过 → 同步开通 → ACTIVE，且回写接入信息（嵌套 organization/dataPlane）。
        mvc.perform(
                        asSuperadmin(
                                post("/api/v1/tenants/" + tenantId + "/approval")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(APPROVE_BODY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("active"))
                .andExpect(jsonPath("$.data.organization.orgId").value("org-tenant-demo"))
                .andExpect(jsonPath("$.data.organization.orgAlias").value("tenant-demo"))
                .andExpect(jsonPath("$.data.dataPlane.namespace").value("tenant-tenant-demo"))
                .andExpect(jsonPath("$.data.dataPlane.dbSchema").value("tenant-demo"));

        // 注销 → DELETED 终态
        mvc.perform(asSuperadmin(delete("/api/v1/tenants/" + tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("deleted"));
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
        String tenantId = location.substring(location.lastIndexOf('/') + 1);

        // REGISTERED 直接 resume（→ACTIVE）非法 → 409
        mvc.perform(asSuperadmin(post("/api/v1/tenants/" + tenantId + "/resume")))
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
        mvc.perform(asSuperadmin(get("/api/v1/tenants/ghost-tenant")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TENANT_NOT_FOUND"));
    }

    /** 注册并返回路由键（Location 末段 = tenantId）。各测试用独立 key，避免共享容器内 uq_tenant_key 冲突。 */
    private String registerReturningId(String tenantKey) throws Exception {
        String location =
                mvc.perform(
                                asSuperadmin(
                                        post("/api/v1/tenants")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(registerBody(tenantKey))))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getHeader("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }

    /** 审批驳回 → DELETED 终态并留痕（单端点 reject 分支，对齐契约 {@code reject → deleted}）。 */
    @Test
    void rejectDeletesTenantWithReason() throws Exception {
        String tenantId = registerReturningId("tenant-reject");
        mvc.perform(
                        asSuperadmin(
                                post("/api/v1/tenants/" + tenantId + "/approval")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"decision\":\"reject\",\"reason\":\"材料不全\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("deleted"))
                .andExpect(
                        jsonPath("$.data.statusReason")
                                .value(org.hamcrest.Matchers.containsString("驳回")));
    }

    /** 契约约束：reject 缺 reason → 400（驳回不可逆，须留审计）。 */
    @Test
    void rejectWithoutReasonIsBadRequest() throws Exception {
        String tenantId = registerReturningId("tenant-rej-noreason");
        mvc.perform(
                        asSuperadmin(
                                post("/api/v1/tenants/" + tenantId + "/approval")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"decision\":\"reject\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_APPROVAL"));
    }

    /** 审批体缺 decision（空体）→ 400（控制器 null 分支，专用异常映射）。 */
    @Test
    void approvalWithoutDecisionIsBadRequest() throws Exception {
        String tenantId = registerReturningId("tenant-no-decision");
        mvc.perform(asSuperadmin(post("/api/v1/tenants/" + tenantId + "/approval")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_APPROVAL"));
    }

    @Test
    void probeEndpointIsNotBlockedBySecurity() throws Exception {
        // permitPaths（探针）放行 = 安全链不拦——无网关身份头不会被 401/403 拒（本上下文未挂载 actuator → 404，
        // 而非 401）。完整探针矩阵由 SecurityMatrixIntegrationTest 守护，此处留一条 happy-path 上下文兜底。
        int sc = mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();
        assertTrue(sc != 401 && sc != 403, () -> "探针应被安全链放行（非 401/403），实际=" + sc);
    }

    @Test
    void highRiskEndpointRejectsNonSuperadminUnderRealWiring() throws Exception {
        // 真实生产装配下（方法安全 + 401 entryPoint 均来自 starter 默认过滤链，#5 已上收 starter-security）
        // 守护：已认证但非 superadmin 调高危端点 → 403。授权在 controller 之前完成，
        // 无需库中真实租户、无需请求体（@PreAuthorize 在 controller 体前拒）。
        mvc.perform(
                        post("/api/v1/tenants/ghost-tenant/approval")
                                .header("X-User", "alice")
                                .header("X-Roles", "USER"))
                .andExpect(status().isForbidden());
    }
}
