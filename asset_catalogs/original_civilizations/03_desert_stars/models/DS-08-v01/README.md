# DS-08-v01 · 路旁双间客栈

29×24×37 格，3318 个可见方块；4 个房间或作业区、9 个使用与交通标记。前厅接待与进餐，后排单人、双人客房分别开门，侧道绕到后院，风塔与炉灶烟道分开。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_hospitality.py](../../../../../tools/structure_studio/studio/oasis_hospitality.py)，`inn(1)`。在工具目录执行 `python -m studio.build DS-08-v01`。

填充结构；接待讲台、长桌长凳、炊事与修补设备、三床、衣柜、饮水和后院歇脚座均可见。空间标记包括：登记餐饮与补给前厅、单人客房、双人客房、后院歇脚。

- **选址**：有可靠生活用水与补给条件的聚落入口或商路停驻点
- **地块**：前厅接待与进餐，后排两间客房分开，侧道通后院；适合商路旁的小补给点。
- **高程**：主入口脚底 Y=2；两层版上层脚底 Y=9
- **落地**：结构连同内院及铺面完整落地；外部交通、供水与库存另行接入，未写入的凹角保持原环境

已完成当前 NBT 外观、内饰、作者标记与已标站位的离线验收。住宿、交易、服务、存货及生产用途由空间和作者标记表达，尚未接入对应国度 Mod 玩法。未启动 MC，未接入国度 Mod 运行时目录。
