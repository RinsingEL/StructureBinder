# C8 AI 节点上下文 TODO

## 位置说明

- 本文件贴近 `current_20260402_030950` 这轮 C8 MCP 联调记录维护。
- 不回写到 `docs/workFlow/` 主流程文档。

## 目标

- 继续收口“外包给 AI 的 C8 节点决策上下文”，只保留最小必要字段，避免主上下文继续膨胀。

## 当前确认保留

- `foreman_plan`
- `active_node`
- `neighbor_summary`
- `template_pool_id`
- `candidate_template_ids`
- `failed_attempts`
- `terrain_relax_profile`

## 当前确认不额外新增

- 不单独新增 `node_brief`
- 不把模板详情整体塞进 `city_c8_data`
- 不把模板自身可放宽的地形限制混入程序硬约束字段

## 后续建议

- 程序补一层 `neighbor_summary`，不要让 AI 每次从整份 `validated_nodes` 自己硬推邻居关系。
- 模板详细信息改为按需查询接口，不并入 C8 主返回。
- 如需新增 `local_constraints`，只表达当前节点的程序硬约束，例如边界、连接、占地避让、保留区限制；不要混入模板可调地形参数。

## 一并记录的后续尾项

- `C7/C8` 仍保留了较多旧 `arrangement_type / arrangement_params / placements` 兼容字段；当前主链已经切到工头计划与逐节点施工，但旧契约尚未彻底退场。
- `city_c8_data` 当前更像“原始会话快照”，还不是专门为 AI 瘦身后的节点输入；除 `neighbor_summary` 外，后续仍可继续收口哪些字段真正需要稳定暴露。
- 当前 repair 仍主要表现为“失败后重开节点再提交”，还不是更细粒度的受限 patch 修补器。
- 模板详情查询接口尚未补齐；当前 AI 仍主要依赖 `template_pool_id + candidate_template_ids`，后续可补按模板或按模板池查询的详情接口。
- 多节点连续施工、长链路稳定性和更多真实 case 覆盖仍需一边测试一边修，不建议现在就把整套 C8/C9 视为彻底收官。
- `C9` 的真实落地主链已经接通，但更复杂的运行时 repair、局部回滚和高级施工编排仍属于后续增强项。
