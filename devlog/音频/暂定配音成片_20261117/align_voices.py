"""Local word timing for subtitle placement; reference transcriptions are not rechecked."""
from pathlib import Path
import argparse,os,sys,json,subprocess,hashlib
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[2]
CACHE=ROOT/'.tools/devlog-audio/opening-tts'
os.environ.update(HF_HOME=str(CACHE/'hf'),HF_HUB_OFFLINE='1',TOKENIZERS_PARALLELISM='false')
sys.path.insert(0,str(ROOT/'.tools/devlog-audio/asr-python'))
from faster_whisper import WhisperModel
import numpy as np
FF=ROOT/'.tools/devlog-audio/python/imageio_ffmpeg/binaries/ffmpeg-win-x86_64-v7.1.exe'
ap=argparse.ArgumentParser();ap.add_argument('--act',required=True);args=ap.parse_args()
folder=HERE/'voices'/args.act;manifest=json.loads((folder/'manifest.json').read_text(encoding='utf8'))
out=HERE/f'alignment_{args.act}.json';result=json.loads(out.read_text(encoding='utf8')) if out.exists() else {}
model=WhisperModel('small',device='cpu',compute_type='int8',cpu_threads=6,num_workers=1,download_root=str(CACHE/'asr-models'),local_files_only=True)
for item in manifest['lines']:
    p=folder/item['file'];sha=hashlib.sha256(p.read_bytes()).hexdigest()
    if item['file'] in result and result[item['file']]['sha256']==sha:continue
    pcm=subprocess.run([str(FF),'-v','error','-i',str(p),'-f','f32le','-ac','1','-ar','16000','pipe:1'],capture_output=True,check=True).stdout
    segments,info=model.transcribe(np.frombuffer(pcm,np.float32),language='zh',beam_size=5,temperature=0,word_timestamps=True,vad_filter=False,condition_on_previous_text=False,initial_prompt='社团开放日，Gemini，Claude，Astra，DeepSeek，GLM，镜潮学院，内置规划。')
    words=[];texts=[]
    for s in segments:
        texts.append(s.text);words.extend({'word':w.word,'start':w.start,'end':w.end,'probability':w.probability} for w in s.words)
    result[item['file']]={'sha256':sha,'text':''.join(texts),'words':words}
    out.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
    print(args.act,item['seq'],item['file'],flush=True)
print('ALIGNMENT_COMPLETE',args.act,flush=True)
