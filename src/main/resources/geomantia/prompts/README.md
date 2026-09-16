# Agent 提示词

修改本目录 UTF-8 文本即可，无需重新打包。首次启动补齐缺失文件，不覆盖已有文件。agent.md 与 providers/ 下的对应补充在模型请求时读取；city/ 阶段提示在返回阶段状态时读取，手册在准备城市上下文时读取。正在执行的请求不会中途更换提示词，既有会话历史也不会被改写。

- agent.md：共同原则。
- providers/：Hermes 与直连的运行方式差异。
- city/overview.md、district.md、integration.md、finalize.md、complete.md：各阶段当前任务。
- city/handbook.md：城市设计手册。

参数 schema、算法能力说明、规模数据、状态流转与验收硬门槛仍由代码管理。修改提示词不会取消代码校验。空文件、无法读取的文件会明确报错，不静默使用旧提示词。删除某个文件后下次读取会重建默认文件。

发布包内默认文本位于 src/main/resources/geomantia/prompts；游戏使用 config/geomantia/prompts。升级不覆盖个人修改，需要新默认时先备份再删除对应配置文件。
