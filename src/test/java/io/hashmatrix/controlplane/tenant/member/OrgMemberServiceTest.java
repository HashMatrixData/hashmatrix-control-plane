package io.hashmatrix.controlplane.tenant.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.hashmatrix.controlplane.tenant.domain.DeliveryMode;
import io.hashmatrix.controlplane.tenant.domain.Tenant;
import io.hashmatrix.controlplane.tenant.domain.TenantQuota;
import io.hashmatrix.controlplane.tenant.repo.TenantRepository;
import io.hashmatrix.controlplane.tenant.service.TenantNotFoundException;
import io.hashmatrix.test.fixtures.MockData;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 成员编排服务单测 —— 守护 D9 租户隔离落点：tenantKey 必经本仓目录解析出<b>该租户自己的</b> orgId 才委派目录，
 * 且租户不存在 / 未开通组织时拒绝（不落到身份后端）。目录为 mock，不连 Keycloak。
 */
class OrgMemberServiceTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final OrgMemberDirectory directory = mock(OrgMemberDirectory.class);
    private final OrgMemberService service = new OrgMemberService(tenants, directory);

    private static Tenant provisioned(String tenantKey, String orgId) {
        Tenant t =
                Tenant.register(
                        tenantKey,
                        tenantKey + " 部门",
                        DeliveryMode.PRIVATE,
                        MockData.email("admin"),
                        TenantQuota.defaults());
        t.recordProvisioningOutcome(orgId, "ns-" + tenantKey, "schema-" + tenantKey);
        return t;
    }

    @Test
    void listResolvesTenantOwnOrgId() {
        when(tenants.findByTenantKey("acme")).thenReturn(Optional.of(provisioned("acme", "org-acme")));
        when(directory.list("org-acme")).thenReturn(List.of());

        service.listMembers("acme");

        // 隔离不变量：acme 的 tenantKey 只解析到 org-acme，目录调用绝不跨租户。
        verify(directory).list("org-acme");
    }

    @Test
    void addResolvesTenantOwnOrgId() {
        when(tenants.findByTenantKey("acme")).thenReturn(Optional.of(provisioned("acme", "org-acme")));
        String email = MockData.email("bob");

        service.addMember("acme", email);

        verify(directory).addExistingUser("org-acme", email);
    }

    @Test
    void removeResolvesTenantOwnOrgId() {
        when(tenants.findByTenantKey("acme")).thenReturn(Optional.of(provisioned("acme", "org-acme")));

        service.removeMember("acme", "member-1");

        verify(directory).remove("org-acme", "member-1");
    }

    @Test
    void rejectsUnknownTenant() {
        when(tenants.findByTenantKey("ghost")).thenReturn(Optional.empty());

        assertThatExceptionOfType(TenantNotFoundException.class)
                .isThrownBy(() -> service.listMembers("ghost"));
        verifyNoInteractions(directory);
    }

    @Test
    void rejectsTenantWithoutProvisionedOrg() {
        // 已注册但未开通：keycloak_org_id 仍为空。
        Tenant notProvisioned =
                Tenant.register(
                        "pending",
                        "Pending 部门",
                        DeliveryMode.PRIVATE,
                        MockData.email("admin"),
                        TenantQuota.defaults());
        when(tenants.findByTenantKey("pending")).thenReturn(Optional.of(notProvisioned));

        assertThatExceptionOfType(TenantOrgNotProvisionedException.class)
                .isThrownBy(() -> service.listMembers("pending"));
        verifyNoInteractions(directory);
    }
}
