# 学院用户试玩验收 · 2026-10-17

本页整理用户于2026-10-16晚至17日凌晨的观察，供验收agent定位问题。**不是验收通过声明，也未确认任何新根因。** [用户原始记录](用户原始记录.md)保留原话，11张截图在线链接实验附件，ZIP内使用相对路径。原始编辑稿仍在用户主工作区dev_docs/systems/city/archive/城市生成验收/任务记录.md。

## 复现环境

- 世界文件夹：Realmfolk_Behavior_Config；游戏中显示名：geometia_beta3_02（继承原实验世界名称）。
- 世界种子：1025860383；runId：provider_3d25631f_r19200；城市：city_jingchao_capital。
- 最后保存时间（北京时间）：2026-10-17T01:15:33.086000+08:00。镜潮学院独立副本；当前仍用村民外观，RFH1素材尚未接入。
- Minecraft 1.20.1 / Forge 47.4.23；Geomantia 0.2.0 / Realmfolk 0.4.0。代码基线369d2a4a；模组文件名及SHA-256见包内mod-manifest.json。
- 截图坐标从用户提供的F3截图转写，采用Block整数坐标。没有坐标的条目明确留空，不能从推测补齐。

## 问题索引

**2026-10-17 诊断已完成**，逐项根因、证据与改法见[诊断结论](诊断结论.md)；修复排序见文档仓路线图“0.3.0 用户试玩修复”。

生活感原文包含三个问题，分别编号；其余按原分组。请先填写“实际结果、期望结果、触发条件、是否复现、最早偏离阶段、证据引用”，再提出解法。

| ID | 观察 | 性质 | 位置 | 验收重点 |
| --- | --- | --- | --- | --- |
| G01 | 除草范围不完整 | 待复现现象 | -3112 69 5167 | 查绿化区域边界、清理掩码、落地顺序；整区清理是用户建议，尚非已确认根因。 |
| G02 | 绿化和花坛观感简陋 | 美术/配置评估 | 未提供 | 区分素材轮廓、组合密度、布置方式与地形适配。 |
| G03 | 绿化区是否保留原地形 | 设计取舍 | 未提供 | 比较顺应原地形和整平的视觉与通行效果。 |
| R01 | 道路出现奇怪下坡 | 待复现现象 | -3136 84 5038 | 先查真实高程、道路衔接、台地边界，不能先认定台地bug。 |
| R02 | 默认道路风格违和 | 配置/设计评估 | 未提供 | 检查已提交道路选择、目录默认与降级记录；道路分级是待讨论解法。 |
| R03 | 道路落在坑里 | 证据待补 | 未提供 | 原文没有截图/坐标；现场定位前不要宣称复现或根因。 |
| C01 | 城市整体审美 | 设计评估 | 原文有两张全景 | 评估空间比例、轮廓、留白、绿化与道路的一致性。 |
| C02 | 广场疑似被水截断 | 待复现现象 | -2722 65 5165 | 核对广场用途范围、实际水面/地形与最终落地记录。 |
| C03 | 城墙是否必要且突兀 | 设计取舍 | 未提供 | 核对设计意图与城市功能，区分有墙是否合理和墙体落地错误。 |
| L01 | 人口密度偏低 | 配置/设计评估 | 全城 | 统计真实可达床位、入住率、岗位与生成缺口，再讨论目标人口。 |
| L02 | 教所/行政楼等建筑缺少工作人员 | 待核实配置 | 未提供具体建筑ID | 核对批准用途、实际工位、职业安排、作息时间与设施可达性。 |
| L03 | 日常活动与路线不够丰富 | 行为/设计评估 | 全城 | 区分已有食堂等活动未观察到、设施不足和尚未实现的洗衣等活动。 |

## 资料包内容与边界

本轮首批修复对应路线图第 1～3 项：村路外观只作小聚落的缺省并保留显式材质；D4 内院元数据传递至 D6 和户外规划；绿化各类预留配额并公开缺省、预算压缩及空间不足。修复在 `codex/campus-playtest-fixes` 分支，验证明细见实现仓 City 故障索引。

本批尚未游戏验收，G02 的素材与构图、C02 的水体与岸线问题仍未关闭；人口、道路横坡、城墙及其他修复继续按路线图排期。上述下载包保留原始故障现场，不是修复后的验收包。

[实验资料下载](https://github.com/RinsingEL/StructureBinder/releases/tag/experiments/campus-20261017) · [上传清单](upload-manifest.json)；每个ZIP内的package-manifest.json提供文件清单和哈希。两个ZIP解压到同一新目录。

- campus-save-20261017.zip：当前世界实际存档，省略realm_debug和session.lock；保留区块、实体、人口数据、玩家数据、生成结构、掩码及世界配置。
- campus-workflow-20261017.zip：本城完整city_test_runs（D3、blueprint及历史提交、D4至D7、land_use、walls）、国度/城市相关上下文、测试配置、Studio输入素材包、关键MCP调用记录、既有实测记录、用户截图和本项目两个JAR。除本项目JAR外，第三方Mod按manifest另行安装。
- 本次精简未包含全世界world_patch_map/world_feature_grid、全量survey tiles与其他城市/实验副本。因此足以供当前城市现场与已有提交分析，不宣称可以从零重放全世界规划。
- 没有上传本机API令牌、运行时凭据、启动参数、账号缓存或外部服务连接配置。原始存档和流程文件仍留本机，包内manifest记录每文件SHA-256及省略范围。

## 读取顺序与复现

先读用户原文与上表，再查game/saves/Realmfolk_Behavior_Config/realm_debug/provider_3d25631f_r19200/city_test_runs/city_jingchao_capital/steps。设计选择看blueprint和land_use，落地差异看d4/d5/d6/d7/walls；生活感同时查config/realmfolk和world/data/realmfolk_society.dat。

准备Minecraft 1.20.1 / Forge 47.4.23，安装mod-manifest列出的第三方模组，将随包本项目JAR放入game/mods。在对应代码基线用addons/realmfolk/scripts/launch-playtest.ps1指定GameDirectory为解压目录/game、World为Realmfolk_Behavior_Config、PlanningSourceDirectory为解压目录/planning_source，VersionDirectory指定本机Forge实例。首次复现用独立副本。

验收agent先按项目入口读取适用规则，分别记录bug、素材问题、配置缺口和设计取舍。未实测不写“已复现”；用户猜测不当作结论；新增动作/人口方案不借此自动列为本版已授权实现。

## 已确认的本机副本清理 · 2026-10-17

用户审阅清单后确认删除第一批5份旧检查点，共10028494728字节（约9.34GiB）。这些目录未被Git跟踪，本提交保存[删除回执与保留对象哈希](world-cleanup.json)。

- run/realmfolk_03_playtest/saves/Realmfolk_Natural03
- run/campus_d7_playtest/saves/Realmfolk_Campus_D7
- run/campus_d7_playtest/saves/Realmfolk_Campus_D7_Final
- run/campus_d7_playtest/saves/Realmfolk_Campus03
- run/realmfolk_behavior_playtest/saves/Realmfolk_Behavior_Config_Round1

当前验收世界Realmfolk_Behavior_Config、Campus03_Updated、原始D4基线及自然城市FullPack等23个保留目录仍在，30份关键元数据哈希未变；第二批14份世界未删除。上述5份旧现场不能通过本页195MiB精简包完整恢复。当前世界与线上附件不受此次清理影响。
