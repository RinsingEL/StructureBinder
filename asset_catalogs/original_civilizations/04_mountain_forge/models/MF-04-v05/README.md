# MF-04-v05 · 双翼更班院舍

33×25×32 格，4237 个可见方块。双寝翼各四床，后部共厨连接两翼形成换班院；街门、院门分别出入。 出檐承托修复后已实际复查外观连接。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [楼层](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/mountain_life.py)；公共注册接入后可按完整 ID 重建；独立构建入口为 `python runtime/mountain_build.py MF-04-v05`。

八床和柜体、两条装备架、共厨短餐台、院中歇脚与洗涤均可见，门道未被家具堵住。空间标记：四床更班寝翼、四床更班寝翼、后部公共餐厨、换班院。角色：`planning_role.fill`。

- **选址**：紧凑平整生活台地，室内与街面脚底 Y=2。
- **高程**：基础上边界及室内脚底 Y=2；入口街面需同高程。
- **保留空间**：完整独立建筑及院落，保留入口和装卸面，不依赖其他模板补墙。

已完成当前 NBT 与作者记录双哈希的离线验收。未启动 Minecraft；原版设备与空间不代表机器、经济、交通、流体或真实生成已接入。
