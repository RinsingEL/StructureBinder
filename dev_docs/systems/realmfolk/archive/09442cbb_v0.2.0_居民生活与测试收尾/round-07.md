# 第七轮：宿主正式接续

独立设计 MCP 的 quick_city 发布成功后，宿主仍等待 FLASH 扩展，不能据此宣布城市主流程完成。测试端使用现有 localhost:5011 的公开 /planning/* 接口，以 FLASH 角色完成租约、next 与 action；遵循同一主 MCP 协议，没有改写宿主状态文件。

planning-0003：realmfolk_quick_plan 生成正式作用域草案，38项用途、51栋建筑；审查 errors=[]。planning-0004：preview 确认12栋住宅、82床、目标69、已有32，岗位与配额限制尚缺37，created=0，保留现有人物。planning-0005：携带 previewToken/reviewToken 发布，revision=3、created=0、refreshed=0，hostDecisionCommitted=true、taskFinished=true。

planning-0007：宿主转入 T4 / realm_t4_patch_planning_create 等待对应角色。这证明本城居民扩展已完成，不代表整个国度已规划完成。0008释放租约；0214保存退出。0209核对35个身份的姓名、职业、床位、工位、住址与重载前快照完全相同；见 verification.json。

调用差错：测试时首次 next 遗漏 ownerId（0006），补齐后通过；未登记为产品故障。额外 Node 后台 HTTP 辅助进程启动被自动审批拒绝（只返回 blocked by policy，未提供进一步原因）；未重试同一服务启动，改用已存在的公开规划 API。SDK 导入缺少 zod/v4、受限 node:process 也仅属测试工具准备问题。

现场已恢复白天、正常昼夜与天气，移除夜视并保存。未继续 T4 或发起外部模型调用。
