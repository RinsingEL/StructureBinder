# 城市设计 MCP · v0.4.0

AI 编辑声明式定义与城市配额，只有少量关键人物逐人创作。普通居民由程序生成。

主模组已经提供自动城市接入：城市蓝图就绪后，主 MCP 的 planning_resume 会提供 Realmfolk 专用任务及工具。正常主流程无需另接 5022。下文的独立 MCP 用于人工调整与调试；自动任务和经济字段见[经济与主流程契约](城市经济与主流程契约.md)。

## 启动与连接

双击 `启动城市设计.bat`，或执行 `scripts/launch-playtest.ps1 -World Realmfolk_City_Test -Designer`。进入世界后开放 `http://127.0.0.1:5022/mcp`，正常玩家启动不开启此接口。独立服务器可用 JVM `-Drealmfolk.designer=true`，端口由 `realmfolk.designerPort` 指定。

每次进世界生成新的 Bearer token，保存在游戏目录 `runtime/realmfolk-designer.json`。只监听回环地址，拒绝浏览器 Origin 及未认证请求。推荐通过 Python 标准库 stdio 转发器连接，无须手动复制 token。本机 MCP 配置示例（没有自动修改用户全局设置）：

```toml
[mcp_servers.realmfolk]
command = "C:/Users/Rinsing/AppData/Local/Programs/Python/Python312/python.exe"
args = ["-u", "E:/Mod/_Dev/国度冒险附属Mod/scripts/designer-mcp.py", "--game-dir", "E:/Mod/_Dev/.realmfolk-playtest"]
```

采用 [MCP 2025-03-26 Streamable HTTP](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)：initialize、ping、tools/list、tools/call，通知返回 202；不提供流式推送，GET 返回 405。工具返回符合 [MCP tools](https://modelcontextprotocol.io/specification/2025-03-26/server/tools) 的文本内容与 isError。

## 工具与作者工作流

| 工具 | 内容 |
| --- | --- |
| realmfolk_economy_catalogue | 作者池目录、兴趣说明、档位和默认组合，只读。 |
| realmfolk_read_pool | 按 id 读取作者池与相关物品基价，只读。 |
| realmfolk_capabilities | 当前版本、已实现能力及组合限制。 |
| realmfolk_schema | 按 patch/profession/realm/city_template/city_plan 查询结构。数值限制见人口契约，提交时完整校验。 |
| realmfolk_city_context | 不带 cityId 列城市；带完整实例 ID 返回职业构成、住宅容量与动态人口目标、工作用途、缺口，以及 publicLife 活动时段、参加人数和地点。 |
| realmfolk_read_design | 用 kind、id 读取一个已发布定义。 |
| realmfolk_submit_draft | baseRevision、patch、towns、scope；批量保存有效草案。 |
| realmfolk_preview | draftId；返回统计、缺口、最多五个新居民样例、可刷新/被保护人数、previewToken。 |
| realmfolk_publish | draftId + previewToken；按相同规划器发布，安排实体生成。 |
| realmfolk_discard_draft | draftId；丢弃草案。 |

先查询能力与城市摘要，再复用模板设计职业、名字和标签权重；普通 NPC 不需要逐人请求。修改前先读旧定义：patch 顶层 map 按条目 upsert，一个职业或模板条目是完整替换，未提交的其他条目保留。

城市摘要中的完整实例 id 用于 towns；cityPlanId/sourceCity 用于 patch.cities。新城市方案按 runId:citySeedId 隔离；旧裸 cityId 方案保留兼容。所有受影响的已发现实例必须列入 towns（最多 64 城）。未发现城市也可先发布规则，日后发现时应用。

例如新增矿材商，再给目标城市套用矿业模板；以下仅展示 patch，其余参数取正式返回的版本与实例 ID：

```json
{
  "professions": {
    "ore_trader": {
      "name": "矿材商",
      "appearance": "merchant",
      "bio": "山路难走，出发前备足物资。",
      "capabilities": ["conversation", "commission", "trade", "buyback", "gift", "witness", "rumors", "public_life"],
      "workplaceTerms": ["商业", "市场"],
      "commission": {"item": "minecraft:iron_ingot", "count": 3, "emeralds": 5, "affection": 6, "reputation": 2},
      "itemPools": ["mining", "building"],
      "stockSlots": 27
    }
  },
  "cityTemplates": {
    "mining_town": {
      "populationMode": "housing",
      "housingOccupancy": 0.85,
      "names": ["岩川", "岚石", "青禾"],
      "traits": {"沉稳": 3, "爽朗": 2},
      "jobs": {
        "resident": {"min": 0, "max": 256, "weight": 4},
        "ore_trader": {"min": 1, "max": 3, "weight": 2},
        "guard": {"min": 1, "max": 4, "weight": 1},
        "mayor": {"min": 1, "max": 1, "weight": 0}
      }
    }
  },
  "cities": {"请替换为返回的cityPlanId": {"template": "mining_town", "economy": {"pools": {"mining": 2, "building": 1}, "tiers": {"rare": 1.2}, "priceBand": "producer"}}}
}
```

住宅驱动模式无需逐城猜固定人数；一般居民的权重应大于零，并留足 max，专业岗位再按城市风格限制。活动规则见[人口契约](人口配置与集成.md)，capability 和场所规则必须同时存在。修改旧世界先读出当前模板和职业，保留需要的字段，再整项提交。

先用城市摘要确认实际用途词，名称相似不等于匹配。缺少市场时商人等待岗位，不强行塞入民居。

## 更新与发布语义

- `future_only`：建议默认选项，保留已有快照；新规则影响后续生成。降低目标不删人。
- `refresh_unprotected`：显式刷新未建立关系的普通居民，沿用其职业 ID 的新定义；保留 UUID、姓名、住址、工作地点、个性与已抽取数值，更新能力、职业显示、简介、装备、作息、商品、委托等快照。
- 关键人物、死亡人物、同行或非居家人物、已有好感条目、个人委托或报案关系者均受保护。已有委托不会因刷新突然换材料。
- 两种 scope 都发布法律与未来人口规则。法律对新行为立即生效，已经记录的罚金不重算。

每个存档最多 8 份草案。当前版本有效草案涉及的城市暂缓自动补员，仍可发现建筑。预览后住房归属、人口或关系变化等使旧凭证失效，须重新预览；版本改变须重新提交。一次发布在游戏线程完成，保留最近 32 次回执；同一草案和凭证重试不重复造人。

独立 MCP 的草案、发布和回执随正常世界保存持久化。主规划扩展则在持久化成功后才返回完成；主流程草案只能通过它的作用域工具发布。返回成功表示内存事务完成，不强制整世界立即落盘；异常关闭的恢复粒度与 Minecraft 正常存档一致。请求上限 128 KiB。未知字段、能力、错误类型、无效物品和不合法能力组合拒绝，旧发布保持有效。
