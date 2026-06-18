package io.hashmatrix.controlplane.tenant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/**
 * 租户业务配额硬限（用户数 / 数据量 / 作业数）。
 *
 * <p>开通时据此渲染 K8s {@code ResourceQuota} 与业务侧硬限；计量计费（metering）仅预留接口，
 * 政企合同制不按量计费（见 spec §2.4、决策 M8）。
 */
@Embeddable
public record TenantQuota(
        @Column(name = "quota_max_users", nullable = false) int maxUsers,
        @Column(name = "quota_max_data_bytes", nullable = false) long maxDataBytes,
        @Column(name = "quota_max_jobs", nullable = false) int maxJobs) {

    public TenantQuota {
        if (maxUsers < 0 || maxDataBytes < 0 || maxJobs < 0) {
            throw new IllegalArgumentException("配额不得为负：" + maxUsers + "/" + maxDataBytes + "/" + maxJobs);
        }
    }

    /** 平台默认配额（脱敏占位档位，供未指定时兜底）。 */
    public static TenantQuota defaults() {
        return new TenantQuota(50, 100L * 1024 * 1024 * 1024, 100);
    }

    /** JPA 需要的无参构造由 record 规范化构造器覆盖；此静态工厂避免 null 字段。 */
    public static TenantQuota orDefault(TenantQuota quota) {
        return Objects.requireNonNullElseGet(quota, TenantQuota::defaults);
    }
}
