"""Generate only the requested act, using the user-confirmed reference snapshot."""
from pathlib import Path
import argparse, concurrent.futures, hashlib, io, json, os, shutil, sys, time, wave
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
PACK = HERE.parent / 'TTS/fish_tts_pack'
SNAPSHOT = HERE.parent / 'TTS/暂定音色_20261117'
sys.path.insert(0, str(PACK))
from fish_common import load_ref_cache, call_fish_tts
from run_fish_timeline import load_act_sentences

def save_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf8')

def wav_info(path):
    with wave.open(str(path), 'rb') as w:
        return len(w.readframes(w.getnframes())) / (w.getframerate()*w.getnchannels()*w.getsampwidth())

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--act', required=True); ap.add_argument('--workers',type=int,default=3); a=ap.parse_args()
    timing=json.loads((HERE.parent/'全片配乐_v6'/f'timeline_{a.act}.json').read_text(encoding='utf8'))
    duration, lines=load_act_sentences(PACK/'timelines'/f'timeline_{a.act}.json')
    assert timing == json.loads((PACK/'timelines'/f'timeline_{a.act}.json').read_text(encoding='utf8'))
    refs=load_ref_cache(); selected=json.loads((PACK/'current_voice_set.json').read_text(encoding='utf8'))
    for who,ref in refs.items():
        assert hashlib.sha256(ref['audio']).hexdigest()==selected['roles'][who]['sha256']
    dest=HERE/'voices'/a.act; dest.mkdir(parents=True,exist_ok=True)
    jobs=[]
    for i,line in enumerate(lines):
        if line['who'] not in refs: continue
        job=dict(line); job['seq']=len(jobs)+1
        job['next_start']=lines[i+1]['start'] if i+1<len(lines) else duration
        job['file']=f"{job['seq']:03d}_{job['who']}_{line['id'] or 'L'+str(job['seq']).zfill(2)}.wav"
        jobs.append(job)
    save_json(dest/'plan.json', {'act':a.act,'duration':duration,'lines':jobs})
    snapshot=json.loads((SNAPSHOT/'音色配置.json').read_text(encoding='utf8'))
    def generate(job):
        p=dest/job['file']; meta=p.with_suffix('.json'); ref=refs[job['who']]
        digest=hashlib.sha256(ref['audio']).hexdigest()
        if p.exists() and meta.exists():
            old=json.loads(meta.read_text(encoding='utf8'))
            if old['text']==job['text'] and old['reference_sha256']==digest:
                job.update(duration=wav_info(p),status='cached');return job
        t=time.monotonic()
        if a.act=='开场':
            selected_role=snapshot['roles'][job['who']]
            assert selected_role['opening_text']==job['text']
            shutil.copy2(SNAPSHOT/selected_role['opening_audio'],p); status='confirmed opening reused'
        else:
            audio=call_fish_tts(os.environ['FISH_API_KEY'],'s2.1-pro-free',job['text'],ref)
            with wave.open(io.BytesIO(audio),'rb') as w:
                params=w.getparams();pcm=w.readframes(w.getnframes())
            assert len(pcm)>params.framerate*params.nchannels*params.sampwidth
            temp=p.with_suffix('.part.wav')
            with wave.open(str(temp),'wb') as w: w.setparams(params);w.writeframes(pcm)
            temp.replace(p); status='generated'
        d=wav_info(p)
        save_json(meta,{'model':'s2.1-pro-free','text':job['text'],'reference_sha256':digest,'reference_text':ref['text'],
                       'duration':d,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'status':status})
        job.update(duration=d,status=status)
        print(f"{a.act} {job['seq']:02d}/{len(jobs)} {job['who']} {d:.2f}s / slot {job['next_start']-job['start']:.2f}s ({time.monotonic()-t:.1f}s)",flush=True)
        return job
    errors=[];finished=[]
    with concurrent.futures.ThreadPoolExecutor(max_workers=a.workers) as pool:
        pending={pool.submit(generate,j):j for j in jobs}
        for future in concurrent.futures.as_completed(pending):
            try:finished.append(future.result())
            except BaseException as e:
                j=pending[future];errors.append({'file':j['file'],'error':str(e)});print('FAILED',j['file'],str(e),flush=True)
            save_json(dest/'manifest.json',{'act':a.act,'duration':duration,'lines':sorted(finished,key=lambda j:j['seq']),'errors':errors})
    if errors: raise SystemExit('Some requests failed; rerun to resume successful files.')
    print('COMPLETE',a.act,len(jobs),flush=True)
if __name__=='__main__':main()
