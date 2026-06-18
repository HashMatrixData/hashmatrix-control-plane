-- 租户目录（control-plane 单一事实源）。
-- 状态机：registered → approving → provisioning → active → suspended → deleted（见架构 05 §4）。
-- 占位/示例一律脱敏（acme / tenant-demo / @example.com）——红线见 CLAUDE.md。

CREATE TABLE tenant (
    id              UUID         PRIMARY KEY,
    -- 稳定租户标识 = 数据/计算隔离的路由键（schema/catalog/namespace），对齐 ICD tenant-context-headers 的 X-Tenant-Id。
    tenant_key      VARCHAR(63)  NOT NULL,
    display_name    VARCHAR(255) NOT NULL,
    -- 交付形态：PUBLIC_SAAS=企业客户 / PRIVATE=客户的部门（双模，见架构 05 §1）。
    delivery_mode   VARCHAR(32)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    -- 自助注册时的租户管理员邮箱（脱敏占位 @example.com）。
    admin_email     VARCHAR(320) NOT NULL,

    -- 开通后回写的接入信息（provision 前为空）。
    keycloak_org_id VARCHAR(255),
    namespace       VARCHAR(63),
    db_schema       VARCHAR(63),

    -- 业务配额硬限（用户数/数据量/作业数）；K8s ResourceQuota 由开通时按此渲染。
    quota_max_users INTEGER      NOT NULL DEFAULT 0,
    quota_max_data_bytes BIGINT  NOT NULL DEFAULT 0,
    quota_max_jobs  INTEGER      NOT NULL DEFAULT 0,

    -- 最近一次状态流转的原因/失败详情（审计与排障用）。
    status_reason   VARCHAR(1024),

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 乐观锁，防并发流转脏写。
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uq_tenant_key UNIQUE (tenant_key)
);

CREATE INDEX idx_tenant_status ON tenant (status);
