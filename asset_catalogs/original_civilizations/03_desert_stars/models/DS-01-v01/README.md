# DS-01-v01 · 月井商队大驿站

53×23×51 格，8914 个可见方块；10 个房间或作业区、14 个使用与交通标记。四边厚墙围合月井内庭，北侧拱门进入；四间双床客房与餐饮后勤各自分区。 蓝色穹顶、赭色门楼与分段遮阳廊区别于铁路街区。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [剖面](previews/roof-off.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[desert.py](../../../../../tools/structure_studio/studio/desert.py)，`desert(1)`。在工具目录执行 `python -m studio.build DS-01-v01`。

- **选址**：有可靠水源的商路补给节点，不因沙地外观自动成立
- **地形**：约 47×43 格可整备平缓地块，北侧保留旅人与货运动线
- **接地**：地坪 Y=1、室内与院内脚底 Y=2
- **组合**：自身含客房、饮食、存货与庭院；动物换乘仍由外部兽栏承担

已完成离线外观、内饰、作者标记与入口至已标站位的碰撞通路初筛。原版方块表达相应用途；实际世界生成、玩法机制与国度 Mod 运行时目录接入需另行验证。
