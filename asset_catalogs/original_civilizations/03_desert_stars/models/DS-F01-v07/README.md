# DS-F01-v07 · 楼上抄图书铺

19×31×29 格，2257 个可见方块；3 个房间或作业区、5 个使用与交通标记。两层书铺用七级双宽内梯联系，上层前部抄图、后部隔屏值守，楼梯口设连续护栏。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_hospitality.py](../../../../../tools/structure_studio/studio/oasis_hospitality.py)，`shop(7)`。在工具目录执行 `python -m studio.build DS-F01-v07`。

填充结构；下层账台、阅读席和书架，上层制图台、卷册架、床位、衣柜与饮水形成工作生活组合。空间标记包括：楼下书铺、楼上抄图间、后部值守床间。

- **选址**：有实际居民、旅人和相应行业供需的绿洲生活街区
- **地块**：下层售书与阅读，上层抄图工作间兼值守睡眠，内部楼梯联系紧凑两层。
- **高程**：主入口脚底 Y=2；两层版上层脚底 Y=9
- **落地**：结构连同内院及铺面完整落地；外部交通、供水与库存另行接入，未写入的凹角保持原环境

已完成当前 NBT 外观、内饰、作者标记与已标站位的离线验收。住宿、交易、服务、存货及生产用途由空间和作者标记表达，尚未接入对应国度 Mod 玩法。未启动 MC，未接入国度 Mod 运行时目录。
