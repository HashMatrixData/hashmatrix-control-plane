package io.hashmatrix.controlplane.tenant.web.dto;

import jakarta.validation.constraints.Size;

/** 通用「原因/备注」请求体（审批/驳回/挂起/注销复用）。原因可空。 */
public record ReasonRequest(@Size(max = 1024) String reason) {

    public String reasonOrDefault(String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason;
    }
}
