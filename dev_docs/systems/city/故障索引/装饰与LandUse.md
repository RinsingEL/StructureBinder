# 装饰与 LandUse 故障索引

| ID | 状态 | 现象与根因摘要 | 记录 |
| --- | --- | --- | --- |
| `CITY-DL-20260722-01` | 已定位，待修复 | LandUse 当前仍把 owner 内 BASE/CROP/BOUNDARY 作为同一事务，任一局部 block 操作不可落地都可能回滚其他正常操作。需按 block 或依赖小组继续缩小事务，并区分局部内容冲突与系统错误。 | `CityLandUseChunkCompilerTest`、`CityLandUseChunkExecutorTest`、`CityLandUseWorldgenRegistryTest`；[故障修复案](../../../../../designer_territoryMod/docs/systems/city/40_tests/故障修复案/20260722_LandUseOwner事务失败粒度过大.md) |
| `CITY-DL-20260723-01` | 已修复，待本次新区块重规划复验 | `StableLandUseExpander` 以 `groupId` 汇总并封顶全部 claims；阵列内多个建筑及其不连通碎片共同消耗一份 `maxAreaBlocks`，使功能组到达总上限后仍无法完成近邻连接。现由每个 D6 anchor 按自身 locked footprint 创建 growth region，独立执行 `min/preferred/max`，group 只汇总额度并保留竞争、融合语义。 | `LandUseAutoConnectionPlannerTest`、`LandUsePlanningServiceTest`；[本次任务记录](../active/20260722_新存档河谷大城镇落地/任务记录.md) |
| `CITY-DL-20260723-02` | 已修复，待本次新区块实机复验 | `CONTOUR_BANDS` 将水槽连接器视为全局唯一占位，多个对角缺口复用同一连接格时会把后续 WATER 错误降级为 CROP，造成预览中水槽碎裂。现允许拓扑必要的连接格复用，并以最终四邻接连通分量决定 fallback。 | `ContourBandSurfaceClassifierTest`、`LandUsePlanningServiceTest`；[本次任务记录](../active/20260722_新存档河谷大城镇落地/任务记录.md) |
| `CITY-DL-20260720-01` | 已修复，待重启后新区块实机复验 | Decoration 连续 prefab 的 lined straight `3×1` 与 lined end-cap `3×2` 原本无法建立 terrain-drop fallback，loader 只接受 `1×1 -> 1×1`。现允许保持一个水平横截面、只沿 run 轴加长的 fallback。该结论不适用于已改为直接 block mask 的 LandUse。 | `CityDecorationContentCatalogLoaderTest`、`CityDecorationTerrainRunCompilerTest`、`CityDecorationChunkCompilerTest`；[本案任务记录](../active/20260720_W结果大城镇设计落地/任务记录.md) |
| `CITY-DL-20260719-02` | 已修复，待新区块实机复验 | 第一次修复只在写当前格前求邻居状态并使用 `UPDATE_ALL`；真实 `WorldGenRegion` 忽略 flags，后写栅栏不会反向刷新先写栅栏，现由执行器显式重算当前格和四个水平连接邻居，跨 chunk 邻居仍限制在 FEATURES 的 write radius 1 内，刷新失败恢复本次状态。 | `CityLandUseChunkExecutorTest` 的无通知 / x=15..16 / 失败恢复回归、`CityLandUseGameTests` 的 ServerLevel 冒烟；[本轮任务记录](../active/20260720_LandUse栅栏WorldGenRegion连接修复/任务记录.md) |
| `CITY-DL-20260719-01` | 待定位 | 新港口样城冻结的坡地农田中，43 个 slot 在 worldgen ledger 以 `CITY_DECORATION_FOUNDATION_NOT_MATERIALIZED` 跳过；其余农田 / 水渠仍应用 229 个，不能把规划槽位数直接当成实际装饰数。 | [生活感样城任务记录](../active/20260719_建筑群生活感设计/任务记录.md) |
| `CITY-DL-20260713-01` | 已修复，待新区块实机复验 | D5 曾把 D3 patch 外包框当作装饰投影范围，造成过宽禁植且未保护普通 / 模组结构。 | `CityPlanningEndpointHandlerTest`、`CityDecorationWorldgenRegistryTest`；[历史明细](./历史明细.md) |
| `CITY-DL-20260713-02` | 已修复 | 旧 Decoration 水渠 tile 未检查相邻坡降会外流，作物会受随机刻与漏水影响；后续增加坡降 fallback。该路径不是当前 LandUse 水槽实现。 | `CityDecorationChunkCompilerTest`；[历史明细](./历史明细.md) |
| `CITY-DL-20260713-03` | 已修复 | 嵌套 configured feature 可在同一 owner 重复投影完整 field，导致传送等待超时。 | `CityDecorationWorldgenRegistryTest`；[历史明细](./历史明细.md) |
| `CITY-DL-20260713-04` | 已修复，待实机复验 | 每个 fragment 都重写完整 JSON ledger，Windows 原子替换失败会中断 worldgen。 | `CityDecorationWorldgenRegistryTest`；[历史明细](./历史明细.md) |
| `CITY-DL-20260713-05` | 已修复，待实机复验 | `cross_section_repeat` 错用世界相位，修正后又跨 `WorldGenRegion` 写相邻 chunk。 | 装饰四项核心测试；[历史明细](./历史明细.md) |
| `CITY-DL-20260718-01` | 已修复，待新区块实机复验 | bundled 与运行期 LandUse profile 漏配 TerraSense `function.矿业`，矿业锚点被当作未知语义跳过。补充 `industry` 规则及 canonical term，重跑 LandUse 后应产出工业 area。 | `LandUseRuleCatalogLoaderTest`、落地测试矿业城 workflow |
