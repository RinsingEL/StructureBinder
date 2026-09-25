"""Attach a portable core-building atlas to an existing Studio runtime bundle.

Only installed catalog entries participate. Captures must match the exported
source NBT; screenshots are visual evidence, never a new approval verdict.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[2]
CORE_ROLES = {'planning_role.key', 'planning_role.anchor'}


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def export(bundle, root=ROOT):
    bundle, root = Path(bundle).resolve(), Path(root).resolve()
    templates = {r['templateRef']: r for r in read(bundle / 'template_catalog.json')['templates']}
    names = {r['templateRef']: r for r in read(bundle / 'asset_names.json')}
    provenance = {r['templateRef']: r for r in read(bundle / 'studio_export_provenance.json')['templates']}
    profiles = [json.loads(line) for line in (bundle / 'StructureProfile.jsonl').read_text(encoding='utf-8-sig').splitlines() if line.strip()]
    selected, functions, styles = [], Counter(), Counter()
    for profile in sorted(profiles, key=lambda p: p['structureId']):
        ref = profile['structureId']
        if ref not in templates:
            continue
        if profile.get('reviewState') != 'approved':
            raise ValueError(f'Unapproved catalog entry: {ref}')
        functions.update(set(profile['functionTerms']))
        styles.update(set(profile['styleTerms']))
        if not CORE_ROLES.intersection(profile.get('planningRoleTerms', [])):
            continue
        source = provenance[ref]
        model = (root / source['sourceNbt']).resolve().parent
        if not model.is_relative_to(root):
            raise ValueError(f'Source outside repository: {ref}')
        capture = read(model / 'previews/capture.json')
        if capture['nbt_sha256'].removeprefix('sha256:') != source['sourceSha256'].removeprefix('sha256:'):
            raise ValueError(f'Stale core capture: {ref}')
        if capture.get('render_errors') or capture.get('missing_textures') or 'front' not in capture['shots']:
            raise ValueError(f'Invalid core capture: {ref}')
        image_path = model / 'previews/front.png'
        with Image.open(image_path) as image:
            image.load()
        selected.append((dict(templateRef=ref, displayName=names[ref]['displayName'],
            functionTerms=profile['functionTerms'], styleTerms=profile['styleTerms'],
            rawSize=templates[ref]['rawSize'], siteConditions=names[ref].get('siteConditions', {})), image_path))
    # Validate all source captures before publishing any atlas files.
    output = bundle / 'core_atlas'
    output.mkdir(exist_ok=True)
    pages, cores = [], []
    font = ImageFont.load_default(size=20)
    for start in range(0, len(selected), 4):
        page_number = len(pages) + 1
        sheet = Image.new('RGB', (1200, 880), '#10171d')
        draw = ImageDraw.Draw(sheet)
        page_refs = []
        for index, (entry, source_image) in enumerate(selected[start:start + 4]):
            x, y = (index % 2) * 600, (index // 2) * 440
            with Image.open(source_image) as image:
                thumb = ImageOps.contain(image.convert('RGB'), (592, 392))
            sheet.paste(thumb, (x + (600-thumb.width)//2, y + (400-thumb.height)//2))
            draw.text((x+12, y+408), entry['templateRef'], font=font, fill='#ffffff')
            cores.append(dict(entry, page=page_number, slot=index+1))
            page_refs.append(entry['templateRef'])
        relative = f'core_atlas/page-{page_number:03}.png'
        sheet.save(bundle / relative)
        pages.append(dict(page=page_number, file=relative, sha256=digest(bundle / relative), templateRefs=page_refs))
    manifest = dict(schema='realm_core_atlas.v1',
        sources={name: digest(bundle/name) for name in ('template_catalog.json', 'asset_names.json', 'StructureProfile.jsonl')},
        cores=cores, pages=pages,
        supportSummary=dict(templateCount=len(templates), coreCount=len(cores),
            functions=dict(sorted(functions.items())), styles=dict(sorted(styles.items()))))
    target = bundle / 'realm_core_atlas.json'
    temp = target.with_suffix('.tmp')
    temp.write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    temp.replace(target)
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    args = parser.parse_args()
    result = export(args.bundle)
    print(json.dumps(dict(cores=len(result['cores']), pages=len(result['pages'])), ensure_ascii=False))
