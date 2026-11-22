"""Render Gemini PR73's unchanged score through the end of act 2."""
from pathlib import Path
import sys,json,importlib.util,subprocess,argparse
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[2]
sys.path.insert(0,str(ROOT/'.tools/devlog-audio/python'))
import numpy as np
from scipy import signal
SOURCE=HERE.parent/'全片配乐_v6'
spec=importlib.util.spec_from_file_location('gemini_score',SOURCE/'compose_all.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
v=c.v
ap=argparse.ArgumentParser();ap.add_argument('--full',action='store_true');ap.add_argument('--out-dir',type=Path);args=ap.parse_args()
out=args.out_dir.resolve() if args.out_dir else HERE;out.mkdir(parents=True,exist_ok=True)
acts,offsets,op_beats,total_beats,full_duration,events,sections=c.load_all_timelines()
latency=c.c12.profile_latency();notes,env=c.make_full_score(op_beats,offsets,total_beats,full_duration,events,sections,latency)
duration=full_duration if args.full else acts['开场']['duration']+acts['第二段']['duration']
v.DURATION=duration;v.PARTS=c.PARTS;v.NOTES={k:[n for n in ns if n[0]<duration] for k,ns in notes.items()}
v.curve=c.c12.build_expression(env,events,full_duration,op_beats)
cache=out/'cache';cache.mkdir(exist_ok=True)
n=round(duration*v.SR);mix=np.zeros((n,2),np.float32);send=np.zeros_like(mix)
for name in c.PARTS:
    print('Render',name,flush=True);a,amount,_=v.render(name,True);mix+=a;send+=a*amount;del a
ir=v.make_reverb_ir()
for side in (0,1):mix[:,side]+=signal.fftconvolve(send[:,side],ir)[:n]
del send
fade=round(1.2*v.SR);mix[:3840]*=np.linspace(0,1,3840)[:,None];mix[-fade:]*=np.linspace(1,0,fade)[:,None]
mix*=min(1,.85/max(np.max(np.abs(mix)),1e-6));premix=cache/'music-premix.wav';v.writewav(premix,mix);del mix
m=v.loudness(premix);gain=min(-20.5-m['input_i'],-1.7-m['input_tp'])
slices=[];position=0
for act in (['开场','第二段','第三段','第四段','第五段'] if args.full else ['开场','第二段']):
    slices.append((act,position,acts[act]['duration']));position+=acts[act]['duration']
for act,start,length in slices:
    dest=out/f'{act}_Gemini配乐.wav'
    subprocess.run([v.FF,'-y','-v','error','-ss',str(start),'-i',str(premix),'-t',str(length),'-af',f'volume={gain}dB','-c:a','pcm_s24le',str(dest)],check=True)
(out/'music_source.json').write_text(json.dumps({'source':'全片配乐_v6/compose_all.py','git_commit':'c5ae5158a','duration':duration,'bpm':108,'global_gain_db':gain,'note_counts':{k:len(ns) for k,ns in v.NOTES.items()},'note':'Original composition and expression preserved; continuous rendering, then split into selected acts.'},ensure_ascii=False,indent=2),encoding='utf8')
print('MUSIC_COMPLETE',flush=True)
