# DS-10-v01 · 黄铜星仪台

35×32×35 格，4070 个可见方块；4 个房间或作业区、7 个使用与交通标记。三层登临次序为接待计算、藏书值守、露天观测；砂岩塔身、蓝色腰线与黄铜星仪形成垂直轮廓。 星仪是原版方块构成的空间装置，不代表天文或望远镜机制已接入。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [剖面](previews/roof-off.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[desert.py](../../../../../tools/structure_studio/studio/desert.py)，`desert(10)`。在工具目录执行 `python -m studio.build DS-10-v01`。

- **选址**：视野开阔且可安全登临的台地，避免树冠与高崖遮挡观测方向
- **地形**：平缓台地上的独立观测塔，入口脚底 Y=2，二层 Y=9，观测露台 Y=16
- **连接**：两段内部楼梯，保留入口步行和露台四周净空

已完成离线外观、内饰、作者标记与入口至已标站位的碰撞通路初筛。原版方块表达相应用途；实际世界生成、玩法机制与国度 Mod 运行时目录接入需另行验证。
