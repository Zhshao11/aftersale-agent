<div align="center">

# AfterSale Agent

**让 LLM 安全地执行高风险写操作 —— 一个 Plan-and-Execute 售后订单智能体**

写工具对 LLM 物理不可见 · 写操作必经用户确认 · 上下文指纹防 TOCTOU · 超时结果二分 · 幂等执行

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1-blue.svg)](https://spring.io/projects/spring-ai)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1.svg)](https://www.mysql.com/)
[![Tests](https://img.shields.io/badge/tests-39%20passed-success.svg)](#-测试)
[![τ-bench](https://img.shields.io/badge/τ--bench-80%25-blueviolet.svg)](#-评测)
[![License](https://img.shields.io/badge/license-MIT-lightgrey.svg)](LICENSE)

<img src="docs/pipeline-overview.jpg" alt="售后订单智能处理 Agent 全链路：用户提问 → 意图路由 → LLM 规划与确定性校验 → 受控执行 → 订单终态" width="100%">

</div>

---

## 📕 目录

- [项目简介](#-项目简介)                [核心特性](#-核心特性)
- [安全模型](#-安全模型)                [系统架构](#-系统架构)
- [快速开始](#-快速开始)                [API 参考](#-api-参考)
- [评测](#-评测)                       [配置项](#-配置项)
- [项目结构](#-项目结构)
- [Roadmap](#-roadmap)                [测试](#-测试)
- [License](#-license)

---

## ✨ 项目简介

电商售后是 LLM Agent 的**高风险落地场景**：用户一句"帮我退掉"，背后可能是资金回流、库存变更、客诉记录。让 LLM 直接调用写工具，等于把资金操作交给一个会幻觉的模型——一旦误执行，代价不可逆。

**AfterSale Agent** 提供一套可参考的工程方案，把"LLM 执行写操作"从**提示词约束**升级为**架构约束**：

> LLM 在高风险写操作上**够不着工具**，也**说了不算**——它只能提出计划，执行权始终握在确定性代码与用户手里。

项目基于 OpenAI 兼容协议接入大模型，可对接任意兼容端点（Qwen / GLM / MiniMax / OpenAI 等），业务侧零改造。配套 36 例消融评测与 [τ-bench](https://github.com/sierra-research/tau-bench) 外部基准验证。

---

## 🌟 核心特性

### 🧭 意图路由与查询问答

- LLM 将用户输入分类为 `QUERY` / `WRITE`，解析失败**保守归 QUERY**（无副作用一侧）
- 查询走只读 ReAct：订单详情、物流状态、售后政策即问即答

### 📋 Plan-and-Execute 执行范式

- 写操作先由 LLM 生成**结构化计划**，经**白名单 / 归属 / 政策**三重确定性校验后落库
- 校验逻辑是纯代码，不依赖 LLM 自觉——工具名合法的同时，订单必须属于该用户、当前状态必须允许该操作
- 计划经用户确认后才进入执行器，未确认的计划无法执行

### 🔒 构造性安全（核心设计）

| 机制 | 说明 |
|---|---|
| **写工具物理隔离** | LLM 的工具集**永远只有只读工具**；写工具注册在执行器侧，LLM 任何路径都拿不到 |
| **用户确认门** | `PENDING_CONFIRM` →（≥$500）`AWAITING_SECOND_CONFIRM` → `CONFIRMED`，状态机驱动 |
| **上下文指纹失效** | 计划生成时记录订单状态+金额+步骤的 SHA-256 指纹，确认时重算；任何条件变化 → 旧计划强制 `EXPIRED`（防 TOCTOU） |
| **越权双层拦截** | 订单归属校验在规划前置 + 执行前置各做一次，LLM 改写订单号也无法绕过 |
| **大额二次确认** | 金额 ≥ $500 必须二次确认，阈值可配置 |

### ⏱️ 故障恢复

| 机制 | 说明 |
|---|---|
| **超时结果二分** | 明确失败（`FAIL_BEFORE_SEND`）→ 安全重试 ≤3 次；**结果未知**（`TIMEOUT_UNKNOWN`）→ 禁止盲目重试，转对账 + 待补偿 |
| **幂等执行** | 幂等键 `planId:stepId:attempt` 唯一索引，原生 `INSERT` 抢占；重复执行回放既有结果而非重复写库 |
| **断点续跑** | `execution_log` 每步落账，`/api/executor/resume` 恢复中断计划 |
| **业务拒绝不重试** | 政策拒绝等终态失败（`FAILED_FINAL`）与瞬态失败严格区分 |

### 🧪 可评测性

- 内置故障注入开关，可在执行链路注入明确失败 / 结果未知，验证恢复行为
- 36 例消融评测集 + 官方 τ-bench 接入脚本，评测可复现

---

## 🛡️ 安全模型

传统方案依赖提示词告诉 LLM"写之前要确认"，但提示词可被绕过、可被幻觉违反。本项目的安全是**构造性**的：

<img src="docs/permission-boundary.svg" alt="LLM 可见域与执行域的权限边界" width="680">

**三条硬约束**：

1. LLM 的工具集**不包含**任何写工具 —— 不是"不允许调用"，是"不存在";
2. 未确认的计划**无法**进入执行器 —— 状态机层面禁止;
3. 计划确认后若订单状态/金额变化，**旧计划立即失效** —— 防止用户确认的条件已被篡改。

---

## 🏗️ 系统架构

```
                    ┌──────────────────────────────────────────────┐
                    │                   /api/chat                   │
                    │              AgentOrchestrator               │
                    └───────────────┬──────────────┬───────────────┘
                                    │              │
                      意图路由(LLM)  │              │
                    QUERY ──────────┤              ├──────── WRITE
                                    ▼              ▼
                    ┌──────────────────┐   ┌─────────────────────┐
                    │   QueryAgent     │   │     PlanService      │
                    │  (只读 ReAct)    │   │  LLM → 结构化 Plan    │
                    │  getOrder        │   │  白名单/归属/政策校验  │
                    │  getLogistics    │   │  → 落库 PENDING       │
                    │  getPolicy       │   └──────────┬──────────┘
                    └──────────────────┘              │ 用户确认
                                                      ▼
                                            ┌─────────────────────┐
                                            │     PlanManager      │
                                            │  状态机 + 指纹失效    │
                                            │  ≥$500 二次确认门     │
                                            └──────────┬──────────┘
                                                       │ CONFIRMED
                                                       ▼
                                            ┌─────────────────────┐
                                            │       Executor       │
                                            │  幂等键 / 超时二分    │
                                            │  execution_log 续跑  │
                                            │  写工具注册表（隔离）  │
                                            └─────────────────────┘
```

**数据模型**：`orders` · `conversations` · `conversation_messages` · `plans` · `plan_steps` · `execution_log` · `idempotency_keys` · `policy_rules`

**技术栈**

| 层级 | 选型 |
|---|---|
| 语言 / 运行时 | Java 17 |
| 应用框架 | Spring Boot 3.5 |
| LLM 接入 | Spring AI 1.1（OpenAI 兼容协议） |
| 数据库 | MySQL 8 |
| 前端 | 单页 `index.html`（原生 JS，零构建） |

---

## 🎮 快速开始

### 1. 启动 MySQL

```bash
docker run -d --name aftersale-mysql -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=root123 -e MYSQL_DATABASE=aftersale \
  -v $(pwd)/src/main/resources/schema.sql:/docker-entrypoint-initdb.d/schema.sql:ro \
  -v $(pwd)/src/main/resources/data.sql:/docker-entrypoint-initdb.d/data.sql:ro \
  mysql:8
```

首次启动会自动执行 `schema.sql`（建表）与 `data.sql`（种子数据）。

### 2. 配置 LLM

```bash
cp .env.example .env
```

编辑 `.env`（任意 OpenAI 兼容端点均可）：

```bash
LLM_API_KEY=your-api-key
LLM_BASE_URL=https://your-endpoint        # 注意：不带 /v1（Spring AI 自动拼接）
LLM_MODEL=your-model
```

### 3. 启动应用

```bash
export $(grep -v '^#' .env | xargs)
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

打开 **http://localhost:8081** 即可使用内置演示页：聊天区 → Plan 卡片 → 确认/拒绝/执行 → 状态刷新。

> 详细操作见 [USE_GUIDE.md](USE_GUIDE.md)。

---

## 🔌 API 参考

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/chat` | `{userId, message, conversationId?}` → 回复 + `planCard` |
| `GET` | `/api/plan/{id}?userId=` | 计划详情（含步骤） |
| `POST` | `/api/plan/{id}/confirm?userId=` | 确认计划（≥$500 需调两次） |
| `POST` | `/api/plan/{id}/reject?userId=` | 拒绝计划 |
| `POST` | `/api/plan/{id}/execute?userId=` | 执行已确认计划（幂等） |
| `POST` | `/api/executor/resume` | 断点续跑（重启后恢复中断计划） |

---

## 📊 评测

### 36 例消融评测

评测集覆盖：查询 8 · 取消 8 · 退换 8 · 异常超时 6 · 越权/大额高风险 6。三组配置**同模型同温度**，唯一变量是编排与确认门：

| 配置 | 消融变量 | 完成率 | 高风险拦截 |
|---|---|---|---|
| **V2 完整版** | — | **36/36（100%）** | 6/6 |
| **V1** | 去掉 Plan 级确认门 | 35/36（97.2%） | 5/6 |
| **V0 基线** | ReAct 直连（工具级确认） | 31/35（88.6%） | 5/6 |

**归因链**：

```
V0 (ReAct 直连)      88.6%  ── LLM 全权控制写工具与重试
   │  +Plan-and-Execute（写权限收回，计划经确定性校验）      +8.6pp
V1 (无确认门)         97.2%  ── "LLM 临场失误"被结构性消除
   │  +确认门 + 大额分级 + 指纹失效 + 断点续跑              +2.8pp
V2 (完整版)          100%   ── 36/36
```

**V2 相对基线提升 +11.4pp**，三配置在越权/高风险场景均实现 100% 拦截或正确拒绝，**未发生一次未经确认的高风险写操作**。

逐 case 归因见 [eval/EVAL_REPORT.md](eval/EVAL_REPORT.md)。

### τ-bench 外部基准

以 Sierra 官方 [τ-bench](https://github.com/sierra-research/tau-bench) 零售域作为外部验证：官方环境 + 官方工具 schema + 官方 tool-calling agent，仅替换决策模型，**end-state 匹配**判定。

| 基准 | 任务数 | 结果 | 参考锚点 |
|---|---|---|---|
| τ-bench retail（test 前 10 任务） | 10 | **8/10（80%）** | gpt-4o ≈ 82% |

两个基准互补：消融评测验证**编排框架的安全与可靠性**，τ-bench 验证**模型在规范基准下的工具调用能力**。

### 复现评测

```bash
cd eval
python3 run_eval.py --config V2 --split all --runs 1
```

---

## ⚙️ 配置项

| 配置 | 默认 | 说明 |
|---|---|---|
| `aftersale.confirm-threshold-cents` | `50000` | 二次确认金额阈值（美分），$500 |
| `aftersale.agent.disable-confirm-gate` | `false` | 消融：关闭确认门（V1） |
| `aftersale.agent.disable-idempotency` | `false` | 消融：关闭幂等键 |
| `aftersale.agent.disable-timeout-classify` | `false` | 消融：关闭超时二分 |
| `aftersale.agent.react-baseline-mode` | `false` | 消融：ReAct 基线模式（V0） |
| `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` | — | 环境变量注入，`.env` 不入库 |

---

## 📁 项目结构

```
aftersale-agent/
├── src/main/java/com/aftersale/
│   ├── api/            # REST 控制器（chat / plan / execute / selftest / baseline）
│   ├── agent/          # 编排、意图路由、规划、确认门、会话（LlmPort 可 stub 测试）
│   ├── executor/       # 幂等执行器、写工具注册表、故障注入
│   ├── tools/          # 6 个工具（3 读 3 写）、政策服务、统一错误码
│   ├── domain/         # JPA 实体
│   ├── enums/          # 订单状态 / 计划状态 / 执行状态 / 风险等级
│   └── repo/           # Spring Data JPA
├── src/main/resources/ # schema.sql / data.sql / application.yml / static/index.html
├── src/test/java/      # 验收测试（39 个，不依赖 LLM）
├── eval/               # 评测 harness（cases.json + run_eval.py + τ-bench 驱动 + 报告）
└── USE_GUIDE.md        # 使用与演示指南
```

---

## 🗺️ Roadmap

- [ ] 接入真实支付 / 物流系统的适配层
- [ ] 多租户与权限模型
- [ ] 计划执行的分布式锁与跨实例幂等
- [ ] 补偿任务调度（RECONCILE_FAILED 自动重试）
- [ ] 可观测性：执行链路 Trace + 指标看板
- [ ] 更多域的工具集（售后以外的交易场景）

---

## 🧪 测试

```bash
mvn test
```

39 个单元测试，覆盖 schema / 工具契约 / 政策矩阵 / 意图路由 / 状态机 / 指纹失效 / 幂等 / 超时二分 / 断点续跑，**不依赖真实 LLM**（通过 `LlmPort` 注入 stub）。

---

## 🤝 Contributing

欢迎 Issue 与 PR。提交前请确保 `mvn test` 全绿；涉及安全机制的改动请在 PR 描述中说明对确认门 / 幂等 / 超时二分的影响。

---

## 📄 License

[MIT](LICENSE)
