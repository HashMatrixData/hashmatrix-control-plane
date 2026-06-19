package io.hashmatrix.controlplane.tenant.web;

import io.hashmatrix.controlplane.tenant.service.TenantService;
import io.hashmatrix.controlplane.tenant.web.dto.MembershipView;
import io.hashmatrix.starter.web.ApiResponse;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录用户自助视图（跨租户 membership）——契约 {@code openapi/control-plane-v1} 的 {@code me} 资源根。
 *
 * <p><b>独立于 {@link TenantController}</b>：契约把 {@code /v1/me/tenants} 建模为<b>独立资源根</b>
 * （{@code me/tenants}，<b>不在</b> {@code /tenants} 之下）。若塞进 {@code TenantController}
 * （类级 {@code /api/v1/tenants}）只会得到 {@code /api/v1/tenants/me}，破契约一致性并令 webui 路径错配；
 * 故单开本控制器，类级 {@code /api/v1/me}（网关 strip {@code /api} → 契约 {@code /v1/me}）。
 *
 * <p>鉴权：非放行路径默认 {@code authenticated()}（见 {@code SecurityConfiguration}）——无身份 → 401。
 * 「当前用户」取自 {@link Authentication}（由 {@code GatewayPreAuthFilter} 据网关下发的 {@code X-User} 还原），
 * <b>不裸读 header</b>。任意已认证用户只查自身 membership，故不加 {@code SUPERADMIN} 门控。
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final TenantService service;

    public MeController(TenantService service) {
        this.service = service;
    }

    /**
     * 列出当前登录用户的租户 membership。恒返回数组（M1 ≤1 个、可为空），对齐 D1/D2 与契约 {@code listMyTenants}。
     *
     * <p>{@code authentication} 非空由安全链保证——本路径非 {@code permitPaths}、链上 {@code authenticated()}，
     * 匿名请求在进入控制器前已被 401 entryPoint 拦截（见 {@code SecurityConfiguration}，{@code MeControllerTest}
     * 的「无头 → 401」用例守护）；故此处直接取主体名，不再判空。
     */
    @GetMapping("/tenants")
    public ApiResponse<List<MembershipView>> myTenants(Authentication authentication) {
        List<MembershipView> memberships =
                service.listMembershipsFor(authentication.getName()).stream()
                        .map(MembershipView::from)
                        .toList();
        return ApiResponse.ok(memberships);
    }
}
