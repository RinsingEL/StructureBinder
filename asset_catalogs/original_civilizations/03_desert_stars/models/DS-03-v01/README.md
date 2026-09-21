# DS-03-v01 · 蓝柱地下蓄水厅

36×25×40 格，5209 个可见方块；3 个房间或作业区、5 个使用与交通标记。地上小门房连接十一格下降楼梯；地下柱廊围绕蓄水池，检修通道和记录区在干侧。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [剖面](previews/roof-off.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[desert.py](../../../../../tools/structure_studio/studio/desert.py)，`desert(3)`。在工具目录执行 `python -m studio.build DS-03-v01`。

- **选址**：可靠集水或引水系统的地下储水节点，需足够岩土覆深与防渗条件
- **埋置**：模型 Y=14 对应地表脚底；主体底部 Y=0，预留约 14 格地下空间
- **入口**：北侧值守门廊接下行楼梯，检修平台脚底 Y=3
- **水**：蓄水池为封闭原版水体，补水、排水与容量逻辑尚未接入

已完成离线外观、内饰、作者标记与入口至已标站位的碰撞通路初筛。原版方块表达相应用途；实际世界生成、玩法机制与国度 Mod 运行时目录接入需另行验证。地下与旧井模型必须按作者地表高程埋置，不能直接把模板最低层当作地表。
