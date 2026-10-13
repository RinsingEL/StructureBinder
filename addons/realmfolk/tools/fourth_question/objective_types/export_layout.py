"""Copy verified D4 footprints into the offline viewer; never touch a game save."""
import hashlib
import json
from pathlib import Path

here = Path(__file__).resolve().parent
source = here.parent / 'results/academy/evidence.json'
evidence = json.loads(source.read_text(encoding='utf-8'))
profiles = {p['semanticProfileId']: p for p in evidence['snapshot']['structureCatalog']['semanticProfiles']}
buildings = []
for anchor in evidence['anchors']['anchors']:
    profile = profiles[anchor['templateId']]
    assert profile['reviewState'] == 'approved', anchor['anchorId']
    buildings.append({'id': anchor['anchorId'], 'bounds': anchor['plannedFootprint'],
                      'terms': profile['functionTerms'], 'template': anchor['templateId']})
assert len({b['id'] for b in buildings}) == len(buildings)
bindings = {'sel': 'observation_required_002', 'tasha': 'archives_required_002',
            'loren': 'reception_required_001', 'mira': 'forest_school_required_003',
            'ilan': 'archives_required_002', 'vera': 'forest_school_required_001'}
assert all(x in {b['id'] for b in buildings} for x in bindings.values())
result = {'version': 1, 'sourceBlueprintHash': evidence['source']['blueprintHash'],
          'evidenceSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
          'note': 'D4 建筑布局为已有冻结证据；人物落点是本实验绑定，不是已生成游戏实体或实际可站立坐标。',
          'buildings': buildings, 'bindings': bindings}
(here / 'campus-layout.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
print(f'Exported {len(buildings)} D4 footprints and {len(bindings)} experiment bindings.')
