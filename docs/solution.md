# 一、世界构造部分


## W1 世界主题与约束（AI）（拓展插件；暂不开发）


- **AI做什么**：设定世界观、时代、科技树、种族/势力类型、主线冲突；给出硬约束（海陆比、极端地形比例、气候带倾向、希望出现的奇观类型等）
- **AI可调用数据**：无（纯文本规划）
- **产出（JSON）**：`W1_WorldTheme.json`（主题、叙事、硬约束）
- **存放位置**：`/saves/<WorldName>/terra_script/world/`

## W2 世界生成配方（AI→程序执行）（拓展插件；暂不开发）


- **AI做什么**：把主题映射成“世界生成参数意图”（比如：大陆更碎/更整体、山脉更高、河流更密）
- **程序做什么**：调用你的世界生成mod/参数，生成世界；记录可复现配方；如reterraForgedMod
- **AI可调用数据**：世界生成器可用参数列表（可选）
- **产出**：


`W2_WorldGenRecipe.json`（种子、mod版本hash、生成器参数、配置hash）


`W2_WorldGenReport.json`（生成日志摘要、耗时、关键统计）
- **存放位置**：`/saves/<WorldName>/terra_script/world/`

---

## 当前实现状态（已完成：W3 / W4 / T1 / T2 / T3 / T4 / C5 / C6；C7 / C8 / C9 进入重构联调中；C4 方案已定稿但未代码落地）

以下内容基于当前代码实际落地情况（`domain` + `server/mcp`）：

- `W3` 已实现：`src/main/java/com/user/terra_script/domain/world/stage/W3Stage.java`
  - 产物：`world/W3/ContinentMeta.json`、`world/W3/OceanMeta.json`
  - 触发方式：工作流阶段 `W3`（或 `/dev stage W3`）
- `W4` 已实现：`src/main/java/com/user/terra_script/domain/world/stage/W4Stage.java`
  - 依赖：`W3`
  - 产物：`world/W4/TerrainFacts.dat`、`world/W4/TerrainSummary.json`、`W4_*preview*.png`（规划中按规范输出）
  - 触发方式：工作流阶段 `W4`（或 `/dev stage W4`）
- 局部细扫步长：Region 局部细扫固定 `step=16`（全图扫描保持原逻辑不变，避免局部细扫 `step=1` 带来的内存/关服压力）
- 领土扩张与领土预览：当存在 Region 细扫缓存时，`TerritoryManager` 扩张主网格优先使用 `step=16`（细扫精度）；无细扫缓存时回退到全局 `scanStep`。`TerritoryPreview` 已对齐扩张网格的 `step/minX/minZ`，避免预览与扩张归属错位。
- `T1` 已实现：`src/main/java/com/user/terra_script/server/mcp/TerritoryController.java`
  - 接口：`POST /t1_blueprint`、`GET /t1_blueprint`
  - 持久化：`src/main/java/com/user/terra_script/territory/io/TerritoryRepository.java`
  - 产物：`/saves/<WorldName>/terra_script/territories/T1_Blueprint.json`
- `T2 / T3 / T4` 已实现并接入工作流：
  - 阶段类：`src/main/java/com/user/terra_script/domain/territory/stage/T2Stage.java`、`src/main/java/com/user/terra_script/domain/territory/stage/T3Stage.java`、`src/main/java/com/user/terra_script/domain/territory/stage/T4Stage.java`
  - 当前默认语义：`T4` 执行首都城市 bootstrap（默认生成 1 座城市）；旧的领土精细扫描保留为可选 legacy 模式（`t4_legacy_scan=true`）。
  - 工作流注册：`src/main/java/com/user/terra_script/server/mcp/WorkflowController.java`
- 城市阶段 `C5 / C6 / C7 / C8 / C9` 已实现基础接口；其中 `C7 / C8 / C9` 当前处于“新语义重构 + 运行态联调”阶段；`C4` 目前为网页端实验流程（未在当前代码主流程落地）：
  - 入口：`src/main/java/com/user/terra_script/server/mcp/CityController.java`
  - 接口：`/city_c5_generate`、`/city_c6_generate`、`/city_c6_rect_prepare`、`/city_c6_rect_submit`、`/city_c7_generate`、`/city_c8_generate`、`/city_c9_generate`
  - 当前语义：
    - `C5` 基于邻接与功能可在同一 layer 形成多个 group
    - `C6` 已改为“AI 决策主模块矩形 + 程序校验闭环”
    - `C7` 正在改为“AI 决定主模块结构组件与排列方式，程序只做契约校验与落盘”
    - `C8` 正在改为“程序按排列算法与拼图连接规则展开主模块结构，并输出调试预览与 debug”
    - `C9` 正在改为“严格按 C8 已确定的模板与坐标落地主模块，不再使用原版随机 jigsaw 扩展”
    - `C4` 的 Polygon Tag 逻辑以本案文定义为准，待正式实现

---

## 城市阶段现行契约补充（C6 / C7 / C8）

### C6：主模块来源改造

- `C6` 不再由程序默认排布主模块矩形。
- 程序职责改为：
  - `city_c6_rect_prepare`
    - 生成 `C6_RectDecisionInput.json`
    - 生成 `groups/<group_id>/height.png`
    - 生成 `groups/<group_id>/hillshade.png`
    - 生成 `groups/<group_id>/roughness.png`
- 生成 `groups/<group_id>/polygon_overview.png`
    - 生成 `groups/<group_id>/rect_preview.png`
  - `city_c6_rect_submit`
    - 接收 AI 回传矩形
    - 生成 `C6_RectCandidates.json`
    - 生成 `C6_RectValidation.json`
    - 仅在校验通过或 AI 明确终止时回写 `C6_BuildAreaLayout.json`

### C6：最终 layout 含义

- `C6_BuildAreaLayout.json` 中的 `primary_modules`
  - 不再表示程序默认猜测矩形
  - 只表示“最终通过校验的 AI 决策矩形”
- `validated = true` 才表示该 plan 可以被下游正式消费。
- `decision_mode`
  - `submit_rects`
  - `keep_current`
  - `no_primary_module`
- `accepted_attempt_index`
  - 表示该 group 的最终收敛轮次

### C7：阶段职责重定义

- `C7` 不再以程序硬编码规则去推导：
  - `function_role`
  - `selected_template`
  - `top_k_templates`
  - “该 group 默认适合什么模板”的打分结论
- `C7` 的主职责改为“AI 选择组件与排列方式”：
  - 输入：
    - `C6` validated 的 `primary_modules`
    - 结构预设池（preset pool）
    - 每个 preset 的落地规则说明
  - 输出：
    - AI 决定的组件集合
    - 每个组件的落地限制/参数
    - 该 group 采用的排列方式
    - 排列方式所需参数
- 程序在 `C7` 只负责：
  - 提供给 AI 的候选池与规则说明
  - 校验 AI 回传字段是否合法
  - 将 AI 决策结果落盘为正式产物

### C7：当前进度

- 已完成：
  - `arrangements` 数据结构已落地
  - `seed / limits / termination / strategy_params` 已进入 `C7` 产物
  - `city_c7_generate` 支持：
    - 程序 fallback 生成
    - AI 直接提交 arrangement decision
  - `c7_validation.json` 已开始校验：
    - `arrangement_type`
    - `selected_components`
    - `template_id`
    - `component_rule`
- 已验证：
  - `g_port_15` 的 `c7_selection.json` 中已成功写出：
    - `seed`
    - `limits`
    - `termination`
    - `linear`
    - `decision_source = ai_decision_submit`
- 未完成：
  - road / secondary 相关策略参数尚未并入 `C7`

### C7：程序写死与 AI 决策的边界

- AI 决策内容：
  - 选哪些结构组件
  - 每个组件的用途与落地规则
  - 采用哪种排列方式
  - 排列方式参数
- 程序写死内容：
  - 排列方式枚举有哪些
  - 每种排列方式需要哪些参数
  - 回传 JSON 的字段契约
  - 字段合法性校验
- 因此，`C7` 不应继续把“port/market/residential 应优先什么模板”写死在 Java 逻辑里。

### C7：建议产物语义

- 当前的 `C7_TemplateSelection.json` 建议后续升级为更明确的“排列决策文件”：
  - 可命名为 `C7_ArrangementDecision.json`
- 最低应包含：
  - `group_id`
  - `build_area_id`
  - `validated_primary_modules`
  - `preset_pool_ref`
  - `selected_components[]`
  - `arrangement_type`
  - `arrangement_params`
  - `component_rules[]`
- 若暂不改文件名，也应把现有 `C7` 的语义逐步迁移到上述结构。

### C8：阶段职责重定义

- `C8` 不再负责“决定怎么排”。
- `C8` 的职责改为：
  - 读取 `C7` 已确定的排列方式
  - 调用程序内置排列算法
  - 计算每个组件/拼图结构的实际坐标、朝向、连接关系、落地高度
- 换句话说：
  - `C7` 决定“排法”
  - `C8` 执行“按该排法求具体坐标”

### C8：当前进度

- 已完成：
  - `C8` 已接入 `arrangement_type`
  - `placements[]` 已升级为 piece 级节点结构，包含：
    - `node_id`
    - `template_id`
    - `x / y / z`
    - `rotation`
    - `level`
    - `parent_node_id`
    - `placement_reason`
  - 已加入：
    - `arrangement_success`
    - `arrangement_errors`
    - `arrangement_warnings`
  - 已输出：
    - `c8_arrangement_preview.png`
    - `c8_arrangement_preview.legend.json`
    - `c8_arrangement_debug.json`
- 当前主模块展开逻辑：
  - 仍是“有限受约束展开”
  - 已支持 root 起点与 `LINEAR` 主方向约束
  - 已支持边界检查与分支回退
- 已验证：
  - `g_port_15` 的 `C8` 已可输出 debug 文件
  - 当前 debug 已能解释为何仅生成 root 节点
- 未完成：
  - `COURTYARD / SPINE_BRANCH / CLUSTER` 的执行算法仍未完全展开
  - `LINEAR` 仍需继续调到能稳定长出多节点主链
  - “闭合收尾 / 模块级整体回退”规则仍需进一步精化

### C8：程序应写死的内容

- `C8` 中真正应写死的是“排列算法实现”，而不是模板选择偏好。
- 程序固定维护：
  - 排列方式枚举
    - 例如：`LINEAR_DOCK`、`COURTYARD`、`SPINE_BRANCH`、`RING`、`TERRACE_CHAIN`
  - 每种排列方式的参数规范
  - 每种排列方式如何从输入模块与组件规则计算出最终结构坐标
- `C8` 输出应聚焦于：
  - `template_id`
  - `x / y / z`
  - `rotation`
  - `anchor_module_id`
  - `placement_reason`
  - `connection_targets`

### C8：当前主模块流程总结

目前主模块结构流程已经固定为：

1. `C6`
   - AI 确定主模块矩形
   - 程序校验 block 级覆盖率
   - 合法后写回 validated `primary_modules`
2. `C7`
   - AI 选择主模块起始模板、排列方式、展开参数
   - 程序只做契约校验与落盘
3. `C8`
   - 程序按 `seed + limits + termination + strategy` 执行结构展开
   - 输出 piece 级坐标计划与 debug
4. `C9`
   - 程序只按 `C8 placements` 落结构
   - 不再调用原版随机 jigsaw 继续扩展
   - 若某拼图方块无计划中的后续，则清为空气

### C7 / C8：代码组织建议

- 建议把“契约”和“排列算法”拆到单独文件夹，而不是继续堆在单一 stages 文件中。
- 推荐组织：
  - `world/city/stage/c7/`
    - `CityC7Contract.java`
    - `CityC7PresetPool.java`
    - `CityC7DecisionIO.java`
    - `CityC7Validation.java`
  - `world/city/stage/c8/`
    - `CityC8ArrangementEngine.java`
    - `CityC8PlacementSolver.java`
    - `arrangement/`
      - `ArrangementType.java`
      - `ArrangementSpec.java`
      - `LinearDockArranger.java`
      - `CourtyardArranger.java`
      - `SpineBranchArranger.java`

### C8：现阶段兼容策略

- 本阶段允许：
  - 继续兼容旧的 `C7_TemplateSelection.json`
  - 继续兼容旧的 `C8_FoundationPlan.json`
- 但文义上应开始转向：
  - `C7` 负责 AI 排列决策
  - `C8` 负责程序坐标求解
