# Hermes 便携运行时

当前随模组发布 `hermes-runtime-0.21.3-win-x64.zip`，上游发布 `v2026.9.14`，版本、来源与源码校验见 `manifest-0.21.3-win-x64.json`。已经确认主请求与辅助请求均使用上游 `agent/opencode_affinity.py` 的会话亲和头。宿主 Java 探针与直接调用由 `ProviderRequestHeaders` 实现相同协议；只对 `opencode.ai` 附加会话头，不伪装其他客户端。

新版采用上游源码目录布局 `hermes/`，依赖独立放在 `site-packages/`。上游已停止支持普通 wheel 分发，因此不修改或绕过其 wheel 构建限制。`HermesAgentClient.pythonPath` 同时加入这两个目录及 Windows DLL 路径。Python 3.12.10 与 Node 沿用旧便携包；不沿用旧 Hermes 源码或旧 Python 依赖。旧 zip 保留作构建输入，Gradle 只打包新版。

## 重建

1. 从官方 `https://api.github.com/repos/NousResearch/hermes-agent/zipball/v2026.9.14` 下载源码。核对 manifest 中的 SHA256；本次所带源码文件还逐一比对官方 Git tree blob SHA1（Git archive 中的 CRLF 按 LF 归一化后比对）。
2. 在新的隔离工作目录建 `hermes-0.21.3-win-x64/`，从旧 zip 只取 `python/` 和 `node/`。不要操作游戏当前运行时。
3. 用 Windows x64 Python 3.12 执行 `python -m pip install --only-binary=:all: --target <工作目录>/site-packages -r runtime/hermes/requirements-0.21.3-win-x64.lock`。锁文件记录此次实际依赖集合，包括上游 core、mcp、web extras，以及原生 API Server 必需的 aiohttp（仅安装前三者不会带入该依赖）。
4. 源码放到 `hermes/`：保留根目录文件与 acp_adapter、agent、cron、gateway、hermes_cli、tui_gateway、tools、providers、plugins、locales、skills、optional-skills、optional-mcps、plugin-catalog、scripts、assets、native 目录。不要将用户 profile、密钥或会话打包。复制 manifest 为运行时根的 `geomantia-runtime.json`。
5. 执行 `python scripts/package_hermes_runtime.py <工作目录> runtime/hermes/hermes-runtime-0.21.3-win-x64.zip`，更新 `HermesPortableRuntime.ARCHIVE_SHA256`。此脚本使用固定 ZIP 时间戳，跳过缓存与包含构建机路径的 pip 命令包装器，并校验 ZIP 完整性。
6. 执行 `gradlew.bat test --tests "com.rinsing.geomantia.systems.provider.application.*"`，再执行 `gradlew.bat jar reobfJar`。运行时测试必须实际启动 CLI Gateway，在 30 秒内通过健康检查及认证后的会话创建、读取；仅导入模块不足以证明 API Server 可用。检查最终 JAR 只含一个 Hermes 运行时。

运行时按版本目录安装，新版不会覆盖旧版目录；正在运行的旧游戏实例须在换用新 JAR 后重新启动才会加载新版。升级不改变 Go URL、API 协议或用户密钥。
