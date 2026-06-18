package io.hashmatrix.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hashmatrix.controlplane.provisioning.spi.ComputeProvisioner;
import io.hashmatrix.controlplane.provisioning.spi.ProvisioningRequest;
import io.hashmatrix.test.fixtures.MockData;
import io.hashmatrix.test.fixtures.MockTenants;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 开通失败回退的**真实事务**回归测试（守护 code-review [B1]）。
 *
 * <p>用 {@link MockBean} 让 {@link ComputeProvisioner} 抛错触发 {@code ProvisioningException}，断言
 * approve 后**重新查库**租户落在 {@code APPROVING} 且 statusReason 留痕——验证失败回退态不被
 * {@code @Transactional} 默认回滚规则卷走（依赖 {@code noRollbackFor = ProvisioningException.class}）。
 *
 * <p>单独成类：本类 {@code @MockBean} 仅替换 ComputeProvisioner（Identity/Data/Secrets 仍为 stub），
 * 不污染 {@link ControlPlaneIntegrationTest} 的 happy-path 上下文。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProvisioningFailureIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("control_plane")
                    .withUsername("control_plane")
                    .withPassword("control_plane");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url", () -> POSTGRES.getJdbcUrl().replace("localhost", "127.0.0.1"));
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @MockBean private ComputeProvisioner compute;

    @Test
    void provisioningFailureRevertsToApprovingAndPersistsReason() throws Exception {
        when(compute.provision(any(ProvisioningRequest.class)))
                .thenThrow(new IllegalStateException("apiserver 不可达"));

        String body =
                json.writeValueAsString(
                        Map.of(
                                "tenantKey", MockTenants.TENANT_DEMO,
                                "displayName", "Demo 部门",
                                "deliveryMode", "PRIVATE",
                                "adminEmail", MockData.email("admin")));
        String location =
                mvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getHeader("Location");
        String id = location.substring(location.lastIndexOf('/') + 1);

        // 开通在 compute 步失败 → 502。
        mvc.perform(post("/api/v1/tenants/" + id + "/approve"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROVISIONING_FAILED"));

        // 关键断言：重新查库（独立读），回退态须已提交——APPROVING + 失败留痕。
        mvc.perform(get("/api/v1/tenants/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVING"))
                .andExpect(jsonPath("$.data.statusReason").value(org.hamcrest.Matchers.containsString("compute")));
    }
}
