package io.hashmatrix.controlplane.tenant.web.dto;

import io.hashmatrix.controlplane.tenant.domain.TenantQuota;
import jakarta.validation.constraints.PositiveOrZero;

/** 业务配额硬限请求（可选；缺省走平台默认档位）。 */
public record QuotaRequest(
        @PositiveOrZero int maxUsers,
        @PositiveOrZero long maxDataBytes,
        @PositiveOrZero int maxJobs) {

    public TenantQuota toQuota() {
        return new TenantQuota(maxUsers, maxDataBytes, maxJobs);
    }
}
