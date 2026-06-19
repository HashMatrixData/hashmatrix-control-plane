package io.hashmatrix.controlplane.tenant.web;

/**
 * 审批请求体不合法（缺 {@code decision}，或 {@code reject} 缺 {@code reason}）——这类<b>跨字段/条件</b>
 * 校验无法用 Bean Validation 注解表达，由控制器显式判定后抛出，Web 层映射为 400。
 *
 * <p>单独成类（不复用裸 {@link IllegalArgumentException}）：避免「按异常类型而非语义」的全局兜底把
 * 编排/适配器内部偶发的 {@code IllegalArgumentException} 误降级为 400「客户端错误」、掩盖真实服务端故障。
 */
public class InvalidApprovalRequestException extends RuntimeException {

    public InvalidApprovalRequestException(String message) {
        super(message);
    }
}
