# 开发端 D4 实测：2026-10-11

- 用户范围：使用开发端现有测试存档，先试 D4。
- 存档：run/saves/campus_java_acceptance_20261010；游戏显示名 geometia_beta3_02。
- runId：provider_3d25631f_r19200；citySeedId：city_jingchao_capital。
- 开发客户端成功自动加载存档，HTTP GIS 服务位于 127.0.0.1:5000。游戏保持运行。
- 工具端 npm run build 成功；重新启动 MCP 后复测。

## 请求与结果

每次请求和完整返回保存在本目录编号 JSON 文件，calls.jsonl 记录调用。

1. geomantia_lobby：成功，CITY 阶段，已有 D4 草案。
2. city_d4_preview：失败，尝试读取迁移前绝对路径下的 structure_anchor_preview.png。
3. city_d4_example / public_realm：成功，返回 15 种资产、模板池和三份公共空间示例。
4. city_d4_district：原样重提教学区，返回 CITY_D4_STAGE_INPUT_INVALID，要求 designAnswers。
5. 根据返回要求添加存档已有的教学区 designAnswers 后重试：仍失败于迁移前预览绝对路径。

失败路径：E:\Users\rinsing\Documents\文稿 - Rinsing的MacBook Air\project\StructureBinder\run\saves\geometia_beta3_02\realm_debug\provider_3d25631f_r19200\city_test_runs\city_jingchao_capital\steps\blueprint\working_preview\structure_anchor_preview.png

当前存档内对应 PNG 实际存在，已查看：旧图显示计划 10 栋、保留 9 栋，教学居中、档案北侧、接待西侧、生活东侧。此图是历史产物，不能作为本次编译成功证据。

重试后 workflow 仍为 INTEGRATION、revision 9，activeDraftHash 仍为 sha256:43c5463b6ade7ba74f18353814b5e2b71419adcce431af2c3bd8e9fe3c067161；预览最后修改时间仍为 2026-10-10 00:34:34。

## 结论

未通过 D4 回归。存档迁移后，正式接口读取旧绝对预览路径造成阻塞；未阅读产品实现源码定位，未修改产品代码或手工重写存档路径。未进入 D5/D6 或世界施工。新公共空间配置实际提交、编译与落地仍未验证。

附：工具 schema 未列出 designAnswers/publicRealm，但补交 designAnswers 后错误已从缺少回答变为路径错误；因此不能仅根据 schema 缺失断言字段被拒绝。辅助 MCP 客户端最初误传空结果 schema 导致 SDK TypeError，已改用默认结果 schema；这是本轮辅助脚本错误，非产品错误。
