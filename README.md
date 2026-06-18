# hashmatrix-control-plane

> hashmatrix 数据中台子模块 · 所属：横切 · 控制平面（跨租户单例）
>
> 主仓：[HashMatrixData/hashmatrix](https://github.com/HashMatrixData/hashmatrix)

## 角色与位置（一眼看懂）

- **所属**：横切层 · **多租户控制平面**（跨租户单例，与数据平面分离）。
- **一句话**：多租户的"中枢"——新企业注册即开通一套服务，并治理其资源隔离与生命周期。
- **编排流**：注册 → 审批 → **control-plane** →（Keycloak Org + Helm/K8s + 数据存储 + secrets）→ 一套隔离的租户服务。

## 职责与边界

- **做**：租户注册 / 开通(provision) / 生命周期 / 配额 / 租户目录；建 namespace、schema·db、服务实例、secrets；管理 Keycloak Organizations。
- **不做（边界）**：不写各分系统业务逻辑；**租户开通不耦合 Git/Argo**（命令式）；它创建数据平面的隔离，业务由各分系统承担。
- **前端**：本仓只提供 API，**无自带前端**；其运营管理界面是「管理平面」，由 `webui` 的 `apps/admin`（跨租户单例、独立域名）承载，与租户使用平面 `apps/console` 严格分包隔离。

## 骨架技术选型（首选 · 待逐仓细化）

| 维度 | 选型 |
|--|--|
| 运行时 | Spring Boot（Java） |
| K8s 编排 | **Helm SDK/CLI 包装 + Kubernetes Java client**（命令式，不依赖 Git/Argo） |
| 身份 | **Keycloak Admin API**（Organizations 管理） |
| Secrets | **External Secrets Operator** |
| 租户目录 | PostgreSQL |

> 详细设计见主仓 `docs/architecture/05-多租户与控制平面.md`。

## 产品形态与多租户（北极星）

**双模交付**：公网 SaaS（我们运营 · 统一**我们品牌** · 租户=企业）／私有化部署（客户环境 · **客户品牌**部署级 · 租户=客户部门）。品牌**部署级**、不按租户运行期换肤。多租户走 **C 分层桥接**：控制平面共享 + 数据平面按租户隔离（Keycloak Organizations 单 realm · schema/db-per-tenant · namespace-per-tenant），由 `control-plane` 编排开通。

**本仓视角**：多租户**中枢**——注册 / 开通 / 生命周期 / 配额，编排租户隔离资源 + Keycloak Organizations。

> 详见主仓 `docs/00-主仓初始化-spec.md`、`docs/architecture/05-多租户与控制平面.md`。

## 工程现状（已落地骨架）

> 对应 GitHub Issue #1 首个增量：**可构建可测的纵切片**——工程基座 + 租户目录/状态机 + 开通编排（端口/适配器 + stub）。真实 Keycloak/Helm/K8s/Doris/ESO 适配器按 issue 路线图后续接入。

**技术栈**：Java 17 · Spring Boot 3.3.5（经主仓 `hashmatrix-bom` 钉死）· Spring Data JPA + PostgreSQL + Flyway · 复用 `starter-tenant`/`starter-web`/`starter-audit`/`starter-observability`/`starter-test`。

**目录结构**：

```
src/main/java/io/hashmatrix/controlplane/
├── ControlPlaneApplication.java
├── tenant/                      # 租户目录领域 + 持久化 + 生命周期服务 + REST
│   ├── domain/                  #   Tenant 聚合 · TenantStatus 状态机 · TenantQuota
│   ├── repo/  service/  web/    #   仓储 · 生命周期编排服务 · 控制器/DTO/异常映射
└── provisioning/                # 开通编排
    ├── spi/                     #   端口：Identity/Compute/Data/Secrets Provisioner
    ├── stub/                    #   stub 适配器（默认，日志/确定性返回，无需活集群）
    ├── metering/                #   计量端口（仅预留接口）
    └── ProvisioningOrchestrator #   按 ①身份→②计算→③数据→④secrets 时序驱动
src/main/resources/
├── application.yml application-local.yml
└── db/migration/V1__init_tenant_catalog.sql   # Flyway
```

## 构建与运行

**只 clone 本仓即可构建**（依赖经 Maven 坐标从制品仓解析，接入见主仓 [`libs-java/README`](https://github.com/HashMatrixData/hashmatrix/blob/main/libs-java/README.md)）：

```bash
mvn -q -DskipTests package        # 产出可执行 fat-jar：target/control-plane-*.jar
```

> ⚠️ **依赖前置**：本仓 `import hashmatrix-bom` 已 pin 到 **v0.2.0** 并复用 `starter-audit`/`starter-observability`（对齐 issue #1 评论3）。该版本与两个 starter 须先由 libs-java 发布（或本地 `mvn install`）方可解析——在 v0.2.0 落地前 `mvn package` 会因解析不到 BOM 0.2.0 而失败，属预期。

**本地独立运行/调试**（依赖 PostgreSQL + Keycloak）：

```bash
docker compose -f docker-compose.local.yml up -d           # 起依赖（本地脱敏占位口令）
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run           # 起服务
curl localhost:8080/actuator/health                        # 健康探针
```

> K8s 不在本地栈内：本地用 kind 或默认 `provisioning.mode=stub` 跑通开通时序。

## API 速览

控制平面**只提供 API**（运营 UI 由 webui `apps/admin` 承载）；统一返回 `ApiResponse`（starter-web）。

| 方法 | 路径 | 说明 |
|--|--|--|
| POST | `/api/v1/tenants` | 自助注册（→ REGISTERED） |
| GET | `/api/v1/tenants` · `/{id}` | 列表 / 详情 |
| POST | `/api/v1/tenants/{id}/approve` | 审批通过 → 开通 → ACTIVE |
| POST | `/api/v1/tenants/{id}/reject` | 审批驳回 → REGISTERED |
| POST | `/api/v1/tenants/{id}/suspend` · `/resume` | 挂起 / 恢复 |
| DELETE | `/api/v1/tenants/{id}` | 注销（尽力回收 → DELETED） |

**状态机**：`registered → approving → provisioning → active → suspended → deleted`（开通失败回退 `provisioning → approving`；审批驳回回退 `approving → registered`）。

## 开通编排（端口/适配器）

开通经四个端口按架构 05 §4 时序解耦：`IdentityProvisioner`（Keycloak Organization）→ `ComputeProvisioner`（Helm+K8s：namespace/quota/netpol/服务实例）→ `DataProvisioner`（schema·db + Doris/Paimon catalog）→ `SecretsProvisioner`（ESO）。默认装配 **stub 适配器**（`hashmatrix.control-plane.provisioning.mode=stub`），无活集群即可端到端跑通；真实适配器以 `@ConditionalOnMissingBean` 优先替换。计量（metering）**仅预留接口**，政企合同制不按量计费。

## 测试

```bash
mvn test                          # 单元测试（状态机 / 编排器 / 生命周期服务，无外部依赖）
mvn verify                        # 含 Testcontainers 集成测试（需 Docker；端到端 provision tenant-demo）
```

> 集成测试复用 `starter-test`（JUnit5 + Testcontainers）起真实 PostgreSQL，覆盖「注册 → 审批 → 开通 → ACTIVE」全链路；mock 数据脱敏（`tenant-demo` / `@example.com`）。非 Docker Desktop（如 colima/rootless）需配置 `~/.testcontainers.properties` 的 `docker.host`。

## 路线图（issue #1 后续）

- **API 鉴权**：当前端点**未接入认证/鉴权**，仅供 stub/本地与集成测试；上线前接入 Keycloak Bearer 鉴权 + 角色门控（`approve`/`delete` 限平台管理员）。
- 真实 **Keycloak Admin API** 适配器（建 Organization + 管理员 + 可选联邦 AD）。
- 真实 **Helm SDK + Kubernetes client** 适配器（per-tenant release：namespace/ResourceQuota/NetworkPolicy/服务实例）。
- 真实**数据开通**（PG schema/db + Doris/Paimon catalog）与 **ESO** secrets 注入。
- 开通改**异步任务 + 状态轮询**（长耗时适配器）；测试集群上端到端 provision 验收。

## 说明

本仓库作为 `hashmatrix` 主仓的 git submodule，挂载于 `services/control-plane`。架构背景见主仓 `docs/architecture/`（尤见 [`05-多租户与控制平面`](https://github.com/HashMatrixData/hashmatrix/blob/main/docs/architecture/05-多租户与控制平面.md)）。

## License

Apache-2.0