- 后续如保留 `C8_FoundationPlan.json` 文件名，也应明确其语义正在从“地基类型计划”转向“结构落位执行前计划”。

### C9：阶段职责重定义

- `C9` 不再负责“根据 foundation_type 粗略改地形”这一层简单动作。
- `C9` 的主模块职责改为：
  - 按 `C8 placements[]` 中给定的：
    - `template_id`
    - `x / y / z`
    - `rotation`
  - 直接放置结构模板
  - 不再让原版 jigsaw 随机继续选择后继模板
  - 对没有计划中后继的拼图方块，直接替换为空气

### C9：当前进度

- 已完成代码：
  - `C9` 已能读取 `C8 placements[]`
  - `StructureInjector` 已新增按 block 坐标与 rotation 直接放模板的方法
  - 模板放置后会清理包围盒中的 `JIGSAW` 方块为空气
  - `C9Placement.items[]` 已扩展支持：
    - `planned_nodes`
    - `placed_structures`
    - `structures[]`
- 已验证：
  - `city_c9_generate` dry-run 可正常返回
  - `groups/<group_id>/c9_placement.json`
  - `groups/<group_id>/c9_decoration.json`
  - 均可落盘
- 当前运行态问题：
  - 最新 `structures[]` 明细在代码中已实现并编译通过
  - 但当前游戏实例多次联调中，运行态产物仍未稳定体现这一新版输出
  - 因此，`C9` 当前应视为：
    - 代码层已到位
    - 运行态验证尚未完全收口

### C7 / C8：主模块排列策略参数（第一版）

- 主模块的起始位置仍采用：
  - AI 决策
  - 程序查错
- 程序当前最低校验要求：
  - `start_x / start_z` 必须在主模块矩形内
  - 后续每个组件矩形必须完全落在主模块矩形内
  - 不允许超 `max_depth / max_pieces`
- `start` 的含义：
  - 表示该模板可独立作为开头
  - 不代表其所有 connector 都必须放开展开
- 排列方式的职责：
  - 决定“允许哪些 connector 使用、按什么顺序展开、每层允许多少分支”
  - 而不是直接对 start 模板的全部 jigsaw 方向无约束 BFS

#### 通用字段

```jsonc
{
  "arrangement_type": "LINEAR",
  "seed": {
    "start_x": 0,
    "start_z": 0,
    "start_rotation": 0,
    "start_template_id": "namespace:path",
    "start_connector_dir": "north"
  },
  "limits": {
    "max_depth": 6,
    "max_pieces": 24,
    "max_branch_per_depth": 2
  },
  "termination": {
    "require_closure": true,
    "allow_trim_leaf": true,
    "rollback_on_unclosed_middle": true
  }
}
```

- `seed.start_x / start_z`
  - AI 指定的起始点
- `seed.start_rotation`
  - 起始模板朝向
- `seed.start_template_id`
  - 起始模板
- `seed.start_connector_dir`
  - 从起始模板优先使用哪个 connector 开始
- `limits.max_depth`
  - 展开最大层数
- `limits.max_pieces`
  - 最大拼图数
- `limits.max_branch_per_depth`
  - 每层最多允许开的分支数
- `termination.require_closure`
  - 是否必须闭合到合法收尾
- `termination.allow_trim_leaf`
  - 是否允许砍掉末端叶子
- `termination.rollback_on_unclosed_middle`
  - 出现未闭合 `MIDDLE` 是否整体回退

#### 1. LINEAR

- 适合：
  - 港口
  - 长廊
  - 码头
  - 线性商业带

```jsonc
{
  "arrangement_type": "LINEAR",
  "seed": { "...": "..." },
  "limits": { "...": "..." },
  "termination": { "...": "..." },
  "linear": {
    "primary_axis": "x",
    "forward_dirs": ["east", "west"],
    "preferred_forward_dir": "east",
    "segment_spacing": 12,
    "lane_count": 1,
    "allow_side_branches": true,
    "side_branch_interval": 3,
    "side_branch_max_length": 2,
    "alternate_branch_side": true,
    "allow_reverse_growth": false,
    "front_loaded_start": true
  }
}
```

- `primary_axis`
  - 线性主轴，`x / z / auto`
- `forward_dirs`
  - 允许沿哪些方向主推进
- `preferred_forward_dir`
  - 优先主方向
- `segment_spacing`
  - 主链相邻 piece 间距
- `lane_count`
  - 平行链条数
- `allow_side_branches`
  - 是否允许主链两侧挂支路
- `side_branch_interval`
  - 每隔几个主节点允许挂一次
- `side_branch_max_length`
  - 支路最大长度
- `alternate_branch_side`
  - 是否左右交替挂支路
- `allow_reverse_growth`
  - 是否允许从 root 向反方向同时生长
- `front_loaded_start`
  - 是否优先把核心 piece 放在链头

#### 2. COURTYARD

- 适合：
  - 城堡内院
  - 市场广场
  - 学校主院
  - 宗教中心

```jsonc
{
  "arrangement_type": "COURTYARD",
  "seed": { "...": "..." },
  "limits": { "...": "..." },
  "termination": { "...": "..." },
  "courtyard": {
    "center_mode": "seed_is_center",
    "ring_count": 1,
    "ring_spacing": 10,
    "arc_coverage_deg": 300,
    "entry_gap_dir": "south",
    "entry_gap_width": 1,
    "prefer_symmetric_pairs": true,
    "allow_corner_emphasis": true,
    "corner_piece_weight": 1.5,
    "inward_facing": true
  }
}
```

- `center_mode`
  - `seed_is_center / seed_on_edge`
- `ring_count`
  - 院落层数
- `ring_spacing`
  - 环层间距
- `arc_coverage_deg`
  - 环绕角度
- `entry_gap_dir`
  - 开口朝向
- `entry_gap_width`
  - 开口宽度
- `prefer_symmetric_pairs`
  - 是否尽量成对称布局
- `allow_corner_emphasis`
  - 是否允许角点放大件
- `corner_piece_weight`
  - 角部件优先权重
- `inward_facing`
  - 是否朝向内院

#### 3. SPINE_BRANCH

- 适合：
  - 住宅骨架
  - 城堡外廓
  - 沿主路展开的功能带

```jsonc
{
  "arrangement_type": "SPINE_BRANCH",
  "seed": { "...": "..." },
  "limits": { "...": "..." },
  "termination": { "...": "..." },
  "spine_branch": {
    "spine_axis": "x",
    "spine_dirs": ["east"],
    "spine_spacing": 10,
    "spine_length_target": 6,
    "branch_dirs": ["north", "south"],
    "branch_spacing": 8,
    "branch_interval": 2,
    "branch_max_length": 3,
    "branch_balance_mode": "alternate",
    "allow_terminal_hub": true,
    "terminal_hub_size": 2
  }
}
```

- `spine_axis`
  - 主脊轴
- `spine_dirs`
  - 主脊允许生长方向
- `spine_spacing`
  - 主脊间距
- `spine_length_target`
  - 主脊目标长度
- `branch_dirs`
  - 支脉方向
- `branch_spacing`
  - 支脉间距
- `branch_interval`
  - 每隔几个主脊节点挂支脉
- `branch_max_length`
  - 单条支脉最大长度
- `branch_balance_mode`
  - `alternate / symmetric / free`
- `allow_terminal_hub`
  - 脊尾是否允许扩成小核心
- `terminal_hub_size`
  - 脊尾核心规模

#### 4. CLUSTER

- 适合：
  - 不规则组团
  - 港口附属组团
  - 工坊群
  - 小院落簇

```jsonc
{
  "arrangement_type": "CLUSTER",
  "seed": { "...": "..." },
  "limits": { "...": "..." },
  "termination": { "...": "..." },
  "cluster": {
    "cluster_count": 3,
    "cluster_radius": 14,
    "cluster_spacing": 18,
    "cluster_shape": "ellipse",
    "scatter_mode": "weighted_random",
    "allow_micro_paths": true,
    "intra_cluster_branch_limit": 2,
    "cluster_center_bias": "medium",
    "edge_avoidance": 0.7,
    "overlap_tolerance": 0.0
  }
}
```

- `cluster_count`
  - 组团数量
- `cluster_radius`
  - 单组团半径
- `cluster_spacing`
  - 组团中心最小距离
- `cluster_shape`
  - `circle / ellipse / irregular`
- `scatter_mode`
  - `weighted_random / radial / patch`
- `allow_micro_paths`
  - 组团内部是否允许短链连接
- `intra_cluster_branch_limit`
  - 组团内部最大支路数
- `cluster_center_bias`
  - 中心聚集倾向
- `edge_avoidance`
  - 靠近主模块边缘时的回避程度
- `overlap_tolerance`
  - 允许多少重叠，默认 `0`

#### 默认映射建议

- `port`
  - 主推 `LINEAR`
- `market`
  - 主推 `COURTYARD`
- `residential / urban block`
  - 主推 `SPINE_BRANCH`
- `castle / workshop yard / irregular compound`
  - 主推 `CLUSTER`

当前实现里，`W4_WorldSummary.json` 仍属于预留描述，尚未由 `W4Stage` 直接产出。

---

## W3. 大陆/海洋聚类与识别 (程序)

**核心逻辑**：这是“发现大陆”的阶段。基于基础的高度/地形扫描，通过算法（DBSCAN 或 连通域算法）将离散的区块聚合为独立的“地理单元”（Region）。

*   **程序做什么**：
    1.  等待世界生成器完成基础地形生成。
    2.  执行全图扫描（ScanPixel）。
    3.  运行聚类算法，识别出 ID 独立的 `Clusters` (大陆) 和 `Oceans` (海洋)。
    4.  计算基础几何属性：中心点 (`cx, cz`)、边界 (`min/max`)、总面积。
*   **AI 参与**：无。
*   **产出（中间态对象）**：内存中的 `List<ScanRegion>`，准备交给 W4 进行深度计算。

---

## W4. 地貌特征计算与图集生成 (程序)

**核心逻辑**：这是“分析大陆”并“持久化数据”的阶段。W3 只是圈出了地盘，W4 要计算这块地盘的**详细特征**（崎岖度、坡度、气候分布），并将结果存盘。**T1 阶段 AI 看到的“世界概况”正是源于此步骤的统计结果。**

*   **程序做什么**：
    1.  **特征计算**：遍历每个 Region 的像素，计算 `Slope` (坡度), `Roughness` (崎岖度), `TPI` (地形位置指数)。
    2.  **统计汇总**：计算平均温/湿度、统计群系占比（如前5大群系）、寻找稀有群系坐标。
    3.  **落盘 (Serialization)**：
        *   **重数据 (`.dat`)**：将方块级/区块级的 `ScanPixel`, `slope[][]`, `tpi[][]` 写入 NBT 格式的大文件。
        *   **轻数据 (`.json`)**：将统计出的概况信息（Atlas）导出，供 AI 读取。
*   **AI 可调用数据 (MCP)**：
    *   `W4_get_world_atlas()`：直接读取 `W3_ContinentMeta.json`。
    *   `scan_local_candidates(...)`：后端会去查 `W4_TerrainFacts.dat` 中的 slope/tpi 数组。

---

### W4 预览图渲染规范（v2）

> 目标：给 AI 一次性提供 5 张世界预览图（高程、斜率、崎岖度、温度、群系）+ 世界总览信息。  
> 本阶段只定义“输出图片 + legend”，候选聚类叠画放在下一步。

#### 输出清单（固定）

- `W4_preview_height.png`
- `W4_preview_slope.png`
- `W4_preview_roughness.png`
- `W4_preview_temperature.png`
- `W4_preview_biome.png`

每张图必须同时输出对应图例文件：

- `W4_preview_height.legend.json`
- `W4_preview_slope.legend.json`
- `W4_preview_roughness.legend.json`
- `W4_preview_temperature.legend.json`
- `W4_preview_biome.legend.json`

#### 通用采样与拉伸规则

- 下采样统一使用“区域平均（area average）”，禁止最近邻。
- 连续量拉伸优先使用百分位：`min=P5`，`max=P95`，避免极值污染。
- 分辨率默认 `512 x 512`（可配，但默认固定）。

#### 1) 高程图（Height）

A. 阴影增强（Hillshade）：

- 从高程计算坡度与坡向，固定光源方向 `315°`（西北）。
- 亮面=面向光源，暗面=背光。
- 合成公式：`final_color = elevation_color * 0.6 + hillshade_gray * 0.4`

B. 分级色带（禁止简单线性渐变）：

