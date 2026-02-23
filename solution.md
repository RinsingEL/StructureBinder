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

## 当前实现状态（已完成：W3 / W4 / T1 / T2 / T3 / T4 / C4 / C5 / C6）

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
- 城市阶段 `C4 / C5 / C6` 已实现并提供接口：
  - 入口：`src/main/java/com/user/terra_script/server/mcp/CityController.java`
  - 接口：`/city_c4_generate`、`/city_c5_generate`、`/city_c6_generate`

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
  5. 人工触发 Q2（`/query_region_pick`）选择最优簇和取点模式（center / north / south / east / west / random_cardinal）。
  6. 提交 `t1_generate_blueprint`，把最终 `target_continent_id` 与扩张参数固化。

*   **程序 做什么**：
  * 提供 `W4_get_world_atlas` 和 `scan_local_candidates`。
  * 为 T1 候选区域输出 PNG 预览图（遵循 W4 同款图例规则）。
  * 返回候选簇列表（每个簇都带 `cluster_id`、关键点、ASCII 图）和 `preview_overlay`。
  * 写入 Q1 待选缓存，等待人工触发 Q2。
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
**目标**：在 C2 占地基础上生成稳定可解释的区划单元，供后续 C4 功能语义标注。  
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


## C4 功能语义分类（AI）

- **目标**：将 C3 的每个区划（district）映射为“功能语义”，作为 C5 聚合输入。
- **AI做什么**：
  - 为每个 `district_id` 选择 1~N 个功能标签（主标签 + 可选副标签）；
  - 填写功能优先级、禁邻规则、偏好邻接；
  - 不直接改几何边界（几何仍由程序维护）。
- **程序做什么**：
  - 提供区划摘要、ASCII 预览、邻接关系；
  - 校验标签合法性（白名单）、冲突关系（如 `cemetery` 不贴 `market`）。
- **输入**：
  - `C3_Districts.json`
  - `C3_DistrictIndex.dat`
  - 可选 `C4_TagPolicy.json`（功能字典与硬约束）
- **AI可调用数据（接口）**：
  - `getDistrictSummary(cityId, districtId)`：区划统计摘要（面积、坡度、高差、layer、邻居）
  - `previewDistrictASCII(cityId, districtId, scale)`：单区划 ASCII
  - `previewDistrictAdjacency(cityId)`：全城区划邻接图（建议新增）
- **输出**：
  - `C4_FunctionPlan.json`（AI主产物）
  - `C4_FunctionPlan.validated.json`（程序校验后）
```jsonc
{
  "step": "C4",
  "ok": true,
  "city_id": "city_foo",
  "version": 1,
  "district_functions": [
    {
      "district_id": "d_01_core",
      "layer": "core",
      "primary_function": "civic_center",
      "secondary_functions": ["market"],
      "priority": 0.92,
      "constraints": {
        "avoid_adjacent": ["heavy_industry", "cemetery"],
        "prefer_adjacent": ["market", "residential_mid"]
      },
      "notes": "核心行政+贸易复合区"
    }
  ],
  "global_policies": {
    "min_function_diversity": 5,
    "max_same_function_ratio": 0.35
  }
}
```
- **可回滚点**：本阶段可反复直到满意
- **存放位置**：`/saves/<WorldName>/terra_script/cities/<city_id>/`


## C5 模块聚合（程序+AI策略）

- **目标**：将 C4 中碎片化的同功能区划聚合成“可执行模块组”，便于后续 C6 可建造区计算。
- **程序做什么**：
  - 基于邻接图 + 几何连通性进行自动合并；
  - 输出聚合后模块组边界、中心、连通块统计；
  - 若跨 layer 冲突，默认不合并（除非策略允许）。
- **AI做什么**：
  - 给聚合阈值与倾向：`min_group_area`、`max_split_count`、`cross_layer_merge`；
  - 为关键功能指定目标规模区间（如市场、港区、墓地）。
