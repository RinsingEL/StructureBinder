# DS-F01-v08 · 分台果蔬小铺

32×23×27 格，2408 个可见方块；3 个房间或作业区、4 个使用与交通标记。低台绿色凉棚售卖，高台仓房与值守间各自开门，中间三格宽短阶接一格坡差。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [平面](previews/floor-1.png) · [标记](previews/annotations.png) · [环境示意](previews/site-context.png)
- 可重建源码：[oasis_hospitality.py](../../../../../tools/structure_studio/studio/oasis_hospitality.py)，`shop(8)`。在工具目录执行 `python -m studio.build DS-F01-v08`。

填充结构；瓜果粮食柜台、周转货筐、值守床位衣柜和饮水完整；修正切片后全平面可同时看到摊面和高台内饰。空间标记包括：高台值守间、高台存货间、下台售卖凉棚。

- **选址**：有实际居民、旅人和相应行业供需的绿洲生活街区
- **地块**：低台遮棚售卖，高台仓房与值守间分开，短阶接一格地坪差，适合绿洲坡缘生活街。
- **高程**：主入口脚底 Y=2；后高台脚底 Y=3
- **落地**：结构连同内院及铺面完整落地；外部交通、供水与库存另行接入，未写入的凹角保持原环境

已完成当前 NBT 外观、内饰、作者标记与已标站位的离线验收。住宿、交易、服务、存货及生产用途由空间和作者标记表达，尚未接入对应国度 Mod 玩法。未启动 MC，未接入国度 Mod 运行时目录。
