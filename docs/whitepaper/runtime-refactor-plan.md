# 运行时基础设施重构设计 (Runtime Infrastructure Refactor Plan)

## 1. 文档目标
本文档定义 StructureBinder 下一阶段运行时重构的统一设计方向，重点覆盖以下三条主线：

1. 统一 Java Mod 与 Node MCP 两侧的日志体系。
2. 将长耗时 MCP 调用升级为标准化任务协议，支持阻塞等待、轮询等待与超时控制。
3. 将涉及 AI 判断的阶段流程沉淀为 `MCP + Skill` 协作模式，明确“执行”和“决策”的边界。

本文档不是一次性最终架构，而是本次大重构分支的指导蓝图。后续所有实现、迁移和接口调整，均以本设计为基准。

## 2. 当前现状与核心问题

### 2.1 日志体系已存在，但入口分散
当前项目中已经有多套日志/跟踪能力：

- Java 侧已有全局 Tick 计数器 `ServerTickTracker`。
- Territory 工作流已有独立轨迹日志 `TStageTraceLogger`。
- Node MCP 侧已有工具调用日志 `country_designer_mcp/src/shared/logging.ts`。

当前问题不是“没有日志”，而是：

- 各模块自行决定日志格式，缺少统一字段。
- Java 与 Node 两侧日志无法自然串联。
- 日志缺少统一的任务上下文，例如 `task_id`、`stage_id`、`city_id`、`territory_id`。
- 已有 Tick 信息没有成为所有运行时日志的默认字段。

### 2.2 MCP 已区分长超时，但还不是统一任务协议
当前 Node MCP 侧已经通过 `TIMEOUTS.workflow`、`TIMEOUTS.worldScan` 等区分不同耗时调用；Java 侧也有 `workflow/status` 与 `task_status` 等接口。

但当前能力仍然偏“接口级特判”，尚未形成统一的长任务协议：

- 有些接口直接阻塞 HTTP 直到完成。
- 有些接口通过状态文件或状态查询接口间接表示进度。
- 不同接口的“开始执行”“任务进行中”“完成”“失败”“等待 AI 决策”没有统一状态模型。
- Node handler 只是薄转发，没有统一的等待策略抽象。

### 2.3 AI 决策阶段还未形成稳定边界
当前很多阶段已经逐渐具备“先产出候选，再由 AI 审阅/选择，再提交结果”的形态，例如：

- T1/T2 选址与方向确认。
- C6 矩形选择。
- C7 模板筛选。
- C8 基台与排列确认。

这些阶段天然不应只是一组裸 MCP 接口，因为：

- 它们依赖阶段性推理和上下文串联。
- 它们通常需要读多个产物后再作决策。
- 它们经常需要暂停、等待、恢复、再继续执行。

因此这部分更适合抽象为 `Skill 负责决策流程 + MCP 负责可验证动作` 的组合。

## 3. 本次重构的总体原则

### 3.1 先收敛基础设施，再迁业务逻辑
本次重构不建议直接从某个业务阶段开始大改，而应按以下顺序推进：

1. 统一日志基础设施。
2. 统一长任务/MCP 协议。
3. 整理 MCP handler 分层。
4. 将 AI 决策阶段 skill 化。

原因是第 3、4 步都依赖前两步提供稳定的观测与执行能力。

### 3.2 优先做“兼容式迁移”，避免一次性推倒
旧类和旧接口在第一阶段不强制删除，而采用以下迁移策略：

- 先提供统一新入口。
- 旧实现内部转调新入口。
- 新增代码只允许依赖新入口。
- 待高频路径迁移完成后，再逐步清理旧包装层。

### 3.3 所有运行时行为都要可追踪
无论是日志、任务状态还是 AI 阶段，都必须能回答以下问题：

- 谁发起了这个动作。
- 它属于哪个阶段、哪个对象、哪个任务。
- 它当前处于什么状态。
- 它为什么暂停、失败或继续。
- 它的最终结果和中间证据在哪。

## 4. 重构主线一：统一日志体系

### 4.1 目标
建立一套可跨 Java Mod 与 Node MCP 对齐的运行时日志模型，满足以下要求：

- 每条日志自动带有运行期 Tick 或 Frame 信息。
- 同一任务在多端日志中可串联。
- 阶段执行、HTTP 调用、AI 决策、任务状态更新使用统一字段。
- 支持结构化 JSONL 落盘，便于后续分析与调试。

