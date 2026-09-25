"""Apply reviewed author-only tag corrections; never rewrite NBT or evidence.

Usage: python -m studio.refine_annotations [--write] [--report PATH]
Without --write this reports proposed changes only. Old visual reviews remain
bound to their original author hash; this is not a publish/migration approval.
"""
import argparse
import json
from pathlib import Path

from .annotation_policy import refine_metadata
from .model import sha256, write_json
from .server import CATALOG, TOOL


def differences(before, after, path=()):
    if isinstance(before, dict) and isinstance(after, dict):
        for key in sorted(before.keys() | after.keys()):
            yield from differences(before.get(key), after.get(key), (*path, key))
    elif isinstance(before, list) and isinstance(after, list) and len(before) == len(after):
        for index, (a, b) in enumerate(zip(before, after)):
            yield from differences(a, b, (*path, index))
    elif before != after:
        yield dict(path=list(path), before=before, after=after)


def check_changes(meta, changes):
    for change in changes:
        path = change['path']
        if path[0] in {'terrain', 'design_notes', 'function_terms'}:
            continue
        if (meta['id'].startswith('SC-F02-v') and len(path) == 3
                and path[0] == 'rooms' and path[2] == 'name'
                and change['before'] == '短期营地种植畦'
                and change['after'] == '长驻营地种植畦'):
            continue
        raise ValueError(f"Refusing non-semantic change: {meta['id']} {path}")


def migrate(root=CATALOG, *, write=False, report_path=None):
    plans = []
    # Preflight the full batch before the first write. Individual replacements
    # are atomic; the entire batch is not a filesystem transaction.
    for path in sorted(root.glob('*/models/*/author.json')):
        old = json.loads(path.read_text(encoding='utf-8'))
        new = refine_metadata(old)
        changes = list(differences(old, new))
        if not changes:
            continue
        check_changes(old, changes)
        nbt_hash = sha256(path.parent / 'structure.nbt')
        if old.get('nbt_sha256') != nbt_hash:
            raise ValueError(f"Refusing mismatched author/NBT: {path}")
        evidence = {str(p.relative_to(path.parent)): sha256(p)
                    for p in [path.parent / 'review.json', path.parent / 'previews/capture.json'] if p.exists()}
        plans.append((path, new, dict(id=old['id'], author_path=str(path),
            author_before=sha256(path), nbt_sha256=nbt_hash, changes=changes,
            evidence_hashes=evidence)))
    report = dict(schema='structure-studio.annotation-refinement.v1', applied=write,
                  changed=len(plans), assets=[row for _, _, row in plans],
                  scope='作者语义校核；NBT及旧验收文件不改；不构成新图审或运行时批准。')
    if write and report_path:
        # Durable before/after values remain available even on an interrupted run.
        report['state'] = 'planned'
        write_json(report_path, report)
    for path, meta, row in plans:
        if write:
            if sha256(path) != row['author_before']:
                raise ValueError(f"Author changed during refinement: {path}")
            write_json(path, meta)
            row['author_after'] = sha256(path)
            if sha256(path.parent / 'structure.nbt') != row['nbt_sha256']:
                raise ValueError(f"NBT changed during refinement: {path}")
            for name, digest in row['evidence_hashes'].items():
                if sha256(path.parent / name) != digest:
                    raise ValueError(f"Evidence changed during refinement: {path} {name}")
    report['state'] = 'applied' if write else 'preview'
    if report_path:
        write_json(report_path, report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    parser.add_argument('--report', type=Path, default=TOOL / 'runtime/annotation-refinement.json')
    args = parser.parse_args()
    report = migrate(write=args.write, report_path=args.report)
    print(json.dumps({'state': report['state'], 'changed': report['changed'],
                      'report': str(args.report)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
