# 2026-10-20 学院原始回放证据恢复

**2026-10-27 补充**：用户已提供同轮 steps 原始资料，现已补齐 86 单元及历史几何快照，见 [真实展开数据](../data/README.md)。下文保留此前仅从日志恢复时的范围；不再代表项目当前资料缺失。

来源：用户已迁入本机的原始会话 `rollout-2026-10-20T15-52-44-01a1584c-6591-7b70.jsonl`；SHA-256 与 `../replay-evidence.json` 相同。未复制会话正文或模型内部内容，只恢复公开工具返回的图片与建筑账本记录。

## 恢复结果

49 张 PNG，46 张内容不重复，逐张通过 PNG 校验。详见 `manifest.json` 的源行号、工具调用 ID、字节数与 SHA-256。事件流里的 23 份图片文本被截断，不能解码；真正完整的图片存在 response_item 工具输出的 input_image 数据中，已从后者恢复。

| 阶段 | 原始文件 | 说明 |
| --- | --- | --- |
| 南宿院初次申请 | [line-0347-3.png](line-0347-3.png) | 全城编译预览 |
| 南宿院初次结果 | [line-0354-5.png](line-0354-5.png) | 局部图明确 retained=1，不能画成四栋已保留 |
| 南宿院改核心配套 | [line-0392-1.png](line-0392-1.png) | 修改后的组团局部图 |
| 东12、南6后 | [line-0502-3.png](line-0502-3.png) | 全城编译预览 |
| 改生活巷后 | [line-0510-3.png](line-0510-3.png) | 全城编译预览 |
| 生活巷小宿舍 | [line-0519-1.png](line-0519-1.png) | student_south_houses，三栋配套；不包含另一个核心组 |
| 北院补餐饮后 | [line-0565-3.png](line-0565-3.png) | 全城编译预览 |
| 最后全城预览 | [line-0583-3.png](line-0583-3.png) | 与已有 astra-d4.png 的 SHA-256 完全相同 |
| D7 放置预览 | [line-0712-2.png](line-0712-2.png) | 后续施工阶段返回的图像，不能倒置为每次设计修改的状态 |

[south-comparison.jpg](south-comparison.jpg) 仅裁取两张局部图左侧并缩小拼接，左为最初单栋，右为后来的三栋生活巷配套；两图缩放范围不同，不作同尺度位移比较。

## 精确数值边界

`placed-structures.partial.json` 从源第666行公开工具返回恢复。D7 placed_structure_ledger.json 当时只读取第一页24,000字符，nextOffset=24000、hasMore=true，未在本日志找到后续读取。能解析出两条完整记录，第三条已截断，未拼造缺失内容。

- seminars_required_001：studio:mg-36-v02；(-3136,5067)；57×49占地；基准Y=66。
- seminars_required_002：studio:mg-34-v03；(-3071,5077)；47×29占地；基准Y=66。

这两条包含 actualFootprint、lockedActualFootprint、旋转、模板hash和入口。它们是教学区，不是南宿院。图片能展示这轮真实编译结果，但不等于已经恢复全城86单元的精确逐栋JSON，也不提供墙体/屋檐的非矩形投影。

## 本机查找范围与补齐入口

已查当前存档、runtime 备份、下载目录学院压缩包、报告交接包及该轮原始日志。找到的本地存档/备份为10月7至12日的其他方案，不能代替这一轮。当前两个HTML均未修改。

如果后续需要矢量化逐栋动画，源电脑的历史记录指向存档 `Academy_D3_04_20261020`。应提供它的 `realm_debug/provider_3d25631f_r19200/city_test_runs/city_jingchao_capital/steps/`：至少 blueprint/、d4/、d7/placed_structure_ledger.json；最好直接打包 steps。该 Windows 路径只是历史定位线索，未连接或核实源电脑当前状态。
