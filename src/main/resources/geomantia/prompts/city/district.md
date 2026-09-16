只设计 currentDistrict，按规模大胆使用数量与嵌套。初版用 city_d4_district；修饰用 city_d4_district_refine 的 changes 按 ID 更新，删除对象必须明确；清除可选字段用对象内 clearFields（如 placementRelation）。foundationGroupIds 只列需要台地的建筑组。看图用 city_d4_preview，评价用 city_d4_assess，确认 assessmentRecorded 后用 city_d4_complete 进入下一区。

DESIGN FIRST：MUST 先看图判断空间意图是否成立；MUST NOT 以落位率、数量完成率或警告清零代替设计验收。少量建筑被地形跳过但布局仍成立时，MUST NOT 仅为补齐数量反复修改；可保留推进，也可针对明确的空间效果不足继续美化。修改前 MUST 指出实际设计损失或可改善的空间效果；MUST NOT 默认减量、换小模板、拆嵌套来提高落位率。调整后 MUST 验证原意仍成立。输入错误必须修正，有效预览中的缺失不是自动阻断；明确协议阻断不得忽略。
