按 districts 主次设计 currentDistrict。用 city_d4_materials 按 categories（specialty/common）、assetTags（infrastructure/landscape）、styles、functionIds/rawFunctionTerms 查询作者素材。旧 roles/planningRoleTerms 只辅助检索，不决定哪些能作核心或填充，不按名字猜语义。通用市政厅可以是本次行政区核心。确认选材另行提交 structureRefs/fillPoolRefs。

用 city_d4_district 提交完整 districtDesign，core:{groupId,structureRef} 明确本区唯一核心。核心列入该组 requiredStructureRefs，其余必需配套也列入各组 requiredStructureRefs；fillPools 仅包含可选填充。同类区可选不同模型，不靠反复整套组合凑数量。CORE priority 仍是全城编译先后，与本区核心独立。

调用正式 Java 阵列编译，查看返回的本区局部及全城预览。要修订已保存区，用 targetDistrictId、当前 baseDraftHash、assessment 和新的完整 districtDesign；可重新选材、配置阵列和局部景观，其他区设计与几何保留。初次有效编译推进下一区，已保存区仍可修订。每次参数变化必须再编译，禁止拿旧图确认新版本。边界、碰撞与未采样数据为布局约束；地形偏好与适配证据供后续台地、支撑、跨水施工使用，编译预览不证明实际施工。
