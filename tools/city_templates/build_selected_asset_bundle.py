"""Build a test author bundle from reviewed source assets and Minecraft runtime metadata.

Does not infer entrances, alter NBT, or install into existing worlds.
"""
import argparse
import hashlib
import json
import shutil
from pathlib import Path


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def digest(path):
    return 'sha256:' + hashlib.sha256(path.read_bytes()).hexdigest()


def build(source, metadata, baseline, output):
    config = read(source)
    rows = config['templates']
    metadata_rows = read(metadata)
    if isinstance(metadata_rows, dict):
        metadata_rows = metadata_rows['templates']
    assert all(r.get('readable', True) for r in metadata_rows)
    runtime = {r['templateRef']: r for r in metadata_rows}
    assert len(rows) == config['selectedCount'] == len(runtime)
    assert len({r['templateRef'] for r in rows}) == len(rows)
    assert not output.exists(), f'Output already exists: {output}'
    # Validate the entire selection before writing anything.
    for row in rows:
        assert row['categoryConfirmedByUser'] and row['styleConfirmedByUser'] and row['entrancesConfirmedByUser']
        assert digest(source.parent / row['sourceNbt']) == 'sha256:' + row['sourceSha256']
        assert runtime[row['templateRef']]['rawSize'] == row['rawSize']
        assert row['sourceDataVersion'] <= 3465
        assert row['noRoadEntrance'] == (not row['roadEntrances'])
        for port in row['roadEntrances']:
            x, z = port['position']['x'], port['position']['z']
            w, d = row['rawSize']['width'], row['rawSize']['depth']
            assert 0 <= x < w and 0 <= z < d
            assert {'NORTH': z == 0, 'SOUTH': z == d-1, 'WEST': x == 0, 'EAST': x == w-1}[port['direction']]
    output.mkdir(parents=True)
    catalog, manifest, entrances, profiles, refs = [], [], [], [], []
    terms = sorted({t for row in rows for t in row['functionTerms']})
    pools = {term: [] for term in terms}
    for row in rows:
        ref = row['templateRef']
        content_hash = runtime[ref]['templateHash']
        ns, resource = ref.split(':')
        relative = f'{ns}/structures/{resource}.nbt'
        target = output / 'city_template_content_pack' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source.parent / row['sourceNbt'], target)
        catalog.append(dict(buildingSemantic=row['buildingSemantic'], style='中世纪', templateId=ref,
            templateRef=ref, contentHash=content_hash, variant='selected_20260912', rawSize=row['rawSize'],
            allowedRotations=row['allowedRotations'], allowedMirrors=row['allowedMirrors'],
            roadEntrances=row['roadEntrances'], terrainPosePolicy=row['terrainPosePolicy'],
            supportPolicy='full_footprint_support', clearanceBlocks=0, frontagePolicy='FIXED_FRONT'))
        manifest.append(dict(templateRef=ref, sourceFile=relative, sourceSha256=digest(target),
            sourceIdentity=ref, converterId='litematic_to_nbt_verified_20260912'))
        entrances.append(dict(structureId=ref, reviewState='approved', contentHash=content_hash,
            captureDigest='sha256:' + row['entranceReview']['sourceSha256'], size=row['rawSize'],
            intent='no_connection' if row['noRoadEntrance'] else 'connect',
            note=row['entranceReview'].get('note') or ('用户在入口标注工具中明确标记无道路入口' if row['noRoadEntrance'] else '用户手工标记'),
            roadEntrances=[dict(entranceId=p['entranceId'], x=p['position']['x'], z=p['position']['z'], direction=p['direction']) for p in row['roadEntrances']]))
        profiles.append(dict(structureId=ref, sourceProfileRef='selected-assets://20260912/' + row['id'],
            reviewState='approved', functionTerms=row['functionTerms'], planningRoleTerms=[],
            terrainModes=['SURFACE'], styleTerms=row['styleTerms']))
        refs.append(dict(structureRef=ref, templateCandidates=[dict(templateId=ref, variantId='selected_20260912')]))
        for term in row['functionTerms']:
            pools[term].append(ref)
    write(output / 'template_catalog.json', dict(schema='city_template_catalog', templates=catalog))
    write(output / 'city_template_content_pack.json', dict(schema='city_template_content_pack.v0.1',
        packId='city_assets_selected_20260912_reviewed', catalogSha256=digest(output / 'template_catalog.json'), templates=manifest))
    write(output / 'StructureEntrances.approved.json', dict(schema='terrasense_approved_entrances.v1', structures=entrances))
    (output / 'StructureProfile.jsonl').write_text(''.join(json.dumps(p, ensure_ascii=False) + '\n' for p in profiles), encoding='utf-8')
    vocab = [dict(term_id=t, vocab_type=kind, label=t, aliases=[], status='approved') for kind, values in [('function', terms), ('style', ['中世纪'])] for t in values]
    write(output / 'StructureVocabulary.snapshot.json', dict(schemaVersion='terrasense_structure_vocabulary_snapshot.v0.1', snapshotId='selected_20260912_reviewed', terms=vocab))
    write(output / 'TerraSenseStructureProfileSource.official.json', dict(schema='terrasense_structure_profile_source',
        sourceType='structure_profile_jsonl', catalogMode='official', profilePath='StructureProfile.jsonl',
        vocabularySnapshotPath='StructureVocabulary.snapshot.json', terrasenseRunId='selected_20260912_reviewed',
        allowDebugUnapproved=False, entrancePolicy='reviewed_required'))
    references = read(baseline / 'blueprint_reference_catalog.json')
    references['structureRefs'] = refs
    references['fillPools'] = [dict(poolRef=f'pool:selected_category_{i+1:02}', structureRefs=pools[t]) for i,t in enumerate(terms)]
    references['styleProfiles'] = [dict(profileRef='style:medieval')]
    write(output / 'blueprint_reference_catalog.json', references)
    # Names and source provenance stay available to humans without unrecognized runtime fields.
    write(output / 'asset_names.json', [dict(templateRef=r['templateRef'], displayName=r['displayName'], functionTerms=r['functionTerms']) for r in rows])
    write(output / 'integration_verification.json', dict(status='built_pending_java_bundle_validation', templates=len(rows),
        withEntrance=sum(bool(r['roadEntrances']) for r in rows), noRoadEntrance=sum(r['noRoadEntrance'] for r in rows),
        metadataSource=str(metadata), rawNbtUnmodified=True, transforms='preserved_from_source', gameplayValidated=False))
    print(json.dumps(dict(output=str(output), templates=len(rows)), ensure_ascii=False))


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    for key in ('source', 'metadata', 'baseline', 'output'):
        p.add_argument('--' + key, type=Path, required=True)
    a = p.parse_args()
    build(a.source, a.metadata, a.baseline, a.output)
