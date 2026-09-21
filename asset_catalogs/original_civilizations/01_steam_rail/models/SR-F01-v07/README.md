# SR-F01-v07 · 玻璃花草铺与换盆间

29×19×24 格，1777 个可见方块。透明人字顶花房、砖砌换盆附房和室外花畦；种植展示与工作通路分离。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路检查](validation.json) · [离线验收](review.json)
- [完整对照](previews/review-sheet.png) · [外观](previews/front.png) · [室内切层](previews/floor-1.png) · [适用地形示意](previews/site-context.png)
- 可重建源码：[shops.py](../../../../../tools/structure_studio/studio/shops.py)，`shop(7)`。工具目录执行 `python -m studio.build SR-F01-v07`。

- **选址**：铁路聚落步行商业街；需要对应行业的客源和补给
- **地块**：较浅宽地块；温室需要采光，街前保留种植展示角
- **地坪**：石基地坪 Y=1，室内脚底 Y=2；北侧台阶连接街面
- **保留空间**：保留门前步行与后勤开口；模板外和未写入的空格不参与清地

已完成离线外观、内饰、标记与通路验收；当前哈希与证据绑定。原版家具和设备表达店铺行业，尚未接入国度 Mod 运行时目录、商人交易或整合包生产配方。
