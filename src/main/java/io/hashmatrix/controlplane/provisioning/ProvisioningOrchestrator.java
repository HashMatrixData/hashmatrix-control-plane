package io.hashmatrix.controlplane.provisioning;

import io.hashmatrix.controlplane.provisioning.spi.ComputeProvisioner;
import io.hashmatrix.controlplane.provisioning.spi.DataProvisioner;
import io.hashmatrix.controlplane.provisioning.spi.IdentityProvisioner;
import io.hashmatrix.controlplane.provisioning.spi.ProvisioningRequest;
import io.hashmatrix.controlplane.provisioning.spi.SecretsProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 开通编排器 —— 按架构 05 §4 时序命令式驱动各端口：
 *
 * <pre>
 *   ① Keycloak Organization → ② namespace/quota/netpol/服务实例 → ③ schema/db·Doris catalog → ④ ESO secrets
 * </pre>
 *
 * <p>纯编排：不直接管理租户状态机（由 {@code TenantService} 负责 PROVISIONING/ACTIVE/回退流转），
 * 仅返回 {@link ProvisioningOutcome} 或抛 {@link ProvisioningException}。任一步失败即中止，已完成步骤的
 * 补偿（回滚）随真实适配器接入时按步骤补充——当前 stub 适配器无副作用。
 */
@Component
public class ProvisioningOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningOrchestrator.class);

    private final IdentityProvisioner identity;
    private final ComputeProvisioner compute;
    private final DataProvisioner data;
    private final SecretsProvisioner secrets;

    public ProvisioningOrchestrator(
            IdentityProvisioner identity,
            ComputeProvisioner compute,
            DataProvisioner data,
            SecretsProvisioner secrets) {
        this.identity = identity;
        this.compute = compute;
        this.data = data;
        this.secrets = secrets;
    }

    /**
     * 执行租户开通全链路。
     *
     * @return 开通产出（org/namespace/schema），供调用方回写租户目录
     * @throws ProvisioningException 任一步失败
     */
    public ProvisioningOutcome provision(ProvisioningRequest request) {
        log.info("开始开通租户 tenantKey={} id={}", request.tenantKey(), request.tenantId());

        String orgId = step("identity", () -> identity.provision(request));
        String namespace = step("compute", () -> compute.provision(request));
        String dbSchema = step("data", () -> data.provision(request));
        step("secrets", () -> {
            secrets.provision(request);
            return null;
        });

        log.info(
                "租户开通完成 tenantKey={} org={} ns={} schema={}",
                request.tenantKey(),
                orgId,
                namespace,
                dbSchema);
        return new ProvisioningOutcome(orgId, namespace, dbSchema);
    }

    /**
     * 尽力回收租户资源（注销时），按开通逆序：secrets → data → compute → identity。
     *
     * <p>各端口 {@code deprovision} 须幂等；单步失败仅记录并继续，不中断其余回收（避免残留泄漏）。
     * 真实适配器应保证「数据删除」走强校验/留痕。
     */
    public void deprovision(ProvisioningRequest request) {
        log.info("开始回收租户 tenantKey={} id={}", request.tenantKey(), request.tenantId());
        bestEffort("secrets", () -> secrets.deprovision(request));
        bestEffort("data", () -> data.deprovision(request));
        bestEffort("compute", () -> compute.deprovision(request));
        bestEffort("identity", () -> identity.deprovision(request));
    }

    private void bestEffort(String name, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("回收步骤[{}]失败，继续回收其余资源：{}", name, e.getMessage(), e);
        }
    }

    private <T> T step(String name, ProvisioningStep<T> action) {
        try {
            return action.run();
        } catch (ProvisioningException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProvisioningException(name, e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface ProvisioningStep<T> {
        T run();
    }
}
