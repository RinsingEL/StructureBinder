# NS-02-v01 · 背风阶院冰原补给站

37×26×35 格。三翼背风阶院中，生活和装备置于低台，独立物资库抬高三格；三宽三级台阶与梯口相接，外沿护边未再穿库。

已查看完整26图：三人床位、热食共桌和干衣储备齐全；装备台与雪橇备件独立，后库有发放登记、粮食、燃料、煤料与待装橇物资，院内保留转向。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与导航](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去顶](previews/roof-off.png) · [首层平面](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/northern_seafarers.py)，独立重建：在 tools/structure_studio 执行 `python runtime/northern_build.py NS-02-v01`。

作者角色：`planning_role.structure`。空间：三人避寒与热食长屋、低檐装备检修屋、后高台冬季物资库。

- **选址**：稳定的寒地海湾背风岸台；避开潮涌、雪崩、海冰推挤和行洪通道。
- **高程**：前院与生活脚底 Y=3，后库脚底 Y=6；三宽三级实体台阶相连。坡地需匹配前低后高并保留转向院。
- **保留空间**：完整独立模板，连同檐口、通路和本体配套保留；不可贴邻堵住工作面。
- **风雪**：厚木围护、短风斗和陡坡屋面表达避风防雪；不模拟风雪、温度或雪荷载。
- **选用**：仅选用于有实际寒地路线的背风停歇点；不作为普通住宅重复投放。

当前版本 26 张完整截图已实际查看并归档，NBT/作者双哈希匹配。未启动 Minecraft；不代表运行时生成、交通、机器、流体或温度已接入。

在 `tools/structure_studio` 运行 `python -m studio.build NS-02-v01` 可从源码重建。
