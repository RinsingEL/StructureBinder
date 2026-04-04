# 项目代码白皮书：StructureBinder (TerraScript) 架构大纲

## 第一部分：策划设定 (Planning & Specifications)
*本部分定义项目的核心愿景、世界观逻辑及生成规则，作为代码实现的元数据来源。*

*   **1.1 世界观与地缘政治逻辑 (`world_lore.json`)**
    *   描述：定义国家、势力范围及文明演进的顶层元数据。
*   **1.2 城市与结构生成规则 (`terra_script_cities.json`, `solution.md`)**
    *   描述：规定建筑密度、功能分区（Districts）及文明等级的配置规范。
*   **1.3 禁置区与地形约束 (`ForbiddenZoneConfig.java`)**
    *   描述：硬性地理边界与生成排除逻辑。

## 第二部分：主工程架构：地形与生成 (Main Mod: Terrain & Generation)
*本部分负责 Minecraft 运行时的逻辑处理、配置消耗以及最终的方块注入。*

*   **2.1 核心工作流引擎 (Workflow & Orchestration)**
    *   **职责映射：** 
        *   `WorkflowEngine`: 驱动异步生成任务序列。
        *   `StageRegistry`: 注册从“地形分析”到“结构注入”的各个阶段。
*   **2.2 配置消耗与数据驱动 (Config Consumers)**
    *   **职责映射：**
        *   `StructurePlan`: 解析 JSON 计划并将其转化为内存中的生成指令。
        *   `WorldProjectData`: 存储当前存档的世界级生成进度与状态。
*   **2.3 地理空间分析工具 (Geo-Spatial Utilities)**
    *   **职责映射：**
        *   `TerrainFeatureComputer`: 分析高度场、坡度及生物群落。
        *   `VoronoiComputer`: 处理势力范围边界及国土划分。
        *   `DBSCAN`: 聚类原生结构，识别现有的村庄或建筑群。
*   **2.4 动态注入系统 (Injection System)**
    *   **职责映射：**
        *   `StructureInjector`: 负责将预制件（Templates）刷入世界。
        *   `CityBoundaryWallInjector`: 根据城防规划动态生成城墙。

## 第三部分：数据管线：辅助 Mod/工具链 (Data Pipeline: MCP & Tools)
*本部分负责外部干预、自动化规划以及原版数据的反向抓取。*

*   **3.1 结构抓取与脚手架 (Structure Scraper)**
    *   **职责映射：**
        *   `StructureDiscovery` (Main Mod 辅助): 运行时扫描原版结构坐标。
        *   `ScanDataIO`: 将扫描到的原版布局序列化为 JSON。
*   **3.2 MCP 控制层 (External Logic Controller)**
    *   **职责映射：**
        *   `country_designer_mcp/index.ts`: 外部 Node.js 服务，负责调用 AI 或复杂算法进行城市布局预演。
        *   `CityController` / `TerritoryController`: 在主 Mod 中暴露 HTTP 接口，接收外部工具的修改指令。
*   **3.3 可视化调试与预处理器 (Dev Tools)**
    *   **职责映射：**
        *   `drawTool/project_rectangles.py`: 将 JSON 布局转化为 PNG 预览。
        *   `maptool/grid_overlay.py`: 在卫星图上叠加势力范围。

---

**核心职责映射总结：**
*   **主 Mod (Forge)**: 负责“消耗”配置（`StructurePlan`）、“扫描”现状（`StructureDiscovery`）与“执行”注入（`StructureInjector`）。
*   **辅助 Mod (MCP/TS)**: 负责“计算”规划、接入外部算法，并将复杂的逻辑结果“反哺”给主 Mod 接口。