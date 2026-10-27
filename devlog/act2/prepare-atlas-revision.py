"""Import public replay requests and verified map metadata; never copy conversation prose."""
import argparse, collections, hashlib, json, re, zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument('archive', type=Path)
parser.add_argument('replay', type=Path)
args = parser.parse_args()
read = lambda p: json.loads(p.read_text(encoding='utf-8'))
html = (HERE/'wtd-atlas.html').read_text(encoding='utf-8')
base = json.loads(re.search(r'<script id="atlas-data" type="application/json">(.*?)</script>', html, re.S)[1])
for k in ['images', 'academy', 'revision']: base.pop(k, None)
archive = zipfile.ZipFile(args.archive)
def archived(name):
    path = next(n for n in archive.namelist() if n.endswith('/'+name))
    return json.loads(archive.read(path))
profiles = archived('realm_profiles.json')
d3 = archived('city_landform_review_package.json')
cells = {}
for patch in d3['landformPatches']:
    for c in patch['memberCells']:
        cells[c['blockMinX'],c['blockMinZ']] = ['water','shore','plain','slope','terrace'].index(patch['landformType'])
assert base['local'] == [[x,z,k] for (x,z),k in sorted(cells.items())], 'Map differs from existing presentation'
assert [(p['realmId'],p['name']) for p in profiles] == [(p['id'],p['name']) for p in base['realms']]
shorts = ['冬季共储与船具维护','木作协作与越冬储粮','工艺教学与供给协作','家族聚落与路站补给','港口工坊与商旅接待','用水协作与旅客补给','教学、藏书与学生生活']
lines = args.replay.read_text(encoding='utf-8').splitlines()
calls = []
for number, line in enumerate(lines,1):
    p = json.loads(line).get('payload',{})
    item = p.get('item',{})
    if p.get('type') == 'item_completed' and item.get('type') == 'McpToolCall':
        a = item.get('arguments',{})
        if a.get('tool') in ['city_d4_overview','city_d4_district','city_d4_materials']:
            calls.append((number,a,item.get('result',{})))
overview = next(a['arguments']['overview'] for n,a,r in calls if n==80)
districts = overview['districts']
designs = {}
for n,a,r in calls:
    design = a.get('arguments',{}).get('districtDesign')
    if design:
        identity = a['arguments'].get('targetDistrictId',design.get('core',{}).get('groupId'))
        if identity in [d['groupId'] for d in districts]: designs[identity] = {'line':n,'design':design}
assert len(designs)==16, designs.keys()
# The transport wraps JSON text; decode individual material records, not malformed
# prose fields elsewhere in the historical response.
response = next(r for n,a,r in calls if n==84)
wrapped = json.loads(response['content'][0]['text'])
material_text = wrapped['content'][1]['text']
materials = {}
for match in re.finditer(r'\{"structureRef":', material_text):
    try:
        fragment=material_text[match.start():]
        ref=json.loads(re.match(r'\{"structureRef":("[^"]+")',fragment)[1])
        marker='"authoredMetadata":'
        # Parse the metadata object itself: historical purpose prose elsewhere
        # contains unescaped quotes and must not silently drop catalogue entries.
        metadata,_=json.JSONDecoder().raw_decode(fragment[fragment.index(marker)+len(marker):])
        if metadata.get('semanticProfileId')==ref:
            materials[ref]={'structureRef':ref,'authoredMetadata':metadata}
    except ValueError: pass
assert materials
tags=collections.Counter(t for m in materials.values() for t in set(m['authoredMetadata'].get('functionTerms',[])))
# Public exec output before the overview: the designer explicitly read authored
# profiles from preparedBlueprintContext, independently of district browsing.
overview_output=json.loads(lines[73])['payload']['output']
overview_profiles=next(json.loads(block['text']) for block in overview_output if block.get('text','').startswith('[{'))
overview_materials={m['semanticProfileId']:m for m in overview_profiles}
overview_tags=collections.Counter(t for m in overview_materials.values() for t in set(m.get('functionTerms',[])))
revision = {
 'provenance': {'archive':args.archive.name,'replay':args.replay.name,'replaySha256':hashlib.sha256(args.replay.read_bytes()).hexdigest(),'terrainMatchesEmbeddedMap':True,'materialsBeforeOverview':True,'overviewSummaryReadLine':55,'overviewProfilesReadLine':72,'overviewProfilesOutputLine':74,'failedMaterialsLine':64,'materialsLine':84},
 'realms':[{'id':p['realmId'],'short':s,'theme':p['theme']} for p,s in zip(profiles,shorts)],
 'patches':[{k:p[k] for k in ['mapLabel','landformType','centerBlock','memberCells']} for p in d3['landformPatches'] if p['landformType']!='water'],
 'districts':districts,'designIntent':overview['citySettings']['designIntent'],
 'designs':designs,'tags':sorted(tags.items(),key=lambda item:(-item[1],item[0])),'materials':materials,
 'overviewMaterials':overview_materials,'overviewTags':sorted(overview_tags.items(),key=lambda item:(-item[1],item[0])),
 'requests':{str(n):a['arguments'] for n,a,r in calls if n in [345,383,501,508]}
}
for name,value in [('atlas-base.json',base),('atlas-revision.json',revision)]:
    (HERE/'data'/name).write_text(json.dumps(value,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
print('Imported 7 realms, 16 district designs;',len(overview_materials),'authored profiles read before overview;',len(materials),'district-query materials after overview.')
