# 道路与 RoadWeaver 故障索引

| ID | 状态 | 现象与根因摘要 | 记录 |
| --- | --- | --- | --- |
| `CITY-RW-20260720-02` | 已修复，待重启后预览与新区块实机复验 | Decoration 曾把尚未生成的 RoadWeaver 端点连线整个对角 bbox 扩 4 格作为硬障碍，斜线会膨胀成大矩形并抹掉广场中心。现只保护 transformed endpoint 周围 `±4` gateway，不再猜测真实道路 corridor。 | `CityPlanningEndpointHandlerTest.handlePlanCityDressingWritesV04ArtifactsAndAvoidsHardObstacles`；[本案任务记录](../active/20260720_W结果大城镇设计落地/任务记录.md) |
| `CITY-RW-20260719-01` | 已修复，待重启后新区块实机复验 | D5 `priority_chain` 只按 endpoint priority 串接，相同优先级会让农业、商业、行政、住宅交替连成长距离链路；现改为 `group_spatial_mst`，先连组内支路、再以最近入口连接区际骨架。52 入口离线比较总距离由 13,288 降至 3,133。 | [本案任务记录](../active/20260720_W结果大城镇设计落地/任务记录.md) |
| `CITY-RW-20260717-01` | 已定位，待单独立案 | butcher 的道路入口点位于 planned footprint 内，bridge 只传 `BlockPos`、丢失入口朝向；道路首段可能穿过建筑。它不是旋转模板被裁剪的主因。 | [旋转模板故障案](E:/Mod_Dev/designer_territoryMod/docs/systems/city/40_tests/故障修复案/20260717_旋转模板pivot与规划几何错位导致分片裁剪.md) |
| `CITY-RW-20260705-01` | 已修复 | `roadProvider=auto` 在 RoadWeaver 缺失时曾回退 WorldEdit 调试道路，容易误判正式道路来源。 | `CityPlanningEndpointHandlerTest`；[历史明细](./历史明细.md) |
| `CITY-RW-20260710-01` | 外部依赖待处理 | RoadWeaver 关服时未等待道路 worker 退出即关闭 H2/MVStore，形成 `ClosedChannelException` 刷屏。当前只完成定位，未用 City 补偿掩盖。 | [任务记录](../active/20260710_RoadWeaver关服刷屏诊断/任务记录.md)；[历史明细](./历史明细.md) |
| `CITY-RW-20260712-01` | 临时规避 | RoadWeaver 道路回调在服务器主线程同步读道路库，造成 35 秒以上卡顿；测试配置已关闭预测、动态规划和道路外观。 | [历史明细](./历史明细.md) |
