# MF-06-v01 · 临街筛磨宝石铺

25×23×27 格，2875 个可见方块。宽面前店经隔门进入后坊，长脊厚石屋有连续承檐。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [楼层](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/mountain_trade.py)；公共注册接入后可按完整 ID 重建；独立构建入口为 `python runtime/mountain_build.py MF-06-v01`。

交易文书、柜台、铺毡验货台、候客长凳与三组样品陈列组成顾客区；后坊完整切磨水洗、原石分级、装配包装、成品储架与贵重成品柜分侧，中央搬料通畅。空间标记：前店交易与陈列、后场筛磨保管。角色：`planning_role.fill`。

- **选址**：邻近矿料路线且具有干燥安全保管条件；切磨声与扬尘需和生活门面分开。
- **高程**：基础上边界及室内脚底 Y=2；入口街面需同高程。
- **保留空间**：完整独立建筑及院落，保留入口和装卸面，不依赖其他模板补墙。

已完成当前 NBT 与作者记录双哈希的离线验收。未启动 Minecraft；原版设备与空间不代表机器、经济、交通、流体或真实生成已接入。
