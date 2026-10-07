"""Verify and package an existing Studio bundle without jars or instance settings."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import shutil
import zipfile


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(bundle):
    catalog = read(bundle / 'template_catalog.json')['templates']
    profiles = [json.loads(line) for line in (bundle / 'StructureProfile.jsonl').read_text(encoding='utf-8-sig').splitlines() if line.strip()]
    manifest = read(bundle / 'city_template_content_pack.json')
    provenance = read(bundle / 'studio_export_provenance.json')
    names = read(bundle / 'asset_names.json')
    refs = read(bundle / 'blueprint_reference_catalog.json')
    runtime = read(bundle / 'runtime_metadata.json')
    atlas = read(bundle / 'realm_core_atlas.json')
    indexed = {}
    for key, rows, field in [('catalog', catalog, 'templateRef'), ('profiles', profiles, 'structureId'),
                             ('payloads', manifest['templates'], 'templateRef'),
                             ('provenance', provenance['templates'], 'templateRef'),
                             ('names', names, 'templateRef'), ('runtime', runtime, 'templateRef'),
                             ('refs', refs['structureRefs'], 'structureRef')]:
        indexed[key] = {r[field]: r for r in rows}
        assert len(indexed[key]) == len(rows), f'Duplicate {key}'
        assert set(indexed[key]) == set(indexed['catalog']), f'Coverage {key}'
    assert manifest['catalogSha256'] == 'sha256:' + digest(bundle / 'template_catalog.json')
    for ref, row in indexed['catalog'].items():
        profile = indexed['profiles'][ref]
        origin = indexed['provenance'][ref]
        payload = indexed['payloads'][ref]
        nbt = bundle / 'city_template_content_pack' / payload['sourceFile']
        assert nbt.resolve().is_relative_to((bundle / 'city_template_content_pack').resolve())
        assert payload['sourceSha256'] == origin['sourceSha256'] == 'sha256:' + digest(nbt)
        assert row['contentHash'] == indexed['runtime'][ref]['templateHash']
        assert row['rawSize'] == indexed['runtime'][ref]['rawSize'] == origin['rawSize']
        assert profile['styleTerms'] == [row['style']] == [origin['author']['civilization']]
        assert profile['functionTerms'] == origin['author']['function_terms']
        assert profile['planningRoleTerms'] == [origin['author']['planning_role']]
        if 'category' in profile:
            assert profile['category'] in {'specialty','common'}
            assert profile['assetTags'] == origin['author'].get('asset_tags',[])
        assert row['roadEntrances'] == origin['roadEntrances']
        assert all(e['clearanceChecked'] for e in origin['entranceEvidence'])
        assert indexed['refs'][ref]['templateCandidates'] == [dict(templateId=row['templateId'], variantId=row['variant'])]
    nbt_files = list((bundle / 'city_template_content_pack').rglob('*.nbt'))
    assert len(nbt_files) == len(catalog)
    for pool in refs['fillPools']:
        members = [indexed['profiles'][ref] for ref in pool['structureRefs']]
        assert members  # Pool membership is authored function/style eligibility, not a city role.
        assert len({tuple(p['styleTerms']) for p in members}) == 1
        assert set.intersection(*(set(p['functionTerms']) for p in members))
    cores = {ref for ref, p in indexed['profiles'].items() if set(p['planningRoleTerms']) & {'planning_role.key', 'planning_role.anchor'}}
    assert cores == {r['templateRef'] for r in atlas['cores']}
    for path, sha in atlas['sources'].items():
        assert digest(bundle / path) == sha
    for page in atlas['pages']:
        assert digest(bundle / page['file']) == page['sha256']
    assert Counter(n['style'] for n in names) == provenance['styleCounts']
    return provenance, names, atlas


def package(source, output, bundle_name='studio_multi_20260927'):
    provenance, names, atlas = verify(source)
    core_counts = Counter(c['styleTerms'][0] for c in atlas['cores'])
    missing_cores = set(provenance['styleCounts']) - set(core_counts)
    if missing_cores:
        raise ValueError(f'Styles without core buildings: {sorted(missing_cores)}')
    if not bundle_name or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789_-' for c in bundle_name):
        raise ValueError('Use a lowercase bundle directory name without path separators')
    archive = output.with_suffix('.zip')
    if output.exists() or archive.exists():
        raise ValueError('Use a fresh output directory and ZIP path')
    target = output / 'config/structureTemplate/terrasense' / bundle_name
    shutil.copytree(source, target)
    counts = provenance['styleCounts']
    summary = '\n'.join(f'| {style} | {count} | {core_counts[style]} |' for style, count in counts.items())
    (output / '安装说明.md').write_text(f'''# 多风格素材配置测试包

共 {len(names)} 个模型、{len(counts)} 个风格，核心模型 {len(atlas['cores'])} 个。
仅包含素材配置、NBT、核心图集与校验记录；沿用现有兼容的 Minecraft 1.20.1 Forge / Geomantia Mod，无须更换 JAR。

## 安装

1. 关闭测试实例。在实际游戏目录（开启版本隔离时为对应版本目录）的 config/structureTemplate/terrasense 下，把旧的完整素材目录移到 config 之外备份。该位置只保留一套活动素材目录。
2. 将本包 config 文件夹复制到该游戏目录。最终应存在 config/structureTemplate/terrasense/{bundle_name}/template_catalog.json。
3. 不使用指向旧素材目录的 geomantia.providerPlanningSourceDir JVM 参数。启动实例并创建新测试存档；Mod 会在世界启动时把 NBT 安装到 generated/studio/structures。
4. 现有 config/geomantia 和 Mod 配置继续沿用。本包未附带 API 密钥、JAR 或存档，也未部署到任何实例。

## 风格数量

| 风格标识 | 模型数 | 核心数（已含于模型数） |
|---|---:|---:|
{summary}

全部风格均含核心建筑。打包器会拒绝任何缺少核心的风格，避免将只有普通/填充建筑的风格当成完整城市测试素材交付。

## 验证范围

已通过 Minecraft 模板编解码和运行时内容哈希核对；目录/profile/manifest/NBT 引用与源文件哈希一致。入口按作者明确朝向投影并通过净空检查，候选池包含作者同风格、同功能素材，本次核心/必需/填充由功能区设计明确。
核心图集源截图与 NBT 哈希一致，不代表人工图审通过。采用 legacy_catalog 未审入口模式；道路高度、接地、坡地和岸边适配仍需游戏内实测。
保留作者 siteConditions，但运行时尚不读取该选址文字。未进行实机验收。
内部模板 variant/packId 沿用导出器的 studio_test_20260924，引用保持一致；安装目录独立命名为 {bundle_name}。

建筑清单见 建筑清单.md；本轮排除 {len(provenance['omitted'])} 项，原因见 排除清单.md。素材目录中的 studio_export_provenance.json 保留完整逐项证据。
''', encoding='utf-8')
    lines = ['# 建筑清单', '', '| 名称 | 风格 | 角色 | 模板 |', '|---|---|---|---|']
    lines += [f"| {n['displayName']} | {n['style']} | {n['planningRole']} | {n['templateRef']} |" for n in names]
    (output / '建筑清单.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    lines = ['# 排除清单', '', '未修改或重建这些模型。本轮按当前作者元数据和入口投影检查排除。', '', '| 模型 | 原因 |', '|---|---|']
    lines += [f"| {n['id']} | {n['reason']} |" for n in provenance['omitted']]
    (output / '排除清单.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    verify(target)
    files = [dict(path=p.relative_to(output).as_posix(), sha256=digest(p)) for p in sorted(output.rglob('*')) if p.is_file()]
    assert not any(f['path'].startswith(('mods/', 'config/geomantia/')) for f in files)
    manifest = dict(kind='studio_config_only_test', templates=len(names), styles=counts,
                    cores=len(atlas['cores']), omitted=len(provenance['omitted']), gameplayValidated=False, files=files)
    (output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for p in sorted(output.rglob('*')):
            if p.is_file():
                z.write(p, p.relative_to(output).as_posix())
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        assert len(z.namelist()) == len(files) + 1
        for entry in files:
            assert hashlib.sha256(z.read(entry['path'])).hexdigest() == entry['sha256']
    print(json.dumps(dict(zip=str(archive.resolve()), templates=len(names), styles=counts,
                         cores=len(atlas['cores']), omitted=len(provenance['omitted']),
                         files=len(files) + 1, sha256=digest(archive)), ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--bundle-name', default='studio_multi_20260927')
    args = parser.parse_args()
    package(args.bundle, args.output, args.bundle_name)
