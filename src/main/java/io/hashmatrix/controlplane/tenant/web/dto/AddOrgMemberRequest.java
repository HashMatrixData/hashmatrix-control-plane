package io.hashmatrix.controlplane.tenant.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 邀请成员请求体（契约 {@code AddOrgMemberRequest}）——把一个<b>已存在</b>的 Keycloak 用户加入当前租户组织。
 *
 * @param emailOrUsername 待加入成员的 email 或 username（须为身份后端中已存在的用户；M2 不做邮件邀请新建）
 */
public record AddOrgMemberRequest(@NotBlank String emailOrUsername) {}
