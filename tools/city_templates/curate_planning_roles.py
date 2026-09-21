"""Curate repeatable candidates from reviewed functions plus actual footprint dimensions.

Roles are conservative initial planning policy, not a claim of visual review. Unknown structures
remain directly selectable; complete-composition exemptions require explicit per-template evidence.
"""
import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

FILL = 'planning_role.fill'
KEY = 'planning_role.key'
STRUCTURE = 'planning_role.structure'
COMPLETE = 'planning_role.self_contained'
CORE = {'行政', '公共建筑', '教育文化', '宗教', '宫殿', '海岸灯塔', '体育娱乐'}
POLICY = 'name_function_and_scale_v2'
STYLE_NAMES = {'中世纪':'medieval','东方':'oriental','日式':'japanese','沙漠':'desert','北欧':'nordic',
               '中式':'chinese','奇幻':'fantasy','哥特':'gothic','古典':'classical','宫廷':'palatial',
               '海洋':'oceanic','现代':'modern','遗迹':'ruins','蒸汽朋克':'steampunk','东南亚':'southeast_asian'}


def usage_group(name, functions):
    terms = set(functions)
    if terms & CORE or any(word in name for word in ('神龛', '祭祀金字塔')):
        return 'core'
    if any(word in name for word in ('花园', '水车', '演讲台', '喷泉', '游泳池')):
        return 'special'
    if terms & {'商业', '商业工坊', '工坊生产', '餐饮', '住宿'} or any(word in name for word in ('烘焙房', '商人小屋')):
        return 'commerce'
    if terms & {'农业仓储', '农业', '农田'} or any(word in name for word in ('草料棚', '草料小棚')):
        return 'agriculture'
    if terms & {'住宅', '房屋（用途未细分）'}:
        return 'housing'
    if '树木绿植' in terms:
        return 'vegetation'
    if '小型配套' in terms or name == '凉亭':
        return 'accessory'
    return 'special'


def footprint_scale(size):
    area = size['width'] * size['depth']
    longest = max(size['width'], size['depth'])
    for code, label, max_area, max_side in [('small', '小型', 144, 16), ('medium', '中型', 400, 24),
                                            ('large', '大型', 900, 36)]:
        if area <= max_area and longest <= max_side:
            return code, label
    return 'extra_large', '超大型'


def sized_name(original, size):
    return f"【{footprint_scale(size)[1]}·占地{size['width']}×{size['depth']}·高{size['height']}】{original}"


def height_class(size):
    return 'low' if size['height'] <= 12 else 'medium' if size['height'] <= 24 else 'tall' if size['height'] <= 40 else 'very_tall'


def classify(name, functions, size):
    """Use and name determine role; dimensions determine separate candidate groups."""
    usage = usage_group(name, functions)
    if usage == 'core':
        return [KEY], '功能主体；按阵列明确选择，不进入随机填充'
    if usage != 'special':
        return [FILL], '可重复用途；尺寸独立分组，大型住宅同样可作填充'
    return [STRUCTURE], '用途或重复适用性不足；保留明确选材，不自动推断核心'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def write_grouped_report(path, report):
    labels = {'core':'功能主体', 'housing':'住宅', 'commerce':'商铺餐饮与工坊', 'agriculture':'农业仓储',
              'vegetation':'树木绿植', 'accessory':'小型配套', 'special':'专项结构'}
    lines = ['# 素材角色与尺度分组', '', '按现有名字、用途和模板包围盒分组；不等同逐项视觉验收。', '',
             '核心表示功能主体；填充表示可重复使用，两者均不由大小决定。完整组合与用途不明的专项结构保留明确选用。', '',
             '占地档：小型≤144格且边长≤16；中型≤400且边长≤24；大型≤900且边长≤36；其余超大型。',
             '高度档：低≤12，中≤24，高≤40，超高>40。宽深包含素材自带装饰和留白，不代表室内净面积。', '',
             f"共 {report['total']} 项；核心 {report['counts'].get(KEY,0)}，填充 {report['counts'].get(FILL,0)}，明确选用 {report['counts'].get(STRUCTURE,0)}。", '']
    for role, title in [(KEY,'核心'), (FILL,'填充'), (STRUCTURE,'明确选用')]:
        lines += [f'## {title}', '']
        for usage, label in labels.items():
            rows = [row for row in report['assignments'] if role in row['planningRoleTerms'] and row['usageGroup']==usage]
            if not rows:
                continue
            lines += [f'### {label}（{len(rows)}）', '', '| 展示名称 | 风格 | 模板ID |', '| --- | --- | --- |']
            for row in sorted(rows, key=lambda r:(r['rawSize']['width']*r['rawSize']['depth'],r['rawSize']['height'],r['templateRef'])):
                lines.append(f"| {row['displayName']} | {'、'.join(row['styleTerms'])} | `{row['templateRef']}` |")
            lines += ['']
    path.write_text('\n'.join(lines), encoding='utf-8')


