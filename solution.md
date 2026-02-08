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

## 当前实现状态（已完成：W3 / W4 / T1）

以下内容基于当前代码实际落地情况（`domain` + `server/mcp`）：

- `W3` 已实现：`src/main/java/com/user/terra_script/domain/world/stage/W3Stage.java`
  - 产物：`world/W3/ContinentMeta.json`、`world/W3/OceanMeta.json`
  - 触发方式：工作流阶段 `W3`（或 `/dev stage W3`）
- `W4` 已实现：`src/main/java/com/user/terra_script/domain/world/stage/W4Stage.java`
  - 依赖：`W3`
  - 产物：`world/W4/TerrainFacts.dat`、`world/W4/TerrainSummary.json`
  - 触发方式：工作流阶段 `W4`（或 `/dev stage W4`）
- `T1` 已实现：`src/main/java/com/user/terra_script/server/mcp/TerritoryController.java`
  - 接口：`POST /t1_blueprint`、`GET /t1_blueprint`
  - 持久化：`src/main/java/com/user/terra_script/territory/io/TerritoryRepository.java`
  - 产物：`/saves/<WorldName>/terra_script/territories/T1_Blueprint.json`

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
    *   `scan_local_W4candidates(...)`：后端会去查 `W4_TerrainFacts.dat` 中的 slope/tpi 数组。

---

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
- **输出**：候选点列表 + ASCII 图（保持现有实现）

# 二、国度构造部分


## T1 国度和区域蓝图生成（AI）
**核心逻辑**：AI 根据 `W4_get_world_atlas` 返回的大陆硬数据，结合 W1 的世界观，决定要诞生一个什么样的国家，以及它**想去哪个大陆发展**。

*   **AI 做什么**：
  1.  调用 `W4_get_world_atlas` 获取所有大陆和`W4_world_summary`比对标准的数据。
  2.  **分析匹配**：
    *   看到 ID=12 的大陆 `avg_height: 78` (较高), `roughness: 1.34` (崎岖), `avg_temp: 0.62` (温带偏凉)。
    *   **决策**：这非常适合一个“高山矮人”或“高原游牧”文明。
  3.  **生成设定**：
    *   **基本信息**：国名、文明特色、叙事背景。
    *   **目标大陆**：指定 `target_continent_id: 12`（后续 T2 就在这个大陆上找首都）。
    *   **扩张基因**：定义 `expansion_power`（总扩张力/能量）和 `movement_costs`（地形消耗表）。

*   **程序 做什么**：
  *   提供 `W4_get_world_atlas` 接口。
  *   接收并存储 AI 生成的蓝图配置。

*   **AI 可调用数据 (MCP)**：
  *   `W4_get_world_atlas()`：返回你 W3 产出的那个详细 JSON（含地貌、气候、生态）。

*   **产出 (JSON)**：`T1_Blueprint.json`（列表，按 territory_id 去重）
*   **存放位置**：`/saves/<WorldName>/terra_script/territories/`

### 🔌 T1 MCP 接口
- **方法名**：`T1_submit_blueprint`
- **HTTP**：`POST /t1_blueprint`
- **输入参数**：T1_Blueprint.json 中的单条对象
- **输出**：
```jsonc
{
  "step": "T1",
  "ok": true,
  "message": "saved",
  "blueprint": { /* 提交的蓝图 */ }
}
```
- **可选**：`T1_list_blueprints`（`GET /t1_blueprint`）返回全部蓝图列表

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

**核心逻辑**：**多图层叠加（Multi-Layer Overlay）**。AI 不直接找“要塞”，而是让程序分别找出“高山”和“水源”，并在 ASCII 图上叠加。AI 通过观察 ASCII 字符的**空间相邻关系**来选址。

