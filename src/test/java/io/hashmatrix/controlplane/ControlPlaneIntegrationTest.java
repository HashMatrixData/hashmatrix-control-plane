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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 控制平面端到端集成测试（Testcontainers PostgreSQL + stub 开通适配器）。
 *
 * <p>覆盖 DoD：自助注册 → 审批门控 → 自动 provision，开通 {@code tenant-demo} 全链路（身份 +
 * namespace + schema/db + secrets），并校验状态机非法流转与 key 冲突的边界。无活集群——开通走
 * stub 时序（{@code provisioning.mode=stub} 默认）。
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
                                post("/api/v1/tenants")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(registerBody(MockTenants.TENANT_DEMO)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.code").value("0"))
                        .andExpect(jsonPath("$.data.status").value("REGISTERED"))
                        .andExpect(jsonPath("$.data.tenantKey").value("tenant-demo"))
                        .andReturn()
                        .getResponse()
                        .getHeader("Location");

        String id = location.substring(location.lastIndexOf('/') + 1);

        // 审批通过 → 同步开通 → ACTIVE，且回写接入信息（身份/namespace/schema）
        mvc.perform(post("/api/v1/tenants/" + id + "/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.keycloakOrgId").value("org-tenant-demo"))
                .andExpect(jsonPath("$.data.namespace").value("tenant-tenant-demo"))
                .andExpect(jsonPath("$.data.dbSchema").value("tenant-demo"));

        // 注销 → DELETED 终态
        mvc.perform(delete("/api/v1/tenants/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELETED"));
    }

    @Test
    void duplicateTenantKeyIsRejected() throws Exception {
        mvc.perform(
                        post("/api/v1/tenants")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(registerBody(MockTenants.ACME)))
                .andExpect(status().isCreated());

        mvc.perform(
                        post("/api/v1/tenants")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(registerBody(MockTenants.ACME)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_KEY_CONFLICT"));
    }

    @Test
    void illegalTransitionIsRejected() throws Exception {
        String location =
                mvc.perform(
                                post("/api/v1/tenants")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(registerBody(MockTenants.BETA)))
                        .andReturn()
                        .getResponse()
                        .getHeader("Location");
        String id = location.substring(location.lastIndexOf('/') + 1);

        // REGISTERED 直接 resume（→ACTIVE）非法 → 409
        mvc.perform(post("/api/v1/tenants/" + id + "/resume"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TENANT_TRANSITION"));
    }

    @Test
    void invalidTenantKeyFailsValidation() throws Exception {
        mvc.perform(
                        post("/api/v1/tenants")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(registerBody("Bad_Key")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getUnknownTenantReturns404() throws Exception {
        mvc.perform(get("/api/v1/tenants/00000000-0000-4000-8000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TENANT_NOT_FOUND"));
    }
}
