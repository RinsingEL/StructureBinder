"""Continuous score with a shared, frame-based visual/music conductor map.

Six character instruments are trial assignments. No speech or borrowed songs.
Rendering uses the bundled SoundFont renderer; existing video edits are retained.
"""
from pathlib import Path
import sys,json,math,subprocess,importlib.util,argparse
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[2]
spec=importlib.util.spec_from_file_location('render_v2',HERE/'renderer.py')
v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
import numpy as np
from scipy import signal,interpolate
import mido
SR=v.SR;FF=v.FF
HITS=json.loads((HERE/'hit_points.json').read_text(encoding='utf8'))
EVENTS=HITS['events'];DURATION=HITS['duration'];ANCHORS=np.array(HITS['anchors'])
WHO={'rin':'rin_piano','gemini':'gemini_flute','claude':'claude_clarinet',
     'astra':'astra_epiano','deepseek':'deepseek_pizz','glm':'glm_vibes'}
PARTS={
 'rin_piano':(0,0,-.12,-26.0,.14),
 'gemini_flute':(73,1,-.23,-27.0,.16),
 'claude_clarinet':(71,2,-.08,-26.3,.16),
 'astra_epiano':(4,3,.12,-26.0,.12),
 'deepseek_pizz':(45,4,.23,-27.0,.11),
 'glm_vibes':(11,5,.08,-28.0,.12),
 'shared_guitar':(24,6,-.24,-30.8,.10),
 'shared_bass':(32,7,0,-29.0,.025),
 'quiet_percussion':(40,9,.10,-35.0,.03),
 'soft_pad':(89,8,.10,-39.0,.09),
}
NOTES={n:[] for n in PARTS};ENV={n:[] for n in PARTS}
CHORDS={'D':(38,[57,61,64,66]),'Bm':(35,[57,61,62,66]),'G':(31,[57,59,62,66]),
 'DF':(30,[57,62,64,66]),'Em':(40,[55,59,62,66]),'A':(33,[55,59,61,64]),
 'As':(33,[55,59,62,64]),'Fsm':(30,[57,61,64,68]),'Gm':(31,[58,62,64,67])}
SECTIONS=[(0,'title'),(6,'welcome'),(86,'intros_warm'),(151,'intros_playful'),
          (221,'handoff'),(252,'blank'),(270,'scan'),(337,'terrain'),(386,'nations'),(458,'sites')]