- **输入**：
  - `C4_FunctionPlan.validated.json`
  - `C3_Districts.json`
  - 可选 `C5_GroupPolicy.json`
- **输出**：
  - `C5_ModuleGroups.json`
  - `C5_ModuleIndex.dat`（block/chunk -> module_group_id）
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
  }
}
```
- **可回滚点**：可回到 C4
- **存放位置**：`/saves/<WorldName>/terra_script/cities/<city_id>/`


## C6 可建造区与空间布局设计

### 阶段定位

C6 是空间设计阶段，目标不是直接放置建筑，而是把 C5 的功能模块转成可落地、可复现的空间占位方案。

* 在硬规则完全由程序控制的前提下，允许 AI 参与空间设计决策。
* 为 C7（模板裁剪）、C8（基台）、C9（放置）提供稳定、可解释的中间结构。

### C6 核心原则

1. 主体建筑优先。
少量 Primary Modules 决定整体空间结构，AI 主要在这一层做几何级决策。
2. 语法而非坐标。
次级建筑不由 AI 指定逐点坐标，而是由 AI 选择 Fill Style 与参数，程序展开。
3. 硬规则先行。
可建造判定完全由程序完成，AI 不直接决定“哪里能建”。
4. 不生成道路。
C6 只为未来道路留空间与意图，道路在房屋完成后再生成。

### C6 输入

#### 必需输入（程序）

* `C5_ModuleGroups.json`
  功能模块（function / layer / centroid / 连通性）。
* `C5_ModuleIndex.dat`
  block 或 chunk 到 module_group_id 的映射。
* `C2_Claim.dat`
  block 或 chunk 到 layer 的映射。
* 地貌 scan 能力，扫描T4中我们算好的国度方块级地形数据
  包括高度、坡度、水体、保护点、可达性。

#### 可选输入（建议）

* `C6_BuildRules.json`
  集中管理硬规则阈值、生态清理强度与 buffer，保证规则可版本化与可复现。

### C6 程序处理（硬规则）

在每个 Module Group 内按固定流程执行：

1. 按生态策略清理地物（0 / 30 / 80 / 100%）。
2. 排除禁区。
水体、悬崖或超阈值坡度、保护点及其 buffer。
3. 计算连续可建造区域（连通块）。
4. 为每个可建造区生成指标。
面积、bbox、坡度统计、可用率、ASCII 预览。

### C6 输出（程序）

#### `C6_BuildAreaIndex.dat`

* 方块级索引：`block -> build_area_id | 0`。
* 用于后续放置合法性判定和容错回滚。

#### `C6_BuildAreaSummary.json`

* 每个建造区候选的摘要信息。
* 不包含完整方块列表。
* 用于 AI 对比与 C7 初筛。

### C6 输出（AI，可选）

#### `C6_BuildAreaLayout.json`（最多回滚 2 次）

#### A. 主体模块（Primary Modules）

* 每个 Module Group 通常 1 到 3 个。
* 每项包含 `anchor`、`importance`、`template_hint`。
* 作用是定义空间主语（广场、地标、核心建筑）。

#### B. 次级填充（Secondary Fill）

* AI 不给具体坐标。
* AI 选择 Fill Style、参数范围、可用地块尺寸族（rect sizes，可多选加权）。
* 程序按固定排列器执行。

Fill Style 示例：

* `PLAZA_RING` （目前只开发这个类别的）
* `STREET_SPINE`
* `EDGE_FOLLOW`
* `CLUSTER_POISSON`
* `GRID_RELAXED`
* `TERRACE_BANDS`
* `DECOR_BUFFER`

#### C. 可选：矩形积木（Bricks）

* AI 可选择介入更细粒度切分。
* 不介入时由程序使用默认切分策略。

### C6 明确不做的事

* 不生成道路。
* 不选择具体模板。
* 不做基台和垂直结构。
* 不做最终装饰。

## C7 模板池裁剪与拼图策略

### 阶段定位

C7 的任务是在 C6 给定的空间结构（primary / plots）上，从模板池中筛选“可用候选模板集合”。

* 决定是否启用拼图。
* 决定拼图深度（0 / 1 / 2）。
* 决定使用哪个拼图池。
* 为 C8 / C9 提供候选与 fallback 链。

### C7 输入

#### 1) C6 输出

* `C6_BuildAreaLayout.json`
  包含 primary_modules 与 fills（Fill Style + rect sizes）。

#### 2) 模板库（Template Catalog）

* 每个模板已由 AI 批量标注。

#### 3) 可选规则

* `C7_TemplateRules.json`
  管理功能区偏好、拼图上限与 fallback 策略。

### 模板标签体系（最终定型）

#### ① 核心硬标签（最重要）

决定“能不能用”：

* `function_role`（第一筛选维度）
* `interaction_role`
  例如 `FRONT_TO_PLAZA` / `FRONT_TO_STREET` / `INWARD_FACING` / `EDGE_ATTACH`
* `footprint`（w/h，可旋转）
* `height`
* `terrain_profile`
  例如 `flat_only` / `slope_ok` / `water_edge_ok`

#### ② 拼图相关（仅在启用拼图时使用）

* `puzzle_pool_id`
* `connector_types`
* `connector_dirs`

这部分不参与普通筛选，避免维度爆炸。

#### ③ 软偏好（排序用）

* `reskin_supported`
* `material_profile`
* `style_tags`
* `landmark_score`

### C7 筛选顺序（建议写死）

1. `function_role`
2. `interaction_role`
3. `footprint / height`
4. `terrain_profile`
5. 拼图可用性（仅当需要）
6. `reskin_supported / material_profile`
7. `style_tags`

### C7 输出

#### `C7_TemplateSelection.json`

对每个 `primary_module` 或 `plot`，输出：

* Top-K 模板候选。
* 拼图参数（若启用）。
* 明确 fallback 链。

* 可回滚点：可在本阶段重选。
* 存放位置：`/saves/<WorldName>/terra_script/cities/<city_id>/`。


# C8 · 基台与垂直过渡（T4 驱动版）

## 阶段定位（修订）

**C8 是“基于 T4 地形分析结果的基台决策与生成阶段”**：

* 不重新计算高度、坡度

* 不扫描方块

* 不推导地形结构

* 只做：

    * 策略选择

    * 几何生成

    * 影响范围记录


* * *

## C8 的几何基准（再次确认）

* **唯一几何基准：`plot`（C6 生成的长方形）**

* 功能区 / 多边形：

    * 只作为**规则选择与风格偏好**

    * 不参与基台几何计算


这点在 T4 已完备的前提下更重要，否则会“重复建模”。

* * *

## C8 输入（最终定型）

### 1️⃣ 来自 C6 / C7

* `plots[]`

    * `plot_id`

    * `rect (x,z,w,h)`

    * `rotation`

    * `module_group_id`

* `selected_template`

    * `footprint`

    * `base_height`

    * `foundation_hint`（可选）


* * *

### 2️⃣ 来自 **T4_HeightAnalysis**（核心输入）

> 这是 C8 的“物理事实源”，也是你系统的优势点。

对 **每个 plot**，直接引用 T4 的结果即可：

* `height_min / max / avg / p50 / p95`

* `relief`（max - min）

* `slope_avg / slope_p95`

* `edge_heights`（N / E / S / W）

* `hazards`

    * `touch_water`

    * `touch_cliff`

    * `protected_overlap`


> ⚠️ 关键点：  
> **C8 不再允许直接访问方块世界**，只消费 T4 产物  
> → 确定性、可回滚、调试友好

* * *

### 3️⃣ C8 规则输入（轻量）

`C8_FoundationRules.json`（或内置表）

* 最大允许抬高 / 挖低

* 是否允许 cut & fill

* 是否允许悬挑

* 台阶 / 护坡触发阈值

* 各 `function_role` 的偏好策略


* * *

## C8 处理流程（精简版）

对 **每个 plot**：

### Step 1：选择基台策略（不算数，只决策）

基于 T4 指标：

* `relief < ε`  
  → `NONE`：直接贴地

* `relief` 中等 & `slope_avg` 可接受  
  → `PLATFORM`（抬高或切平）

* `relief` 大 & 等高线近似平行  
  → `TERRACE`（梯田/分级台阶）

* `edge_heights` 单侧突变  
  → `PLATFORM + RETAINING_WALL`


> 这一步可以是**程序规则**，也可以留一个 `strategy_hint` 给 AI（但 AI 不接触原始高度）。

* * *

### Step 2：确定基准高度

使用 T4 已算好的统计量：

* `FOLLOW_AVG`

* `FOLLOW_P50`

* `FOLLOW_MIN`

* `FOLLOW_MAX`

* `CUT_AND_FILL`（受规则约束）


* * *

### Step 3：生成基台几何

* 基台体块

* 护坡 / 挡墙

* 台阶

* 边界裁切


* * *

## C8 输出（最终）

### `C8_FoundationPlan.json`

（不变，但现在**完全可复现**）

```jsonc
{
  "plot_id": "p23",
  "foundation_type": "PLATFORM",
  "strategy": "CUT_AND_FILL",
  "base_y": 74,
  "delta_height": 3,
  "supports": [
    { "type": "retaining_wall", "side": "N" },
    { "type": "stairs", "side": "E" }
  ],
  "terrain_impact_bbox": {
    "minX": -1182, "minZ": 540,
    "maxX": -1156, "maxZ": 566
  }
}
```

* * *

## 那等高线 + C6 多边形还要不要？

### ✔️ 要，但角色变了

它们不再是 **C8 的“计算输入”**，而是：

* **AI 决策辅助**（当你允许 AI 参与策略选择时）

* **Debug 可视化**

* **回放 / 复盘**


可以定义为：

* `C8_TerrainPreview`（只读、非必需）

* 不影响 determinism

* 丢了也不影响结果


* * *

# C9 · 建筑放置与装饰（顺承 T4 + C8）

在这个体系下，C9 变得非常“干净”：

* 地形问题 → 已由 T4 + C8 解决

* 几何位置 → 已由 C6 决定

* 模板选择 → 已由 C7 决定


**C9 只负责执行与记录。**
- **程序做什么**：


放置建筑（位置+朝向N/E/S/W+基准点）


若有“拼图式扩展”：用你自定义的“受边界约束的拼接逻辑”（不走原版无限扩张）


填充装饰/小结构（权重随机）
- **AI做什么**：看效果评审（截图/俯视图），不满意可回滚到“该建造区”
- **产出**：


`C9_Placement.dat/json`（最终落地记录）


`C9_DecorationPlan.json`
- **可回滚点**：单建造区回滚（不要推翻全城）
- **存放位置**：`/saves/<WorldName>/terra_script/cities/<city_id>/`
* * *

## 最终一句话定性

> **T4 是地形物理真相，  
> C6 是空间设计，  
> C8 是“如何把设计接到真实世界上”。**

# 总结：三大模块的“谁负责什么”


- **世界构造**：AI负责“讲得通 + 约束”，程序负责“算得准 + 存得下”
- **国度构造**：AI负责“政治与叙事”，程序负责“扩张落地 + 边界数据”
- **城市生成**：AI负责“语义/风格/取舍”，程序负责“几何/遮罩/落地执行”





**存放位置**：`/saves/<WorldName>/terra_script/territories/<territory_id>/`
**存放位置**：`/saves/<WorldName>/terra_script/territories/<territory_id>/`
**存放位置**：`/saves/<WorldName>/terra_script/cities/<city_id>/`
