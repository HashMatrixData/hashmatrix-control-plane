package io.hashmatrix.controlplane.tenant.web;

import io.hashmatrix.controlplane.tenant.domain.TenantStatus;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * {@code ?status=} 查询参数 → {@link TenantStatus} 绑定转换器。
 *
 * <p>契约 {@code TenantStatus} 取值为<b>小写</b>（registered/active/…），而 Spring MVC 默认枚举绑定走
 * {@code Enum.valueOf}（区分大小写、需大写），故显式注册本转换器复用 {@link TenantStatus#fromJson}
 * （大小写容忍）。Spring Boot 自动把 {@link Converter} bean 纳入 MVC 转换服务。非法取值由 {@code fromJson}
 * 抛 {@link IllegalArgumentException}，经 {@code TenantExceptionHandler} 显式映射为 {@code 400}
 * （框架默认会落到 starter 兜底 500，故在该处显式归一，见其 {@code handleTypeMismatch}）。
 */
@Component
public class StringToTenantStatusConverter implements Converter<String, TenantStatus> {

    @Override
    public TenantStatus convert(String source) {
        return source == null || source.isBlank() ? null : TenantStatus.fromJson(source);
    }
}
