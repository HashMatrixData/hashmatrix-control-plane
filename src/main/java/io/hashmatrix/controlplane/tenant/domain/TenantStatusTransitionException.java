package io.hashmatrix.controlplane.tenant.domain;

/** 非法的租户状态流转（违反 {@link TenantStatus} 转移表）。 */
public class TenantStatusTransitionException extends RuntimeException {

    private final TenantStatus from;
    private final TenantStatus to;

    public TenantStatusTransitionException(TenantStatus from, TenantStatus to) {
        super("非法的租户状态流转：" + from + " → " + to + "（合法目标：" + from.allowedTargets() + "）");
        this.from = from;
        this.to = to;
    }

    public TenantStatus getFrom() {
        return from;
    }

    public TenantStatus getTo() {
        return to;
    }
}