### 4.2 建议的核心组件
建议引入以下 Java 侧组件：

- `RuntimeTickClock`
  - 统一读取当前游戏 Tick。
  - 默认封装 `ServerTickTracker.currentTick()`。

- `RuntimeLogContext`
  - 封装一条日志的上下文字段。
  - 包含 `task_id`、`stage_id`、`city_id`、`territory_id`、`workflow_id`、`source` 等信息。

- `RuntimeLogManager`
  - 负责日志目录、文件路由、序列化与写入。
  - 负责生成默认字段，例如 `ts_epoch_ms`、`tick`、`thread`。

- `RuntimeLogger`
  - 作为业务侧统一调用门面。
  - 暴露 `info/debug/warn/error/event/progress` 等方法。

Node MCP 侧建议对应引入：

- `McpLogContext`
- `McpLogManager`
- `writeMcpEvent(...)`

Node 侧不需要读取游戏 Tick，但应尽可能携带来自服务端的 `task_id`、`stage_id` 和 request correlation 信息。

### 4.3 统一日志字段
建议统一结构化字段如下：

```json
{
  "ts_epoch_ms": 0,
  "tick": 0,
  "level": "INFO",
  "source": "java_mod",
  "domain": "workflow",
  "scope": "stage",
  "event": "stage_started",
  "message": "Workflow stage accepted.",
  "task_id": "workflow_t3",
  "stage_id": "T3",
  "city_id": null,
  "territory_id": null,
  "thread": "TerraScript-API",
  "details": {}
}
```

字段约束建议：

- `source`：`java_mod` / `node_mcp`
- `domain`：`workflow` / `world_scan` / `city` / `territory` / `ai`
- `scope`：`http` / `task` / `stage` / `tool` / `decision`
- `event`：固定语义事件名，不直接使用自然语言句子
- `message`：人可读摘要
- `details`：补充结构化载荷

### 4.4 文件组织建议
建议统一落到世界或工程内可区分的目录下：

- Java 侧世界内日志：
  - `world/terra_script/runtime_logs/...`
- Node MCP 侧工程内日志：
  - `country_designer_mcp/logs/...`

建议按主题和日期分流，而不是一个功能一个完全独立格式：

- `runtime_logs/workflow/*.jsonl`
- `runtime_logs/world/*.jsonl`
- `runtime_logs/city/*.jsonl`
- `runtime_logs/ai/*.jsonl`

### 4.5 迁移策略
第一阶段优先迁移以下路径：

1. `WorkflowController`
2. `WorldAutomationController`
3. `CityController` 中的长耗时接口，例如 `city_c2_generate`
4. `TStageTraceLogger`

其中：

- `TStageTraceLogger` 可以先保留类名，但内部改为委托 `RuntimeLogger`。
- 旧的 `logging.ts` 也先保留导出名，但内部转到新的 MCP 日志实现。

## 5. 重构主线二：统一长任务 MCP 协议

### 5.1 目标
将“耗时任务”从普通 HTTP 接口升级为标准化任务模型，使 MCP 与 Java 服务端对以下场景有统一处理：

- 立即执行并返回。
- 阻塞等待直到完成。
- 发起后轮询等待完成。
- 在限定超时内等待，超时后返回“仍在执行”。
- 等待 AI 继续决策后再进入下一步。

### 5.2 统一任务类型
建议把 MCP 工具调用按执行语义分成三类：

1. `immediate`
   - 普通短接口。
   - 例如读取摘要、读取预览、读取数据文件。

2. `wait_until_done`
   - 发起后必须等任务真正完成才返回给 AI。
   - 例如地形扫描、区域扫描、重型导出。

3. `start_and_poll`
   - 发起后获取 `task_id`，在规定超时内轮询。
   - 若完成则返回最终结果。
   - 若超时则返回当前状态，由 AI 决定是否继续等待或转入别的步骤。

### 5.3 服务端统一状态模型
建议统一任务状态枚举：

- `ACCEPTED`
- `RUNNING`
- `WAITING_FOR_AI`
- `SUCCEEDED`
- `FAILED`
- `CANCELLED`
- `TIMED_OUT`

建议统一状态载荷：

