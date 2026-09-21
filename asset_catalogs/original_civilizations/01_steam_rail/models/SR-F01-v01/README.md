# SR-F01-v01 · 站前面包铺与店主住宅

SR-F01 铁路街区小商铺族的第一个独立模型；不是整个结构族完成。

23×23×25 格，2170 个可见方块。一层前店、柜台、后厨与楼梯；二层起居、两间独立卧室；侧面后勤小院。砖石下层、木骨浅墙上层、深色陡坡屋顶与黄白遮阳棚。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观对照](previews/exterior-sheet.png) · [内部对照](previews/interior-sheet.png) · [工作台](previews/workbench.png)
- 可重建源码：[samples.py](../../../../../tools/structure_studio/studio/samples.py)，函数 `bakery()`。在工具目录执行 `python -m studio.samples` 重建样板与兼容性夹具。

适用于临街平地，主体石基地坪 Y=1、门口脚底 Y=2；正面朝 -Z，入口通过台阶接到街面。需要保留前廊和右侧后勤出入口，不声称自动适配陡坡、水岸或地下。

原版蛋糕表达烘焙售卖，烟熏炉、桶、炼药锅表达厨房与储物用途；没有附加自定义生产机制。截图和标记均来自最终 NBT，已检查外观、内部空间及标记。离线通路支持入口到柜台、烘炉、卧室使用点及上下楼；实际世界生成和游戏行为仍需后续接入检查。
