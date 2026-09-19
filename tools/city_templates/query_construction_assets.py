"""Query name-based candidates without loading the full collection into an AI prompt."""
import argparse
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--catalog', type=Path, default=Path(__file__).resolve().parents[2] / 'asset_catalogs/construction_pack/assets.jsonl')
    parser.add_argument('--style', help='Exact style label, e.g. 中世纪')
    parser.add_argument('--function', help='Exact function label, e.g. 行政')
    parser.add_argument('--series', help='Source series substring')
    parser.add_argument('--kind', help='Exact asset kind, e.g. 建筑与城市配套')
    parser.add_argument('--limit', type=int, default=20)
    args = parser.parse_args()
    if args.limit < 1:
        parser.error('--limit must be positive')
    rows = []
    with args.catalog.open(encoding='utf-8') as stream:
        for line in stream:
            row = json.loads(line)
            if args.style and args.style not in row['styles']: continue
            if args.function and args.function not in row['functions']: continue
            if args.series and args.series not in row['sourceSeries']: continue
            if args.kind and args.kind != row['assetKind']: continue
            rows.append(row)
    coverage_path = args.catalog.parent / 'coverage.json'
    coverage = json.loads(coverage_path.read_text(encoding='utf-8')) if coverage_path.exists() else {}
    print(json.dumps({'totalMatched': len(rows), 'returned': min(len(rows), args.limit),
                      'styleCoverage': coverage.get('styles', {}).get(args.style),
                      'matchingSeriesCoverage': {name: item for name, item in coverage.get('sourceSeries', {}).items()
                                                if args.series and args.series in name},
                      'classificationBasis': 'folder_and_filename_only', 'assets': rows[:args.limit]},
                     ensure_ascii=False, indent=2))


if __name__ == '__main__':
    import sys
    sys.stdout.reconfigure(encoding='utf-8')
    main()
