# StructureBinder

> 一个仍在探索和开发中的 Minecraft Forge / AI 协作式城市生成项目。

StructureBinder 的目标，是把 Minecraft 世界中的地形扫描、领土/城市规划、结构模板选择、Jigsaw 求解和运行时落地串成一条可被 AI 参与、可被程序校验、可持续调试的生成链路。

项目仍处在实验和重构阶段。当前仓库里的实现、接口、文档和调试产物会持续变化，很多能力不是“开箱即用的成品 Mod”，而是围绕 AI 规划城市、逐节点施工、运行时验证和失败回写逐步搭起来的研究型工程。

## 当前活跃入口

- GitHub 仓库：[RinsingEL/StructureBinder](https://github.com/RinsingEL/StructureBinder)
- 当前活跃开发分支：[codex/rebuild_AIBase](https://github.com/RinsingEL/StructureBinder/tree/codex/rebuild_AIBase)

如果你是从默认分支或旧提交进入项目，请优先切到 `codex/rebuild_AIBase`。这个分支承载当前正在推进的 AIBase rebuild 方向，也是现阶段最接近实际开发状态的入口。

## 项目在做什么

StructureBinder 当前围绕 `terra_script` Mod 与配套 MCP 工具展开，核心方向包括：

- 扫描 Minecraft 世界地形、海陆、坡度、粗糙度、生物群系等环境信息。
- 基于领土、城市中心点和地形窗口生成城市规划输入。
- 让 AI 参与城市 C 阶段规划，例如城市边界、主干道路、功能区和结构意图。
- 将城市主模块推进到 `C7 -> C8 -> C9`：
  - `C7`：生成工头计划和阶段任务。
  - `C8`：维护节点施工会话，接收 AI 节点决策，执行校验与重试。
  - `C8 Jigsaw`：基于原版 Jigsaw / pool / connector 语义求解 child 结构落点。
  - `C9`：消费已校验节点，进入实际建造、执行和回写。
- 输出调试 trace、预览图、队列数据和落地证据，方便继续复查和迭代。

简单说，它不是只“生成一座城”，而是在试着搭一套 AI 与 Minecraft 运行时共同工作的城市生成管线。

## 仓库结构

主要目录如下：

| 路径 | 说明 |
| --- | --- |
| `src/main/java/com/user/terra_script` | Forge Mod 主实现，包含地形扫描、城市阶段、HTTP/MCP 接口、执行层等运行时代码。 |
| `country_designer_mcp` | MCP 工具层，负责把 AI 工具调用转发到 Minecraft 运行时接口。 |
| `code_process_viewer` | 开发过程和调试产物查看相关工具。 |
| `config` / `run` / `run-data` | 本地开发运行、数据生成和调试环境目录。 |
| `dev_docs` | 开发过程记录、阶段讨论、调试证据和提交归档，不作为当前功能真值。 |
| `docs` | 旧技能/辅助文档入口，当前主方案以独立文档仓库为准。 |

当前有效的产品方案、数据契约、代码导览和测试入口主要维护在配套文档仓库中；本仓库重点承载实现代码、运行时接口、MCP 工具和调试记录。

## 技术栈

- Minecraft Forge `1.20.1`
- Java `17`
- Gradle / ForgeGradle
- TypeScript MCP server
- JUnit 5
- 本地结构模板、Jigsaw / pool 元数据与运行时调试产物

## 开发状态说明

请把这个项目当作“正在搭建中的实验系统”来看待：

- 接口和数据契约还会继续调整。
- 部分流程需要本地 Minecraft 开发环境、存档、结构模板和配套数据才能完整运行。
- `dev_docs` 中可能保留历史讨论和阶段性结论，最终有效方案以当前文档仓库为准。
- 旧 README / Forge MDK 说明仍可能存在，用于保留环境搭建背景，但不代表项目当前目标。

## 本地开发参考

基础环境来自 Forge MDK。常用入口包括：

```bash
./gradlew genIntellijRuns
./gradlew runClient
./gradlew test
```

`runClient` 默认会进入开发运行目录中的 `run/saves/新的世界 (3)`，复用其中的测试数据。需要临时改用其他存档时，传入该存档名的 UTF-8 Base64：

```powershell
$name = '目标存档名'
$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($name))
.\gradlew.bat runClient "-PgeomantiaDevAutoLoadWorldBase64=$encoded"
```

MCP 工具层位于 `country_designer_mcp`：

```bash
cd country_designer_mcp
npm install
npm run build
npm start
```

实际联调通常需要先启动 Minecraft 开发客户端，让 `terra_script` 的本地 HTTP 接口可用，再由 MCP 工具调用对应的城市或世界阶段接口。

## 给新来的读者

如果你只是想了解项目当前方向，建议从这几个问题开始看：

1. 这个项目如何让 AI 参与 Minecraft 城市规划？
2. `C7 -> C8 -> C9` 如何把一个城市 group 从计划推进到可执行节点？
3. Jigsaw 求解器如何基于原版结构连接语义计算 child 结构落点？
4. 程序如何校验 AI 决策，并把失败、重试和调试证据写回？

这个仓库还在长线探索中。欢迎把它理解成一个“AI 城市生成实验室”：有能跑的代码，也有还在拆解和重建的系统边界。
