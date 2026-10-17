# 程序接入准备 · Gate1待补齐后移交

用户已采用RFH1首版方案，要求暂缓增加精制数量。作者编辑/重建流程已修复并通过真实源副本编辑验收；Blockbench学院标准模型实开/保存/重开尚未通过，当前**未正式交给程序开始接入**。

## 已就绪

- canonical作者源：`source/{rig,poses,uv_layout}.json`、`source/components/`、`source/textures/`、`draft/`；日常构建不修改这些文件。
- 构建：`python tools/build_samples.py`；随后`python tools/validate_samples.py`。构建读取源关节、动作帧和材质绑定，不隐藏再生内置样板。新库初始化单独用`init_samples.py --root <空目录>`。
- 编辑保留验收：`python tools/test_edit_rebuild.py`；[结果](verification/edit_rebuild.json)与[预览差分](verification/edit_rebuild/both_preview_diff.png)。不修改真实作者源，不增加资产数量。
- 先行工程：`build/projects/academy_standard.bbmodel`，38立方体、13骨、12材质绑定；对应实际组合为`draft/resolved/academy_standard.json`。
- 编辑器工作副本另存`authoring/`；不要把唯一改动留在build目录。目前JSON/PNG是构建真值，编辑器修改须人工回写，尚未实现自动反导。

## Gate1剩余验证

在Blockbench中打开上述**模型文件**，核对可见模型、骨组与贴图；另存`authoring/academy_standard_bb_saved.bbmodel`，关闭工程，再打开保存文件。保留工具版本、截图、保存文件哈希和几何/UV/纹理的语义比较；当前不能用JSON结构检查代替这些步骤。

本轮已打开官方网页编辑器，扩展filechooser导入被本地文件权限拒绝；随后电脑控制工具因无法可靠确认浏览器URL而终止本轮UI。没有修改浏览器权限，没有继续窗口操作。当前没有保存/重开文件及其截图，不得标记通过。

## 补齐后程序首包

只接学院standard：RFH1加载→服务端解析实际ID/颜色→SavedData快照→同步→13骨渲染与生活姿态。先验证真实站/走/坐/睡/持/食/工、主副手/床朝向、重进不变脸和资源缺失fallback，再扩三体型与另两风格。保存社会身份、关系、库存和设施归属，装饰不产生装备/库存属性。

接口与完整验收要求见[程序接入草案](E:/Mod_Dev/designer_territoryMod/docs/addons/realmfolk/NPC外观素材库/03_配置与程序接入.md)及[验证/任务包](E:/Mod_Dev/designer_territoryMod/docs/addons/realmfolk/NPC外观素材库/04_验证与任务包.md)。游戏加载、动画适配、碰撞/视点/导航、真实物品与性能均未完成。
