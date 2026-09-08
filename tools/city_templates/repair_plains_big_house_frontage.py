"""Apply the reviewed two-door frontage declaration; never edits saved city snapshots."""
import argparse, hashlib, json
from pathlib import Path
import nbtlib
REF = 'geomantia:city/trek_v3/village/plains/houses/plains_big_house_1'
def apply(root, backup):
    catalog_path=root/'template_catalog.json'; manifest_path=root/'city_template_content_pack.json'
    catalog=json.loads(catalog_path.read_text(encoding='utf-8')); manifest=json.loads(manifest_path.read_text(encoding='utf-8'))
    template=next(t for t in catalog['templates'] if t['templateRef']==REF)
    assert template['contentHash']=='sha256:b532f1ba4a7a87d8a7b3e4414a9084e198d5405f0955def83fe8a72bd8972e93'
    assert template.get('frontagePolicy','FIXED_FRONT') in ['FIXED_FRONT','ANY_AUTHORED_ENTRANCE']
    assert [(p['position']['x'],p['position']['z'],p['direction']) for p in template['roadEntrances']]==[(0,4,'WEST'),(0,10,'WEST')]
    entry=next(e for e in manifest['templates'] if e['templateRef']==REF)
    file=root/'city_template_content_pack'/entry['sourceFile']
    assert 'sha256:'+hashlib.sha256(file.read_bytes()).hexdigest()==entry['sourceSha256']
    n=nbtlib.load(file); palette=n['palette']; blocks={tuple(map(int,b['pos'])):palette[int(b['state'])] for b in n['blocks']}
    for z in [4,10]:
        door=blocks[(3,1,z)]
        assert str(door['Name'])=='minecraft:oak_door' and str(door['Properties']['facing'])=='west'
        for x in range(3):
            assert str(blocks[(x,0,z)]['Name']) in ['minecraft:rooted_dirt','minecraft:coarse_dirt']
            assert all(str(blocks[(x,y,z)]['Name'])=='minecraft:air' for y in [1,2])
    backup.mkdir(parents=True,exist_ok=True)
    for p in [catalog_path,manifest_path]:
        target=backup/p.name
        if not target.exists():target.write_bytes(p.read_bytes())
    template['frontagePolicy']='ANY_AUTHORED_ENTRANCE'
    catalog_path.write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    manifest['catalogSha256']='sha256:'+hashlib.sha256(catalog_path.read_bytes()).hexdigest()
    manifest_path.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return dict(bundle=str(root),templateRef=REF,catalogSha256=manifest['catalogSha256'],verifiedDoorPaths=2)
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('bundle',type=Path);parser.add_argument('--backup',type=Path,required=True);args=parser.parse_args()
    print(json.dumps(apply(args.bundle,args.backup),ensure_ascii=False))
