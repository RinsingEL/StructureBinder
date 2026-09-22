# MF-06-v03 · 窄巷宝石鉴定店

19×23×27 格，2082 个可见方块。窄深鉴定铺分为前厅和隔门小库，比例区别于批量作坊。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [楼层](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/mountain_trade.py)；公共注册接入后可按完整 ID 重建；独立构建入口为 `python runtime/mountain_build.py MF-06-v03`。

顾客凳、估价文书、样品陈列和铺毡精检台分布前厅；后库保留复磨、编号封装、储架及贵重样品柜，每处站位可达。空间标记：鉴定陈列室、后部样品小库。角色：`planning_role.fill`。

- **选址**：邻近矿料路线且具有干燥安全保管条件；切磨声与扬尘需和生活门面分开。
- **高程**：基础上边界及室内脚底 Y=2；入口街面需同高程。
- **保留空间**：完整独立建筑及院落，保留入口和装卸面，不依赖其他模板补墙。

已完成当前 NBT 与作者记录双哈希的离线验收。未启动 Minecraft；原版设备与空间不代表机器、经济、交通、流体或真实生成已接入。
