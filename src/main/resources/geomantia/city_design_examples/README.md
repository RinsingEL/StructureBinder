# 北湾按需设计案例包

catalog.json 是案例数据；id/title/when 构成常驻短目录，process/result/source/limits 只在选中该案例时返回。每例两张 PNG，顺序 before、after。Java 只读取目录、发图并记录已发送状态，不写具体城市构图。

来源：dev_docs/systems/city/active/20260914_北湾新城设计，对应13→14、20→21、26→28轮。图片为原始预览副本，不重绘、不冒充实机截图。案例为设计阶段过程；保留原有路网和落地限制说明。

调用：city_submit_d4_blueprint，沿用当前contextId及运行身份，单独提交designExample:{caseId}；不提交cityBlueprint/designReview。读取案例不推进设计阶段、不编译、不消费设计失败预算。一次仅一例、两图；同context重复默认只给已发送提示，需要重看时加reloadImages:true。

资源当前随JAR打包；编辑本目录并重新打包即可更新案例，无需改Java枚举。不是已经热加载的PCL外置配置。未自动删除模型历史中的图片，也不向压缩后的会话自动重发。
