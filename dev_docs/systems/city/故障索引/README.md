# City 故障分类索引

## 使用方式

根级 [故障索引](../故障索引.md) 只用于先看当前未修复项和选择子系统；本目录按故障归属保留短索引。每个索引中的 ID 稳定不随修复状态变化，便于任务记录、提交说明和后续检索引用。

| 子系统 | 范围 |
| --- | --- |
| [模板 worldgen](./模板worldgen.md) | NBT、旋转几何、datum、owner fragment、template ledger。 |
| [规划与工作流](./规划与工作流.md) | D2-D7、D4 候选、artifact、HTTP workflow 与测试夹具。 |
| [装饰与 LandUse](./装饰与LandUse.md) | DecorationProgram、LandUse、feature hook、装饰 ledger。 |
| [道路与 RoadWeaver](./道路与RoadWeaver.md) | City 道路 bridge、RoadWeaver 生命周期与运行时表现。 |
| [城市边界与城墙](./城市边界与城墙.md) | wall reservation、road mask、v4/v5 planner 与落地。 |

## 状态约定

- `已定位，未修复`：根因和影响范围已有证据，尚未进入实现修复。
- `外部依赖待处理`：根因在外部 Mod 或运行环境，City 侧未作掩盖性补偿。
- `临时规避`：存在可用的测试或运行规避，不等同于根因已修复。
- `已修复`：代码与相应回归已完成；仍可能标注“待实机复验”。

## 历史保留

[历史明细](./历史明细.md) 保留改造前的完整大表，作为旧记录和详细复盘的只读归档。它包含整理前的 24 条记录；分类索引中的第 25 条 `CITY-RW-20260717-01` 是本次整理时从旋转模板故障案拆出的独立道路风险，因此不回写旧历史表。新增故障同样不再写入历史明细；只写对应子系统短索引，并在需要时建立文档仓库 `40_tests/故障修复案/` 或 active 任务记录。
