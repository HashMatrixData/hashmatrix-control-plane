package io.hashmatrix.controlplane.tenant.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 租户生命周期状态机。
 *
 * <p>规范流转（架构 05 §4）：
 *
 * <pre>
 *   registered → approving → provisioning → active → suspended → deleted
 * </pre>
 *
 * <p>转移表为单一事实源；失败/驳回的回退语义：
 *
 * <ul>
 *   <li>{@code APPROVING → REGISTERED}：审批驳回，退回草稿待补充再提交。
 *   <li>{@code PROVISIONING → APPROVING}：开通失败，退回审批门控以便排障后重试。
 * </ul>
 *
 * {@link #DELETED} 为终态。所有合法流转集中在 {@link #ALLOWED}，业务代码一律经
 * {@link #canTransitionTo(TenantStatus)} / {@link #checkTransitionTo(TenantStatus)} 校验，
 * 不得绕过直接 set。
 */
public enum TenantStatus {

    /** 自助注册落库，等待审批。 */
    REGISTERED,
    /** 审批门控中。 */
    APPROVING,
    /** 已通过审批，正在命令式开通租户资源（身份/namespace/数据/secrets）。 */
    PROVISIONING,
    /** 开通完成，租户可用。 */
    ACTIVE,
    /** 已挂起（停用但保留资源与数据）。 */
    SUSPENDED,
    /** 已注销（终态，资源已回收）。 */
    DELETED;

    private static final Map<TenantStatus, Set<TenantStatus>> ALLOWED =
            new EnumMap<>(TenantStatus.class);

    static {
        ALLOWED.put(REGISTERED, Set.of(APPROVING, DELETED));
        ALLOWED.put(APPROVING, Set.of(PROVISIONING, REGISTERED, DELETED));
        ALLOWED.put(PROVISIONING, Set.of(ACTIVE, APPROVING, DELETED));
        ALLOWED.put(ACTIVE, Set.of(SUSPENDED, DELETED));
        ALLOWED.put(SUSPENDED, Set.of(ACTIVE, DELETED));
        ALLOWED.put(DELETED, Collections.emptySet());
    }

    /** 该状态允许流向的目标状态集合（只读）。 */
    public Set<TenantStatus> allowedTargets() {
        return ALLOWED.get(this);
    }

    /** 是否允许从当前状态流转到 {@code target}。 */
    public boolean canTransitionTo(TenantStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    /** 终态（不再允许任何流转）。 */
    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /**
     * 校验流转合法性，非法即抛 {@link TenantStatusTransitionException}。
     *
     * @param target 目标状态
     */
    public void checkTransitionTo(TenantStatus target) {
        if (!canTransitionTo(target)) {
            throw new TenantStatusTransitionException(this, target);
        }
    }
}
