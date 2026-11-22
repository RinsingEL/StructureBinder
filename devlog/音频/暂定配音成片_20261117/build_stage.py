"""Place natural-speed voices, align subtitle cards, and drive Rin's text/bubbles together."""
from pathlib import Path
import argparse,json,sys,subprocess,wave,math,re,unicodedata,difflib
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[2]
sys.path.insert(0,str(ROOT/'.tools/devlog-audio/python'))
sys.path.insert(0,str(HERE.parent/'TTS/fish_tts_pack'))
import numpy as np
from scipy import signal
from run_fish_timeline import load_act_sentences
import imageio_ffmpeg
FF=imageio_ffmpeg.get_ffmpeg_exe();SR=48000;BEAT=60/108;STEP=.11462585034013606

def read_audio(p,channels=2,speed=1.0):
    raw=subprocess.run([FF,'-v','error','-i',str(p),*(['-af',f'atempo={speed}'] if speed!=1.0 else []),'-f','f32le','-ac',str(channels),'-ar',str(SR),'pipe:1'],capture_output=True,check=True).stdout
    return np.frombuffer(raw,np.float32).reshape(-1,channels).copy()
def write(p,x):
    assert np.isfinite(x).all() and np.max(np.abs(x))<1
    if x.ndim==1:x=x[:,None]
    with wave.open(str(p),'wb') as w:w.setnchannels(x.shape[1]);w.setsampwidth(2);w.setframerate(SR);w.writeframes((x*32767).astype('<i2').tobytes())
def loudness(p):
    result=subprocess.run([FF,'-hide_banner','-i',str(p),'-af','loudnorm=I=-18.5:TP=-1.5:LRA=11:print_format=json','-f','null','-'],capture_output=True,check=True)
    s=result.stderr.decode('utf8','replace');v=json.loads(s[s.rfind('{'):s.rfind('}')+1]);return {k:float(v[k]) for k in ['input_i','input_tp','input_lra']}
def norm(s):
    s=unicodedata.normalize('NFKC',s).lower()
    for a,b in [('十一月','11月'),('十月','10月'),('九月','9月'),('一月','1月')]:s=s.replace(a,b)
    return ''.join(c for c in s if c.isalnum())
