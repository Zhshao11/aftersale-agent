# 售后订单智能处理 Agent —— 项目总览

> 本文是**单一汇总入口**，按「背景 → 系统介绍 → 设计主要考虑点 → 核心卖点 → 已完成功能 → 待完善功能」六节组织。
> 所有事实均逐条核对到代码 / 库表 / 评测报告，**不采信既有文档自述**；文末附「与既有文档的不一致清单」。
>
> 配套文档分工：
> `README.md`（对外门面）· `TECH_SUMMARY.md`（机制取舍视角）· `TECH_JOURNAL.md`（过程日志，内部素材）·
> `docs/DESIGN.html`（设计说明书）· `docs/LATENCY_OPTIMIZATION.md`（时延讲点评估）·
> `eval/EVAL_REPORT.md`（评测报告）
>
> 核对基线：`2026-09-21` · 单测 54/54 全绿 · 43 例消融 + τ-bench 跑分

---

## 一、背景：为什么做这个

### 1.1 场景选择的理由

电商售后客服是 LLM Agent 落地的**高风险场景**：用户一句自然语言就要"退钱 / 换货 / 取消"，而一次错误操作的代价是**真实资金损失 + 客诉**。这个场景的价值在于它把 LLM 的两个致命弱点同时放大：

| 弱点 | 在售后场景的后果 |
|---|---|
| **幻觉** | 编造一个订单号、编造一个"已退款"的结论 |
| **不可靠** | 模型调用超时后的重试，会变成**重复退款** |

### 1.2 要回答的问题

> **怎么让一个会幻觉的模型安全地动钱？**

这个问题的形态决定了技术路线。绝大多数 Agent 演示在回答"能聊天"；本项目回答的是"**写操作如何被约束**"。因此本项目的技术重心**不在提示词工程**，而在：

- **如何让 LLM 够不着写工具**（不是禁止，是物理不存在）
- **如何让 LLM 说了不算**（LLM 输出是"建议"，每一步都有确定性代码把关）
- **如何在模型失联时仍知道"钱动没动"**（结果未知 ≠ 失败）

### 1.3 与"常规方案"的分野

| 维度 | 常规做法 | 本项目做法 |
|---|---|---|
| 写权限约束 | 提示词声明"写之前要确认" | **写工具不注册进任何 ChatClient**（构造性安全） |
| 失败处理 | 抛异常 → 重试 | **失败三分类**：`FAILED`（可重试）/ `FAILED_FINAL`（重试无意义）/ `UNKNOWN`（禁止盲目重试） |
| 确认机制 | 工具级弹窗 | **Plan 级事前确认 + 状态机 + 上下文指纹（防 TOCTOU）** |
| 重复执行防护 | 业务侧判断 | **幂等键抢占数据库唯一索引**，与业务写同事务提交 |
| 控制环归属 | 交给 Agent 框架托管 | **自建控制环**，状态机 / 幂等 / 恢复是一等公民 |

---

## 二、系统介绍

### 2.1 技术栈（`pom.xml` 逐字核对）

| 层级 | 选型 |
|---|---|
| 语言 / 运行时 | Java 17 |
| 应用框架 | Spring Boot **3.5.16**（`spring-boot-starter-parent`） |
| LLM 接入 | Spring AI **1.1.8**（`spring-ai-starter-model-openai`，OpenAI 兼容协议） |
| 数据库 | MySQL 8（`mysql-connector-j`） |
| 其他 starter | `web` / `data-jpa` / `validation` / `actuator` / `test` |
| 前端 | 单页 `index.html`（**471 行**原生 JS，零构建） |
| 坐标 | `com.aftersale:aftersale-agent:0.1.0-SNAPSHOT` |

### 2.2 代码规模（实测）

| 项 | 数量 |
|---|---|
| 主源码 Java 文件 | **65**（`src/main/java`） |
| 测试 Java 文件 | **6**（`src/test/java`） |
| 主源码行数 | **4510** 行 |
| 最大文件 | `ReadLoop.java` 605 · `PlanService.java` 381 · `StepRunner.java` 275 · `AgentOrchestrator.java` 209 |

包结构：`api` · `agent` · `config` · `domain` · `enums` · `executor` · `repo` · `tools` · `tools/read` · `tools/write`

### 2.3 数据模型（`schema.sql` 实测 **9 张表**）

| 表 | 职责 | 关键索引 |
|---|---|---|
| `orders` | 订单主数据 | `UNIQUE uk_order_no` · `KEY idx_user` |
| `conversations` / `conversation_messages` | 会话与历史 | — |
| `plans` | 计划主表（含状态机、指纹） | — |
| `plan_steps` | 计划步骤 | `UNIQUE uk_plan_seq(plan_id, seq)` |
| `execution_log` | 每步每 attempt 落账 | — |
| `idempotency_keys` | 幂等键 | `idempotency_key VARCHAR(128) PRIMARY KEY`（值为 `planId:stepId:attempt`） |
| `policy_rules` | 政策规则表 | `UNIQUE uk_rule_code` |
| `agent_trace` | 链路轨迹（只追加） | `KEY idx_trace(trace_id, step_index)` · `idx_conv` · `idx_created` |

