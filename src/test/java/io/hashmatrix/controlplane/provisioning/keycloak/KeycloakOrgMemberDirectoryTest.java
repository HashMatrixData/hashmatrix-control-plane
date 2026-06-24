package io.hashmatrix.controlplane.provisioning.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hashmatrix.controlplane.tenant.member.MemberUserNotFoundException;
import io.hashmatrix.controlplane.tenant.member.OrgMember;
import io.hashmatrix.test.fixtures.MockData;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * 真实组织成员适配器单测 —— deep-stub 模拟 Keycloak Organizations members API，守护：列表只映射身份字段不带凭据、
 * 邀请按「登录名→邮箱」检索既有 user 且 409 幂等、查无此人抛 {@link MemberUserNotFoundException}、移除对 404 幂等。
 * 不连真实 Keycloak。
 */
class KeycloakOrgMemberDirectoryTest {

    private final Keycloak keycloak = mock(Keycloak.class, RETURNS_DEEP_STUBS);
    private final KeycloakProvisioningProperties props = props();
    private final RealmResource realm = keycloak.realm(props.getTargetRealm());
    private final KeycloakOrgMemberDirectory directory =
            new KeycloakOrgMemberDirectory(keycloak, props);

    private static final String ORG = "org-123";

    private static KeycloakProvisioningProperties props() {
        KeycloakProvisioningProperties p = new KeycloakProvisioningProperties();
        p.setTargetRealm("hashmatrix");
        return p;
    }

    private static UserRepresentation user(String id, String username, String email) {
        UserRepresentation u = new UserRepresentation();
        u.setId(id);
        u.setUsername(username);
        u.setEmail(email);
        u.setEnabled(true);
        return u;
    }

    private static Response status(Response.Status s) {
        return Response.status(s).build();
    }

    @Test
    void listMapsIdentityFieldsOnly() {
        when(realm.organizations().get(ORG).members().getAll())
                .thenReturn(List.of(user("u1", "alice", MockData.email("alice"))));

        List<OrgMember> members = directory.list(ORG);

        assertThat(members).singleElement().satisfies(m -> {
            assertThat(m.id()).isEqualTo("u1");
            assertThat(m.username()).isEqualTo("alice");
            assertThat(m.enabled()).isTrue();
        });
    }

    @Test
    void addExistingUserResolvesByUsernameThenAddsAndReturnsMember() {
        String email = MockData.email("bob");
        when(realm.users().searchByUsername(email, true)).thenReturn(List.of(user("u2", "bob", email)));
        when(realm.organizations().get(ORG).members().addMember("u2"))
                .thenReturn(status(Response.Status.CREATED));
        when(realm.organizations().get(ORG).members().member("u2").toRepresentation())
                .thenReturn(user("u2", "bob", email));

        OrgMember added = directory.addExistingUser(ORG, email);

        assertThat(added.id()).isEqualTo("u2");
        verify(realm.organizations().get(ORG).members()).addMember("u2");
    }

    @Test
    void addExistingUserIsIdempotentWhenAlreadyMember() {
        String email = MockData.email("carol");
        when(realm.users().searchByUsername(email, true)).thenReturn(List.of(user("u3", "carol", email)));
        // 已是成员 → KC 返回 409，适配器视为幂等成功，仍回读成员视图。
        when(realm.organizations().get(ORG).members().addMember("u3"))
                .thenReturn(status(Response.Status.CONFLICT));
        when(realm.organizations().get(ORG).members().member("u3").toRepresentation())
                .thenReturn(user("u3", "carol", email));

        OrgMember added = directory.addExistingUser(ORG, email);

        assertThat(added.id()).isEqualTo("u3");
    }

    @Test
    void addExistingUserFallsBackToEmailSearch() {
        String email = MockData.email("dave");
        when(realm.users().searchByUsername(email, true)).thenReturn(List.of());
        when(realm.users().searchByEmail(email, true)).thenReturn(List.of(user("u4", "dave", email)));
        when(realm.organizations().get(ORG).members().addMember("u4"))
                .thenReturn(status(Response.Status.CREATED));
        when(realm.organizations().get(ORG).members().member("u4").toRepresentation())
                .thenReturn(user("u4", "dave", email));

        assertThat(directory.addExistingUser(ORG, email).id()).isEqualTo("u4");
    }

    @Test
    void addExistingUserThrowsWhenUserAbsent() {
        String email = MockData.email("ghost");
        when(realm.users().searchByUsername(email, true)).thenReturn(List.of());
        when(realm.users().searchByEmail(email, true)).thenReturn(List.of());

        assertThatExceptionOfType(MemberUserNotFoundException.class)
                .isThrownBy(() -> directory.addExistingUser(ORG, email));
        // 查无此人 → 绝不调 addMember。
        verify(realm.organizations().get(ORG).members(), never()).addMember(anyString());
    }

    @Test
    void removeDeletesMember() {
        when(realm.organizations().get(ORG).members().member("u5").delete())
                .thenReturn(status(Response.Status.NO_CONTENT));

        directory.remove(ORG, "u5");

        verify(realm.organizations().get(ORG).members().member("u5")).delete();
    }

    @Test
    void removeIsIdempotentWhenMemberAbsent() {
        when(realm.organizations().get(ORG).members().member("gone").delete())
                .thenThrow(new NotFoundException());

        // 不抛：成员已不在组织中，幂等跳过。
        directory.remove(ORG, "gone");
    }
}