- `< sea level`：深蓝
- `0–70`：浅绿
- `70–110`：绿
- `110–160`：黄棕
- `160–220`：棕
- `>220`：灰白

C. 等高线：

- 使用等值线算法绘制 contour（建议按固定高差间隔）。
- 等高线叠加在高程底图上，颜色与底图保持足够对比。

#### 2) 斜率图（Slope）

问题口径：当前噪点重（雪花）且颜色连续过渡导致结构不清晰。  
修正规范：

- 下采样改为区域平均。
- 拉伸使用 `P5–P95`。
- 使用离散分档（禁止连续渐变），建议 5 档：
  - `0–5°`：平地（绿色）
  - `5–15°`：缓坡（浅黄）
  - `15–30°`：中坡（橙）
  - `30–45°`：陡坡（红）
  - `>45°`：悬崖（紫/深红）

#### 3) 崎岖度图（Roughness）

- 同样采用：区域平均下采样 + `P5–P95` 拉伸 + 分档显示。
- 档位数建议与 slope 一致（5 档），便于 AI 视觉对齐。

#### 4) 温度图（Temperature）

- 同样采用：区域平均下采样 + `P5–P95` 拉伸 + 分档显示。
- 档位数建议 `5~7` 档，表达冷温带梯度，不使用纯连续渐变。

#### 5) 群系图（Biome）

A. 使用超类（super-class）分色，不再“每个 biome 一个颜色”：

- 海洋/河流：蓝
- 沙漠/恶地：黄红
- 草原/平原：浅绿
- 森林：深绿
- 丛林：翠绿
- 山地：灰
- 雪地：白
- Nether/End：紫黑

建议总色类控制在 `8–12` 种。


#### legend.json 最小字段（每图）

```jsonc
{
  "image": "W4_preview_slope.png",
  "type": "slope",
  "resolution": [512, 512],
  "downsample": "area_average",
  "stretch": { "mode": "percentile", "p_min": 5, "p_max": 95 },
  "bins": [
    { "label": "0-5deg", "range": [0, 5], "color": "#4CAF50" }
  ],
  "style": {
    "discrete": true,
    "saturation_scale": 1.0,
    "hillshade": null,
    "contour": null
  }
}
```

高程图的 `style` 需包含：

- `hillshade.azimuth_deg = 315`
- `blend.elevation_weight = 0.6`
- `blend.hillshade_weight = 0.4`
- `contour.enabled = true`

### 产出文件详解

这一步的产出分为 **“给 AI 看的说明书”** 和 **“给程序查的数据库”**。

#### 1. 给 AI 看的：世界图集 (World Atlas)
**文件名**：`W3_ContinentMeta.json` (对应你提供的 `W4terra_script_regions_debug.json`)
**用途**：T1 阶段 AI 决定文明类型和目标大陆的唯一依据。
**存放位置**：`/saves/<WorldName>/terra_script/world/`

```jsonc
[
  {
    "id": 12,                 // 区域ID
    "type": "CONTINENT",      // CONTINENT / ISLAND / OCEAN
    
    // 1. 地理硬指标 (Metrics)
    "metrics": {
      "area_pixels": 123456,  // 实际大小 (Chunks)
      "center_x": -512,
      "center_z": 1024,
      "avg_height": 78,       // 决定是否是高原大陆
      "max_relief": 52.0,     // 最大高差
      "roughness": "1.34"     // 决定是平坦还是崎岖 (AI据此选高山/平原文明)
    },

    // 2. 气候概况 (Climate)
    "climate": {
      "avg_temp": "0.62",     // 决定寒带/温带/热带文明
      "temp_range": "[0.12, 1.03]",
      // 4x4 网格：让 AI 知道冷暖分布 (例如：北冷南热)
      "temp_distribution_4x4": [
        [0.6, 0.6, 0.7, 0.8],
        [0.5, 0.6, 0.7, 0.8],
        [0.4, 0.5, 0.6, 0.7],
        [0.3, 0.4, 0.5, 0.6]
      ]
    },

    // 3. 生态资源 (Ecology)
    "ecology": {
      // 主导群系：决定文明的基础风格 (森林精灵 vs 沙漠游牧)
      "dominant_biomes": [
        {"id": "minecraft:plains", "pct": 0.41},
        {"id": "minecraft:forest", "pct": 0.22}
      ],
      // 稀有发现：可能成为首都选址的加分项
      "rare_finds": [
        {"id": "minecraft:bamboo_jungle", "pos": "-480,980"}
      ]
    }
  }
  // ... 其他大陆
]
```

#### 2. 给程序查的：地形大数据库 (Terrain Database)
**文件名**：`W4_TerrainFacts.dat`
**用途**：T2 选址、T3 扩张、C 阶段城市生成时，程序快速读取地形数据，无需重新进游戏扫描。
**存放位置**：`/saves/<WorldName>/terra_script/world/`

```text
// NBT 结构示意
root
  global: { seed, radius, step, width, height }
  
  // 核心数据：按区域存储的详细数组
  regions: [
    { 
      id: 12,
      bounds: { minX, minZ, w, h },
      step: 2, // 采样精度
      
      // 下面是体积巨大的原始数组
      pixels: [ ...ScanPixel对象的序列化... ], 
      slope: [ ...long array... ],     // 坡度图
      roughness: [ ...long array... ], // 崎岖度图
      tpi: [ ...long array... ]        // 地形位置指数图
    },
    // ... 其他区域
  ]
```

#### 3. 索引文件 (Technical Index)
**文件名**：`W4_TerrainSummary.json`
**用途**：程序快速定位 DAT 文件中的数据段（例如：知道 ID=12 的大陆在 DAT 的什么位置，宽高是多少），避免一次性加载几百 MB 的 DAT。
**存放位置**：`/saves/<WorldName>/terra_script/world/`

```jsonc
{
  "regions": [
    {
      "id": 12,
      "minX": -1024,
      "minZ": -768,
      "w": 512,
      "h": 384,
      "step": 2,
      "data_source": "W4_TerrainFacts.dat" // 指向 DAT 文件
    }
  ]
}
```

#### 4. 世界特征总览

**用途**：此处的数据为各种总结性数据，包括世界总大小、各个地貌特征如高度、崎岖度、TPI的取值范围，用于给后续AI调用时提供参考

**产出**：`W4_WorldSummary.json`，随`W4_get_world_atlas`一起返回。
**存放位置**：`/saves/<WorldName>/terra_script/world/`

---

## W阶段 MCP 接口对照 (W1/W2 不启用)

> 以下接口均为 W3/W4 阶段使用，返回体里包含 `step` 字段便于对齐流程阶段。

### 0) `W3_get_continents`（W3 大陆/海洋列表）
- **用途**：获取 W3 聚类后的大陆/海洋列表
- **HTTP**：`GET /continents`
- **输入参数**：无
- **输出**：
```jsonc
{
  "step": "W3",
  "continents": [ /* W3 聚类结果 */ ]
}
```

### 1) `W4_get_world_atlas`（W4 汇总入口）
- **用途**：获取世界图集 + 世界特征总览
- **HTTP**：`GET /world_atlas`
- **输入参数**：无
- **输出**：
```jsonc
{
  "step": "W4",
  "atlas": [ /* W3_ContinentMeta.json 内容 */ ],
  "summary": { /* W4_WorldSummary.json 内容 */ }
}
```

### 2) `W4_world_summary`（W4 世界特征总览）
- **用途**：只读取世界总体统计
- **HTTP**：`GET /world_summary`
- **输入参数**：无
- **输出**：
```jsonc
{
  "step": "W4",
  "summary": { /* W4_WorldSummary.json */ }
}
```

### 3) `W4_terrain_summary`（W4 索引文件）
- **用途**：读取 DAT 索引，定位每个 Region 的数据段
- **HTTP**：`GET /terrain_summary`
- **输入参数**：无
- **输出**：
```jsonc
{
  "step": "W4",
  "summary": {
    "regions": [
      { "id": 12, "minX": -1024, "minZ": -768, "w": 512, "h": 384, "step": 2, "data_source": "W4_TerrainFacts.dat" }
    ]
  }
}
```

### 4) `W4_scan_local_candidates`（W4 局部候选点扫描）
- **用途**：基于 W4_TerrainFacts 的 slope/tpi 快速筛选候选点
- **HTTP**：`POST /query_region`
- **输入参数**：
```jsonc
{
  "region_id": 12, // 或 territory_id
  "min_slope": 0.2,
  "max_slope": 1.0,
  "min_tpi": -0.5,
  "max_tpi": 2.0,
  "limit": 5
}
```
- **输出（Q1）**：候选点列表 + `group_ascii_maps` + `visual_map` + `preview_overlay`，并写入待选择缓存（流程暂停，等待人工触发 Q2）。

### 5) `Q2_pick_query_region_cluster_point`（Q2 人工选簇取点）
- **用途**：在 Q1 结果上人工选择簇，并按模式提取最终坐标点。
- **HTTP**：`POST /query_region_pick`
- **输入参数**：
```jsonc
{
  "query_id": "region_12_1700000000000", // 或 target_type + target_id 读取最新挂起记录
  "cluster_id": 3,                        // 可选：cluster_id / label / preview_label 三选一
  "point_mode": "random_cardinal"         // center|north|south|east|west|random_cardinal
}
```
- **输出（Q2）**：
```jsonc
{
  "step": "Q2_PICK_CLUSTER_POINT",
  "selected_cluster": { "cluster_id": 3, "label": "A3", "preview_label": "C" },
  "selected_point": { "x": -1180, "z": 550 }
}
```

# 二、国度构造部分


## T1 国度和区域蓝图生成（AI）
**核心逻辑**：T1 不再只做“国家设定”，还要做“多候选战略区筛选”。  
流程与 T2 一致：先扫描多个感兴趣区域并生成预览挂起（Q1），再人工触发选簇取点（Q2），最后提交蓝图。

*   **AI 做什么**：
  1. 调用 `W4_get_world_atlas` 获取 `atlas + world_summary`。
  2. 调用 T1 预览图接口获取候选区域地理图（高度+等高线、山体阴影等）。
  3. 根据文明定位，定义 2~3 组兴趣条件（例如高海拔、低坡度、沿海、峡谷）。
  4. 调用 `scan_local_candidates(region_id=...)` 进入 Q1，拿到多簇候选 + ASCII + 叠图预览，并生成待选缓存。
  5. AI根据簇预览图，触发 Q2（`/query_region_pick`）选择最优簇和取点模式（center / north / south / east / west / random_cardinal）。
  6. 提交 `t1_generate_blueprint`，把最终 `target_continent_id` 与扩张参数固化。

*   **程序 做什么**：
  * 提供 `W4_get_world_atlas` 和 `scan_local_candidates`。
  * 为 T1 候选区域输出 PNG 预览图（遵循 W4 同款图例规则）。
  * 返回候选簇列表（每个簇都带 `cluster_id`、关键点、ASCII 图）和 `preview_overlay`。
  * 写入 Q1 待选缓存，等待AI预览图片后触发 Q2。
  * 依据 `world_summary` 约束 `base_power` 合理范围（避免扩张力过大/过小）。
  * 存储蓝图到 `T1_Blueprint.json`。

*   **AI 可调用数据 (MCP)**：
  * `get_world_atlas()`
  * `get_t1_preview_maps(...)`（拟新增）
  * `scan_local_candidates(...)`
  * `t1_generate_blueprint(...)`

*   **产出 (JSON)**：`T1_Blueprint.json`（列表，按 `territory_id` 去重）
*   **产出 (PNG + Legend)**：`T1_preview_*.png` + `T1_preview_*.legend.json`
*   **存放位置**：`/saves/<WorldName>/terra_script/territories/`

### T1 预览图渲染规范（v1，沿用 W4 规范）

> 目标：给 AI 在 T1 选址时提供“可直接视觉推理”的区域图。整体规则与 W4 一致，重点新增山体阴影独立图层。

#### 输出清单（固定）

- `T1_preview_height.png`（必须叠加等高线）
- `T1_preview_hillshade.png`（新增：山体阴影图，灰度）
- `T1_preview_slope.png`
- `T1_preview_biome.png`

每张图同时输出对应图例：

- `T1_preview_height.legend.json`
- `T1_preview_hillshade.legend.json`
- `T1_preview_slope.legend.json`
- `T1_preview_biome.legend.json`

#### 关键约束