def curate(bundle, output, apply=False, overrides=None):
    profiles = [json.loads(line) for line in (bundle / 'StructureProfile.jsonl').read_text(encoding='utf-8-sig').splitlines() if line.strip()]
    names = {row['templateRef']: row for row in read(bundle / 'asset_names.json')}
    templates = {row['templateRef']: row for row in read(bundle / 'template_catalog.json')['templates']}
    references = read(bundle / 'blueprint_reference_catalog.json')
    vocabulary = read(bundle / 'StructureVocabulary.snapshot.json')
    overrides = read(overrides) if overrides else {}
    previous = read(output) if output.exists() else {}
    generated = {row['templateRef']: [row['planningRoleTerms'], row.get('previousPlanningRoleTerms', [])]
                 for row in previous.get('assignments', []) if row.get('roleSource') != 'preserved'}
    if previous.get('policy') not in ('conservative_function_and_footprint_v1', POLICY):
        generated = {}
    assignments = []
    for profile in profiles:
        ref = profile['structureId']
        size = templates[ref]['rawSize']
        name = names[ref].get('originalDisplayName', names[ref]['displayName'])
        roles, reason = classify(name, profile['functionTerms'], size)
        # Migrate known generated roles, but preserve edits made outside the last audit.
        existing = profile.get('planningRoleTerms', [])
        role_source = 'inferred'
        if existing and existing != roles and existing not in generated.get(ref, []):
            roles, reason = existing, '保留已有显式角色标记'
            role_source = 'preserved'
        if ref in overrides:
            override = overrides[ref]
            if override['contentHash'] != templates[ref]['contentHash']:
                raise ValueError('Reviewed template content drift: ' + ref)
            roles, reason = override['planningRoleTerms'], override['evidence']
            role_source = 'override'
        profile['planningRoleTerms'] = roles
        names[ref].update(originalDisplayName=name, displayName=sized_name(name, size))
        assignments.append(dict(templateRef=ref, originalDisplayName=name, displayName=names[ref]['displayName'],
                                contentHash=templates[ref]['contentHash'], rawSize=size, styleTerms=profile['styleTerms'],
                                functionTerms=profile['functionTerms'], usageGroup=usage_group(name, profile['functionTerms']),
                                footprintScale=footprint_scale(size)[0], heightClass=height_class(size), roleSource=role_source,
                                previousPlanningRoleTerms=existing,
                                planningRoleTerms=roles, evidence=reason))
    allowed = {row['templateRef'] for row in assignments if FILL in row['planningRoleTerms']
               and not {KEY, 'planning_role.anchor', COMPLETE} & set(row['planningRoleTerms'])}
    for pool in references['fillPools']:
        pool['structureRefs'] = [ref for ref in pool['structureRefs'] if ref in allowed]
    # Scale-oriented pools expose existing small shops and stalls independently of coarse categories.
    support_pools = []
    for pool_prefix, max_area in [('pool:public_small_support', 144), ('pool:public_medium_support', 400)]:
        references['fillPools'] = [pool for pool in references['fillPools'] if not pool['poolRef'].startswith(pool_prefix)]
        for style in sorted({s for row in assignments for s in row['styleTerms']}):
            refs = [row['templateRef'] for row in assignments if row['templateRef'] in allowed and style in row['styleTerms']
                    and row['rawSize']['width'] * row['rawSize']['depth'] <= max_area
                    and row['rawSize']['height'] <= 24
                    and not {'农业', '农田', '树木绿植'} & set(names[row['templateRef']]['functionTerms'])]
            if not refs:
                continue
            suffix = '' if style == '中世纪' else '_' + STYLE_NAMES.get(style, hashlib.sha256(style.encode()).hexdigest()[:8])
            pool_ref = pool_prefix + suffix
            references['fillPools'].append(dict(poolRef=pool_ref, structureRefs=refs, maxCopiesPerStructurePerGroup=2))
            support_pools.append(dict(poolRef=pool_ref, style=style, maximumFootprintArea=max_area, candidateCount=len(refs)))
    # Rebuild from all profiles rather than filtered legacy pools: large housing must be reachable.
    references['fillPools'] = [pool for pool in references['fillPools'] if not pool['poolRef'].startswith('pool:scaled_')]
    grouped = {}
    for row in assignments:
        if row['templateRef'] not in allowed:
            continue
        for style in row['styleTerms']:
            key = (row['usageGroup'], style, row['footprintScale'], row['heightClass'])
            grouped.setdefault(key, []).append(row['templateRef'])
    scale_pools = []
    for (usage, style, scale, height), refs in sorted(grouped.items()):
        style_id = STYLE_NAMES.get(style, hashlib.sha256(style.encode()).hexdigest()[:8])
        pool_ref = f'pool:scaled_{usage}_{style_id}_{scale}_{height}'
        references['fillPools'].append(dict(poolRef=pool_ref, structureRefs=refs, maxCopiesPerStructurePerGroup=2))
        scale_pools.append(dict(poolRef=pool_ref, usageGroup=usage, style=style, footprintScale=scale, heightClass=height, candidateCount=len(refs)))
    known = {term['term_id'] for term in vocabulary['terms']}
    for role in (KEY, FILL, STRUCTURE, COMPLETE):
        if role not in known:
            vocabulary['terms'].append(dict(term_id=role, vocab_type='planning_role', label=role, aliases=[], status='approved'))
    report = dict(schema='city_planning_role_audit.v1', policy=POLICY,
                  visualReviewComplete=False, assignments=assignments,
                  counts=dict(Counter(role for row in assignments for role in row['planningRoleTerms'])),
                  repeatableCount=len(allowed), total=len(assignments), supportPools=support_pools, scalePools=scale_pools)
    output.parent.mkdir(parents=True, exist_ok=True)
    write(output, report)
    write_grouped_report(output.with_suffix('.md'), report)
    if apply:
        (bundle / 'StructureProfile.jsonl').write_text(''.join(json.dumps(p, ensure_ascii=False) + '\n' for p in profiles), encoding='utf-8')
        write(bundle / 'blueprint_reference_catalog.json', references)
        write(bundle / 'StructureVocabulary.snapshot.json', vocabulary)
        write(bundle / 'asset_names.json', list(names.values()))
    print(json.dumps({k: report[k] for k in ('counts', 'repeatableCount', 'total')}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--overrides', type=Path)
    args = parser.parse_args()
    curate(args.bundle, args.report, args.apply, args.overrides)
