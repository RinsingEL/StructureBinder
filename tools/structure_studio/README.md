# 国度 · 结构工坊

不启动 Minecraft 的原创结构制作与验收工具。用 Python 编写结构与空间标记，导出原版 1.20.1 NBT，再由本地浏览器读取**导出后的 NBT**预览。不是方块编辑器的占位界面：旋转、缩放、平移、XYZ 剖切、去屋顶、楼层切片、房间聚焦、标记与室内漫游均已实现。

正式需求与契约入口：`E:\Mod_Dev\designer_territoryMod\docs\tools\structure_studio\README.md`。

## 启动

在本目录运行，首次安装依赖并准备本机已有的 **1.20.1 client.jar**：

```powershell
python -m pip install -r requirements.txt
npm.cmd ci
node scripts/export_registry.mjs
python cli.py prepare --jar '本机的 Minecraft 1.20.1 client.jar 路径'
npm.cmd run build
python -m studio.samples
python cli.py serve
```

打开 `http://127.0.0.1:8765`。以后启动只需要最后一条命令；修改前端后重新 build，修改建模源码后重新生成对应资产。

也可以运行 `start.ps1 -ClientJar <路径>`，它只在缓存或编译产物不存在时准备资源，在前台启动服务。资源准备从本机 JAR 提取模型、方块状态与原始纹理到被 Git 忽略的 `.cache/`，不打包或下载游戏资源。运行服务仅监听 `127.0.0.1`，只提供读取。

## 建模与验收

- `studio/model.py`：体素、门床、空间与工作点；确定性压缩 NBT。明确写入的 air 会清除房间，未写入的位置保持不动。
- `studio/samples.py`：兼容性样板与第一间面包铺的可重建源码。
- `studio/validate.py`：重新读取 NBT，检查边界、调色板、状态、门床配对、标记与哈希。
- `studio/navigation.py`：使用 1.20.1 碰撞盒的离线步行初筛，检查入口到标记站位；0.6×1.8 玩家体积、0.6 台阶高度。木门按玩家可打开处理，铁门保持原状。不模拟跳跃、游泳、爬梯和游戏逻辑。
- `scripts/capture.mjs`：真实浏览器自动拍摄外观、分层、房间和标记，遇到渲染错误或缺纹理则失败。**拍图不自动批准审美**。

```powershell
python -m unittest discover -s tests -v
python cli.py validate SR-F01-v01
npx.cmd playwright install chromium
npm.cmd run capture -- SR-F01-v01
node scripts/smoke.mjs
```

已有 Chromium 可通过 `STUDIO_CHROMIUM` 指定可执行文件，省去下载。`STUDIO_URL` 可覆盖本地服务地址。截图默认到 `runtime/captures/<编号>/`；也可传入第二个输出目录参数。截图清单携带 NBT 和作者 JSON 的 SHA-256，模型或标记变更后必须重拍、重审。

## 当前范围

支持本库的原版 1.20.1 方块与实体方块外形。灯光为统一查看光照，纹理动画使用首帧，颜色使用预览器默认色；不替代 Minecraft 的光照、流体、邻居更新、红石或模组机器测试。漫游允许穿墙，通路证据来自独立碰撞初筛和剖面检查。标记是作者记录，不会自动修改国度 Mod 的运行时模板目录。

后续资产批次将继续补充环境适配预览和多样性对照，**当前只有第一间商住样板，整份文明清单仍在制作中**。唯一进度源位于 `asset_catalogs/original_civilizations/` 对应核心与填充清单。
