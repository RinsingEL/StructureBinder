# SR-F02-v03 · 院边 L 形田

25×10×25 格，912 个可见方块。L 形田沿院墙或道路转角包边，凹口保留给邻地；双向作业道 成熟作物与少量待收行表现连续生产；水沟保持开放维护，工具位置避开主要通行。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [剖面](previews/roof-off.png) · [标记](previews/annotations.png) · [适用地形示意](previews/site-context.png)
- 可重建源码：[agriculture.py](../../../../../tools/structure_studio/studio/agriculture.py)，`farm(3)`。在工具目录执行 `python -m studio.build SR-F02-v03`。

- **选址**：铁路聚落郊区；需要土壤、足够光照与可维护的水源
- **地块**：L 形田沿院墙或道路转角包边，凹口保留给邻地；双向作业道
- **地坪**：田埂脚底 Y=2；平地版本
- **落地方式**：固定模板田块；独立成品，非 Landscape 自然生长大田
- **边界**：仅填入实际田块与田埂范围；形状外空格不参与清地，凹口和收窄边界留给地形或邻地

已完成离线外观、内部或作业空间、作者标记与碰撞通路初筛。验收对应当前 NBT 与作者文件哈希；实际世界生成、游戏行为和国度 Mod 运行时目录接入需另行验证。
