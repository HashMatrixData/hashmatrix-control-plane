package io.hashmatrix.controlplane.tenant.member;

import io.hashmatrix.controlplane.tenant.domain.Tenant;
import io.hashmatrix.controlplane.tenant.repo.TenantRepository;
import io.hashmatrix.controlplane.tenant.service.TenantNotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户组织成员编排（内部能力，ST1）——把「租户路由键 → 身份后端组织」的解析与租户隔离收口于此，
 * 再委派 {@link OrgMemberDirectory} 对 Keycloak Organization 做 列/加/移。
 *
 * <p><b>租户隔离（D9）的执行点</b>：一切成员操作都以 {@code tenantKey}（= {@code X-Tenant-Id} 路由键）为入口，
 * 经本仓租户目录解析出该租户<b>自己的</b> {@code keycloak_org_id} 后才落到身份后端——调用方无法跨租户寻址
 * （A 租户的 tenantKey 永远解析到 A 的 org）。ST2 的控制器在此之上再加「租户管理员」角色门控。
 *
 * <p><b>成员 SoT 边界（D1/R3）</b>：本服务<b>不</b>读写 control-plane PG 的任何成员实体——租户目录（PG）仅用于
 * 解析 orgId，成员真相始终在身份后端。
 */
@Service
public class OrgMemberService {

    private final TenantRepository tenants;
    private final OrgMemberDirectory directory;

    public OrgMemberService(TenantRepository tenants, OrgMemberDirectory directory) {
        this.tenants = tenants;
        this.directory = directory;
    }

    /** 列出本租户组织成员。 */
    @Transactional(readOnly = true)
    public List<OrgMember> listMembers(String tenantKey) {
        return directory.list(orgIdOf(tenantKey));
    }

    /** 邀请：把已存在的用户（邮箱/登录名）加入本租户组织。 */
    @Transactional(readOnly = true)
    public OrgMember addMember(String tenantKey, String emailOrUsername) {
        return directory.addExistingUser(orgIdOf(tenantKey), emailOrUsername);
    }

    /** 从本租户组织移除成员（幂等）。 */
    @Transactional(readOnly = true)
    public void removeMember(String tenantKey, String memberId) {
        directory.remove(orgIdOf(tenantKey), memberId);
    }

    /**
     * 解析租户路由键 → 该租户的 {@code keycloak_org_id}，是租户隔离不变量的落点。
     *
     * @throws TenantNotFoundException 租户不存在
     * @throws TenantOrgNotProvisionedException 租户存在但尚未开通身份组织（org id 为空）
     */
    private String orgIdOf(String tenantKey) {
        Tenant tenant =
                tenants.findByTenantKey(tenantKey)
                        .orElseThrow(() -> new TenantNotFoundException(tenantKey));
        String orgId = tenant.getKeycloakOrgId();
        if (orgId == null || orgId.isBlank()) {
            throw new TenantOrgNotProvisionedException(tenantKey);
        }
        return orgId;
    }
}
