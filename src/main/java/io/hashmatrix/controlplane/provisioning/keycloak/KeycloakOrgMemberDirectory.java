package io.hashmatrix.controlplane.provisioning.keycloak;

import io.hashmatrix.controlplane.tenant.member.MemberUserNotFoundException;
import io.hashmatrix.controlplane.tenant.member.OrgMember;
import io.hashmatrix.controlplane.tenant.member.OrgMemberDirectory;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.OrganizationMembersResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 真实组织成员目录适配器 —— 经 Keycloak Admin REST 的 Organizations members API 列/加/移成员
 * （{@code identity=keycloak} 时由 {@link KeycloakProvisioningConfiguration} 装配）。
 *
 * <p>复用与 {@link KeycloakIdentityProvisioner} 同一 {@link Keycloak} admin client 与目标 realm。仅读写
 * 身份后端，<b>不</b>触碰 control-plane PG（守 D1/R3：成员 SoT 在 Keycloak）。返回 {@link OrgMember} 只含身份
 * 字段，<b>绝不</b>外泄任何凭据。
 *
 * <p>「邀请」语义（M2）：仅把<b>已存在</b>的 realm user（按登录名→邮箱精确检索）加入组织；查无此人即抛
 * {@link MemberUserNotFoundException}（不走邮件邀请新建，见 #17 范围）。已是成员（KC 409）按幂等处理。
 */
public class KeycloakOrgMemberDirectory implements OrgMemberDirectory {

    private static final Logger log = LoggerFactory.getLogger(KeycloakOrgMemberDirectory.class);

    private final Keycloak keycloak;
    private final KeycloakProvisioningProperties props;

    public KeycloakOrgMemberDirectory(Keycloak keycloak, KeycloakProvisioningProperties props) {
        this.keycloak = keycloak;
        this.props = props;
    }

    @Override
    public List<OrgMember> list(String orgId) {
        return members(orgId).getAll().stream().map(KeycloakOrgMemberDirectory::toMember).toList();
    }

    @Override
    public OrgMember addExistingUser(String orgId, String emailOrUsername) {
        String userId = resolveExistingUserId(emailOrUsername);
        try (Response resp = members(orgId).addMember(userId)) {
            int status = resp.getStatus();
            // 409 = 已是成员 → 幂等；其余 4xx/5xx 为真失败。
            if (status >= 400 && status != Response.Status.CONFLICT.getStatusCode()) {
                throw new KeycloakProvisioningException(
                        "加成员入组织失败 status=" + status + " org=" + orgId, null);
            }
        }
        log.info("[keycloak:member] 加成员 org={} user={}", orgId, userId);
        return toMember(members(orgId).member(userId).toRepresentation());
    }

    @Override
    public void remove(String orgId, String memberId) {
        try (Response resp = members(orgId).member(memberId).delete()) {
            int status = resp.getStatus();
            if (status >= 400 && status != Response.Status.NOT_FOUND.getStatusCode()) {
                throw new KeycloakProvisioningException(
                        "移除组织成员失败 status=" + status + " org=" + orgId, null);
            }
            log.info("[keycloak:member] 移除成员 org={} member={}", orgId, memberId);
        } catch (NotFoundException idempotent) {
            // 成员已不在组织中 → 幂等跳过。
            log.info("[keycloak:member] 成员不存在，幂等跳过 org={} member={}", orgId, memberId);
        }
    }

    private OrganizationMembersResource members(String orgId) {
        return realm().organizations().get(orgId).members();
    }

    private RealmResource realm() {
        return keycloak.realm(props.getTargetRealm());
    }

    /** 按登录名（精确）→ 邮箱（精确）检索既有 user；查无则抛 {@link MemberUserNotFoundException}。 */
    private String resolveExistingUserId(String emailOrUsername) {
        List<UserRepresentation> matches = realm().users().searchByUsername(emailOrUsername, true);
        if (matches.isEmpty()) {
            matches = realm().users().searchByEmail(emailOrUsername, true);
        }
        if (matches.isEmpty()) {
            throw new MemberUserNotFoundException(emailOrUsername);
        }
        return matches.get(0).getId();
    }

    /** Keycloak {@link UserRepresentation} → 领域 {@link OrgMember} 的映射（只取身份字段，不碰凭据）。 */
    private static OrgMember toMember(UserRepresentation user) {
        return new OrgMember(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                Boolean.TRUE.equals(user.isEnabled()));
    }
}
