"""Audit the actual emitted voxels between two different-height core entrances."""
import argparse,json,sys
from pathlib import Path

tool=Path(__file__).resolve().parents[1];sys.path.insert(0,str(tool))
from studio.navigation import audit

def check(folder):
    meta=json.loads((folder/'metadata.json').read_text(encoding='utf-8'))
    world=json.loads((folder/'world.json').read_text(encoding='utf-8'))
    registry=json.loads((tool/'.cache/registry.json').read_text(encoding='utf-8'))
    members={m['memberId']:m for m in meta['members']}
    a,b=[members[k] for k in ('teaching-core:1','teaching-research:1')]
    pa,pb=[m['roadEntrances'][0]['worldPosition'] for m in (a,b)]
    lo=[min(pa['x'],pb['x'])-9,min(a['surfaceY'],b['surfaceY'])-6,min(pa['z'],pb['z'])-7]
    hi=[max(pa['x'],pb['x'])+9,max(a['surfaceY'],b['surfaceY'])+6,max(pa['z'],pb['z'])+7]
    data={'size':[hi[i]-lo[i]+1 for i in range(3)],'palette':world['palette'],'blocks':[]}
    for block in world['blocks']:
        pos=[block['pos'][i]+world['origin'][i] for i in range(3)]
        if all(lo[i]<=pos[i]<=hi[i] for i in range(3)):
            data['blocks'].append({**block,'pos':[pos[i]-lo[i] for i in range(3)]})
    # Only the hall is a traversal seed; the research entrance must be reached.
    points=[]
    for index,(member,pos) in enumerate(((a,pa),(b,pb))):
        points.append({'id':member['memberId'],'kind':'entrance' if index==0 else 'circulation',
          'pos':[pos['x']-lo[0],member['surfaceY']+1-lo[1],pos['z']-lo[2]]})
    report=audit(data,{'points':points},registry)
    result={'revision':meta['revision'],'window':{'min':lo,'max':hi},'worldEntrances':[
      [pa['x'],a['surfaceY']+1,pa['z']],[pb['x'],b['surfaceY']+1,pb['z']]],'audit':report}
    output=folder/'terrace-walk-check.json';output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False));return report['passed']

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('folder',type=Path);args=p.parse_args()
    sys.exit(0 if check(args.folder) else 1)
