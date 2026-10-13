# 模组机制调研与本轮选择

本轮参考以下模组的官方页面和资料；它们提供不同方向的玩法，不把下载量当作严格排名。

| 模组 | 可供作者定义的机制目标 | 本轮状态 |
| --- | --- | --- |
| [Ars Nouveau](https://www.curseforge.com/minecraft/mc-mods/ars-nouveau) | 学会若干种符文、围绕法术构筑的进阶目标 | 选为学习目标实验；物品标识与接口核对了本地 1.20.1-4.12.7 JAR。 |
| [Iron's Spells 'n Spellbooks](https://www.curseforge.com/minecraft/mc-mods/irons-spells-n-spellbooks) | 法术收集、法术书成长、战斗练习 | 设计候选。未安装适配器；“拥有卷轴”“写入法术书”“实际施放”应分别定义，不能混为学会。 |
| [Create](https://www.curseforge.com/minecraft/mc-mods/create) | 借机械工艺完成制造、特定自动化成果 | 设计候选。若要求制造来源，单纯持有成品不足以证明，需要对应工艺事件适配。 |

[Ars 官方学习指南](https://ars.guide/book/getting_started/learning_glyphs/)描述制作、使用符文后学习法术部件；[法术使用指南](https://ars.guide/book/getting_started/spell_casting/)说明组合与施放。

先前对本机 JAR 的静态核对：`CapabilityRegistry.getPlayerDataCap` 可读玩家能力，`IPlayerCap.getKnownGlyphs` 提供已知部件；`Glyph` 使用逻辑调用解锁能力并处理物品消耗。适配须考虑默认起始部件，不能仅检查玩家是否持有 Glyph 物品。官方源码：[baileyholl/Ars-Nouveau](https://github.com/baileyholl/Ars-Nouveau)。

独立网页不加载这些 API，只模拟其预期输入，验证“持有不等于学会”“按不同种类计数”等规则。本次没有证明真实游戏内学习适配已经运行。
