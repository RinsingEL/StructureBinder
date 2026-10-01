"""Backfill this selected bundle from explicit builder datums, with geometry evidence.

Never infer a datum from a preview or entrance. The builder must already author it.
The dry run checks access from a level exterior street using collision shapes.
Existing ground_plane fields are preserved. Source files are backed up before apply.
"""
import argparse
import copy
import hashlib
import importlib
import json
import math
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/structure_studio'))
from studio.build import BUILDERS
from studio.grounding import resolve_ground_plane
from studio.model import read_structure
from studio.navigation import collision_boxes
from studio.validate import validate


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def builders():
    result = {key: fn for key, (_, fn) in BUILDERS.items()}
    for module in ('arcane_life', 'arcane_crafts', 'steppe_life',
                   'tidal_life', 'tidal_crafts', 'tidal_gardens', 'memorial_lanterns'):
        result.update(importlib.import_module('studio.' + module).BUILDERS)
    return result


def outside_access(data, point, ground_y, registry):
    """Straight entrance approach over an otherwise level dry exterior street.

    Unknown cells below the datum retain terrain; explicit NBT air stays empty.
    Match Studio's 0.6x1.8 body, <=0.6 step and opened wooden door assumptions.
    This checks the entrance approach, not whole-building or Minecraft pathfinding.
    """
    palette = [collision_boxes(b, registry) for b in data['palette']]
    grid = {tuple(b['pos']): palette[b['state']] for b in data['blocks']}
    x, target_y, z = point['pos']
    dx, dz = {'north': (0,-1), 'south': (0,1), 'west': (-1,0), 'east': (1,0)}[point['facing'].lower()]
    width, _, depth = data['size']
    distance = x if dx == -1 else width-1-x if dx == 1 else z if dz == -1 else depth-1-z
    start_x, start_z = x+.5+dx*(distance+2), z+.5+dz*(distance+2)

    def stance(px, py, pz):
        overlap = []
        for bx in range(math.floor(px-.3), math.floor(px+.3)+1):
            for by in range(math.floor(py)-2, math.ceil(py+2.5)):
                for bz in range(math.floor(pz-.3), math.floor(pz+.3)+1):
                    shapes = grid.get((bx,by,bz), [(0,0,0,1,1,1)] if by < ground_y else [])
                    for a,b,c,d,e,f in shapes:
                        if bx+a < px+.3-1e-6 and bx+d > px-.3+1e-6 and bz+c < pz+.3-1e-6 and bz+f > pz-.3+1e-6:
                            overlap.append((by+b,by+e))
        tops = sorted({top for _,top in overlap if py-.6-1e-6 <= top <= py+.6+1e-6}, reverse=True)
        return next((top for top in tops if not any(lo < top+1.8-1e-6 and hi > top+1e-6 for lo,hi in overlap)), None)

    y = ground_y
    samples = []
    for i in range((distance+2)*4+1):
        px, pz = start_x-dx*i/4, start_z-dz*i/4
        next_y = stance(px,y,pz)
        if next_y is None:
            return dict(passed=False, point=point['id'], stoppedAt=[px,y,pz], reason='unsupported_or_blocked_step')
        y = next_y
        if not samples or samples[-1][1] != y:
            samples.append([i/4,y])
    return dict(passed=abs(y-target_y)<=.0625, point=point['id'], groundY=ground_y,
                reachedY=y, expectedY=target_y, heightTransitions=samples)


