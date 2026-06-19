package io.hashmatrix.controlplane.tenant.web.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * 审批裁决（契约 {@code ApprovalDecision.decision} 枚举：{@code approve} / {@code reject}）。
 *
 * <p>序列化为小写（{@link JsonValue}），与契约一致；反序列化容忍大小写（tolerant reader）。
 */
public enum ApprovalDecision {
    APPROVE,
    REJECT;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ApprovalDecision fromJson(String value) {
        return value == null ? null : valueOf(value.toUpperCase(Locale.ROOT));
    }
}
