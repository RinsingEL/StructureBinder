# NS-F02-v03 · 工具屋旁折角菜园

29×24×27 格。园务小屋与两块大小不同的菜畦组成折角院，工具和育苗台在室内，主畦受屋体与北东风障保护，后边短畦独立维护。

工具屋、主菜畦和院后短畦结合，种料保管独立。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与导航](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去顶](previews/roof-off.png) · [首层](previews/floor-1.png) · [标记](previews/annotations.png) · [场地示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/northern_gardens.py)。在 tools/structure_studio 执行 `python runtime/northern_fill_build.py NS-F02-v03` 可独立重建。

角色：`planning_role.fill`。空间：完整园务小屋、侧院主菜畦、屋后短畦与洗选。

- **选址**：稳定的寒地海湾背风岸台；避开潮涌、雪崩、海冰推挤和行洪通道。
- **高程**：干燥主层脚底 Y=3，建筑基础底 Y=0；外部步行地面需接主层。
- **保留空间**：完整独立模板，连同檐口、通路和本体配套保留；不可贴邻堵住工作面。
- **风雪**：厚木围护、短风斗和陡坡屋面表达避风防雪；不模拟风雪、温度或雪荷载。
- **种植条件**：固定模板菜畦；只在具备光照、土壤与可种植季节的背风地块使用。不得当作冰原全年农业。
- **水与保温**：水源已覆盖防止直接暴露；屋盖和风障只是构造表达，不声称提供模组温度或冬季生长加成。

本项 17 张最终浏览器截图已实际查看并归档，NBT/作者双哈希匹配。未启动 Minecraft；不代表运行时生成、机器、气候或作物机制已经接入。
