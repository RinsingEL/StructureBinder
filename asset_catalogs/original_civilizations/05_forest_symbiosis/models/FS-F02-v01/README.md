# FS-F02-v01 · 双长畦 · 林缘农田与药圃

29×17×41 格，3097 个可见方块；角色 `planning_role.fill`。

[结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路](validation.json) · [图审记录](review.json)

[外观](previews/front.png) · [内部剖切](previews/roof-off.png) · [使用层](previews/floor-1.png) · [标记](previews/annotations.png) · [环境](previews/site-context.png)

狭长林隙中的两条不同长度田畦，中间保留贯通林路
完整封边的独立田块，可直接读出田埂、种植、水眼与工作区；不代表沿自然地形自动延展的大田。

空间：林隙作物畦：实体耕地、水眼、边缘木质作业路；需实际足够日照；林隙作物畦：实体耕地、水眼、边缘木质作业路；需实际足够日照。

- **选址**：狭长林隙中的两条不同长度田畦，中间保留贯通林路
- **落地**：只整理建筑本体与步道；保留未写入的林地。附树版本包含独立柱脚和树根占地，不能套入任意现存树体。
- **高程**：主入口脚底 Y=3；高位层和坡地接驳另见楼层与使用点
- **运行边界**：种植、蜂具、藤果、兽栏与遗留物均为原版静态空间表达；实际生产、动物和生态行为须另行接入。

[源码](../../../../../tools/structure_studio/studio/forest_gardens.py)；在工具目录执行 `python -m studio.build FS-F02-v01`。

本项14张归档截图与当前NBT/作者JSON一致。农业/棚架为静态空间；生产、生态和国度运行时导入另行验证。
