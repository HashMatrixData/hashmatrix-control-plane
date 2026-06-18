package io.hashmatrix.controlplane.tenant.domain;

/**
 * 交付形态（双模，见架构 05 §1）。隔离机制同一套，仅「租户」语义随模式而变。
 */
public enum DeliveryMode {
    /** 公网 SaaS：我们运营、统一品牌，租户 = 一个企业客户。 */
    PUBLIC_SAAS,
    /** 私有化部署：客户环境、客户品牌（部署级），租户 = 客户的部门。 */
    PRIVATE
}
