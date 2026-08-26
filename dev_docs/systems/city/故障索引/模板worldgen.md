# 模板 worldgen 故障索引

| ID | 状态 | 现象与根因摘要 | 记录 |
| --- | --- | --- | --- |
| `CITY-TW-20260825-01` | 已修复，资源校验通过 | 六份 `town_workshop_01.nbt` 的 3 株无效 `minecraft:grass` 已在原坐标替换为 `minecraft:moss_carpet`；其他 palette、尺寸和入口不变。六份内容哈希统一为 `sha256:ef75f79ef1695713216e5901cba3aaa8f8c1b95d01063a2a21e1a7984fa869ec`，两份正式 catalog 已同步。 | [本轮记录](../active/20260826_待修Bug集中修复/任务记录.md) |
| `CITY-TW-20260717-01` | 已修复，待新区域实机复验 | 非正方形模板旋转后，L3 把规范化 placement origin offset 错当成 `rotationPivot`，实际写入偏出 D6 footprint 并被 owner box 裁剪。现改为规范化 origin + ZERO pivot，12 组 rotation/mirror 逐点校验，runtime/locked/owner 漂移均 hard fail。 | [正式故障案](E:/Mod_Dev/designer_territoryMod/docs/systems/city/40_tests/故障修复案/20260717_旋转模板pivot与规划几何错位导致分片裁剪.md)；`CityTemplatePlacementGeometryTest`；`MinecraftCityTemplateWorldgenPlacerTest`；[历史明细](./历史明细.md) |
| `CITY-TW-20260717-02` | 已修复，待新区域实机复验 | 每个 owner 重查 anchor heightmap：远端 owner 在 anchor 仅到 `BIOMES` 时得到无效高度，首片写入后又可能把后续查询从 `109` 抬到 `110`。现改为首次 FEATURES durable pending、anchor owner 单次冻结真实 datum、所有 fragment 强制复用；仅有 pending proof 的 FULL chunk 可延迟重试。 | [正式故障案](E:/Mod_Dev/designer_territoryMod/docs/systems/city/40_tests/故障修复案/20260717_模板worldgen高度datum暂不可用.md)；`CityReservationMaskRegistryTemplateFragmentTest`；[历史明细](./历史明细.md) |
| `CITY-TW-20260715-01` | 部分修复，后续问题未解 | 已阻断“datum 缺失即回落 `Y=-64`”的灾难路径，并强制 datum policy / 记录实际 datum；但可靠 datum 的 worldgen 读取时机尚未解决，见 `CITY-TW-20260717-02`。 | [历史明细](./历史明细.md) |
| `CITY-TW-20260717-03` | 已修复，待实机持续复验 | 跨 chunk 模板只由 anchor owner 回调，局部 fragment 写完后没有聚合 completed ledger；现已按 locked footprint 派发全部 owner 并聚合。 | `CityReservationMaskRegistryTemplateFragmentTest`；[历史明细](./历史明细.md) |
| `CITY-TW-20260723-01` | 已定位，待修复 | D2/D4 可复用其他存档导出的 template catalog，但 D5 激活前未用当前存档 `StructureTemplateManager` 逐项校验 `templateRef + templateHash`；目标存档缺少 NBT 时仍会放行，首次 worldgen 才批量 `TEMPLATE_NOT_FOUND`。解决方向：D5 对全部唯一模板做当前世界实读与 hash 预检，缺失或漂移时整体拒绝激活。 | [本次任务记录](../active/20260722_新存档河谷大城镇落地/任务记录.md) |

## 新增准入

- 模板资源、变换、owner fragment、ledger 或实际落地范围的问题写入本文件。
- 若问题需要保留现场证据、修复方案和验收口径，同时新建文档仓库 `40_tests/故障修复案/`；本文件只保留可检索摘要。