**政策规则**（`data.sql` 逐字）：

| rule_code | scope | 允许状态 | 时限 |
|---|---|---|---|
| `CANCEL_ONLY_BEFORE_SHIP` | CANCEL | `PAID` | — |
| `REFUND_WITHIN_7D` | REFUND | `DELIVERED` | 7 天 |
| `EXCHANGE_WITHIN_15D` | EXCHANGE | `DELIVERED` | 15 天 |

### 2.4 运行面

- **端口**：`8081`（`application.yml` 的 `server.port`）
- **前端**：`http://localhost:8081` 单页 —— 聊天区 → Plan 卡片 → 确认 / 拒绝 / 执行 → 轨迹轮询 → 秒表
- **LLM 配置**：`spring.ai.openai` base-url 默认 `https://dashscope.aliyuncs.com/compatible-mode`，model `${LLM_MODEL:qwen-plus}`，`temperature: 0.1`

### 2.5 REST 接口（**9 条 + 自测端点**，逐字）

| 方法 | 路径 | 说明 | 控制器 |
|---|---|---|---|
| `POST` | `/api/chat` | `{userId, message, conversationId?, traceId?}` → 回复 + `planCard` + `trace` + `refusalCode` | `ChatController` |
| `GET` | `/api/plan/{id}` | 计划详情（含步骤） | `PlanController` |
| `POST` | `/api/plan/{id}/confirm` | 确认计划（≥$500 需调两次） | `PlanController` |
| `POST` | `/api/plan/{id}/reject` | 拒绝计划 | `PlanController` |
| `POST` | `/api/plan/{id}/execute` | 执行已确认计划（幂等） | `ExecuteController` |
| `POST` | `/api/executor/resume` | 断点续跑（重启后恢复中断计划） | `ExecuteController` |
| `POST` | `/api/fault` | 故障注入（**仅评测**）：`FAIL_BEFORE_SEND` / `TIMEOUT_UNKNOWN` / `HANG` | `ExecuteController` |
| `GET` | `/api/trace/{traceId}` | 轨迹回放，**请求进行中即可轮询** | `TraceController` |
| `POST` | `/api/baseline/confirm` | V0 基线模式的确认入口 | `BaselineConfirmController` |
| `GET` | `/api/selftest` | 工具层全路径自测（**有副作用**，靠强制回滚兜底） | `SelfTestController` |

### 2.6 三种可切换配置（消融用）

V0 / V1 / V2 是**应用启动参数**，不是 harness 开关：

| 配置 | 编排 | 确认机制 | 启动参数 |
|---|---|---|---|
| **V0 基线** | 单环 ReAct，读写工具全挂载 | 工具级确认对话框 | `--aftersale.agent.react-baseline-mode=true` |
| **V1** | Plan-and-Execute | 无确认门（生成即执行） | `--aftersale.agent.disable-confirm-gate=true` |
| **V2 完整版** | Plan-and-Execute | Plan 级事前确认 + ≥$500 二次确认 | （默认值） |

---

## 三、系统设计的主要考虑点

> 这一节是项目的技术重心。每条都给出**为什么这么选**，以及**被否掉的备选**。

### 3.1 工具集隔离 —— 安全不变式

**不变式逐字**（`AgentOrchestrator` 类注释）：

> 无论路由结果如何，LLM 能接触到的工具集永远是 ReadToolBundle；
> 写操作只能通过规划路径产生 Plan，经用户确认后由 Executor 执行（D3/D4）。

实现上是**两个物理分离的注册表**：

```
ReadToolBundle   → 4 个只读工具：listMyOrders / getOrder / getLogistics / getPolicy
                   （唯一挂进 ChatClient 的工具集）
WriteToolRegistry → 3 个写工具：cancelOrder / refundOrder / exchangeOrder
                   （只被 Executor 引用，LLM 不可见）
```

**为什么不用"提示词禁止调用"**：提示词可被绕过、可被幻觉违反。这里的安全是**编译期/架构级保证**——写工具根本没有出现在 LLM 的 tool 列表里，"调用"这个动作在协议层面不存在。

**附带收益**：由于安全不依赖意图路由，所以**把意图路由降级成本地零成本分类器是安全的**（见 §5.3）。

### 3.2 Plan-and-Execute + 三重前置校验

写诉求的完整链路（每一步都是确定性代码）：

```
用户消息
  → 确定性正则提取订单号（ORD\d+）
  → 查订单：不存在 → 提示用户提供订单号
  → 归属校验：order.owner != userId → 拒绝（Plan 都不生成）
  → LLM 生成结构化 Plan {summary, orderNo, steps[tool, reason]}
  → ① 工具白名单校验（非 cancel/refund/exchange 整份拒绝）
  → ② 订单号二次校验（LLM 可能改写订单号，以 LLM 输出为准再查一遍）
  → ③ 政策前置评估（policy_rules 表）
  → 全部通过 → 落库 PENDING_CONFIRM
```

**关键点**：LLM 输出是**建议**不是**命令**。每一步都有确定性代码把关，LLM 幻觉或改写订单号无法逃逸。

