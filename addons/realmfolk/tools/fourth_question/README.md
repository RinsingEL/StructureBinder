# 第四题 · 精简群体配置实验

用户于 2026-10-13 确认开始实验。这里验证 AI 按群体做少量设计选择后，程序能否自动展开居民、服务和需求。复用真实 D4 已接受建筑与作者用途，执行现有 Realmfolk 的填写校验、事务、人口和经济代码；不启动 Minecraft，不修改存档。

后续[核心任务类型实验](core_tasks/README.md)在本轮证据上加入可操作的地方介绍、多途径贡献与共同项目，入口为 http://127.0.0.1:8775/ 。本页保留第一轮人口与职业实验说明。

## 本轮输入

- `academy.intent.json`：AI 的五个群体及其目的、工作用途、构成权重、物品池和需求选择。权重不是固定人数。
- `author-policy.json`：作者复用的基础职业能力、人数约束、作息、需求材料与关系回报。首轮实验规则，不是用户确认的最终平衡。
- 经济目录：复用 `examples/modpacks/pcl-industries/economy.json`，包括 Ars Nouveau、农夫乐事与原版商品。没有新增商品 ID 或声称进行了本次注册表验证。
- 世界事实：读取 D4 accepted trace、蓝图、geometry commit 和冻结 catalog；拒绝未接受、哈希不符、未知用途或未批准素材。

## 复现

在本仓根目录运行（Java 17）：

```powershell
$env:PYTHONIOENCODING='utf-8'
python tools/fourth_question/experiment.py --d4 '<正式 D4 steps/blueprint 目录>'
python -m unittest discover -s tools/fourth_question -p test_experiment.py -v
./gradlew.bat test --tests com.rinsing.realmfolk.design.FourthQuestionExperimentTest --offline --rerun-tasks
python tools/fourth_question/render_report.py
```

已保存的 `results/academy/` 是本次实验输出。只查看结果直接打开 `index.html`；只复验已保存输入时省略第一个生成命令。Java 报告保存到 `.realmfolk-build/reports/fourth-question/runtime.json`，渲染器检查蓝图及三份输入 hash，拒绝复用过期结果。

## 实验编译约定

AI 只填群体，不逐个生成 NPC 或重复填写所有功能行。程序从已选角色规则展开职业，调用现有城市提交接口的纯 Java 类进行校验。人数受住房、岗位和权重影响。输出的 `submission.json` 是正式形状，但 `baseRevision=1` 仅用于隔离的内存社会模型；不能原样发布到用户世界。

本轮群体名称与背景进入职业，报告保留群体和职业对应；未新增家族、亲属、派系声望存档字段，也不保证家族同住。岗位仍按作者用途词选择，不支持精确的群体独占院落。

物资委托按作者需求模板选择已配置的本群体商品池作为获取渠道，奖励沿用作者基价和原有委托格式。货架随机抽取不保证当时有货，商品渠道不是附近野外资源证据。当前每个职业仅一个物资委托，本实验拒绝多需求静默截断。

教学、施法、附近讨伐和地牢委托列为显式缺口，不伪造成已有行为。`nearby_candidates` 只用合成测试验证地点筛选边界；从未向学院结果插入合成地点。结构生成完成不代表存在可追踪的讨伐目标，更不代表已经清剿。

## 可判定范围

正式规划器以真实建筑用途运行；离线站立名额是明确的试验假设，不是实际建筑内部可站立位置。产生的人数、身份与库存证明规则能被程序展开；实际 NPC、寻路、教学和任务完成仍需后续正式接入与游戏内验证。

本轮没有修改 Realmfolk 生产源文件，也未合并冒险工作树；该工作树的未提交内容保持原样。
