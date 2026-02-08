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
    *   `scan_local_candidates(...)`：后端会去查 `W4_TerrainFacts.dat` 中的 slope/tpi 数组。

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
**核心逻辑**：T1 不再只做“国家设定”，还要做“多候选战略区筛选”。  
流程与 T2 一致：先扫描多个感兴趣区域，再通过 ASCII 图选择 `region_id`，最后提交蓝图。

*   **AI 做什么**：
  1. 调用 `W4_get_world_atlas` 获取 `atlas + world_summary`。
  2. 根据文明定位，定义 2~3 组兴趣条件（例如高海拔、低坡度、沿海、峡谷）。
  3. 调用 `scan_local_candidates(region_id=...)` 对候选大陆做局部扫描，拿到多簇候选 + ASCII。
  4. 在 ASCII 上做视觉推理，选择最优候选簇 `cluster_id`（并记录理由）。
  5. 提交 `t1_generate_blueprint`，把最终 `target_continent_id` 与扩张参数固化。

*   **程序 做什么**：
  * 提供 `W4_get_world_atlas` 和 `scan_local_candidates`。
  * 返回候选簇列表（每个簇都带 `cluster_id`、关键点、ASCII 图）。
  * 依据 `world_summary` 约束 `base_power` 合理范围（避免扩张力过大/过小）。
  * 存储蓝图到 `T1_Blueprint.json`。

*   **AI 可调用数据 (MCP)**：
  * `get_world_atlas()`
  * `scan_local_candidates(...)`
  * `t1_generate_blueprint(...)`

*   **产出 (JSON)**：`T1_Blueprint.json`（列表，按 `territory_id` 去重）
*   **存放位置**：`/saves/<WorldName>/terra_script/territories/`

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


## C3 多边形/区划种子与分区（程序主导，AI可选增强）
**目标**：在 C2 占地基础上生成稳定可解释的区划单元，供后续 C4 功能语义标注。  
**关键约束**：多边形初划分通常不考虑 layer，因此 C3 必须补一个“按 layer 二次拆分”步骤。

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
