package io.hashmatrix.controlplane.tenant.member;

/**
 * 待邀请的用户在身份后端不存在（按邮箱/登录名未命中）。
 *
 * <p>M2 仅支持把<b>已存在</b>的 Keycloak user 加入组织（邮件邀请新建后置，见 #17 范围）。Web 层（ST2）按契约
 * 成员面定形映射为 4xx；HTTP 状态码由契约最终拍板，本异常只表达「用户不存在」语义。
 */
public class MemberUserNotFoundException extends RuntimeException {

    public MemberUserNotFoundException(String emailOrUsername) {
        super("身份后端中不存在该用户：" + emailOrUsername);
    }
}
