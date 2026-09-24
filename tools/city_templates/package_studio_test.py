"""Package existing Studio export and built jars for a fresh external test instance."""
import argparse,hashlib,json,shutil,zipfile
from pathlib import Path

root=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base-package',type=Path,required=True,help='Package supplying config/geomantia defaults')
parser.add_argument('--bundle',type=Path,required=True,help='Verified Studio runtime bundle')
parser.add_argument('--output',type=Path,required=True,help='Fresh output directory; zip is written beside it')
args=parser.parse_args()
old=args.base_package.resolve()
out=args.output.resolve()
source=args.bundle.resolve()
assert not out.exists()
out.mkdir(parents=True)
shutil.copytree(old/'config/geomantia',out/'config/geomantia',ignore=shutil.ignore_patterns('mcp_server.json'))
survey_path=out/'config/geomantia/world_survey.json'
survey=json.loads(survey_path.read_text(encoding='utf-8-sig'))
survey['planningRadiusBlocks']=12288
survey_path.write_text(json.dumps(survey,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
bundle=out/'config/structureTemplate/terrasense/studio_test_20260924'
shutil.copytree(source,bundle)
for name,dest in [('geomantia-0.1.0.jar','mods'),('geomantia-0.1.0-harness.jar','optional/harness/mods'),('geomantia-0.1.0-map.jar','optional/map/mods')]:
    (out/dest).mkdir(parents=True,exist_ok=True)
    shutil.copy2(root/'build/libs'/name,out/dest/name)
provenance=json.loads((source/'studio_export_provenance.json').read_text(encoding='utf-8'))
counts=provenance['styleCounts']
names=json.loads((source/'asset_names.json').read_text(encoding='utf-8'))
summary='、'.join(f'{s} {n} 个' for s,n in counts.items())
(out/'安装说明.md').write_text(f'''# Studio 新建筑独立测试包

本包只使用 Studio 新模型：{summary}，共 {len(names)} 个，不含旧 471 建筑。

## 安装

1. 新建独立 Minecraft 1.20.1 / Forge 47.4.0 实例，使用 Java 17（Windows x64）。
2. 把本包 mods 与 config 两个目录复制到该实例根目录。
3. 默认只安装主 Mod，已含外部 MCP；不需要安装 Harness。
4. 如需游戏内 AI 配置界面与 Harness 执行器，再把 optional/harness/mods 内 Jar 放入 mods。地图同理，位于 optional/map/mods。
5. 创建全新存档。服务器启动时主 Mod 自动把本包 NBT 安装到该存档 generated/studio/structures。

config/structureTemplate/terrasense 下只能有一套完整活动目录，本包为 studio_test_20260924。已有旧素材目录的实例请先将其移到 config 以外保留备份，避免多目录歧义；不要往旧开发实例覆盖安装。
不要使用开发环境的 geomantia.providerPlanningSourceDir JVM 参数。首次到主菜单时选择 MCP 端口（建议 5001），内部 HTTP 为 5000。包内不预设 mcp_server.json，以免跳过首次设置。
新手区会在 W 范围内寻找有足够陆地且局部平缓的地面，中心保存在当前存档；不再固定在 0,0。新手区半径仍为 2048，W 中心保持 0,0。没有 W 数据时地图明确显示“地形尚未扫描”。

## 配置

config/geomantia 内保留现行世界扫描、国度规划、队列、活动范围、道路台地、城墙和最新默认 AI 提示。
W 扫描半径已按用户确认扩大为 12288 格，X/Z 范围 [-12288,12287]，面积为原 8192 半径的 2.25 倍。
3 国度，每国 1–4 城，初始活动范围 2048，首城最小距离 3072。固定采样密度下采样量随面积增加，实际耗时尚未测量。
不含 API 密钥、已有世界、生成进度或开发缓存。内置 Provider 默认关闭；外部 MCP 驱动不需要 Harness 或内置 Provider 密钥。

## 测试范围

功能、风格、核心/填充标签逐字段来自 Studio；填充池只包含作者标成 fill 的模型。
NBT 字节保持原样，Minecraft 实际模板编解码器已校验调色板、方块数、尺寸与运行时内容哈希。
作者已有离线导航报告按 NBT 哈希核对，没有重新宣称图审或游戏验收通过。
入口沿作者明确朝向投影到边界并检查净空，采用明确的 legacy_catalog 未审入口模式；仍要在游戏内观察道路高度、台阶、接地与地形适配。
原始选址条件保存在素材目录 asset_names.json/siteConditions 和 studio_export_provenance.json，当前 AI 运行时尚不读取这段文字；本包因此先选择常规平地模型。
语义 profile 的 approved 仅表示本次已核对测试标签映射，不代表 Studio 原模型图审已通过；没有改动原模型审核文件。
正式落地测试尚未进行。此包用于新存档测试，不代表可以正式发布全部素材。

## 核对文件

- 建筑清单.md：名字、风格、功能、核心/填充角色与选址说明。
- 素材目录 studio_export_provenance.json：每项作者资料、源文件哈希、排除原因和验证范围。
- manifest.json：包内文件 SHA-256。
''',encoding='utf-8')
lines=['# 新建筑清单','',f'共 {len(names)} 个：{summary}。','']
for style in counts:
    lines += ['## '+style,'','| 名字 | 角色 | 功能 | 模板 |','|---|---|---|---|']
    for n in names:
        if n['style']==style:
            lines.append(f"| {n['displayName']} | {n['planningRole'].removeprefix('planning_role.')} | {'、'.join(n['functionTerms'])} | `{n['templateRef']}` |")
    lines += ['','### 选址说明','']
    for n in names:
        if n['style']==style:
            lines += ['- '+n['displayName']+'：'+'；'.join(f'{k}：{v}' for k,v in n['siteConditions'].items())]
    lines.append('')
(out/'建筑清单.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
files=[dict(path=p.relative_to(out).as_posix(),sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in sorted(out.rglob('*')) if p.is_file()]
(out/'manifest.json').write_text(json.dumps(dict(kind='studio_isolated_test',templates=len(names),styles=counts,gameplayValidated=False,files=files),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
zip_path=out.with_suffix('.zip')
with zipfile.ZipFile(zip_path,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for p in out.rglob('*'):
        if p.is_file(): z.write(p,p.relative_to(out))
with zipfile.ZipFile(zip_path) as z:
    assert z.testzip() is None
    for f in files: assert hashlib.sha256(z.read(f['path'])).hexdigest()==f['sha256']
print(json.dumps(dict(zip=str(zip_path),templates=len(names),files=len(files),sha256=hashlib.sha256(zip_path.read_bytes()).hexdigest()),ensure_ascii=False))
