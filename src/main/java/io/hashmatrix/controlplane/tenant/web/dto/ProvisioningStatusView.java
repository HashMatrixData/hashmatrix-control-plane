package io.hashmatrix.controlplane.tenant.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import io.hashmatrix.controlplane.tenant.domain.Tenant;
import io.hashmatrix.controlplane.tenant.domain.TenantStatus;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/**
 * 开通状态视图（契约 {@code openapi/control-plane-v1} 的 {@code ProvisioningStatus}）：整体阶段 {@code phase}
 * + 四个外呼步骤 {@code steps}（{@code keycloak}/{@code helm}/{@code datastore}/{@code secrets}）。
 *
 * <p><b>M1 派生语义（known-drift，已登记 #11 / CLAUDE.md）</b>：开通在 {@code TenantService.approve} 内<b>同步</b>
 * 执行且<b>不持久化分步进度</b>，故本视图由租户生命周期状态<b>派生</b>（coarse），非真实分步落库：
 *
 * <ul>
 *   <li>{@code active}/{@code suspended}/{@code deleted} → {@code succeeded}（已开通 / 已回收）；
 *   <li>{@code provisioning} → {@code in_progress}（同步链路下为瞬态，少经独立查询观测到）；
 *   <li>{@code approving} → {@code failed}（M1 持久化 {@code approving} ⟺ 开通失败回退，见 {@code approve()}），
 *       失败步由 {@code statusReason}（{@code 开通失败[<step>]…}）定位；
 *   <li>{@code registered} → {@code pending}（未开通）。
 * </ul>
 *
 * <p><b>注销（deprovision）进度派生为后置</b>：M1 下 {@code deleted} 统一回 {@code succeeded}（资源已回收），
 * 暂不细分回收进度——与契约 {@code deleteTenant} 注记「{@code phase} 表征回收进度」的差异为 known-drift；
 * 接入异步注销后于此补回收态派生。消费方勿把 {@code deleted} 租户的 {@code succeeded} 读作「正在/刚回收」。
 *
 * <p><b>失败 message 脱敏</b>：失败步 {@code message} 仅回通用文案，<b>不</b>外泄底层适配器原始异常原文
 * （真实适配器接入后该原文可能含主机/端口等内部细节）；排障详情留在租户 {@code statusReason}（单一权威出口，
 * 见 {@link io.hashmatrix.controlplane.tenant.web.dto.TenantView}）与审计日志，不在本端点二次外泄。
 *
 * 接入异步开通 + 分步落库后，替换 {@link #from} 的派生为真实进度查询，契约形态与消费方均不变。
 * {@code startedAt}/{@code finishedAt} M1 不单独持久化，省略（契约 optional，{@link JsonInclude.Include#NON_NULL}）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProvisioningStatusView(
        String tenantId, Phase phase, Instant startedAt, Instant finishedAt, List<StepView> steps) {

    /** 失败步通用文案（脱敏）：排障详情留 statusReason / 审计日志，不外泄底层异常原文。 */
    private static final String STEP_FAILED_NOTE = "该步开通失败（详情见租户 statusReason）";

    public static ProvisioningStatusView from(Tenant t) {
        Phase phase = phaseOf(t.getStatus());
        List<StepView> steps = stepsOf(phase, t.getStatusReason());
        return new ProvisioningStatusView(t.getTenantKey(), phase, null, null, steps);
    }

    private static Phase phaseOf(TenantStatus status) {
        return switch (status) {
            case ACTIVE, SUSPENDED, DELETED -> Phase.SUCCEEDED;
            case PROVISIONING -> Phase.IN_PROGRESS;
            // M1：approve() 同步链路下，持久化的 APPROVING 仅由开通失败回退产生（见 TenantService.approve）。
            case APPROVING -> Phase.FAILED;
            case REGISTERED -> Phase.PENDING;
        };
    }

    private static List<StepView> stepsOf(Phase phase, String statusReason) {
        Target[] targets = Target.values();
        int failedIdx = phase == Phase.FAILED ? failedStepIndex(statusReason) : -1;
        return IntStream.range(0, targets.length)
                .mapToObj(
                        i -> {
                            StepStatus st =
                                    switch (phase) {
                                        case SUCCEEDED -> StepStatus.SUCCEEDED;
                                        case PENDING -> StepStatus.PENDING;
                                            // coarse：未分步落库，整体进行中。
                                        case IN_PROGRESS -> StepStatus.IN_PROGRESS;
                                        case FAILED ->
                                                i < failedIdx
                                                        ? StepStatus.SUCCEEDED
                                                        : i == failedIdx
                                                                ? StepStatus.FAILED
                                                                : StepStatus.PENDING;
                                    };
                            // 失败步仅回通用文案（脱敏）；原始 reason 留在租户 statusReason / 审计日志，不在此外泄。
                            String msg =
                                    (phase == Phase.FAILED && i == failedIdx) ? STEP_FAILED_NOTE : null;
                            return new StepView(targets[i].wire, st, msg, null);
                        })
                .toList();
    }

    /**
     * 从 {@code statusReason}（{@code 开通失败[<internal>]：…}）定位失败步序号。{@code internal} 为编排器
     * {@code step()} 名（{@code identity}/{@code compute}/{@code data}/{@code secrets}，见
     * {@code ProvisioningOrchestrator}）。解析不出（防御分支，M1 文案稳定下不触发）则归因首步（保守、loud）。
     */
    private static int failedStepIndex(String statusReason) {
        if (statusReason != null) {
            Target[] targets = Target.values();
            for (int i = 0; i < targets.length; i++) {
                if (statusReason.contains("[" + targets[i].internal + "]")) {
                    return i;
                }
            }
        }
        return 0;
    }

    /** 外呼步骤：契约 {@code target}（wire）↔ 编排器内部步名（internal，失败时落入 statusReason）。 */
    private enum Target {
        KEYCLOAK("keycloak", "identity"),
        HELM("helm", "compute"),
        DATASTORE("datastore", "data"),
        SECRETS("secrets", "secrets");

        final String wire;
        final String internal;

        Target(String wire, String internal) {
            this.wire = wire;
            this.internal = internal;
        }
    }

    /** 开通整体阶段（契约 {@code ProvisioningPhase}，小写序列化）。 */
    public enum Phase {
        PENDING,
        IN_PROGRESS,
        SUCCEEDED,
        FAILED;

        @JsonValue
        public String toJson() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 单步状态（契约 {@code ProvisioningStep.status}，小写序列化）。 */
    public enum StepStatus {
        PENDING,
        IN_PROGRESS,
        SUCCEEDED,
        FAILED,
        SKIPPED;

        @JsonValue
        public String toJson() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 单个外呼开通步骤（契约 {@code ProvisioningStep}）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepView(String target, StepStatus status, String message, Instant updatedAt) {}
}
