# C4 多边形 Tag 标记

## 当前状态

- 状态：方案已定，但主工程尚未完整按方案落地
- 当前代码里最接近的实现是：`world.city.stage.c4.CitySemanticStages`
- 但当前实现更像“district 功能规划”，不是 `solution.md` 里的严格 polygon tagging

## C4 在当前项目里的真实分层

当前 C4 实际分成两段：

1. 由 AI / 人工提交一个 function whitelist
2. 程序基于 district、layer、邻接关系自动推导功能规划

所以当前 C4 的真实边界是：

- AI 决定“允许什么功能集合”
- 程序决定“具体某个 district 最终用什么功能”

## 一、whitelist 提交

### 入口

| 类 | 方法 | 说明 |
| --- | --- | --- |
| `server.mcp.CityController` | `handleCityC4WhitelistGenerate(HttpExchange)` | 保存 whitelist |
| `server.mcp.CityController` | `handleCityC4WhitelistData(HttpExchange)` | 读取 whitelist |

### 核心类与关键方法

| 类 | 方法 | 作用 |
| --- | --- | --- |
| `server.mcp.CityController` | `handleCityC4WhitelistGenerate(...)` | 读取 AI 输入 |
| `world.city.stage.c4.CitySemanticStages` | `saveFunctionWhitelist(...)` | 保存 whitelist |
| `world.city.stage.c4.CitySemanticStages` | `loadFunctionWhitelist(...)` | 读取 whitelist |

### 当前输入

- `city_id`
- `primary_functions[]`
- `secondary_functions[]`
- 可选 `version`
- 可选 `source`
- 可选 `rationale`

### 当前输出

- `C4_FunctionWhitelist.json`
- `function_whitelist_version`
- `primary_count`
- `secondary_count`

## 二、功能规划生成

### 入口

| 类 | 方法 | 说明 |
| --- | --- | --- |
| `server.mcp.CityController` | `handleCityC4Generate(HttpExchange)` | 生成 C4 规划 |
| `server.mcp.CityController` | `handleCityC4Data(HttpExchange)` | 读取 C4 数据 |

### 核心类与关键方法

| 类 | 方法 | 作用 |
| --- | --- | --- |
| `world.city.stage.c4.CitySemanticStages` | `generateC4(...)` | 生成 C4 plan |
| `world.city.stage.c4.CitySemanticStages` | `saveC4(...)` | 保存 plan 和 validated 版本 |
| `world.city.stage.c4.CitySemanticStages` | `buildDistrictAdjacency(...)` | 计算 district 邻接 |
| `world.city.stage.c4.CitySemanticStages` | `inferPrimaryFunction(...)` | 推导 primary function |
| `world.city.stage.c4.CitySemanticStages` | `inferSecondaryFunctions(...)` | 推导 secondary functions |
| `world.city.stage.c4.CitySemanticStages` | `inferPriority(...)` | 计算优先级 |
| `world.city.stage.c4.CitySemanticStages` | `inferConstraints(...)` | 计算邻接约束 |
| `world.city.stage.c4.CitySemanticStages` | `validateC4(...)` | 生成 validated plan |

## 三、程序具体怎么做

### 1. 先读取城市 district 和 layer 结构

`generateC4(...)` 会读取：

- `city.districts`
- `city.getLayerLayout()`
- district 邻接关系

### 2. 对每个 district 生成 DistrictFunction

每个 district 会被推导出：

- `district_id`
- `layer`
- `zone_type`
- `primary_function`
- `secondary_functions`
- `priority`
- `constraints`
- `notes`
- `chunk_count`
- `area_blocks`
- `centroid`

### 3. primary function 怎么来

当前代码的思路是：

- 先根据：
  - `zone_type`
  - 是否是 wall layer
  - `density`
  做一个 `inferredPrimary`
- 再根据 whitelist 做合法化和回退

也就是说，当前 primary function 不是 AI 直接逐区块指定，而是程序在 whitelist 约束下推导出来的。

### 4. secondary / priority / constraints 怎么来

程序还会继续根据 primary function 推导：

- secondary functions
- district priority
- avoid / prefer adjacent 约束
- 补充 notes

### 5. 结果怎么保存

`saveC4(...)` 会同时写两份：

- `C4_FunctionPlan.json`
- `C4_FunctionPlan.validated.json`

其中 validated 版本会对功能集合再次清洗，确保不超出 whitelist。

## 四、输入

- `city_id`
- district 列表
- layer layout
- whitelist

## 五、输出

- `C4_FunctionWhitelist.json`
- `C4_FunctionPlan.json`
- `C4_FunctionPlan.validated.json`

输出字段重点：

- `district_functions[]`
- `adjacency`
- `function_whitelist_version`

## 六、AI 决策和程序计算的边界

### 1. AI 决策的部分

当前代码里 AI 真正直接决定的是：

- `primary_functions[]`
- `secondary_functions[]`
- whitelist 版本和来源说明

### 2. 程序计算的部分

- district 邻接关系
- 每个 district 的功能推导
- secondary / priority / constraints 推导
- validated plan 生成

## 七、当前代码和方案的差异

- `solution.md` 的 C4 是“对 polygon 逐块打标签”。
- 当前代码的 C4 更像“对 district 做功能规划”。
- grouping 仍交给 C5，这点与方案一致。
- 但还没有 `C4_PolygonTagTable.json` 那套严格文件体系。