**拒绝原因五分类**（`RefusalCode`，逐字）：

```
ORDER_NOT_LOCATED  用户消息与对话历史里都定位不到订单
ORDER_NOT_FOUND    订单号存在但库中查无此单
FORBIDDEN          订单不属于当前用户
POLICY_DENIED      售后政策拒绝（时间窗口 / 状态不符）
PLAN_PARSE_FAILED  规划器输出无法解析（JSON 非法 / 缺字段 / 非法工具）
```

**为什么不复用 `ToolErrorCode`**（枚举 javadoc 逐字）：那个是「工具契约的一部分」，会和 LLM 工具描述一起对外；这个是「编排层的失败分类」，**不该出现在模型可见的契约里——否则等于告诉模型有哪些失败模式可探测**。

**为什么保留 `refusalMessage` 的同时加 `refusalCode`**："文案要能改，分类要稳定"。原先四种完全不同的失败在用户侧长得一模一样（都是一句「抱歉，计划生成失败」），**在排障与评测上都是致命的**：无法归因，也无法统计"哪一类失败占多数"。

**拒绝的实际发生顺序**（`PlanService` 实测，不是一条线性优先级列表，而是**两段式**）：

```
快路径（用户报了订单号）
  ORDER_NOT_FOUND(不存在)  →  FORBIDDEN(越权)
慢路径（用户没报订单号，先走只读侧 listMyOrders 定位）
  ORDER_NOT_LOCATED(定位不到)  →  PLAN_PARSE_FAILED(LLM 输出非法)
  →  ORDER_NOT_FOUND(LLM 改写的订单号查无此单)  →  FORBIDDEN  →  POLICY_DENIED
```

两段式都是**先判存在性、再判归属**（因为归属校验的前提是先拿到订单对象）。这个顺序有安全含义：慢路径下订单号是 **LLM 从"用户自己的订单列表"里选出来的**，若 LLM 把它改写成他人订单号，代码会先报 `ORDER_NOT_FOUND`（不存在）而不是 `FORBIDDEN`（不属于）——**前者不确认该订单的存在性**。这与 §4.5 中 Q7 的口径缺陷正好呼应：项目在代码里做对了这件事，反而被一个偏向"无权"措辞的判分词表扣了分。

### 3.3 确认门与状态机

```
PENDING_CONFIRM ──confirm──▶ AWAITING_SECOND_CONFIRM（金额 ≥ $500）──confirm──▶ CONFIRMED
       └──────────confirm──▶ CONFIRMED（< $500）
任意时刻 ──reject──▶ CLOSED
```

- **阈值**：`aftersale.confirm-threshold-cents: 50000`（美分，$500）
- **终态四类**：`COMPLETED` / `FAILED` / `CLOSED`（用户拒绝）/ `EXPIRED`（指纹失效）
- **状态全集**（`PlanStatus`）：`PENDING_CONFIRM, AWAITING_SECOND_CONFIRM, CONFIRMED, EXECUTING, COMPLETED, FAILED, CLOSED, EXPIRED`
- **执行前置检查**：`Executor` 只接受 `CONFIRMED | EXECUTING`，未确认的计划在**状态机层面**无法执行

**一个设计缺口被评测抓出来**：最初 `estimatedAmountCents` 只对 `refundOrder` 生效。但**取消已支付订单 = 全额退款 = 资金回流**，同样该参与大额二次确认。教训：**按资金流向定义风险，不按工具名**。

### 3.4 上下文指纹（TOCTOU 防护）

```
Plan 生成时：fingerprint = SHA-256(orderNo | status | amountCents | stepsCanonical)
用户确认时：重算当前指纹，不一致 → 旧 Plan 强制 EXPIRED
```

**防的是什么**：用户生成取消计划后，订单发货了（`PAID → SHIPPED`）——按政策已不能取消。若无指纹，确认后执行会**绕过政策**；有指纹，**确认即失效**，必须重新规划。

这是一个经典的 TOCTOU（Time-of-Check to Time-of-Use）问题：**校验时的世界 ≠ 使用时的世界**，所以要给"被确认的那个世界"算个指纹。

### 3.5 超时二分与失败三分类（可靠性的核心）

**失败不是一个状态。** 一次尝试的完整结局有三类，处理策略完全不同：

| 情形 | 判定 | 策略 |
|---|---|---|
| 瞬态失败（工具抛异常 / 上游限流，**请求未送达**） | `FAILED` | 可安全重试：换新 attempt，上限 **3** 次 |
| 业务拒绝（政策不允许、归属不符等**确定性**拒绝） | `FAILED_FINAL` | **立即终止，重试无意义** |
| 结果未知（**请求已发出但无响应**） | `UNKNOWN` | **禁止盲目重试** → 对账 |

**为什么要单列 `FAILED_FINAL`**：政策拒绝重试一百次也不会通过。若和瞬态失败混为一谈去重试，不仅白白消耗尝试次数，还会在日志里制造"系统正在重试"的假象，**掩盖真实原因**。

**对账逻辑**（`UNKNOWN` 的归宿）：

