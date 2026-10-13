# 冒险内容分布附属 · Geomantia Adventure

`geomantia_adventure` 是可选附属包，依赖主模组 Geomantia 0.1.0。源码、测试和作者配置已从 `codex/adventure-distribution` 旧工作树迁入当前主仓；它不依赖 Realmfolk、地图或 Harness，不自动安装到现有游戏实例。

## 作用

- 初始大陆及其近海为第一档；按大陆空间邻接层次升档，超过作者最高档时封顶，外海独立配置。
- 各档引用作者配置的怪物池与探索结构池，不自动增加实体血量或伤害。
- 自然刷怪和结构分布避让城区、郊区、村庄与道路规则；城市施工保护仍优先。
- 记录结构起点与生成完成状态，为后续委托查询提供输入。`complete` 表示相关区块生成完成，不表示玩家已清剿；尚未接通 Realmfolk 委托。

## 构建与启用

在 StructureBinder 根目录执行（Java 17）：

```text
gradlew.bat adventureTest verifyAdventurePackaging -PgeomantiaDevUseReTerraForged=false -PgeomantiaDevUseStructurePacks=false
```

输出 `build/libs/geomantia-0.1.0-adventure.jar`，需配套本次公开区域接口的主包。只安装主包时不加载冒险规则；开发环境默认 `geomantiaDevAdventure=false`，仅显式加 `-PgeomantiaDevAdventure=true` 或 `-PgeomantiaDevAddons=true` 才加载。

实现位于 `addons/adventure/src/main/`，测试位于 `addons/adventure/src/test/`。使用主仓 Gradle 的独立 source set 与 Jar 任务，没有复制独立 wrapper 或把附属实现打进主 JAR。

## 作者配置

首次启用在实例 `config/geomantia_adventure/` 下补齐五份 JSON：

| 文件 | 配置内容 |
| --- | --- |
| distribution.json | 档位陆地/近海与外海的池引用。 |
| mob_pools.json | 实体 ID、权重、成群数量、条件与频率约束。 |
| structure_pools.json | 结构 ID、权重、条件、密度与间距。 |
| settlement_rules.json | 人烟区、安全缓冲与允许名单。 |
| generation_control.json | 哪些内容归附属管理、是否保留原有生成。 |

默认文件位于 `src/main/resources/adventure_defaults/`，仅使用原版内容，三档仍是同一组示例池；需要作者配置不同池才能体现内容差异。错误配置或不存在的注册 ID 会拒绝初始化；改配置后重启实例。

[探索整合试配](profiles/exploration-trial/README.md)包含第三方模组候选，只做过离线资源核对与配置测试，未完成联合启动、实际战斗与强度验收，不作为默认配置安装。

## 对主功能的影响边界

主模组新增地理就绪与结构分布事件，以及只读地理、原子占地预留 API。城市原有结构保护先执行；没有安装附属时，无冒险监听器参与分档、选池或刷怪。启用附属后，受管范围内的怪物与结构分布会按作者规则改变，这是该包的职责。

现有区块不回填；未就绪、扫描范围外或非受管维度不追加冒险内容。仍遵守原生群系、亮度和碰撞等约束，不能保证游荡怪物永远不进入居民区。第三方自定义粘贴/脚本生成器、未来程序村庄生成器、完整 NPC 清剿任务不在当前实现范围。

## 验证入口

- `adventureTest`：分档、配置、候选间距、地点存档、占地竞争及探索试配。
- `coreMcpTest`：移除 Harness、地图与冒险的 classpath 后验证主 MCP 可用。
- `verifyAddonPackaging verifyAdventurePackaging`：主包不依赖附属类，无 split-package，无重复 Mixin，冒险配置只在独立包中。
- Forge 事件验收使用全新隔离目录。先执行 `python addons/adventure/prepare_gametest.py --mode adventure --directory build/adventure-gametest`，再执行 `gradlew.bat runGameTestServer -PgeomantiaDevAdventure=true -PgeomantiaDevAddons=false -PgeomantiaDevUseMap=false -PgeomantiaGameTestNamespaces=geomantia_adventure -PgeomantiaGameTestDir=build/adventure-gametest -PgeomantiaDevUseReTerraForged=false -PgeomantiaDevUseStructurePacks=false`。测试准备关闭在线模型和 MCP；复验请换新目录名。
- 主包单独验收先以 `--mode core --directory build/adventure-core-gametest` 准备，再运行相同命令，改为 `geomantiaDevAdventure=false`、`geomantiaGameTestNamespaces=geomantia_regions` 及对应目录。保留正常生成保护，只将临时测试世界的初始活动区固定在 GameTest 原点附近，避免随机出生位置使测试场地落在禁区。

以上自动化检查不等同于用户现有整合包的游戏验收。本次合入不修改用户存档，也不自动部署 JAR。
