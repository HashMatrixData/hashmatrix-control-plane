# CLAUDE.md — hashmatrix-control-plane 协作与合规指引

本文件为 Claude Code 及所有协作者在本仓库工作的**强制约束**。违反「信息红线」的内容一律不得提交。

## 🔴 信息红线（强制 · 不可协商）

本仓库为**公开开源仓库**。所有内容（代码、注释、文档、配置样例、提交信息、Issue/PR、分支与标签名）必须满足：

1. **禁止出现任何甲方/客户可识别信息**，包括但不限于：真实单位名称/简称/品牌、人员姓名或账号、招标/合同/立项编号、内部项目代号、甲方专有业务术语、真实数据、具体部署地点、客户网络或系统拓扑。
2. **禁止透漏任何项目机密**：商务/合同条款、里程碑与报价、验收细节、甲方环境参数、真实业务数据样本。
3. **仅允许记录可面向大众公开的内容**：通用技术方案、代码实现、系统架构与产品决策、开源组件选型、通用工程最佳实践。
4. **示例/测试数据一律虚构脱敏**，使用通用占位（如 `example.com`、`acme`、`tenant-demo`），严禁使用任何真实甲方数据。
5. **敏感原始资料一律置于 `.gitignore`、不得入库**（仅本地留存）。

> 判定标准：把本仓任意文件公开到互联网，不会泄露任何客户身份或项目机密。不确定时一律按「不写入」处理。

## 提交前自检（每次 commit / PR 必过）

- [ ] 无甲方名称 / 编号 / 代号 / 人员 / 地点等可识别信息
- [ ] 无商务 / 合同 / 验收 / 报价等项目机密
- [ ] 示例数据均为虚构 / 脱敏
- [ ] 敏感原始资料未入库（已在 `.gitignore`）
- [ ] 提交信息与分支/标签名同样不含上述敏感信息

## 🧭 北极星：产品形态与多租户模式（开发者时刻谨记）

本平台**双模交付**，所有设计与代码都须按此模式思考：

| | 公网 SaaS | 私有化部署 |
|--|--|--|
| 运营 / 品牌 | 我们运营 · **我们公司统一品牌** | 客户环境 · **客户品牌（部署级）** |
| 租户 = | 企业客户 | 客户的部门 |

- **品牌是部署级**（部署期配置注入），**不按租户在运行期动态换肤**。
- **多租户隔离（C 分层桥接）**：控制平面共享 + 数据平面按租户隔离。身份 = Keycloak **Organizations 单 realm**（org=租户，JWT 带 tenant 声明）；数据 = **schema/db-per-tenant**；计算 = **namespace-per-tenant**；由 `control-plane` 编排开通。

**本仓视角（control-plane）**：**本仓就是多租户的中枢**——承载租户注册 / 开通 / 生命周期 / 配额，经 Helm SDK + Kubernetes client 命令式开通租户的 namespace / schema·db / 服务实例 / secrets，并管理 Keycloak Organizations。其它仓「按租户隔离」，本仓「创建并治理这些隔离」。

> 全局定义见主仓 `docs/00-主仓初始化-spec.md` 与 `docs/architecture/05-多租户与控制平面.md`。

## 🔗 契约（Contracts）—— 跨子系统集成

本项目经**契约**与其它子系统集成。契约的**单一事实源在主仓** `HashMatrixData/hashmatrix` 的 `contracts/`：
- 索引（机器可读）`contracts/registry.yaml` · 规范 `contracts/CONVENTIONS.md` · 设计 `docs/architecture/06-契约治理.md`
- 在线：https://github.com/HashMatrixData/hashmatrix/tree/main/contracts

**铁律**：先改契约、再改实现；加法兼容默认放行，破坏性走 MAJOR + 弃用期双跑 + 通知消费方；消费方一律 tolerant reader。

**本仓契约**：
- producer：`openapi/control-plane-v1`（北向 API：租户注册/审批/开通状态/生命周期/配额查询 + `/v1/me/tenants`）、`icd/control-plane-provisioning`（租户目录状态机 + 外呼开通边界：Keycloak/Helm/datastore/ESO，仅契约）。
- consumer：`icd/tenant-context-headers`

