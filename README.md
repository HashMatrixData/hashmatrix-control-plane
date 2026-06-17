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

## 说明

本仓库作为 `hashmatrix` 主仓的 git submodule，挂载于 `services/control-plane`。架构背景见主仓 `docs/architecture/`（尤见 [`05-多租户与控制平面`](https://github.com/HashMatrixData/hashmatrix/blob/main/docs/architecture/05-多租户与控制平面.md)）。

## License

Apache-2.0