```
订单已达期望终态 → 补记 SUCCESS
订单未达期望终态 → 记 RECONCILE_FAILED，转人工/补偿
```

**故障注入两种形态**（`FaultInjector`）：`FAIL_BEFORE_SEND`（明确失败）vs `TIMEOUT_UNKNOWN`（结果未知）——这是"超时二分"。

### 3.6 幂等键与刻意下沉的事务边界

**幂等键**：`planId:stepId:attempt`，落 `idempotency_keys` 表（`VARCHAR(128) PRIMARY KEY`）。

**关键实现细节**：抢占必须用**原生 INSERT 直击数据库唯一索引**。JPA 的 `save()` 对同主键走 `merge`，**不触发约束**——用 `save()` 会导致幂等失效。

**事务边界刻意下沉**（`Executor` 类注释逐字）：

> 本类**刻意不加 `@Transactional`**。事务边界在 `PlanStateWriter`（计划状态迁移）与 `StepRunner`（单次步骤尝试）里，各自独立提交。

理由是崩溃恢复的两条硬要求：

1. 若整个计划共用一个事务，崩溃时已成功步骤的 `execution_log`、`plan_steps.status` 和**幂等键**会一起回滚 → 幂等键消失意味着重试时**没有任何东西能拦住重复执行**，而真实系统里写工具的副作用（真实退款）**已经发生在外部、本地回滚不了**。
2. 若 `EXECUTING` 标记和大事务绑定，崩溃后计划会退回 `CONFIRMED`，`resume()` 扫不到它，**续跑直接失效**。

**三个类各管一件事**：`Executor` 只做编排（无事务）· `StepRunner` 承担单次尝试的事务边界（`Propagation.REQUIRED`）· `PlanStateWriter` 负责计划状态的独立提交。

### 3.7 只读循环的硬边界 vs 消融开关（配置分类原则）

`AgentProps` 把配置分成两段，javadoc 明写**为什么不能混**：

| 段 | 性质 | 字段 |
|---|---|---|
| `agent` | **实验开关**，生命周期短暂 | `disableConfirmGate` / `disableIdempotency` / `disableTimeoutClassify` / `reactBaselineMode` |
| `read` | **安全边界**，生命周期稳定，不该随实验翻转 | `maxSteps:6` / `wallClockMs:30000` / `tokenBudget:12000` / `callTimeoutMs:20000` / `retryMax:2` / `retryBaseBackoffMs:500` / `toolOutputMaxChars:4000` / `maxRepeatedActions:2` / `fabricationGuardEnabled:true` |

**为什么 `callTimeoutMs` 与 `wallClockMs` 不可合并**：前者是**单次模型调用**的上限（防单点挂死），后者是**整次只读请求**的上限（含多次调用 + 工具往返）。合并会让"一次调用卡住"和"多次调用累计变慢"无法区分。

**另一个易误读点**：`read-loop.max-steps: 6` 是**护栏值**，实际只用 2–3 轮。**别把护栏当用量**。

### 3.8 反幻觉闸门（刻意窄范围）

`FabricationGuard` 只校验**订单号**这一类强标识实体：

```java
private static final Pattern ORDER_NO = Pattern.compile("ORD\\d{6,}", Pattern.CASE_INSENSITIVE);
```

类注释明确：当前只校验订单号，**金额 / 日期 / 人名不校验**——"宁可不做也不要制造假阳性"。

**定位要说清**：它是"**降低**编造出现在用户可见输出里的概率"，**不是"消除幻觉"**。声称消除幻觉是过度承诺；窄范围的构造性校验是可信的能力。

### 3.9 错误码分层：`RefusalCode` vs `ToolErrorCode`

| 枚举 | 归属 | 是否对模型可见 |
|---|---|---|
| `RefusalCode` | **编排层**失败分类（`ORDER_NOT_LOCATED / ORDER_NOT_FOUND / FORBIDDEN / POLICY_DENIED / PLAN_PARSE_FAILED`） | **不可见** |
| `ToolErrorCode` | **工具契约**的一部分（`INVALID_ARGS / ORDER_NOT_FOUND / FORBIDDEN / POLICY_DENIED / TIMEOUT_KNOWN_FAIL / TIMEOUT_UNKNOWN / IDEMPOTENT_REPLAY / CONFIRMATION_REQUIRED`） | 可见 |

**为什么要分**：把编排层的失败分类暴露给模型，等于**告诉模型"有哪些失败模式可以探测"**——那是一个攻击面。

### 3.10 为什么自建控制环（不用框架托管）

调研过三条路线：

| 方案 | 结论 |
|---|---|
| LangChain4j 1.19 agentic | agentic 仍是 beta，控制环不透明，无法插桩状态机 |
| Spring AI Alibaba Graph | 接管整个控制流，幂等 / 恢复 / 确认门没法作为一等公民实现 |
| **Spring AI 原语 + 自建控制环** ✅ | 只用 ChatModel / Tool 原语，Plan / 状态机 / 执行器全部自己写，**每个安全机制都是显式代码** |

