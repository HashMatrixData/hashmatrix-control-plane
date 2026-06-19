package io.hashmatrix.controlplane.tenant.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 审批裁决请求体（契约 {@code openapi/control-plane-v1} 的 {@code ApprovalDecision} schema）。
 *
 * <p>单端点 {@code POST /{tenantId}/approval} 复用本体：{@code approve} → 开通；{@code reject} → 置
 * {@code deleted}（终态，不可逆）。契约约束：{@code reject} 时 {@code reason} 必填（驳回须留审计）——
 * 该条件校验在控制器内显式判定（非注解可表达的跨字段条件）。
 */
public record ApprovalRequest(@NotNull ApprovalDecision decision, @Size(max = 1024) String reason) {

    public boolean isReject() {
        return decision == ApprovalDecision.REJECT;
    }
}
