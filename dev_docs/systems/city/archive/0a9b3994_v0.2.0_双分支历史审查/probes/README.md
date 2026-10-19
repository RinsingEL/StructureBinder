# 双分支审查探针源码

这些是2026-10-17的独立复现源码快照，不加入产品构建或当前回归测试集。

- Codex基线：`2e10af9cb667166536b6be3ca7225a658f090ede`。
- DeepSeek基线：`62ed7d2e31fd7b04cd6e48f2dcdb788ffbc9f1a7`。
- [CoreReviewProbe](CoreReviewProbe.java)：道路断面、显式材质、无墙出口、D4字段与掩码交接。
- [RealmReviewProbe](RealmReviewProbe.java)：活动半径、目录定位、缺省人口数字。
- [CodexGroundProbe](CodexGroundProbe.java)：入口末端与地表规划。
- [Gradle classpath任务](review.init.gradle)：当时导出对应模块测试运行时classpath。

用JDK17、对应提交编译产物及测试运行时classpath编译后运行。主模组与Realmfolk为独立Gradle工程；人口探针需同时提供主模组类。CoreReviewProbe把 `runtime/campus-branch-review/no-config.json` 用作不存在的缺省配置样本，复查时应确保该文件不存在。RealmReviewProbe会建立临时容量目录，不操作游戏存档。

本机run_probe.py固定了原开发机路径，编译出的class与完整日志保留本机；关键输出见[结果摘要](../probe-results.json)。入口问题后来已修复，不能在最新master重跑后仍期待旧失败结果。


## 云端读取入口

- [DeepSeek 独立远端分支](https://github.com/RinsingEL/StructureBinder/tree/deepseek/campus-playtest-fixes)；[本报告固定审查版本](https://github.com/RinsingEL/StructureBinder/tree/62ed7d2e31fd7b04cd6e48f2dcdb788ffbc9f1a7)。2026-10-19核对本地与远端同为该提交，工作树无产品改动。
- [Codex 当时的固定审查版本](https://github.com/RinsingEL/StructureBinder/tree/2e10af9cb667166536b6be3ca7225a658f090ede)。后续主线已修复的部分见报告页首，不用当前主线的行号替代当时源码。

分支对照用于追溯决策，未将DeepSeek分支合入master，也不因本次归档新增功能验收结论。
