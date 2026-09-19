"""Audit named building functions; decorative assets never establish city capability."""
import argparse
from collections import defaultdict
import json
from pathlib import Path

AXES = {
    '居住': {'住宅'}, '商业餐饮': {'商业', '餐饮', '金融'},
    '生产工坊': {'工坊生产', '采矿'}, '仓储': {'仓储'}, '治理': {'行政', '治安消防'},
    '农业': {'农业'}, '宗教文化': {'宗教', '宗教建筑（类型未细分）', '教育文化'},
    '军事防御': {'军事防御'}, '住宿': {'住宿'}, '交通供水': {'港航', '桥梁', '轨道交通', '水利供水'},
}
CORE = ['居住', '商业餐饮', '生产工坊', '仓储', '治理']
UNCLEAR = {'房屋（用途未细分）', '建筑（用途未细分）', '塔楼（用途未细分）',
           '大厅（用途未细分）', '工棚（用途未细分）', '功能待确认'}


def audit(items):
    buildings = [r for r in items if r['assetKind'] == '建筑与城市配套' and not r['bundleHint']]
    functions = {axis: [r['assetId'] for r in buildings if tags.intersection(r['functions'])]
                 for axis, tags in AXES.items()}
    missing = [axis for axis in CORE if not functions[axis]]
    uncertain = [r['assetId'] for r in buildings if UNCLEAR.intersection(r['functions'])]
    coverage = sum(bool(functions[axis]) for axis in CORE)
    if not buildings:
        status = '仅配套，不能独立成城'
    elif not missing:
        status = '核心功能名称齐备，待外观与落地审核'
    elif coverage >= 3:
        status = '已有城镇骨架，需补齐功能'
    else:
        status = '功能覆盖不足，暂作专项或待定素材'
    return dict(totalAssets=len(items), cityFolderCandidates=len(buildings), status=status,
                independentCityApproved=False, coverageByFunction=functions,
                missingNamedCoreFunctions=missing, unassignedBuildingCandidates=uncertain,
                note='缺口表示未从名称找到明确对应，不表示结构内部一定没有该用途；一般房屋不自动填补缺口。')


def build(directory):
    rows = [json.loads(s) for s in (directory / 'assets.jsonl').read_text(encoding='utf-8').splitlines()]
    by_style, by_series = defaultdict(list), defaultdict(list)
    lookup = {r['assetId']: r for r in rows}
    for row in rows:
        for style in row['styles']: by_style[style].append(row)
        if row['assetKind'] == '建筑与城市配套': by_series[row['sourceSeries']].append(row)
    styles = {style: audit(items) for style, items in sorted(by_style.items())}
    series = {series: audit(items) for series, items in sorted(by_series.items())}
    result = dict(basis='name_based_function_coverage',
                  comparisonAxes=list(AXES), coreComparisonAxes=CORE,
                  interpretation='审阅排序用的功能对照，不是新增游戏准入规则；城市设计仍决定实际必需功能。',
                  styles=styles, sourceSeries=series)
    (directory / 'coverage.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n',encoding='utf-8')
    lines = ['# 风格与成城功能核对', '', '[返回素材总览](README.md)', '',
             '**有风格标签，不代表能组成城市。**雕像、树木、车辆、船舶陈设及一般装饰不会补齐住宅、工坊、仓储等建筑功能。', '',
             '本表仍只读文件夹和名称。数字是名称明确命中的候选文件数，不是已验证可用的建筑数；多功能可重复计入。',
             '普通“房屋1”“建筑2”保留为用途未分配，不自动当住宅、商店或仓库。出现“带农田”等名称，也只算候选，不能证明能独立承担整个功能。', '',
             '暂以居住、商业餐饮、生产工坊、仓储、治理五项核对基础城镇功能，农业、宗教文化、防御等另外展示。这是整理时的比较维度，不强制所有城镇必须具备同一组建筑。', '',
             '**同风格跨系列汇总齐备，也不代表单个系列齐备或可混搭。**下方另列系列缺口。所有风格目前都尚未获准独立成城。', '',
             '## 风格功能矩阵', '',
             '| 风格 | 居住 | 商业餐饮 | 生产工坊 | 仓储 | 治理 | 用途待分配 | 名称核对结论 |',
             '| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |']
    for style, info in styles.items():
        if style == '风格待确认': continue
        counts = [str(len(info['coverageByFunction'][axis])) for axis in CORE]
        lines.append('| '+ ' | '.join([f'[{style}](styles/{style}.md)', *counts,
                    str(len(info['unassignedBuildingCandidates'])), info['status']])+' |')
    for style, info in styles.items():
        if style == '风格待确认': continue
        lines += ['', f'## {style}', '', f'**{info["status"]}**。', '',
                  '尚未找到明确名称的基础功能：'+('、'.join(info['missingNamedCoreFunctions']) or '无（仍需审核实际用途与系列兼容）')+'。', '',
                  '| 功能 | 数量 | 名称证据示例（最多 4 份） |', '| --- | ---: | --- |']
        for axis, ids in info['coverageByFunction'].items():
            examples = '；'.join(lookup[i]['name']+'〔'+lookup[i]['sourceSeries']+'〕' for i in ids[:4]) or '未找到明确名称'
            lines.append(f'| {axis} | {len(ids)} | {examples} |')
    lines += ['', '## 原建筑系列核对', '',
              '各系列单独计算，不用其他系列的功能掩盖本系列缺口。主题性住宅包可以保留为住宅池，但不可因此单独提供整城设计。', '',
              '| 原系列 | 基础功能未找到明确名称 | 已有基础功能 | 用途待分配 |', '| --- | --- | --- | ---: |']
    for name, info in series.items():
        found = [axis for axis in CORE if info['coverageByFunction'][axis]]
        lines.append('| '+' | '.join([name, '、'.join(info['missingNamedCoreFunctions']) or '无',
                     '、'.join(found) or '暂无明确名称',str(len(info['unassignedBuildingCandidates']))])+' |')
    lines += ['', '## AI 使用边界', '',
              '- 先读取 coverage.json 的风格和原系列缺口，再检索具体候选。',
              '- 核心功能缺失时明确报告缺口；未经确认，不把无用途房屋改称工坊、仓库或市政厅。',
              '- 配套素材继续保留，但不能单独作为文明选择依据。风格待确认素材也不能自动借用补缺。',
              '- 可以提出同风格跨系列补齐方案，保留各自来源，待确认视觉协调后再作为完整素材池。', '']
    (directory / 'COVERAGE.md').write_text('\n'.join(lines),encoding='utf-8')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--catalog-dir',type=Path,default=Path(__file__).resolve().parents[2]/'asset_catalogs/construction_pack')
    print(json.dumps({s:v['status'] for s,v in build(parser.parse_args().catalog_dir)['styles'].items()},ensure_ascii=True))
