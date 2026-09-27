# ML-10-v01 · 双翼彩窗医馆与康复庭

47×32×41 格。双翼窄厅围合康复庭院，沿墙扶壁与彩窗提供明显竖向节奏，低翼体量服务医疗尺度。

实际查看33图：登记、四床护理、清洁被服、员工轮休、候诊、诊床配药及备餐餐席都独立安排；庭院环道与休息席供正常居民使用。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与导航](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去顶](previews/roof-off.png) · [首层平面](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/memorial_lanterns.py)，独立重建：在 tools/structure_studio 执行 `python runtime/memorial_build.py ML-10-v01`。

作者角色：`planning_role.key`。空间：家属等候与入院、四床休养病区、护理轮休生活间、公众门诊候诊厅、诊疗配药室、医馆备餐间、康复纪念庭院。

- **选址**：排水良好的稳定台地，连接仍有常住居民的聚落道路；纪念设施不替代活人的生活供给。
- **高程**：基础底 Y=0，公共地面 Y=2，步行脚底 Y=3；道路接入口同高。
- **保留空间**：独立完整模板，保留外扶壁、彩窗采光面及庭院；不可用相邻建筑遮挡。
- **构造**：尖拱、外扶壁与内肋共同表达高挑空间；不模拟结构受力、照度或天气。
- **卫生**：近常住区与洁净水源，院内饮水为静态容器；污物处置和医学玩法需运行时另行接入。

当前版本 33 张完整截图已实际查看并归档，NBT/作者双哈希匹配。未启动 Minecraft；不代表运行时生成、交通、机器、流体或温度已接入。
