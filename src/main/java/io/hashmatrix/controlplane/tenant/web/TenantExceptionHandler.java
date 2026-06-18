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
