package io.hashmatrix.controlplane.tenant.web;

import io.hashmatrix.controlplane.tenant.member.OrgMember;
import io.hashmatrix.controlplane.tenant.member.OrgMemberService;
import io.hashmatrix.controlplane.tenant.web.dto.AddOrgMemberRequest;
import io.hashmatrix.controlplane.tenant.web.dto.OrgMemberView;
import io.hashmatrix.starter.tenant.TenantContextHolder;
import io.hashmatrix.starter.web.ApiResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
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
 * 组织成员自管理 API（契约 {@code openapi/control-plane-v1} 的 {@code /v1/org/members}，M2 链③）。
 *
 * <p><b>租户自管理面</b>，区别于 {@link TenantController} 的 superadmin 跨租户运营面：
 *
 * <ul>
 *   <li><b>寻址（D2/D9）</b>：活动租户<b>唯一</b>来自网关注入的 {@code X-Tenant-Id}（经 starter-tenant
 *       {@link TenantContextHolder} 还原），<b>不</b>在 path 携带 {@code tenantId}——避免双源、守 D2；
 *       {@link OrgMemberService} 据此解析本租户 org 并强制隔离，调用方无法跨租户寻址。
 *   <li><b>门控</b>：{@code hasRole('tenant-admin')}（网关下发 {@code X-Roles: tenant-admin} →
 *       权限 {@code ROLE_tenant-admin}）——租户管理员自管理，非平台 superadmin。非管理员 → 403。
 *   <li><b>SoT（D1/R3）</b>：成员真相在 Keycloak Org，本控制器经 service→KC Admin API 编排，
 *       响应 {@link OrgMemberView} 不含任何凭据。
 * </ul>
 *
 * <p>类级 {@code /api/v1/org/members}（网关 strip {@code /api} → 契约 {@code /v1/org/members}）。
 * 领域异常（用户不存在 / 租户未开通组织 / 缺租户头）由 {@link TenantExceptionHandler} 映射状态码。
 */
@RestController
@RequestMapping("/api/v1/org/members")
public class OrgMemberController {

    /** 租户自管理门控：限当前租户管理员（区别于 {@code TenantController} 的 superadmin 跨租户面）。 */
    private static final String REQUIRE_TENANT_ADMIN = "hasRole('tenant-admin')";

    private final OrgMemberService service;

    public OrgMemberController(OrgMemberService service) {
        this.service = service;
    }

    /** 列出本租户组织成员。 */
    @GetMapping
    @PreAuthorize(REQUIRE_TENANT_ADMIN)
    public ApiResponse<List<OrgMemberView>> list() {
        List<OrgMemberView> members =
                service.listMembers(currentTenant()).stream().map(OrgMemberView::from).toList();
        return ApiResponse.ok(members);
    }

    /** 邀请：把已存在用户（email/username）加入本租户组织。 */
    @PostMapping
    @PreAuthorize(REQUIRE_TENANT_ADMIN)
    public ResponseEntity<ApiResponse<OrgMemberView>> add(
            @Valid @RequestBody AddOrgMemberRequest request) {
        OrgMember added = service.addMember(currentTenant(), request.emailOrUsername());
        return ResponseEntity.created(URI.create("/api/v1/org/members/" + added.id()))
                .body(ApiResponse.ok(OrgMemberView.from(added)));
    }

    /** 从本租户组织移除成员（幂等 → 204）。 */
    @DeleteMapping("/{userId}")
    @PreAuthorize(REQUIRE_TENANT_ADMIN)
    public ResponseEntity<Void> remove(@PathVariable String userId) {
        service.removeMember(currentTenant(), userId);
        return ResponseEntity.noContent().build();
    }

    /** 当前活动租户路由键（= {@code X-Tenant-Id}）；缺租户头抛 {@code TenantContextMissingException} → 400。 */
    private String currentTenant() {
        return TenantContextHolder.requireTenantId();
    }
}
