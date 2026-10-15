# 第九轮：新学院居民接入失败，尚未发布人口

复用已通过 D7 的 Realmfolk_Campus03_v2，原城市由真实结构发现，设施来源 export:sha256。call-0056～0066：主宿舍 residence_required_001 有 16 张床 present/available=true；四栋 MG27 小宿舍各六床 available=false，须核对真实方块与导出变换。

正式 realmfolk_quick_city 请求按 tools/list schema 提供 choices.baseRevision=1、住房用途 [学生住宿,教职住宿,旅客住宿] 与学院定位。call-0063 与0066 均返回 QUICK_CITY: 从实际建筑用途选择住房；本城没有匹配的文明默认时请填写 housingTerms。实际请求已保存，参数存在但工具未接受，工具层没有进一步纠正方向。没有发布人口，也没有改社会存档。

保存退出后进入诊断，D7 成功存档保留。

补充定位：quick_city 属于本次调用参数错误，不是接口缺陷。housingTerms 混入了尚未发现的“教职住宿、旅客住宿”，available.containsAll 正确拒绝；下一轮只选真实的“学生住宿”。四栋小宿舍的床 present=true，available=false 是通路不可达，并非床位坐标或方块 tag 错误。MG27 生成器先开门后画窄窗，窗框覆盖门洞；已调整为门洞最后成形，并增加室内通路校验点及可阻断的导航回归。冻结 D4 素材包不直接改 hash；旧副本的小宿舍还存在门外高差，本轮入住只使用实际可达床位，保留缺口证据。
