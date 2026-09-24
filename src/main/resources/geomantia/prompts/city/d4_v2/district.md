只为 currentDistrict 做一次初版，用 city_d4_district 提交完整阵列、嵌套和景观。按城市规模与功能大胆设计，foundationGroupIds 只列需要台地的建筑组。输入格式、参数和引用错误按具体反馈修正；地形与碰撞由程序逐栋筛选，只要本区有有效落位就自动进入下一区，禁止为落位率、缺失建筑或美化重试。只有 initialDistrictEmpty=true（本功能区全部建筑和景观为空）才允许重做本区。程序故障由宿主处理。无需逐区评价或完成调用。

景观归属于功能区，建筑只可作为位置参考；建筑未落位、后续填充或被挤占，不影响已冻结景观的存在。新设计 owner 只填 groupId，优先用 growth 明确景观自己的起点、范围和地形偏好。不得把景观当作远处随意撒下的斑块：在功能区 intent 中说明服务对象、选址理由和可达方式。农田/牧场考虑生产通道及邻近聚落，林场考虑作业入口和运输关系，公共花园融入公共建筑与步行空间。无需每块地强制造路；确需道路时表达真实目的地之间的 CONNECTION，不能声称没有设计的道路已经存在。

选材先看 planningRoleTerms 与实际 rawSize：核心明确选入阵列，fill 候选用于可重复配套，小店铺与摊位可从小型配套池选择。核心不必最大，COMPACT 可全用小模板。普通村庄默认直接地形兼容落地；foundationGroupIds 仅用于确有需要的共同台地，公共地表与绿化不需要为每栋建筑垫台。小型独立绿化由程序处理公共间隙，完整花园或喷泉庭院由 AI 作为结构设计。

建筑分层选材：先读 Context.materialCatalog，或用 city_d4_materials 的 materialSelections（groupId 为当前功能区）查询。filters.roles 可选 core/fill/structure/self_contained/unknown；functionIds 从 facets.functions 的 id 选择，parent 表示父子关系；functionMode 默认 all，也可 any。styles 内任一风格匹配，rawFunctionTerms 要全部匹配，各维度始终取交集。可以先筛核心看功能与风格数量，再按用途、子功能和城市设定缩小范围，也可以一次合并条件；风格不限定种族。facets 统计完整匹配集，每个结构在同一类只数一次，不受候选分页影响。limit=0 只取统计，默认20、最大100，用 nextOffset 翻页。分类只把细用途归入父类，不能由宽泛用途猜子功能；未归类用途保留原始标签可查。结果为空时放宽明确条件或换原始标签，不假定建筑具备未标注能力。确认候选时另行提交 structureRefs/fillPoolRefs，不带 filters/limit/offset；查询本身不改变已确认选材。实际尺寸、作者 terrainModes 与落地校验仍然有效，推荐情境不构成选址硬条件。
