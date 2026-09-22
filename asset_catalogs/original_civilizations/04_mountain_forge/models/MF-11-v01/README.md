# MF-11-v01 · 封册旧矿口

31×19×29 格，5417 个可见方块。带局部不规则石突的山体入口片段包裹坑木旧轨巷，侧边独立铜顶登记房共用巡查坪。 出檐承托修复后已实际复查外观连接。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [楼层](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/mountain_forge.py)；在工具目录执行 `python -m studio.build MF-11-v01` 重建。

登记桌、装备架、维护台、旧轨和坑木连续，巷道末端铁栅与塌方清楚划定不可继续探索边界。空间标记：登记与装备间、封闭旧巷。角色：`planning_role.structure`。

- **选址**：仅用于山体前缘，+Z 旧巷尽端须接实山；岩体外壳是局部入口表达，不能悬放平原。
- **高程**：基础上边界及室内脚底 Y=2；入口街面需同高程。
- **保留空间**：完整独立建筑及院落，保留入口和装卸面，不依赖其他模板补墙。
- **边界**：铁栅与塌方封住 Z=21 后段；本体没有可继续探索或矿车通行的出口。

已完成当前 NBT 与作者记录双哈希的离线验收。未启动 Minecraft；原版设备与空间不代表机器、经济、交通、流体或真实生成已接入。
