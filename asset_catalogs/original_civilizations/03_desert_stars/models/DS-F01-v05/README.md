# DS-F01-v05 · 凉篷饮水茶铺

23×23×29 格，1889 个可见方块；2 个房间或作业区、6 个使用与交通标记。临街青白长篷作等候区，室内前部储水服务、后部两组茶座，风塔与炉灶分开。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_hospitality.py](../../../../../tools/structure_studio/studio/oasis_hospitality.py)，`shop(5)`。在工具目录执行 `python -m studio.build DS-F01-v05`。

填充结构；三口储水盆、茶饮柜台、两组茶桌长凳、烧水炉和器具架清晰，主门不被柜台挡住。空间标记包括：饮水与茶座厅、临街凉篷。

- **选址**：有实际居民、旅人和相应行业供需的绿洲生活街区
- **地块**：前篷等候与取水，后厅分开服务柜台、两组茶座及烧水备料，依赖可靠的生活供水。
- **高程**：主入口脚底 Y=2；两层版上层脚底 Y=9
- **落地**：结构连同内院及铺面完整落地；外部交通、供水与库存另行接入，未写入的凹角保持原环境
- **供水**：仅用于能稳定取得生活用水的位置；锅盆为储水与服务空间，不会凭模板产生水源或经济服务

已完成当前 NBT 外观、内饰、作者标记与已标站位的离线验收。住宿、交易、服务、存货及生产用途由空间和作者标记表达，尚未接入对应国度 Mod 玩法。未启动 MC，未接入国度 Mod 运行时目录。
