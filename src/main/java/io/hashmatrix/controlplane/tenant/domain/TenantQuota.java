package io.hashmatrix.controlplane.tenant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/**
 * 租户业务配额硬限（用户数 / 存储 / 并发作业 / 计算）。
 *
 * <p>字段对齐契约 {@code openapi/control-plane-v1} 的 {@code QuotaSpec}：存储以 <b>GiB</b> 计
 * （{@code maxStorageGi}，呼应 K8s {@code ResourceQuota} 的 {@code Gi} 习惯，非字节）、并发作业
 * {@code maxConcurrentJobs}、计算 {@code compute}（CPU 核 / 内存 GiB）。DTO 边界把 {@code compute*}
 * 两列收拢为契约的嵌套 {@code compute} 对象。
 *
 * <p>开通时据此渲染 K8s {@code ResourceQuota} 与业务侧硬限；计量计费（metering）仅预留接口，
 * 政企合同制不按量计费（见 spec §2.4、决策 M8）。
 */
@Embeddable
public record TenantQuota(
        @Column(name = "quota_max_users", nullable = false) int maxUsers,
        @Column(name = "quota_max_storage_gi", nullable = false) int maxStorageGi,
        @Column(name = "quota_max_concurrent_jobs", nullable = false) int maxConcurrentJobs,
        @Column(name = "quota_compute_cpu_cores", nullable = false) int computeCpuCores,
        @Column(name = "quota_compute_memory_gi", nullable = false) int computeMemoryGi) {

    public TenantQuota {
        if (maxUsers < 0
                || maxStorageGi < 0
                || maxConcurrentJobs < 0
                || computeCpuCores < 0
                || computeMemoryGi < 0) {
            throw new IllegalArgumentException(
                    "配额不得为负："
                            + maxUsers + "/" + maxStorageGi + "/" + maxConcurrentJobs
                            + "/compute(" + computeCpuCores + "," + computeMemoryGi + ")");
        }
    }

    /** 平台默认配额（脱敏占位档位，供未指定时兜底；对齐契约 QuotaSpec 示例量级）。 */
    public static TenantQuota defaults() {
        return new TenantQuota(50, 100, 10, 16, 64);
    }

    /** JPA 需要的无参构造由 record 规范化构造器覆盖；此静态工厂避免 null 字段。 */
    public static TenantQuota orDefault(TenantQuota quota) {
        return Objects.requireNonNullElseGet(quota, TenantQuota::defaults);
    }
}
