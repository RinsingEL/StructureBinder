# 技术改进与协同规范 (Technical Improvements & Synergy Specs)

## 1. 字段一致性与冗余治理
- **现状**：JSON 中区块字段 `t` (简写) 与 `type` (全称) 混合使用，Java 端解析逻辑复杂。
- **改进规范**：
    - **海量数据块** (如 `chunks[]`)：统一使用单字母简写 `t` 以压缩文件体积。
    - **顶层/分区对象** (如 `districts[]`)：统一使用全称 `type` 以提高可读性。
    - **推荐映射**：`C` -> `CORE`, `U` -> `URBAN`, `B` -> `BUFFER`, `R` -> `RING`。

## 2. 健壮性与版本控制
- **缺失项**：当前 JSON 缺少版本号，导致主 Mod 无法识别过时的配置。
- **改进规范**：
    - 在 JSON 根部引入 `"version": "1.0"`。
    - **Fallback 机制**：辅助 Mod 导出时应尽量填充数值（避免 `null`），主 Mod 读取时必须为每个可选字段配置默认值（如 `territory` 默认为 `unknown`）。

## 3. 性能隐患分析 (面向主 Mod 开发)
- **IO 竞态条件**：`CityManager.saveToFile` 在异步线程中直接读取活跃的 Map 集合。在高频生成时，可能导致导出的 JSON 结构损坏。
    - **优化方案**：在启动 IO 线程前，先进行内存快照（Snapshot）拷贝。
- **内存峰值控制**：大型城市群的 JSON 序列化非常消耗内存。
    - **优化方案**：建议从 `Gson.toJson(Object)` 切换到流式 API `JsonWriter` 直接写入文件流。

## 4. 协同职责边界
- **辅助 Mod (TerraSense)**：负责“计算权重”、“路径搜索”与“空间拓扑规划”。它应作为“大脑”，输出确定性的区块占领方案。
- **主 Mod (StructureBinder)**：负责“地形采样”、“方块注入”与“运行时实体维护”。它应作为“手脚”，忠实执行 JSON 中的规划。
