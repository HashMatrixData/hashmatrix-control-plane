package io.hashmatrix.controlplane.tenant.web;

import io.hashmatrix.controlplane.tenant.domain.Tenant;
import io.hashmatrix.controlplane.tenant.service.TenantService;
import io.hashmatrix.controlplane.tenant.web.dto.ReasonRequest;
import io.hashmatrix.controlplane.tenant.web.dto.RegisterTenantRequest;
import io.hashmatrix.controlplane.tenant.web.dto.TenantView;
import io.hashmatrix.starter.web.ApiResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户生命周期 API（控制平面对外仅提供 API；运营 UI 由 webui {@code apps/admin} 承载）。
 *
 * <p>统一返回 {@link ApiResponse}（starter-web）；领域异常由 {@link TenantExceptionHandler} 映射状态码。
 *
 * <p>鉴权模型（starter-security · 网关前置）：网关完成 OIDC 校验后下发 {@code X-User}/{@code X-Roles}，
 * 应用<b>不二次校验 token</b>，由 {@code GatewayPreAuthFilter} 还原 SecurityContext。非放行路径默认
 * {@code authenticated()}；跨租户高权变更（{@code approve}/{@code reject}/{@code suspend}/{@code resume}/
 * {@code delete}）经 {@link PreAuthorize} 限平台管理员（{@code SUPERADMIN}）。只读/注册端点仅需已认证主体。
 * 探针/指标（{@code /actuator/health|info|prometheus}）放行——见 {@code SecurityConfiguration}。
 */
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    /** 跨租户高权操作门控：限平台管理员（网关下发 {@code X-Roles: SUPERADMIN} → 权限 {@code ROLE_SUPERADMIN}）。 */
    private static final String REQUIRE_SUPERADMIN = "hasRole('SUPERADMIN')";

    private final TenantService service;

    public TenantController(TenantService service) {
        this.service = service;
    }

    /** 自助注册。 */
    @PostMapping
    public ResponseEntity<ApiResponse<TenantView>> register(
            @Valid @RequestBody RegisterTenantRequest request) {
        Tenant tenant = service.register(request.toCommand());
        return ResponseEntity.created(URI.create("/api/v1/tenants/" + tenant.getId()))
                .body(ApiResponse.ok(TenantView.from(tenant)));
    }

    @GetMapping
    public ApiResponse<List<TenantView>> list() {
        return ApiResponse.ok(service.list().stream().map(TenantView::from).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<TenantView> get(@PathVariable UUID id) {
        return ApiResponse.ok(TenantView.from(service.get(id)));
    }

    /** 审批通过并触发开通（同步走完开通时序，返回 ACTIVE 或失败详情）。 */
    @PreAuthorize(REQUIRE_SUPERADMIN)
    @PostMapping("/{id}/approve")
    public ApiResponse<TenantView> approve(
            @PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest body) {
        String note = body == null ? "审批通过" : body.reasonOrDefault("审批通过");
        return ApiResponse.ok(TenantView.from(service.approve(id, note)));
    }

    /** 审批驳回。 */
    @PreAuthorize(REQUIRE_SUPERADMIN)
    @PostMapping("/{id}/reject")
    public ApiResponse<TenantView> reject(
            @PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest body) {
        String reason = body == null ? "未说明" : body.reasonOrDefault("未说明");
        return ApiResponse.ok(TenantView.from(service.reject(id, reason)));
    }

    @PreAuthorize(REQUIRE_SUPERADMIN)
    @PostMapping("/{id}/suspend")
    public ApiResponse<TenantView> suspend(
            @PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest body) {
        String reason = body == null ? "管理员挂起" : body.reasonOrDefault("管理员挂起");
        return ApiResponse.ok(TenantView.from(service.suspend(id, reason)));
    }

    @PreAuthorize(REQUIRE_SUPERADMIN)
    @PostMapping("/{id}/resume")
    public ApiResponse<TenantView> resume(@PathVariable UUID id) {
        return ApiResponse.ok(TenantView.from(service.resume(id)));
    }

    /** 注销（尽力回收资源后置 DELETED 终态）。 */
    @PreAuthorize(REQUIRE_SUPERADMIN)
    @DeleteMapping("/{id}")
    public ApiResponse<TenantView> delete(
            @PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest body) {
        String reason = body == null ? "管理员注销" : body.reasonOrDefault("管理员注销");
        return ApiResponse.ok(TenantView.from(service.delete(id, reason)));
    }
}
