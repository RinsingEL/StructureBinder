"""Export an isolated Studio test bundle; preserve all source NBT and author files.

Semantic approval here covers copying the user-selected Studio tags into the test
catalog only. Visual reviews are neither copied nor promoted. Projected entrances
use the runtime's explicit legacy_catalog mode until in-game entrance review.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
from export_core_atlas import export as export_core_atlas

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/structure_studio'))
from studio.model import read_structure
from studio.navigation import collision_boxes
from studio.frontage import runtime_frontage
from studio.grounding import resolve_ground_plane

STYLES = ('03_desert_stars', '05_forest_symbiosis', '07_arcane_academy')
EXCLUDE = {'DS-03-v01': '地下蓄水厅：首轮不验证地下接地',
           'DS-12-v01': '旧星井：首轮不验证高差接地'}
VARIANT = 'studio_test_20260924'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def digest(path):
    return 'sha256:' + hashlib.sha256(path.read_bytes()).hexdigest()


def ground_plane_y(author):
    """Read explicit local ground contact height; absence keeps legacy placement."""
    resolved = resolve_ground_plane(author)
    if resolved['status'] == 'invalid':
        raise ValueError(f"Invalid ground_plane: {author.get('id', '<unknown>')}; {resolved['message']}")
    return resolved['y']


def project_entrances(author, data, registry):
    """Project authored facing onto the footprint; reject obstructed body corridors.

    This checks clearance at authored entry height, not world road height/support.
    Hence the result is explicitly unreviewed and requires in-game road validation.
    """
    ports, evidence = [], []
    width, _, depth = data['size']
    shapes = [collision_boxes(block, registry) for block in data['palette']]
    grid = {tuple(b['pos']): shapes[b['state']] for b in data['blocks']}
    for point in author.get('points', []):
        if point['kind'] != 'entrance':
            continue
        x, y, z = point['pos']
        if not (0 <= x < width and 0 <= y < data['size'][1] and 0 <= z < depth):
            raise ValueError(f"Entrance out of bounds: {author['id']}")
        direction = point.get('facing', '').upper()
        if direction not in ('NORTH', 'SOUTH', 'WEST', 'EAST'):
            raise ValueError(f"Missing explicit entrance facing: {author['id']}")
        bx = 0 if direction == 'WEST' else width-1 if direction == 'EAST' else x
        bz = 0 if direction == 'NORTH' else depth-1 if direction == 'SOUTH' else z
        for cx in range(min(x, bx), max(x, bx)+1):
            for cz in range(min(z, bz), max(z, bz)+1):
                for cy in range(max(0, y-1), min(data['size'][1], y+3)):
                    for a,b,c,d,e,f in grid.get((cx,cy,cz), []):
                        if a < .8 and d > .2 and c < .8 and f > .2 and cy+b < y+1.8 and cy+e > y+1e-6:
                            raise ValueError(f"Obstructed projected entrance: {author['id']} {point['id']} {(cx,cy,cz)}")
        ports.append(dict(entranceId=point['id'], position=dict(x=bx,z=bz), direction=direction))
        evidence.append(dict(authorPoint=point, projectedBoundary=[bx,bz],
                             clearanceChecked=True, worldRoadHeightValidated=False))
    if not ports:
        raise ValueError(f"No authored entrance: {author['id']}")
    return ports, evidence


def build(args):
    if args.output.exists():
        raise ValueError(f'Use a fresh output directory: {args.output}')
    registry = read(ROOT / 'tools/structure_studio/.cache/registry.json')
    rows, omitted, unmarked = [], [], []
    for style in (getattr(args, 'styles', None) or STYLES):
        directory = ROOT / 'asset_catalogs/original_civilizations' / style / 'models'
        if not directory.is_dir():
            raise ValueError(f'Missing style: {directory}')
        for author_file in sorted(directory.glob('*/author.json')):
            a = read(author_file)
            ground_y = ground_plane_y(a)
            if a['id'] in EXCLUDE:
                omitted.append(dict(id=a['id'], reason=EXCLUDE[a['id']]))
                continue
            nbt = author_file.parent / 'structure.nbt'
            data = read_structure(nbt)
            validation = read(author_file.parent / 'validation.json')
            if (not validation['passed'] or not validation.get('navigation', {}).get('passed')
                    or validation['nbt_sha256'] != data['sha256'] or a['nbt_sha256'] != data['sha256']
                    or a['size'] != data['size'] or data['data_version'] != 3465):
                raise ValueError(f'Source validation mismatch: {a["id"]}')
            if not getattr(args, 'all_previews', False) and a.get('preview_context', {}).get('kind') != 'flat':
                omitted.append(dict(id=a['id'],reason='首轮仅使用平地预览模型'))
                continue
            if (not a['function_terms'] or not a['civilization'] or a['planning_role'] not in
                    {f'planning_role.{r}' for r in ('key','anchor','fill','structure','self_contained')}):
                raise ValueError(f'Incomplete author tags: {a["id"]}')
            try:
                ports, evidence = project_entrances(a, data, registry)
            except ValueError as error:
                omitted.append(dict(id=a['id'], reason=str(error)))
                continue
            try:
                ports, frontage_policy = runtime_frontage(a, ports)
            except ValueError as error:
                unmarked.append(str(error))
                continue
            rows.append(dict(templateRef='studio:' + a['id'].lower(), sourceNbt=str(nbt),
                sourceSha256=digest(nbt), authorSha256=digest(author_file), author=a,
                rawSize=dict(zip(('width','height','depth'),data['size'])),
                roadEntrances=ports, frontagePolicy=frontage_policy, entranceEvidence=evidence,
                validationSha256=digest(author_file.parent / 'validation.json'),
                **({'groundPlaneY': ground_y} if ground_y is not None else {})))
    if unmarked:
        raise ValueError('请先在 Structure Studio 完成以下入口标注，再重新导出：\n' + '\n'.join(unmarked))
    if not rows:
        raise ValueError('Empty selection')
    args.output.mkdir(parents=True)
    write(args.output / 'codec_input.json', rows)
    cp = ';'.join(args.classpath_file.read_text(encoding='utf-8-sig').splitlines())
    subprocess.run([str(args.java), '-Dfile.encoding=UTF-8', '-cp', cp,
        str(ROOT / 'tools/city_templates/StudioTemplateMetadata.java'),
        str(args.output / 'codec_input.json'), str(args.output / 'runtime_metadata.json')], check=True)
    runtime = {r['templateRef']:r for r in read(args.output / 'runtime_metadata.json')}
    templates, payloads, profiles, refs, names = [], [], [], [], []
    pools = defaultdict(list)
    for row in rows:
        a, ref = row['author'], row['templateRef']
        if runtime[ref]['rawSize'] != row['rawSize']:
            raise ValueError(f'Runtime size mismatch: {ref}')
        relative = f"studio/structures/{ref.split(':')[1]}.nbt"
        target = args.output / 'city_template_content_pack' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(row['sourceNbt'], target)
        assert digest(target) == row['sourceSha256']
        templates.append(dict(buildingSemantic=a['function_terms'][0],style=a['civilization'],
            templateId=ref,templateRef=ref,contentHash=runtime[ref]['templateHash'],variant=VARIANT,
            rawSize=row['rawSize'],allowedRotations=['NONE','CLOCKWISE_90','CLOCKWISE_180','COUNTERCLOCKWISE_90'],
            allowedMirrors=['NONE'],roadEntrances=row['roadEntrances'],
            terrainPosePolicy='structure_start_beard_thin', supportPolicy='full_footprint_support',
            clearanceBlocks=0,frontagePolicy=row['frontagePolicy'],
            **({'groundPlaneY': row['groundPlaneY']} if 'groundPlaneY' in row else {})))
        payloads.append(dict(templateRef=ref,sourceFile=relative,sourceSha256=row['sourceSha256'],
            sourceIdentity=f"structure-studio:{a['id']}",converterId='studio_byte_exact_test_export_v1'))
        profiles.append(dict(structureId=ref,sourceProfileRef=f"structure-studio://{a['id']}",
            reviewState='approved',functionTerms=a['function_terms'],planningRoleTerms=[a['planning_role']],
            terrainModes=['SURFACE'],styleTerms=[a['civilization']]))
        refs.append(dict(structureRef=ref,templateCandidates=[dict(templateId=ref,variantId=VARIANT)]))
        if a['planning_role'] == 'planning_role.fill':
            for term in a['function_terms']:
                pools[(a['civilization'],term)].append(ref)
        names.append(dict(templateRef=ref,displayName=a['name'],functionTerms=a['function_terms'],
            style=a['civilization'],planningRole=a['planning_role'],siteConditions=a.get('terrain',{})))
    write(args.output / 'template_catalog.json',dict(schema='city_template_catalog',templates=templates))
    write(args.output / 'city_template_content_pack.json',dict(schema='city_template_content_pack.v0.1',
        packId=VARIANT,catalogSha256=digest(args.output/'template_catalog.json'),templates=payloads))
    (args.output/'StructureProfile.jsonl').write_text(''.join(json.dumps(p,ensure_ascii=False)+'\n' for p in profiles),encoding='utf-8')
    vocab = [dict(term_id=t,vocab_type=kind,label=t,aliases=[],status='approved')
        for kind,key in [('function','functionTerms'),('style','styleTerms'),('planning_role','planningRoleTerms')]
        for t in sorted({v for p in profiles for v in p[key]})]
    write(args.output/'StructureVocabulary.snapshot.json',dict(schemaVersion='terrasense_structure_vocabulary_snapshot.v0.1',snapshotId=VARIANT,terms=vocab))
    write(args.output/'TerraSenseStructureProfileSource.official.json',dict(schema='terrasense_structure_profile_source',
        sourceType='structure_profile_jsonl',catalogMode='official',profilePath='StructureProfile.jsonl',
        vocabularySnapshotPath='StructureVocabulary.snapshot.json',terrasenseRunId=VARIANT,
        allowDebugUnapproved=False,entrancePolicy='legacy_catalog'))
    references = read(args.baseline/'blueprint_reference_catalog.json')
    references['structureRefs'] = refs
    references['fillPools'] = [dict(poolRef=f'pool:studio_{i:03}',structureRefs=values)
                              for i,(_,values) in enumerate(sorted(pools.items()),1)]
    references['styleProfiles'] = [dict(profileRef='style:studio_'+style) for style in (getattr(args, 'styles', None) or ('desert','forest','arcane'))]
    write(args.output/'blueprint_reference_catalog.json',references)
    write(args.output/'asset_names.json',names)
    # Portable provenance: no absolute developer paths in the delivered bundle.
    for row in rows:
        row['sourceNbt'] = Path(row['sourceNbt']).relative_to(ROOT).as_posix()
    ground_unmarked = [row['author']['id'] for row in rows if 'groundPlaneY' not in row]
    ground_coverage = dict(markedCount=len(rows)-len(ground_unmarked),
                           unmarkedCount=len(ground_unmarked), unmarkedIds=ground_unmarked)
    write(args.output/'studio_export_provenance.json',dict(schema='studio_test_export.v1',templates=rows,
        omitted=omitted,styleCounts=dict(Counter(r['author']['civilization'] for r in rows)),
        groundPlaneCoverage=ground_coverage,
        semanticReviewScope='仅核对已选 Studio 作者标签到测试目录的逐字段映射；不代表原模型图审通过',
        entranceAuthority='legacy_catalog_unreviewed',
        entranceReviewScope='沿作者标明朝向投影至边界并检查净空；未验证世界道路高度与接地',
        cachedNavigationVerifiedByNbtHash=True,gameplayValidated=False,
        siteConditionsRuntimeSupported=False,rawNbtUnmodified=True))
    # This file was only an input to the local codec process; retain portable form.
    write(args.output/'codec_input.json', [dict(templateRef=r['templateRef'],sourceNbt=r['sourceNbt'],sourceSha256=r['sourceSha256'],
        **({'groundPlaneY': r['groundPlaneY']} if 'groundPlaneY' in r else {})) for r in rows])
    export_core_atlas(args.output)
    print(json.dumps(dict(output=str(args.output),selected=len(rows),styles=dict(Counter(r['author']['civilization'] for r in rows)),
        omitted=omitted,groundPlaneCoverage=ground_coverage),ensure_ascii=False))


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ('output','baseline','classpath-file','java'):
        parser.add_argument('--'+field,type=Path,required=True)
    parser.add_argument('--styles', nargs='+', help='Explicit catalog directory names')
    parser.add_argument('--all-previews', action='store_true', help='Include non-flat preview scenes for terrain testing; preview is not runtime terrain admission')
    build(parser.parse_args())
