# NS-01-v01 · 双舷作业长船屋

39×28×39 格。高跨深色船屋与低矮两间侧屋形成修造院，端部木构架、侧柱与承檐连续；海向大开口对准长船滑道。

已查看完整25图：长船托架、双舷搬运检修道、捻缝铆接、船板储备与帆索各有空间；干燥木工间含刃具、拼装中台与零件架，两人值守屋含床柜、桌凳与炊洗。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与导航](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去顶](previews/roof-off.png) · [首层平面](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/northern_seafarers.py)，独立重建：在 tools/structure_studio 执行 `python runtime/northern_build.py NS-01-v01`。

作者角色：`planning_role.key`。空间：长船托架及双舷修船廊、干燥木工作坊、两人值守小屋。

- **选址**：稳定的寒地海湾背风岸台；避开潮涌、雪崩、海冰推挤和行洪通道。
- **高程**：干燥主层脚底 Y=3，建筑基础底 Y=0；外部步行地面需接主层。
- **保留空间**：完整独立模板，连同檐口、通路和本体配套保留；不可贴邻堵住工作面。
- **风雪**：厚木围护、短风斗和陡坡屋面表达避风防雪；不模拟风雪、温度或雪荷载。
- **岸线**：海在 +Z，示意岸线 Z=33、水面 Y=2；需校对潮差与实际船舶吃水。
- **船舶**：船屋后端开口 7 格宽、6 格高，滑道朝 +Z；本体展示长船不对应可驾驶实体，实际船宽和潮汐拖曳另行核对。

当前版本 25 张完整截图已实际查看并归档，NBT/作者双哈希匹配。未启动 Minecraft；不代表运行时生成、交通、机器、流体或温度已接入。

在 `tools/structure_studio` 运行 `python -m studio.build NS-01-v01` 可从源码重建。
