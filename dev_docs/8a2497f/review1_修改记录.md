# 8a2497f - review1 修改记录

## 说明

本记录对应 `8a2497f` 在第一轮 review 后的修复修改。

本轮目标是修复以下两个高优先级问题：

1. `group_id` 模式下执行 C8 会污染 city 级总产物
2. `strict_tag_source` 在 arrangement 提交链路中未真正生效

---

## 修改 1：打通 `strict_tag_source` 到 arrangement 提交链路

### 问题

此前 `/city_c7_generate` 虽然已经支持：

- 全局默认 `strict_tag_source_default`
- 请求体显式传入 `strict_tag_source`

但只有“自动生成”分支会使用这个值。

当请求中包含 `arrangements` 时，会改走：

- `CityC7Stages.fromDecisionRequest(...)`

而这个方法内部把：

- `result.strict_tag_source`

写死成了 `true`，导致 arrangement 提交链路始终强制严格模式。

### 本次修改

在 `CityC7Stages` 中新增重载：

- `fromDecisionRequest(String cityId, C6Layout c6Layout, JsonObject request, boolean strictTagSource)`

行为改为：

1. 由调用方显式传入 strict 配置
2. `result.strict_tag_source` 不再写死为 `true`
3. 后续候选筛选继续统一读取 `selection.strict_tag_source`

在 `CityController.handleCityC7Generate(...)` 中同步调整：

- 当请求包含 `arrangements` 时，也把 `strictTagSource` 传入 `fromDecisionRequest(...)`

### 修复后的效果

现在两条 C7 入口行为一致：

1. 自动生成分支支持严格/宽松切换
2. arrangement 提交分支也支持严格/宽松切换

不会再出现：

- 配置已改成宽松
- 但 AI / 人工提交 arrangement 仍然被强制严格过滤

---

## 修改 2：C8 的 group 调试不再覆盖 city 级 `C8_FoundationPlan.json`

### 问题

此前 `handleCityC8Generate(...)` 在带 `group_id` 时会：

1. 优先读取 group 目录下的 `c7_selection.json`
2. 用这个“局部 C7 数据”去生成整城 C8 plan
3. 直接覆盖保存 city 级：
   - `C8_FoundationPlan.json`

这样会导致：

- 一次 group 调试请求把全局 C8 正式产物写坏
- 其它 group 因缺少完整 C7 输入而退化
- 现场看到的返回结果、group 文件、city 文件三者可能出现不一致

### 本次修改

新增了用于 C8 生成阶段的读取逻辑：

- `loadC8GenerationSelection(...)`

新逻辑改为：

1. C8 生成优先读取 city 级 `C7_TemplateSelection.json`
2. 只有 city 级 C7 不存在时，才回退到 group 级 `c7_selection.json`

同时修改 `handleCityC8Generate(...)` 与相关复用路径：

1. 仅当请求是 city 级生成时，才执行：
   - `CityC8Stages.save(cityDir, plan)`
2. 当请求带 `group_id` 时，只输出 group 级调试文件：
   - `groups/<group>/c8_foundation.json`
   - `groups/<group>/c8_arrangement_debug.json`
3. 不再覆盖 city 级 `C8_FoundationPlan.json`

### 修复后的效果

现在行为变成：

1. city 级请求负责生成并写入 city 级总产物
2. group 级请求只负责本 group 的调试输出
3. 局部调试不会再反向污染全局 C8 文件

这也更符合后续排查 `g_market_03` 的使用方式。

---

## 本次涉及文件

代码：

- `src/main/java/com/user/terra_script/server/mcp/CityController.java`
- `src/main/java/com/user/terra_script/world/city/stage/c7/CityC7Stages.java`

测试：

- `src/test/java/com/user/terra_script/server/mcp/CityControllerTest.java`
- `src/test/java/com/user/terra_script/world/city/stage/c7/CityC7StagesTest.java`

---

## 本次补充测试

### 1. C7 strict 透传测试

新增测试确认：

- `fromDecisionRequest(..., false)` 会保留 `strict_tag_source = false`

避免之后再次回退成写死严格模式。

### 2. C8 选择源优先级测试

新增测试确认：

1. 若 city 级 C7 存在，则 C8 生成优先使用 city 级 C7
2. 若 city 级 C7 不存在，则允许回退到 group 级 C7

这样可防止 group 调试再次污染全局生成链路。

---

## 验证

本轮执行的验证命令：

```bash
./gradlew test --tests "com.user.terra_script.server.mcp.CityControllerTest" --tests "com.user.terra_script.world.city.stage.c7.CityC7StagesTest"
```

结果：

- 通过

---

## 当前结论

`review1` 已完成以下修复：

1. `strict_tag_source` 已贯通到 arrangement 提交链路
2. group 级 C8 调试不再覆盖 city 级 `C8_FoundationPlan.json`

后续如果继续排查 `g_market_03` 的 C8 现场问题，建议基于本次修复后的日志和 group 级产物继续观察，而不是再参考修复前的 city 级 C8 总文件状态。
