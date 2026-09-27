# NS-08-v04 · 分屋卸货冬储场

35×28×31 格。大宗粮库、工具保养屋和开放燃料棚围住卸货院，三种存储对湿度和搬运的不同要求以真实分屋和工作面表达。

分屋物资库由院落连接，装卸与储备空间相互独立。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与导航](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去顶](previews/roof-off.png) · [首层](previews/floor-1.png) · [标记](previews/annotations.png) · [场地示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/northern_crafts.py)。在 tools/structure_studio 执行 `python runtime/northern_fill_build.py NS-08-v04` 可独立重建。

角色：`planning_role.fill`。空间：长条食品批次库、独立装备小库、露侧有顶燃料场、中间卸货与配给院。

- **选址**：稳定的寒地海湾背风岸台；避开潮涌、雪崩、海冰推挤和行洪通道。
- **高程**：干燥主层脚底 Y=3，建筑基础底 Y=0；外部步行地面需接主层。
- **保留空间**：完整独立模板，连同檐口、通路和本体配套保留；不可贴邻堵住工作面。
- **风雪**：厚木围护、短风斗和陡坡屋面表达避风防雪；不模拟风雪、温度或雪荷载。

本项 21 张最终浏览器截图已实际查看并归档，NBT/作者双哈希匹配。未启动 Minecraft；不代表运行时生成、机器、气候或作物机制已经接入。
