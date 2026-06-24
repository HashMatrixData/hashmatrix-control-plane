package io.hashmatrix.controlplane.tenant.member;

import java.util.List;

/**
 * 组织成员目录端口 —— 对某个身份后端组织（Keycloak Organization）的成员做 列/加/移。
 *
 * <p><b>后端中立的开通端口</b>（与 {@code provisioning.spi} 同构思路）：领域/服务层只依赖本接口，真实实现为
 * {@code KeycloakOrgMemberDirectory}（{@code identity=keycloak} 时装配），默认装配内存 stub
 * （{@code InMemoryOrgMemberDirectory}），使无活 Keycloak 也能本地跑通。
 *
 * <p>入参一律是身份后端的<b>组织 id</b>（{@code orgId} = 租户的 {@code keycloak_org_id}），<b>不感知租户语义</b>；
 * 由 {@link OrgMemberService} 负责 租户 → orgId 解析与租户隔离。本端口不读写 control-plane PG（守 D1：成员
 * SoT 仅在身份后端）。
 */
public interface OrgMemberDirectory {

    /** 列出组织成员（只读视图，不含凭据）。 */
    List<OrgMember> list(String orgId);

    /**
     * 将一个<b>已存在</b>的用户（按邮箱或登录名定位）加入组织，返回其成员视图。已是成员则幂等返回既有视图。
     *
     * @throws MemberUserNotFoundException 身份后端中不存在该邮箱/登录名对应用户（M2 不做邮件邀请新建，见 #17 范围）
     */
    OrgMember addExistingUser(String orgId, String emailOrUsername);

    /** 从组织移除成员（按成员 id）。实现须幂等：不在组织中则静默跳过。 */
    void remove(String orgId, String memberId);
}
