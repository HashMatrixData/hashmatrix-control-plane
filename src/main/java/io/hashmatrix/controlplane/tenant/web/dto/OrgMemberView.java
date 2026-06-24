package io.hashmatrix.controlplane.tenant.web.dto;

import io.hashmatrix.controlplane.tenant.member.OrgMember;

/**
 * 组织成员视图（契约 {@code openapi/control-plane-v1} 的 {@code OrgMemberView}）。
 *
 * <p><b>勿与 {@link MembershipView} 混淆</b>：{@code MembershipView} 是「我属于哪些租户」的跨租户切换器；
 * 本视图是「当前租户里有哪些成员」的自管理列表项。
 *
 * <p>🔴 仅含身份标识与启用态，<b>绝不</b>承载凭据（成员 SoT 在 Keycloak，见 D1/R3）。契约字段名 {@code userId}
 * 对应领域 {@link OrgMember#id()}（Keycloak user id，也是 {@code DELETE /v1/org/members/{userId}} 的寻址键）。
 */
public record OrgMemberView(String userId, String username, String email, boolean enabled) {

    public static OrgMemberView from(OrgMember member) {
        return new OrgMemberView(
                member.id(), member.username(), member.email(), member.enabled());
    }
}
