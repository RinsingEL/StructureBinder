"""Name-based discovery catalog, separate from the approved runtime template catalog."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import re

STYLES = {
    '中世纪': ['中世纪'], '东方': ['东方'], '日式': ['日式', '日本'],
    '北欧': ['北欧'], '沙漠': ['沙漠'], '罗马': ['罗马'], '斯巴达': ['斯巴达'],
    '精灵': ['精灵'], '兽人': ['兽人'], '哥布林': ['哥布林'], '矮人': ['矮人'],
    '蒸汽朋克': ['蒸汽朋克'], '西部': ['西部'], '现代': ['现代'],
    '科幻': ['科幻', '未来主义', '外星', '星球大战'], '奇幻': ['奇幻', '魔法'],
    '埃及': ['埃及'], '乐高': ['乐高'],
}
THEMES = {'冬日': ['冬季', '冬日', '雪屋'], '湖畔': ['湖畔'], '海滨': ['海滨'],
          '农场': ['农场'], '铜顶': ['铜顶'], '砖顶': ['砖顶'], '石顶': ['石顶'],
          '红顶': ['红顶'], '木构': ['木屋', '木房'], '石构': ['石屋'],
          '彩色': ['彩色'], '郊区': ['郊区'], '山地': ['山间', '山坡']}
FUNCTIONS = {
    '行政': ['市政厅', '公所', '议事厅', '议会', '法院'],
    '公会': ['公会'], '商业': ['商店', '店铺', '杂货', '集市', '市场', '摊位', '小店', '花店'],
    '餐饮': ['酒馆', '酒吧', '餐馆', '餐厅', '小餐馆', '咖啡', '烧烤', '肉铺', '鱼店', '面包'],
    '住宿': ['旅店', '旅馆', '客栈', '酒店', '酒店', '宾馆', '旅舍', '精品酒店'],
    '教育文化': ['图书', '学校', '学院', '博物馆', '剧院', '音乐厅'],
    '宗教': ['教堂', '寺庙', '神庙', '清真寺', '修道院', '神社', '佛塔', '祭坛'],
    '工坊生产': ['铁匠', '锻造', '冶矿', '炼金', '作坊', '工坊', '锯木', '工厂', '修车', '磨坊'],
    '采矿': ['矿井', '金矿', '煤矿', '采石'],
    '农业': ['农田', '梯田', '农场', '农舍', '牧舍', '畜栏', '马厩', '温室', '麦田', '风车', '水车'],
    '仓储': ['谷仓', '仓库', '粮仓', '存放', '货仓', '粮库'],
    '军事防御': ['城堡', '堡垒', '哨塔', '瞭望', '守卫塔', '防御塔', '兵营', '军事', '炮台', '城墙', '门楼', '防御工事'],
    '港航': ['码头', '船坞', '灯塔', '港口'], '桥梁': ['桥'],
    '轨道交通': ['铁路', '铁轨', '车站', '地铁'], '照明': ['路灯', '街灯'],
    '水利供水': ['水井', '水闸', '水塔', '水渠', '水坝'],
    '公共空间': ['广场', '喷泉', '公园', '花园', '凉亭', '池塘', '温泉'],
    '纪念装饰': ['纪念碑', '雕像', '石像', '拱门', '钟楼', '国旗', '符号', '齿轮'],
    '体育娱乐': ['竞技场', '体育场', '游泳池'],
    '住宅': ['民居', '住宅', '居民区', '公寓', '别墅', '豪宅', '宅邸', '公馆', '长屋'],
}
GENERIC = {'房屋（用途未细分）': ['房屋', '小屋', '木屋', '石屋', '平房', '棚屋', '村舍', '岛屋'],
           '塔楼（用途未细分）': ['塔', '高楼'], '建筑（用途未细分）': ['建筑']}
FUNCTIONS.update({
    '金融': ['银行'], '办公': ['办公楼', '办公大楼', '事务所', '商会', '商贸中心'],
    '治安消防': ['警察局', '警长', '消防', '监狱'], '医疗': ['医院', '住院部', '诊所'],
    '门与入口': ['大门', '正门', '黑暗之门'], '宫殿': ['宫殿'],
    '墓葬纪念': ['墓穴', '墓地', '陵墓'], '船舶陈设': ['木船', '帆船', '船只', '触礁的船'],
    '军事器械陈设': ['投石机', '重炮', '军械'], '运输配套': ['驿站', '马车'],
    '生存基地': ['生存基地'], '营地': ['营地'], '工棚（用途未细分）': ['工棚'],
    '大厅（用途未细分）': ['大厅'], '场景或聚落整体': ['浮岛', '浮空岛', '浮空小岛', '小岛', '小镇', '村庄', '瀑布城'],
})
for tag, words in {
    '行政': ['国会'], '商业': ['书店', '宠物店', '果蔬店', '商业区', '商铺', '贸易'],
    '宗教': ['神殿', '神龛', '圣所'], '工坊生产': ['冶炼'], '农业': ['鸡舍'],
    '仓储': ['库房', '军械库'], '军事防御': ['要塞', '地堡', '石堡', '主堡', '守卫房', '烽火台', '据点'],
    '公共空间': ['庭院', '钓鱼池'], '纪念装饰': ['凯旋门', '女神像', '装饰雪人', '沙漏', '时钟', '绞刑架'],
    '教育文化': ['戏院', '剧场', '影院'], '体育娱乐': ['道场', '训练场', '足球场', '斗兽场', '曲棍球场', '靶场'],
    '住宅': ['居所'],
}.items():
    FUNCTIONS[tag].extend(words)
GENERIC['房屋（用途未细分）'].extend(['树屋', '砖屋', '蘑菇屋'])
GENERIC['建筑（用途未细分）'].extend(['庄园', '摩天大楼'])
KINDS = {'城市,房屋': '建筑与城市配套', '树木,绿植': '绿化素材', '环境装饰等': '环境配套',
         '天空类': '空中场景', '载具武器类': '载具与武器', '雕像': '雕像与展示素材'}


def matches(text, rules, source):
    return [{'tag': tag, 'source': source, 'matched': keyword}
            for tag, keywords in rules.items()
            for keyword in [next((word for word in keywords if word in text), None)] if keyword]


def classify(relative):
    parts = relative.parts
    directories = parts[:-1]
    name = relative.stem
    # Mixed umbrella folders do not assert one style; retain their exact series name.
    evidence = []
    for folder in directories[1:]:
        if folder == '科幻和现实的飞行载具':
            continue
        evidence.extend(matches(folder, STYLES, 'folder:' + folder))
    evidence.extend(matches(name, STYLES, 'filename'))
    styles = sorted({e['tag'] for e in evidence}) or ['风格待确认']
    theme_evidence = matches('/'.join(directories[1:]) + '/' + name, THEMES, 'path_and_filename')
    specific = matches(name, FUNCTIONS, 'filename')
    if parts[0] == '雕像':
        specific = [{'tag': '雕像装饰', 'source': 'folder', 'matched': '雕像'}]
    elif parts[0] == '载具武器类':
        specific = [{'tag': '载具或武器陈设', 'source': 'folder', 'matched': parts[1]}]
    elif parts[0] == '树木,绿植':
        if not specific:
            tag = '植物雕像' if '植物雕像' in directories else '绿化素材'
            specific = [{'tag': tag, 'source': 'folder', 'matched': parts[1]}]
    if not specific:
        specific = matches(name, GENERIC, 'filename')
    # A numbered building within a village pack is not the whole village; composite names
    # such as 公会大厅 keep the specific function instead of also becoming an unknown hall.
    if len(specific) > 1:
        specific = [e for e in specific if e['tag'] not in {'大厅（用途未细分）', '场景或聚落整体'}]
    if any(word in name for word in ['房屋', '小屋']) and all(e['tag'] == '场景或聚落整体' for e in specific):
        specific = matches(name, GENERIC, 'filename')
    if not specific:
        # Only unambiguous functional folders: never inherit all words from mixed names.
        folder_functions = {'房屋': '房屋（用途未细分）', '店铺': '商业', '摊位': '商业',
                            '路灯': '照明', '农田': '农业', '树木': '绿化素材',
                            '桥': '桥梁', '池塘': '公共空间', '雕像': '雕像装饰',
                            '寺庙教堂等 构建包': '宗教建筑（类型未细分）',
                            '各种塔 构建包': '塔楼（用途未细分）'}
        for folder in reversed(directories):
            if folder in folder_functions:
                specific = [{'tag': folder_functions[folder], 'source': 'folder', 'matched': folder}]
                break
    functions = sorted({e['tag'] for e in specific}) or ['功能待确认']
    series_parts = directories[1:2]
    # Source packs, not functions such as 房屋/树木, define the original subseries.
    if len(directories) > 2 and directories[1] in {
            '中世纪房屋素材 构建', '各式风格建筑构建包', '各式各样的房屋1', '各式各样的房屋2'}:
        series_parts = directories[1:3]
    return dict(styles=styles, styleEvidence=evidence,
                themes=sorted({e['tag'] for e in theme_evidence}), themeEvidence=theme_evidence,
                functions=functions, functionEvidence=specific,
                sourceSeries='/'.join(series_parts), sourceFolder='/'.join(directories),
                assetKind=KINDS.get(parts[0], '其他'),
                bundleHint=bool(re.search(r'全套|全部|全体|合集|整套', name)))


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def build(root, output):
    root = root.resolve()
    if not root.is_dir():
        raise ValueError('Source directory does not exist')
    if output.resolve().is_relative_to(root):
        raise ValueError('Output must be outside the source collection')
    files = sorted(root.rglob('*.litematic'), key=lambda p: p.relative_to(root).as_posix())
    rows = []
    for path in files:
        relative = path.relative_to(root)
        rows.append(dict(assetId='asset_' + hashlib.sha256(relative.as_posix().encode()).hexdigest()[:16],
                         name=path.stem, relativePath=relative.as_posix(), sourcePath=str(path),
                         classificationStatus='name_based_candidate', runtimeReady=False,
                         **classify(relative)))
    scanned_count = len(rows)
    # The user asked to remove unusable city palettes from the delivered list.
    # Evaluate the style pool, not individual residential subseries in isolation.
    from audit_construction_coverage import audit
    candidate_styles = sorted({style for row in rows for style in row['styles']} - {'风格待确认'})
    retained_styles = {style for style in candidate_styles if not audit(
        [row for row in rows if style in row['styles']])['missingNamedCoreFunctions']}
    excluded_functions = {'雕像装饰', '植物雕像', '军事器械陈设', '载具或武器陈设',
                          '场景或聚落整体', '功能待确认'}
    rows = [row for row in rows if retained_styles.intersection(row['styles'])
            and row['assetKind'] in {'建筑与城市配套', '环境配套', '绿化素材'}
            and not row['bundleHint'] and not excluded_functions.intersection(row['functions'])]
    for row in rows:
        row['secondaryStyles'] = [style for style in row['styles'] if style not in retained_styles]
        row['styles'] = [style for style in row['styles'] if style in retained_styles]
    assert len({row['assetId'] for row in rows}) == len(rows)
    output.mkdir(parents=True, exist_ok=True)
    with (output / 'assets.jsonl').open('w', encoding='utf-8') as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False) + '\n')
    style_groups, series_groups = defaultdict(list), defaultdict(list)
    for row in rows:
        for style in row['styles']:
            style_groups[style].append(row)
        series_groups[(row['assetKind'], row['sourceSeries'])].append(row)
    series = [dict(assetKind=kind, sourceSeries=name, count=len(items),
                   styles=sorted({s for row in items for s in row['styles']}),
                   functions=dict(sorted(Counter(f for row in items for f in row['functions']).items())),
                   assetIds=[row['assetId'] for row in items])
              for (kind, name), items in sorted(series_groups.items())]
    write_json(output / 'series.json', series)
    summary = dict(sourceRoot=str(root), totalAssets=len(rows), scannedAssets=scanned_count,
                   excludedAssets=scanned_count-len(rows),
                   selectionPolicy='retain_named_core_complete_style_pools_and_city_support_assets',
                   classificationBasis='folder_and_filename_only',
                   countsByKind=dict(Counter(row['assetKind'] for row in rows)),
                   countsByStyle={s: len(items) for s, items in sorted(style_groups.items())},
                   countsByFunction=dict(sorted(Counter(f for row in rows for f in row['functions']).items())),
                   sourceSeriesCount=len(series), bundleHints=sum(row['bundleHint'] for row in rows))
    write_json(output / 'summary.json', summary)
    lines = ['# 构建包素材目录', '',
             '**先看 [风格与成城功能核对](COVERAGE.md)**：此处列的是名称标签，不能当成可独立成城的文明清单。雕像及其他配套不用于填补建筑功能。', '',
             '按原始文件夹名与文件名整理，供 AI 设计检索候选素材。没有读取结构内容，也未审核外观、尺寸、入口、独立可用性；所有条目均不是已批准的运行时模板。', '',
             f'源目录：`{root}`', '', f'筛选后保留 **{len(rows)}** 份素材，**{len(series)}** 个来源系列。', '',
             '按用户要求，主清单只保留居住、商贸、生产、仓储、治理均有名称证据的风格素材池及其城市配套；移除其他风格、雕像、载具武器陈设、合集和无法辨认用途的条目。子系列可作为已保留风格的部分功能素材，不单独冒充完整文明。原始文件不变。', '',
             '## 使用方法', '',
             '1. 从下表选择风格，优先查看建筑与城市配套，保留原素材系列。',
             '2. 按功能找候选，再查看原名及依据；多功能标签允许并存。',
             '3. 同风格不同系列不代表视觉兼容；东方/沙漠等是源目录风格标签，不自动等同具体历史文明。',
             '4. “房屋（用途未细分）”不可当作已确认民居；缺少功能时报告缺口，不根据编号猜用途。',
             '5. 风格待确认的素材不自动视为通用兼容；载具、雕像、合集不自动当作可落地建筑。', '',
             '机器读取：`assets.jsonl` 每行一个素材（稳定 ID、路径、风格、功能、主题及匹配依据）；`series.json` 为来源系列摘要；`summary.json` 为统计。', '',
             '## 风格入口', '', '| 名称标签 | 全部素材 | 建筑与城市配套 | 明确功能标签覆盖 |', '| --- | ---: | ---: | --- |']
    style_dir = output / 'styles'
    style_dir.mkdir(exist_ok=True)
    for style, items in sorted(style_groups.items()):
        buildings = [row for row in items if row['assetKind'] == '建筑与城市配套']
        functions = sorted({f for row in buildings for f in row['functions']
                            if '未细分' not in f and '待确认' not in f})
        lines.append(f'| [{style}](styles/{style}.md) | {len(items)} | {len(buildings)} | {"、".join(functions) or "待确认"} |')
        page = [f'# {style}：名称分类候选', '', '[返回总览](../README.md)', '',
                '标签来自名称，不表示外观审核或可直接落地。主题与功能分别记录；原系列保留。', '']
        grouped = defaultdict(list)
        for row in items:
            grouped[(row['assetKind'], row['sourceSeries'])].append(row)
        for (kind, source_series), group in sorted(grouped.items()):
            page += [f'## {kind} / {source_series}（{len(group)}）', '',
                     '| 原名 | 功能候选 | 主题 | 原始相对路径 |', '| --- | --- | --- | --- |']
            for row in group:
                values = [row['name'] + ('【合集候选】' if row['bundleHint'] else ''),
                          '、'.join(row['functions']), '、'.join(row['secondaryStyles'] + row['themes']) or '—', row['relativePath']]
                page.append('| ' + ' | '.join(value.replace('|', '\\|') for value in values) + ' |')
            page.append('')
        (style_dir / (style + '.md')).write_text('\n'.join(page) + '\n', encoding='utf-8')
    lines += ['', '风格与功能均可多标签，分组数量不可相加当作去重总数。', '', '## 按素材类型', '',
              '| 类型 | 数量 |', '| --- | ---: |']
    lines.extend(f'| {kind} | {count} |' for kind, count in summary['countsByKind'].items())
    lines += ['', '## 功能入口', '', '| 功能候选 | 素材数（可重叠） |', '| --- | ---: |']
    function_dir = output / 'functions'
    function_dir.mkdir(exist_ok=True)
    for function, count in summary['countsByFunction'].items():
        lines.append(f'| [{function}](functions/{function}.md) | {count} |')
        page = [f'# {function}：名称分类候选', '', '[返回总览](../README.md)', '',
                '| 原名 | 风格候选 | 类型 | 来源系列 | 原始相对路径 |', '| --- | --- | --- | --- | --- |']
        for row in rows:
            if function in row['functions']:
                values = [row['name'], '、'.join(row['styles']), row['assetKind'], row['sourceSeries'], row['relativePath']]
                page.append('| ' + ' | '.join(value.replace('|', '\\|') for value in values) + ' |')
        (function_dir / (function + '.md')).write_text('\n'.join(page) + '\n', encoding='utf-8')
    (output / 'README.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    summary = build(args.source, args.output)
    from audit_construction_coverage import build as audit_coverage
    audit_coverage(args.output)
    print(json.dumps(summary, ensure_ascii=True))


if __name__ == '__main__':
    main()
