package io.hashmatrix.controlplane.tenant.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hashmatrix.controlplane.tenant.domain.DeliveryMode;
import io.hashmatrix.controlplane.tenant.domain.Tenant;
import io.hashmatrix.controlplane.tenant.domain.TenantQuota;
import io.hashmatrix.controlplane.tenant.service.TenantService;
import io.hashmatrix.starter.security.SecurityAutoConfiguration;
import io.hashmatrix.starter.security.SecurityFilterChainConfiguration;
import io.hashmatrix.test.fixtures.MockData;
import io.hashmatrix.test.fixtures.MockTenants;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WP3 切片测试（Docker-free）—— 守护 {@code GET /api/v1/me/tenants}（当前用户 membership 数组）验收。
 *
 * <p>只装 MVC + 鉴权 starter（{@link SecurityAutoConfiguration} 提供网关预认证过滤器，
 * {@link SecurityFilterChainConfiguration} 提供默认过滤链（含 401 入口，#5 已上收 starter）；{@code TenantService} 用 {@link MockBean} 顶替，不触 DB），
 * 断言端点路径、数组形状与 401。本机无 Docker 即可运行。
 *
 * <p>覆盖验收：① 由<b>独立 {@link MeController}</b> 承载，路径 {@code /api/v1/me/tenants}
 * （<b>非</b> {@code /api/v1/tenants/me}）；② 已认证（非 superadmin 亦可）→ membership 数组（M1 1 个）；
 * ③ 无头 → 401；④ 无 membership → 空数组（契约「响应一律数组、可为空」）。
 */
@WebMvcTest(controllers = MeController.class)
@Import({SecurityAutoConfiguration.class, SecurityFilterChainConfiguration.class})
@EnableMethodSecurity
class MeControllerTest {

    /** 契约资源根 {@code /v1/me/tenants}（网关 strip 前应用内 {@code /api/v1/me/tenants}）。 */
    private static final String ME_TENANTS = "/api/v1/me/tenants";

    @Autowired private MockMvc mvc;
    @MockBean private TenantService service;

    private static Tenant sampleTenant() {
        return tenant(MockTenants.TENANT_DEMO);
    }

    private static Tenant tenant(String tenantKey) {
        return Tenant.register(
                tenantKey,
                "Demo 部门",
                DeliveryMode.PRIVATE,
                MockData.email("admin"),
                TenantQuota.defaults());
    }

    @Test
    void returnsMembershipArrayForAuthenticatedUser() throws Exception {
        // 控制器须以「当前主体名」（X-User）查 membership——以同一表达式 stub，校验未裸读/未错传 header。
        when(service.listMembershipsFor(MockData.email("admin"))).thenReturn(List.of(sampleTenant()));

        mvc.perform(
                        get(ME_TENANTS)
                                .header("X-User", MockData.email("admin"))
                                .header("X-Roles", "USER")) // 非 superadmin 亦可
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.length()").value(1))
                // 契约 tenantId/tenantKey 在 M1 均映射领域路由键（demo 同值）。
                .andExpect(jsonPath("$.data[0].tenantId").value("tenant-demo"))
                .andExpect(jsonPath("$.data[0].tenantKey").value("tenant-demo"))
                .andExpect(jsonPath("$.data[0].displayName").value("Demo 部门"))
                .andExpect(jsonPath("$.data[0].status").value("registered"));
    }

    @Test
    void withoutGatewayHeaderIsUnauthorized() throws Exception {
        // 无网关身份头（匿名）→ 401（沿用 WP2 过滤链 + 401 entryPoint）。
        mvc.perform(get(ME_TENANTS)).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsAllMembershipsAsArrayForMultiTenantUser() throws Exception {
        // D1（单 User + 多 Org Membership）不可返工约束：DTO 一律按数组——多 membership 须全部出现在数组中，
        // 守护「即便 M1 多为 1 个，形状也按多租户预留」。
        when(service.listMembershipsFor(MockData.email("admin")))
                .thenReturn(List.of(tenant(MockTenants.TENANT_DEMO), tenant(MockTenants.ACME)));

        mvc.perform(
                        get(ME_TENANTS)
                                .header("X-User", MockData.email("admin"))
                                .header("X-Roles", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].tenantId").value("tenant-demo"))
                .andExpect(jsonPath("$.data[1].tenantId").value(MockTenants.ACME));
    }

    @Test
    void returnsEmptyArrayWhenNoMembership() throws Exception {
        // 当前用户不匹配任何租户 → 空数组（而非 404/空体），守护契约「响应一律数组、可为空」。
        when(service.listMembershipsFor(MockData.email("stranger"))).thenReturn(List.of());

        mvc.perform(
                        get(ME_TENANTS)
                                .header("X-User", MockData.email("stranger"))
                                .header("X-Roles", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
