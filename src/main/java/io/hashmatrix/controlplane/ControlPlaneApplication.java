package io.hashmatrix.controlplane;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 多租户控制平面服务入口。
 *
 * <p>承载租户注册 / 开通编排（provision）/ 生命周期 / 配额 / 租户目录；经 Keycloak Admin API +
 * Helm SDK + Kubernetes client 命令式开通租户资源（生产期不耦合 Git/Argo）。设计见主仓
 * {@code docs/architecture/05-多租户与控制平面.md}。
 */
@SpringBootApplication
public class ControlPlaneApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlPlaneApplication.class, args);
    }
}
