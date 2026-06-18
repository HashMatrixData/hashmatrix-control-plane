package io.hashmatrix.controlplane.provisioning.spi;

/**
 * 计算开通端口 —— 经 Helm SDK/CLI + Kubernetes client 命令式开通（架构 05 §4 第 ② 步）：
 * namespace + ResourceQuota/LimitRange + NetworkPolicy + 拉起该租户服务实例。
 *
 * <p>生产期不耦合 Git/Argo。真实实现接 Helm SDK + fabric8/官方 K8s client；当前提供 stub 适配器。
 */
public interface ComputeProvisioner {

    /** 渲染并应用 per-tenant release，返回租户 namespace 名。 */
    String provision(ProvisioningRequest request);

    /** 卸载 per-tenant release（注销时）。实现须幂等。 */
    void deprovision(ProvisioningRequest request);
}
