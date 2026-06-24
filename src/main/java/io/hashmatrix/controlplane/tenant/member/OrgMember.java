package io.hashmatrix.controlplane.tenant.member;

/**
 * 租户组织成员的<b>只读视图</b>（成员 SoT 在 Keycloak Organization，control-plane 仅编排，见 D1/R3）。
 *
 * <p>🔴 红线：本模型<b>绝不</b>承载任何凭据（密码/哈希/credential）——成员真相留在 Keycloak，控制平面 PG
 * 不另存成员凭据实体（守 D1：单 Keycloak User + 多 Org Membership）。仅暴露身份标识与启用态供列表回显。
 *
 * <p>后端中立：本记录不引用任何 Keycloak 类型；KC ↔ 领域映射由适配器
 * （{@code provisioning.keycloak.KeycloakOrgMemberDirectory}）独占，领域层不感知身份后端。
 *
 * @param id 成员稳定键（KC 实现下为 Keycloak user id；移除成员时寻址用）
 * @param username 登录名
 * @param email 邮箱（可空——KC user 不强制有 email）
 * @param enabled 账号是否启用
 */
public record OrgMember(String id, String username, String email, boolean enabled) {}
