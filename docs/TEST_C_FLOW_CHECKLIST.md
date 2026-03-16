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

## Step 2：C6 AI 主模块矩形闭环

### 输入

- `C5_ModuleGroups.json`
- `Stage1/heightmap`
- `buildableGroups`

### 输出

- `C6_BuildAreaSummary.json`
- `C6_BuildAreaLayout.json`
- `C6_BuildAreaIndex.dat`
- `C6_RectDecisionInput.json`
- `C6_RectCandidates.json`
- `C6_RectValidation.json`
- `C6_buildable_preview.png`
- `C6_rect_placement_preview.png`
- `groups/<group_id>/bbox_overview.png`
- `groups/<group_id>/rect_preview.png`
- `groups/<group_id>/c6_preview.legend.json`

### 自检

- `area_count` 和 `plan_count` 是否大于 0
- 目标 group 是否在 `C6_BuildAreaSummary.json` 中存在对应 `build_area_id`
- `C6_RectDecisionInput.json` 中该 group 是否带出：
  - `polygon_area_blocks`
  - `mask_bbox`
  - `centroid`
  - `current_attempt_count`
  - 预览图路径
- `groups/<group_id>/bbox_overview.png` 是否生成
- `groups/<group_id>/rect_preview.png` 是否生成
- 未提交矩形前：
  - `rect_preview.png` 应只看到 mask 与 anchor
- 提交非法矩形后：
  - `C6_RectValidation.json` 中应出现：
    - `inside_functional_blocks`
    - `total_rect_blocks`
    - `coverage_ratio`
    - `valid=false`
    - `reason`
  - `rect_preview.png` 应出现红色越界区
- 提交合法矩形后：
  - `coverage_ratio >= 0.80`
  - `sum(w*h) / polygon_area_blocks > 0.50`
  - `finalized_into_layout = true`
  - `C6_BuildAreaLayout.json` 里该 group 才应写入最终 `primary_modules`

---

## Step 3：C7 AI 组件与排列方式决策

### 输入

- `C6_BuildAreaLayout.json`
- `run/config/structureTemplate/C3_5_StructureCatalog.preprocessed.json`
- preset pool 的组件落地规则说明

### 输出

- `C7_TemplateSelection.json`（后续建议升级为 `C7_ArrangementDecision.json`）
- `groups/<group_id>/c7_selection.json`（或对应 arrangement decision 局部文件）
- `groups/<group_id>/c7_validation.json`

### 自检

- `catalog_source` 或 `preset_pool_ref` 是否指向当前预设池
- 只有 `C6_BuildAreaLayout.json` 中 `validated=true` 且 `primary_modules` 非空的 group 才应进入 C7
- `no_primary_module` 的 group 不应再被硬生成默认 `c7_selection`
- C7 回传结果中应至少能看出：
  - 选了哪些组件
  - 采用了哪种 `arrangement_type`
  - `seed`
  - `limits`
  - `termination`
  - `arrangement_params` 是否存在
  - 每个组件的落地规则是否存在
  - 对应策略参数段是否存在：
    - `linear`
    - `courtyard`
    - `spine_branch`
    - `cluster`
- `c7_validation.json` 是否主要校验：
  - 排列方式枚举是否合法
  - 参数是否齐全
  - 组件引用是否都来自 preset pool
- 不再要求程序在 C7 阶段硬编码判断：
  - `port` 必须优先哪个模板
  - `market` 必须优先哪个模板

---

## Step 4：C8 排列求解与执行前计划

### 输入

- `C6_BuildAreaSummary.json`
- `C6_BuildAreaLayout.json`
- `C7_TemplateSelection.json`
- `heightmap`
- `C2 scan`

### 输出

- `C8_FoundationPlan.json`（后续可升级为更明确的 placement/arrangement 执行计划）
- `groups/<group_id>/c8_foundation.json`

### 自检

- `C8` 是否读取了 `C7` 已确定的 `arrangement_type`
- `C8` 是否读取了该排列方式对应参数：
  - `seed`
  - `limits`
  - `termination`
  - 以及具体策略子段（如 `linear / courtyard / spine_branch / cluster`）
- `C8` 是否按固定排列算法计算出每个组件的：
  - `template_id`
  - `x / y / z`
  - `rotation`
  - `anchor_module_id`
  - 连接或依附关系
- `C8` 是否只负责“求坐标”，而不再重新决定模板偏好
- `groups/<group_id>/c8_arrangement_debug.json` 是否生成
- debug 中是否能看出：
  - `arrangement_success`
  - `arrangement_errors`
  - `arrangement_warnings`
  - 为什么某个方向被拒绝
- 若 `arrangement_type` 改变：
  - 输出坐标布局应明显变化
  - 但程序校验流程不应崩
- 若 `no_primary_module`
  - 该 group 可以跳过 C8 主落位
  - 或输出空结果
  - 但不应再回退生成程序默认主模块方案

---

## Step 5：C9 主模块世界写入前检查

### 输入

- `C6 summary/index`
- `C8_FoundationPlan.json`

### 输出

- `C9_Placement.json`
- `C9_DecorationPlan.json`
- `groups/<group_id>/c9_placement.json`
- `groups/<group_id>/c9_decoration.json`

### 自检

- `processed_areas` 是否大于 0
- `foundation_type` 是否延续 C8
- 如果 `apply_blocks=false`
  - 应只生成计划，不改世界
  - 应尽可能能看出计划中的结构落位信息
- 如果 `apply_blocks=true`
  - `changed_blocks_total` 应大于 0
  - 不应出现明显超预算或整组空结果
- `C9` 是否按 `C8 placements[]` 的：
  - `template_id`
  - `x / y / z`
  - `rotation`
  直接落位
- 不应再依赖原版随机 jigsaw 继续展开
- 若某拼图方块没有计划中的后继，应清为空气

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
- `C6_RectValidation.json` 中港口矩形 `coverage_ratio` 必须能解释成功/失败
- `C7` 应明确给出港口组采用了什么 `arrangement_type`
- 港口组 `C7` 应能明确给出：
  - `seed`
  - `limits`
  - `termination`
  - `linear.forward_dirs`
- 港口组的组件集合中应能解释为何包含：
  - `ship`
  - `dock`
  - `harbor`
  - `lighthouse`
  - 或为何不包含
- `C8` 应能按港口排列算法算出沿岸结构坐标，而不是重新猜港口应该怎么排
- 若港口组没有展开出多节点，`c8_arrangement_debug.json` 必须能解释是：
  - 越界
  - 分支回退
  - 深度/数量限制
  - 还是 template 邻接信息不足
- `C9` 最终应只按 `C8 placements` 落港口主模块结构

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
