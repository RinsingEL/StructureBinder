# MF-07-v01 · 小车紧凑矿仓

23×23×26 格，2437 个可见方块。紧凑双列矿仓以宽装卸口对中央硬路。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [楼层](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/mountain_trade.py)；公共注册接入后可按完整 ID 重建；独立构建入口为 `python runtime/mountain_build.py MF-07-v01`。

铁料与煤堆左右分开，门侧验货台、后账台、出库器材架分别服务到货、登记和发料；可见中央小车道连续。空间标记：双列紧凑仓。角色：`planning_role.fill`。

- **选址**：山前矿业聚落的稳定石质台地，正面朝 -Z；须避开落石、塌陷和洪水通道。
- **高程**：基础上边界及室内脚底 Y=2；入口街面需同高程。
- **保留空间**：依托实际货运面，保留宽门、搬运道及棚下空区；不得用矿堆填满人行主路。

已完成当前 NBT 与作者记录双哈希的离线验收。未启动 Minecraft；原版设备与空间不代表机器、经济、交通、流体或真实生成已接入。
