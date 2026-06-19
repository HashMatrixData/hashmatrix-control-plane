package io.hashmatrix.controlplane.tenant.repo;

import io.hashmatrix.controlplane.tenant.domain.Tenant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 租户目录持久化（PostgreSQL，schema 由 Flyway 管理）。 */
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findByTenantKey(String tenantKey);

    boolean existsByTenantKey(String tenantKey);

    /**
     * 按租户管理员邮箱查租户。
     *
     * <p>支撑 {@code GET /me/tenants} 的 M1 成员资格启发式（当前主体 == 某租户 {@code adminEmail}）——尚无
     * user↔tenant 关联表，故以 {@code adminEmail} 派生 membership；返回 {@link List} 而非 {@link Optional}，
     * 为 D1（单 User 多 Org Membership）的多租户演进预留，调用方一律按数组处理。
     */
    List<Tenant> findByAdminEmail(String adminEmail);
}
