# 依赖与来源

Realmfolk 依赖 Rinsing 的 Geomantia 0.1.0（GPL-3.0-or-later）。主模组源码：E:\Mod_Dev\StructureBinder。本仓库未提交编译后的依赖 JAR；通过构建与准备脚本复制到本地测试环境。

Gradle Wrapper 和 ForgeGradle 项目框架沿用本地主项目；Forge、Minecraft、其他测试模组及其资源分别遵循各自许可。居民渲染引用游戏运行时的原版村民纹理，不复制游戏素材到本仓库。

测试准备脚本只在独立运行目录为本地 ReTerraForged JAR 补上缺失的 pack.mcmeta，使 Forge 不停留在资源包元数据警告界面；原依赖文件保持原样。
