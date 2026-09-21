# WT-01-v01 · 蓝帆水门 · 客运码头

45×23×43 格，5592 个可见方块。浅色拱廊、蓝绿檐口与橙陶屋面围合面向水面的候船庭院。 两条桩架栈桥之间留出水道，蓝白帆布廊遮雨；岸侧石基与水侧木桩有明确分界。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [剖面](previews/roof-off.png) · [标记](previews/annotations.png) · [适用地形示意](previews/site-context.png)
- 可重建源码：[transport.py](../../../../../tools/structure_studio/studio/transport.py)，`passenger_quay`。在工具目录执行 `python -m studio.build WT-01-v01`。

- **选址**：平缓河岸或避风内港，岸线沿 X，水域位于 +Z；仅用于足够深、宽的可停靠水面
- **高程**：陆侧与木码头脚底 Y=4；参考水面 Y=3；桩脚到 Y=0，需要浅岸河床继续承接
- **功能**：陆侧售票候船，侧翼站务与行李；中央登船廊通往两条栈桥；右岸小吊机处理行包
- **支撑**：石砌岸台只到 Z=20；水侧保持桩架、横梁和码头开口，不填平河道
- **保留空间**：两条栈桥之间及 +Z 端为航行与靠泊空间，不能塞入沿街填充池

已完成离线外观、内部或作业空间、作者标记与碰撞通路初筛。验收对应当前 NBT 与作者文件哈希；实际世界生成、游戏行为和国度 Mod 运行时目录接入需另行验证。