- 高度图 `T1_preview_height.png`：必须包含 contour（等高线）。
- 山体阴影图 `T1_preview_hillshade.png`：独立输出，不与其他图合并。
- 其余拉伸、分档、下采样策略默认复用 W4（区域平均 + 百分位拉伸 + 离散分档）。

### 🔌 T1 MCP 接口
- **方法名**：`t1_generate_blueprint`
- **能力**：读取 `world_atlas + world_summary`，并按摘要约束扩张力范围后生成并提交蓝图
- **输入参数（关键）**：
  - `territory_id`, `name`
  - `target_continent_id`（或 `auto_pick_region=true`）
  - `base_power`（会被 world_summary 约束）
  - `base_move/slope_penalty/water_penalty/forest_penalty`
  - `preferred_biomes/avoid_biomes`
- **输出**：
```jsonc
{
  "step": "T1",
  "ok": true,
  "message": "blueprint generated and saved",
  "blueprint": { /* 提交的蓝图 */ }
}
```
- **可选**：`GET /t1_blueprint` 返回全部蓝图列表

### 📄 产出示例：T1_Blueprint.json


这是 AI 提交给程序的“建国申请书”：

```jsonc
{
  "territory_id": "kingdom_iron_peak", // 唯一ID
  "name": "铁峰堡",
  "target_continent_id": 12,           // 关键：AI 选定了在 12 号大陆建国（基于 Atlas 数据）
  
  // 1. 故事与设定 (Flavor)
  "narrative": {
    "theme": "HIGHLAND_FORGE",         // 风格标签
    "description": "一个依附于崎岖山脉（Roughness 1.34）建立的工业王国，利用地形防御外敌。",
    "ruler": "Thane Korgan",
    "color": "0xA52A2A"                // 地图颜色：红褐色
  },

  // 2. 扩张模型 (Mechanics) - 决定了 T3 怎么画线
  "expansion_policy": {
    "base_power": 1500,                // 扩张总能量（决定国土最大理论面积）
    
    // 扩张消耗配置 (Cost Weights)
    // 算法逻辑：Dijkstra Cost = Base + (Slope * W_Slope) + (Water * W_Water) ...
    "costs": {
      "base_move": 1.0,                // 基础平地消耗
      "slope_penalty": 0.2,            // 对坡度的敏感度 (0.2=很低，说明擅长爬山)
      "water_penalty": 8.0,            // 对水的敏感度 (8.0=很高，说明很难跨海)
      "forest_penalty": 1.5,           // 穿林消耗
      
      // 偏好修正 (Bonus)
      "preferred_biomes": ["minecraft:jagged_peaks", "minecraft:meadow"], // 遇到这些群系消耗减半
      "avoid_biomes": ["minecraft:swamp"] // 遇到这些群系消耗加倍
    }
  }
}
```
---

## T2. 多维勘探与首都决策 (Multi-Criteria Exploration)

**核心逻辑**：T2 使用“多兴趣组叠加 + 综合 ASCII + 区域ID决策”。  
单簇 ASCII 只用于看局部细节；**最终决策必须基于一张综合 `visual_map`**，这样 AI 才能看到 A/B 簇的相对关系（相邻、包夹、断裂、通道）。

*   **步骤 2.1：下发勘探任务 (AI 动作)**
    * AI 定义多个兴趣组（如 A=山地防御带，B=平原补给带，C=河谷通道）。
    * AI 调用 MCP：`scan_local_candidates(...)`（建议扩展支持 `interest_groups`）。

    **请求示例**：
    ```jsonc
    {
      "region_id": 12,
      "limit_per_group": 3,
      "interest_groups": [
        { "id": "A", "criteria": { "min_tpi": 1.0, "min_slope": 0.8 } },
        { "id": "B", "criteria": { "max_slope": 0.35, "min_tpi": -0.5, "max_tpi": 0.6 } }
      ]
    }
    ```

*   **步骤 2.2：执行扫描与聚类 (程序 动作)**
    * 对每个兴趣组独立筛选并 DBSCAN 聚类。
    * 每组保留 TopN（如 A1/A2/A3, B1/B2/B3）。
    * 输出两类可视化：
      - `group_ascii_maps`：每簇单图（局部细节）
      - `visual_map`：所有兴趣组叠加到同一底图（全局关系）

*   **步骤 2.3：返回勘探报告 (程序 返回)**
    * **返回内容必须包含**：
      - `candidates_metadata`：每个簇的标签与坐标（A1/B1...）
      - `visual_map`：综合 ASCII（多组叠加）
      - `candidates[]`：兼容现有结构（每簇 `cluster_id + ascii_map + key_points + metrics`）

    **返回示例（核心字段）**：
    ```jsonc
    {
      "candidates_metadata": [
        { "label": "A1", "group": "A", "cluster_id": 1, "center": { "x": -1200, "z": 500 } },
        { "label": "B1", "group": "B", "cluster_id": 7, "center": { "x": -1150, "z": 600 } }
      ],
      "visual_map": [
        "~~~~~~~~~~~~~~~~~~~~",
        "~~~~~~AAAAAA~~~~~~~~",
        "~~~~~~AAAAAA~~~~~~~~",
        "~~~~~~AAAABBBBBB~~~~",
        "~~~~~~~BBBBBBBB~~~~~",
        "~~~~~~~BBBBB........"
      ],
      "candidates": [ /* 兼容当前单簇输出 */ ]
    }
    ```

*   **步骤 2.4：视觉推理与定都 (AI 决策)**
    * AI 先在 `visual_map` 选“关系最优点”（如 A1 与 B1 交界）。
    * 再回到单簇详情校正坐标，最终给出 `capital_x/z`。
    * 调用 `establish_territory` 提交首都与势力参数。

*   **产出 (JSON)**：`T2_CapitalData.json` / `TerritorySummary.json`
*   **存放位置**：`/saves/<WorldName>/terra_script/territory/<territory_id>/T2/`

---

### 🏛️ T3：势力范围扩张 (Territory Expansion)

**核心变更**：从“响应式计算”改为“批处理计算”。将 `TerritoryManager` 拆分为 **配置态** 和 **计算态**。

#### 1. 输入数据 (Input)
这部分由 T1 (蓝图) 和 T2 (定都) 填充完毕。

在 `TerritoryManager` 中，我们需要一个暂存区：
```java
// 状态标记
private static boolean isLocked = false; 

// 暂存列表 (对应 T1/T2 的产物)
// 只有在 T3 触发时，才把这些 Config 拿去跑 Dijkstra
private static final List<TerritoryConfig> pendingFactions = new ArrayList<>();
```

**TerritoryConfig 增强字段**：
保留目前的字段(`maxPower`, `mountainCost` 等)。增加：
*   `biomes_preference`: `Set<String>` (偏好群系 ID，用于在 Dijkstra 中计算额外的 bonus/penalty)。
*   `type`: `String` (文明类型枚举，用于给 AI 看)。

#### 2. 处理逻辑 (Process)
将原本的 `recalculateAll()` 改名为 `runT3Expansion()`，逻辑微调：

1.  **锁定状态**：设置 `isLocked = true`，禁止再添加新国家或移动首都。
2.  **加载地形**：从 `ScanResultHolder` 获取 W4 的低/中精度地形数据（无需方块级，扩张用 Chunk 级或 Grid 级足够）。
3.  **多源 Dijkstra (保留原逻辑)**：
    *   现有的 `PriorityQueue` 和 `while(!pq.isEmpty())` 逻辑完全可用。
    *   **改进点**：在计算 `moveCost` 时，加入 `biomes_preference` 的权重（例如：精灵在森林里消耗减半）。
4.  **飞地处理**：`fillEnclaves` 保留，用于填补空洞。
5.  **生成统计**：`analyzeTerritories` 保留，计算面积和邻国。
6.  **阶段联动触发**：T3 成功结束后，程序立即调用一次 `T4` 触发接口（内部调用），对本批次全部国度进入方块级统计与战略场计算。

#### 3. 输出数据 (Output)

**A. `T3_Map.dat` (重数据)**
*   **内容**：全图 Chunk 的归属关系。
*   **结构**：
    ```java
    // 伪代码结构
    Map<Long, Short> chunkOwnerMap; // ChunkPos(long) -> TerritoryIndex(short)
    // 或者用二维数组 int[w][h] 压缩存储
    ```
*   **用途**：游戏内快速判断“这里是谁的地盘”。

**B. `T3_ExpansionReport.json` (给 AI 看)**
*   对应你代码里的 `TerritoryStats` 类。
*   **内容**：
    ```jsonc
    {
      "territories": [
        {
          "id": "kingdom_iron_peak",
          "capital": { "x": -4500, "z": 1200 },
          "stats": {
            "total_chunks": 450,
            "neighbors": ["kingdom_human", "ocean"], // 也就是 neighborIds
            "biome_composition": { "mountains": 0.8, "plains": 0.2 } // 你的代码已实现
          }
        }
      ]
    }
    ```

---

### 🌊 T3.5：领海层计算（规划占位，暂不开发）

**阶段定位**：  
`T3.5` 用于在 `T3` 陆地扩张结果之外，额外生成“领海层（maritime layer）”，作为海域归属与后续海权玩法的基础数据。

**当前约束**：
- 该阶段仅立项记录，不进入当前工作流执行链。
- 当前版本仍保持 `T3` 只处理陆地扩张，不放开水体扩张。
- `T3.5` 未来应与 `T3` 分离实现，避免影响现有陆地扩张稳定性。

**预期输入（未来）**：
- `T3` 的陆地边界结果（已确认归属的陆地格/区块）
- `W3/W4` 海陆与地形数据（用于海域可达性/边界裁剪）

**预期输出（未来）**：
- `T3_5_MaritimeMap.dat`（海域归属层）
- `T3_5_MaritimeReport.json`（领海面积、邻海势力、关键海峡等摘要）

---

### 🔭 T4：全境精细化扫描与战略场 (Strategic Scan)

**核心变更**：这是一个全新的逻辑模块。T3 只是圈了地（Chunk 级），T4 要进去看细节（Block 级）。

**触发语义调整**：
*   不再由 AI 按需决定是否触发。
*   在 T3 完成后，由程序自动触发 T4。
*   触发接口仍保留，但用途改为“程序内部调用”（例如 `WorkflowController` 在 T3 结束后调用），不对 AI 暴露为决策型接口。

#### 1. 输入数据 (Input)
*   `territoryIds`: 本次 T3 批处理得到的国家 ID 列表（通常是全部已配置国家）。
*   `T3_Map.dat`: 知道哪些 Chunk 属于这个国家。
*   `MinecraftServer`: 需要读取真实的 World 对象。

#### 2. 处理逻辑 (Process - 新增 TerritoryScanner 类)

**步骤 0：程序触发入口**
*   T3 结束后，程序调用 `T4_trigger_after_t3(batchId, territoryIds)`（命名可按代码实际调整）。
*   该接口可以手工调试调用，但语义上属于“程序接口”，不是 AI 工作流接口。

**步骤 A：方块级数据采集**
遍历该国度名下的所有 `claimedChunks`：
1.  **加载 Chunk**：(注意性能，控制每 tick 处理数量，或者异步读取 IChunk)。
2.  **提取数据**：
    *   **Height**: `WORLD_SURFACE` 高度。
    *   **Slope**: 计算局部 3x3 高差。
    *   **Water**: 是否是水方块。
    *   **River**: 是否是河流群系。

**步骤 B：战略场计算 (Strategic Fields)**
在内存中构建一个该国度的局部网格图，计算：
1.  **Distance to Border (DTB)**：
    *   把所有 **非本国** 的像素设为 0。
    *   运行 BFS (Breadth-First Search) 向内泛洪。
    *   结果：每个点的值 = 离最近边境的格子数。
    *   *价值：AI 知道哪里是腹地（安全），哪里是前线。*
2.  **Distance to Capital (DTC)**：
    *   从首都开始 BFS。
    *   *价值：AI 知道统治半径。*

#### 3. 输出数据 (Output)

**A. `T4_HighRes.dat` (核心资产)**
这是后续 C 阶段（城市生成）**唯一**需要读取的地形文件。
*   **格式**：自定义二进制格式。
*   **包含通道**：
    *   `Height (short)`
    *   `BiomeID (byte lookup)`
    *   `WaterMask (bit)`
    *   `StrategicValue (byte)`: 综合了 DTB 和 DTC 的评分。

