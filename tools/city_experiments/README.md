# 离线学院阵列工具

已实现 CLI 和 MCP stdio，复用项目 Java 算法，不需要启动 Minecraft。工具没有自动注册进当前聊天的 MCP 配置。题目仍只要求教学、生活、种植、迎宾、码头。

| 工具 | 调用 | 结果 |
|---|---|---|
| array_preview | 单个 array，seed 可选 | 单阵列占地、成员坐标、D3反馈；不改整城版本 |
| layout_preview | arrays，compositions/seed 可选 | 全场图、布局放大图、每阵列局部图；创建新版本 |
| layout_update | baseRevision，完整 arrays/compositions 或 removeIds | 按稳定ID替换/追加/删除，返回变化成员和新版本 |

完整机器接口在 `工具Schema.json`。调用方法见 `可调用入口.md`。`工具示例.json` 是工具冒烟样例，不是第三题的正式答卷；`工具修订示例.json` 演示只挪迎宾阵列。示例执行结果放在 result/array-tools/。

## 坐标与参数

array 必填 arrayId、anchorBlock:{x,z}、algorithm、count。核心 coreAssetId 可选；requiredAssetIds 为必需成员；fill 为 {assetId,weight} 列表。count 包括核心和必需成员，剩余按权重及固定seed选择；素材可以混用真实 studio: 和假 mock: 引用。

anchorBlock 是世界坐标的占地原点，不是建筑中心。存在核心时，核心固定在此原点。无核心时，它是传给生产算法的构图参考点；COURTYARD/CENTER_SYMMETRIC 的成员围绕参考点展开，因此首栋不保证就在该点。CONTIGUOUS 使用该点作为拼接布局偏移。尺寸来自冻结目录，90/270度旋转交换宽深。

directionDegrees 可选 0/90/180/270，控制阵列向 +X/+Z/-X/-Z 展开；rotation 独立控制模板旋转，不声称已解析入口朝向。densityClass 为 SPARSE/BALANCED/DENSE，映射生产算法间距。支持 GRID、LINEAR、COURTYARD、COMPACT、ORGANIC_COMPACT、CENTER_SYMMETRIC、CONTIGUOUS；CONTIGUOUS 当前只使用世界轴，directionDegrees 必须为0，可旋转模板尺寸。未暴露自定义gap、行列数、院落开口等生产入口尚不能直接控制的参数。

compositions 通过 compositionId、anchorBlock、algorithm、memberIds 组织完整子阵列包围盒，允许嵌套；父组合会重新定位子组团，但保留组内坐标关系。每个子组团只能归属一个父组合，拒绝循环与未知ID。父组合不能使用CONTIGUOUS。未修改且不受组合移动影响的阵列保持确定性坐标；组合修改后，其子阵列可能一起移动。

## 真实执行与检查边界

几何直接调用 CityBlueprintGroupLayoutPlanner.propose 和 CityContiguousLayoutPlanner.plan，Java适配器放在独立实验 sourceSet，不进入模组发行包。Python只负责输入、版本、图片与清单。此入口不走正式D4的Patch选址/候选筛选，不会替换指定坐标、缩小结构或丢弃冲突成员。

每个成员返回尺寸、原点、旋转、实际几何引擎、生产算法轨迹（适用时）、D3水域面积比例、高差/坡度、碰撞对方和状态。使用完整占地做两两碰撞与D3覆盖检查，边界外仍可见并报告冲突；水域/高差作为工程待复核。它不是完整 CityBlueprintCompilerService 编译，也不验证地基、桥墩、NBT放置或通路可达，generationReady 固定为 false。

## 小型假结构

18种候选关注课堂/研讨、宿舍/食堂/厨房、菜圃/药草/温室、接待厅/门房/旅舍、栈桥/岸仓以及连廊。目录记录相关已有素材和具体缺口，名字保留括号、图上用虚线、无NBT。只有实际参与布局的候选进入 missing-assets.json，记录用量、用途、拟定尺寸和冲突用量；候选目录不等于已确认建模任务。

每次执行保存request、Java输入/结果/日志、新生成图片和missing-assets。layout_update 要求当前版本且冻结D3/目录指纹一致；旧版本或目录变更会明确拒绝。单个输出目录按顺序调用，不支持并发写同一状态。图片绝对路径对应生成时的本机位置，解压到别处时从该调用目录查看PNG即可。

# 可调用入口

依赖本地 StructureBinder 仓库、Java/Gradle 和带Pillow的Python；实验ZIP包含题目/数据/示例/结果，不是独立Java发行包。首次运行会编译Java。无须启动游戏。

本机入口：

```sh
cd '/Users/rinsing/Documents/文稿 - Rinsing的MacBook Air/project/StructureBinder'
/Users/rinsing/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 tools/city_experiments/array_tools.py --list-tools
/Users/rinsing/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 tools/city_experiments/array_tools.py --tool layout_preview --request '../designer_territoryMod/experiments/第三题_学院岛坐标布局_20261008/工具示例.json' --output /tmp/academy-my-trial
/Users/rinsing/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 tools/city_experiments/array_tools.py --tool layout_update --request '../designer_territoryMod/experiments/第三题_学院岛坐标布局_20261008/工具修订示例.json' --output /tmp/academy-my-trial
```

两条示例按顺序在全新的输出目录运行：首轮revision为1，修订示例baseRevision为1。重复使用目录时，使用当前state.json中的revision。--experiment 可指定解压后的题包目录；--output 指定独立结果/状态目录。跨机器请换成本机Python绝对路径与仓库路径，Python安装Pillow即可。

给支持MCP的模型使用时，配置一个stdio服务器，command为上述Python绝对路径；args依次为本机array_tools.py绝对路径、--stdio、--experiment、题包绝对路径、--output、独立结果目录。传输使用换行JSON-RPC，提供initialize、tools/list、tools/call；工具调用返回JSON与全场/放大PNG。当前未替你修改客户端MCP配置，普通网页聊天不能仅凭附件自动调用本机工具。

验证命令：

```sh
/Users/rinsing/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 tools/city_experiments/test_array_tools.py
```
