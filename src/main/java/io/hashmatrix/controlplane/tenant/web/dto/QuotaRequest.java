package io.hashmatrix.controlplane.tenant.web.dto;

import io.hashmatrix.controlplane.tenant.domain.TenantQuota;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 业务配额硬限请求（可选；缺省走平台默认档位）。对齐契约 {@code QuotaSpec}：存储以 GiB 计、含嵌套 {@code compute}。
 */
public record QuotaRequest(
        @PositiveOrZero int maxUsers,
        @PositiveOrZero int maxStorageGi,
        @PositiveOrZero int maxConcurrentJobs,
        @Valid ComputeRequest compute) {

    public TenantQuota toQuota() {
        int cpu = compute == null ? 0 : compute.cpuCores();
        int mem = compute == null ? 0 : compute.memoryGi();
        return new TenantQuota(maxUsers, maxStorageGi, maxConcurrentJobs, cpu, mem);
    }

    /** 计算配额（契约 {@code ComputeQuota}）；整体可选，省略则计算配额按 0 处理。 */
    public record ComputeRequest(@PositiveOrZero int cpuCores, @PositiveOrZero int memoryGi) {}
}
