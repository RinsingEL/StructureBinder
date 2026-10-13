# 附属模组

独立产出 JAR 的附属模组与作者资料放在这里，跟随 StructureBinder 仓库提交和推送。构建方式按下表区分；构建 JAR 不代表自动安装或启用玩法。

| 目录 | 模组 | 内容与构建方式 |
| --- | --- | --- |
| [realmfolk](realmfolk/README.md) | 国度 NPC / 冒险附属 | 居民、经济、交互、委托与第四题离线实验；在该目录独立 Gradle 构建。 |
| [adventure](adventure/README.md) | 冒险内容分布附属 | 大陆档位、怪物/结构池、居民区避让与地点记录；主仓 `adventureJar` 单独打包。 |

既有 Harness 与地图附属仍分别由 `src/harness`、`src/map` 构建为 `-harness.jar`、`-map.jar`。冒险包为 `build/libs/geomantia-0.1.0-adventure.jar`，依赖主包，不依赖这些 UI 附属或 Realmfolk。主包提供公共接口，不包含冒险实现或默认内容池。

`-PgeomantiaDevAdventure=true` 单独启用冒险附属；默认 false。`-PgeomantiaDevAddons=true` 加载主构建管理的 Harness、地图、冒险三个附属；已有 `geomantiaDevUseMap` 地图开关保留。Realmfolk 仍通过独立项目/安装自己的 JAR 使用。

Realmfolk 于 2026-10-14 从本地独立仓库导入，来源提交 `e9ca1de31551cf2650e52de9e3c3cb57367ac8e5`（实验实现提交 `d4848a9`）。导入的是 199 个已提交文件，不包含嵌套 `.git`、凭据、运行存档或依赖缓存。原目录 `E:\Mod\_Dev\国度冒险附属Mod` 保留作迁移来源；后续源码在本目录维护。旧记录里的哈希与绝对路径按历史来源保留。

本机构建、实验进度与可选试玩实例仍使用 `addons/.realmfolk*/`，已忽略，不随源码发布。原运行存档没有迁移；旧试玩批处理需要先准备独立实例。
