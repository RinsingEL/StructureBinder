# Realmfolk 可组合 NPC 外观样板 · RFH1 / draft-1

本目录是**离线资产原型**，不是可放入游戏即生效的资源包。当前 Realmfolk 仍使用村民渲染；本轮没有新增游戏加载能力。

- [设计、规范、接口与任务包](E:/Mod_Dev/designer_territoryMod/docs/addons/realmfolk/NPC外观素材库/README.md)
- `source/rig.json`：可编辑骨架、尺寸、挂点源数据。
- `source/components/*.json`：可编辑、独立组件，按身体提供几何变体。
- `source/textures/`：透明 PNG 灰度明暗、分区掩码、面部与眼睛层；128×128 最近邻。
- `source/poses.json`：可编辑的保持姿态与循环关键帧；日常预览读取此文件，不再调用内置样板动作。
- `build/projects/*.bbmodel`：日常构建生成的Blockbench工程（容器4.10、free，嵌入贴图和骨组）。这是导出物，不能在此保存唯一的作者修改。
- `authoring/legacy/*.bbmodel`：首轮工程原字节移存，保留可能已有的作者修改；日常构建不读取或覆盖此目录。
- `authoring/`：编辑器保存的作者工作副本，日常构建不覆盖。当前仍是单向导出；在编辑器修改后，须人工回写JSON/PNG再重建，不宣称可自动往返。
- `draft/catalog.json`、`pools.json`、`resolved/*.json`：**拟议接口**及已解析的实际 ID 快照，不是现有 design.json 的扩展字段。
- `export/proposed/assets/realmfolk/appearance/`：拟议 `.rfmodel.json` 与 PNG 目标结构；只有未来专用加载器才能读取，**未验证游戏可加载**。
- `preview/`：离线几何渲染PNG/GIF；不是游戏截图。`build/verification.json`记录当前检查；`verification/edit_rebuild.json`及同名目录保留作者编辑/重建验收证据。

日常：编辑`source/`中的JSON/PNG，以及`draft/`中的目录、色板和组合快照，然后执行：

```powershell
python tools/build_samples.py
python tools/validate_samples.py
```

构建只读取已有源，写入`build/`、`export/`、`preview/`；检查source/draft/authoring的构建前后字节哈希一致。缺少源文件时直接报错，绝不自动初始化。只构建学院标准组合可用`python tools/build_samples.py --assembly academy_standard`；此命令生成单体预览和工程，不刷新整库矩阵。

**初始化是另一入口**：仅在全新空目录执行`python tools/init_samples.py --root E:/path/to/new_library`。只要source或draft已存在就拒绝，没有覆盖开关。当前库已初始化，勿重复执行。首次拆分时仅将既有动作样本显式补充为可编辑循环帧，其他作者源不重生。

验证编辑保留：`python tools/test_edit_rebuild.py`。它复制当前作者源到隔离目录，修改学院袍体from角点X和`outer_academy__cloth.png`的(6,17)像素，分别/同时重建，核对源文件不变、RFH1/PNG/嵌入工程与预览同步变化；原库作者文件保持不变。RFH1是cuboid，from角点调整会移动一整面，不表示支持自由网格的单顶点形变。

几何材质应编辑组件`materials`引用的`__<region>.png`及掩码；公共灰度图不自动反向同步到各区域图。皮肤层使用skin资产的texture，面部与眼睛独立叠加，虹膜以快照colors.eye为准。依赖Python 3、Pillow、NumPy，无网络素材下载。

Blockbench学院标准工程的JSON结构校验已过；本轮已打开官方网页编辑器，但文件导入受扩展本地文件权限限制，随后电脑控制工具因无法确认浏览器URL而终止。**实开模型、保存与重新打开尚未通过**。暂停新增精制数量；编辑器门槛通过后才移交程序开始最小游戏闭环。

预览：`lineup.png` 为三风格×三体型；`academy_turnaround.png` 为学院标准体型前/侧/后；`poses_*.png` 为七姿态×三体型；`motion_*.gif` 是同一离线骨架的连续采样。先以学院完整组合为基准，其余两风格用于对照，不表示全文明库完成。

来源与许可见 [PROVENANCE.md](PROVENANCE.md)。
