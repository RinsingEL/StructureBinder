# 需求：C8 保持 rect 级结构选择池映射，不再按 build area/group 拍扁

## 1. 背景

当前 C6/C7 的数据形态已经具备了“一个 rect / 一个 primary module 对应一份结构选择结果”的基础：

- C6 的 `primary_modules[]` 同时包含 `module_id` 与 `rect_id`
- C7 的 `TemplateSelectionItem` 是按 `module_id` 生成的
- C7 的 `GroupArrangementDecision.selected_components[]` 也会按 module 展开

但在 C8 消费这些数据时，module 级信息被提前拍扁，导致 rect 与结构选择池的映射在进入空间求解前就丢失。

## 影响范围

- 阶段范围：以 `C8` 为主，向上影响 `C7` 选择结果消费方式
- 代码范围：`CityC8Stages`、`CityC8ArrangementEngine`，必要时补充 `CityC7Stages`
- 数据/产物范围：`C7_TemplateSelection.json`、`C8_FoundationPlan.json`、group 级 `c8_foundation.json`、C8 调试与预览产物
- 测试范围：`CityC8ArrangementEngineTest`、`CityControllerTest`，以及新增多 rect / 多 module 映射测试

## 2. 当前问题

### 2.1 选择结果在 C8 入口被降成 build area / group 粒度

`CityC8Stages.indexSelections(...)` 当前只按：

- `build_area_id`
- `group_id`

建立索引，并使用 `putIfAbsent(...)` 保留首条记录。

这意味着：

- 同一个 build area 下如果有多个 rect / module
- 只有第一条 `TemplateSelectionItem` 会被保留下来
- 其他 rect 自己的 `selected_template`、`top_k_templates`、`fallback_chain` 会丢失

### 2.2 FoundationItem 只保留了一份 selection 结果

`buildFoundationItem(...)` 当前把：

- `selected_template`
- `top_k_templates`
- `fallback_chain`
- `function_role`

这类字段挂在整个 `FoundationItem` 上。

这会把“多 rect 多选择”的场景简化成：

- 一个 build area 只有一份主模板选择结果

### 2.3 当前行为与设计意图不一致

按现有 C6/C7 设计，rect/module 已经是独立决策单元。

因此当前问题不是“系统本来就只想做 group 级共用池”，而是：

- 数据设计支持 rect 级
- C8 实现把 rect 级信息退化成了 area/group 级

## 3. 需求目标

在 C8 阶段保留并消费 rect / module 粒度的结构选择结果，确保：

1. 每个 `primary_module` 都能拿到自己的选择结果
2. 每个 rect 的结构候选、选中模板、fallback 信息不会互相覆盖
3. 后续的边界检查、编排求解、调试产物都能追溯到具体 rect/module

## 4. 期望行为

### 4.1 选择索引应至少支持 module 级命中

C8 在解析 C7 结果时，应优先按以下粒度匹配：

1. `module_id`
2. `build_area_id + module_id` 或等价精确上下文
3. `build_area_id`
4. `group_id`

其中低优先级只可作为兼容兜底，不能覆盖已存在的 module 级结果。

### 4.2 Foundation 中需要保留 module 级选择上下文

如果一个 build area 下存在多个 rect / module，则 C8 产物中应能明确看出：

- 哪个 placement / component 来自哪个 module / rect
- 该 module 的 `selected_template`
- 该 module 的候选与 fallback 信息

不要求必须复用当前 `FoundationItem` 字段形状，但最终产物必须能表达这层映射。

### 4.3 编排求解不能再默认共享一份 area 级选择

在多 rect 场景下，C8 编排时不能把所有 placement 都视为共用同一套模板选择上下文。

至少需要保证：

- root / component 选择能回到对应 module
- rect 边界检查时知道当前结构属于哪个 rect
- 调试时能解释“为什么这个结构落在这个 rect”

## 5. 范围

### 本需求包含

- C8 对 C7 selection / component 的解析粒度修正
- C8 产物中 module / rect 到结构选择结果的映射保留
- 与此直接相关的测试补齐

### 本需求不包含

- C6 rect 生成算法重构
- rect 面积预算模型重算
- catalog footprint 统计建模

这些属于更上游的设计改造，应由独立需求推进。

## 6. 验收标准

### 场景 1：一个 build area 下有多个 rect

给定同一 build area 下至少 2 个 `primary_module`，且 C7 为它们产出不同的：

- `selected_template`
- `top_k_templates`

则 C8 生成后应仍能区分这两套结果，不能只保留第一条。

### 场景 2：module 级信息优先于 group 级兜底

当同一 group 同时存在：

- module 级 selection
- group 级兜底 selection

则对应 rect 应优先使用 module 级结果。

### 场景 3：调试可追溯

开发者在检查 C8 产物时，应能追溯：

- placement 属于哪个 module / rect
- 它使用的是哪份选择结果

### 场景 4：兼容单 rect 老场景

对于只有一个 `primary_module` 的 build area，行为应与现有单 rect 流程兼容，不引入额外回归。

## 7. 建议落点

优先检查以下位置：

- `src/main/java/com/user/terra_script/world/city/stage/c8/CityC8Stages.java`
- `src/main/java/com/user/terra_script/world/city/stage/c8/CityC8ArrangementEngine.java`
- `src/main/java/com/user/terra_script/world/city/stage/c7/CityC7Stages.java`

## 8. 备注

这个需求来自本轮排查中新识别出的实现缺陷：

- 设计上已有 rect/module 粒度
- 但 C8 实际把它拍扁成了 area/group 粒度

因此它应被视为“已有设计未被正确贯彻”的修复型需求，而不是纯新功能。
