# FS-10-v01 · 年轮学舍 · 自然学堂

45×27×36 格，6277 个可见方块。角色：`planning_role.key`。

[结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路](validation.json) · [实际图审](review.json)

[外观](previews/front.png) · [内部剖切](previews/roof-off.png) · [楼层](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)


空间用途：林下教室：讲台、双列桌椅与阅览书架；标本与记录室：标本、记录和观察出入口；露天观察台：面向林缘的观察与示范植物空间。

- **选址**：面向林隙的观察边缘；观察台应朝向真实林地而非其它建筑背墙
- **落地**：只整理建筑本体与步道；保留未写入的林地。附树版本包含独立柱脚和树根占地，不能套入任意现存树体。
- **高程**：主入口脚底 Y=3；高位层和坡地接驳另见楼层与使用点
- **运行边界**：种植、蜂具、藤果、兽栏与遗留物均为原版静态空间表达；实际生产、动物和生态行为须另行接入。

[建模源码](../../../../../tools/structure_studio/studio/forest_symbiosis.py)；在工具目录执行 `python -m studio.build FS-10-v01` 重建。

当前 NBT 与作者记录双哈希一致，16 张最终截图经作者实际查看，主审另核对外观、剖面与环境。原版方块、标记与离线步行初筛通过。

作者标记保存于配套 JSON。国度 Mod 运行时导入、真实选址和设备玩法需另行验证；本次未启动 Minecraft。