def sec(beat):return float(np.interp(beat,ANCHORS[:,0],ANCHORS[:,1]))
def beat(t):return float(np.interp(t,ANCHORS[:,1],ANCHORS[:,0]))
def kind_at(b):return next(k for start,k in reversed(SECTIONS) if b>=start)
def harmony(b):
    k=kind_at(b)
    if k in ['title','welcome','intros_warm','intros_playful','handoff']:
        return ['D','D','Bm','Bm','G','DF','Em','As','D','Fsm','Bm','Bm','G','Gm','DF','A'][int(b//4)%16]
    if k=='blank':return ['D','D','G','As'][int((b-252)//4)%4]
    if k=='terrain':return ['G','G','DF','DF','Em','Em','As','As'][int((b-337)//4)%8]
    if k=='nations':return ['G','G','A','A','Fsm','Fsm','Bm','Bm','Em','Em','A','A','D','D','D','As'][int((b-386)//4)%16]
    return ['D','D','Bm','Bm','G','G','Em','A'][int(b//4)%8]

def profile_latency():
    result={}
    for name,(program,ch,*_) in PARTS.items():
        if name not in WHO.values():continue
        s=v.tinysoundfont.Synth(gain=-9,samplerate=SR);sid=s.sfload(str(v.SF));s.program_select(0,sid,0,program)
        s.noteon(0,66,64);a=np.frombuffer(s.generate(SR//3),np.float32).reshape(-1,2)
        a=signal.sosfilt(signal.butter(2,85,'highpass',fs=SR,output='sos'),a,axis=0)
        a=signal.sosfilt(signal.butter(2,6500,'lowpass',fs=SR,output='sos'),a,axis=0)
        env=np.sqrt(np.mean(a[:len(a)//240*240].reshape(-1,240,2)**2,axis=(1,2)))
        result[name]=int(np.flatnonzero(env>=env[:50].max()*.25)[0])*.005
    return result

def add(part,b,p,length,vel,latency=0):
    t=max(0,sec(b)-latency);end=sec(b+length)-latency
    if t<DURATION-.7:NOTES[part].append((t,int(p),max(.07,end-t),int(vel)))

def phrase(name,b,length=8,scale=1,variation=0,latency=0,support=False):
    # The motif is shared; articulation and rhythmic answers carry identity.
    if name in ['gemini_flute','claude_clarinet']:
        line=[(0,66,.94,63),(1,69,.94,68),(2,71,1.43,72),(3.5,69,.9,61),
              (5,66,.9,57),(6,64,1.1,52)]
    elif name=='deepseek_pizz':
        line=[(0,66,.28,65),(.5,69,.28,60),(1.5,71,.38,70),(2.5,69,.30,60),
              (4,66,.4,54),(5,64,.4,50),(6,62,.5,48)]
    else:
        line=[(0,66,.42,64),(.5,69,.42,69),(1.5,71,.85,73),(3,69,.75,61),
              (4.5,66,.7,56),(6,64,.8,52)]
    if variation:
        line=[(0,69,.8,58),(1.5,66,.8,54),(3,64,1.0,49),(5,66,.7,51)]
    used=[]
    for off,p,d,vel in line:
        if off>=length-1:continue
        sym=harmony(b+off)
        if sym=='Gm':p+={11:-1,1:1,6:1}.get(p%12,0)
        if name=='glm_vibes' and not support:p-=12
        add(name,b+off,p,min(d,length-off-.12),round(vel*scale),latency)
        used.append((off,d))
    if not used:return
    t=sec(b)-latency;end=sec(b+max(off+d for off,d in used))-latency
    strength=.38 if support else .90
    # Phrase envelope is audible immediately at its marked onset, then breathes.
    ENV[name].append({'times':[t-.15,t,t+.30,(t+end)*.5,max(t+.31,end-.10),end+.45],
                      'values':[.12,strength*.82,strength,strength*.82,strength*.48,.12]})

def background():
    for bar in range(math.ceil(ANCHORS[-1,0]/4)):
        base=bar*4
        # Pattern follows current content even when the boundary lands mid-bar.
        for bt in [0,.5,1,1.5,2,2.5,3,3.5]:
            b=base+bt;k=kind_at(b);root,ch=CHORDS[harmony(b)]
            if k=='title':pat={0:(0,2.7,43)}
            elif k in ['welcome','intros_warm']:pat={0:(0,1.1,48),1.5:(2,.7,41),2.5:(1,.8,44),3.5:(3,.35,38)}
            elif k=='intros_playful':pat={0:(0,.8,48),1:(2,.65,41),2.5:(1,.8,44),3.5:(3,.3,38)}
            elif k in ['handoff','blank']:pat={0:(0,2.4,43),2:(2,1.5,38)}
            elif k=='scan':pat={0:(0,.8,46),1:(2,.8,39),2:(1,.8,43),3:(3,.8,37)}
            elif k=='terrain':pat={0:(0,2.6,40),2:(2,1.5,35)} if bar%2==0 else {0:(3,2.7,39)}
            elif k=='nations':pat={0:(0,1.1,46),1.5:(2,.7,39),2:(1,1.1,43),3.5:(3,.4,37)}
            else:pat={0:(0,1.1,44),1:(2,.7,37),2:(1,1.1,41),3:(3,.7,36)} if bar%2==0 else {0:(0,1.5,43),2:(2,1.5,38)}
            if bt in pat:
                idx,d,vel=pat[bt];add('shared_guitar',b,ch[idx],d,vel)
            if bt==0 or bt==2 and k not in ['title','blank','terrain']:
                add('shared_bass',b,root if bt==0 else root+7 if root<36 else root-5,2.8 if k=='terrain' else 1.6,54 if bt==0 else 44)
            if k in ['welcome','intros_warm','intros_playful','scan','nations','sites']:
                if k=='scan' and bt in [0,1,2,3]:add('quiet_percussion',b,42,.12,30 if bt%2==0 else 24)
                elif k=='nations' and bt in [.5,1.5,2.5,3.5]:add('quiet_percussion',b,42,.10,24)
                elif k in ['welcome','intros_warm','intros_playful'] and bt in [1,3]:add('quiet_percussion',b,37,.12,32)
                elif k=='sites' and bt in [1,3] and bar%2==0:add('quiet_percussion',b,42,.10,25)
            if bt==0 and k in ['intros_warm','terrain']:
                for p in ch[1:]:add('soft_pad',b,p,3.8,39)
    # Actual musical accents at topic landmarks, including mid-bar changes.
    # They provide the sync point; the longer accompaniment can flow through it.
    for e in EVENTS:
        if e['kind'] in ['blank','scan','terrain','nations','sites']:
            root,ch=CHORDS[harmony(e['beat'])]
            for p in ch[:3]:add('shared_guitar',e['beat'],p,1.2,48)

def build_score(latencies):
    # Small title gesture, before the conversational pulse becomes established.
    for t,p,vel in [(.60,66,61),(1.10,69,57),(1.55,74,53)]:
        NOTES['rin_piano'].append((t,p,.65,vel))
    ENV['rin_piano'].append({'times':[0,.4,.6,1.5,2.7,3.1],'values':[.12,.25,.8,.7,.3,.12]})
    background()
    for i,e in enumerate(EVENTS):
        name=WHO[e['who']];b=e['beat'];end=EVENTS[i+1]['beat'] if i+1<len(EVENTS) else ANCHORS[-1,0]
        available=end-b
        length=min(8,available-1) if b<221 else min(6,available-1)
        phrase(name,b,length,1 if b<221 else .90,latency=latencies[name])
        # Continue participating inside longer speeches. These are real phrases,
        # not a repeated entrance sound on every subtitle card.
        if available>=18:phrase(name,b+10,min(6,available-12),.85,variation=1)
        if available>=30:phrase(name,b+24,min(6,available-26),.77,variation=1)
        # A quiet answer from the preceding player makes the handoff an ensemble.
        if i and available>=18 and EVENTS[i-1]['who']!=e['who']:
            prev=WHO[EVENTS[i-1]['who']]
            phrase(prev,b+6,4,.72,variation=1,support=True)
    # A topic accent can coincide with an accompaniment note. Merge identical
    # key/onset events so an earlier note-off cannot truncate a doubled key.
    for name in NOTES:
        merged={}
        for t,p,d,vel in NOTES[name]:
            key=(round(t,6),p)
            if key in merged:
                old=merged[key];merged[key]=(old[0],p,max(d,old[2]),max(vel,old[3]))
            else:merged[key]=(t,p,d,vel)
        NOTES[name]=sorted(merged.values())

def expression(name,times):
    a=np.asarray(times);out=np.full(a.shape,.12 if name in WHO.values() else .83,dtype=np.float32)
    if name in WHO.values():
        for env in ENV[name]:
            ts=np.array(env['times']);vs=np.array(env['values'])
            # Coalesce duplicate/interior knots for very short answer phrases.
            ix=np.argsort(ts);ts=ts[ix];vs=vs[ix]
            ts,idx=np.unique(ts,return_index=True);vs=vs[idx]
            lo=np.searchsorted(a,ts[0]);hi=np.searchsorted(a,ts[-1],side='right')
            if hi>lo:out[lo:hi]=np.maximum(out[lo:hi],interpolate.PchipInterpolator(ts,vs)(a[lo:hi]))
    else:
        # A shallow continuous duck makes room for entrances without dropping
        # the music away or suppressing all character accompaniment.
        for e in EVENTS:
            t=e['hit'];lo=np.searchsorted(a,t-.2);hi=np.searchsorted(a,t+1.2)
            if hi>lo:out[lo:hi]*=np.interp(a[lo:hi],[t-.2,t+.08,t+.55,t+1.2],[1,.82,.82,1])
    # Shift gently from the lively welcome to the quieter explanatory section.
    out*=np.interp(a,[0,132,142,DURATION],[1,1,.80,.80])
    return out

def export_midi():
    mid=mido.MidiFile(ticks_per_beat=960);meta=mido.MidiTrack();mid.tracks.append(meta)
    meta.extend([mido.MetaMessage('track_name',name='Visual/music conductor'),mido.MetaMessage('key_signature',key='D'),mido.MetaMessage('time_signature',numerator=4,denominator=4)])
    ev=[]
    for (b,t),(b2,t2) in zip(ANCHORS,ANCHORS[1:]):
        ev.append((round(b*960),mido.MetaMessage('set_tempo',tempo=round((t2-t)/(b2-b)*1e6))))
    for e in EVENTS:ev.append((round(e['beat']*960),mido.MetaMessage('marker',text=f"{e['id']} {e['who']}")))
    pos=0
    for tick,msg in sorted(ev,key=lambda x:x[0]):msg.time=tick-pos;meta.append(msg);pos=tick
    for name,(program,ch,pan,*_) in PARTS.items():
        tr=mido.MidiTrack();mid.tracks.append(tr);tr.append(mido.MetaMessage('track_name',name=name))
        tr.extend([mido.Message('program_change',channel=ch,program=program),mido.Message('control_change',channel=ch,control=10,value=round(64+pan*45))])
        ev=[]
        for t,p,d,vel in NOTES[name]:
            ev.extend([(round(beat(t)*960),0,mido.Message('note_on',channel=ch,note=p,velocity=vel)),(round(beat(t+d)*960),1,mido.Message('note_off',channel=ch,note=p,velocity=0))])
        ts=np.arange(0,DURATION,.05);ex=expression(name,ts)
        for t,amp in zip(ts,ex):ev.append((round(beat(t)*960),2,mido.Message('control_change',channel=ch,control=11,value=round(float(np.clip(amp,0,1))*127))))
        pos=0
        for tick,_,msg in sorted(ev,key=lambda x:(x[0],x[1])):msg.time=tick-pos;tr.append(msg);pos=tick
    mid.save(HERE/'开场至七国_同步配乐.mid')

def onset_checks(name,a,latencies):
    checks=[]
    for e in EVENTS:
        if WHO[e['who']]!=name:continue
        start=max(0,round((e['hit']-.09)*SR));end=round((e['hit']+.24)*SR)
        clip=a[start:end];hop=240
        level=np.sqrt(np.mean(clip[:len(clip)//hop*hop].reshape(-1,hop,2)**2,axis=(1,2)))
        idx=int(np.flatnonzero(level>=level.max()*.25)[0]);detected=start/SR+idx*.005
        checks.append({'id':e['id'],'who':e['who'],'visual_hit_s':e['hit'],
           'note_on_s':e['hit']-latencies[name],'detected_dry_onset_s':detected,
           'offset_ms':round((detected-e['hit'])*1000,2),'method':'5 ms RMS >25% of local 0.33 s peak, dry individual part; proxy, not perceptual acceptance'})
    return checks

def main():
    parser=argparse.ArgumentParser(description='Render the five-minute reference score.')
    parser.add_argument('--audio-only',action='store_true',help='Skip video input and video muxing; render MIDI/WAV/MP3.')
    parser.add_argument('--soundfont',type=Path,help='Override the default GeneralUser GS SoundFont path.')
    args=parser.parse_args()
    if args.soundfont is not None:v.SF=args.soundfont.expanduser().resolve()
    if not v.SF.is_file():parser.error('SoundFont missing: '+str(v.SF))
    v.RT.mkdir(parents=True,exist_ok=True)
    latency=profile_latency();print('Latency compensation',latency,flush=True)
    build_score(latency);export_midi()
    v.HERE=HERE;v.DURATION=DURATION;v.PARTS=PARTS;v.NOTES=NOTES;v.curve=expression
    n=round(DURATION*SR);mix=np.zeros((n,2),np.float32);send=np.zeros_like(mix);gr={};audit=[]
    for name in PARTS:
        print('Render',name,flush=True)
        a,amount,gr[name]=v.render(name,True)
        if name in WHO.values():audit+=onset_checks(name,a,latency)
        mix+=a;send+=a*amount
    rng=np.random.default_rng(120);t=np.arange(int(.65*SR))/SR
    ir=rng.normal(size=len(t))*np.exp(-t*10);ir[:int(.022*SR)]=0
    ir=signal.sosfilt(signal.butter(2,4200,'lowpass',fs=SR,output='sos'),ir);ir=(ir/np.linalg.norm(ir)*.4).astype(np.float32)
    for side in range(2):mix[:,side]+=signal.fftconvolve(send[:,side],ir,mode='full')[:n]
    t=np.arange(n)/SR;mix*=np.minimum(np.clip(t/.08,0,1),np.clip((DURATION-t)/1.1,0,1))[:,None]
    mix*=min(1,.85/np.max(np.abs(mix)))
    premix=v.RT/'v4-premix.wav';v.writewav(premix,mix)
    m=v.loudness(premix);gain=min(-21-m['input_i'],-1.7-m['input_tp'])
    out=HERE/'开场至七国_同步配乐.wav'
    subprocess.run([FF,'-y','-v','error','-i',str(premix),'-af',f'volume={gain}dB','-c:a','pcm_s24le',str(out)],check=True)
    subprocess.run([FF,'-y','-v','error','-i',str(out),'-c:a','libmp3lame','-b:a','224k',str(out.with_suffix('.mp3'))],check=True)
    src=ROOT/'devlog/视频素材/第二版完整版/开放日_完整版.mp4'
    if not args.audio_only:
        subprocess.run([FF,'-y','-v','error','-i',str(src),'-i',str(out),'-vf','trim=start_frame=0:end_frame=7250,setpts=PTS-STARTPTS','-map','0:v:0','-map','1:a:0','-t',str(DURATION),'-c:v','libx264','-preset','fast','-crf','21','-c:a','aac','-b:a','192k','-movflags','+faststart',str(HERE/'开场至第二段_连续样片_v4.mp4')],check=True)
    tempos=[(b2-b)*60/(t2-t) for (b,t),(b2,t2) in zip(ANCHORS,ANCHORS[1:])]
    report={'duration':DURATION,'source_frames':[0,7250],'instruments':PARTS,'character_instruments':WHO,'tempo_min_max':[min(tempos),max(tempos)],
      'latency_compensation_s':latency,'sync_audit':sorted(audit,key=lambda x:x['visual_hit_s']),
      'rhythm_sections':[{'beat':b,'time':sec(b),'kind':k} for b,k in SECTIONS],
      'loudness':v.loudness(out),'compression_max_db':gr,'note_counts':{k:len(x) for k,x in NOTES.items()}}
    (HERE/'render_report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
    (HERE/'score_events.json').write_text(json.dumps({'notes':NOTES,'envelopes':ENV},ensure_ascii=False,indent=2),encoding='utf8')
    print(json.dumps(report,ensure_ascii=False),flush=True)
if __name__=='__main__':main()
