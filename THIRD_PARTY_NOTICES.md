# Geomantia 第三方内容与发布范围

核对日期：2026-09-09。Geomantia 自有代码采用 GPL-3.0-or-later，第三方代码、运行时和素材保留各自许可。本文件是来源清单，不替代许可全文，也不表示尚待核实的内容已经取得授权。

## 建筑模板

当前测试内容清单包含 Trek 50 个模板、Stubbs 15 个模板。发布时应按实际随附文件重新核对。

| 来源 | 作者 | 许可及处理 |
| --- | --- | --- |
| [Trek](https://modrinth.com/datapack/trek) | hotsuop、Hugger、ALDgamer | MIT；保留 Copyright 2024 hotsuop 及完整许可。当前本地 trek_v3 素材的原始下载版本还待补齐。 |
| [Stubbs Medieval Building Bundle](https://www.planetminecraft.com/project/medieval-nordic-house-bundle-free-to-use/) | Stubbs1、Tom | 作者页面允许署名使用；随素材提供作者和原项目链接。不要把作者建筑标为 Geomantia 原创。 |

Trek 许可：https://raw.githubusercontent.com/hotsu0p/Trek-Issues/main/license

推荐视频说明：Geomantia 负责 AI 城市规划、模板组合及道路和台地生成；建筑模板来自 Trek（hotsuop、Hugger、ALDgamer）以及 Stubbs Medieval Building Bundle（Stubbs1、Tom），原作品链接见上表。

## 测试环境中的独立 Mod

这些是测试环境来源记录，不表示它们会随 Geomantia 发布。仓库 libs/local 内的第三方 JAR 也属于再分发内容，不能因为不是最终整合包就忽略其许可。

| 项目 | 作者 | 本地版本许可核对 |
| --- | --- | --- |
| [ReTerraForged](https://github.com/racoonman2/ReTerraForged) | racoonman2；上游 dags、Won-Ton | MIT，保留版权和许可。 |
| [Distant Horizons](https://modrinth.com/mod/distanthorizons) | James Seibel 等贡献者 | LGPL-3.0，保留许可并提供对应源码获取方式。 |
| [Cristel Lib](https://www.curseforge.com/minecraft/mc-mods/cristel-lib) | Cristelknight | 1.1.6：CC BY-NC-ND 4.0；包内允许署名整合包使用，除配置外不修改，并遵守其余条款。 |
| [Towns and Towers](https://www.curseforge.com/minecraft/mc-mods/towns-and-towers) | Kubek、Biban_Auriu、Cristelknight | 1.12 元数据 NC-SA、包内 LICENSE NC-ND，当前官网 CC BY 4.0，存在版本声明差异；官网 FAQ 允许署名整合包使用，不能据此认定旧版提取改编授权。 |
| [Library Ferret](https://www.curseforge.com/minecraft/mc-mods/library-ferret-forge) | JTorLeon Studios | 4.0.0：All Rights Reserved，直接重新分发授权待确认。 |
| [JourneyMap](https://journeymap.readthedocs.io/en/latest/about/licensing/) | Techbrew、Mysticdrew | 不随本 Mod 发布；不可直接捆绑客户端 JAR 重新分发，整合包应按官方许可从认可来源下载。 |

## 发布前尚待完成

- 补齐 Trek 模板原始版本溯源及随包完整许可证。
- 核对最终 JAR/配套素材包的真实内容，不将测试依赖自动纳入发布。
- 核对内置 Hermes、Node/Python 运行时及依赖的完整许可清单。
- 自有发布产物附 GPL 完整文本与对应版本源码；LICENSE.txt 是 Forge MDK 第三方声明，不能代替自有 GPL 文本。
- 仓库另有 bettervillageforge、villager_fish_shop、voxy 本地依赖，本次尚未审查，不能将此清单视作整个仓库的版权放行。
