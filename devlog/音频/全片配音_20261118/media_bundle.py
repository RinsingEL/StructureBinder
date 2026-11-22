"""Check or package the frozen media inventory without invoking TTS or changing sources."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('command', choices=['check', 'pack'])
    ap.add_argument('--output', type=Path)
    args = ap.parse_args()
    inventory = json.loads((HERE / 'media_manifest.json').read_text(encoding='utf-8'))
    paths = []
    for item in inventory['files']:
        p = (ROOT / item['path']).resolve()
        if not p.is_relative_to(ROOT) or not p.is_file():
            raise SystemExit('Missing or invalid media: ' + item['path'])
        with p.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        if p.stat().st_size != item['bytes'] or digest != item['sha256']:
            raise SystemExit('Media differs from frozen inventory: ' + item['path'])
        paths.append((p, item['path']))
    print(f'CHECKED {len(paths)} media files', flush=True)
    if args.command == 'pack':
        if not args.output:
            ap.error('pack requires --output (a new zip file)')
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.output, 'x', zipfile.ZIP_DEFLATED, compresslevel=1) as z:
            for p, relative in paths:
                z.write(p, relative)
            z.write(HERE / 'media_manifest.json', (HERE / 'media_manifest.json').relative_to(ROOT).as_posix())
        print(f'PACKED {args.output}', flush=True)


if __name__ == '__main__':
    main()
