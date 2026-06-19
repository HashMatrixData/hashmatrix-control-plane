package io.hashmatrix.controlplane.provisioning.stub;

import io.hashmatrix.controlplane.provisioning.metering.MeteringPort;
import io.hashmatrix.controlplane.provisioning.spi.ComputeProvisioner;
import io.hashmatrix.controlplane.provisioning.spi.DataProvisioner;
import io.hashmatrix.controlplane.provisioning.spi.IdentityProvisioner;
import io.hashmatrix.controlplane.provisioning.spi.ProvisioningRequest;
import io.hashmatrix.controlplane.provisioning.spi.SecretsProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stub 开通适配器装配 —— {@code hashmatrix.control-plane.provisioning.mode=stub}（默认）时生效。
 *
 * <p>用日志/确定性返回值模拟开通时序，**无活集群即可端到端跑通**（本地调试 / 集成测试）。真实
 * Keycloak/Helm/K8s/Doris/ESO 适配器接入后，以对应 {@code mode} 提供同名端口 Bean 替换之
 * （{@link ConditionalOnMissingBean} 让真实 Bean 优先）。
 *
 * <p>⚠️ identity 端口单独可<b>逐端口</b>切真实适配器：{@code stubIdentityProvisioner} 额外 gate 于
 * {@code provisioning.identity!=keycloak}，使 {@code identity=keycloak} 时它让位于真实 Keycloak 适配器
 * （{@code KeycloakProvisioningConfiguration}），而 compute/data/secrets 三个 stub 不受影响——
 * 「真 identity + stub 其余」可共存。两者按属性互斥，不依赖 Bean 注册顺序。
 *
 * <p>📌 后续接入者注意：当前 {@code mode} 仅 {@code stub} 一值有效，装配完备。若将来引入非 stub 的
 * {@code mode}（如 {@code helm}）却未同时为该端口提供真实适配器，本类整体关闭会使对应端口 Bean 缺失、
 * 编排器构造注入失败。届时应把各端口 stub 改为各自独立的 {@code @ConditionalOnMissingBean} 兜底（脱离
 * 类级 {@code mode} 守卫），与此处 identity 的「逐端口」模式一致。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "hashmatrix.control-plane.provisioning",
        name = "mode",
        havingValue = "stub",
        matchIfMissing = true)
public class StubProvisioningConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StubProvisioningConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "hashmatrix.control-plane.provisioning",
            name = "identity",
            havingValue = "stub",
            matchIfMissing = true)
    IdentityProvisioner stubIdentityProvisioner() {
        return new IdentityProvisioner() {
            @Override
            public String provision(ProvisioningRequest request) {
                String orgId = "org-" + request.tenantKey();
                log.info("[stub:identity] 建 Keycloak Organization org={} admin={}", orgId, request.adminEmail());
                return orgId;
            }

            @Override
            public void deprovision(ProvisioningRequest request) {
                log.info("[stub:identity] 回收 Keycloak Organization tenantKey={}", request.tenantKey());
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    ComputeProvisioner stubComputeProvisioner() {
        return new ComputeProvisioner() {
            @Override
            public String provision(ProvisioningRequest request) {
                String namespace = TenantNaming.namespace(request.tenantKey());
                log.info(
                        "[stub:compute] 渲染并应用 per-tenant release ns={} quota(users={},storageGi={},jobs={},cpu={},memGi={})",
                        namespace,
                        request.quota().maxUsers(),
                        request.quota().maxStorageGi(),
                        request.quota().maxConcurrentJobs(),
                        request.quota().computeCpuCores(),
                        request.quota().computeMemoryGi());
                return namespace;
            }

            @Override
            public void deprovision(ProvisioningRequest request) {
                log.info("[stub:compute] 卸载 per-tenant release ns={}", TenantNaming.namespace(request.tenantKey()));
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    DataProvisioner stubDataProvisioner() {
        return new DataProvisioner() {
            @Override
            public String provision(ProvisioningRequest request) {
                String schema = TenantNaming.schema(request.tenantKey());
                log.info("[stub:data] 建 schema/db + Doris catalog schema={}", schema);
                return schema;
            }

            @Override
            public void deprovision(ProvisioningRequest request) {
                log.info("[stub:data] 回收 schema/db schema={}", TenantNaming.schema(request.tenantKey()));
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    SecretsProvisioner stubSecretsProvisioner() {
        return new SecretsProvisioner() {
            @Override
            public void provision(ProvisioningRequest request) {
                log.info("[stub:secrets] 配置 ESO ExternalSecret tenantKey={}", request.tenantKey());
            }

            @Override
            public void deprovision(ProvisioningRequest request) {
                log.info("[stub:secrets] 回收 ESO ExternalSecret tenantKey={}", request.tenantKey());
            }
        };
    }

    /** 计量仅预留接口：默认 no-op，不产生任何计量副作用（spec 决策 M8）。 */
    @Bean
    @ConditionalOnMissingBean
    MeteringPort noopMeteringPort() {
        return (tenantId, metric, value) -> { /* no-op：政企合同制不按量计费 */ };
    }
}