**选型标准不是"功能多"，而是"控制权"**——安全系统的控制环必须自己掌握。

---

## 四、系统的核心卖点

### 4.1 卖点一：构造性安全（写工具物理不可见）

不是"提示词约束 LLM"，而是**LLM 的工具列表里根本没有写工具**。三条硬约束：

1. LLM 的工具集**不包含**任何写工具 —— 不是"不允许调用"，是"不存在"；
2. 未确认的计划**无法**进入执行器 —— 状态机层面禁止；
3. 计划确认后若订单状态 / 金额变化，**旧计划立即失效** —— 防止用户确认的条件已被篡改。

**这一条的含金量**：它把安全从"概率"变成"不变式"。人的提示词可以被绕过，架构不行。

### 4.2 卖点二：可靠性的"不确定性建模"

大多数系统只有"成功 / 失败"两态。本项目把**结果未知**单列为一态，并给出不同的策略（禁止盲目重试 → 对账）。再加上 `FAILED_FINAL`（业务拒绝），构成三态。

**为什么这才是可靠性的核心**：分布式系统的真正难题不是"失败了怎么办"，而是"**我不知道成没成功**怎么办"。乐观重试会重复退款，悲观放弃会漏掉已生效的操作——**只有对账能解**。

### 4.3 卖点三：可评测性（能力被证据支撑，而不是被声明）

| 评测物料 | 内容 |
|---|---|
| 43 例消融用例 | 查询 12 · 取消 10 · 退换 9 · 异常超时 6 · 越权/大额 6；**开发集 31 + 留出集 12** |
| 其中**安全相关 16 例** | `category=privilege` ∪ `type∈{blocked, policy_refusal}` 的**并集** = 16（`privilege 6`、`blocked 8`、`policy_refusal 6`，三者有 4 例重叠：S1/S2/S3/S5 同时属 privilege 与 blocked）；**其中 6 例属留出集**（未参与调参） |
| 三配置消融 | V2 **42/43** · V1 **42/43** · V0 **36/42** |
| τ-bench 外部基准 | test split 前 10 任务，**8/10 = 80%**（官方 gpt-4o retail ≈ 82% 作锚点） |
| 崩溃恢复实验 | 真实 `kill -9` + 重启续跑（`scripts/crash_recovery_probe.sh`） |
| 意图路由三方对照 | 本地规则 vs LLM 路由器（`eval/intent_eval.py`） |

**"安全没变坏"是可回归验证的**：16 例安全用例（12 例留出集）构成回归网络，而不是靠断言。

### 4.4 卖点四：测量纪律（最值钱的部分是"如何发现自己的归因错了"）

这是本项目区别于"刷分型项目"的地方。已定位**四个同型测量错误**：

| # | 错误 | 教训 |
|---|---|---|
| 1 | 贪婪正则 | 测量工具本身有偏差 |
| 2 | 用中位数代表体验 | 长尾任务必须报 p90 / 均值 |
| 3 | 编造夹具 | 夹具不保真会让**结论方向反过来**，不只是数值不准 |
| 4 | 把单次测量当稳态 | 时延不是稳态量，对照必须**逐轮交错** |

**四次形状完全一致**：*测量侧的缺陷被读成被测对象的缺陷*，而且每次都指向一个"值得修的机制问题"，所以很难被怀疑。**四次都是靠回头读被测对象的代码发现的，不是靠更仔细地看数字。**

**一个具体的自我否定案例**（可讲度最高）：

- 早先测出"提示词加一句『不要输出分析过程』能让 PLAN 从 13.94s → 8.81s"，列为优先级 1；
- 复核发现**那个数字建立在我手写的、生产里不存在的输入上**：

| 夹具 | `status` | 额外字段 | 与提示词取值域 |
|---|---|---|---|
| 我手写的 | 中文「已发货」 | `statusCode:"SHIPPED"`（生产无此字段） | **交集为空** |
| 生产真实（`PlanService.singleOrderContext`） | `PAID`/`SHIPPED`/`DELIVERED` | 无 | 一致 |

- 换保真夹具后：思考占比 **87% → 49%**，思考 token **329 → 48**，解析通过 **4/6 → 6/6**；
- 在保真夹具上重测该优化（各 8 次交错）：中位差 **0.10s（2%）**，p90 无差异 → **收益不成立，从优先级列表移除**。

**"我测到的『模型忍不住分析』，八成是我自己造出来的。"**

### 4.5 卖点五：判分口径的诚实（评测自己也被审查）

`eval/EVAL_REPORT.md` 主动写出三类判分缺陷：

| 类型 | 案例 |
|---|---|
| 肯定型假失败 | Q2（答得更具体被判失败） |
| **否定型激励反转** | **Q7**：把"不泄露订单存在性"这种**更安全的回复**判为失败 |
| 抖动 | Q6（措辞敏感，同代码同用例结果不同） |

**Q7 的严重性**：判分词表偏向"无权 / 不属于您"的措辞（**隐含确认了订单存在**），而 V2 答"您账号下没有这个订单"（**不确认存在性**）反而被判失败。**一个对更安全实现扣分的口径，会持续把实现推向更不安全的那一侧。**

