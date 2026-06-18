package io.hashmatrix.controlplane.provisioning.metering;

import java.util.UUID;

/**
 * 计量端口 —— **仅预留接口**。
 *
 * <p>政企合同制不按量计费（spec 决策 M8），当前只做配额硬限，不落地计量计费。本接口预留给未来按量
 * 场景；平台默认装配 {@code NoopMeteringPort}，不产生任何计量副作用。
 */
public interface MeteringPort {

    /**
     * 上报一次租户用量采样（预留）。
     *
     * @param tenantId 租户 id
     * @param metric   指标名（如 {@code storage.bytes} / {@code jobs.count}）
     * @param value    采样值
     */
    void recordUsage(UUID tenantId, String metric, long value);
}