**B. `T4_Strategy.json` (给 AI 的战略简报)**
```jsonc
{
  "territory_id": "kingdom_iron_peak",
  "bbox": { "minX": -5000, "maxX": -4000, ... },
  
  // 战略摘要
  "analysis": {
    "border_security": "HIGH", // 基于边界地形算出来的（比如边界全是山）
    "hinterland_depth": "DEEP", // 腹地很深
    "choke_points": [ // 识别出的咽喉点坐标
      { "x": -4200, "z": 1300, "desc": "Mountain Pass" }
    ]
  }
}
```

**C. `T4_BatchReport.json` (给工作流编排器)**
```jsonc
{
  "step": "T4",
  "triggered_by": "T3",
  "batch_id": "t3_2026-02-07_01",
  "territories_total": 3,
  "territories_succeeded": 3,
  "territories_failed": 0
}
```

---





# 三、城市生成部分（你的城市 9阶段流水线，承接国度结果）



输入前提：已冻结世界与国度；城市只在其国度归属内生成。




## C0 冻结城市上下文（不可回滚）


- **程序做什么**：锁定该国度/大陆/地貌缓存版本号；锁定本次城市ID
- **产出**：`C0_Context.json`（引用哪个 Snapshot_vX）
- **存放位置**：`/saves/<WorldName>/terra_script/cities/<city_id>/`


## C1 选址与城市参数（AI）
**目标**：把“选哪里建城”变成标准化输入输出，采用与 T1/T2 相同的“多候选+ASCII+ID选择”。

- **AI做什么**：
  - 调用候选扫描接口拿多个候选区；
  - 选择 `candidate_id`（或 `cluster_id`）并确定中心点；
  - 配置层级权重（程序按 `weight/sum(weight)` 计算层面积占比）；
  - 对 `RING` 层显式指定 `is_wall`（该环层是否作为城墙层）。
- **程序做什么**：
  - 返回候选区列表（带 ASCII + 统计 + 关键点）；
  - 校验中心点是否在 territory 内；
  - 固化 C1 设计意图。
- **建议输入（C1_Intent）**：
```jsonc
{
  "city_id": "city_foo",
  "territory_id": "kingdom_iron_peak",
  "candidate_id": "cand_03",
  "center_x": -1180,
  "center_z": 550,
  "ecology_policy": "BALANCED",
  "layers": [
    { "id": "core", "type": "CORE", "weight": 4, "is_wall": false },
    { "id": "urban_1", "type": "URBAN", "weight": 6, "is_wall": false },
    { "id": "ring_1", "type": "RING", "weight": 3, "is_wall": true },
    { "id": "buffer", "type": "BUFFER", "weight": 2, "is_wall": false }
  ],
  "reason": "near ridge pass and river access"
}
```
- **建议输出（C1_Result）**：
```jsonc
{
  "step": "C1",
  "ok": true,
  "city_id": "city_foo",
  "selected_candidate": "cand_03",
  "validated_center": { "x": -1180, "z": 550 }
}
```
- **存放位置**：`/saves/<WorldName>/terra_script/cities/<city_id>/C1_Intent.json`


## C2 城市领地生成（程序）
**目标**：由程序根据 C1 意图完成城市占地与分层，输出稳定的结构化结果。  
**核心口径**：C2 不再让 AI 提面积规模参数；面积由程序方块级扩张算法自动计算。

- **程序做什么**：
  - 从 `center_x/z` 进行方块级扩张（类似扩张力模型，但作用于 city 域）；
  - 生成多层结构（`CORE/URBAN/RING/BUFFER`）；
  - CORE代表核心城区，URBAN代表则是普通城市区，RING为环状带，可以只为城墙，BUFFER则是缓冲区，用于于其他自然环境过渡，比如像篝火啊这种代表人烟气息的结构
  - 根据 C1 的层权重自动分配各层占比；
  - 对 `RING` 层依据 `is_wall` 决定是否写入墙体层标记。
- **AI做什么**：无（硬规则执行）。
- **输入**：`C1_Intent.json`
- **输出**：
  - `C2_Claim.dat`（block/chunk -> layer 映射索引）
  - `C2_ClaimSummary.json`（程序计算的总面积、每层面积、层占比、边界 bbox）
  - `C2_satellite_preview.png` + `C2_satellite_preview.legend.json`（仅城市范围、默认 step=1 精细扫描）
  - `terra_script_city_c2_scan_<city_id>.dat`（城市范围 step=1 扫描数据，供 C3 预览与后续可视化复用）
```jsonc
{
  "step": "C2",
  "ok": true,
  "city_id": "city_foo",
  "blocks_total": 112384,
  "weight_sum": 15,
  "layers": [
    { "id": "core", "type": "CORE", "weight": 4, "is_wall": false, "blocks": 30012, "ratio": 0.267 },
    { "id": "urban_1", "type": "URBAN", "weight": 6, "is_wall": false, "blocks": 44783, "ratio": 0.399 },
    { "id": "ring_1", "type": "RING", "weight": 3, "is_wall": true, "blocks": 22412, "ratio": 0.199 },
    { "id": "buffer", "type": "BUFFER", "weight": 2, "is_wall": false, "blocks": 15177, "ratio": 0.135 }
  ]
}
```
- **存放位置**：`/saves/<WorldName>/terra_script/cities/<city_id>/`

### C2 结束后自动动作（新增）

- C2 完成后自动触发一次“城市范围地貌精细扫描”（默认 `step=1`，仅扫描城市占用范围 + padding）。
- 程序立即导出城市卫星预览图到城市目录，供后续 C3/C4 决策前复核。
- MCP 返回体会附带 `ai_should_pause=true` 和 `next_action=STOP_CURRENT_STEP_AND_REVIEW_C2_SATELLITE_PREVIEW`，提示 AI 终止当前步骤并先审图再继续。


## C3 多边形/区划种子与分区（程序主导，AI可选增强）
**目标**：在 C2 占地基础上生成稳定可解释的区划单元，供后续 C3.5/C4 使用。  
**关键约束**：多边形初划分通常不考虑 layer，因此 C3 必须补一个“按 layer 二次拆分”步骤。

- **当前落地状态判定（用于开发检查）**：
  - **半步合格**：已经有可用区划单元（district）+ 每个区划可追溯 layer，可支撑后续 C4 打标签。
  - **完全合格**：除上述外，还补齐 `C3_GlobalPolygons.json`、`C3_Districts.json`、`C3_DistrictIndex.dat` 三件套，且存在 `source_polygon_id`（可追溯 Phase A -> Phase B）。

- **程序做什么（两阶段）**：
  - **Phase A：全域多边形划分（不看 layer）**
  - 在城市总占地上生成种子点，运行 Voronoi/Lloyd，得到 `global_polygon_id`。
  - **Phase B：按 layer 二次拆分**
  - 对每个全域多边形与 `C2` 的 layer mask 做相交（`polygon × layer`）。
  - 将跨层多边形拆成多个 `layered_polygon`，确保每个最终区划只属于一个 layer。
  - 计算每个最终区划统计值（面积、形状紧致度、坡度/高差、连通性）。
- **AI可选做什么**：
  - 仅调策略参数（如核心区密度、外围稀疏度、工业区远离核心权重）；
  - 不直接手动点每个种子。
- **输入**：
  - `C2_Claim.dat`
  - `C2_ClaimSummary.json`
  - 可选 `C3_Policy.json`
- **输出**：
  - `C3_GlobalPolygons.json`（Phase A 原始多边形，不分层）
  - `C3_Districts.json`（Phase B 最终区划：`district_id`、`source_polygon_id`、`layer`、块/区块列表、统计）
  - `C3_DistrictIndex.dat`（block/chunk -> district_id）
  - `C3_polygon_preview.png` + `C3_polygon_preview.legend.json`（底图数据源固定使用 C2 的 `step=1` 扫描文件）
```jsonc
{
  "step": "C3",
  "ok": true,
  "city_id": "city_foo",
  "global_polygon_count": 12,
  "district_count": 19,
  "districts": [
    {
      "district_id": "d_01_core",
      "source_polygon_id": "p_01",
      "layer": "core",
      "blocks": 8412,
      "centroid": { "x": -1168, "z": 544 }
    },
    {
      "district_id": "d_01_ring",
      "source_polygon_id": "p_01",
      "layer": "ring_1",
      "is_wall": true,
      "blocks": 1205,
      "centroid": { "x": -1152, "z": 528 }
    }
  ]
}
```
- **存放位置**：`/saves/<WorldName>/terra_script/cities/<city_id>/`


## C3.5 结构预处理与功能先验（程序）

- **目标**：把“结构级信息”前置到 C4 之前，先形成可供 AI 分类的结构特征库与功能总枚举表。
- **程序做什么**：
  - 扫描可用模板库，生成结构级元信息（不做最终筛选与摆放）。
  - 为每个结构计算/标注以下字段：
  - `size`：`length`、`width`、`height`；
  - `orientation`：拼图方块朝向、入口朝向、可旋转集合；
  - `piece_role`：`START` / `MIDDLE` / `END` / `SINGLE`；
  - `style_score`：按既定风格轴（如古典/军事/商业/居住）输出 0~1 分值向量；
  - `function_candidates`：结构可能承担的功能候选及置信度。
  - 聚合全量模板，产出城市级 `function_enum_table`（功能总枚举），作为 C4 的硬约束输入。
- **AI做什么**：
  - 不直接改结构元数据；
  - 在 C4 使用 `function_enum_table` 和 `function_candidates` 做功能决策。
- **输入**：
  - `C3_Districts.json`
  - 模板库原始清单（结构文件 + 现有标签）
  - 可选 `C3_5_StructurePreprocessPolicy.json`
- **输出**：
  - `C3_5_StructureCatalog.preprocessed.json`
  - `C3_5_FunctionEnumTable.json`
  - `C3_5_StructureFeatureIndex.dat`（可选，用于快速查检）
```jsonc
{
  "step": "C3.5",
  "ok": true,
  "city_id": "city_foo",
  "function_enum_table": ["civic_center", "market", "residential_mid", "workshop", "fortification"],
  "structures": [
    {
      "structure_id": "house_a_01",
      "size": { "length": 13, "width": 9, "height": 11 },
      "orientation": {
        "jigsaw_facing": ["north", "south"],
        "entry_facing": "south",
        "rotations": [0, 90, 180, 270]
      },
      "piece_role": "MIDDLE",
      "style_score": { "classical": 0.73, "military": 0.12, "commercial": 0.48, "residential": 0.82 },
      "function_candidates": [
        { "function": "residential_mid", "score": 0.86 },
        { "function": "market", "score": 0.33 }
      ]
    }
  ]
}
```
- **存放位置**：`/saves/<WorldName>/terra_script/cities/<city_id>/`

## C4：多边形 Tag 标记（Polygon Tagging）

- **目标**：为 C3 的每个多边形区块打功能与地形语义标签，不改几何边界，不做分组合并；分组仍由 C5 处理。
- **当前状态（2026-03-06）**：本阶段方案已在网页端实验验证；主工程代码尚未正式落地该版本 C4。

### 一、输入

#### 1）图像输入

- `city_c3_polygon_preview`（城市多边形总预览图）
    - 用于 AI 理解多边形边界、相邻关系、城市结构。
- 城市总体地貌特征图（3 张）
    - 阴影图（hillshade）
    - 崎岖度图（roughness）
    - 高度图（height）
    - 用于约束功能与地形匹配，避免出现不自然布局。

多边形总预览图图例/参数：

| 参数                           | 说明           |
|------------------------------|--------------|
| city_id                      | 城市ID         |
| origin_x / origin_z          | 世界坐标原点       |
| width_blocks / height_blocks | 城市扫描尺寸       |
| district_count               | 多边形数量        |
| district_codes               | 多边形编号与 layer |
| ownership_step               | 扫描精度         |

#### 2）数据输入

表1：多边形基础信息表（程序生成，来源 C3 polygon ownership）

| 字段            | 类型     | 说明                          |
|---------------|--------|-----------------------------|
| district_code | int    | 多边形编号                       |
| district_id   | int    | 多边形ID                       |
| layer_index   | int    | 城市层级                        |
| zone_type     | string | 区域类型（CORE / URBAN / BUFFER） |

表2：层级定义表（来源 C2 layer labels）

| 字段          | 类型     | 说明   |
|-------------|--------|------|
| layer_index | int    | 层级编号 |
| layer_type  | string | 层级名称 |
| chunk_count | int    | 面积规模 |
| centroid_x  | float  | 层级中心 |
| centroid_z  | float  | 层级中心 |

表3：功能枚举表（来源 C3.5 全结构功能枚举）

