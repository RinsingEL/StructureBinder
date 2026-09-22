# DS-05-v04 · 楼院账房商宅

32×31×38 格，4818 个可见方块；6 个房间或作业区、8 个使用与交通标记。紧凑两层商宅下层接待与货仓、上层起居账房卧室，七级双宽内梯通向有护栏的家用露台。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_crafts.py](../../../../../tools/structure_studio/studio/oasis_crafts.py)，`merchant(4)`。在工具目录执行 `python -m studio.build DS-05-v04`。

填充结构；下层展示、候坐、织物架与打包货箱，上层阅读柜、账台、厨房、床位、露台坐席与饮水都有布置。空间标记包括：楼下接待铺、后仓与楼梯、楼上起居、会客账房、露台卧室、楼上家用露台。

- **选址**：可靠供水的贸易聚落，有顾客步行、货物卸载与家居生活需求
- **地块**：下层铺面与货仓，上层家居、账房和露台；内部楼梯连接商业与生活。
- **接地**：主入口与地面层脚底 Y=2；上层版二层脚底 Y=9
- **落地**：独立完整建筑与内部院落，不依靠运行时补齐；模板外与凹角未写入格保持原环境

已完成当前 NBT 外观、内饰、作者标记与已标站位的离线验收。交易、账记和库存仅作空间与标记表达，尚未接入经济玩法。未启动 MC，未接入国度 Mod 运行时目录。
