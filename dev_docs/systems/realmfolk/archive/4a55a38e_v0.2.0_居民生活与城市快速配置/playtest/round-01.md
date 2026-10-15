# 第一轮真实游玩：快速配置报告不一致

目标：独立真实客户端创建生活城，通过正式 MCP 发布 0.3 居民与商店。

实际请求与返回：call-0003 创建 Realmfolk_Living03；call-0008 执行 realmfolk life_demo；call-0010 /life 确认 16 张床、12 个工作设施、8 个座位均 available=true；call-0012 realmfolk_quick_city 提交四个群体及两位关键人物，cityReview 返回规划住房 8、人口上限 6；call-0013 realmfolk_preview 返回实际住房 16、目标及新居民 13、无缺口。

调用修正：准备阶段改用 PowerShell 7 运行已有启动准备脚本，解决 PowerShell 5 缺失压缩类型；一次 preview 请求末尾多写右括号，在请求发出前解析失败，改正后正式调用成功。不是产品功能故障。

结果：确认全城审查的旧容量估计与新的物理床位人口预览冲突。结束本轮 Agent Loop，正常退出保存；转入已授权诊断修复。存档场景、设施与草案可复用，未发布人口。
