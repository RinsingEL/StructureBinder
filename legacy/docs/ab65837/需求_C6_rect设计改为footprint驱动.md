# 需求：将 C6 rect 设计从“默认参数 + AI 摆框”升级为“结构 footprint 驱动”

## 1. 背景

当前 rect 设计链路主要是：

1. 系统按功能区面积生成默认 `fill_params`
2. 系统给出固定档位 `rect_sizes`
3. AI 根据预览图和功能区信息提交 rect
4. 系统只校验 coverage 与总面积比例

这套流程可以快速得到可用 rect，但它并不是从候选结构池的实际 footprint 出发设计的，因此很容易出现：

- rect 形状与结构池不匹配
- rect 边界虽然“合法”，但对后续结构放置并不友好
- C8/C9 再去补边界合法性时成本变高

## 影响范围

- 阶段范围：以 `C6` 为主，直接影响 `C7` 的结构选择前提与 `C8` 的空间求解边界基础
- 代码范围：`CityC6Stages`、`CityC6Validation`、`PlazaRingArranger`，必要时联动 `CityC7Stages`
- 数据/产物范围：`C6_RectDecisionInput.json`、`C6_BuildAreaLayout.json`、`C6_RectValidation.json`，以及后续被 `C7/C8` 消费的 rect/module 约束信息
- 测试范围：`C6` 相关布局与校验测试，外加覆盖 `C7/C8` 消费新约束的回归测试

## 2. 当前问题

### 2.1 默认参数只看 area 面积，不看结构池

`PlazaRingArranger.defaultParams(areaBlocks)` 当前只根据区域面积推导：

- plaza 半径
- ring 深度
- 开口数
- spacing / jitter

没有使用：

- function tag 对应候选结构池
- 候选结构 footprint 面积分布
- 典型长宽比

### 2.2 rect 尺寸档位是固定模板，不是按功能推导

`defaultRectSizes()` 当前固定为三档：

- `S1`
- `M1`
- `L1`

它们不是从 catalog 统计出来的，也不体现不同 function / interaction role 的差异。

### 2.3 AI 输入约束不足

当前 `C6_RectDecisionInput.json` 给 AI 的更多是：

- 功能区位置
- 面积
- bbox
- 预览图
- rect_limit

但缺少对“什么样的 rect 才适合后续结构池”的明确约束，例如：

- 推荐 rect 数量
- 建议面积预算
- 候选 footprint 范围
- 推荐长宽比范围

### 2.4 校验只看 coverage，不看结构适配性

`CityC6Validation.validateSubmission(...)` 主要确保：

- rect 在区域内
- 覆盖率达标
- 总面积比例达标

但并不检查：

- rect 是否适合该 function 的候选结构
- rect 面积是否能承载推荐模板
- 多个 rect 的组合是否与编排策略相匹配

## 3. 需求目标

把 rect 设计改造成“先理解结构池，再推导 rect 预算与约束”的链路，使 C6 输出的 rect 更适合作为 C7/C8 的空间前提。

核心目标：

1. rect 数量、尺寸、长宽比应与候选结构 footprint 分布有关
2. AI 提交 rect 时应拿到更明确的结构侧约束
3. C6 校验应新增“结构适配性”判断，而不只看 coverage

## 4. 目标流程

建议将链路调整为：

1. 按 function tag、interaction role、size tier 过滤候选结构池
2. 统计候选结构 footprint 的面积分布、宽高分布、长宽比、典型模板
3. 推导该 group/build area 的 rect 数量建议、面积预算、尺寸范围、长宽比约束
4. 将这些约束写入 `C6_RectDecisionInput`
5. AI 在这些约束内提交 rect
6. C6 校验同时检查 coverage 与结构适配性

## 5. 具体需求

### 5.1 增加 footprint 统计输入

C6 在准备 rect 决策输入前，应能拿到候选结构池的至少以下统计：

- 候选总数
- footprint 面积分布
- 宽度/长度分布
- 常见长宽比区间
- 典型模板示例

### 5.2 生成 rect 预算与约束

对于每个 build area / group，系统应生成可供 AI 参考的 rect 约束，例如：

- 推荐 rect 数量范围
- 单 rect 面积上下限
- 单 rect 宽高上下限
- 推荐长宽比范围
- 应优先容纳的大型主模板尺寸

### 5.3 扩充 C6 决策输入

`C6_RectDecisionInput` 中应新增表达上述约束的信息，使 AI 不是只看地形和预览图摆框。

### 5.4 扩充 C6 校验

提交后的 rect 校验不应只验证几何覆盖，还应验证：

- rect 是否与候选结构池尺寸相容
- 是否存在至少一批主模板可以合理落入这些 rect
- 多 rect 组合是否满足最小主结构承载能力

### 5.5 为下游阶段输出稳定约束

C6 最终产物应为 C7/C8 提供稳定约束基础，至少支持下游明确知道：

- 这个 rect 是为哪类结构尺寸准备的
- 它更偏主结构还是次结构
- 它是否具有明确的推荐模板范围

## 6. 范围

### 本需求包含

- C6 rect 决策输入增强
- footprint 统计与预算约束生成
- rect 提交校验增强
- 与此相关的测试

### 本需求不包含

- C9 运行时逻辑改造
- 结构注入执行细节
- 完整 AI prompt 策略重写

如需调整 AI 提示词，可在本需求落地时按新增输入同步微调，但不作为主目标。

## 7. 验收标准

### 场景 1：不同 function 得到不同约束

不同 function / interaction role 的 group，在相近面积下，也应因为结构池不同而得到不同的：

- rect 数量建议
- 尺寸预算
- 长宽比范围

### 场景 2：明显不适配的 rect 能被拒绝

如果提交的 rect 虽然 coverage 合格，但明显无法承载该结构池的主模板尺寸，应被校验失败或至少给出强告警。

### 场景 3：下游可消费

C7/C8 能从 C6 产物中读到对 rect 的结构侧约束，而不需要完全重新推导。

### 场景 4：保留兼容兜底

在 catalog 统计缺失或候选结构过少时，允许回退到当前默认档位逻辑，但必须显式标记为 fallback 路径。

## 8. 建议落点

优先检查以下位置：

- `src/main/java/com/user/terra_script/world/city/stage/c6/CityC6Stages.java`
- `src/main/java/com/user/terra_script/world/city/stage/c6/CityC6Validation.java`
- `src/main/java/com/user/terra_script/world/city/stage/c6/PlazaRingArranger.java`
- `src/main/java/com/user/terra_script/world/city/stage/c7/CityC7Stages.java`

## 9. 备注

这个需求直接提取自 [`C8_C9检查记录_20260327.md`](/E:/Mod_Dev/StructureBinder/dev_docs/ab65837/C8_C9检查记录_20260327.md) 的第六点。

它是一次方案级改造，建议独立于本轮 C8/C9 缺陷修复推进。
