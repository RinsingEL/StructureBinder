# 数据契约白皮书 (Data Contract Whitepaper)

## 1. 概述
本文档定义了 StructureBinder 项目中主 Mod 与辅助工具（MCP/AI）之间交换数据的标准 JSON 格式。所有数据交换必须遵循本契约，以确保系统的稳定性和可预测性。

## 2. 核心数据结构 (JSON Schema)

### 2.1 城市布局规划 (`terra_script_cities.json`)
该文件定义了世界中所有城市的空间占位、行政分区及建筑密度。

```json
[
  {
    "id": "string",                // 城市唯一标识符 (格式: city_x_z)
    "territory": "string",         // 所属国家/势力 ID
    "center_x": "int",             // 城市中心世界坐标 X
    "center_z": "int",             // 城市中心世界坐标 Z
    "target_size": "int",          // 计划占用的区块总数
    "bias": "string",              // 扩张偏好 (BALANCED, COASTAL, INLAND)
    "ecology": "string",           // 生态策略 (ADAPTIVE, AGGRESSIVE, PROTECTIVE)
    "chunks": [                    // 城市占用的区块列表
      {
        "x": "int",                // 区块坐标 X
        "z": "int",                // 区块坐标 Z
        "t": "string",             // [精简字段] 区块类型 (C: Core, U: Urban, B: Buffer)
        "layer": "int"             // 该区块所在的环层索引 (0 为最内层)
      }
    ],
    "districts": [                 // 内部功能分区
      {
        "id": "int",               // 分区 ID
        "type": "string",          // 分区类型 (CORE, RESIDENTIAL, INDUSTRIAL, PARK)
        "blocks": [                // 分区包含的区块列表
          { "x": "int", "z": "int" }
        ]
      }
    ]
  }
]
```

### 2.2 世界理念与地缘政治 (`world_lore.json`)
定义国家的宏观属性及生成偏好。

```json
[
  {
    "concept_name": "string",      // 国家名称
    "preferred_region_id": "int",  // 首选地区 ID (对应地形分析结果)
    "theme": "string",             // 文化/建筑主题描述
    "capital_requirements": "string" // 首都选址的地形约束描述
  }
]
```

## 3. 技术规范与隐患改进

### 3.1 字段一致性
- **禁止缩写**：除非在海量数据块（如 `chunks` 列表）中为了节省空间使用 `t` 代替 `type`，其余顶层字段严禁使用缩写。
- **强制 ID 校验**：所有实体必须包含 `id` 字段。

### 3.2 容错与默认值
- **主 Mod 行为**：读取 JSON 时，若缺少可选字段，必须赋予预定义的 `Fallback` 值。
- **辅助 Mod 行为**：导出 JSON 时，必须确保数值字段不为 `null`。

### 3.3 性能优化建议
- **流式处理**：对于超过 10MB 的 `terra_script_cities.json`，建议从 `Gson.fromJson(Reader, ...)` 切换到 `JsonReader` 流式解析，以降低内存峰值。
- **异步 IO 锁**：在 `CityManager.saveToFile` 中，应对 `cities` 集合进行快照拷贝或使用读写锁，防止序列化过程中数据结构发生变化。

## 4. 版本控制
- JSON 根对象或首个条目应包含 `"version": "1.0"` 字段。
- 结构变更时需同步更新本白皮书并执行版本迁移逻辑。
