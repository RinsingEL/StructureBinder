# DeepSeek Harness 内置运行时

内核固定为官方 `@deepseek-ai/dsh-* 0.1.5-rc.2`，Cordis `4.0.2`，Windows x64 Node `22.23.2`。上游：https://github.com/deepseek-ai/deepseek-harness 。依赖的精确版本和完整性见 `geomantia_harness/package-lock.json`；二进制与源码指纹见 `manifest.json`。

Geomantia 是 Cordis/Harness 的业务插件：提供规划提示词、当前阶段允许的工具及原生图片附件；官方 Agent Loop 负责模型请求、工具调度和历史。Java 通过私有 stdio 执行原有宿主工具，继续控制阶段提交、作用域、停止和重试。外部 MCP 接口保留，但内置 Agent 不再绕经 MCP Server / Gateway。运行时没有 shell、浏览器或文件写入工具；Geomantia 提供当前 run 规划产物范围的只读目录浏览、文件搜索、文本读取和原生图片读取。

图片策略：新设计返回当前总览；初次选址、材质/示例等必要视觉反馈继续附图。局部图片由模型自主请求，数量不固定。宿主续跑移除旧轮图片负载，保留路径与文字；同一轮不同图片可比较，相同附件去重。修改使用官方可持久化 surface replacement，原始审计记录仍保留。

长会话配置：显式32k输出预算，官方 token-meter + compaction-basic 在60%压力时压缩旧历史并保留近期16%；llm-retry 最多2次瞬态重试；session-checkpoint-policy 在请求和工具副作用前落盘。完整核对及有意不挂载的组件见 [PLUGIN_AUDIT.md](PLUGIN_AUDIT.md)。

默认选用 DeepSeek Harness，旧 `agentRuntime: hermes` 自动归一化为 `harness`，密钥和自定义地址不变。OpenCode Go 的 DeepSeek 路由使用 Chat Completions 和 `x-opencode-session`；其他 Responses 配置及非 DeepSeek 模型走官方 pi-ai 适配插件。旧 Hermes 会话不导入。新历史及图片保存于各世界调试根目录的 `.harness/`，`request-evidence.jsonl` 只记录实际请求图片数量、哈希和模型，不保存密钥或内联图片。

## 重建

1. 获取官方 Windows x64 Node 22.23.2 发行包（`https://nodejs.org/dist/v22.23.2/node-v22.23.2-win-x64.zip`），保留 `node.exe` 和同目录 `LICENSE`。开发端可用已经解压的同版 Node，但发布包不依赖该目录。
2. 在 `geomantia_harness` 执行 `npm ci --ignore-scripts`，然后用该 Node 执行 `build.mjs`。构建收集 JS、sharp 及 Windows 原生库、许可证和源码指纹，不携带 npm、Python 或 Hermes。
3. 仓库根执行 `python scripts/package_harness_runtime.py --node <Node目录>/node.exe`。生成 ZIP、manifest 并更新 Java 安装器哈希。脚本拒绝源码已变化而尚未重建的 bundle。
4. `gradlew.bat test --tests "com.rinsing.geomantia.systems.provider.application.*" jar reobfJar`。Gradle 也拒绝与当前源码不符的运行时；ZIP 和 manifest 随仓库保存，普通 Java 构建无需 Node/npm/Python。

## 验证

- 在 `geomantia_harness` 使用 Node 22.23.2：`node --test test/*.test.mjs`；环境变量 `GEOMANTIA_HARNESS_ENTRY=dist/main.mjs` 可测 bundle。
- `HarnessAgentClientTest` 必须从 JAR 资源解压运行时并实际调用本地 HTTP Provider、往返宿主工具和图片，防止构建机 node_modules 掩盖漏打包。
- 显式真实 Provider 验证：`node test/live-provider.mjs ../run/config/geomantia`，会读取该目录已保存的配置与密钥，发送两次小型视觉请求；不加载或修改游戏世界。随机色块答案仅存在测试侧，模型必须从初始图和工具返回图识别，报告保留在输出的临时目录。

首次启动会按版本与哈希解压到实例 `config/geomantia/runtime/harness-0.1.5-rc.2-win-x64/`，无需玩家额外安装依赖。正在运行的旧游戏需要重启；已有 Hermes 安装和旧审计记录不会被自动删除。
