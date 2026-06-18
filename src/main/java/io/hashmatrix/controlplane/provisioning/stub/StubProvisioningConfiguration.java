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
                        "[stub:compute] 渲染并应用 per-tenant release ns={} quota(users={},bytes={},jobs={})",
                        namespace,
                        request.quota().maxUsers(),
                        request.quota().maxDataBytes(),
                        request.quota().maxJobs());
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
