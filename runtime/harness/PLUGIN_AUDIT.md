# Geomantia Harness 插件完整性核对

核对基线：官方 `@deepseek-ai/dsh-base@0.1.5-rc.2/cordis.patch.yml`，与内核固定版本一致；不以持续变化的 master 配置替代发布版。官方基线包来自 npm，副本与检查记录保存在本次本地任务记录。

“已安装 npm 包”不代表插件已经挂载。以下以 `src/runtime.mjs` 实际注册及端到端测试为准。

| 能力 | Geomantia 组成与职责 | 验证 |
| --- | --- | --- |
| 模型、循环、工具 | `llm`、`agent`、`agent-loop`、`tools`；官方 DeepSeek / pi-ai 适配器 | Chat、Responses、宿主工具往返 |
| 业务指令与隔离 | `system-prompt` + Geomantia 插件；Java 当前阶段工具白名单 | 原有 Provider 作用域测试 |
| 原生图片 | `attachment-local` + 适配器显式图片能力 | 初始图和工具图进入请求、像素验证；不经过辅助模型描述 |
| 会话保存和恢复 | `session`、`session-projection`、`session-persistence-jsonl` | 独立进程恢复及压缩后恢复 |
| 上下文计量 | **补齐 `token-meter`** | 长会话/现场超限历史副本 |
| 自动压缩与溢出恢复 | **补齐 `compaction-basic`**（含 `compaction` 服务依赖）；60% 压力触发、保留近期16%、摘要输出8192 | 连续18轮完整状态触发压缩；最新状态与图片保留；既有饱和会话恢复 |
| 输出预算 | 会话明确 `maxTokens=32768`，DeepSeek 路由亦设置默认值 | 请求断言；不再继承256000 |
| 网络瞬态故障 | **补齐 `llm-retry`**；正常模式，最多2次步骤内重试 | 503 后同轮成功，最终不误报失败 |
| 关键步骤耐久性 | **补齐 `session-checkpoint-policy`** | 宿主工具执行前，日志已经包含请求和 tool/call |
| 取消与超时 | 官方 Agent cancellation、可取消退避、适配器流空闲超时；Java 20分钟总时限及关闭子进程 | 退避期间取消，不发生后续请求；取消返回明确失败状态 |
| 重复工具/无进展保护 | Java `PlanningTurnControl` 与阶段状态推进检查；插件每次运行24步上限 | 原有 Provider 重复拒绝、无进展测试 |
| 设置与凭据 | Java Provider 设置与独立密钥文件；凭据经私有stdin进入子进程环境 | 不另建 Harness 设置/凭据仓库；不写进请求证据 |

官方基线中以下部分在本产品没有对应入口，明确不挂载：

- Web/TUI、API Gateway、会话标题、全文检索、工作空间和投影缓存：游戏已有 UI；JSONL 可直接重放，不依赖这些展示/加速服务。
- 通用 shell、文件、浏览器、网络检索、代码执行、子 Agent、后台 jobs、goal/todo/plan：当前城市规划由宿主阶段驱动，工具仅由 Geomantia 插件提供。
- `spill-policy` 与 `spill-local`：默认会把大工具结果变成外部文件引用；本插件只读当前 run 的规划产物，不能读取任意 spill 目录，暂不启用。
- `compaction-tool-result-pruner`：可选的确定性文本截断，不是 `compaction-basic` 的必需依赖。城市结构化结果需要保留完整上下文，采用较早历史摘要并保留近期完整状态，不启用头尾裁切。
- `command-compact`：人类 `/compact` 命令入口，自动压缩不依赖它；游戏没有该命令面板。
- 遥测、热重载、反馈上传：不是规划循环的运行依赖。

新版本 master 中额外出现的图片卸载等插件不与固定版混装。当前附件与模型适配器已有图片预算处理；Geomantia 使用官方 session surface replacement，在宿主续跑时移除旧图片负载、保留文字和路径；同一轮保留模型按需读取的不同图片并去重。最新必需预览继续以原生附件提供。

边界：token-meter 给出估算而非精确计费值；摘要由模型生成，不能保证没有语义遗漏。正式城市状态始终由 Java 保存并在后续轮次提供。插件回归不等于完整城市视觉质量验收。

## 规划资料按需读取

Geomantia 提供 artifact_list、artifact_search、artifact_read_text、artifact_view_image。只读当前 run 规划产物，真实路径校验防止目录逃逸，搜索不跟随目录链接。模型自主选择读图数量；设计更新返回总览，选址与主动材质/示例查询仍返回必要图片。文件工具不是任意 shell 或全盘文件能力。
