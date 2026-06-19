package io.hashmatrix.controlplane.provisioning.keycloak;

import io.hashmatrix.controlplane.provisioning.spi.IdentityProvisioner;
import io.hashmatrix.controlplane.provisioning.spi.ProvisioningRequest;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.OrganizationDomainRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 真实身份开通适配器 —— 经 Keycloak Admin REST 创建租户身份资源（开通时序第 ① 步，架构 05 §4）。
 *
 * <p>开通（{@link #provision}）一次性原子完成：建 Organization（name={@code tenantKey}，附唯一组织域）→
 * 确保 realm 角色「租户管理员」存在（幂等）→ 建首个管理员 user（{@code adminEmail}）→ 将其纳入组织成员 →
 * 赋予租户管理员角色 → 返回组织 id 供回写 {@code tenant.keycloak_org_id}。任一步失败即就地补偿：删除本次
 * 已建的组织（与本次新建的 user），不留残留，再抛出 {@link KeycloakProvisioningException}。
 *
 * <p>回收（{@link #deprovision}）按 {@code tenantKey} 精确检索组织并删除；组织不存在则幂等跳过。
 *
 * <p>装配与全局 {@code provisioning.mode} 解耦：仅 {@code provisioning.identity=keycloak} 时由
 * {@link KeycloakProvisioningConfiguration} 提供本 Bean，compute/data/secrets 仍走 stub（见 README 路线图）。
 */
public class KeycloakIdentityProvisioner implements IdentityProvisioner {

    private static final Logger log = LoggerFactory.getLogger(KeycloakIdentityProvisioner.class);

    private final Keycloak keycloak;
    private final KeycloakProvisioningProperties props;

    public KeycloakIdentityProvisioner(Keycloak keycloak, KeycloakProvisioningProperties props) {
        this.keycloak = keycloak;
        this.props = props;
    }

    @Override
    public String provision(ProvisioningRequest request) {
        RealmResource realm = keycloak.realm(props.getTargetRealm());
        String orgId = createOrganization(realm, request);

        String createdUserId = null;
        try {
            ensureTenantAdminRole(realm);
            AdminUser admin = upsertAdminUser(realm, request.adminEmail());
            if (admin.created()) {
                createdUserId = admin.userId();
            }
            addToOrganization(realm, orgId, admin.userId());
            assignTenantAdminRole(realm, admin.userId());

            log.info(
                    "[keycloak:identity] 开通完成 tenantKey={} org={} adminUser={}",
                    request.tenantKey(),
                    orgId,
                    admin.userId());
            return orgId;
        } catch (RuntimeException e) {
            compensate(realm, orgId, createdUserId);
            throw new KeycloakProvisioningException(
                    "Keycloak 身份开通失败 tenantKey=" + request.tenantKey() + "：" + e.getMessage(), e);
        }
    }

    @Override
    public void deprovision(ProvisioningRequest request) {
        RealmResource realm = keycloak.realm(props.getTargetRealm());
        String orgId = findOrganizationId(realm, request.tenantKey());
        if (orgId == null) {
            log.info("[keycloak:identity] 组织不存在，幂等跳过 tenantKey={}", request.tenantKey());
            return;
        }
        try (Response ignored = realm.organizations().get(orgId).delete()) {
            log.info("[keycloak:identity] 删除组织 org={} tenantKey={}", orgId, request.tenantKey());
        }
    }

    private String createOrganization(RealmResource realm, ProvisioningRequest request) {
        OrganizationRepresentation org = new OrganizationRepresentation();
        org.setName(request.tenantKey());
        org.setEnabled(true);
        if (request.displayName() != null) {
            org.setDescription(request.displayName());
        }
        org.addDomain(new OrganizationDomainRepresentation(props.domainFor(request.tenantKey())));
        try (Response resp = realm.organizations().create(org)) {
            String orgId = CreatedResponseUtil.getCreatedId(resp);
            log.info("[keycloak:identity] 建 Organization org={} name={}", orgId, request.tenantKey());
            return orgId;
        }
    }

    /** 确保「租户管理员」realm 角色存在；不存在则创建（幂等，跨租户共享同一角色）。 */
    private void ensureTenantAdminRole(RealmResource realm) {
        String role = props.getTenantAdminRole();
        try {
            realm.roles().get(role).toRepresentation();
        } catch (NotFoundException notFound) {
            realm.roles().create(new RoleRepresentation(role, "租户管理员", false));
            log.info("[keycloak:identity] 创建缺失的 realm 角色 role={}", role);
        }
    }

    /** 按邮箱建管理员 user；已存在（409）则复用既有 user（开通幂等）。 */
    private AdminUser upsertAdminUser(RealmResource realm, String adminEmail) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(adminEmail);
        user.setEmail(adminEmail);
        user.setEnabled(true);
        user.setEmailVerified(true);
        try (Response resp = realm.users().create(user)) {
            int status = resp.getStatus();
            if (status == Response.Status.CREATED.getStatusCode()) {
                return new AdminUser(CreatedResponseUtil.getCreatedId(resp), true);
            }
            if (status == Response.Status.CONFLICT.getStatusCode()) {
                String existingId = findUserIdByEmail(realm, adminEmail);
                if (existingId == null) {
                    throw new KeycloakProvisioningException(
                            "管理员 user 冲突但无法定位既有用户 email=" + adminEmail, null);
                }
                return new AdminUser(existingId, false);
            }
            throw new KeycloakProvisioningException(
                    "创建管理员 user 失败 status=" + status + " email=" + adminEmail, null);
        }
    }

    private String findUserIdByEmail(RealmResource realm, String adminEmail) {
        List<UserRepresentation> matches = realm.users().searchByUsername(adminEmail, true);
        if (matches.isEmpty()) {
            matches = realm.users().searchByEmail(adminEmail, true);
        }
        return matches.isEmpty() ? null : matches.get(0).getId();
    }

    private void addToOrganization(RealmResource realm, String orgId, String userId) {
        try (Response resp = realm.organizations().get(orgId).members().addMember(userId)) {
            if (resp.getStatus() >= 400) {
                throw new KeycloakProvisioningException(
                        "用户入组织失败 status=" + resp.getStatus() + " org=" + orgId, null);
            }
        }
    }

    private void assignTenantAdminRole(RealmResource realm, String userId) {
        RoleRepresentation role = realm.roles().get(props.getTenantAdminRole()).toRepresentation();
        realm.users().get(userId).roles().realmLevel().add(List.of(role));
    }

    /** 就地补偿：删本次新建的 user（若有）与组织，best-effort 互不阻断。 */
    private void compensate(RealmResource realm, String orgId, String createdUserId) {
        if (createdUserId != null) {
            try (Response ignored = realm.users().delete(createdUserId)) {
                log.warn("[keycloak:identity] 补偿：删除本次新建 user={}", createdUserId);
            } catch (RuntimeException e) {
                log.error("[keycloak:identity] 补偿删除 user 失败 user={}：{}", createdUserId, e.getMessage());
            }
        }
        try (Response ignored = realm.organizations().get(orgId).delete()) {
            log.warn("[keycloak:identity] 补偿：删除本次新建 org={}", orgId);
        } catch (RuntimeException e) {
            log.error("[keycloak:identity] 补偿删除 org 失败 org={}：{}", orgId, e.getMessage());
        }
    }

    private String findOrganizationId(RealmResource realm, String tenantKey) {
        List<OrganizationRepresentation> found =
                realm.organizations().search(tenantKey, Boolean.TRUE, 0, 1);
        return found.isEmpty() ? null : found.get(0).getId();
    }

    /** 管理员 user 定位结果：id + 是否本次新建（决定补偿是否回删该 user）。 */
    private record AdminUser(String userId, boolean created) {}
}
