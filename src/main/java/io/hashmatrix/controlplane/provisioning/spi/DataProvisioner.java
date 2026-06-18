package io.hashmatrix.controlplane.provisioning.spi;

/**
 * 数据开通端口 —— schema/db(PostgreSQL) + Doris/Paimon catalog/database（架构 05 §4 第 ③ 步）。
 *
 * <p>对应数据隔离档位 schema/db-per-tenant。真实实现执行 DDL/catalog 建模；当前提供 stub 适配器。
 */
public interface DataProvisioner {

    /** 建立租户数据隔离单元，返回业务库 schema 名。 */
    String provision(ProvisioningRequest request);

    /** 回收租户数据单元（注销时）。实现须幂等，且对数据删除做强校验/留痕。 */
    void deprovision(ProvisioningRequest request);
}
