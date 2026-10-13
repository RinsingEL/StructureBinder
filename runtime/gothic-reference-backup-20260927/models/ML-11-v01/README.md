# ML-11-v01 · 下沉旧陵寝与守护前厅

39×32×47 格。旧路高台经六级实阶下至陵寝，坡地与地下保留范围单列；纪念室仍有尖顶石肋和彩窗。

实际查看最终20图：补看高台上口及石阶房间，确认台阶连续、护边与底部平台完整；前仪式守护、后双棺和碑文记录可达，地形示意方向已与高前低后条件对齐。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与导航](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去顶](previews/roof-off.png) · [首层平面](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/memorial_lanterns.py)，独立重建：在 tools/structure_studio 执行 `python runtime/memorial_build.py ML-11-v01`。

作者角色：`planning_role.structure`。空间：高台与连续下沉石阶、仪式与守护前厅、下沉双棺纪念室。

- **选址**：排水良好的稳定台地，连接仍有常住居民的聚落道路；纪念设施不替代活人的生活供给。
- **高程**：基础底 Y=0，公共地面 Y=2，步行脚底 Y=3；道路接入口同高。
- **保留空间**：独立完整模板，保留外扶壁、彩窗采光面及庭院；不可用相邻建筑遮挡。
- **构造**：尖拱、外扶壁与内肋共同表达高挑空间；不模拟结构受力、照度或天气。
- **地下**：前旧路脚底 Y=9，经6级台阶下至 Y=3。地下保留范围 X=10～28、Z=20～41、Y=2～10；两侧围土不得侵入彩窗和内部通路。

当前版本 20 张完整截图已实际查看并归档，NBT/作者双哈希匹配。未启动 Minecraft；不代表运行时生成、交通、机器、流体或温度已接入。
