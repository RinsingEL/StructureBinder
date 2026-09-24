# 可选附属

构建：`gradlew.bat assemble verifyAddonPackaging`。

| 发布包 | 安装用途 |
| --- | --- |
| `build/libs/geomantia-0.1.0.jar` | 必装主 Mod：城市生成、workflow、区域保护与统一 MCP。 |
| `build/libs/geomantia-0.1.0-harness.jar` | 可选规划助手：游戏内配置模型服务，暂停菜单打开“城市规划助手”。 |
| `build/libs/geomantia-0.1.0-map.jar` | 可选冒险地图：地图物品、轮廓、迷雾、国度及位置标记。 |

两个附属分别依赖主 Mod，不互相依赖。服务器与客户端使用地图/Harness 时应安装对应附属。主包的世界生成与区域进入限制不因地图未安装而停用。

Harness 使用主 Mod 拥有的规划服务；移除 Harness 不会移除存档内的规划进度。外部 Agent 通过主 Mod 的单一 `/mcp` 地址接入，包括已登记的附属规划任务。模型服务凭证仅属于 Harness，主 Mod 不要求配置模型。

首次使用外部 MCP 时在主菜单选择端口并保存，之后自动启动；默认 5001。安装 Harness 时不主动弹出外部连接配置，仍可通过主 Mod 设置开启。Harness 的普通玩家只需进入世界、打开规划助手并配置模型。

开发客户端默认只加载主 Mod；同时加载附属使用 `-PgeomantiaDevAddons=true`。主包和附属使用不同 Java 包，避免 Forge 模块层 split-package 冲突。

验证入口：`test`、`coreMcpTest`（移除两个附属的 classpath 后运行真实 MCP 初始化）、`verifyAddonPackaging`（主包无附属类依赖、无 Harness 运行资源、无地图配方，附属无重复 Mixin）。区域 GameTest 需新的隔离目录和 `geomantia_regions` 命名空间，分别以 `geomantiaDevAddons=false/true` 验证。
