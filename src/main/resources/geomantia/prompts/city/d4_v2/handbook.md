# 功能区设计循环

继承第二题城市设计，结合 D3 简短确认→全城设置与主次区列表→按区查询素材→一区一核心、必需配套、可选填充→正式 Java 阵列编译→局部与全城预览→指定区域修订再编译→当前版本最终保存。

素材类别（特色/通用）、作者标签（基建/景观）、功能与风格独立；本次角色由功能区设计决定。通用素材可作核心，旧 planning_role.fill 不是填充资格门槛。

GRID 工整街坊；LINEAR 沿轴街面；COURTYARD 院落；CENTER_SYMMETRIC 一中心配成对填充，其他必需项使用配套组；COMPACT/ORGANIC_COMPACT 紧凑组团；CONTIGUOUS 可用于作者标注的连片生产素材。父阵列组织本区子阵列，同类区不强制合成巨型阵列。

每个阵列先明确空间目的，再选算法。院落围合主公共空间，网格组织重复功能建筑，线性组服务街面；把这些有独立目的的子阵列嵌套为完整功能区，不把每栋独立建筑包装成单成员 GRID。groups 与 arrayCompositions 可选 layout（仅 GRID/LINEAR/COURTYARD）：gapBlocks 4..40，columns 1..180，courtWidth/courtDepth 8..256，crossAlign START/CENTER/END，purpose 非空。默认分别为 8、2、36/28、START、public-space；坐标仍由编译器决定。显式 COURTYARD 最多四个完整成员按北西东南围绕留空排布；叶子组可用 faceInward=true，旋转受素材允许方向约束。purpose 含 garden 的留空交程序生成分层花园，其余作公共广场；喷泉、雕像等完整素材也可另作明确成员。未指定 layout 的旧阵列保留原行为。

districtDesigns 的 coreGroupId/coreStructureRef 决定本区台地高程，groupIds 配套共享该台地；核心是高程与功能归属，并非要求建筑占据几何中心。道路连接服务入口与公共空间，花园和广场保留通行路径。相邻小间隙由施工阶段沿实际道路补齐两侧台面；河流、峡谷和大跨度仍保留自然分隔，不用提高建筑数量来遮盖布局问题。

道路仅用明确 CONNECTION 服务真实目的地，不从邻接、距离或河流自动造桥。D4 检查平面布局与边界/碰撞，地形适配证据交后续施工；精确台地、支撑与跨水算法不在本轮。每轮看新版本实际预览再判断，不以建筑数量代替视觉整体性。


成功方案可用 city_d4_history 查看版本。定稿但尚未进入后半段流程时，用户要求修改可用 city_d4_reopen（绑定接受 hash）或 city_d4_restore（指定版本并绑定当前接受/草稿 hash），重新编译并看新图，再继续逐区修订与定稿。想保留后续修改机会时，定稿明确 autoAdvanceAfterD4=false；默认定稿自动推进。版本恢复不清零失败预算，不混入另一 Context，已开始施工流程不支持设计回退。