处置：不改期望词表（那是在给更安全的实现做回归），而是**把否定型用例的期望改成断言**——「不得出现该订单的任何字段」而非「必须出现某个词」。

### 4.6 卖点六：消融设计的自我审查

**43 例口径下 V1 与 V2 打平（都是 42/43）**——但这个"打平"不能读成"确认门没用"：

| 配置 | 唯一失败 | 失败的性质 |
|---|---|---|
| V1 | **S4**（大额首次确认即执行） | **真实缺陷**——正是消融变量本身 |
| V2 | **Q7**（越权拒绝措辞） | **判分缺陷**——回复正确且更安全 |

**可迁移判断**：当两个配置的总分相同时，必须逐条看失败项是否同类。**"总分相同"是一个会把真实差异和判分噪声抵消掉的数字——它比"总分不同"更需要逐条拆开。**

---

## 五、系统已完成的功能

### 5.1 单元测试：54/54 全绿（不依赖真实 LLM）

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `D1AcceptanceTest` | 12 | schema + 种子数据 + 工具契约 + 政策矩阵 + 幂等键唯一性 |
| `D2AcceptanceTest` | 5 | 无 LLM：意图解析纯函数 + 会话持久化 |
| `D3AcceptanceTest` | 18 | 规划 / 确认门 / 指纹失效 |
| `D4AcceptanceTest` | 9 | 执行器（`Executor` / `FaultInjector`） |
| `D5DurabilityTest` | 2 | 崩溃恢复持久性（**刻意不加 `@Transactional`**） |
| `ReadLoopTest` | 8 | 只读循环边界（步数 / 去重 / 截断 / 退避 / 幻觉工具名 / 反幻觉闸门） |
| **合计** | **54** | Failures 0 · Errors 0 · Skipped 0 |

> `D5DurabilityTest` 刻意不加 `@Transactional` 的理由：**带测试事务去测持久性，等于用橡皮擦去证明铅笔写过字**——测试事务会把"被测代码提交了什么"一并回滚。

### 5.2 评测结果

| 指标 | 结果 |
|---|---|
| 43 例消融 · V2 完整版 | **42/43（97.7%）** |
| 43 例消融 · V1 无确认门 | 42/43（97.7%） |
| 43 例消融 · V0 ReAct 基线 | **36/42（85.7%）**（T5 设计性 N/A） |
| 高风险拦截 | V2 **6/6** · V1 5/6（S4）· V0 5/6（S4） |
| 留出集（12 例） | V0 12/12 · V1 12/12 · V2 11/12（唯一失败 Q7，判分缺陷） |
| τ-bench 外部基准 | **8/10 = 80.0%** |

**归因链**：

- **V0 → V1**：故障恢复从"LLM 临场判断"上移为确定性执行器（V0 在"明确失败重试"场景多轮后 LLM 偏离任务）
- **V1 → V2**：确认门 + 大额分级（S4 是唯一由消融变量直接导致的失败）

### 5.3 意图路由本地化（已量出结论，**未接线**）

`eval/intent_eval.py` 对照结果（评分口径按**代价不对称**定：写被判成查 = 事故级；查被判成写 = 体验级 → **写召回优先**）：

| 臂 | 总准确 | 写召回 | 查误判为写 | 中位耗时 |
|---|---|---|---|---|
| 本地规则（兜底偏 WRITE） | **43/43** | **30/30** | 0/13 | **0.00s** |
| 本地规则（兜底偏 QUERY，同生产） | 42/43 | 29/30 | 0/13 | 0.00s |

**诚实边界**：43/43 是照着 dev 用例调出来的；脚本分开报 dev 31/31 与 holdout 12/12，否则就是自证式优化。

### 5.4 崩溃恢复实验（真实进程级证据）

`scripts/crash_recovery_probe.sh`：**真实 `kill -9`** 打断正在执行的计划，重启后验证续跑。

判定标准（崩溃后仍应能从库里看到四条）：

```
plan = EXECUTING
第 1 步 = SUCCESS
execution_log 有记录
幂等键存在
```

**为什么必须真 `kill -9`**：手工把 `plan.status` 改成 `EXECUTING` 默认了"崩溃后进度还在库里"这个前提——**而这恰恰是需要被验证的东西**。

### 5.5 可观测性（链路轨迹）

- `agent_trace` 表**只追加**，`TraceController` 支持**请求进行中轮询**
- 三件事一起解决"等待期间大片空白"：① 请求入口立刻落 `RECEIVED`；② **每一段长耗时调用发出前**落一行 `RUNNING`；③ 轨迹写入 `REQUIRES_NEW` 独立提交
- 效果：**首帧从 7.8s 提前到 1.2s**，飞行中可见步骤从 1 条变成 6 条

### 5.6 前端与交付物

