# 城市 C 流程测试单

## 目标

这份测试单用于逐步验证城市 `C` 流程当前是否可用。

原则：

- 一次只看一个步骤
- 每一步只关注：
  - 输入
  - 输出
  - 自检
- 本轮优先验证“流程是否打通”，不是先追求结果完美

---

## 测试准备

### 测试城市选择

建议优先选择：

- 沿海城市
- 已有 `C2 / C3` 基础产物
- 地形起伏明显，便于观察 `port / cliff / terrace` 相关行为

### 当前测试环境

- 世界：`V3`
- 结构 catalog：`run/config/structureTemplate/C3_5_StructureCatalog.preprocessed.json`
- 当前重点验证：
  - `C3.5 -> C7`
  - `C7 -> C8`
  - `C6` 局部矩形图输出

---

## Step 0：C3.5 结构 catalog 检查

### 输入

- `run/config/structureTemplate/C3_5_StructureCatalog.preprocessed.json`
- `run/config/structureTemplate/C3_5_FunctionEnumTable.json`
- `run/config/structureTemplate/C3_5_PresetMatchReport.json`

### 输出

- 可被 C7 消费的结构候选库

### 自检

- 顶层字段是否存在：
  - `step`
  - `ok`
  - `city_id`
  - `function_enum_table`
  - `structures`
- 结构项核心字段是否存在：
  - `structure_id`
  - `size`
  - `orientation`
  - `piece_role`
  - `style_score`
  - `function_candidates`
- `PresetMatchReport` 是否没有大面积 `unmatched`
- `port / market / residential / military` 这些常用功能是否至少有候选模板

---

## Step 1：C5 模块分组

### 输入

- `C4_FunctionPlan.validated.json`
- 当前城市基础区划数据

### 输出

- `C5_ModuleGroups.json`
- `C5_MergeLog.json`
- `C5_merge_preview.png`
- `groups/<group_id>/height.png`
- `groups/<group_id>/hillshade.png`
- `groups/<group_id>/roughness.png`

### 自检

- 是否成功生成 `group_id`
- 是否能看到目标沿海组，例如：
  - `g_port_*`
- `group_count` 是否合理
- 港口组的局部地形图是否真的靠海
- `merge_log` 是否没有明显异常吸并

---

## Step 2：C6 可建区与矩形布局

### 输入

- `C5_ModuleGroups.json`
- `Stage1/heightmap`
- `buildableGroups`

### 输出

- `C6_BuildAreaSummary.json`
- `C6_BuildAreaLayout.json`
- `C6_BuildAreaIndex.dat`
- `C6_buildable_preview.png`
- `C6_rect_placement_preview.png`
- `groups/<group_id>/bbox_overview.png`
- `groups/<group_id>/rect_preview.png`
- `groups/<group_id>/c6_preview.legend.json`

### 自检

- `area_count` 和 `plan_count` 是否大于 0
- 目标 group 是否在 `C6_BuildAreaSummary.json` 中存在对应 `build_area_id`
- `C6_BuildAreaLayout.json` 里该 group 是否有 `primary_modules`
- `groups/<group_id>/bbox_overview.png` 是否生成
- `groups/<group_id>/rect_preview.png` 是否生成
- 局部矩形是否落在组边界内部，没有明显越界
- 沿海组的矩形是否确实覆盖临海区域，而不是跑到内陆

---

## Step 3：C7 模板选择

### 输入

- `C6_BuildAreaLayout.json`
- `run/config/structureTemplate/C3_5_StructureCatalog.preprocessed.json`

### 输出

- `C7_TemplateSelection.json`
- `groups/<group_id>/c7_selection.json`
- `groups/<group_id>/c7_validation.json`

### 自检

- `catalog_source` 是否指向 `C3_5_StructureCatalog.preprocessed.json`
- `selection_count` 是否大于 0
- 目标 group 是否有 `selected_template`
- `top_k_templates` 是否不为空
- `selected_template` 是否符合该 group 语义
  - `port` 不应优先选明显的非港口构件
  - `market` 不应优先选防御塔
  - `residential` 不应优先选地标件
- `c7_validation.json` 是否 `ok=true`

---

## Step 4：C8 地基与执行前计划

### 输入

- `C6_BuildAreaSummary.json`
- `C6_BuildAreaLayout.json`
- `C7_TemplateSelection.json`
- `heightmap`
- `C2 scan`

### 输出

- `C8_FoundationPlan.json`
- `groups/<group_id>/c8_foundation.json`

### 自检

- 目标 group 是否生成 `foundation`
- 是否带出 C7 透传字段：
  - `selected_template`
  - `top_k_templates`
  - `fallback_chain`
  - `landing_hint`
  - `growth_axis`
  - `vertical_role`
  - `vertical_clearance`
  - `vertical_capable`
  - `vertical_mode_hint`
- `foundation_type` 是否与地形合理匹配
  - 平地不应无故大量 `TERRACE`
  - 沿海/起伏边界区允许 `PLATFORM / TERRACE`
- `base_y / delta_height / terrain_metrics` 是否有值且看起来合理

---

## Step 5：C9 世界写入前检查

### 输入

- `C6 summary/index`
- `C8_FoundationPlan.json`

### 输出

- `C9_Placement.json`
- `C9_DecorationPlan.json`

### 自检

- `processed_areas` 是否大于 0
- `foundation_type` 是否延续 C8
- 如果 `apply_blocks=false`
  - 应只生成计划，不改世界
- 如果 `apply_blocks=true`
  - `changed_blocks_total` 应大于 0
  - 不应出现明显超预算或整组空结果

---

## 沿海城专项检查

### 输入

- 目标港口组 `g_port_*`
- 该组的 `C5/C6/C7/C8` 全部局部产物

### 输出

- 一组可解释的港口模板选择与地基方案

### 自检

- `C5` 局部图看起来确实临海
- `C6` 局部矩形确实贴近岸线
- `C7` 至少有一个候选明显属于：
  - `ship`
  - `dock`
  - `harbor`
  - `lighthouse`
- `C8` 若地势起伏较大，允许 `TERRACE / PLATFORM`
- 若当前港口组优先选出明显不合适模板：
  - 记录 `group_id`
  - 记录 `selected_template`
  - 记录 `top_k_templates`
  - 回头调 C7 打分或 C3.5 规则

---

## 本轮建议执行顺序

1. 选定测试城市
2. 跑 `C5`
3. 跑 `C6`
4. 检查局部矩形图
5. 跑目标 group 的 `C7`
6. 检查模板选择是否合理
7. 跑目标 group 的 `C8`
8. 检查地基与 vertical 透传字段
9. 必要时再跑 `C9`

---

## 当前重点观察项

本轮最该重点盯的不是“美观”，而是下面这三件事：

- `C7` 是否真的吃到了 `C3.5 catalog`
- `C6` 是否真的自动导出了 group 局部矩形图
- `C8` 是否真的把 `C7` 的模板与 vertical 意图带进结果
