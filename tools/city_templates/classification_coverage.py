"""Audit exported semantic profiles against the same taxonomy used by Studio and Java."""
import argparse
import hashlib
import json
from pathlib import Path

TAXONOMY = Path(__file__).resolve().parents[2] / 'src/main/resources/geomantia/catalog/structure_functions.json'


def coverage(profiles, taxonomy_path=TAXONOMY):
    raw = taxonomy_path.read_bytes()
    taxonomy = json.loads(raw)
    nodes = {node['id']: node for node in taxonomy['functions']}
    by_term = {}
    for node in taxonomy['functions']:
        for term in node['terms']:
            by_term.setdefault(term, set()).add(node['id'])
    rows = []
    for profile in profiles:
        ref = profile.get('structureId', profile.get('semanticProfileId'))
        if not ref:
            raise ValueError('Classification report requires an exported structure ID')
        terms = set(profile.get('functionTerms', []))
        direct = set().union(*(by_term.get(term, set()) for term in terms))
        for rule in taxonomy['assetRules']:
            if ref in rule['ids'] and set(rule['allTerms']) <= terms:
                direct.update(rule['functionIds'])
        expanded = set(direct)
        for ident in direct:
            parent = nodes[ident]['parent']
            seen = {ident}
            while parent:
                if parent in seen:
                    raise ValueError('Taxonomy parent cycle')
                seen.add(parent)
                expanded.add(parent)
                parent = nodes[parent]['parent']
        rows.append(dict(structureRef=ref, functionTerms=sorted(terms),
                         directFunctionIds=sorted(direct), functionIds=sorted(expanded), unmappedFunctionTerms=sorted(terms - by_term.keys()),
                         category=profile.get('category', ''), assetTags=profile.get('assetTags', [])))
    return dict(schema='studio_classification_coverage.v1', taxonomySchema=taxonomy['schema'],
                taxonomySha256='sha256:' + hashlib.sha256(raw).hexdigest(), totalCount=len(rows),
                classifiedCount=sum(bool(r['functionIds']) for r in rows),
                unclassifiedCount=sum(not r['functionIds'] for r in rows),
                missingFunctionTermsCount=sum(not r['functionTerms'] for r in rows),
                unclassifiedRefs=[r['structureRef'] for r in rows if not r['functionIds']],
                unmappedFunctionTerms=sorted({t for r in rows for t in r['unmappedFunctionTerms']}),
                assetTagCounts={tag: sum(tag in r['assetTags'] for r in rows)
                                for tag in ('infrastructure', 'landscape')},
                note='Diagnostic only: retains author terms and tags; unknown terms are not inferred or rejected.',
                entries=rows)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('profiles', type=Path, help='StructureProfile.jsonl')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.profiles.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    report = coverage(rows)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: report[k] for k in ('totalCount', 'classifiedCount', 'unclassifiedCount', 'missingFunctionTermsCount')}, ensure_ascii=False))
