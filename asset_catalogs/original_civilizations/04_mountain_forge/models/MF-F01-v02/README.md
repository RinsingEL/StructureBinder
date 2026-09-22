# MF-F01-v02 · 转角矿具修理铺

25×25×23 格，2218 个可见方块。双街门矿具铺可分收件与取件。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [楼层](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/mountain_trade.py)；公共注册接入后可按完整 ID 重建；独立构建入口为 `python runtime/mountain_build.py MF-F01-v02`。

验损拆检长台、修理台、铁砧和磨刃工位成套，备件箱、已修成品架及收件柜台分边，工具搬运空区完整。空间标记：双门修理铺。角色：`planning_role.fill`。

- **选址**：服务工人与居民日常生活；依照门面、转角、坡差或院落要求落位，保持完整独立模板。
- **高程**：基础上边界及室内脚底 Y=2；入口街面需同高程。
- **保留空间**：完整独立建筑及院落，保留入口和装卸面，不依赖其他模板补墙。

已完成当前 NBT 与作者记录双哈希的离线验收。未启动 Minecraft；原版设备与空间不代表机器、经济、交通、流体或真实生成已接入。
