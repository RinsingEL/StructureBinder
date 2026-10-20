# 阶段二：将功能区设计成完整组团

## 目标

把 currentDistrict 这个子区做成有生活的组团：围绕核心组织足够的配套、重复功能建筑与公共空间，并与相邻子区、全城骨架连在一起。

## 设计工作

先根据用途查询 city_d4_materials，比较尺寸、作者用途与风格。按需要使用公共空间目录、绿化池和布局案例。考虑本区的活动、服务对象与空间层次所需的实际体量；大核心落位之后，仍要设计让人停留和生活的配套（自习、休憩、餐饮、仓储、庭院或小广场）。居住与公共子区宁可宽裕。

建筑选定后，在 districtDesign.population 填 purpose、residents、jobs 与 housingGroupIds（见手册“工作、居住、公共与人口”）。工作子区填 jobs 和服务对象、residents 为 0；居住子区填 residents 与 housingGroupIds；公共子区在 purpose 写明容量与服务对象。数字是估算，用来核对，不用来压缩本区。jobs 逐项填写 profession、count、functions，餐饮值班岗位设置 servesMeals。根据上下文 templateFacilityCapacities 的床位和工位决定建筑数量，未知容量应说明待核实，不能当成可用容量。学生和普通居民可无固定工作点。住处靠近工作地，公共区放在通勤路上。Realmfolk 会承接预算并审查配额、床位、岗位与通勤。

把本区挂到全城骨架上：本区用一个根组合加嵌套组织成整体，紧邻相关子区、主轴或广场布置，向总览为它指定的用地展开；不要为本区另找远离骨架的独立锚点。用完整阵列与嵌套表达院落、街坊或轴线：围合公共空间的建筑用 COURTYARD 并开启 faceInward；同类重复建筑放进同一个阵列；按本区在全城中的层级选择 gapBlocks 与密度（核心疏朗，生活与服务紧密，农林外围成片）。建筑、景观和台地安排应有地形依据，服务于本区活动及全城联系。

## 本阶段操作与规则

用 city_d4_district 提交完整 districtDesign 与 designAnswers。core:{groupId,structureRef} 明确本区唯一核心；核心及必需配套列入 requiredStructureRefs，可选填充放入 fillPools。本区核心与全城 CORE priority 是两个概念。根组/根组合使用明确 anchorBlock，嵌套成员由父布局组织。

编译后实际读取局部及全城预览，区分计划内容、保留内容、跳过项与适配交接。初次有效结果自动推进下一区；保存了初版之后，仍可以修订。

正常生成中的必要修订，或用户明确要求的测试/修订，可修改已保存区。修订已有区时，使用 targetDistrictId、当前 baseDraftHash、assessment 以及新的完整 districtDesign 和 designAnswers。可重新选材、修改阵列与本区景观；其他区设计和布局保留。每次变化后再编译并查看新图。格式错误与失败预算遵循当前反馈。

## 阶段完成依据

初版已形成有活动、有配套、挂在骨架上的子区，实际保留内容和缺口有可判断的预览。把需要全城协调的问题带入复核；判断时先看本区与相邻子区、广场、主轴的关系。
