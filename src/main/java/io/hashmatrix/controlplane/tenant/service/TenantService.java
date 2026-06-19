package io.hashmatrix.controlplane.tenant.service;

import io.hashmatrix.controlplane.provisioning.ProvisioningException;
import io.hashmatrix.controlplane.provisioning.ProvisioningOrchestrator;
import io.hashmatrix.controlplane.provisioning.ProvisioningOutcome;
import io.hashmatrix.controlplane.provisioning.spi.ProvisioningRequest;
import io.hashmatrix.controlplane.tenant.domain.DeliveryMode;
import io.hashmatrix.controlplane.tenant.domain.Tenant;
import io.hashmatrix.controlplane.tenant.domain.TenantQuota;
import io.hashmatrix.controlplane.tenant.domain.TenantStatus;
import io.hashmatrix.controlplane.tenant.repo.TenantRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户生命周期编排服务 —— 注册 / 审批门控 / 开通 / 挂起恢复 / 注销。
 *
 * <p>状态流转一律经聚合根 {@link Tenant#transitionTo}（受 {@link TenantStatus} 转移表守护）；开通编排
 * 委托 {@link ProvisioningOrchestrator}。开通在本服务内**同步**执行（适配 stub 时序）；接入真实长耗时
 * 适配器时应改为异步任务 + 状态轮询（PROVISIONING 即「进行中」语义已预留）。
 *
 * <p><b>寻址键</b>：单租户操作一律以稳定路由键 {@code tenantKey}（= 契约 {@code tenantId} / {@code X-Tenant-Id}）
 * 寻址，内部 UUID 不出对外边界（对齐契约 {@code /v1/tenants/{tenantId}}）。
 */
@Service
@Transactional
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);

    private final TenantRepository repository;
    private final ProvisioningOrchestrator orchestrator;
    /** 部署级交付形态（一个部署整体 SaaS 或私有化，非按租户）；注册时统一落到聚合根。 */
    private final DeliveryMode deliveryMode;

    public TenantService(
            TenantRepository repository,
            ProvisioningOrchestrator orchestrator,
            @Value("${hashmatrix.control-plane.delivery-mode:PRIVATE}") DeliveryMode deliveryMode) {
        this.repository = repository;
        this.orchestrator = orchestrator;
        this.deliveryMode = deliveryMode;
    }

    /** 自助注册：落库为 {@link TenantStatus#REGISTERED}。key 须全局唯一；交付形态据部署配置注入。 */
    public Tenant register(RegisterTenantCommand command) {
        if (repository.existsByTenantKey(command.tenantKey())) {
            throw new TenantKeyConflictException(command.tenantKey());
        }
        Tenant tenant =
                Tenant.register(
                        command.tenantKey(),
                        command.displayName(),
                        deliveryMode,
                        command.adminEmail(),
                        TenantQuota.orDefault(command.quota()));
        Tenant saved = repository.save(tenant);
        log.info("租户注册 tenantKey={} id={}", saved.getTenantKey(), saved.getId());
        return saved;
    }

    /**
     * 审批通过并开通：{@code REGISTERED → APPROVING → PROVISIONING → ACTIVE}。
     *
     * <p>开通失败则回退 {@code PROVISIONING → APPROVING} 并把失败详情写入 statusReason，原样抛出
     * {@link ProvisioningException} 供上层呈现。
     *
     * <p>⚠️ 事务：类级 {@code @Transactional} 默认对 {@link RuntimeException} 回滚——若不抑制，catch 块里
     * 刚落库的 APPROVING 回退态会随抛出的 {@link ProvisioningException} 一并回滚，租户被悄悄退回 REGISTERED
     * 且不留痕，与设计相反。故本方法 {@code noRollbackFor = ProvisioningException.class}，使失败回退态如实提交
     * （开通副作用与目录状态需留痕）。
     */
    @Transactional(noRollbackFor = ProvisioningException.class)
    public Tenant approve(String tenantKey, String approvalNote) {
        Tenant tenant = require(tenantKey);
        tenant.transitionTo(TenantStatus.APPROVING, approvalNote);
        tenant.transitionTo(TenantStatus.PROVISIONING, "审批通过，开始开通");
        // 先持久化 PROVISIONING，确保「进行中」状态对外可见（即便随后开通抛错）。
        repository.saveAndFlush(tenant);

        ProvisioningRequest request = ProvisioningRequest.from(tenant);
        try {
            ProvisioningOutcome outcome = orchestrator.provision(request);
            tenant.recordProvisioningOutcome(
                    outcome.keycloakOrgId(), outcome.namespace(), outcome.dbSchema());
            tenant.transitionTo(TenantStatus.ACTIVE, "开通完成");
            return repository.save(tenant);
        } catch (ProvisioningException e) {
            tenant.transitionTo(TenantStatus.APPROVING, "开通失败[" + e.getStep() + "]：" + e.getMessage());
            repository.save(tenant);
            log.error("租户开通失败 tenantKey={} step={}", tenant.getTenantKey(), e.getStep(), e);
            throw e;
        }
    }

    /**
     * 审批驳回：置 {@link TenantStatus#DELETED}（终态，关闭，带 reason）——对齐契约 {@code reject → deleted}
     * （驳回不可逆，须留审计）。允许态 {@code REGISTERED}/{@code APPROVING}（转移表均许 {@code → DELETED}）。
     */
    public Tenant reject(String tenantKey, String reason) {
        Tenant tenant = require(tenantKey);
        tenant.transitionTo(TenantStatus.DELETED, "审批驳回：" + reason);
        return repository.save(tenant);
    }

    /** 挂起：{@code ACTIVE → SUSPENDED}（停用但保留资源与数据）。 */
    public Tenant suspend(String tenantKey, String reason) {
        Tenant tenant = require(tenantKey);
        tenant.transitionTo(TenantStatus.SUSPENDED, reason);
        return repository.save(tenant);
    }

    /** 恢复：{@code SUSPENDED → ACTIVE}。 */
    public Tenant resume(String tenantKey) {
        Tenant tenant = require(tenantKey);
        tenant.transitionTo(TenantStatus.ACTIVE, "恢复启用");
        return repository.save(tenant);
    }

    /** 注销：尽力回收资源后置 {@link TenantStatus#DELETED}（终态）。 */
    public Tenant delete(String tenantKey, String reason) {
        Tenant tenant = require(tenantKey);
        orchestrator.deprovision(ProvisioningRequest.from(tenant));
        tenant.transitionTo(TenantStatus.DELETED, reason);
        return repository.save(tenant);
    }

    @Transactional(readOnly = true)
    public Tenant get(String tenantKey) {
        return require(tenantKey);
    }

    @Transactional(readOnly = true)
    public List<Tenant> list() {
        return repository.findAll();
    }

    /**
     * 列出某登录用户的租户 membership（支撑 {@code GET /me/tenants}）。
     *
     * <p><b>M1 启发式</b>：尚无 user↔tenant 关联表，以「当前用户身份 == 某租户 {@code adminEmail}」派生
     * membership。{@code userIdentity} 为网关前置鉴权下发并由 {@code GatewayPreAuthFilter} 还原的主体名
     * （非裸读 header）。恒返回数组（可空）——对齐 D1（单 User + 多 Org Membership）与契约「响应一律数组」，
     * 接入真实 Keycloak Organizations membership 后替换查询、上层零改动。
     */
    @Transactional(readOnly = true)
    public List<Tenant> listMembershipsFor(String userIdentity) {
        return repository.findByAdminEmail(userIdentity);
    }

    private Tenant require(String tenantKey) {
        return repository
                .findByTenantKey(tenantKey)
                .orElseThrow(() -> new TenantNotFoundException(tenantKey));
    }
}
