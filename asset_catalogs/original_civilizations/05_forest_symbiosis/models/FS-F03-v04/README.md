# FS-F03-v04 · 背墙暖棚

37×22×34 格，3972 个可见方块；角色 `planning_role.fill`。

[结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路](validation.json) · [图审记录](review.json)

[外观](previews/front.png) · [内部剖切](previews/roof-off.png) · [使用层](previews/floor-1.png) · [标记](previews/annotations.png) · [环境](previews/site-context.png)

靠独立背墙的棚架与封闭工具间组合，适合院边暖向地块
叶蔓依托落地梁架；果串采用原版紫晶芽外观，仅作藤果示意。实际葡萄品种、温度、季节和生产按整合包配置。

空间：完整独立藤架：落地棚柱、纵横梁、叶蔓与采收走道；采收工具间：清洗台、分拣包装、采收剪具、空篮与果箱；从前门沿中央通道进入。

- **选址**：靠独立背墙的棚架与封闭工具间组合，适合院边暖向地块
- **落地**：只整理建筑本体与步道；保留未写入的林地。附树版本包含独立柱脚和树根占地，不能套入任意现存树体。
- **高程**：主入口脚底 Y=3；高位层和坡地接驳另见楼层与使用点
- **运行边界**：种植、蜂具、藤果、兽栏与遗留物均为原版静态空间表达；实际生产、动物和生态行为须另行接入。

[源码](../../../../../tools/structure_studio/studio/forest_gardens.py)；在工具目录执行 `python -m studio.build FS-F03-v04`。

本项17张归档截图与当前NBT/作者JSON一致。农业/棚架为静态空间；生产、生态和国度运行时导入另行验证。
