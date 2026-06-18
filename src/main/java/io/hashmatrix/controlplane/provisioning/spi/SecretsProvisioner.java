package io.hashmatrix.controlplane.provisioning.spi;

/**
 * Secrets 开通端口 —— External Secrets Operator 注入租户 secrets（架构 05 §4 第 ④ 步）。
 *
 * <p>🔴 红线：secrets 一律经 ESO 从外部 Vault/KMS 注入，**不入库**。真实实现下发 ExternalSecret/
 * SecretStore 资源；当前提供 stub 适配器。
 */
public interface SecretsProvisioner {

    /** 为租户配置 ESO ExternalSecret，使其 secrets 由外部密管注入。 */
    void provision(ProvisioningRequest request);

    /** 回收租户 secrets 配置（注销时）。实现须幂等。 */
    void deprovision(ProvisioningRequest request);
}
