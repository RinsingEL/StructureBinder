# 8a2497f - Review 问题记录

## 说明

本文件用于记录对提交 `8a2497f` 的代码审查结果。

当前仅记录本轮 review 中确认存在风险、且优先级较高的问题，便于后续逐项修复与回归验证。

---

## 问题 1：group 级 C8 调试会污染 city 级 C8 总产物

### 位置

- `src/main/java/com/user/terra_script/server/mcp/CityController.java`
- 重点关注：
  - `handleCityC8Generate(...)`
  - `loadC7Selection(...)`

### 问题描述

当请求带 `group_id` 时，`loadC7Selection(...)` 会优先读取 group 目录下的：

- `c7_selection.json`

这个文件只包含当前 group 的 C7 选择结果。

但是后续 `handleCityC8Generate(...)` 仍然直接调用：

- `CityC8Stages.generate(cityId, c6Summary, c6Layout, c7Selection, heightData, c2ScanData, c6Index)`

来生成整座城市的 C8 plan，并且紧接着执行：

- `CityC8Stages.save(cityDir, plan)`

把结果覆盖写回城市级总文件：

- `C8_FoundationPlan.json`

### 风险

这样会导致：

1. 本次 C8 生成时，只有当前 group 拥有完整的 C7 输入
2. 其它 group 因为缺少 arrangement / selection，生成出来的内容可能退化或缺失
3. 最终却把这个“部分输入生成出的整城结果”写回成全局正式产物

这会造成一种很隐蔽的问题：

- group 级调试请求看起来成功
- group 级文件可能也写出来了
- 但 city 级 `C8_FoundationPlan.json` 已经被局部调试结果污染

这与本轮开发日志里提到的现象高度一致：

- “返回成功，但 group 级 foundation 文件内容与预期不一致”
- “现场返回与 group 文件内容之间出现不一致”

### 建议修复方向

可考虑二选一：

1. `group_id` 模式下不要覆盖保存 city 级 `C8_FoundationPlan.json`
2. `group_id` 模式下生成前先加载完整 city 级 C7，再仅对目标 group 做过滤输出，而不是拿 group 级 C7 直接生成整城 plan

更稳妥的方向通常是：

- city 级产物只由 city 级全量输入生成
- group 级请求只写 group 级调试产物，不反向覆盖全局文件

---

## 问题 2：`strict_tag_source` 在 arrangement 提交链路中未真正生效

### 位置

- `src/main/java/com/user/terra_script/server/mcp/CityController.java`
- `src/main/java/com/user/terra_script/world/city/stage/c7/CityC7Stages.java`

### 问题描述

在 `handleCityC7Generate(...)` 中，已经新增了：

- 读取请求体中的 `strict_tag_source`
- 当未显式传值时，读取 `CityGenerationConfig` 默认值

也就是说，接口层已经支持：

1. 全局默认控制
2. 单次请求覆盖

但是这个值只在下面这个分支中被传入：

- `CityC7Stages.generate(cityId, c6Layout, strictTagSource)`

而当请求中包含 `arrangements` 时，代码会走：

- `CityC7Stages.fromDecisionRequest(cityId, c6Layout, json)`

这个分支没有接收 `strictTagSource`，并且在 `fromDecisionRequest(...)` 内部还直接写死了：

- `result.strict_tag_source = true`

后面候选筛选逻辑又会读取：

- `selection.strict_tag_source`

因此实际效果是：

- 自动生成分支：支持严格/宽松切换
- arrangement 提交分支：始终强制严格模式

### 风险

这会让新增功能在最需要它的链路上失效，典型表现包括：

1. 请求明明显式传了 `strict_tag_source=false`
2. 或者全局配置已经改成宽松模式
3. 但只要走 `arrangements` 提交链路，候选筛选仍然会继续严格过滤

这样就会出现：

- 配置“看起来生效了”
- 某些接口也确实生效
- 但 AI 提交方案或人工提交 arrangement 时却不生效

问题会非常难排查。

### 建议修复方向

建议把 `strictTagSource` 一并传入 `fromDecisionRequest(...)`，并确保：

1. `fromDecisionRequest(...)` 使用调用方传入的 strict 配置
2. 不再把 `result.strict_tag_source` 写死成 `true`
3. 后续候选筛选统一从该值读取，保证两条 C7 入口行为一致

---

## 当前状态

本轮 review 确认的高优先级问题共 2 条：

1. `group_id` 模式下 C8 会覆盖污染 city 级总产物
2. `strict_tag_source` 在 arrangement 提交链路中被忽略

后续修复时，建议在同一轮补充对应回归测试，至少覆盖：

1. group 级 C8 请求不会改坏 city 级 `C8_FoundationPlan.json`
2. `arrangements` 请求在 `strict_tag_source=false` 时确实允许宽松候选进入筛选
