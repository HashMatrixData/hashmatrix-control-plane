package io.hashmatrix.controlplane;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hashmatrix.test.fixtures.MockData;
import io.hashmatrix.test.fixtures.MockTenants;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
 * WP5 · 末端集成测试（依赖图唯一汇聚点 · Testcontainers PostgreSQL + stub 开通适配器 · 零生产改动）。
 *
 * <p>守护<b>没有任何单 WP 能独立守护的跨边界不变量</b>：鉴权过滤链（WP2）须在<b>真实生产装配</b>下
 * 同时正确门控<b>生命周期端点</b>（{@code TenantController}）与<b>新 {@code /me/tenants}</b>（WP3
 * {@code MeController}），且<b>启用 security 后开通 approve 链路仍贯通至 {@code ACTIVE}</b>。
 *
 * <p>与互补测试的边界（避免重复、各守其责）：
 *
 * <ul>
 *   <li>{@code TenantApiSecurityTest}（WP2 切片，Docker-free）：仅 {@code TenantController}、由<b>测试类</b>
 *       自带 {@code @EnableMethodSecurity}——非生产装配，且不含 {@code /me/tenants}。
 *   <li>{@code MeControllerTest}（WP3 切片，Docker-free）：仅 {@code /me/tenants} 的形状/401。
 *   <li>{@code ControlPlaneIntegrationTest}：开通 happy-path / 状态机 / 冲突等业务边界（非系统性鉴权矩阵）。
 * </ul>
 *
 * <p><b>探针覆盖边界</b>：本类只守「安全链<b>放行</b> permitPaths（探针不被 401/403 拦）」这一语义子集——
 * MOCK web 环境下 actuator 挂在独立 management 上下文、MockMvc 不可达，故断言「非 401/403」而非具体 200。
 * 探针在生产独立 9081 端口的<b>真实 200 可达性</b>留待 M1 K8s 部署期的 readiness 探针 / 冒烟覆盖（部署关切，正交）。
 *
 * 本类独有价值：以<b>启动期 starter 默认过滤链 + 方法安全</b>（非测试类自带）一处覆盖<b>两个控制器</b>的
 * 401/403/200 三态矩阵 + 探针放行 + 「security on 不破开通」，作为父 #3 各 WP 交叉边界的回归兜底。
 *
 * <p>鉴权在控制器/查库<b>之前</b>完成：401（过滤链）/403（方法级 {@code @PreAuthorize}）用例均无需库中真实
 * 租户，故用合法 UUID 占位 {@link #DUMMY_ID}。无活集群——开通走 stub 时序（{@code provisioning.mode=stub} 默认）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SecurityMatrixIntegrationTest {

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

    private static final String TENANTS = "/api/v1/tenants";
    private static final String ME_TENANTS = "/api/v1/me/tenants";

    /** 合法 UUID 占位：401/403 在 controller 之前判定，无需库中真实租户。 */
    private static final String DUMMY_ID = "11111111-1111-4111-8111-111111111111";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    /** 网关下发的普通用户身份头（脱敏占位用户名 + USER 角色）。 */
    private static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder builder) {
        return builder.header("X-User", "alice").header("X-Roles", "USER");
    }

    /** 网关下发的平台管理员身份头（脱敏占位用户名 + superadmin 角色）。 */
    private static MockHttpServletRequestBuilder asSuperadmin(MockHttpServletRequestBuilder builder) {
        return builder.header("X-User", "ops-admin").header("X-Roles", "superadmin");
    }

    /** 全部高危端点（跨租户高权变更，须 {@code superadmin}）。审批为单端点 {@code /approval}（对齐契约）。 */
    static Stream<Arguments> highRiskEndpoints() {
        return Stream.of(
                arguments("POST .../approval", post(TENANTS + "/" + DUMMY_ID + "/approval")),
                arguments("POST .../suspend", post(TENANTS + "/" + DUMMY_ID + "/suspend")),
                arguments("POST .../resume", post(TENANTS + "/" + DUMMY_ID + "/resume")),
                arguments("DELETE .../{tenantId}", delete(TENANTS + "/" + DUMMY_ID)));
    }

    /** 全部需认证端点（高危 + 只读 + 注册 + WP3 自助视图）——匿名一律 401。 */
    static Stream<Arguments> protectedEndpoints() {
        return Stream.concat(
                highRiskEndpoints(),
                Stream.of(
                        arguments("POST /tenants (register)", post(TENANTS)),
                        arguments("GET /tenants (list)", get(TENANTS)),
                        arguments("GET /tenants/{id}", get(TENANTS + "/" + DUMMY_ID)),
                        arguments("GET /me/tenants", get(ME_TENANTS))));
    }

    @ParameterizedTest(name = "匿名 → 401: {0}")
    @MethodSource("protectedEndpoints")
    void anonymousIsUnauthorizedAcrossProtectedSurface(
            String label, MockHttpServletRequestBuilder request) throws Exception {
        // 无网关身份头：过滤链 anyRequest().authenticated() + 401 entryPoint，在进入控制器/查库前即拒。
        mvc.perform(request).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "USER → 403: {0}")
    @MethodSource("highRiskEndpoints")
    void normalUserForbiddenOnHighRiskEndpoints(
            String label, MockHttpServletRequestBuilder request) throws Exception {
        // 已认证但非 superadmin：方法级 @PreAuthorize 在控制器方法体前拒 → 403（service 不被触达）。
        mvc.perform(asUser(request)).andExpect(status().isForbidden());
    }

    @Test
    void normalUserCanSelfViewAndRead() throws Exception {
        // WP3 自助视图 + 只读端点仅需已认证（USER 即可）→ 200。本用例守护「USER 可读」鉴权语义；
        // /me/tenants 恒返回数组（契约「响应一律数组、可为空」），/tenants 为契约 TenantList（items 数组 + 分页元）。
        mvc.perform(asUser(get(ME_TENANTS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
        mvc.perform(asUser(get(TENANTS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @ParameterizedTest(name = "探针放行(免认证不被拦): {0}")
    @ValueSource(strings = {"/actuator/health", "/actuator/info", "/actuator/prometheus"})
    void probeEndpointsAreNotBlockedBySecurity(String path) throws Exception {
        // permitPaths（health/info/prometheus）放行 = 安全链不拦探针路径：匿名访问不会被 401/403 拒。
        // 本测试 app 上下文未挂载 actuator（生产 actuator 独立 9081，与「安全链是否放行」正交），故落到 404
        // （资源不在此上下文）而非 401（被安全拦截）——「非 401/403」即精确证明放行，且不耦合 management 端口拓扑。
        int sc = mvc.perform(get(path)).andReturn().getResponse().getStatus();
        assertTrue(
                sc != 401 && sc != 403,
                () -> "探针路径须被安全链放行（非 401/403），实际=" + sc + " path=" + path);
    }

    @Test
    void unknownRouteUnderAuthIsNotFoundNotServerError() throws Exception {
        // 守护 starter-web GlobalExceptionHandler：实现 ErrorResponse 的框架异常（NoResourceFound）按携带状态渲染——
        // 已认证访问不存在路由 → 404（而非被兜底 @ExceptionHandler(Exception) 吞成 500）。
        mvc.perform(asUser(get("/api/v1/no-such-route"))).andExpect(status().isNotFound());
    }

    @Test
    void superadminProvisionsTenantThroughApproveUnderSecurity() throws Exception {
        // 跨边界不变量：启用 security 后，superadmin 走 register → approve 仍贯通至 ACTIVE（stub 时序、无活集群）；
        // 同时即「superadmin → 全 200」在写链路上的代表用例（201 创建 + 200 审批开通）。
        String body =
                json.writeValueAsString(
                        Map.of(
                                "tenantId", MockTenants.TENANT_DEMO,
                                "displayName", "Demo 部门",
                                "adminEmail", MockData.email("admin")));
        String location =
                mvc.perform(
                                asSuperadmin(
                                        post(TENANTS)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(body)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.status").value("registered"))
                        .andExpect(header().exists("Location"))
                        .andReturn()
                        .getResponse()
                        .getHeader("Location");
        assertNotNull(location, "注册响应应带 Location 头");
        String tenantId = location.substring(location.lastIndexOf('/') + 1);

        mvc.perform(
                        asSuperadmin(
                                post(TENANTS + "/" + tenantId + "/approval")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"decision\":\"approve\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("active"));
    }
}