*   **步骤 2.1：下发勘探任务 (AI 动作)**
    *   **AI 思考**：“我是高地文明，最好的首都是：**背靠高山 (A)** 且 **面向平原 (B)** 的交界处（既能防守又能种地）。”
    *   **AI 调用 MCP**：`scan_local_W4candidates(...)`

    **MCP 请求示例**：
    ```jsonc
    {
      "continent_id": 2,
      "limit_per_group": 3, // 每组特征找前3大
      "interest_groups": [
        {
          "id": "A", 
          "type": "mountain", // 图例字符：山脉
          "criteria": { "min_height": 100, "min_roughness": 0.5 },
          "limit": 3
        },
        {
          "id": "B", 
          "type": "plain", // 图例字符：平原
          "criteria": { "max_slope": 0.2, "biome_preference": ["plains"] },
          "limit": 3
        }
      ]
    }
    ```

*   **步骤 2.2：执行扫描与绘图 (程序 动作)**
    *   **搜索**：程序在 W4 缓存中分别搜索满足 A 条件和 B 条件的像素。
    *   **聚类**：使用 DBSCAN 分别聚类，找出最大的几块 A 区域和 B 区域。
    *   **过滤**：剔除已被其他国度占领的区域（硬约束）。
    *   **绘图**：将 A 和 B 绘制在同一张 ASCII 底图上。

*   **步骤 2.3：返回勘探报告 (程序 返回)**
    *   **返回内容**：JSON 列表（含精确坐标） + **ASCII 可视化地图**。

    **MCP 返回示例**：
    ```jsonc
    {
      "candidates_metadata": [
        { "label": "A1", "center": "-1200, 500", "desc": "Huge Mountain Range" },
        { "label": "B1", "center": "-1150, 600", "desc": "Fertile Plains" },
        // ... A2, B2 ...
      ],
      "visual_map": [
        "~~~~~~~~~~~~~~~~~~~~",
        "~~~~~~AAAAAA~~~~~~~~", // A1: 高山
        "~~~~~~AAAAAA~~~~~~~~",
        "~~~~~~AAAABBBBBB~~~~", // 关键点：A1 和 B1 在这里紧紧相邻！
        "~~~~~~~BBBBBBBB~~~~~", // B1: 平原
        "~~~~~~~BBBBB........"
      ]
    }
    ```

*   **步骤 2.4：视觉推理与定都 (AI 决策)**
    *   **AI 思考**：“看地图，`A1` (山) 和 `B1` (平原) 紧密相邻，这是完美的关隘位置。而 `A2` 孤零零在海边，不好。”
    *   **AI 调用 MCP**：`confirm_capital(...)`。可以选择 `A1` 的边缘，或者 `B1` 靠近 `A1` 的一侧，或者直接给出一个基于 A1/B1 中心点微调的坐标。

    **AI 最终指令**：
    ```jsonc
    confirm_capital({
      "territory_id": "kingdom_stone_heart",
      "capital_x": -1180, // AI 综合判断选定的坐标（在山脚下）
      "capital_z": 550,
      “area”: "A" // 是在A区域内还是B区域内，方便后续差错
      "reason": "Located at the junction of Mountain Range A1 and Plains B1."
    })
    ```
    **程序检查**：
    * 检查在哪个区域内，假如AI给出的坐标有错误，那就找到AI给出的坐标最近的区域的区块。

*   **产出 (JSON)**：`T2_CapitalData.json`
*   **存放位置**：`/saves/<WorldName>/terra_script/territories/<territory_id>/`

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


- **AI做什么**：选城市中心点（chunk/block坐标）、城市规模、层数/城墙意图、生态策略等
- **程序做什么**：提供候选点与预览（避免AI瞎填）
- **AI可调用数据（接口）**：


`listCitySiteCandidates(territoryId, type, constraints)`


`previewSiteASCII(candidateId)`

`getSiteStats(candidateId)`
- **产出（JSON）**：`C1_Intent.json`（你已有的那套 + 扩展层数/城墙偏好）
- **存放位置**：`/saves/<WorldName>/terra_script/cities/<city_id>/`


## C2 城市领地生成（程序）



- **程序做什么**：从市中心扩张得到城市占地区域（chunk集合），并分层（至少核心+缓冲；允许多层墙）


输出每个chunk属于哪个层（layerId），而不只是 C/U/B
- **AI做什么**：无（硬算法）
- **产出**：