> ✅ **契约↔实现结构性漂移已 reconcile（#9，契约 `control-plane-v1` v1.2.0）**：原三条 known-drift 均按「先改契约」铁律对齐，已清账——
> 1. **审批端点形态**：实现已收敛为契约单端点 `POST /v1/tenants/{tenantId}/approval`（`decision: approve|reject`，`reject→deleted` 终态、reason 必填）；原 `/approve`+`/reject` 双端点（`reject→registered`）已移除。
> 2. **`/api` 前缀约定**：契约 `info.description` 已写明「网关 strip 部署级 `/api`，对外为 `/v1/...`」；实现保持应用内 `/api/v1/...`。
> 3. **`Tenant`/`TenantView` 结构**：已全面对齐——单租户端点改 `{tenantId}` 路由键寻址（内部 UUID 不出边界）；`organization{orgId,orgAlias}`/`dataPlane{namespace,dbSchema,dorisCatalog,helmRelease}` 嵌套；quota 改 `maxStorageGi`/`maxConcurrentJobs`+`compute`（领域+DB 迁移 V2，字节→GiB）；`deliveryMode` 上收为部署级配置（不入注册体/视图）、`id`/`adminEmail` 不出视图；`statusReason` 已纳入契约 `Tenant`（加法）；注册体改 `requestedQuota`。webui 消费方侧字段对接经 cross-ask 协同。

> ✅ **契约已声明端点已补齐（#11）**：原「契约声明、实现缺失」三处已落地——`GET /v1/tenants` 加 `?status` 服务端过滤 + `page`/`pageSize` 分页（→ `TenantList`，取代裸数组 + 前端客户端过滤）；`GET /v1/tenants/{tenantId}/quota`（→ `QuotaStatus`）；`GET /v1/tenants/{tenantId}/provisioning`（→ `ProvisioningStatus`）。**M1 仅落子集 known-drift（已登记）**：① `QuotaStatus.usage` 为 no-op（合同制不按量计费、`MeteringPort` 仅写不读）→ 各字段省略（`{}`）表「未计量」；② `ProvisioningStatus` 由租户生命周期状态**派生**（M1 同步开通、未分步落库），失败步由 `statusReason` 定位、失败 message 已脱敏；注销（deprovision）回收进度派生为后置（`deleted` 暂统一 `succeeded`）。接入真实计量 / 异步开通后于原契约形态内补全，消费方 tolerant reader 零改动。

**如何查阅（随时拉最新，勿存本地副本）**：
- 在 superproject（`hashmatrix/services/<本仓>`）下：直接读 `../../contracts/`。
- 独立 clone：WebFetch `https://raw.githubusercontent.com/HashMatrixData/hashmatrix/main/contracts/registry.yaml`（公开仓免鉴权）→ 按 registry 取对应契约；或 `gh api repos/HashMatrixData/hashmatrix/contents/contracts/<path> -H "Accept: application/vnd.github.raw"`。

## 仓库定位

多租户**控制平面**：租户注册 / 开通（provision）/ 生命周期 / 配额 / 租户目录。经 Helm SDK + Kubernetes client 命令式编排开通租户资源；身份对接 Keycloak Organizations。

**技术栈（已落地骨架）**：Java 17 · Spring Boot 3.3.5（经主仓 `hashmatrix-bom` 钉死）· Spring Data JPA + PostgreSQL + Flyway · 复用 `starter-tenant`/`starter-web`/`starter-audit`/`starter-observability`/`starter-test`。开通编排以**端口/适配器**解耦：`IdentityProvisioner`（Keycloak）/`ComputeProvisioner`（Helm+K8s）/`DataProvisioner`（schema·db + Doris/Paimon）/`SecretsProvisioner`（ESO），默认装配 **stub 适配器**（`provisioning.mode=stub`）以无活集群跑通时序；真实适配器按 issue #1 路线图逐步接入。详见 `README.md`。
