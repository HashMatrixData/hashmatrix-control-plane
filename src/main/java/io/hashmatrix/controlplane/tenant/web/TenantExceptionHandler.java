package io.hashmatrix.controlplane.tenant.web;

import io.hashmatrix.controlplane.provisioning.ProvisioningException;
import io.hashmatrix.controlplane.tenant.domain.TenantStatusTransitionException;
import io.hashmatrix.controlplane.tenant.service.TenantKeyConflictException;
import io.hashmatrix.controlplane.tenant.service.TenantNotFoundException;
import io.hashmatrix.starter.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 控制平面领域异常 → HTTP 状态码映射。
 *
 * <p>处理特定领域异常（比 starter-web 的兜底 {@code GlobalExceptionHandler} 更具体，优先匹配），
 * 统一以 {@link ApiResponse} 结构返回，错误码语义化、不泄露内部细节。
 */
@RestControllerAdvice
public class TenantExceptionHandler {

    @ExceptionHandler(TenantNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(TenantNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail("TENANT_NOT_FOUND", ex.getMessage()));
    }

    /**
     * 审批请求体跨字段/条件校验失败 → 400（缺 {@code decision}，或 {@code reject} 缺 {@code reason}）。
     *
     * <p>精确匹配专用异常而非裸 {@link IllegalArgumentException}：不把编排/适配器内部偶发的 IAE 误判为
     * 「客户端错误」400（那类应保留为 500/502 服务端语义）。
     */
    @ExceptionHandler(InvalidApprovalRequestException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidApproval(
            InvalidApprovalRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail("INVALID_APPROVAL", ex.getMessage()));
    }

    /**
     * 查询参数类型/取值不匹配 → 400（如 {@code ?status=bogus} 经 {@code StringToTenantStatusConverter} 抛
     * {@link IllegalArgumentException}）。显式映射：避免落到 starter-web 兜底 {@code GlobalExceptionHandler}
     * 被归为 500（实测默认即 500）。仅回显参数名（用户输入侧），不外泄内部细节。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail("INVALID_PARAMETER", "查询参数取值非法：" + ex.getName()));
    }

    @ExceptionHandler(TenantKeyConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleKeyConflict(TenantKeyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail("TENANT_KEY_CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(TenantStatusTransitionException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalTransition(
            TenantStatusTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail("ILLEGAL_TENANT_TRANSITION", ex.getMessage()));
    }

    @ExceptionHandler(ProvisioningException.class)
    public ResponseEntity<ApiResponse<Void>> handleProvisioning(ProvisioningException ex) {
        // 开通依赖下游（Keycloak/K8s/数据），失败语义为「网关后端不可用」→ 502。
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.fail("PROVISIONING_FAILED", ex.getMessage()));
    }
}
