# 第二轮真实游玩：报告修复通过，访客位置异常

call-0018 quick_city 已返回 requires_physical_facilities，错误人数上限已移除；call-0019 实际 preview 为 16 床、13 人、无缺口；call-0020 正式 publish 创建 13 人。

call-0021 /state 与 call-0023 /life：常住居民出门前往工坊、商店，守卫沿街巡逻；另有两名访客，但其 home/work 为 y=-60（life_demo:guard、life_demo:home0），实际生活台面站立高度 y=-57，访客活动到台地下。call-0022 截图记录实际城镇。

测试端解析修正：/life 返回未包含预期 towns 字段，读取其实际 buildings/citizens 字段核对；这是诊断响应字段缺失，未据此推断人口失败。

本轮结束：访客公共场所/高度选择出现真实功能偏离，正常退出保存，进入源码诊断修复。继续复用已发布城市及居民，后续检查修复是否保留 UUID 与关系。