| 交付物 | 说明 |
|---|---|
| `src/main/resources/static/index.html` | 471 行单页，含 Plan 卡片 / 确认 / 拒绝 / 执行 / trace 轮询 / 秒表 |
| `docs/DESIGN.html` | 设计说明书（151 KB，14 节，含分层架构 / 幂等抢占 / 事务边界 / 崩溃恢复图示） |
| `docs/permission-boundary.svg` | 权限边界图 |
| `docs/pipeline-overview.jpg` | 流水线总览图（README 头图） |
| `docs/LATENCY_OPTIMIZATION.md` | 时延讲点评估（含 7 项候选优化排序 / 5 条被证伪的直觉） |
| `USE_GUIDE.md` | 使用与演示指南（含演示用户与订单速查） |
| `TECH_JOURNAL.md` | 过程日志（135 KB，19 条技术问题留档） |

### 5.7 评测基建（可复现）

| 脚本 / 文件 | 职责 |
|---|---|
| `eval/cases.json` | 43 例定义（含 **4 条指代式查询 + 3 条指代式写诉求**） |
| `eval/run_eval.py` | HTTP harness，每 case 自动 `reset_db()`，支持 `--ids` 定向复跑 |
| `scripts/run_ablation.sh` | 一键跑三配置（负责重启应用并等端口释放） |
| `scripts/crash_recovery_probe.sh` | 真实 `kill -9` 崩溃恢复实验 |
| `eval/intent_eval.py` | 意图路由三方对照（本地 vs LLM） |
| `scripts/llm_*.py`（10 个） | 时延归因探针族（分解 / 分布 / 固定成本 / 模型对照 / 夹具保真 / 提示词变体） |
| `scripts/reset_demo_data.sh` | 演示数据复位 |
| `scripts/trace_live_probe.py` | 实时进度探针 |

---

## 六、待完善的功能

### 6.1 已定位、有实测依据的改进项

按「证据强度 × 收益 ÷ 代价」排序（出自 `docs/LATENCY_OPTIMIZATION.md`）：

| # | 优化项 | 现状 | 证据强度 |
|---|---|---|---|
| 1 | **降低读路径模型轮次**（合并工具往返后的再调用 / 一次给足上下文） | **未测**——但已是收益最大的**结构性**方向 | ⚠️ 只定位了位置 |
| 2 | **意图路由降级为本地分类器** | 已量出 43/43、写召回 30/30、holdout 12/12、0.00s；**未接进代码** | ✅ 强 |
| 3 | **确定性计划缓存**（键 = 订单号 + 规范动作） | 已量出覆盖 **40%** 可缓存写请求；**未实现** | ✅ 强（边界已量） |
| 4 | PLAN 换 flash 模型 | 长输出最大、短输出几乎无收益；会破坏 42/43 基线可比性 | ⚠️ 时段污染 |
| 5 | 合并 INTENT + PLAN | 早先测 40%，但在**虚构输入**上测的 | ❌ 需重测 |
| 6 | ~~提示词里禁止分析~~ | **已证伪，移除** | ❌ |
| 7 | ~~压 max_tokens~~ | **反优化**（160/96/64 → 0/5 解析失败）；且 64 比 96 更慢 | ❌ |

> 第 1 项为什么排第一：读路径端到端 10.5s / 2 轮、12.1s / 3 轮，其中模型调用占 **100%**（工具 0.0s、DB 0.0s）。**每一轮模型调用都是一次完整的"排队 + prefill + decode"，轮次是乘性成本。** 把 3 轮压到 2 轮，比把任何单轮优化 10% 都更有效——这是**结构**收益，不是**速率**收益。
>
> 另注：读路径比写路径**慢**（读 10.5s vs 写 5.5s），这直接推翻了"Plan-and-Execute 链路重"的初始归因。

### 6.2 测量与评测层的缺口

| 缺口 | 现状 |
|---|---|
| **时延未被判定** | `eval/run_eval.py:376` 只 `r["latency"] = round(...)` 记录，`:381` 仅打印，**无任何断言使用** → 任何延迟优化都无法被评测层拦住回归。需引入 **p90 阈值断言**，且必须在**交错窗口内**采样 |
| **三配置按配置串行跑** | `scripts/run_ablation.sh` 是逐配置串行，V0/V1/V2 落在三个不同时间窗 → **排序可信，精确 pp 不可信**。修法：按用例**交错**跑 |
| **旧绝对秒数未重测** | `TECH_JOURNAL.md` 第 16/17/18 条的所有绝对秒数标注为"慢窗口快照"，需交错重测 |
| **上游时变只有两个采样点** | 现仅当日下午（均值 34.1s）与当晚（均值 3.6s）两点，**差 9.5 倍**（极值 33 倍）。需长期采样刻画（几点开始变差、持续多久） |
| **否定型期望未断言化** | Q6/Q7 这类措辞敏感用例，期望应写成"不得出现该订单任何字段"的断言 |
| **写路径 token 计账缺失** | `LlmPort.complete()` 只返文本，未带 token 用量 |

### 6.3 必须修的实现问题

