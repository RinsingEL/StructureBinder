# DS-F01-v06 · 露窑日用陶器铺

27×23×29 格，2095 个可见方块；3 个房间或作业区、6 个使用与交通标记。西侧制坯厅与东侧陶器陈列棚并置，露天砖窑位于棚后，窑前和窑后均可接近。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_hospitality.py](../../../../../tools/structure_studio/studio/oasis_hospitality.py)，`shop(6)`。在工具目录执行 `python -m studio.build DS-F01-v06`。

填充结构；原泥、水盆、制坯台、晾干架、陶器样品、交易台及窑炉维护点完整。空间标记包括：制坯与晾干厅、遮棚陶器陈列、独立露窑。

- **选址**：有实际居民、旅人和相应行业供需的绿洲生活街区
- **地块**：西侧制坯工作厅，东侧遮棚陈列与露天小窑，原泥、器皿、烧制和维护顺序明确。
- **高程**：主入口脚底 Y=2；两层版上层脚底 Y=9
- **落地**：结构连同内院及铺面完整落地；外部交通、供水与库存另行接入，未写入的凹角保持原环境

已完成当前 NBT 外观、内饰、作者标记与已标站位的离线验收。住宿、交易、服务、存货及生产用途由空间和作者标记表达，尚未接入对应国度 Mod 玩法。 陶器窑炉借用原版熔炉表达，未实现制陶配方、晾干、烧成或自动化。未启动 MC，未接入国度 Mod 运行时目录。
