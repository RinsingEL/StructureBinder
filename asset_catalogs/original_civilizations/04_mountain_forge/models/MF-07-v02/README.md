# MF-07-v02 · 四格分类矿仓

31×23×31 格，4117 个可见方块。四格分类仓有厚隔墙和十字搬运道。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [楼层](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/mountain_trade.py)；公共注册接入后可按完整 ID 重建；独立构建入口为 `python runtime/mountain_build.py MF-07-v02`。

煤铁铜金独立货格；前格各有验料台，后格各有出库保管架；横道账台与周转箱不占纵向主道。空间标记：四格矿料间、十字验收与搬运道。角色：`planning_role.fill`。

- **选址**：山前矿业聚落的稳定石质台地，正面朝 -Z；须避开落石、塌陷和洪水通道。
- **高程**：基础上边界及室内脚底 Y=2；入口街面需同高程。
- **保留空间**：依托实际货运面，保留宽门、搬运道及棚下空区；不得用矿堆填满人行主路。

已完成当前 NBT 与作者记录双哈希的离线验收。未启动 Minecraft；原版设备与空间不代表机器、经济、交通、流体或真实生成已接入。
