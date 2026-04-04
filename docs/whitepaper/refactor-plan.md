# 代码重构与架构解耦方案 (Refactoring & Decoupling Plan)

## 1. 重构目标 01：`CityManager.java` -> 引入领域驱动架构
- **现状分析**：职责过载，逻辑与 IO 混合。
- **重构步骤**：
    1. **提取 IO 层**：新建 `CityRepository` 类，专门负责 `terra_script_cities.json` 的读写。
    2. **解耦扩张算法**：将 `expandCity` 提取为 `CityExpansionStrategy` 接口，支持未来不同的城市扩张模式（如方正型、沿河型）。
    3. **状态隔离**：`CityManager` 仅作为城市实例的 Registry（注册表），不再干涉具体的生成过程。
- **IDE AI 配合建议**：
    - 让 Codex 负责：从现有 `CityManager` 中提取属性并自动生成标准化的 `Getter/Setter` 以及 `toCommand()` 转换逻辑。

## 2. 重构目标 02：`CityStage1Processor.java` -> 特征提取流水线化
- **现状分析**：巨型循环，难以扩展。
- **重构步骤**：
    1. **定义特征计算器接口**：创建 `ITerrainFeatureComputer`，子类分别为 `SlopeComputer`, `RoughnessComputer` 等。
    2. **数据通道化**：创建一个 `TerrainDataBuffer` 对象，让各个计算器像滤镜一样依次处理数据，而不是在一个循环里做所有事。
    3. **结果封装**：将最后的 `forbidden` 判定逻辑移至独立的 `ExclusionPolicy` 类。
- **IDE AI 配合建议**：
    - 让 Codex 负责：根据现有的 `computeSlope` 逻辑，编写对应的 `SlopeComputerTest` 单元测试用例，确保重构后算法一致性。

## 3. 重构目标 03：`StructureInjector.java` -> 命令模式解耦
- **现状分析**：底层 API 操作与高层决策逻辑耦合。
- **重构步骤**：
    1. **引入指令集**：`StructureInjector` 不再直接操作 `Level`，而是输出一系列 `PlacementCommand`。
    2. **统一执行器**：由 `WorkflowEngine` 负责在安全的 Tick 时间点统一执行这些指令，确保线程安全。
- **IDE AI 配合建议**：
    - 让 Codex 负责：编写繁琐的方块状态（BlockState）转换代码，以及将复杂的 NBT 标签解析为 POJO 的过程。

## 4. 总结：AI 辅助分工原则
- **人类架构师**：定义接口契约、划分职责边界、审查线程模型。
- **IDE AI (Codex)**：填充具体算法实现、编写样板代码（Boilerplate）、生成测试数据、补全 Javadoc 注释。
