"""Prepare editable metadata and preview-only hooks; never changes source scripts/audio."""
from pathlib import Path
import json,re,shutil,subprocess
H=Path(__file__).resolve().parent
data=json.loads((H/'preview_data.json').read_text(encoding='utf8'))
for a in data['acts']:
    name=a['name'];r=json.loads((H/f'mix_report_{name}.json').read_text(encoding='utf8'))
    a['voices']=r['voices'];a['masterGain']=r['master_gain_db'];a['groups']=[]
    for i,c in enumerate(a['cards']):
        key=f"{c['who']}:{c['lineStart']:.5f}"
        g=next((g for g in a['groups'] if g['key']==key),None)
        if g is None:
            g={'key':key,'who':c['who'],'start':c['lineStart'],'end':c['end'],'cards':[]};a['groups'].append(g)
        g['cards'].append(i);g['end']=max(g['end'],c['end'])
    for v in a['voices']:
        g=next(g for g in a['groups'] if g['who']==v['who'] and abs(g['start']-v['start'])<.001)
        g['voice']=v['file'];v['key']=g['key'];v['url']=f"cache/{name}/{Path(v['file']).stem}-dry.wav"
    a['bgm']=name+'_BGM试听.m4a'
    a['marks']=json.loads((H/f'timeline_{name}_配音.json').read_text(encoding='utf8')).get('marks',{})
    p=H/a['html'];s=p.read_text(encoding='utf8')
    # The atlas must be centered exactly where the original 1438px image was.
    if name=='第二段':s=s.replace("left:'0',top:'0',width:'1600px'","left:'105.5px',top:'0',width:'1600px'")
    if name in ['第三段','第四段','第五段'] and 'window.editorRefreshInternals' not in s:
        marker='  const shots = shotLines.map(([a, b]) => [pauseTime[a], pauseTime[b]]);'
        hook='''
  const editorOldLines={...lines},editorOldShots=shots.map(s=>s.slice());
  window.editorRefreshInternals=()=>{
    boundaries.splice(0,boundaries.length,...[...new Set(timeline.flatMap(c=>[c.start,c.end,c.end+HOLD]))].sort((a,b)=>a-b));
    for(const id of Object.keys(lines)){const c=timeline.find(c=>c.id===id);if(c)lines[id]=c.lineStart;}
    shots.forEach((s,i)=>shotLines[i].forEach((li,j)=>{const id=window.SCRIPT.lines[li].id;s[j]=editorOldShots[i][j]+lines[id]-editorOldLines[id];}));
  };
'''
        assert marker in s;s=s.replace(marker,marker+hook)
    if 'editor_bridge.js' not in s:s=s.replace('</body>','<script src="../../音频/全片配音_20261118/editor_bridge.js"></script>\n</body>')
    p.write_text(s,encoding='utf8')
(H/'editor_data.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf8')
if not (H/'preview.html').exists():shutil.copy2(H/'index.html',H/'preview.html')
print('Editor metadata ready')
