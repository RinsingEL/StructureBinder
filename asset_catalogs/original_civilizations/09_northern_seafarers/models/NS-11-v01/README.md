# NS-11-v01 · 浅湾残舟回收营

39×24×38 格。两间陆侧临时屋、桩基分拣甲板与指向浅湾残舟的侧桥构成回收营，残舟保留断舷和舟肋；后缘护边及水中承桩连续。

已查看完整25图：三人轮休和炊事、回收登记保管、小件清理拼接、淡水冲洗、木板晾干、金属分类与手吊都有对应作业面；干燥步行路线可达全部工位。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与导航](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去顶](previews/roof-off.png) · [首层平面](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/northern_seafarers.py)，独立重建：在 tools/structure_studio 执行 `python runtime/northern_build.py NS-11-v01`。

作者角色：`planning_role.structure`。空间：三人轮班生活屋、登记与干燥物保管屋、桩基分拣起吊甲板。

- **选址**：稳定的寒地海湾背风岸台；避开潮涌、雪崩、海冰推挤和行洪通道。
- **高程**：干燥主层脚底 Y=3，建筑基础底 Y=0；外部步行地面需接主层。
- **保留空间**：完整独立模板，连同檐口、通路和本体配套保留；不可贴邻堵住工作面。
- **风雪**：厚木围护、短风斗和陡坡屋面表达避风防雪；不模拟风雪、温度或雪荷载。
- **岸线**：海在 +Z，示意岸线 Z=24、水面 Y=2；需校对潮差与实际船舶吃水。
- **选用**：明确选于有船骸的避风浅湾；自带残舟片段及栈桥，不能与另一船骸重复叠放。
- **水深**：本体浅水 Y=1～2，岸台甲板脚底 Y=3；水中船骸不可当通路，全部工作点由干燥岸台及桩桥到达。

当前版本 25 张完整截图已实际查看并归档，NBT/作者双哈希匹配。未启动 Minecraft；不代表运行时生成、交通、机器、流体或温度已接入。

在 `tools/structure_studio` 运行 `python -m studio.build NS-11-v01` 可从源码重建。