| 字段            | 类型     | 说明   |
|---------------|--------|------|
| function_id   | int    | 功能ID |
| function_name | string | 功能名称 |

示例：住宅、商业店铺、道路段、公园绿地、办公楼、工厂仓库、学校、医院诊所、宗教建筑、防御塔楼、桥梁、广场、市场、市政厅、供水设施、农场、牧场、港口。

### 二、输出

数据输出：表4 多边形 Tag 表

| 字段            | 类型     | 说明    |
|---------------|--------|-------|
| district_code | int    | 多边形编号 |
| zone_type     | string | 区域层级  |
| function      | string | 功能标签  |
| terrain_tag   | array  | 地形标签  |
| role_tag      | array  | 城市角色  |

示例：

| district_code | zone_type | function | terrain_tag   | role_tag         |
|---------------|-----------|----------|---------------|------------------|
| 10            | CORE      | 港口       | coastal,flat  | logistics_anchor |
| 15            | BUFFER    | 港口       | coastal_slope | shore_support    |
| 5             | BUFFER    | 港口       | cliff         | breakwater       |

推荐文件产物：

- `C4_PolygonTagTable.json`（AI 产物）
- `C4_PolygonTagTable.validated.json`（程序校验后，供 C5 使用）
- `C4_FunctionPlan.validated.json`（兼容旧 C5 消费口径，可由程序从 `function/role_tag` 映射生成）

可回滚点：本阶段可反复打标直到满意。
存放位置：`/saves/<WorldName>/terra_script/cities/<city_id>/`


## C5 模块拓扑合并（程序）

- **目标**：程序根据相邻关系 + 功能标签自动合并 group，输出稳定可解释的模块组。
- **职责边界**：
  - AI 不再直接给 group；
  - 程序在 C5 完成 grouping、连通性检查与防抖修正。
- **程序做什么**：
  - 以 `C4_FunctionPlan.validated.json` 的 `primary_function` 为主键，按拓扑邻接自动合并；
  - 计算每个 group 的 `centroid`、`area_blocks`、`connectivity`（`component_count`、`compactness`）；
  - 输出 `C5_ModuleGroups.json` + 预览图；
  - 产出合并日志（规则命中、吞碎片、拆分修正）。
  - 在 `cross_layer_merge=false` 默认下，同一 layer 内可存在多个 group（由 C4 层内多功能 + 拓扑连通共同决定）。
- **默认合并条件（可配置）**：
  1. 区划必须相邻（共享边，不接受仅角点接触）；
  2. `primary_function` 相同；
  3. `layer` 相同（默认）；可通过 `cross_layer_merge=true` 开关放宽。
- **防抖规则（默认启用）**：
  - 最小面积吞碎片：当区划面积 `< min_district_area`（示例：`2 chunks`）时，优先并入“最相邻大组”；必要时允许跨功能吸附，并写入 `merge_log`；
  - 连通性 sanity check：默认不允许一个 group 含多个离散 component；若出现 `component_count > 1`，自动拆分为多个 group；
  - 紧致度 sanity check：`compactness < min_compactness` 时，执行拆分或近邻重吸附（由策略控制）。
- **AI做什么**：
  - 仅在上游 C4 输出标签；可通过策略文件间接影响 C5，但不直接分组。
- **输入**：
  - `C4_FunctionPlan.validated.json`
  - `C3_Districts.json`
  - 可选 `C5_GroupPolicy.json`
- **输出**：
  - `C5_ModuleGroups.json`
  - `C5_ModuleIndex.dat`（block/chunk -> module_group_id）
  - `C5_merge_preview.png` + `C5_merge_preview.legend.json`
  - `C5_MergeLog.json`
```jsonc
{
  "step": "C5",
  "ok": true,
  "city_id": "city_foo",
  "groups": [
    {
      "group_id": "g_market_01",
      "function": "market",
      "layer": "urban_1",
      "district_ids": ["d_07_u1", "d_09_u1", "d_12_u1"],
      "area_blocks": 18640,
      "centroid": { "x": -1182, "z": 566 },
      "connectivity": {
        "component_count": 1,
        "compactness": 0.71
      }
    }
  ],
  "merge_stats": {
    "input_district_count": 19,
    "output_group_count": 11,
    "fragment_reduction_ratio": 0.42
  },
  "policy": {
    "cross_layer_merge": false,
    "adjacency_mode": "shared_edge_only",
    "min_district_area_chunks": 2,
    "allow_cross_function_absorb_for_tiny": true,
    "split_disconnected_group": true
  },
  "merge_log": [
    {
      "type": "tiny_absorb",
      "district_id": "d_18_u1",
      "from_function": "green_buffer",
      "to_group_id": "g_residential_mid_02",
      "reason": "area_below_min_threshold"
    }
  ],
  "quality": {
    "disconnected_groups": 0,
    "low_compactness_groups": 1
  }
}
```
- **可回滚点**：可回到 C4
- **存放位置**：`/saves/<WorldName>/terra_script/cities/<city_id>/`


## C6 可建造矩形生成

### 阶段定位

C6 的目标收敛为一件事：在每个 `C5 Module Group` 内生成 `1~3` 个可建造主矩形（buildable rectangles）。

* 程序负责提供局部地形图、group 边界、功能约束与结构库。
* AI 负责做“矩形级”的规划决策，而不是直接放建筑。
* C6 的直接产物是可复现的矩形占位结果，供 C7 做组件规划与内置排列/拼图规则选择、C8 做 jigsaw 求解与基台计划、C9 做最终放置。

### C6 核心原则

1. 先矩形，后建筑。
AI 在 C6 只决定“主矩形放哪里、多大、承担什么空间角色”，不决定最终模板实例。
2. 必须落在世界坐标系。
所有矩形都必须用世界坐标表达：`(cx, cz, w, h)`。
3. 只能在当前 group 内规划。
AI 不得跨 group 放置，不得越过当前 group 的允许建造区域。
4. 地形先验必须参与决策。
矩形不能建立在高 roughness、明显断裂、陡坡或不连续地形上。
5. 程序硬校验兜底。
AI 可以提出候选矩形，但 coverage、重叠、越界、非法地形一律由程序复核。

### C6 输入

输入分成五类数据。

#### 1) 地形图输入

给 AI 三张带世界坐标的局部图：

* `height map`
* `hillshade`
* `roughness`

说明：

* 图上的坐标网格沿用 C4 / C5 预览图的世界坐标系。
* 这三张图来自 C2 的局部扫描数据。
* `height` 负责判断高差与平台可能性。
* `hillshade` 负责判断山体形态、坡向与地形转折。
* `roughness` 负责判断哪里适合放规则矩形。

高度分类示例：

* `70-110`：平缓丘陵
* `110-160`：高地
* `>160`：山体

相关图例文件：

* `C2_satellite_preview.legend.json`

作用：

* 判断哪里适合建造
* 判断坡度方向
* 判断哪些区域需要后续平台或挡墙

#### 2) group 边界

程序为当前 group 提供边界摘要与唯一标识，例如：

```json
{
  "code": 10,
  "group_id": "g_port_06",
  "function": "port",
  "layer": "buffer",
  "district_count": 3
}
```

作用：

* 限定 AI 只能在当前 `group_id` 对应区域内规划矩形
* 提供该组的功能语义（如 `port` / `market` / `fortification`）

#### 3) group 多边形结构

来自 C3 / C5 的区划结构信息：

* `district_code`
* `zone_type`
* `layer_index`

例如：

* `CORE`
* `URBAN`
* `BUFFER`

相关图例文件：

* `C3_polygon_preview.legend.json`

作用：

* 控制建筑密度
* 控制建筑规模
* 让 AI 理解当前 group 在整座城市中的空间角色

#### 4) 坐标网格

局部图必须明确给出坐标范围，例如：

* `x: -5480 -> -5350`
* `z: -6760 -> -6690`

作用：

* AI 不能只“看图画框”，而必须在世界坐标系中输出矩形
* 后续 C7 / C8 / C9 直接消费这些坐标，不再重新解释

#### 5) 功能结构库输入

以当前 group 的 `function` 为索引，提供该功能下可用的全部结构配置。

例如 `function = port` 时，输入该功能下全部 starter / module 结构：

```json
{
  "function": "port",
  "structures": [
    {
      "structure_id": "port_harbor_master",
      "category": "starter",
      "footprint": { "w": 20, "h": 12 },
      "height_class": "mid",
      "entry_side": ["south", "west"],
      "requires_near_water": true,
      "requires_platform": true,
      "expandable": true,
      "puzzle_pool": [
        "port_warehouse_block",
        "port_office_block",
        "port_crane_block",
        "port_yard_block"
      ],
      "tags": ["core", "administration", "dock_control"]
    },
    {
      "structure_id": "port_warehouse_block",
      "category": "module",
      "footprint": { "w": 12, "h": 8 },
      "height_class": "low",
      "requires_near_water": false,
      "requires_platform": true,
      "expandable": true,
      "tags": ["storage"]
    }
  ]
}
```

作用：

* 不是让 AI 只选“建筑名”
* 而是让 AI 理解最小需要多大矩形才能容纳 starter 结构
* 让 AI 理解一个主矩形里适合塞哪些 module
* 让 AI 理解哪些结构必须靠海、靠平台、靠主入口
* 让 AI 区分哪些结构应当作为主矩形，哪些只是后续填充部件

### C6 提示词（Prompt）

核心提示词分三部分。

#### 1) 角色

AI 必须被设定为城市规划师，例如：

```text
You are a Minecraft city planner.

Your task is to place 1-3 buildable rectangles
inside the given group area.

The rectangles represent major structures.
```

目的：

* 让 AI 做规划决策，而不是自由发散

#### 2) 地形分析任务

提示词必须要求 AI 先完成地形判断：

1. Analyze terrain height
2. Analyze slope direction
3. Identify flat zones
4. Avoid high roughness areas

这是强约束，否则矩形容易落在悬崖、斜坡折线或破碎地块上。

#### 3) 输出要求

输出格式必须被限制为：

```json
{
  "rectangles": [
    { "cx": -5391, "cz": -6734, "w": 60, "h": 24 }
  ]
}
```

并明确约束：

* `1 <= rectangles <= 3`
* 坐标必须使用世界坐标
* 宽高必须是实际方块尺寸

### C6 AI 内部思考步骤

AI 在本阶段实际需要完成六步决策。

#### Step 1：识别可建区域

根据 `height / hillshade / roughness` 找出低 roughness、地形连续、适合放规则矩形的区域。

#### Step 2：识别该功能的空间组织方式

结合功能结构库，判断：

* 需要几个主矩形
* 每个矩形承担什么角色
* 每个矩形是否需要容纳 starter + puzzle modules

#### Step 3：生成候选矩形

输出 `1~3` 个候选主矩形。

#### Step 4：领土覆盖率检测

不要求矩形 `100%` 全包含，但要求矩形面积至少 `80%` 落在当前 group 的允许建造方块内。

#### Step 5：矩形冲突处理

发现重叠或边界冲突后，不直接失败，而是：

* `merge`
* `trim`
* `drop`

并记录差错日志。

#### Step 6：结果落盘

输出矩形结果与冲突处理日志，供后续阶段直接消费。

### C6 输出

#### `C6_BuildableRects.json`

这是 C6 的主产物，由 AI 规划、程序校验后落盘。

结构示意：

```json
{
  "step": "C6",
  "group_id": "g_port_06",
  "function": "port",
  "rectangles": [
    {
      "id": "R1",
      "cx": -5391,
      "cz": -6734,
      "w": 60,
      "h": 24
    },
    {
      "id": "R2",
      "cx": -5373,
      "cz": -6730,
      "w": 36,
      "h": 18
    }
  ],
  "conflict_log": [
    {
      "type": "trim",
      "target": "R2",
      "reason": "overlap_with_R1"
    }
  ]
}
```

建议附带的校验字段：

* `coverage_ratio`
* `terrain_fit_score`
* `requires_platform`
* `near_water`


差错日志字段规范：

* `type`：差错类型，当前至少包括 `overlap_detected`。
* `rect_a / rect_b`：参与冲突的两个矩形 ID。
* `overlap_area`：重叠面积（方块数）。
* `overlap_ratio_a / overlap_ratio_b`：重叠面积分别占各自矩形面积的比例。
* `resolution`：程序或 AI 采用的解决动作，建议限定为 `merge / trim / drop / keep_both`。
* `winner / loser`：当存在主次取舍时，记录保留方与被裁剪/丢弃方。
* `notes`：补充说明，记录为什么采用该处理方式。

