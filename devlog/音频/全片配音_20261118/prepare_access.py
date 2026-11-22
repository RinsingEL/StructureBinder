"""Freeze an exact public-file list for this preview; no repository directory access."""
from pathlib import Path
import json,re,urllib.parse
H=Path(__file__).resolve().parent;ROOT=H.parents[2];allowed=set()
def add(p):
    p=p.resolve()
    if p.is_file() and p.is_relative_to(ROOT):allowed.add(p.relative_to(ROOT).as_posix())
for name in ['index.html','editor.html','editor.js','audio_edit.js','clip_edit.js','clip_model.js','replace_audio.js','editor.css','editor_bridge.js','editor_data.json','preview.html','preview_data.json','节奏排查/全片问题清单.md','节奏排查/具体位置.md','节奏排查/定位试听.html']:
    add(H/name)
data=json.loads((H/'editor_data.json').read_text(encoding='utf8'))
sources=[]
for a in data['acts']:
    add(H/a['html']);add(H/a['audio']);add(H/a['bgm']);add(H/(a['name']+'_混音.wav'))
    for v in a['voices']:
        for alternative in v.get('alternatives',[]):add(H/alternative['url'])
        add(H/v['url']);add(H.parent/'暂定配音成片_20261117/voices'/a['name']/v['file'])
    sources.append((H/a['html'],ROOT/'devlog/视频素材'/a['name']))
for name in ['开场_接话等待.wav','第二段_问答等待.wav','第二段_快速接话.wav','第二段_原生停顿.wav']:add(H/'节奏排查'/name)
add(H.parent/'气泡音候选_20261117/参考分析/参考方向_单音.wav')
atlas=ROOT/'devlog/act2/wtd-atlas.html';add(atlas);sources.append((atlas,atlas.parent))
seen=set()
while sources:
    file,base=sources.pop()
    if (file,base) in seen:continue
    seen.add((file,base));text=file.read_text(encoding='utf8')
    for ref in re.findall(r'''["']([^"'\n<>]{1,250}\.(?:js|css|png|jpg|jpeg|webp|svg|woff2?))(?:\?[^"']*)?["']''',text):
        if ':' in ref or '${' in ref:continue
        p=(base/urllib.parse.unquote(ref)).resolve();add(p)
        if p.is_file() and p.suffix in ['.js','.css']:sources.append((p,base if p.suffix=='.js' else p.parent))
for who in ['rin','gemini','claude','astra','deepseek','glm']:
    for state in ['listen','greet','d4','talk_smile','think','moved','wry']:
        add(ROOT/f'devlog/act2/assets/avatars/sprites/compact/{who}/{state}.png')
        add(ROOT/f'devlog/act2/assets/avatars/heads/{who}/{state}.webp')
# Dynamically selected images already requested by the five-act preview.
log=ROOT/'.tools/devlog-audio/preview-8790.err.log'
if log.exists():
    for url in re.findall(r'"(?:GET|HEAD) ([^ ]+) HTTP/[^\"]+" (?:200|206|304)',log.read_text(encoding='utf8',errors='replace')):
        p=(ROOT/urllib.parse.unquote(urllib.parse.urlsplit(url).path).lstrip('/')).resolve()
        if p.suffix.lower() in ['.png','.jpg','.jpeg','.webp','.svg'] and (p.is_relative_to(ROOT/'devlog') or p.is_relative_to(ROOT/'dev_docs/systems/city/reports/20261031_学院功能区实景报告/evidence')):add(p)
(H/'access_manifest.json').write_text(json.dumps(sorted(allowed),ensure_ascii=False,indent=2),encoding='utf8')
assert not any(Path(p).suffix in ['.py','.log','.toml','.env'] for p in allowed)
print('Allowed exact preview files:',len(allowed))
