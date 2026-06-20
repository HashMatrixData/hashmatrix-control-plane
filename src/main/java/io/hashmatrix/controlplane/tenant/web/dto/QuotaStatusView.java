package io.hashmatrix.controlplane.tenant.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.hashmatrix.controlplane.tenant.domain.Tenant;
import io.hashmatrix.controlplane.tenant.domain.TenantQuota;

/**
 * 租户配额视图（契约 {@code openapi/control-plane-v1} 的 {@code QuotaStatus}）：额度 {@code spec} + 用量 {@code usage}。
 *
 * <p><b>M1 用量为 no-op</b>：政企合同制不按量计费（spec 决策 M8），{@code MeteringPort} 仅预留写入、无读取源，
 * 故 {@code usage} 各字段一律省略（{@link JsonInclude.Include#NON_NULL} → 序列化为 {@code {}}），表征「未计量」
 * 而非「用量为 0」。消费方 tolerant reader：接入真实计量源后补全字段，上层零改动。
 */
public record QuotaStatusView(String tenantId, QuotaSpecView spec, QuotaUsageView usage) {

    public static QuotaStatusView from(Tenant t) {
        return new QuotaStatusView(
                t.getTenantKey(), QuotaSpecView.from(t.getQuota()), QuotaUsageView.unmetered());
    }

    /** 配额额度（硬限，契约 {@code QuotaSpec}）；存储以 GiB 计，含嵌套 {@code compute}。 */
    public record QuotaSpecView(
            int maxUsers, int maxStorageGi, int maxConcurrentJobs, ComputeView compute) {
        static QuotaSpecView from(TenantQuota q) {
            return new QuotaSpecView(
                    q.maxUsers(),
                    q.maxStorageGi(),
                    q.maxConcurrentJobs(),
                    new ComputeView(q.computeCpuCores(), q.computeMemoryGi()));
        }
    }

    /** 计算配额（契约 {@code ComputeQuota}）。 */
    public record ComputeView(int cpuCores, int memoryGi) {}

    /**
     * 当前用量快照（契约 {@code QuotaUsage}）。M1 各字段均为 {@code null}（未计量）并经
     * {@link JsonInclude.Include#NON_NULL} 省略；接入真实计量后填充。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuotaUsageView(
            Integer users, Integer storageGi, Integer runningJobs, ComputeView compute) {

        /** M1 no-op：返回全空用量（序列化为 {@code {}}），明确表征「未计量」。 */
        static QuotaUsageView unmetered() {
            return new QuotaUsageView(null, null, null, null);
        }
    }
}
