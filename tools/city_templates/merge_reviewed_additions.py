"""Merge an explicitly approved addition batch without changing existing template records."""
import argparse
import copy
import json
import re
import shutil
from pathlib import Path
from curate_planning_roles import classify, curate, FILL, KEY, STRUCTURE, COMPLETE
from build_selected_asset_bundle import read, write, digest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ('baseline', 'source', 'annotations', 'metadata', 'output'):
        parser.add_argument('--' + key, type=Path, required=True)
    parser.add_argument('--pool-prefix', default='selected_additions_20260919')
    parser.add_argument('--pack-id', default='city_assets_selected_93_reviewed_20260919')
    a = parser.parse_args()
    assert not a.output.exists(), 'Output must be a fresh directory'
    config, marks = read(a.source), read(a.annotations)
    assert marks['schema'] == 'city_entrance_annotations.v1'
    rows = config['templates']
    assert all(re.fullmatch(r'[a-z0-9_.-]+:[a-z0-9_./-]+', r['templateRef']) for r in rows), 'Invalid Minecraft resource ID'
    by_id = {r['id']: r for r in rows}
    assert len(by_id) == len(rows)
    assert len(marks['templates']) == len(rows)
    assert {r['id'] for r in marks['templates']} == set(by_id)
    metadata = read(a.metadata)
    runtime = {r['templateRef']: r for r in metadata}
    assert set(runtime) == {r['templateRef'] for r in rows}
    catalog = read(a.baseline / 'template_catalog.json')
    pack = read(a.baseline / 'city_template_content_pack.json')
    entrances = read(a.baseline / 'StructureEntrances.approved.json')
    references = read(a.baseline / 'blueprint_reference_catalog.json')
    vocab = read(a.baseline / 'StructureVocabulary.snapshot.json')
    names = read(a.baseline / 'asset_names.json')
    profiles = [json.loads(line) for line in (a.baseline / 'StructureProfile.jsonl').read_text(encoding='utf-8-sig').splitlines() if line.strip()]
    baseline_rows = copy.deepcopy(catalog['templates'])
    old_refs = {r['templateRef'] for r in baseline_rows}
    assert old_refs.isdisjoint(runtime)
    for mark in marks['templates']:
        row = by_id[mark['id']]
        assert mark['reviewed'] is True
        assert mark['templateRef'] == row['templateRef']
        assert mark['sourceSha256'] == row['sourceSha256']
        assert digest(a.source.parent / row['sourceNbt']) == 'sha256:' + mark['sourceSha256']
        assert runtime[row['templateRef']]['rawSize'] == row['rawSize']
        assert row['sourceDataVersion'] <= 3465
        assert row['styleTerms'] and row['functionTerms']
        ports = mark['roadEntrances']
        assert type(mark['noRoadEntrance']) is bool and mark['noRoadEntrance'] == (not ports)
        assert len({p['entranceId'] for p in ports}) == len(ports)
        for p in ports:
            x, z = p['position']['x'], p['position']['z']
            w, d = row['rawSize']['width'], row['rawSize']['depth']
            assert type(x) is int and type(z) is int and 0 <= x < w and 0 <= z < d
            assert {'NORTH': z == 0, 'SOUTH': z == d-1, 'WEST': x == 0, 'EAST': x == w-1}.get(p['direction'], False)
        row.update(roadEntrances=ports, noRoadEntrance=mark['noRoadEntrance'],
            entrancesConfirmedByUser=True, categoryConfirmedByUser=True, styleConfirmedByUser=True,
            runtimeContentHash=runtime[row['templateRef']]['templateHash'], runtimeValidated=True,
            runtimeValidationScope='offline_minecraft_template_codec_and_registry', pendingFields=[],
            entranceReview=dict(status='user_reviewed', source=str(a.annotations.resolve()),
                sourceSha256=digest(a.annotations).removeprefix('sha256:'), note=mark.get('note', '')))
    shutil.copytree(a.baseline, a.output)
    for row in rows:
        ref = row['templateRef']
        variant = ref.split(':')[1].split('/')[0]
        content_hash = runtime[ref]['templateHash']
        rotations = ['NONE', 'CLOCKWISE_90', 'CLOCKWISE_180', 'COUNTERCLOCKWISE_90']
        row.update(allowedRotations=rotations, allowedMirrors=['NONE'], terrainPosePolicy='structure_start_beard_thin')
        catalog['templates'].append(dict(buildingSemantic=row['buildingSemantic'], style=row['styleTerms'][0],
            templateId=ref, templateRef=ref, contentHash=content_hash, variant=variant,
            rawSize=row['rawSize'], allowedRotations=rotations, allowedMirrors=['NONE'],
            roadEntrances=row['roadEntrances'], terrainPosePolicy='structure_start_beard_thin',
            supportPolicy='full_footprint_support', clearanceBlocks=0, frontagePolicy='FIXED_FRONT'))
        namespace, resource = ref.split(':')
        relative = f'{namespace}/structures/{resource}.nbt'
        target = a.output / 'city_template_content_pack' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(a.source.parent / row['sourceNbt'], target)
        pack['templates'].append(dict(templateRef=ref, sourceFile=relative, sourceSha256=digest(target),
            sourceIdentity=ref, converterId='litematic_to_nbt_verified_20260919'))
        entrances['structures'].append(dict(structureId=ref, reviewState='approved', contentHash=content_hash,
            captureDigest=digest(a.annotations), size=row['rawSize'], intent='no_connection' if row['noRoadEntrance'] else 'connect',
            note=row['entranceReview']['note'] or '用户手工标记', roadEntrances=[dict(entranceId=p['entranceId'],
                x=p['position']['x'], z=p['position']['z'], direction=p['direction']) for p in row['roadEntrances']]))
        profiles.append(dict(structureId=ref, sourceProfileRef='selected-assets://20260919/' + row['id'],
            reviewState='approved', functionTerms=row['functionTerms'], planningRoleTerms=row.get('planningRoleTerms') or classify(row['displayName'], row['functionTerms'], row['rawSize'])[0], terrainModes=['SURFACE'], styleTerms=row['styleTerms']))
        references['structureRefs'].append(dict(structureRef=ref, templateCandidates=[dict(templateId=ref, variantId=variant)]))
        names.append(dict(templateRef=ref, displayName=row['displayName'], functionTerms=row['functionTerms']))
    terms = sorted({term for row in rows for term in row['functionTerms']})
    existing_terms = {(t['vocab_type'], t['term_id']) for t in vocab['terms']}
    for index, term in enumerate(terms, 1):
        if ('function', term) not in existing_terms:
            vocab['terms'].append(dict(term_id=term, vocab_type='function', label=term, aliases=[], status='approved'))
        pool_ref = f'pool:{a.pool_prefix}_{index:02}'
        assert all(p['poolRef'] != pool_ref for p in references['fillPools'])
        fill_refs = {p['structureId'] for p in profiles if FILL in p['planningRoleTerms'] and not {KEY, COMPLETE, 'planning_role.anchor'} & set(p['planningRoleTerms'])}
        references['fillPools'].append(dict(poolRef=pool_ref, structureRefs=[r['templateRef'] for r in rows if term in r['functionTerms'] and r['templateRef'] in fill_refs]))
    for role in (FILL, KEY, STRUCTURE, COMPLETE):
        if ('planning_role', role) not in existing_terms:
            vocab['terms'].append(dict(term_id=role, vocab_type='planning_role', label=role, aliases=[], status='approved'))
    for term in sorted({term for row in rows for term in row['styleTerms']}):
        if ('style', term) not in existing_terms:
            vocab['terms'].append(dict(term_id=term, vocab_type='style', label=term, aliases=[], status='approved'))
    write(a.output / 'template_catalog.json', catalog)
    pack.update(packId=a.pack_id, catalogSha256=digest(a.output / 'template_catalog.json'))
    for file, value in [('city_template_content_pack.json', pack), ('StructureEntrances.approved.json', entrances),
                        ('blueprint_reference_catalog.json', references), ('StructureVocabulary.snapshot.json', vocab), ('asset_names.json', names)]:
        write(a.output / file, value)
    (a.output / 'StructureProfile.jsonl').write_text(''.join(json.dumps(p, ensure_ascii=False) + '\n' for p in profiles), encoding='utf8')
    curate(a.output, a.output / 'planning_role_audit.json', apply=True)
    meta = read(a.baseline / 'runtime_metadata.json')
    meta['templates'].extend(metadata)
    write(a.output / 'runtime_metadata.json', meta)
    assert catalog['templates'][:len(baseline_rows)] == baseline_rows
    expected = {r['templateRef'] for r in catalog['templates']}
    for values in [pack['templates'], names]:
        assert {r['templateRef'] for r in values} == expected and len(values) == len(expected)
    assert {r['structureId'] for r in profiles} == expected
    assert {r['structureId'] for r in entrances['structures']} == expected
    assert {r['structureRef'] for r in references['structureRefs']} == expected
    for item in pack['templates']:
        assert digest(a.output / 'city_template_content_pack' / item['sourceFile']) == item['sourceSha256']
    write(a.output / 'integration_verification.json', dict(status='merged_pending_java_validation',
        templates=len(expected), originalTemplatesUnchanged=len(baseline_rows), additions=len(rows),
        withEntrance=sum(bool(r['roadEntrances']) for r in catalog['templates']),
        noRoadEntrance=sum(not r['roadEntrances'] for r in catalog['templates']), gameplayValidated=False,
        annotationSource=str(a.annotations.resolve()), annotationSha256=digest(a.annotations)))
    # Keep reviewed author data beside the source; never rewrite the original export.
    reviewed = a.source.with_name('city_assets.reviewed.json')
    assert not reviewed.exists()
    config['status'] = 'reviewed_offline_metadata_validated'
    write(reviewed, config)
    annotation_copy = a.source.parent.parent / a.annotations.name
    if a.annotations.resolve() != annotation_copy.resolve():
        shutil.copy2(a.annotations, annotation_copy)
    print(f'Merged {len(baseline_rows)} + {len(rows)} = {len(expected)} templates: {a.output}')


if __name__ == '__main__':
    main()