def stime(t):
    ms=round(t*1000);h,ms=divmod(ms,3600000);m,ms=divmod(ms,60000);s,ms=divmod(ms,1000);return f'{h:02}:{m:02}:{s:02},{ms:03}'

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--act',required=True);ap.add_argument('--voice-speed',type=float,default=1.0);ap.add_argument('--out-dir',type=Path);ap.add_argument('--music-dir',type=Path);a=ap.parse_args();act=a.act
    assert .8<=a.voice_speed<=1.25
    speed=a.voice_speed;out=a.out_dir.resolve() if a.out_dir else HERE
    if speed!=1.0 and out==HERE:raise ValueError('Speed variants require a separate --out-dir')
    out.mkdir(parents=True,exist_ok=True)
    timing=json.loads((HERE.parent/'全片配乐_v6'/f'timeline_{act}.json').read_text(encoding='utf8'))
    original=json.loads(json.dumps(timing));D=timing['duration'];n=round(D*SR)
    manifest=json.loads((HERE/'voices'/act/'manifest.json').read_text(encoding='utf8'));assert not manifest['errors']
    aligned=json.loads((HERE/f'alignment_{act}.json').read_text(encoding='utf8'))
    _,groups=load_act_sentences(HERE.parent/'全片配乐_v6'/f'timeline_{act}.json')
    jobs={(j['who'],round(j['lineStart'],5)):j for j in manifest['lines']}
    def cards(g):return [c for c in timing['cards'] if c['who']==g['who'] and abs(c['lineStart']-g['lineStart'])<1e-5]
    groups_cards=[cards(g) for g in groups];floor=0;changes=[]
    for g,cs in zip(groups,groups_cards):
        requested=g['start'];start=requested if requested>=floor-1e-5 else math.ceil((floor-1e-7)/BEAT)*BEAT
        g['placed_start']=start
        if start-requested>.001:changes.append({'id':g['id'],'who':g['who'],'from':requested,'to':start})
        if g['who']=='rin':
            last=cs[-1];floor=start+(last['start']-requested)+len(last['text'])*STEP+.75
        else:
            j=jobs[(g['who'],round(g['lineStart'],5))];floor=start+j['duration']/speed+.12
    assert floor<D,(floor,D)
    voice=np.zeros((n,2),np.float32);bubbles=np.zeros(n,np.float32);duck=np.ones(n,np.float32)
    pulse=read_audio(HERE.parent/'气泡音候选_20261117/参考分析/参考方向_单音.wav',1)[:,0]*.70
    cache=out/'cache'/act;cache.mkdir(parents=True,exist_ok=True)
    report={'act':act,'duration':D,'voice_speed':speed,'source_music':'Gemini PR73 full score v6','bpm':108,'timing_shifts':changes,'voices':[],'rin':[]}
    def duck_for(start,end,db):
        lo=max(0,round((start-.18)*SR));hi=min(n,round((end+.6)*SR));ts=np.arange(lo,hi)/SR
        curve=np.interp(ts,[start-.18,start+.05,end+.12,end+.6],[1,10**(db/20),10**(db/20),1])
        duck[lo:hi]=np.minimum(duck[lo:hi],curve)
    for gi,(g,cs) in enumerate(zip(groups,groups_cards)):
        start=g['placed_start'];next_start=groups[gi+1]['placed_start'] if gi+1<len(groups) else D
        if g['who']=='rin':
            delta=start-g['start']
            for c in cs:
                c['start']+=delta;c['end']=min(c['end']+delta,next_start-.04);c['lineStart']=start
                length=len(c['text']);available=c['end']-c['start']-.75
                reveal_duration=min(length*STEP,available);assert reveal_duration>0
                c['revealEnd']=c['start']+reveal_duration;c['charStep']=reveal_duration/max(1,length)
                # Pulses accompany the reveal continuously, then stop for the reading hold.
                count=max(1,math.ceil(reveal_duration/STEP))
                for k in range(count):
                    onset=c['start']+k*STEP;at=round(onset*SR);stop=min(n,at+len(pulse),round(c['revealEnd']*SR))
                    if stop>at:
                        chunk=pulse[:stop-at].copy()
                        if len(chunk)<len(pulse):chunk[-min(96,len(chunk)):]*=np.linspace(1,0,min(96,len(chunk)))
                        bubbles[at:stop]+=chunk
                duck_for(c['start'],c['revealEnd'],-3)
                report['rin'].append({'text':c['text'],'start':c['start'],'sound_end':c['revealEnd'],'card_end':c['end'],'reading_hold':c['end']-c['revealEnd'],'pulses':count})
            continue
        j=jobs[(g['who'],round(g['lineStart'],5))];p=HERE/'voices'/act/j['file'];audio=read_audio(p,speed=speed);length=len(audio)/SR
        assert start+length+.02<=next_start,(g['id'],start+length,next_start)
        audio=signal.sosfilt(signal.butter(2,70,'highpass',fs=SR,output='sos'),audio,axis=0).astype(np.float32)
        fade=min(240,len(audio)//4);audio[:fade]*=np.linspace(0,1,fade)[:,None];audio[-fade:]*=np.linspace(1,0,fade)[:,None]
        dry=cache/(p.stem+'-dry.wav');write(dry,audio);m=loudness(dry);gain=min(-18.5-m['input_i'],-3.0-m['input_tp']);audio*=10**(gain/20)
        at=round(start*SR);voice[at:at+len(audio)]+=audio;duck_for(start,start+length,-7.5)
        wanted=''.join(norm(c['text']) for c in cs);actual='';times=[]
        for word in aligned[j['file']]['words']:
            s=norm(word['word']);actual+=s;times.extend([word['start']/speed]*len(s))
        matcher=difflib.SequenceMatcher(None,wanted,actual,autojunk=False);mapping={}
        for block in matcher.get_matching_blocks():
            for k in range(block.size):mapping[block.a+k]=block.b+k
        boundaries=[start];cursor=len(norm(cs[0]['text']))
        for ci in range(1,len(cs)):
            match=next((k for k in range(cursor,min(len(wanted),cursor+10)) if k in mapping),None)
            rel=(times[mapping[match]]-.10*(match-cursor)) if match is not None and matcher.ratio()>.65 else length*cursor/max(1,len(wanted))
            upper=start+length-.18*(len(cs)-ci);new=max(boundaries[-1]+.18,min(upper,start+max(0,rel-.03)))
            boundaries.append(round(new*24)/24);cursor+=len(norm(cs[ci]['text']))
        for ci,c in enumerate(cs):
            c['start']=boundaries[ci];c['lineStart']=start
            c['end']=boundaries[ci+1] if ci+1<len(cs) else min(next_start-.04,max(g['end'],start+length+.35))
            assert c['end']>c['start']
        report['voices'].append({'id':g['id'],'who':g['who'],'file':j['file'],'start':start,'end':start+length,'original_start':g['start'],'duration':length,'gain_db':gain,'script_match_ratio':matcher.ratio(),'subtitle_boundaries':boundaries})
        print('MIX',act,j['seq'],g['who'],round(length,2),flush=True)
    assert all(c['end']<=D and c['end']>c['start'] for c in timing['cards'])
    for previous,current in zip(timing['cards'],timing['cards'][1:]):assert previous['end']<=current['start']+.001
    if act=='第二段':
        for b in timing['beats']:
            cue=next((c for c in timing['cards'] if c.get('beat')==b['index']),None)
            if cue:b['t']=cue['start']
    # Marks belong to subtitle cards; preserve the original within-card offset.
    for key,value in original.get('marks',{}).items():
        idx=next((i for i,c in enumerate(original['cards']) if abs(c['start']-value)<.001),None)
        if idx is not None:timing['marks'][key]=timing['cards'][idx]['start']
    music=read_audio((a.music_dir or HERE)/f'{act}_Gemini配乐.wav');assert abs(len(music)-n)<3
    if len(music)<n:music=np.pad(music,((0,n-len(music)),(0,0)))
    music=music[:n];speech=voice+bubbles[:,None]*.7071;write(out/f'{act}_人声与气泡.wav',speech)
    write(out/f'{act}_纯配音.wav',voice);write(out/f'{act}_气泡.wav',bubbles)
    mix=music*duck[:,None]+speech;mix*=min(1,.94/max(np.max(np.abs(mix)),1e-8));premix=cache/'premix.wav';write(premix,mix)
    m=loudness(premix);gain=min(-18.5-m['input_i'],-1.5-m['input_tp'])
    final=out/f'{act}_混音.wav';subprocess.run([FF,'-y','-v','error','-i',str(premix),'-af',f'volume={gain}dB','-c:a','pcm_s24le',str(final)],check=True)
    report['final_loudness']=loudness(final);report['master_gain_db']=gain
    (out/f'mix_report_{act}.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
    (out/f'timeline_{act}_配音.json').write_text(json.dumps(timing,ensure_ascii=False,indent=2),encoding='utf8')
    (out/f'{act}_字幕.srt').write_text('\n\n'.join(f"{i+1}\n{stime(c['start'])} --> {stime(c['end'])}\n{c['text']}" for i,c in enumerate(timing['cards']))+'\n',encoding='utf8')
    filename={'开场':'opening.html','第二段':'act2.html','第三段':'act3.html','第四段':'act4.html','第五段':'act5.html'}[act];source=ROOT/'devlog/视频素材'/act/filename
    html=source.read_text(encoding='utf8');html=html.replace('<head>','<head>\n<base href="'+source.parent.as_uri()+'/">',1)
    replacement='window.DURATION = '+str(D)+';\nconst VOICE_TIMING='+json.dumps(timing,ensure_ascii=False)+';\ntimeline.splice(0,timeline.length,...VOICE_TIMING.cards);'
    if act=='开场':replacement+='\nObject.assign(stamps,VOICE_TIMING.stamps);'
    elif act=='第二段':replacement+='\nbeatStarts.splice(0,beatStarts.length,...VOICE_TIMING.beats.map(b=>({beat:b.index,t:b.t})));\nfor(let i=0;i<beatStarts.length-1;i++){const b=beatStarts[i]; DUR[b.beat]=Math.min(DUR[b.beat],Math.max(.2,beatStarts[i+1].t-b.t-.05));}'
    if act in ['开场','第二段']:
        html,count=re.subn(r'window\.DURATION\s*=\s*[^;]+;',lambda _:replacement,html,count=1);assert count==1
    else:
        stage=(ROOT/'devlog/视频素材/common/stage.js').read_text(encoding='utf8')
        injection='const VOICE_TIMING='+json.dumps(timing,ensure_ascii=False)+';\n'
        injection+='''
  if(timeline.length!==VOICE_TIMING.cards.length)throw Error('Subtitle card count changed');
  timeline.forEach((c,i)=>{if(c.text!==VOICE_TIMING.cards[i].text)throw Error('Subtitle text changed at '+i);});
  const oldLines={...lines};
  timeline.splice(0,timeline.length,...timeline.map((c,i)=>({...c,...VOICE_TIMING.cards[i]})));
  Object.assign(marks,VOICE_TIMING.marks);
  for(const c of timeline)lines[c.id]=c.lineStart;
  for(const key of Object.keys(pauseTime)){
    const id=window.SCRIPT.lines[Number(key)].id;
    pauseTime[key]+=lines[id]-oldLines[id];
  }
  window.DURATION=VOICE_TIMING.duration;
'''
        marker='  window.MARKS = marks;'
        assert marker in stage
        stage=stage.replace(marker,injection+marker,1)
        tag='<script src="../common/stage.js"></script>'
        assert tag in html
        html=html.replace(tag,'<script>\n'+stage+'\n</script>',1)
    # Overlay a masked text twin: original text remains in layout, preventing line/box jitter.
    extension='''<style>#line{position:relative} #rinReveal{position:absolute;inset:0;padding:inherit;box-sizing:border-box;pointer-events:none;white-space:inherit;color:var(--ink,#3a3631)}</style>
<script>
const fullRender=window.render;
window.render=function(time){fullRender(time);const cue=window.TIMELINE.find(c=>time>=c.start&&time<c.end);const el=document.getElementById('line');let overlay=document.getElementById('rinReveal');
 if(cue&&cue.who==='rin'){
  const chars=Array.from(cue.text);const k=Math.min(chars.length,Math.max(1,Math.floor((time-cue.start)/cue.charStep)+1));
  el.style.color='transparent';if(!overlay){overlay=document.createElement('span');overlay.id='rinReveal';el.appendChild(overlay);}overlay.textContent=chars.slice(0,k).join('');
 }else{el.style.color='';if(overlay)overlay.remove();}
};
</script>'''
    html=html.replace('</body>',extension+'\n</body>');(out/f'{act}_配音预览.html').write_text(html,encoding='utf8')
    print('STAGE_COMPLETE',act,report['final_loudness'],flush=True)
if __name__=='__main__':main()
