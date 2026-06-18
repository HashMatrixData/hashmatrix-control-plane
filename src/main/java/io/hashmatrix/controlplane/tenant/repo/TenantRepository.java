package io.hashmatrix.controlplane.tenant.repo;

import io.hashmatrix.controlplane.tenant.domain.Tenant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 租户目录持久化（PostgreSQL，schema 由 Flyway 管理）。 */
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findByTenantKey(String tenantKey);

    boolean existsByTenantKey(String tenantKey);
}
