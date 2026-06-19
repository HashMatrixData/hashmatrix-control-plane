package io.hashmatrix.controlplane.tenant.web.dto;

import io.hashmatrix.controlplane.tenant.domain.Tenant;
import io.hashmatrix.controlplane.tenant.domain.TenantStatus;

/**
 * 当前登录用户在某租户的<b>成员资格视图</b>（契约 {@code openapi/control-plane-v1} 的 {@code MembershipView}）。
 *
 * <p>供 webui 渲染<b>租户切换器</b>：{@code GET /api/v1/me/tenants}（网关 strip {@code /api} 后对齐契约
 * {@code /v1/me/tenants}）恒返回本视图的<b>数组</b>（D1：单 Keycloak User + 多 Org Membership；M1 实现先返回
 * ≤1 个，schema 一律按数组为多租户预留）。切换租户走 D2（以 {@code tenantKey} 重新换 org-scoped token，
 * {@code X-Tenant-Id} 始终唯一）。
 *
 * <p>⚠️ <b>字段命名桥接（勿误读为重复赋值）</b>——契约字段名与本仓领域字段名语义错位，务必对齐契约语义：
 *
 * <ul>
 *   <li>契约 {@code tenantId}（稳定路由键，= {@code X-Tenant-Id}）← 本仓 {@link Tenant#getTenantKey()}
 *       （领域里路由键就叫 {@code tenantKey}；本仓 {@code Tenant.id} 是内部 UUID，<b>不是</b>契约 tenantId）。
 *   <li>契约 {@code tenantKey}（Keycloak Organization 别名键 = {@code OrganizationRef.orgAlias}，切租户换 token 用）
 *       ← M1 无独立 org 别名，demo 下「与 tenantId 取值相同」，故同取 {@link Tenant#getTenantKey()}；
 *       接入真实 Keycloak org（tenantId 映射 org UUID）后此处分化为独立别名。
 * </ul>
 *
 * <p>契约 {@code roles}（每租户角色）为可选、M1 暂不返回；消费方 tolerant reader，未返回不报错，故本视图不含该字段。
 */
public record MembershipView(
        String tenantId, String tenantKey, String displayName, TenantStatus status) {

    public static MembershipView from(Tenant t) {
        // 见类注释「字段命名桥接」：契约 tenantId/tenantKey 在 M1 均映射领域路由键 tenantKey。
        return new MembershipView(
                t.getTenantKey(), t.getTenantKey(), t.getDisplayName(), t.getStatus());
    }
}
