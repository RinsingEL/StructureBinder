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
REPEATABLE = {'住宅', '房屋（用途未细分）', '商业工坊', '商业', '工坊生产', '农业仓储',
              '农业', '农田', '小型配套', '树木绿植'}
CORE = {'行政', '公共建筑', '教育文化', '宗教', '宫殿', '海岸灯塔', '体育娱乐'}


def classify(name, functions, size):
    """Size limits repeatability; it never establishes importance or self-contained composition."""
    terms = set(functions)
    area = size['width'] * size['depth']
    if terms & CORE:
        return [KEY], '功能主体；按阵列明确选择，不进入随机填充'
    if terms & REPEATABLE and area <= 625 and max(size['width'], size['depth']) <= 32:
        if any(token in name for token in ('花园', '水车', '神龛', '演讲台')):
            return [STRUCTURE], '专项结构；保留明确选材，避免重复填充'
        return [FILL], '可重复用途且实际占地适合配套；大小仍由阵列选择'
    return [STRUCTURE], '用途或重复适用性不足；保留明确选材，不自动推断核心'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def curate(bundle, output, apply=False, overrides=None):
    profiles = [json.loads(line) for line in (bundle / 'StructureProfile.jsonl').read_text(encoding='utf-8-sig').splitlines() if line.strip()]
    names = {row['templateRef']: row for row in read(bundle / 'asset_names.json')}
    templates = {row['templateRef']: row for row in read(bundle / 'template_catalog.json')['templates']}
    references = read(bundle / 'blueprint_reference_catalog.json')
    vocabulary = read(bundle / 'StructureVocabulary.snapshot.json')
    overrides = read(overrides) if overrides else {}
    assignments = []
    for profile in profiles:
        ref = profile['structureId']
        size = templates[ref]['rawSize']
        roles, reason = classify(names[ref]['displayName'], profile['functionTerms'], size)
        # Authored explicit roles take precedence over this fallback policy on future reruns.
        existing = profile.get('planningRoleTerms', [])
        if existing and existing != roles:
            roles, reason = existing, '保留已有显式角色标记'
        if ref in overrides:
            override = overrides[ref]
            if override['contentHash'] != templates[ref]['contentHash']:
                raise ValueError('Reviewed template content drift: ' + ref)
            roles, reason = override['planningRoleTerms'], override['evidence']
        profile['planningRoleTerms'] = roles
        assignments.append(dict(templateRef=ref, displayName=names[ref]['displayName'], rawSize=size, styleTerms=profile['styleTerms'],
                                planningRoleTerms=roles, evidence=reason))
    allowed = {row['templateRef'] for row in assignments if FILL in row['planningRoleTerms']
               and not {KEY, 'planning_role.anchor', COMPLETE} & set(row['planningRoleTerms'])}
    for pool in references['fillPools']:
        pool['structureRefs'] = [ref for ref in pool['structureRefs'] if ref in allowed]
    # Scale-oriented pools expose existing small shops and stalls independently of coarse categories.
    support_pools = []
    style_names = {'中世纪':'medieval','东方':'oriental','日式':'japanese','沙漠':'desert','北欧':'nordic',
                   '中式':'chinese','奇幻':'fantasy','哥特':'gothic','古典':'classical','宫廷':'palatial',
                   '海洋':'oceanic','现代':'modern','遗迹':'ruins','蒸汽朋克':'steampunk','东南亚':'southeast_asian'}
    for pool_prefix, max_area in [('pool:public_small_support', 144), ('pool:public_medium_support', 400)]:
        references['fillPools'] = [pool for pool in references['fillPools'] if not pool['poolRef'].startswith(pool_prefix)]
        for style in sorted({s for row in assignments for s in row['styleTerms']}):
            refs = [row['templateRef'] for row in assignments if row['templateRef'] in allowed and style in row['styleTerms']
                    and row['rawSize']['width'] * row['rawSize']['depth'] <= max_area
                    and not {'农业', '农田', '树木绿植'} & set(names[row['templateRef']]['functionTerms'])]
            if not refs:
                continue
            suffix = '' if style == '中世纪' else '_' + style_names.get(style, hashlib.sha256(style.encode()).hexdigest()[:8])
            pool_ref = pool_prefix + suffix
            references['fillPools'].append(dict(poolRef=pool_ref, structureRefs=refs, maxCopiesPerStructurePerGroup=2))
            support_pools.append(dict(poolRef=pool_ref, style=style, maximumFootprintArea=max_area, candidateCount=len(refs)))
    known = {term['term_id'] for term in vocabulary['terms']}
    for role in (KEY, FILL, STRUCTURE, COMPLETE):
        if role not in known:
            vocabulary['terms'].append(dict(term_id=role, vocab_type='planning_role', label=role, aliases=[], status='approved'))
    report = dict(schema='city_planning_role_audit.v1', policy='conservative_function_and_footprint_v1',
                  visualReviewComplete=False, assignments=assignments,
                  counts=dict(Counter(role for row in assignments for role in row['planningRoleTerms'])),
                  repeatableCount=len(allowed), total=len(assignments), supportPools=support_pools)
    output.parent.mkdir(parents=True, exist_ok=True)
    write(output, report)
    if apply:
        (bundle / 'StructureProfile.jsonl').write_text(''.join(json.dumps(p, ensure_ascii=False) + '\n' for p in profiles), encoding='utf-8')
        write(bundle / 'blueprint_reference_catalog.json', references)
        write(bundle / 'StructureVocabulary.snapshot.json', vocabulary)
    print(json.dumps({k: report[k] for k in ('counts', 'repeatableCount', 'total')}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--overrides', type=Path)
    args = parser.parse_args()
    curate(args.bundle, args.report, args.apply, args.overrides)
