# MF-12-v01 · 岩底双泵排水站

31×20×29 格，3673 个可见方块。双泵大厅采用双采光条脊，两个独立围护集水池各有检修环路，后部低排水槽与人行地坪分开。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [楼层](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/mountain_forge.py)；在工具目录执行 `python -m studio.build MF-12-v01` 重建。

铜管泵架、检修杆、水盆、备件台与货架、值守床和登记桌完整；水槽尽端仅作为排水接口说明。空间标记：双泵巡检厅、隔墙值班应急间、备件与维护间。角色：`planning_role.key`。

- **选址**：山前矿业聚落的稳定石质台地，正面朝 -Z；须避开落石、塌陷和洪水通道。
- **高程**：基础上边界及室内脚底 Y=2；入口街面需同高程。
- **保留空间**：完整独立建筑及院落，保留入口和装卸面，不依赖其他模板补墙。
- **服务**：仅用于存在地下水或汇水需求的低位矿业设施；尾部排水口必须连接有坡降的安全受水去向。

已完成当前 NBT 与作者记录双哈希的离线验收。未启动 Minecraft；原版设备与空间不代表机器、经济、交通、流体或真实生成已接入。
