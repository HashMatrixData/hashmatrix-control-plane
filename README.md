# hashmatrix-control-plane

> hashmatrix 数据中台子模块 · 所属：控制平面
>
> 主仓：[HashMatrixData/hashmatrix](https://github.com/HashMatrixData/hashmatrix)

## 产品形态与多租户（北极星）

**双模交付**：公网 SaaS（我们运营 · 统一**我们品牌** · 租户=企业）／私有化部署（客户环境 · **客户品牌**部署级 · 租户=客户部门）。品牌**部署级**、不按租户运行期换肤。多租户走 **C 分层桥接**：控制平面共享 + 数据平面按租户隔离（Keycloak Organizations 单 realm · schema/db-per-tenant · namespace-per-tenant），由 `control-plane` 编排开通。

**本仓视角**：多租户**中枢**——注册 / 开通 / 生命周期 / 配额，编排租户隔离资源 + Keycloak Organizations。

> 详见主仓 `docs/00-主仓初始化-spec.md`、`docs/architecture/05-多租户与控制平面.md`。

## 职责

多租户**控制平面**：租户注册、开通（provision）、生命周期、配额与租户目录。经 **Helm SDK + Kubernetes client 命令式编排**开通租户资源（namespace / schema·db / 服务实例 / secrets），生产期不依赖 Git/Argo。

## 技术栈

Java（Spring Boot）（**具体技术选型待独立讨论，逐步丰富**）

## 说明

本仓库作为 `hashmatrix` 主仓的 git submodule，挂载于 `services/control-plane`。架构背景见主仓 `docs/architecture/`（尤见 [`05-多租户与控制平面`](https://github.com/HashMatrixData/hashmatrix/blob/main/docs/architecture/05-多租户与控制平面.md)）。

## License

Apache-2.0
