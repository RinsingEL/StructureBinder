# MF-07-v03 · 顺坡双面装卸仓

29×23×31 格，3459 个可见方块。低账房、低卸货棚及后高仓三体围阶院。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [楼层](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/mountain_trade.py)；公共注册接入后可按完整 ID 重建；独立构建入口为 `python runtime/mountain_build.py MF-07-v03`。

账房含登记文书，卸货棚含抽验长台和周转货箱，高仓两类矿料与中央出库分装台分开，短阶与仓门均可达。空间标记：低位收货账房、前部低街卸货棚、高街保管仓。角色：`planning_role.fill`。

- **选址**：山前矿业聚落的稳定石质台地，正面朝 -Z；须避开落石、塌陷和洪水通道。
- **高程**：低街脚底 Y=2、高仓 Y=3；+Z 单格坡差，四宽短阶供人员，不代表矿车能过台阶。
- **保留空间**：依托实际货运面，保留宽门、搬运道及棚下空区；不得用矿堆填满人行主路。

已完成当前 NBT 与作者记录双哈希的离线验收。未启动 Minecraft；原版设备与空间不代表机器、经济、交通、流体或真实生成已接入。
