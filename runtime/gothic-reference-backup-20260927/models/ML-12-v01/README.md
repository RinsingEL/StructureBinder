# ML-12-v01 · 封闭墓区旧门与值守遗存

39×32×37 格。独立尖拱封闭门、低矮值守屋与前纪念庭组成非对称历史入口，完整用途止于封闭线。

实际查看18图：旧登记工具、轮休床柜饮水保留生活痕迹；告示、旧路长凳和铁栅前止步位清楚，后碎石不标为可达房间。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与导航](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去顶](previews/roof-off.png) · [首层平面](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/memorial_lanterns.py)，独立重建：在 tools/structure_studio 执行 `python runtime/memorial_build.py ML-12-v01`。

作者角色：`planning_role.structure`。空间：旧值守登记室、值守轮休遗存、可达旧径与纪念小庭。

- **选址**：排水良好的稳定台地，连接仍有常住居民的聚落道路；纪念设施不替代活人的生活供给。
- **高程**：基础底 Y=0，公共地面 Y=2，步行脚底 Y=3；道路接入口同高。
- **保留空间**：独立完整模板，保留外扶壁、彩窗采光面及庭院；不可用相邻建筑遮挡。
- **构造**：尖拱、外扶壁与内肋共同表达高挑空间；不模拟结构受力、照度或天气。
- **封闭边界**：Z=25 铁栅及后方碎石明确封闭；可达范围止于 Z=24。后墓区未制作，不声明后续通路存在或可进入。

当前版本 18 张完整截图已实际查看并归档，NBT/作者双哈希匹配。未启动 Minecraft；不代表运行时生成、交通、机器、流体或温度已接入。