`C2_Claim.dat`（chunk集合 + layer标记）


`C2_ClaimSummary.json`（面积、各层比例）
- **存放位置**：`/saves/<WorldName>/terra_script/cities/<city_id>/`


## C3 多边形/区划种子与分区（程序主导，AI可选增强）


- **程序做什么**：


生成区划种子点（散点数量有上下限，按面积与层级加权）


Voronoi/Lloyd 得到初始区块模块（chunk级）
- **AI可选做什么**：


若你想：AI可以调“功能密度层级”（核心密/边缘疏），但**不必手选每个点**
- **产出**：


`C3_Districts.json`（每个模块：中心点、所属层、chunk列表、统计值）


方块级数据仍在 `.dat`，按模块索引读取


## C4 功能语义分类（AI）


- **AI做什么**：给每个模块打功能标签（可重复，邻近可合并）
- **AI可调用数据（接口）**：


`getDistrictSummary(cityId, districtId)`


`previewDistrictASCII(cityId, districtId, scale)`
- **产出**：`C4_FunctionPlan.json`
- **可回滚点**：本阶段可反复直到满意
- **存放位置**：`/saves/<WorldName>/terra_script/cities/<city_id>/`


## C5 模块聚合（程序+AI策略）


- **程序做什么**：把相邻同功能模块合并成“大模块”（解决 3~4 chunk碎片）
- **AI做什么**：给合并阈值/偏好（比如市场要更大、墓地要远离核心）
- **产出**：`C5_ModuleGroups.json`
- **可回滚点**：可回到 C4
- **存放位置**：`/saves/<WorldName>/terra_script/cities/<city_id>/`


## C6 可建造区计算（硬规则阶段，程序）


- **程序做什么**：


按生态策略清除地物（0/30/80/100%）


计算地面平整与禁区：水体/悬崖/保护点


在每个大模块内找连续可用区域（连通块）


输出“可建造区候选”的**摘要**与 ASCII（而不是全方块列表）
- **AI做什么**：可选做“矩形积木序列”来切分建造区（你提出的方案）
- **产出**：


`C6_BuildAreaIndex.dat`（方块级mask索引）


`C6_BuildAreaSummary.json`（每个建造区：面积、bbox、平均坡度、可用率）


`C6_BuildAreaLayout.json`（AI返回的矩形切分结果，最多回滚2次）
- **存放位置**：`/saves/<WorldName>/terra_script/cities/<city_id>/`


## C7 模板池裁剪（AI+规则）


- **AI做什么**：为每个建造区选择可用模板池、是否换皮、是否需要拼图式扩展、拼图深度/权重
- **程序做什么**：根据模板尺寸/碰撞/生态/高度约束裁掉不合法模板
- **产出**：`C7_TemplateSelectionPlan.json`
- **可回滚点**：可在本阶段重选
- **存放位置**：`/saves/<WorldName>/terra_script/cities/<city_id>/`


## C8 基台与垂直处理（程序执行 + AI风格参数）


- **AI做什么**：给“权威感/仪式感/荒蛮感”等参数（决定基台高度、护坡材料风格）
- **程序做什么**：生成台基、护坡、台阶、缓冲绿化
- **产出**：`C8_TerracePlan.json`
- **可回滚点**：可回滚（只影响地形改造与台基）
- **存放位置**：`/saves/<WorldName>/terra_script/cities/<city_id>/`


## C9 建筑放置与装饰（程序执行，AI评审）


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


# 总结：三大模块的“谁负责什么”


- **世界构造**：AI负责“讲得通 + 约束”，程序负责“算得准 + 存得下”
- **国度构造**：AI负责“政治与叙事”，程序负责“扩张落地 + 边界数据”
- **城市生成**：AI负责“语义/风格/取舍”，程序负责“几何/遮罩/落地执行”





**存放位置**：`/saves/<WorldName>/terra_script/territories/<territory_id>/`
**存放位置**：`/saves/<WorldName>/terra_script/territories/<territory_id>/`
**存放位置**：`/saves/<WorldName>/terra_script/cities/<city_id>/`