建议：后续 C7 / C8 如果也有布局修正、候选淘汰、裁剪冲突，继续沿用这套日志字段，避免每阶段单独发明一套格式。

差错日志示例：

```json
{
  "overlap_logs": [
    {
      "type": "overlap_detected",
      "rect_a": "R1",
      "rect_b": "R2",
      "overlap_area": 168,
      "overlap_ratio_a": 0.12,
      "overlap_ratio_b": 0.31,
      "resolution": "trim",
      "winner": "R1",
      "loser": "R2",
      "notes": "R2 truncated on east side to preserve main harbor core."
    }
  ]
}
```

#### `C6_BuildableRects.validated.json`

程序复核后的稳定版本，供 C7 / C8 / C9 使用。

程序负责校验：

* 是否越出当前 group
* 是否低于 `80%` 覆盖率
* 是否与其他矩形发生不可接受重叠
* 是否落在明显不可建地形

#### 可选调试产物

* `C6_rect_preview.png`
* `C6_rect_preview.legend.json`

用途：

* 在底图上回看矩形布局是否合理
* 为后续人工 review 和回滚提供依据

### C6 明确不做的事

* 不生成道路
* 不直接选择最终模板
* 不做基台和垂直结构
* 不做最终装饰
* 不做逐建筑逐模块坐标展开

## C7 组件规划与拼图规则选择

### 阶段定位

C7 的目标是在每个 `C6 buildable rectangle` 内，先把“可建空间”转成“组件计划 + starter/pool/landing_profile 规则”。

* AI 负责组件语义拆分与优先级判断。
* 程序负责预筛结构库、裁剪非法候选、固化 fallback 链。
* C7 不输出最终 piece 世界坐标，不直接写入世界。
* C7 的产物是“怎么长”的规则，而不是“已经长完”的结果。

### C7 核心原则

1. 先语义，后几何。
C7 先决定矩形里需要哪些组件，再决定每个组件用什么 starter 和 pool。
2. starter 是组件锚点。
每个组件必须先确定 `starter_structure` 或 `starter_candidates`，后续扩展都围绕它展开。
3. `landing_profile` 在 C7 只是一种意图。
它表示该组件更偏向 `none / adapt / platform / flatten`，真正的基台几何与高度解算留到 C8。
4. C7 允许 AI 参与，C8 尽量确定性。
AI 在这里做风格与组织判断，程序在下一阶段做可复放的几何求解。
5. 不在本阶段生成最终 piece 坐标。
否则 C7 会同时承担“选规则”和“执行落位”，阶段边界会混乱。

### C7 输入

#### 1) C6 输出

* `C6_BuildableRects.validated.json`

每个矩形至少包含：

* `rect_id`
* `group_id`
* `function`
* `cx / cz / w / h`
* `coverage_ratio`
* `terrain_fit_score`
* `requires_platform`
* `near_water`

#### 2) 写死的组件排列类型表

C7 直接内置一张固定的排列类型表，不再单独拆出 C6.5 阶段。

第一版只支持：

* `linear`
* `cluster`
* `axial`
* `edge_wrap`

每种排列类型至少固定以下字段：

* `layout_type`
* `allowed_component_roles`
* `required_component_roles`
* `preferred_order`
* `starter_strategy`
* `expansion_strategy`
* `boundary_preference`
* `terrain_preference`

同时在 C7 内部维护 `function -> preferred_layout + fallback_layouts` 的写死映射，例如：

* `port -> linear`
* `market -> cluster`
* `fortification -> edge_wrap`
* `civic_center -> axial`

作用：

* 为当前 `rect` 指定主 `layout_type`
* 给出该 `layout_type` 的默认组件顺序与必需组件角色
* 给出 `fallback_layouts`，避免 C7 临时发明排列方式

#### 3) C3.5 结构预处理产物

* `C3_5_StructureCatalog.preprocessed.json`
* `C3_5_FunctionEnumTable.json`

每个结构至少要带：

* `size.length / width / height`
* `orientation.jigsaw_facing`
* `orientation.entry_facing`
* `piece_role`：`START / MIDDLE / END / SINGLE`
* `style_score`
* `function_candidates`
* 可选 `connector_types / connector_dirs / allowed_neighbors / landing_hint`
* 若结构可参与竖直延申，建议额外带：`growth_axis`、`vertical_role`、`vertical_clearance`

#### 4) C4 / C5 语义上下文

* `function`
* `layer`
* `zone_type`
* `district_count`
* `role_tag`

作用：

* 决定组件顺序
* 决定哪些组件是必需项
* 决定哪些组件应靠边、靠路、靠水

#### 5) 三张局部地形预览图

C7 虽然不做逐 piece 地形求解，但仍然建议给 AI 直接看局部图，而不是只看统计摘要。

建议沿用 C6 的三张局部预览图：

* `height map`
* `hillshade`
* `roughness`

作用：

* 帮 AI 判断矩形内部哪里更适合放 `core`，哪里更适合放 `boundary / amenity`
* 帮 AI 判断组件更适合做线性展开、团块展开还是边缘包裹
* 帮 AI 判断 `landing_profile` 更偏向 `none / adapt / platform / flatten`

程序侧仍可附带轻量摘要字段作为辅助，但不再把摘要当成 C7 的主输入。

#### 6) 组件规则表

建议使用：

* `C7_ComponentRules.json`

管理以下约束：

* 各功能默认组件顺序
* 各组件最小 / 最大占比
* starter 候选优先级
* `starter_pool / horizontal_pool / vertical_up_pool / vertical_down_pool / cap_pool / base_pool / transition_pool` 的白名单 / 黑名单
* 各组件是否允许启用竖直延申
* `vertical_mode`：`none / up_only / down_only / both`
* `max_upward_extension / max_downward_extension`
* `upward_cap_mode`：达到上限后是否直接封顶且不回退整条分支
* `downward_target`：`until_non_air / until_solid / until_terrain`
* `replace_bottom_block`：是否允许替换底部 `lava / water / fragile`
* `embed_into_terrain`：是否允许末端嵌入地形
* 组件删减优先级
* fallback 顺序

### C7 提示词（Prompt）

#### 1) 角色

```text
You are a Minecraft component planner.

Your task is to decompose each buildable rectangle
into semantic components and choose starter candidates,
puzzle pool, landing profile, and fallback order.

Do not place individual pieces.
Do not generate final world coordinates.
```

#### 2) AI 必须完成的判断

1. 读取 C6 已给出的 `function / group_id / rect` 约束
2. 确定组件顺序与目标占比
3. 为每个组件选 `starter_candidates`
4. 为每个组件选 `puzzle_pool`
5. 为每个组件选 `landing_profile`
6. 给出组件删减与 starter 替换优先级

#### 3) 输出要求

```json
{
  "rect_id": "R1",
  "components": [
    {
      "component_id": "CP1",
      "role": "core",
      "order": 1,
      "target_ratio": 0.42,
      "starter_candidates": ["palace_main_hall"],
      "puzzle_pool": "palace_pool_v1",
      "landing_profile": "platform",
      "fallback_policy": ["swap_pool", "swap_starter", "drop_component"]
    }
  ]
}
```

### C7 AI 内部思考步骤

#### Step 1：读取已确定的矩形约束

C7 不再重新判断这块矩形属于什么功能。功能语义已经由 C4 / C5 / C6 给定，当前步骤只读取并接受：

* `function`
* `group_id`
* `rect (cx, cz, w, h)`
* `near_water / requires_platform` 等先验约束

#### Step 2：拆分组件

例如：

* `core -> secondary -> amenity -> landmark -> boundary`

这一步不是只决定“有哪些组件”，还要同时决定“每个组件预留多少生成空间”。

也就是说，C7 在拆分组件时要先做一轮 **组件空间预算**：

* `starter_min_footprint`：该组件至少要容纳 1 个 starter 的最小长宽
* `expected_piece_count`：预期会扩展多少个 piece
* `expected_fill_ratio`：目标占比
* `connector_buffer`：为 jigsaw 接口和转向预留的缓冲空间
* `reserved_bbox_hint`：该组件在矩形中大致应占据的子区域

组件空间预算的目的不是生成最终坐标，而是避免出现：

* `core` 的 starter 能放下，但后续扩展完全没有空间
* 前一个组件把后一个组件的 starter 区域挤没
* 组件理论上达到 `target_ratio`，但实际装不下对应尺寸的 piece

一个实用口径是：

* 先按 `layout_type` 切出组件顺序和大致子区域
* 再按 `starter footprint + 预期扩展长度/块数 + connector_buffer` 估算每个组件最小预算
* 若预算总和超过 `rect` 可用面积，优先删减低优先级组件，而不是硬塞

#### Step 3：选 starter

基于 `piece_role = START`、尺寸、朝向、风格分数、功能候选给出候选序。

#### Step 4：选 pool

决定组件更适合线性扩展、团块扩展还是边界包裹扩展。

#### Step 5：选 landing_profile

这里只输出意图，不生成基台几何。

#### Step 6：输出回退优先级

明确哪些组件可以先删、哪些 starter 可以先换。

### C7 程序处理

程序在本阶段只做规则固化，不做最终落位。

#### Step 1：预筛 starter 与 pool

至少校验：

* 功能匹配
* `piece_role`
* 尺寸是否可能装入当前矩形
* 朝向是否可旋转
* 风格分数是否落在允许范围

#### Step 2：归一化组件计划

程序把 AI 输出收敛成稳定结构：

* 补齐 `order`
* 归一化 `target_ratio`
* 去掉非法 starter / pool
* 固化 fallback 链

#### Step 3：组件空间预算复核

程序要对每个组件做一次静态预算复核，至少检查：

* `starter_candidates` 的最小 footprint 是否落在该组件预算内
* 若按 `expected_piece_count` 扩展，是否仍大概率装得下
* 是否为 connector 留出了最小缓冲带
* 多个组件的预算区是否发生明显重叠

建议程序额外补出以下中间字段：

* `starter_min_footprint`
* `reserved_area_blocks`
* `expected_piece_count`
* `connector_buffer`
* `space_budget_ok`

如果预算不成立，优先采用以下修正顺序：

* 缩减低优先级组件的 `target_ratio`
* 降低该组件 `expected_piece_count`
* 替换更小的 starter
* 删除低优先级组件

#### Step 4：输出给 C8 的执行计划

此时只得到“执行蓝图”，还没有最终 piece 坐标。

### C7 输出

#### `C7_ComponentPlan.json`

这是 C7 的主产物，供 C8 执行。

```json
{
  "step": "C7",
  "rect_id": "R1",
  "group_id": "g_port_06",
  "function": "port",
  "components": [
    {
      "component_id": "CP_core_01",
      "role": "core",
      "order": 1,
      "target_ratio": 0.38,
      "starter_candidates": ["port_harbor_master"],
      "puzzle_pool": "port_core_pool_v1",
      "landing_profile": "platform",
      "fallback_policy": ["swap_pool", "swap_starter"]
    },
    {
      "component_id": "CP_secondary_01",
      "role": "secondary",
      "order": 2,
      "target_ratio": 0.34,
      "starter_candidates": ["port_warehouse_head"],
      "puzzle_pool": "port_storage_pool_v1",
      "landing_profile": "adapt",
      "fallback_policy": ["swap_pool", "drop_component"]
    }
  ]
}
```

#### `C7_ComponentCandidates.json`（可选）

用于调试程序预筛结果，记录每个组件被保留的 starter / pool 候选。

### C7 差错与回退

常见差错：

* `starter_candidate_empty`
* `pool_candidate_empty`
* `ratio_overflow`
* `function_mismatch`
* `style_conflict`

回退顺序建议固定为：

```text
swap_pool
-> swap_starter
-> drop_component
-> abandon_rect
```

* 可回滚点：可在本阶段重选组件计划；必要时回退到 C6 重选矩形。
* 存放位置：`/saves/<WorldName>/terra_script/cities/<city_id>/`。

# C8 · 结构求解落位与基台计划（顺承 T4 + C7）

## 阶段定位

C8 是第一个真正开始“落地结构方案”的阶段。

这里的“落地”不是立刻写世界方块，而是把 `C7_ComponentPlan` 求解成稳定、可复放的 piece 布局结果，并同步生成基台与垂直过渡计划。