```json
{
  "task_id": "workflow_t4_1712345678901",
  "state": "RUNNING",
  "message": "Stage still running.",
  "progress_percent": 42,
  "progress_current": 84,
  "progress_total": 200,
  "active_operation": "scan_chunks",
  "waiting_for_ai": false,
  "next_action": null,
  "started_at": 0,
  "heartbeat_at": 0,
  "finished_at": null,
  "result": null,
  "error": null
}
```

其中：

- `task_id` 必须全局唯一并可落日志。
- `active_operation` 用于表示当前正在做什么。
- `waiting_for_ai` 与 `next_action` 保留给 AI 阶段。
- `result` 应在成功完成后返回最终摘要，而不是要求 MCP 再拼接多份数据。

### 5.4 Java 侧建议新增组件
建议引入统一任务基础设施：

- `TaskExecutionMode`
  - `IMMEDIATE`
  - `WAIT_UNTIL_DONE`
  - `START_AND_POLL`

- `TaskState`
  - 对应统一状态枚举。

- `RuntimeTaskHandle`
  - 包含 `taskId`、状态、结果、错误、上下文信息。

- `RuntimeTaskRegistry`
  - 管理活动任务。
  - 提供开始、查询、取消、完成、失败接口。

- `RuntimeTaskRunner`
  - 负责将 controller 中的长耗时逻辑包装成统一任务执行模型。

当前已有的 `StageStatusStore`、`TaskStatusHeartbeat`、`FileStageStatusStore` 可以作为底层能力继续复用，但上层需要一个更统一的任务抽象。

### 5.5 Node MCP 侧建议新增组件
Node 侧建议新增统一执行器，而不是每个 handler 自己挑 timeout：

- `invokeMcTask(...)`
  - 发起工具请求。
  - 按工具配置选择等待模式。

- `awaitTaskCompletion(...)`
  - 对 `task_status` 或专用状态接口轮询。

- `McpTaskPolicy`
  - 定义工具的等待模式、超时、轮询间隔。

例如：

```ts
type McpTaskPolicy = {
  waitMode: "none" | "until_done" | "poll_until_done";
  requestTimeoutMs: number;
  overallTimeoutMs?: number;
  pollIntervalMs?: number;
};
```

### 5.6 与你的两类需求的直接映射

#### A. 必须一直等待服务端回包
适合：

- 全图扫描
- 局部地形扫描
- 超大导出

策略：

- `waitMode = "until_done"`
- Node 侧可采用更大的 HTTP 超时，或发起任务后轮询但不提前返回
- 对 AI 表现为“该工具只有真正完成才返回”

#### B. 可以等待超时，例如 60 秒
适合：

- 工作流推进
- 某些阶段式处理接口
- 可能进入 `WAITING_FOR_AI` 的流程接口

策略：

- `waitMode = "poll_until_done"`
- `overallTimeoutMs = 60_000`
- 超时后返回当前任务状态、进度与下一步建议

### 5.7 第一批统一接入的接口
建议优先统一以下接口：

1. `/workflow/run`
2. `/world/scan/start`
3. `/world/w4/region_scan`
4. `/city_c2_generate`

原因：

- 都是明显长任务。
- 都已经有不同形式的等待或状态概念。
- 都属于后续 AI 流程的关键基础能力。

## 6. 重构主线三：AI 决策阶段封装为 MCP + Skill

### 6.1 目标
建立清晰边界：

- MCP 负责“动作”和“数据”
- Skill 负责“阶段性决策流程”

从而避免以下问题：

- AI 直接裸调大量工具，状态难跟踪。
- 决策逻辑散落在 Prompt 中，难复用。
- 暂停/恢复逻辑没有固定骨架。

### 6.2 职责边界

#### MCP 负责
- 读取阶段输入与产物
- 启动阶段计算
- 提交 AI 已确认的选择
- 查询任务状态
- 返回结构化结果与错误

#### Skill 负责
- 规定一个阶段应该先读哪些数据
- 如何理解候选、验证条件和风险
- 在什么情况下暂停给人审阅
- 在什么情况下自动推进下一步
- 如何在超时、失败、待人工确认时恢复流程

### 6.3 适合优先 skill 化的阶段
建议按以下顺序推进：

1. `T1/T2`
   - 候选区域读取
   - 簇选择
   - 方位选择

