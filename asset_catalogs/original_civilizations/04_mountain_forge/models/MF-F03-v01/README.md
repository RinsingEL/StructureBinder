# MF-F03-v01 · 贴山矿具靠壁棚

23×16×19 格，1183 个可见方块。贴山实心背墙棚的前部开放，棚盖直接接柱顶。

- [结构 NBT](structure.nbt) · [作者标记](author.json) · [数据与通路](validation.json) · [实际图审](review.json)
- [外观](previews/front.png) · [去屋顶](previews/roof-off.png) · [楼层](previews/floor-1.png) · [标记](previews/annotations.png) · [地形示意](previews/site-context.png)
- [建模源码](../../../../../tools/structure_studio/studio/mountain_outside.py)；公共注册接入后可按完整 ID 重建；独立构建入口为 `python runtime/mountain_build.py MF-F03-v01`。

货箱、背墙矿具架与右侧修理台分角，前部和中央留搬运面；背墙需真实山脚依托，示意未替代地形落位。空间标记：背墙矿具棚。角色：`planning_role.fill`。

- **选址**：仅用于稳定山脚石坎，+Z 背墙需有真实山体或挡土依托；不得让背墙悬空。
- **高程**：干地与基础顶脚底 Y=2，四角支柱落在完整石基上。
- **用途**：少量材料周转与手工修缮造型；不代表自动仓储、搬运或矿业系统接入。

已完成当前 NBT 与作者记录双哈希的离线验收。未启动 Minecraft；原版设备与空间不代表机器、经济、交通、流体或真实生成已接入。
