from pathlib import Path
import sys,json,math,re,hashlib,subprocess
HERE=Path(__file__).resolve().parent;H=HERE.parent;ROOT=H.parents[2]
sys.path.insert(0,str(ROOT/'devlog/音频/暂定配音成片_20261117'))
from build_stage import read_audio,write,SR,FF,stime
import numpy as np
from scipy import signal
project=json.loads((HERE/'最终剪辑_配乐气泡均为负10dB.json').read_text(encoding='utf8'))
data=json.loads((H/'editor_data.json').read_text(encoding='utf8'))
out=HERE/'render';out.mkdir(exist_ok=True)
db=lambda x:10**(x/20)
report=[]
for a in data['acts']:
 name=a['name'];s=project['acts'][name];D=a['duration'];N=math.ceil(D*24)/24;ns=round(N*SR)
 assert not s.get('audioEdits'), 'Legacy destructive edits require explicit render support'
 cs=json.loads((out/f'{name}_cards.json').read_text(encoding='utf8'))
 voices={v['key']:v for v in a['voices']};imports={x['id']:x for entries in s.get('importedAudio',{}).values() for x in entries}
 cache={};input_hashes={}
 def load(url):
  p=(H/url).resolve();assert p.is_relative_to(H.resolve()) and p.is_file(),p
  if url not in cache:
   cache[url]=read_audio(p);input_hashes[url]=hashlib.sha256(p.read_bytes()).hexdigest()
  return cache[url]
 def original(v):
  choices={x['id']:x for x in v.get('alternatives',[])};choices.update(imports)
  chosen=s.get('audioChoice',{}).get(v['key'],'original');alt=choices.get(chosen)
  if chosen!='original':assert alt,chosen
  return (alt['url'],alt['gain_db']) if alt else (v['url'],v['gain_db'])
 clips=s.get('clips')
 if clips is None:clips=[{'id':v['file'],'key':v['key'],'at':v['start']+s['voice'].get(v['key'],0),'in':0,'out':len(load(original(v)[0]))/SR,'gain':s['gain'].get(v['key'],0)} for v in a['voices']]
 speech=np.zeros((ns,2),np.float32);bubble=np.zeros_like(speech)
 def place(dest,chunk,start,gain):
  at=round(start*SR);assert at>=0 and at+len(chunk)<=len(dest)+48,(name,start,len(chunk)/SR)
  chunk=chunk[:len(dest)-at].copy();dur=len(chunk)/SR;ts=np.arange(len(chunk))/SR
  chunk*=np.minimum(np.minimum(ts/max(.00001,min(.003,dur/3)),1),np.clip((dur-ts)/.005,0,1))[:,None]
  dest[at:at+len(chunk)]+=chunk*gain
 for c in clips:
  v=voices[c['key']]
  if c.get('sourceId'):url=imports[c['sourceId']]['url'];gain=0
  else:url,gain=original(v)
  audio=load(url);lo=round(c['in']*SR);hi=round(c['out']*SR)
  assert 0<=lo<hi<=len(audio)+96,(name,c['id'],hi,len(audio))
  chunk=audio[lo:hi];place(speech,chunk,c['at'],0 if s['mute'].get('voice') else db(gain+a['masterGain']+c['gain']+s['tracks']['voice']))
 pulse=read_audio(H.parent/'气泡音候选_20261117/参考分析/参考方向_单音.wav')
 for c in cs:
  if c['who']!='rin':continue
  t=c['start']
  while t<c['revealEnd']-.0001:
   size=min(len(pulse),round((c['revealEnd']-t)*SR));place(bubble,pulse[:size],t,0 if s['mute'].get('bubble') else .70*.7071*db(a['masterGain']+s['tracks']['bubble']));t+=.11462585034013606
 # Match the editor's voice-triggered music ducking, with deterministic 10ms control samples.
 ts=np.arange(math.ceil(ns/480))*0.01;duck=np.ones_like(ts)
 for c in clips:
  st=c['at'];en=st+c['out']-c['in'];g=np.interp(ts,[st-.18,st+.05,en+.12,en+.6],[1,db(-7.5),db(-7.5),1]);duck=np.minimum(duck,g)
 for c in cs:
  if c['who']=='rin':duck[(ts>=c['start'])&(ts<c['revealEnd'])]=np.minimum(duck[(ts>=c['start'])&(ts<c['revealEnd'])],db(-3))
 alpha=math.exp(-.01/.03);duck,_=signal.lfilter([1-alpha],[1,-alpha],duck,zi=[alpha]);duck=np.interp(np.arange(ns)/SR,ts,duck).astype(np.float32)
 music=read_audio(H/f'{name}_Gemini配乐.wav');mixed=speech+bubble;at=round(s['bgmStart']*SR);take=min(len(music),ns-at)
 if not s['mute'].get('bgm'):mixed[at:at+take]+=music[:take]*duck[at:at+take,None]*db(a['masterGain']+s['tracks']['bgm'])
 mixed*=.8;peak=float(np.max(np.abs(mixed)));scale=min(1,db(-1)/max(peak,1e-9));mixed*=scale
 wav=out/f'{name}_定稿混音.wav';write(wav,mixed)
 html=(H/a['html']).read_text(encoding='utf8');base=(ROOT/'devlog/视频素材'/name).as_uri()+'/'
 html=re.sub(r'<base href="[^"]+">',lambda _:f'<base href="{base}">',html,count=1)
 html=re.sub(r'<script>\s*// LIVE_ATLAS_PREVIEW.*?</script>','',html,flags=re.S)
 injection='<script>window.applyEditorCards('+json.dumps(cs,ensure_ascii=False)+');</script>'
 html=html.replace('</body>',injection+'\n</body>');(out/f'{name}_定稿.html').write_text(html,encoding='utf8')
 (out/f'{name}_定稿.srt').write_text('\n\n'.join(f"{i+1}\n{stime(c['start'])} --> {stime(c['end'])}\n{c['text']}" for i,c in enumerate(sorted(cs,key=lambda c:c['start'])))+'\n',encoding='utf8')
 report.append({'act':name,'duration':D,'video_duration':N,'clips':len(clips),'cards':len(cs),'tracks':s['tracks'],'peak_before_safety':peak,'safety_gain':scale,'input_hashes':input_hashes})
 (HERE/'渲染核验.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
 print('READY',name,len(clips),'peak',round(peak,3),flush=True)
 del speech,bubble,mixed,music,cache
