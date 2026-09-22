# FS-05-v01 · 枝议台 · 树冠议事堂

43×35×42 格，6987 个可见方块。角色：`planning_role.key`。

[结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路](validation.json) · [实际图审](review.json)

[外观](previews/front.png) · [内部剖切](previews/roof-off.png) · [楼层](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)

实际可步行的宽长阶上升八格；高位桥台连接完整室内，根部和柱脚分别可见。
树冠只是建筑适配环境，不表达活树自动生长或跨树网络。

空间用途：树冠议事厅：围桌议事、发言、档案与有遮蔽等候。

- **选址**：可容纳完整根盘的大林隙；议事层由落地木柱和树旁梁架共同支承，北侧长阶连接林路
- **落地**：只整理建筑本体与步道；保留未写入的林地。附树版本包含独立柱脚和树根占地，不能套入任意现存树体。
- **高程**：主入口脚底 Y=3；高位层和坡地接驳另见楼层与使用点
- **运行边界**：种植、蜂具、藤果、兽栏与遗留物均为原版静态空间表达；实际生产、动物和生态行为须另行接入。

[建模源码](../../../../../tools/structure_studio/studio/forest_symbiosis.py)；在工具目录执行 `python -m studio.build FS-05-v01` 重建。

当前 NBT 与作者记录双哈希一致，14 张最终截图经作者实际查看，主审另核对外观、剖面与环境。原版方块、标记与离线步行初筛通过。

作者标记保存于配套 JSON。国度 Mod 运行时导入、真实选址和设备玩法需另行验证；本次未启动 Minecraft。
