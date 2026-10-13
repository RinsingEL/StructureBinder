import json,pathlib,subprocess,sys
base=pathlib.Path('E:/Mod_Dev/StructureBinder');out=base/'runtime/academy-d4-refresh-20261011/bundle-complete';out.parent.mkdir(parents=True,exist_ok=True)
models=base/'asset_catalogs/original_civilizations/R02_arcane_reborn/models';selected={}
for p in sorted(models.glob('*/author.json')):
 a=json.loads(p.read_text(encoding='utf-8-sig'))
 if 'infrastructure' in a.get('asset_tags',[]) or a['id'] in ['MG-11-v01','MG-24-v01']:continue
 selected[a['family']]=a['id']
cmd=[sys.executable,'tools/city_templates/build_studio_test_bundle.py','--output',str(out),'--baseline','run/config/structureTemplate/terrasense/pcl_validation_02','--classpath-file','build/city-workbench-classpath.txt','--java','C:/Program Files/Java/jdk-17/bin/java.exe','--styles','R02_arcane_reborn','--all-previews','--asset-ids',*selected.values()]
(base/'runtime/academy-d4-refresh-20261011/export-selection.json').write_text(json.dumps(list(selected.values()),indent=2),encoding='utf-8')
subprocess.run(cmd,check=True)

