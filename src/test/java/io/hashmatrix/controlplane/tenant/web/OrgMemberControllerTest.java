package io.hashmatrix.controlplane.tenant.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hashmatrix.controlplane.tenant.member.MemberUserNotFoundException;
import io.hashmatrix.controlplane.tenant.member.OrgMember;
import io.hashmatrix.controlplane.tenant.member.OrgMemberService;
import io.hashmatrix.starter.security.SecurityAutoConfiguration;
import io.hashmatrix.starter.security.SecurityFilterChainConfiguration;
import io.hashmatrix.starter.tenant.TenantAutoConfiguration;
import io.hashmatrix.test.fixtures.MockData;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 成员管理 API 鉴权 + 隔离切片测试（Docker-free）—— 守护 #19 验收：
 * tenant-admin 门控（无头→401 / 非管理员→403 / tenant-admin→放行）、活动租户唯一来自 {@code X-Tenant-Id}
 * （缺租户头→400）、用户不存在→404、DTO 无凭据。
 *
 * <p>装 MVC + 鉴权 starter（网关预认证 + 默认过滤链）+ starter-tenant（{@code TenantContextFilter} 据
 * {@code X-Tenant-Id} 还原租户上下文）；{@link OrgMemberService} 用 {@link MockBean} 顶替，不触 KC/DB。
 * 真实 KC 端到端与跨租户隔离回归见 #20（Testcontainers）。
 */
@WebMvcTest(controllers = OrgMemberController.class)
@Import({
    SecurityAutoConfiguration.class,
    SecurityFilterChainConfiguration.class,
    TenantAutoConfiguration.class
})
@EnableMethodSecurity
class OrgMemberControllerTest {

    private static final String MEMBERS = "/api/v1/org/members";
    private static final String TENANT = "acme";

    @Autowired private MockMvc mvc;
    @MockBean private OrgMemberService service;

    private static OrgMember member(String id, String name) {
        return new OrgMember(id, name, MockData.email(name), true);
    }

    @Test
    void listWithoutHeaderIsUnauthorized() throws Exception {
        mvc.perform(get(MEMBERS)).andExpect(status().isUnauthorized());
    }

    @Test
    void listAsNonAdminIsForbidden() throws Exception {
        mvc.perform(get(MEMBERS).header("X-User", "alice").header("X-Roles", "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listAsTenantAdminReturnsMembersWithoutCredentials() throws Exception {
        when(service.listMembers(TENANT)).thenReturn(List.of(member("u1", "alice")));

        mvc.perform(
                        get(MEMBERS)
                                .header("X-User", "admin")
                                .header("X-Roles", "tenant-admin")
                                .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].userId").value("u1"))
                .andExpect(jsonPath("$.data[0].username").value("alice"))
                // DTO 无任何凭据字段。
                .andExpect(jsonPath("$.data[0].password").doesNotExist());
    }

    @Test
    void addAsTenantAdminCreatesMember() throws Exception {
        String email = MockData.email("bob");
        when(service.addMember(eq(TENANT), anyString())).thenReturn(member("u2", "bob"));

        mvc.perform(
                        post(MEMBERS)
                                .header("X-User", "admin")
                                .header("X-Roles", "tenant-admin")
                                .header("X-Tenant-Id", TENANT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"emailOrUsername\":\"" + email + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userId").value("u2"));
    }

    @Test
    void removeAsTenantAdminReturnsNoContent() throws Exception {
        mvc.perform(
                        delete(MEMBERS + "/u3")
                                .header("X-User", "admin")
                                .header("X-Roles", "tenant-admin")
                                .header("X-Tenant-Id", TENANT))
                .andExpect(status().isNoContent());
        verify(service).removeMember(TENANT, "u3");
    }

    @Test
    void addWithoutTenantHeaderIsBadRequest() throws Exception {
        // tenant-admin 通过门控，但缺 X-Tenant-Id → currentTenant() 抛 TenantContextMissing → 400。
        mvc.perform(
                        post(MEMBERS)
                                .header("X-User", "admin")
                                .header("X-Roles", "tenant-admin")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"emailOrUsername\":\"" + MockData.email("x") + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addUnknownUserIsNotFound() throws Exception {
        when(service.addMember(eq(TENANT), anyString()))
                .thenThrow(new MemberUserNotFoundException(MockData.email("ghost")));

        mvc.perform(
                        post(MEMBERS)
                                .header("X-User", "admin")
                                .header("X-Roles", "tenant-admin")
                                .header("X-Tenant-Id", TENANT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"emailOrUsername\":\"" + MockData.email("ghost") + "\"}"))
                .andExpect(status().isNotFound());
    }
}
