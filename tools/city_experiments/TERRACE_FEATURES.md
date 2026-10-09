# 通用核心台地与设施试验

魔法学院只是一套测试数据。规划器不读取学院名称、用途词或 MG 编号；普通城镇使用相同的道路、台地和设施规划代码。风格配置决定材质及构件选择，台地实际几何决定能否放置。

## 工作台使用

打开 [本机工作台](http://127.0.0.1:8767/?district=teaching)，选择“试验方案”后点击“载入并生成”。提供学院和普通城镇两套实例。

“设施风格”可将当前全部台地切换为石砌边缘或木构边缘，点击“生成并查看”生效；刷新后选择框返回“各台地原配置”，表示沿用方案中的各组 styleRef，而非恢复旧风格。“台地边缘与公共设施”可关闭设施，建筑位置和台地范围保留。完整方案编辑器可分别配置各台地。旧版无风格方案继续可用。

## 实验方案字段

原有 `districts` 负责展示分区和交通关系。可选 `terraceGroups` 将一个分区拆成多个核心台地组：

```json
{
  "terraceId": "study-platform",
  "districtId": "teaching",
  "coreMemberId": "teaching-research:1",
  "arrayIds": ["teaching-research", "teaching-class-west", "teaching-class-east"],
  "styleRef": "stone_civic",
  "datumOffsetBlocks": 4
}
```

台地组必须包含一个实际 CORE 成员；叶阵列在所属分区内只能归属一个台地组，台地分区的叶阵列不能漏掉。自然地形分区不创建台地组。缺省时兼容旧方案，每个 platform 分区形成一个核心台地组。嵌套组合负责摆位，嵌套深度不会自动产生台地。

高程由核心占地采样确定；`datumOffsetBlocks` 可选、默认 0，范围 -16～16，用于试验组与组之间的高度关系。核心与配套共同使用该组高程；核心不必最高。台地重叠按距离所属实际建筑占地的远近裁决，不得切掉所属建筑。`foundationMarginBlocks` 默认 4，试验范围 4～12；两套示范使用 8，为边缘构件留出承载空间。

`terraceStyles` 保存具名配置；`styleId` 必需，`name` 为显示名。可选字段：

| 字段 | 用途 |
| --- | --- |
| railBlock / postBlock / capBlock | 边缘护栏、节奏立柱和柱帽；允许省略 |
| postSpacing | 立柱间距，默认 9 |
| minimumExposure | 相对外部地面的最小露出高度，默认 1；同高相邻台地不产生内部护栏 |
| cornerAssetId / courtAssetId | 程序消费的转角与公共空间构件；允许省略 |
| maximumCorners / cornerSpacing | 转角构件限额及最小间距，默认 4 / 24；限额不是必须达到的数量 |
| materials | 已有 CitySurfaceMaterials 平面槽位，例如 ground、retainingWall、wallColumn、wallCap、wallBand；不新增主题判断 |

`terraceFeatures=false` 关闭本轮新增护栏、立柱及构件，不关闭已有挡土、道路和路灯。

## 几何和交通规则

`CityTerraceFeaturePlanner` 消费已经确定的台地掩码、高程、建筑占地和道路范围。转角候选必须完整容纳旋转后的模板包围盒，保持与建筑一格、道路两格的净空；按候选之间距离分散选择。公共空间构件只放在同一台地上足够大的空区，没有位置则省略。护栏沿实际外缘生成，为道路和入口留出开口。构件不能反过来扩大台地，也不能被当成阵列建筑。

`CityTerraceRoadGradePlanner` 在存在不同高程相邻台地时，对包含该边界的完整连通道路图处理高程，保留实际入口接驳高度，限制切填 12 格和每格至多一级。短路段边界不作为额外固定高度。楼梯方块与路面一起冻结后进入原 ChunkCompiler/ChunkExecutor。长度不足时拒绝生成，不移动建筑入口。挡土施工不重建高于冻结道路面的墙体；离线路面半砖状态与 Java 的 double 保持一致。

实际 NBT 构件在 Python 合成时再次核对哈希、承载及道路/建筑净空。元信息包含 `fixtures`、`fixtureChecks`、`terraceFeaturePlans`；每轮保存完整方案、SurfacePrint 和实际方块世界。

## 新增素材

| 素材 | 宽×深×高 | 程序用途 |
| --- | --- | --- |
| MG-41 石砌小角塔 | 7×7×26 | 边缘转角 |
| MG-42 晶簇庭院标志 | 9×9×11 | 公共空区 |
| EU-25 木构角亭 | 7×7×15 | 同一转角用途的另一套风格 |
| EU-26 石井遮棚 | 9×9×12 | 同一公共空区用途的另一套风格 |

源码为 `tools/structure_studio/studio/terrace_fixtures.py`；通过 `studio.build` 按编号重建。作者显式声明 `placement_consumer=infrastructure`、地面基准和入口。素材保持 draft，离线图审与实机验收分别记录。

## 验证与边界

Java 测试覆盖承载、净空、候选分布、确定性、同高接缝和不可行高差；工作台回归使用实际 Java/NBT 构建两套主题，检查失败发布保护及真实碰撞方块的单入口跨台地步行。浏览器脚本 `capture-terrace-features.mjs` 实际载入两套方案、切换风格和关闭设施，比较建筑坐标与台地掩码，并保存预览。

新设施与核心台地组契约当前接入网页实验。正式游戏入口尚未调用这套设施规划；边缘护栏写入和 NBT 构件合成为离线适配。道路高程和地表通过原区块执行器处理，但虚拟世界仍不模拟 Minecraft 流体、光照或 tick，`generationReady=false`。自动连接高层建筑端口、桥梁与码头消费未包含在本轮试验中。
