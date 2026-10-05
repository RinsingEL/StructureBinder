"""Adapt a second-question city into an isolated D3-only experiment input.

Does not select a new location, approve T4/D3, or modify the source run.
Uses the experiment's existing scale validation; D3 verifies world identity
and design bounds using the production implementation.
"""
import argparse
import hashlib
import json
import shutil
from pathlib import Path


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--answer', type=Path, required=True)
    parser.add_argument('--validation', type=Path, required=True)
    parser.add_argument('--source-run', type=Path, required=True)
    parser.add_argument('--city-id', required=True)
    parser.add_argument('--output-run', type=Path, required=True)
    args = parser.parse_args()
    answer = read(args.answer)
    matches = [c for c in answer['cities'] if c['id'] == args.city_id]
    if len(matches) != 1:
        raise ValueError('Expected exactly one matching city')
    city = matches[0]
    checks = [c for c in read(args.validation)['cities'] if c['name'] == city['name']]
    if len(checks) != 1 or checks[0]['outsideOwnedCoarseCells'] != 0:
        raise ValueError('Expected an existing successful coarse territory check')
    source_files = ['world_survey_context.json', 'world_survey_manifest.json', 'realm_territory_map.json']
    sources = {name: read(args.source_run / name) for name in source_files}
    if sources['world_survey_manifest.json']['config']['cellStepBlocks'] != 128:
        raise ValueError('This adapter supports the second-question 128-block W dataset only')
    scale = city['theoreticalScale']
    radius_cells = 4 if city['capital'] else {'hamlet': 1, 'village': 1, 'outpost': 1, 'town': 2, 'city': 3, 'large_city': 3}[scale]
    radius = checks[0]['radiusBlocks']
    x, z = city['anchorBlock']['x'], city['anchorBlock']['z']
    if any(type(v) is not int for v in [x, z, radius]) or radius <= 0:
        raise ValueError('Coordinates and radius must be integers')
    provenance = {
        'mode': 'second_question_d3_only', 'sourceRun': str(args.source_run.resolve()),
        'answer': str(args.answer.resolve()), 'answerSha256': hashlib.sha256(args.answer.read_bytes()).hexdigest(),
        'validationSha256': hashlib.sha256(args.validation.read_bytes()).hexdigest(),
        'sourceCity': city, 'siteReviewStatus': 'not_reviewed',
        'note': 'Explicit experiment input, not a T4 selection or approval. Upstream files are unchanged copies; their IDs retain source-run provenance.'
    }
    seed = {
        'citySeedId': city['id'], 'realmId': answer['realmId'],
        'role': 'capital' if city['capital'] else city['seedRole'],
        'theoreticalScale': scale, 'anchorBlock': city['anchorBlock'],
        'planningRadiusCells': radius_cells,
        'designBounds': {'minX': x-radius, 'minZ': z-radius, 'maxX': x+radius, 'maxZ': z+radius},
        'source': {'sourceMode': 'second_question_experiment', 'answerSha256': provenance['answerSha256']}
    }
    args.output_run.mkdir(parents=False, exist_ok=False)
    for name in source_files:
        shutil.copy2(args.source_run / name, args.output_run / name)
    write(args.output_run / 'experiment_input_provenance.json', provenance)
    write(args.output_run / 'city_seed_registry.json', {'registryId': 'experiment_' + args.output_run.name, 'citySeeds': [seed]})
    print(json.dumps({'runId': args.output_run.name, 'citySeedId': city['id'], 'anchorBlock': city['anchorBlock'], 'scale': scale}, ensure_ascii=False))


if __name__ == '__main__':
    main()
