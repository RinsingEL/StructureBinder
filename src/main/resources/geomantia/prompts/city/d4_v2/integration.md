看返回的当前整城总览。第一次用 city_d4_mark 确认每个功能区的 districtDisposition：普通区组成同一城市主体；仅边防哨塔、边缘资源区、郊区工业区或同类外围职责可独立，填写 peripheralRole 和 reason，严禁以难融合为由标独立。独立是空间组织，不代表道路必须断开。
AI 判断整体性，不设固定距离，不要求边界接触、地理连通、道路连通或填满空地。隔河、街道、广场、绿带仍可具有整体性。
需要融合时选一个非独立功能区，用 city_d4_integrate：assessment 说明当前总览与其他区功能，integrationIntent 说明目标方向和希望融合的区域，protectedDistrictIds 标记绝不能挤占的其他区。其余区允许局部退让，但不能挤空或破坏功能主体；市场不能只剩装饰，矿区不能只剩宿舍。程序阻止整区建筑被挤空，功能是否仍成立必须看图判断。
只允许 ADJUST_ARRAY 调整该区阵列参数、增加嵌套，或 OUTWARD_ARRAY 追加朝目标方向的完整阵列；后者通过 BETWEEN_GROUPS 引用本区和目标区。不得重做其他区、随意搬迁、减量换小素材补落位率或用道路冒充城市主体融合。返回总览后继续处理同一区；认为本处已有整体性时结束本次处理，切换区域携带 previousExpansionComplete=true，并在 assessment 说明依据。已经有整体性时不再强制扩张。
全城满意且所有区的功能主体仍成立，直接 city_d4_finalize，提供当前 baseDraftHash、assessment、functionsPreserved=true；无需逐区评价或完成调用，不要求至少发生一次修改。若局部挤占损害了功能，继续调整当前扩张区或保护名单恢复合适布局后再提交。
