# 第七轮：整实例准入的时序回归

新副本 Realmfolk_Campus03 来自原始 D4。实际流程重新编译 D4 并生成 D5/D6/LandUse；蓝图未重设计。campus-d7-start-07、status-07a～07f；最终 809/844，35 个失败（道路冻结地形冲突 21、作者景观承载 8、特征占用 4、体积占用 1、填方占用 1）。公开准入记录 600 份：540 admitted、31 support missing、29 volume occupied。

根因：等待邻区地形时延迟整个含植物的 owner，改变原有道路与结构 worldgen 时序；另提前相信待落地的平台使部分植物错误准入。修正为 base 在原 FEATURES 时机施工并保存 publicRealmPending，D7 仅执行待处理植物层。整实例以此时实际支撑和空间占用做准入，不再用尚未施工平台推定承载。新副本复验，前轮副本只留证据。

call-0043～0044 已保存退出；进入开发修复。