def plan(bundle, output, resume=False):
    if output.exists() and not resume:
        raise ValueError('Use a fresh evidence directory')
    output.mkdir(parents=True, exist_ok=resume)
    registry = read(ROOT / 'tools/structure_studio/.cache/registry.json')
    known = builders()
    rows = read(bundle / 'studio_export_provenance.json')['templates']
    report = dict(bundle=str(bundle.resolve()), scope='explicit_builder_datum_and_level_street_approach',
                  preserved=[], changes=[], errors=[], applied=False)
    completed = set()
    if resume:
        report = read(output/'plan.json')
        if report['bundle'] != str(bundle.resolve()) or report['applied']:
            raise ValueError('Resume requires an unapplied plan for the same bundle')
        completed = {r['id'] for r in report['changes'] + report['preserved']}
        report['errors'] = []
    for row in rows:
        if row['author']['id'] in completed:
            continue
        source = ROOT / row['sourceNbt']
        author_path = source.with_name('author.json')
        author = read(author_path)
        if 'ground_plane' in author:
            resolved = resolve_ground_plane(author)
            if resolved['status'] != 'marked':
                report['errors'].append(dict(id=author['id'], error=resolved['message']))
            report['preserved'].append(dict(id=author['id'], source=str(author_path.relative_to(ROOT)), sha256=digest(author_path)))
            continue
        try:
            if digest(source) != row['sourceSha256'].removeprefix('sha256:'):
                raise ValueError('Source NBT changed since selected bundle')
            if digest(author_path) != row['authorSha256'].removeprefix('sha256:'):
                raise ValueError('Author metadata changed since selected bundle')
            model = known[author['id']]()
            resolved = resolve_ground_plane(model.meta)
            if resolved['status'] != 'marked':
                raise ValueError('Builder does not explicitly author ground_plane')
            if list(model.size) != author['size']:
                raise ValueError('Builder geometry dimensions changed')
            data = read_structure(source)
            updated = copy.deepcopy(author)
            updated['ground_plane'] = copy.deepcopy(model.meta['ground_plane'])
            if author['id'] == 'TC-08-v01':
                updated['preview_context']['land_surface_y'] = 17
            changed_nbt = None
            if author['id'] in ('DS-11-v01','ML-11-v01'):
                temp = output / 'staged' / author['id']
                new_data = model.export(temp, registry)
                def blocks(d):
                    return {tuple(b['pos']): (d['palette'][b['state']], b.get('nbt')) for b in d['blocks']}
                before, after = blocks(data), blocks(new_data)
                delta = {p for p in before.keys() | after.keys() if before.get(p) != after.get(p)}
                expected = {(17,1,3),(18,1,3),(19,1,3)} if author['id']=='DS-11-v01' else {(x,y,1) for x in range(18,21) for y in range(3,9)}
                if delta != expected:
                    raise ValueError('Rebuild changes beyond the reviewed entrance correction: ' + str(sorted(delta)[:20]))
                updated['nbt_sha256'] = new_data['sha256']
                write(temp / 'author.json', updated)
                validation = validate(temp, registry, save=True)
                if not validation['passed'] or not validation['navigation']['passed'] or validation['warnings']:
                    raise ValueError('Revised structure validation failed: ' + str(validation))
                data = new_data
                changed_nbt = dict(stagedNbt=str((temp/'structure.nbt').resolve()), stagedValidation=str((temp/'validation.json').resolve()), sha256=data['sha256'], changedCells=sorted(delta))
            front = next((p for p in row['roadEntrances'] if p['entranceId']=='front'), row['roadEntrances'][0])
            point = next(e['authorPoint'] for e in row['entranceEvidence'] if e['projectedBoundary']==[front['position']['x'],front['position']['z']] and e['authorPoint']['facing'].upper()==front['direction'])
            access = outside_access(data, point, resolved['y'], registry)
            if not access['passed']:
                raise ValueError('Exterior approach failed: ' + str(access))
            report['changes'].append(dict(id=author['id'], source=str(author_path.relative_to(ROOT)),
                beforeSha256=digest(author_path), sourceNbtSha256=digest(source),
                groundPlane=updated['ground_plane'], outsideAccess=access,
                previewCorrection=17 if author['id']=='TC-08-v01' else None,
                nbtChange=changed_nbt, updatedAuthor=updated))
        except Exception as exc:
            report['errors'].append(dict(id=author['id'], error=str(exc)))
        done=len(report['changes'])+len(report['errors'])
        if done%25==0:
            print(json.dumps(dict(checked=done, errors=len(report['errors'])),ensure_ascii=False),flush=True)
    write(output/'plan.json',report)
    print(json.dumps(dict(changes=len(report['changes']),preserved=len(report['preserved']),errors=report['errors'],plan=str(output/'plan.json')),ensure_ascii=False),flush=True)
    return not report['errors']


def apply(plan_path):
    report=read(plan_path)
    if report['errors'] or report['applied']:
        raise ValueError('Plan has errors or has already been applied')
    for item in report['preserved']:
        if digest(ROOT/item['source']) != item['sha256']:
            raise ValueError('Preserved asset changed: '+item['id'])
    for item in report['changes']:
        path=ROOT/item['source']
        if digest(path)!=item['beforeSha256'] or digest(path.with_name('structure.nbt'))!=item['sourceNbtSha256']:
            raise ValueError('Source changed since plan: '+item['id'])
    for item in report['changes']:
        path=ROOT/item['source']
        backup=plan_path.parent/'backups'/item['source']
        backup.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(path,backup)
        if item['nbtChange']:
            for name,field in [('structure.nbt','stagedNbt'),('validation.json','stagedValidation')]:
                shutil.copy2(path.with_name(name),backup.with_name(name))
                shutil.copy2(item['nbtChange'][field],path.with_name(name))
        write(path,item['updatedAuthor'])
        item['afterSha256']=digest(path)
    report['applied']=True
    write(plan_path,report)
    print(json.dumps(dict(applied=len(report['changes']),preserved=len(report['preserved']),backups=str(plan_path.parent/'backups'))),flush=True)


if __name__=='__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle',type=Path)
    parser.add_argument('--output',type=Path)
    parser.add_argument('--apply-plan',type=Path)
    parser.add_argument('--resume',action='store_true',help='Retry failed assets; keep successful per-asset evidence and recheck sources before apply')
    args=parser.parse_args()
    if args.apply_plan:
        apply(args.apply_plan)
    else:
        if not args.bundle or not args.output: parser.error('--bundle and --output are required for a dry run')
        raise SystemExit(0 if plan(args.bundle,args.output,args.resume) else 1)
