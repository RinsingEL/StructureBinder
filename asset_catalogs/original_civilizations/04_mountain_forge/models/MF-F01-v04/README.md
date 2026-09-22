# MF-F01-v04 · 前店后住杂货铺

23×25×29 格，2811 个可见方块。杂货前店与掌柜后居隔门分区。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [楼层](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/mountain_trade.py)；公共注册接入后可按完整 ID 重建；独立构建入口为 `python runtime/mountain_build.py MF-F01-v04`。

分类货架、称分装包台、订货架和周转箱服务前店；后居床、个人箱、餐厨桌及私人物品架完整，店居通路不穿家具。空间标记：分类杂货前铺、掌柜后居。角色：`planning_role.fill`。

- **选址**：服务工人与居民日常生活；依照门面、转角、坡差或院落要求落位，保持完整独立模板。
- **高程**：基础上边界及室内脚底 Y=2；入口街面需同高程。
- **保留空间**：完整独立建筑及院落，保留入口和装卸面，不依赖其他模板补墙。

已完成当前 NBT 与作者记录双哈希的离线验收。未启动 Minecraft；原版设备与空间不代表机器、经济、交通、流体或真实生成已接入。
