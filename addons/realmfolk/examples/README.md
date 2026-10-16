# 设施规则示例

把create_facility_rules.json作为config/realmfolk/facility_rules.json使用；已有规则时将facilities条目合并到原数组，保留其他适配。需安装Create，运行时按真实#create:seats标签匹配，不硬编码颜色。

样例使用marker_seat固定行为适配，seatHeight=0.5表示座面相对方块底部的高度。它承载居民坐姿，不调用Create内部交互。2026-10-16真实客户端已验证蓝/紫座椅自动发现及4个实际坐姿。

如供离线Studio导出使用，通过--living-rules传入文件，并补目标整合包的tags成员快照；此示例本身只配置运行时标签，不能证明离线NBT具有该标签。规则改动重启后生效；已加载建筑按预算轮转重新发现设施。
