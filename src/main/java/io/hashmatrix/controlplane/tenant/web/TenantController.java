package io.hashmatrix.controlplane.tenant.web;

import io.hashmatrix.controlplane.tenant.domain.Tenant;
import io.hashmatrix.controlplane.tenant.domain.TenantStatus;
import io.hashmatrix.controlplane.tenant.service.TenantService;
import io.hashmatrix.controlplane.tenant.web.dto.ApprovalRequest;
import io.hashmatrix.controlplane.tenant.web.dto.ProvisioningStatusView;
import io.hashmatrix.controlplane.tenant.web.dto.QuotaStatusView;
import io.hashmatrix.controlplane.tenant.web.dto.ReasonRequest;
import io.hashmatrix.controlplane.tenant.web.dto.RegisterTenantRequest;
import io.hashmatrix.controlplane.tenant.web.dto.TenantListView;
import io.hashmatrix.controlplane.tenant.web.dto.TenantView;
import io.hashmatrix.starter.web.ApiResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户生命周期 API（控制平面对外仅提供 API；运营 UI 由 webui {@code apps/admin} 承载）。
 *
 * <p>统一返回 {@link ApiResponse}（starter-web）；领域异常由 {@link TenantExceptionHandler} 映射状态码。
 *
 * <p><b>寻址键</b>：单租户端点一律以稳定路由键 {@code {tenantId}}（= 契约 {@code tenantId} / {@code X-Tenant-Id}）
 * 寻址，内部 UUID 不出对外边界（对齐契约 {@code openapi/control-plane-v1}）。类级 {@code /api/v1/tenants}
 * （网关 strip {@code /api} → 契约 {@code /v1/tenants}）。
 *
 * <p>鉴权模型（starter-security · 网关前置）：网关完成 OIDC 校验后下发 {@code X-User}/{@code X-Roles}，
 * 应用<b>不二次校验 token</b>，由 {@code GatewayPreAuthFilter} 还原 SecurityContext。非放行路径默认
 * {@code authenticated()}；跨租户高权变更（{@code approval}/{@code suspend}/{@code resume}/{@code delete}）
 * 经 {@link PreAuthorize} 限平台管理员（{@code superadmin}）。只读/注册端点仅需已认证主体。
 * 探针/指标（{@code /actuator/health|info|prometheus}）放行——见 starter-security 默认过滤链。
 */
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    /**
     * 跨租户高权操作门控：限平台管理员（网关下发 {@code X-Roles: superadmin} → 权限 {@code ROLE_superadmin}）。
     * 角色名小写，对齐平台既有约定（security 服务、admin-webui、realm 角色均小写，见 #16）。
     */
    private static final String REQUIRE_SUPERADMIN = "hasRole('superadmin')";

    private final TenantService service;

    public TenantController(TenantService service) {
        this.service = service;
    }

    /** 自助注册。 */
    @PostMapping
    public ResponseEntity<ApiResponse<TenantView>> register(
            @Valid @RequestBody RegisterTenantRequest request) {
        Tenant tenant = service.register(request.toCommand());
        return ResponseEntity.created(URI.create("/api/v1/tenants/" + tenant.getTenantKey()))
                .body(ApiResponse.ok(TenantView.from(tenant)));
    }

    /**
     * 分页列出租户目录，可选按 {@code status} 过滤（对齐契约 {@code listTenants} → {@code TenantList}）。
     *
     * <p>{@code page}（1 起，默认 1）/ {@code pageSize}（默认 20，契约上限 200）<b>钳制</b>而非拒绝越界值
     * （tolerant server）。排序按 {@code createdAt} 倒序（新者在前），保证分页稳定。{@code status} 缺省不过滤；
     * webui admin 待审队列以 {@code status=registered} 服务端过滤，取代 M1 临时的前端客户端过滤。
     */
    @GetMapping
    public ApiResponse<TenantListView> list(
            @RequestParam(required = false) TenantStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int p = Math.max(1, page);
        int size = Math.min(200, Math.max(1, pageSize));
        PageRequest pageable =
                PageRequest.of(p - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.ok(TenantListView.from(service.list(status, pageable), p, size));
    }

    @GetMapping("/{tenantId}")
    public ApiResponse<TenantView> get(@PathVariable String tenantId) {
        return ApiResponse.ok(TenantView.from(service.get(tenantId)));
    }

    /**
     * 查询租户配额额度与用量（对齐契约 {@code getTenantQuota} → {@code QuotaStatus}）。只读，仅需已认证。
     * M1 {@code usage} 为 no-op（不按量计费），见 {@link QuotaStatusView}。
     */
    @GetMapping("/{tenantId}/quota")
    public ApiResponse<QuotaStatusView> quota(@PathVariable String tenantId) {
        return ApiResponse.ok(QuotaStatusView.from(service.get(tenantId)));
    }

    /**
     * 查询命令式开通的整体阶段与分步进度（对齐契约 {@code getProvisioningStatus} → {@code ProvisioningStatus}）。
     * 只读，仅需已认证。M1 由租户生命周期状态<b>派生</b>（同步开通、未分步落库），见 {@link ProvisioningStatusView}。
     */
    @GetMapping("/{tenantId}/provisioning")
    public ApiResponse<ProvisioningStatusView> provisioning(@PathVariable String tenantId) {
        return ApiResponse.ok(ProvisioningStatusView.from(service.get(tenantId)));
    }

    /**
     * 审批裁决（单端点，对齐契约 {@code POST /{tenantId}/approval}）：
     * {@code approve} → 同步走完开通时序（返回 ACTIVE 或失败详情）；{@code reject} → 置 {@code deleted}（终态）。
     * 契约约束：{@code reject} 时 {@code reason} 必填（驳回不可逆，须留审计）。
     */
    @PreAuthorize(REQUIRE_SUPERADMIN)
    @PostMapping("/{tenantId}/approval")
    public ApiResponse<TenantView> decideApproval(
            @PathVariable String tenantId, @Valid @RequestBody(required = false) ApprovalRequest body) {
        if (body == null || body.decision() == null) {
            throw new InvalidApprovalRequestException("approval 须提供 decision（approve|reject）");
        }
        Tenant tenant;
        if (body.isReject()) {
            if (body.reason() == null || body.reason().isBlank()) {
                throw new InvalidApprovalRequestException("驳回（reject）须填写 reason（留审计）");
            }
            tenant = service.reject(tenantId, body.reason());
        } else {
            String note = body.reason() == null || body.reason().isBlank() ? "审批通过" : body.reason();
            tenant = service.approve(tenantId, note);
        }
        return ApiResponse.ok(TenantView.from(tenant));
    }

    @PreAuthorize(REQUIRE_SUPERADMIN)
    @PostMapping("/{tenantId}/suspend")
    public ApiResponse<TenantView> suspend(
            @PathVariable String tenantId, @Valid @RequestBody(required = false) ReasonRequest body) {
        String reason = body == null ? "管理员挂起" : body.reasonOrDefault("管理员挂起");
        return ApiResponse.ok(TenantView.from(service.suspend(tenantId, reason)));
    }

    @PreAuthorize(REQUIRE_SUPERADMIN)
    @PostMapping("/{tenantId}/resume")
    public ApiResponse<TenantView> resume(@PathVariable String tenantId) {
        return ApiResponse.ok(TenantView.from(service.resume(tenantId)));
    }

    /** 注销（尽力回收资源后置 DELETED 终态）。 */
    @PreAuthorize(REQUIRE_SUPERADMIN)
    @DeleteMapping("/{tenantId}")
    public ApiResponse<TenantView> delete(
            @PathVariable String tenantId, @Valid @RequestBody(required = false) ReasonRequest body) {
        String reason = body == null ? "管理员注销" : body.reasonOrDefault("管理员注销");
        return ApiResponse.ok(TenantView.from(service.delete(tenantId, reason)));
    }
}
