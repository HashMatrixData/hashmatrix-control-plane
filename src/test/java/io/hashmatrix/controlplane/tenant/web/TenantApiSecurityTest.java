package io.hashmatrix.controlplane.tenant.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hashmatrix.controlplane.tenant.domain.DeliveryMode;
import io.hashmatrix.controlplane.tenant.domain.Tenant;
import io.hashmatrix.controlplane.tenant.domain.TenantQuota;
import io.hashmatrix.controlplane.tenant.service.TenantService;
import io.hashmatrix.starter.security.SecurityAutoConfiguration;
import io.hashmatrix.starter.security.SecurityFilterChainConfiguration;
import io.hashmatrix.test.fixtures.MockData;
import io.hashmatrix.test.fixtures.MockTenants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WP2 鉴权矩阵切片测试（Docker-free）—— 守护「网关前置鉴权 + superadmin 门控」验收。
 *
 * <p>只装 MVC + 鉴权 starter（{@link SecurityAutoConfiguration} 提供网关预认证过滤器/配置项,
 * {@link SecurityFilterChainConfiguration} 提供默认过滤链（含 401 入口，#5 已上收 starter）；{@code TenantService} 用 {@link MockBean} 顶替，
 * 不触 DB），断言授权决策在 controller 之前完成。本机无 Docker 即可运行，与两个 Testcontainers
 * 集成测试（真实持久化）互补。
 *
 * <p>覆盖验收四条：高危端点 无头→401 / 非 superadmin→403 / superadmin→放行；只读端点仅需已认证。
 * 探针 {@code permitPaths} 放行由 {@code ControlPlaneIntegrationTest}（全上下文含 actuator）守护。
 *
 * <p>高危端点取审批单端点 {@code POST /{tenantId}/approval}（对齐契约）：401/403 在 controller 之前判定，
 * 无需请求体；superadmin 放行用例补 {@code {decision:approve}} 体（否则进 controller 缺 decision → 400）。
 */
@WebMvcTest(controllers = TenantController.class)
@Import({SecurityAutoConfiguration.class, SecurityFilterChainConfiguration.class})
@EnableMethodSecurity
class TenantApiSecurityTest {

    /** 占位路由键（401/403 在 controller 之前判定，无需库中真实租户）。 */
    private static final String TENANT_ID = "tenant-demo";
    private static final String APPROVAL = "/api/v1/tenants/" + TENANT_ID + "/approval";
    private static final String LIST = "/api/v1/tenants";

    @Autowired private MockMvc mvc;
    @MockBean private TenantService service;

    private static Tenant sampleTenant() {
        return Tenant.register(
                MockTenants.TENANT_DEMO,
                "Demo 部门",
                DeliveryMode.PRIVATE,
                MockData.email("admin"),
                TenantQuota.defaults());
    }

    @Test
    void highRiskEndpointWithoutHeaderIsUnauthorized() throws Exception {
        // 无网关身份头（匿名）→ 401，授权在 controller 之前拒绝（service 不被触达）。
        mvc.perform(post(APPROVAL)).andExpect(status().isUnauthorized());
    }

    @Test
    void highRiskEndpointAsNonSuperadminIsForbidden() throws Exception {
        // 已认证但角色不足（USER）→ 403（方法级 @PreAuthorize 在 controller 体前拒，无需请求体）。
        mvc.perform(post(APPROVAL).header("X-User", "alice").header("X-Roles", "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void highRiskEndpointAsSuperadminIsAllowed() throws Exception {
        when(service.approve(anyString(), any())).thenReturn(sampleTenant());

        mvc.perform(
                        post(APPROVAL)
                                .header("X-User", "ops")
                                .header("X-Roles", "superadmin")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"decision\":\"approve\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void readOnlyEndpointAllowsAnyAuthenticatedUser() throws Exception {
        // 只读端点仅需已认证（非 superadmin 亦可）。
        when(service.list(any(), any())).thenReturn(Page.empty());

        mvc.perform(get(LIST).header("X-User", "alice").header("X-Roles", "USER"))
                .andExpect(status().isOk());
    }

    @Test
    void readOnlyEndpointWithoutHeaderIsUnauthorized() throws Exception {
        // 默认非放行路径需认证：无头读取 → 401。
        mvc.perform(get(LIST)).andExpect(status().isUnauthorized());
    }
}
