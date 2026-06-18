package io.hashmatrix.controlplane.provisioning.spi;

/**
 * 身份开通端口 —— Keycloak Admin API：建 Organization + 租户管理员（+ 可选联邦客户 AD）。
 *
 * <p>开通时序第 ① 步（架构 05 §4）。真实实现接 Keycloak Admin REST；当前提供 stub 适配器（见
 * {@code provisioning.stub}），无活 Keycloak 即可跑通时序。
 */
public interface IdentityProvisioner {

    /** 建立租户身份资源，返回 Keycloak Organization id。 */
    String provision(ProvisioningRequest request);

    /** 回收租户身份资源（注销时）。实现须幂等。 */
    void deprovision(ProvisioningRequest request);
}
