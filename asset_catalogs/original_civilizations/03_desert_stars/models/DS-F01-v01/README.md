# DS-F01-v01 · 街口薄饼干粮铺

17×23×25 格，1129 个可见方块；1 个房间或作业区、4 个使用与交通标记。街口小矩形铺面配短篷、黄窗和小圆顶，前售后作，烟道沿后角贯穿屋顶。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_hospitality.py](../../../../../tools/structure_studio/studio/oasis_hospitality.py)，`shop(1)`。在工具目录执行 `python -m studio.build DS-F01-v01`。

填充结构；食品柜台、备餐工作台、烘烤炉、储物桶与清洗水盆均有布置，柜台与烤炉站位通畅。空间标记包括：干粮与烘烤间。

- **选址**：有实际居民、旅人和相应行业供需的绿洲生活街区
- **地块**：小矩形店铺前售后烤，备料与烤炉相邻，短遮阳檐面对聚落街口。
- **高程**：主入口脚底 Y=2；两层版上层脚底 Y=9
- **落地**：结构连同内院及铺面完整落地；外部交通、供水与库存另行接入，未写入的凹角保持原环境

已完成当前 NBT 外观、内饰、作者标记与已标站位的离线验收。住宿、交易、服务、存货及生产用途由空间和作者标记表达，尚未接入对应国度 Mod 玩法。未启动 MC，未接入国度 Mod 运行时目录。
