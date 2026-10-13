# ML-02-v01 · 送葬车站与双湾候车院

47×32×45 格。长厅分候行、仪式准备与站务三段，双车湾和东侧车道共同表达地面牵引车接驳。

实际查看23图：候车席、行李、礼布柜和准备台完整；调度、车夫轮休饮水与双车侧移交面可达，车棚前后落地柱与加厚坡盖覆盖整车。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与导航](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去顶](previews/roof-off.png) · [首层平面](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/memorial_lanterns.py)，独立重建：在 tools/structure_studio 执行 `python runtime/memorial_build.py ML-02-v01`。

作者角色：`planning_role.key`。空间：家属候行与行李登记、送行仪式准备厅、站务轮休室、双湾车棚与移交院。

- **选址**：排水良好的稳定台地，连接仍有常住居民的聚落道路；纪念设施不替代活人的生活供给。
- **高程**：基础底 Y=0，公共地面 Y=2，步行脚底 Y=3；道路接入口同高。
- **保留空间**：独立完整模板，保留外扶壁、彩窗采光面及庭院；不可用相邻建筑遮挡。
- **构造**：尖拱、外扶壁与内肋共同表达高挑空间；不模拟结构受力、照度或天气。
- **交通**：使用地面牵引车交通，不假定铁路。东侧 X=40～44 的5格车道需两端接通并留外部转弯场；展示车辆非可驾驶实体。

当前版本 23 张完整截图已实际查看并归档，NBT/作者双哈希匹配。未启动 Minecraft；不代表运行时生成、交通、机器、流体或温度已接入。