* C7 决定：组件顺序、starter、pool、landing_profile、fallback。
* C8 决定：starter 具体放哪、后续 piece 怎么长、哪里能放、哪里必须回退、基台怎么接地。
* C8 输出的是“已求解完成但尚未执行写入”的结果，供 C9 直接执行。

## C8 核心原则

1. C8 是确定性执行层。
connector 匹配、占位更新、碰撞检测、地形校验、基台决策都由程序完成。
2. 从 C8 开始才产生真实 piece 坐标。
C7 只有规则与预算，C8 才真正产生 `world_pos / rotation / jigsaw_facing`。
3. 组件按顺序求解，不允许后组件破坏前组件的已确认占位。
4. 先通过地形校验，再确认 piece 落位。
5. 先稳定 piece 布局，再汇总生成 foundation plan。

## C8 输入

### 1) 来自 C6 / C7

* `C6_BuildableRects.validated.json`
* `C7_ComponentPlan.json`

C8 至少读取：

* `rect_id / rect`
* `group_id`
* `function`
* `layout_type`
* `components[]`
* `order`
* `target_ratio`
* `starter_candidates`
* `starter_pool / horizontal_pool`
* 可选 `vertical_up_pool / vertical_down_pool / cap_pool / base_pool / transition_pool`
* `landing_profile`
* `vertical_mode / max_upward_extension / max_downward_extension`
* `upward_cap_mode / downward_target / replace_bottom_block / embed_into_terrain`
* `fallback_policy`
* `starter_min_footprint / expected_piece_count / connector_buffer` 等空间预算字段

### 2) 来自 T4 的地形事实

对每个 `rect` 和候选落点，直接引用 T4 已计算结果：

* `height_min / max / avg / p50 / p95`
* `relief`
* `slope_avg / slope_p95`
* `roughness_avg / roughness_p95`
* `edge_heights`
* `water_ratio`
* `hazards`
  * `touch_water`
  * `touch_cliff`
  * `protected_overlap`

关键点：C8 只消费 T4 与规则库，不重新扫描世界。

### 3) C8 规则输入

* `C8_JigsawRules.json`
* `C8_FoundationRules.json`

建议至少管理：

* connector 匹配优先级
* 单组件最大 piece 数
* 连续失败阈值
* 最大允许抬高 / 挖低
* 是否允许 `cut_and_fill / terrace / retaining_wall / suspend`
* 不同 `landing_profile` 的落地阈值
* 竖直 connector 的匹配优先级与最小净空
* 竖直延申的停止条件、封顶规则、触底替换规则

## C8 处理流程

### Step 1：初始化矩形求解状态

程序为当前 `rect` 建立：

* `occupied_mask`
* `reserved_component_areas`
* `component_progress`
* `frontier_connectors`
* `terrain_cache`
* `foundation_hints`

### Step 2：按组件顺序求解 starter

对每个 `component`，按照 `order` 依次执行：

* 按 `starter_candidates` 顺序尝试
* 结合 `layout_type` 选择该组件的优先锚点区域
* 为 starter 选择具体 `world_pos + rotation`
* 检查是否越出 `rect`
* 检查是否撞上已落位 piece
* 检查是否满足 `near_water / edge / entry` 等约束
* 检查 starter 是否落在本组件的预算区内

### Step 3：组件级 jigsaw 扩展

starter 成功后，程序维护该组件的 `frontier_connectors`，再从对应 `puzzle_pool` 中选择 piece 继续扩展。

默认口径：

* 水平扩展优先使用 `horizontal_pool`
* 命中 `up/down` 连接器时，不再混用普通池，而是切换到对应的 `vertical_up_pool / vertical_down_pool`
* 需要转向时先尝试 `transition_pool`
* 达到上限或命中终止条件后，使用 `cap_pool / base_pool` 收口

固定执行口径：

```text
place starter
open frontier connectors
pick piece from puzzle_pool
check connector match
check terrain and bounds
accept or reject
repeat until stop condition
```

每次尝试 piece 时至少检查：

* connector 类型是否匹配
* 朝向能否接上
* `piece_role` 是否允许出现在当前时机
* 放下后是否仍在 `rect` 内
* 是否侵占其他组件预算区太多
* 是否与已落位 piece 冲突

### Step 3.5：竖直分支求解

当某个 frontier connector 被判定为竖直连接器时，按单独规则求解，不再走普通水平扩展口径：

* 向上分支：逐层从 `vertical_up_pool` 取候选 piece
* 向下分支：逐层从 `vertical_down_pool` 取候选 piece
* 每成功放置一层都更新当前顶点 / 底点高度与占位
* 达到 `max_upward_extension / max_downward_extension` 后立即停止该分支
* 若配置了 `upward_cap_mode`，达到高度上限后直接放置 `cap_pool`，不回退已成立的上升分支
* 向下分支根据 `downward_target` 判断是否继续生长到非 air / solid / terrain
* 命中目标面后，按 `replace_bottom_block / embed_into_terrain` 决定末端是否替换底块、贴底收口或嵌入地形

建议将竖直分支视为“组件内子分支”处理：单个分支失败优先只回退当前分支，不回退整个组件。

### Step 4：piece 落地前地形校验

每个 piece 在确认前都必须检查：

* `height`
* `slope`
* `roughness`
* `water_ratio`
* 是否触发 `touch_cliff / protected_overlap`

若任一条件不满足，则该次尝试直接判失败，不进入最终 placement。

### Step 5：按 landing_profile 生成落地策略

piece 通过地形校验后，再根据 C7 给定的 `landing_profile` 决定实际落地方式：

* `none`：直接贴地或只做极小修正
* `adapt`：允许局部补齐或小范围抬降
* `platform`：生成规则平台、挡墙、台阶
* `flatten`：对局部区域做切平方案，再放置 piece

若 piece 属于竖直延申链，还应追加以下判定：

* 是否超过该链允许的高度上限 / 深度上限
* 末端是否需要封顶、落底或转入 `transition_pool`
* 末端接触物是否允许被替换（如 `lava / water`）
* 是否允许把末端嵌入山体、地面或 cliff 面

### Step 6：组件停止条件

满足任一条件就停止当前组件：

* 达到 `target_ratio`
* 达到 `expected_piece_count` 或 `max_piece_count`
* 无可用 connector
* 连续失败次数超过阈值
* 剩余预算空间已不足以再放下最小 piece

### Step 7：组件 / 矩形回退

建议固定回退链：

```text
piece 失败
-> 换 pool 元素

starter 失败
-> 换 starter

component 失败
-> drop component / 标记 underfilled

rect 失败
-> abandon rect
```

其中：

* `drop component` 只允许删除低优先级组件
* `abandon rect` 只在核心组件无法成立时触发
* 竖直链到达高度上限时优先“封顶收口”，而不是回退整个已成立分支

### Step 8：汇总生成基台与垂直过渡计划

当整块 `rect` 的 piece 布局稳定后，再统一汇总：

* 平台体块
* 护坡 / 挡墙
* 台阶
* terracing
* 切填区域
* `terrain_impact_extent`

## C8 输出

### `C8_ComponentPlacement.json`

这是 C8 的主产物，记录最终求解完成的 piece 布局。

```jsonc
{
  "step": "C8",
  "rect_id": "R1",
  "group_id": "g_port_06",
  "function": "port",
  "placements": [
    {
      "piece_id": "P_001",
      "component_id": "CP_core_01",
      "structure_id": "port_harbor_master",
      "piece_role": "START",
      "world_pos": { "x": -5398, "y": 71, "z": -6738 },
      "rotation": 90,
      "jigsaw_facing": "south",
      "landing_profile": "platform",
      "pool_source": "horizontal_pool",
      "vertical_branch": null
    }
  ],
  "component_stats": [
    {
      "component_id": "CP_core_01",
      "filled_ratio": 0.41,
      "piece_count": 4,
      "starter_used": "port_harbor_master",
      "space_budget_ok": true
    }
  ]
}
```

### `C8_FoundationPlan.json`

```jsonc
{
  "rect_id": "R1",
  "foundation_type": "PLATFORM",
  "strategy": "CUT_AND_FILL",
  "base_y": 74,
  "delta_height": 3,
  "supports": [
    { "type": "retaining_wall", "side": "N" },
    { "type": "stairs", "side": "E" }
  ],
  "terrain_impact_extent": {
    "minX": -1182, "minZ": 540,
    "maxX": -1156, "maxZ": 566
  }
}
```

### `C8_ComponentPlacement.validated.json`

程序复核后的稳定版本，供 C9 执行。

建议校验：

* 是否越出 `rect`
* 是否存在不可接受重叠
* 是否存在未通过地形校验却被保留的 piece
* 是否存在核心组件缺失
* 是否存在 foundation 与 piece 布局不一致

### 可选调试产物

* `C8_component_preview.png`
* `C8_component_preview.legend.json`
* `C8_connector_debug.json`
* `C8_foundation_preview.png`

## C8 差错与回退

常见差错建议固定为：

* `piece_connector_mismatch`
* `piece_terrain_rejected`
* `piece_overlap`
* `piece_out_of_budget`
* `starter_unplaceable`
* `component_underfilled`
* `core_component_missing`
* `rect_abandoned`

## C8 明确不做的事

* 不直接写世界方块
* 不做最终装饰随机
* 不在本阶段重新改 C7 的组件语义

# C9 · 世界写入与装饰执行（顺承 C8）

## 阶段定位

C9 只负责把 C8 已经求解并验证过的结果写进世界，并记录执行结果。

* 地形与基台问题 -> 已由 T4 + C8 解决
* 几何位置 -> 已由 C6 + C8 决定
* 组件与拼图规则 -> 已由 C7 决定

C9 不再负责重新选 starter、重新拼 jigsaw、重新判断主功能。

## C9 输入

* `C8_ComponentPlacement.validated.json`
* `C8_FoundationPlan.json`
* 可选 `C9_DecorationRules.json`

执行前至少读取：

* 各 piece 的 `structure_id / world_pos / rotation`
* foundation 的 `base_y / supports / terrain_impact_extent`
* 装饰权重、随机种子、禁放列表

## C9 处理流程

### Step 1：写入 foundation

先按 `C8_FoundationPlan.json` 写入：

* 平台
* 挡墙
* 台阶
* terracing
* 其他基础支撑结构

### Step 2：写入结构 piece

再按 `C8_ComponentPlacement.validated.json` 依次写入：

* starter
* middle
* end / single

写入时记录每个 piece 的实际执行状态：

* `placed`
* `skipped`
* `failed`

### Step 3：写入装饰与小结构

主结构稳定后，再执行：

* 权重随机装饰
* 小型附属物
* 功能性细节补件

装饰不得破坏 C8 已确认的主结构和 connector 逻辑。

### Step 4：执行复核与记录

程序应输出：

* 成功写入多少个 foundation 单元
* 成功写入多少个 piece
* 失败的 piece 列表
* 是否需要局部回滚

## C9 输出

### `C9_Placement.dat/json`

最终世界写入记录。

### `C9_DecorationPlan.json`

最终执行过的装饰清单与随机结果。

### `C9_ExecutionReport.json`

建议至少包含：

* `rect_id`
* `piece_total / piece_success / piece_failed`
* `foundation_success`
* `decoration_success`
* `rollback_applied`
* `notes`

## C9 差错与回退

常见差错建议固定为：

* `foundation_write_failed`
* `piece_write_failed`
* `chunk_not_ready`
* `placement_blocked`
* `decoration_conflict`
* `partial_rollback_applied`

回滚原则：

* 优先单 piece 回滚
* 其次单组件回滚
* 再次单矩形回滚
* 不要因为局部失败推翻整城

## C9 明确不做的事

* 不回头重算 C8 几何
* 不重做 C7 组件规划
* 不修改 C6 矩形

* * *

## 最终一句话定性

> **T4 是地形物理真相，  
> C6 是空间设计，  
> C7 是组件与规则决策，  
> C8 是几何求解与接地，  
> C9 是最终写入。**

# 总结：三大模块的“谁负责什么”


- **世界构造**：AI负责“讲得通 + 约束”，程序负责“算得准 + 存得下”
- **国度构造**：AI负责“政治与叙事”，程序负责“扩张落地 + 边界数据”
- **城市生成**：AI负责“语义/风格/取舍”，程序负责“几何/遮罩/落地执行”





**存放位置**：`/saves/<WorldName>/terra_script/territories/<territory_id>/`
**存放位置**：`/saves/<WorldName>/terra_script/territories/<territory_id>/`
**存放位置**：`/saves/<WorldName>/terra_script/cities/<city_id>/`




