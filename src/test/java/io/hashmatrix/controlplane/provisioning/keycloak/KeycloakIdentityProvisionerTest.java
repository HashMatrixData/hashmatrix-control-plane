package io.hashmatrix.controlplane.provisioning.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hashmatrix.controlplane.provisioning.spi.ProvisioningRequest;
import io.hashmatrix.controlplane.tenant.domain.DeliveryMode;
import io.hashmatrix.controlplane.tenant.domain.TenantQuota;
import io.hashmatrix.test.fixtures.MockData;
import io.hashmatrix.test.fixtures.MockTenants;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * Keycloak 身份适配器单测 —— 以 deep-stub 模拟 Admin REST 链路，守护：开通时序（org→role→user→member→role
 * 赋予）、失败就地补偿（删本次新建 org）、回收幂等（不存在则跳过）。不连真实 Keycloak。
 */
class KeycloakIdentityProvisionerTest {

    private final Keycloak keycloak = mock(Keycloak.class, RETURNS_DEEP_STUBS);
    private final KeycloakProvisioningProperties props = props();
    // 与适配器内部一致地取同一 realm deep-stub（deep-stub 对相同入参返回同一实例，故可在此 stub/verify）。
    private final RealmResource realm = keycloak.realm(props.getTargetRealm());
    private final KeycloakIdentityProvisioner provisioner =
            new KeycloakIdentityProvisioner(keycloak, props);
    // 固定脱敏邮箱，供 request() 与「409 复用既有 user」用例的检索 stub 共用同一值。
    private final String adminEmail = MockData.email("admin");

    private static KeycloakProvisioningProperties props() {
        KeycloakProvisioningProperties p = new KeycloakProvisioningProperties();
        p.setTargetRealm("hashmatrix");
        p.setTenantAdminRole("tenant-admin");
        p.setDomainSuffix("example.com");
        return p;
    }

    private ProvisioningRequest request() {
        return new ProvisioningRequest(
                UUID.randomUUID(),
                MockTenants.TENANT_DEMO,
                "Demo 部门",
                DeliveryMode.PRIVATE,
                adminEmail,
                TenantQuota.defaults());
    }

    private static Response created(String location) {
        return Response.created(URI.create(location)).build();
    }

    private static Response noContent() {
        return Response.status(Response.Status.NO_CONTENT).build();
    }

    @Test
    void provisionCreatesOrgUserMembershipAndRole() {
        when(realm.organizations().create(any()))
                .thenReturn(created("https://kc/admin/realms/hashmatrix/organizations/org-123"));
        when(realm.roles().get("tenant-admin").toRepresentation())
                .thenReturn(new RoleRepresentation("tenant-admin", "租户管理员", false));
        when(realm.users().create(any()))
                .thenReturn(created("https://kc/admin/realms/hashmatrix/users/user-1"));
        when(realm.organizations().get("org-123").members().addMember("user-1"))
                .thenReturn(noContent());

        String orgId = provisioner.provision(request());

        assertThat(orgId).isEqualTo("org-123");
        // 用户被纳入组织成员，并被赋予租户管理员 realm 角色。
        verify(realm.organizations().get("org-123").members()).addMember("user-1");
        verify(realm.users().get("user-1").roles().realmLevel()).add(anyList());
    }

    @Test
    void provisionCompensatesByDeletingOrgWhenUserCreationFails() {
        when(realm.organizations().create(any()))
                .thenReturn(created("https://kc/admin/realms/hashmatrix/organizations/org-123"));
        when(realm.roles().get("tenant-admin").toRepresentation())
                .thenReturn(new RoleRepresentation("tenant-admin", "租户管理员", false));
        when(realm.users().create(any())).thenThrow(new IllegalStateException("Keycloak 不可达"));
        when(realm.organizations().get("org-123").delete()).thenReturn(noContent());

        assertThatExceptionOfType(KeycloakProvisioningException.class)
                .isThrownBy(() -> provisioner.provision(request()));

        // 补偿：删除本次新建的组织；user 未建成 → 不回删 user。
        verify(realm.organizations().get("org-123")).delete();
        verify(realm.users(), never()).delete(anyString());
    }

    @Test
    void provisionDoesNotDeleteReusedExistingUserWhenLaterStepFails() {
        when(realm.organizations().create(any()))
                .thenReturn(created("https://kc/admin/realms/hashmatrix/organizations/org-123"));
        when(realm.roles().get("tenant-admin").toRepresentation())
                .thenReturn(new RoleRepresentation("tenant-admin", "租户管理员", false));
        // user 已存在 → create 返回 409，按用户名精确检索命中既有 user（本次未新建）。
        when(realm.users().create(any())).thenReturn(Response.status(Response.Status.CONFLICT).build());
        UserRepresentation existing = new UserRepresentation();
        existing.setId("user-existing");
        when(realm.users().searchByUsername(adminEmail, true)).thenReturn(List.of(existing));
        // 入组织失败 → 触发补偿。
        when(realm.organizations().get("org-123").members().addMember("user-existing"))
                .thenReturn(Response.status(Response.Status.BAD_REQUEST).build());
        when(realm.organizations().get("org-123").delete()).thenReturn(noContent());

        assertThatExceptionOfType(KeycloakProvisioningException.class)
                .isThrownBy(() -> provisioner.provision(request()));

        // 关键不变量：补偿删本次新建的 org，但既有 user 系「复用」而非本次新建 → 绝不能被回删（误删他人账号）。
        verify(realm.organizations().get("org-123")).delete();
        verify(realm.users(), never()).delete(anyString());
    }

    @Test
    void deprovisionDeletesOrgWhenPresent() {
        OrganizationRepresentation org = new OrganizationRepresentation();
        org.setId("org-123");
        org.setName(MockTenants.TENANT_DEMO);
        when(realm.organizations().search(MockTenants.TENANT_DEMO, Boolean.TRUE, 0, 1))
                .thenReturn(List.of(org));
        when(realm.organizations().get("org-123").delete()).thenReturn(noContent());

        provisioner.deprovision(request());

        verify(realm.organizations().get("org-123")).delete();
    }

    @Test
    void deprovisionIsIdempotentWhenOrgAbsent() {
        when(realm.organizations().search(MockTenants.TENANT_DEMO, Boolean.TRUE, 0, 1))
                .thenReturn(List.of());

        provisioner.deprovision(request());

        // 组织不存在 → 不进入 get(...).delete() 链路。
        verify(realm.organizations(), never()).get(anyString());
    }
}
