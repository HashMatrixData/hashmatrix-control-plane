package io.hashmatrix.controlplane.tenant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 租户聚合根 —— 控制平面租户目录的单一事实源。
 *
 * <p>状态流转一律经 {@link #transitionTo(TenantStatus, String)}，由 {@link TenantStatus} 转移表守门，
 * 不得绕过直接改 status；开通结果（org/namespace/schema）经 {@link #recordProvisioningOutcome} 回写。
 */
@Entity
@Table(name = "tenant")
public class Tenant {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_key", nullable = false, updatable = false, length = 63)
    private String tenantKey;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_mode", nullable = false, length = 32)
    private DeliveryMode deliveryMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TenantStatus status;

    @Column(name = "admin_email", nullable = false, length = 320)
    private String adminEmail;

    @Column(name = "keycloak_org_id")
    private String keycloakOrgId;

    @Column(name = "namespace", length = 63)
    private String namespace;

    @Column(name = "db_schema", length = 63)
    private String dbSchema;

    @Embedded
    private TenantQuota quota;

    @Column(name = "status_reason", length = 1024)
    private String statusReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** JPA 用。 */
    protected Tenant() {}

    private Tenant(
            UUID id,
            String tenantKey,
            String displayName,
            DeliveryMode deliveryMode,
            String adminEmail,
            TenantQuota quota) {
        this.id = id;
        this.tenantKey = tenantKey;
        this.displayName = displayName;
        this.deliveryMode = deliveryMode;
        this.adminEmail = adminEmail;
        this.quota = quota;
        this.status = TenantStatus.REGISTERED;
    }

    /**
     * 自助注册一个新租户，初始状态 {@link TenantStatus#REGISTERED}。
     *
     * @param tenantKey 稳定租户标识（路由键，对齐 ICD 的 X-Tenant-Id）
     */
    public static Tenant register(
            String tenantKey,
            String displayName,
            DeliveryMode deliveryMode,
            String adminEmail,
            TenantQuota quota) {
        return new Tenant(
                UUID.randomUUID(),
                tenantKey,
                displayName,
                deliveryMode,
                adminEmail,
                TenantQuota.orDefault(quota));
    }

    /**
     * 受状态机守护的流转。非法流转抛 {@link TenantStatusTransitionException}。
     *
     * @param target 目标状态
     * @param reason 流转原因/失败详情（审计），可为 {@code null}
     */
    public void transitionTo(TenantStatus target, String reason) {
        this.status.checkTransitionTo(target);
        this.status = target;
        this.statusReason = reason;
    }

    /** 回写开通产出的接入信息（org/namespace/schema）。 */
    public void recordProvisioningOutcome(String keycloakOrgId, String namespace, String dbSchema) {
        this.keycloakOrgId = keycloakOrgId;
        this.namespace = namespace;
        this.dbSchema = dbSchema;
    }

    public UUID getId() {
        return id;
    }

    public String getTenantKey() {
        return tenantKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public DeliveryMode getDeliveryMode() {
        return deliveryMode;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public String getAdminEmail() {
        return adminEmail;
    }

    public String getKeycloakOrgId() {
        return keycloakOrgId;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getDbSchema() {
        return dbSchema;
    }

    public TenantQuota getQuota() {
        return quota;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