2. `C6`
   - 读取 build area 与候选矩形
   - 评估候选质量
   - 提交矩形决策

3. `C7`
   - 读取功能区需求
   - 选择结构模板
   - 解释筛选理由

4. `C8`
   - 读取基台规划上下文
   - 形成排列与基础确认

这些阶段都具备“可读产物 + AI 判断 + 提交动作 + 等待结果”的完整闭环。

### 6.4 Skill 模板建议
每个 AI 阶段 skill 建议统一结构：

1. 读取阶段上下文
2. 读取输入产物
3. 做约束检查
4. 生成决策或建议
5. 调 MCP 提交
6. 等待结果或轮询状态
7. 输出阶段总结与下一步

这样后续每个 skill 都会形成稳定的执行骨架，而不是一次次重新写 Prompt。

## 7. 建议的目录与模块调整

### 7.1 Java Mod 侧
建议逐步引入如下包结构：

```text
com.user.terra_script.runtime.log
com.user.terra_script.runtime.task
com.user.terra_script.runtime.context
com.user.terra_script.server.mcp.protocol
```

建议职责：

- `runtime.log`
  - 日志上下文、日志管理器、事件类型

- `runtime.task`
  - 任务句柄、任务状态、任务注册表、任务执行器

- `runtime.context`
  - Tick、请求上下文、阶段上下文、trace id

- `server.mcp.protocol`
  - HTTP 层返回对象、统一任务响应模型、错误响应模型

### 7.2 Node MCP 侧
建议逐步引入如下模块：

```text
country_designer_mcp/src/shared/logging.ts
country_designer_mcp/src/shared/task-policy.ts
country_designer_mcp/src/shared/task-runner.ts
country_designer_mcp/src/shared/protocol.ts
```

建议职责：

- `logging.ts`
  - MCP 侧统一事件日志

- `task-policy.ts`
  - 每个 tool 的等待模式定义

- `task-runner.ts`
  - 发起请求、等待、轮询、超时控制

- `protocol.ts`
  - 统一描述任务响应与状态响应

## 8. 分阶段实施计划

### 阶段 A：日志基础设施
目标：

- 新建统一日志 API
- 在 `WorkflowController`、`WorldAutomationController`、`CityController` 首批接入
- 让 `TStageTraceLogger` 转调统一日志实现

完成标准：

- 所有首批长任务均带 `tick`、`task_id`、`stage_id`
- Java 与 Node 两侧日志字段对齐

### 阶段 B：长任务协议
目标：

- 建立统一任务状态模型
- Node 侧引入统一等待策略
- 首批长任务改为统一任务返回格式

完成标准：

- 不再依赖“每个 handler 自己选 timeout”
- 至少 `workflow/run` 与 `city_c2_generate` 支持统一任务等待

### 阶段 C：MCP handler 分层
目标：

- 将工具按 `immediate / wait_until_done / start_and_poll` 标记
- 收敛请求构造与状态等待逻辑

完成标准：

- handler 层变薄
- 长任务逻辑集中在共享任务执行器中

### 阶段 D：AI skill 化
目标：

- 选 `T1/T2` 或 `C6` 作为首个 skill 化样板
- 跑通“读取 -> 决策 -> 提交 -> 等待 -> 总结”闭环

完成标准：

- 至少一个阶段不再依赖自由散装 Prompt 即可稳定推进

## 9. 非目标与边界说明
本次重构的第一阶段不包含以下内容：

- 不强制立刻重写全部 Controller。
- 不强制将所有旧日志文件迁移成同一目录结构。
- 不在本阶段解决所有领域对象拆分问题。
- 不在本阶段全面重做城市/领土阶段算法本身。

本次工作的重点是“运行时可观测性、任务执行协议、AI 决策边界”三件基础能力。

## 10. 最终决策建议
建议本次重构分支以“运行时基础设施”作为主轴推进，而不是以单一业务阶段作为主轴。推荐实施顺序如下：

1. 先落统一日志模型。
2. 再落统一任务协议。
3. 然后整理 MCP handler。
4. 最后选一个 AI 阶段做 `MCP + Skill` 样板。

这样可以确保后续大规模重构时：

- 每一步都有日志可看。
- 每个长任务都有一致状态。
- 每个 AI 阶段都有稳定执行骨架。

这会显著降低后续 C/T/W 多阶段继续膨胀时的维护成本。
