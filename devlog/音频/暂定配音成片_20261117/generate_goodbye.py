"""Generate the final five-voice goodbye; cache each role to avoid repeated API calls."""
from pathlib import Path
import sys,json,os,io,wave,hashlib,subprocess
HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE.parent/'TTS/fish_tts_pack'))
sys.path.insert(0,str(HERE.parents[2]/'.tools/devlog-audio/python'))
from fish_common import load_ref_cache,call_fish_tts
from run_fish_timeline import load_act_sentences
import imageio_ffmpeg
folder=HERE/'voices/第五段';cache=folder/'chorus';cache.mkdir(exist_ok=True)
refs=load_ref_cache(); paths=[]
for who,ref in refs.items():
    p=cache/(who+'.wav');paths.append(p)
    if p.exists():continue
    data=call_fish_tts(os.environ['FISH_API_KEY'],'s2.1-pro-free','大家再见！',ref)
    with wave.open(io.BytesIO(data),'rb') as w:params=w.getparams();pcm=w.readframes(w.getnframes())
    with wave.open(str(p),'wb') as w:w.setparams(params);w.writeframes(pcm)
    print('GOODBYE',who,flush=True)
dest=folder/'006_all_5-7.wav'
args=[imageio_ffmpeg.get_ffmpeg_exe(),'-y','-v','error']
for p in paths:args+=['-i',str(p)]
args+=['-filter_complex',f'amix=inputs={len(paths)}:duration=longest:normalize=1','-ar','44100','-ac','1','-c:a','pcm_s16le',str(dest)]
subprocess.run(args,check=True)
with wave.open(str(dest),'rb') as w:duration=w.getnframes()/w.getframerate()
_,groups=load_act_sentences(HERE.parent/'全片配乐_v6/timeline_第五段.json')
job=dict(next(g for g in groups if g['who']=='all'));job.update(seq=6,file=dest.name,duration=duration,status='five-role chorus')
manifest=json.loads((folder/'manifest.json').read_text(encoding='utf8'))
manifest['lines']=[j for j in manifest['lines'] if j['who']!='all']+[job]
(folder/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf8')
(dest.with_suffix('.json')).write_text(json.dumps({'text':job['text'],'sha256':hashlib.sha256(dest.read_bytes()).hexdigest(),'duration':duration,'sources':[str(p.relative_to(folder)) for p in paths],'model':'s2.1-pro-free'},ensure_ascii=False,indent=2),encoding='utf8')
print('CHORUS_COMPLETE',duration,flush=True)
