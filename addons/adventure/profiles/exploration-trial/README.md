# 探索模组首轮试配

状态：离线资源核对与配置解析试验。尚未通过六模组联合启动、实际生成或战斗验收，不能视为正式整合包配置。

## 试配内容

| 区域 | 首轮配置 |
| --- | --- |
| 一档陆地 | 原版基础怪物；WDA 小型池全部六项，保留原权重 |
| 二档陆地 | 增加低概率单只突变僵尸/骷髅；WDA 大型池权重 2～3 的五项陆地结构 |
| 三档陆地 | 增加 WDA 大型池权重 1 的二十一项主世界陆地结构；保留 Mowzie 地点和灾变金字塔 |
| 一档近海 | 小游艇 |
| 二档近海 | 增加七海海盗船和 WDA 权重 2 的两项海洋结构 |
| 三档近海 | 增加 WDA 权重 1 的两项海洋结构；大型舰船、符合原生群系要求的 Aquamirae 地点 |
| 独立外海 | WDA 四项海洋结构、大型舰船、Aquamirae 地点、灾变沉没之城 |

这些档位是试验假设，未实测强度。“轻量”不是安全保证，结构内守卫、刷怪笼与战利品保持第三方原设定。

## 文件和安装边界

真正供附属读取的配置仍只有五张表：distribution.json、mob_pools.json、structure_pools.json、settlement_rules.json、generation_control.json。mods.lock.json 和 structure-catalog.json 只是试验清单，不是运行时配置。

依赖及六个模组共 11 个 jar 已隔离下载至工作树 build/exploration-trial/mods，逐个校验上游 SHA-512。来源与校验值见 mods.lock.json。未复制到 PCL2 测试端。

只有完成主模组接口整合及联合验证后，才将五张表部署到附属的配置目录；不要把整个目录当成配置复制，也不要与默认表混用。

## 已核对与待验证

- 67 个结构 ID 来自 jar 内 data/<namespace>/worldgen/structure；已排除同名结构标签文件。试配实际选择其中 48 个结构。
- 怪物候选名称已核对资源翻译键，仍须启动时确认注册表与原生生成条件。
- Aquamirae 的 Modrinth 版本依赖声明为空，但 mods.toml 要求 Obscure API，已补充版本 18。
- Puzzles Access API 已内嵌在 Puzzles Lib，未重复下载。
- Seven Seas 上游所选文件名含 1.19.2，但版本 API 标注支持 Forge 1.20.1，jar 声明 Minecraft [1.19,1.21)。运行兼容性仍待启动验证。
- generation_control 在受管理地域内关闭六模组全部标准结构的原生随机生成，只允许所选结构池重新安排；主模组管理范围以外不接管。
- 未接管所有第三方怪物：目前只对选入池的两种突变怪接管原生自然刷新。Mowzie、Aquamirae、灾变的其他自然生物和特殊生成途径仍须审计，不能据此宣称低档区域已完全隔绝高档怪物。
- 结构原生群系和地形判定仍然生效；Aquamirae 冰海地点不保证在任意外海生成。
- 大型结构必须验证附属的结构尺寸限制、地形适配和城区避让；Boss 激活器、刷怪笼、结构内预置怪不走野外怪物池。
- 不新增 NPC 委托，不改 Realmfolk 数据。默认 settlement_rules 未额外补录测试存档的郊区。

## WDA 稀有度依据

原包 2.1.58：minor_structures 六项，spacing 35 / separation 25；major_structures 三十一项，spacing 60 / separation 50。前者进一档，后者按权重 2～3 / 1 分二档、三档。保留条目原权重。四项纯海洋结构归近海与独立外海池，岸边小屋、灯塔仍走陆地池及原生群系条件。

末地专属 aviary 不进入本轮主世界池；giant_mushroom、small_prairie_house 不在原包默认结构集中，未擅自启用。逐项来源见 wda-rarity-audit.json（审计资料，不是第六张运行时表）。

chance 保留试验值 0.35；多个分档池独立抽取、群系过滤和跨池避让都会改变实际密度，因此这套配置保留的是原包稀有度排序依据，不复刻原包遇见频率。小型池继续在二档出现，二档大型池继续在三档出现，三档稀有项不会提前进入一、二档。