| 问题 | 说明 |
|---|---|
| **`PlanService` 在 ~51s 的 LLM 调用期间持有数据库事务** | `@Transactional` 标在 `PlanService.createPlan(...)`（`:101` / `:114`）上，而 LLM 调用 `planGenerator.generate(...)` 在 `:169`——**在事务内**。后果：一个 **6ms** 的工具调用被扣在 **51.9s** 的事务里才对读连接可见。**做压力测试前必须先解决** |
| **消融开关接线缺口** | `timeoutClassifyEnabled()` 在 `AgentProps` 中声明、经 `AgentPropsProvider` 暴露，但**全项目无调用点**（`disable-timeout-classify` 开关实际不起作用）。其余四个开关均已接线：`reactBaselineMode`→`AgentOrchestrator:115`、`confirmGateEnabled`→`PlanManager:85`、`idempotencyEnabled`→`StepRunner:104`、`fabricationGuardEnabled`→`ReadLoop:290` |

### 6.4 能力边界（有意不做，列出来避免误判）

| 项 | 现状 |
|---|---|
| 长期记忆 | **没有**。会话历史落在 `conversation_messages`，但无跨会话的用户偏好/事实记忆层——**有意不做**，避免为凑清单虚设一层 |
| 多步计划 | 执行器支持多步（`plan_steps` 唯一索引 `plan_id+seq`），但当前规划提示词在多数场景产出**单步**计划 |
| 意图路由 | 只有 `QUERY` / `WRITE` 二分，**无置信度阈值与人工转接** |
| 用户模拟 | τ-bench 官方用 LLM 模拟用户，自评测用脚本驱动，交互深度有限 |

### 6.5 生产化缺口（MVP 边界内有意为之）

| 缺口 | 风险说明 |
|---|---|
| **无真实支付/物流系统对接** | 写工具操作的是本地 `orders` 表。**因此"重复执行"当前只是重复改一行状态，代价可逆**；一旦接上真实外部系统，**幂等键与对账就是资金安全问题** |
| **无鉴权** | `userId` 从请求参数传入，**仅用于归属校验，不是身份认证** |
| 无多租户隔离 | — |
| 无监控告警 / 无分布式锁 | `resumeAll()` 是**单实例扫描**，多实例并发调用会重复接管同一计划 |
| `/api/selftest` 是有副作用的 GET | 会真的调用写工具，靠强制回滚兜底，**生产不应这样暴露** |
| `/api/fault` 无鉴权 | 故障注入端点，**生产必须移除** |

### 6.6 已知技术债

| 债项 | 影响 |
|---|---|
| 重试次数的持久化粒度是"单次尝试" | 崩溃发生在一次尝试**内部**时，该次尝试的 attempt 计数会丢失，续跑会从这次尝试重新开始。因幂等键与业务写同事务提交，**不会造成重复执行，但会造成一次多余的尝试** |
| `RECONCILE_FAILED` 只有落账 | **没有自动重试的调度器** |

### 6.7 Roadmap（未做项）

- [ ] 接入真实支付 / 物流系统的适配层（接入后幂等键与对账从"状态可逆"升级为**资金安全**）
- [ ] 多租户与权限模型（当前 `userId` 只是归属校验）
- [ ] 计划执行的分布式锁与跨实例幂等
- [ ] 补偿任务调度（`RECONCILE_FAILED` 已有记录，缺调度器）
- [ ] 指标看板与告警（链路 Trace 已有：`/api/trace/{traceId}`）
- [ ] 长期记忆层 + 多步计划规划能力
- [ ] SSE 流式输出（注：实测流式比非流式**慢一倍**，7.05s vs 3.80s——其价值在**可观测性**，不在耗时）
- [ ] 本地部署吞吐实测
- [ ] 更多域的工具集（售后以外的交易场景）

---

## 附：与既有文档的不一致清单

本次全量取证过程中发现三处**文档与实现不符**，此处标注（已核实到代码/表结构）：

| # | 位置 | 文档写法 | 实测 |
|---|---|---|---|
| 1 | `TECH_SUMMARY.md` §7 | "64 个 Java 文件" | **65**（`src/main/java` 实测） |
| 2 | `README.md` "数据模型" 行 | 列了 8 张表 | **9 张**（漏了 `agent_trace`） |
| 3 | `README.md` 配置项 / `AgentProps` | `aftersale.agent.disable-timeout-classify` 列为"消融：关闭超时二分" | **`timeoutClassifyEnabled()` 全项目无调用点**，该开关实际不起作用 |

> 第 3 条需要在"这是预留开关"与"该接线却漏了"之间做一次定性——目前无法从代码判断作者意图，建议在 README 配置表中显式标注。

---

## 一句话回答"为什么这个项目值得看"

> 大部分 Agent 演示在"能聊天"，这个项目回答的是"**怎么让一个会幻觉的模型安全地动钱**"——通过把安全机制做成**架构级不变式**（写工具物理隔离、确认门、指纹失效、失败三分类、幂等续跑），并用 **43 例消融评测**证明：高风险写操作 100% 拦截、订单终态一次未被越权改动，而 ReAct 基线在故障恢复与"确认前的校验"上确实会翻车。**评测本身也做了自查**——判分口径的三类缺陷（假失败 / 否定型激励反转 / 抖动）与单轮噪声都已定位并写明：`eval/EVAL_REPORT.md` 里给出的**排序是有证据的，精确数值则明确标注为不可复现**。