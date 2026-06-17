# hashmatrix-control-plane

> hashmatrix 数据中台子模块 · 所属：控制平面
>
> 主仓：[HashMatrixData/hashmatrix](https://github.com/HashMatrixData/hashmatrix)

## 职责

多租户**控制平面**：租户注册、开通（provision）、生命周期、配额与租户目录。经 **Helm SDK + Kubernetes client 命令式编排**开通租户资源（namespace / schema·db / 服务实例 / secrets），生产期不依赖 Git/Argo。

## 技术栈

Java（Spring Boot）（**具体技术选型待独立讨论，逐步丰富**）

## 说明

本仓库作为 `hashmatrix` 主仓的 git submodule，挂载于 `services/control-plane`。架构背景见主仓 `docs/architecture/`（尤见 [`05-多租户与控制平面`](https://github.com/HashMatrixData/hashmatrix/blob/main/docs/architecture/05-多租户与控制平面.md)）。

## License

Apache-2.0
