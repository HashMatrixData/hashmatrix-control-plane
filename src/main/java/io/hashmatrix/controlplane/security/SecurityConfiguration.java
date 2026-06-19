package io.hashmatrix.controlplane.security;

import io.hashmatrix.starter.security.GatewayPreAuthFilter;
import io.hashmatrix.starter.security.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 控制平面安全过滤链：在 starter-security「网关前置鉴权」基线上，仅补一处——为<b>未认证</b>请求返回
 * {@code 401 Unauthorized}（starter 默认无 {@code AuthenticationEntryPoint}，匿名访问受保护资源会落到
 * Spring 默认的 {@code 403}）。补齐后语义清晰：<b>无身份 → 401、已认证但缺角色 → 403</b>，符合控制平面
 * （跨租户高权面）对鉴权矩阵的要求。
 *
 * <p>本 Bean 经 starter 的扩展点接管默认链（starter 的 {@code SecurityFilterChain} 标注
 * {@code @ConditionalOnMissingBean}，见其类注释「子仓可提供自定义 SecurityFilterChain 覆盖」）。
 * 其余装配仍复用 starter：注入其 {@link GatewayPreAuthFilter} 与 {@link SecurityProperties}（含
 * {@code permitPaths}），不重造身份解析与放行清单；方法级授权（{@code @EnableMethodSecurity}）仍由
 * starter 的 {@code SecurityFilterChainConfiguration} 提供（其类仍加载，仅 Bean 让位于本类）。
 *
 * <p>📌 后续：401 入口点属「网关前置鉴权」通用语义，宜上收至 {@code starter-security}
 * （libs-java）统一提供；上收后本类可删除。在此之前由本仓自持，保证 #5 自洽且 CI 可复现。
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /**
     * 覆盖 starter 默认链：放行探针/指标，其余需认证，前置网关预认证过滤器，并把未认证响应钉为 401。
     * 与 starter 默认链保持一致（仅多一处 {@code authenticationEntryPoint}）。
     */
    @Bean
    SecurityFilterChain controlPlaneSecurityFilterChain(
            HttpSecurity http, GatewayPreAuthFilter preAuthFilter, SecurityProperties properties)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        registry ->
                                registry.requestMatchers(
                                                properties.getPermitPaths().toArray(new String[0]))
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                // 无身份（匿名）→ 401；已认证但缺 SUPERADMIN 角色 → 由默认 AccessDeniedHandler 返回 403。
                .exceptionHandling(
                        ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(preAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
