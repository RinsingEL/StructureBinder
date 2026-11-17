"""Read-only audio/timeline audit; writes separate evidence, never changes the mix."""
from pathlib import Path
import sys,json,re,wave,warnings,subprocess,html
HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE.parents[2]/'.tools/devlog-audio/python'))
import numpy as np
from scipy.io import wavfile
OUT=HERE/'节奏排查';OUT.mkdir(exist_ok=True)
def load(p):return json.loads(p.read_text(encoding='utf8'))
def clock(t):return f'{int(t)//60:02}:{t%60:05.2f}'
def spans(mask):
    e=np.diff(np.r_[False,mask,False].astype(int));return list(zip(np.where(e==1)[0],np.where(e==-1)[0]))
def analyze(p):
    # The approved opening WAVs retain streaming RIFF lengths; read real PCM to EOF.
    with warnings.catch_warnings():
        warnings.filterwarnings('ignore',message='Reached EOF prematurely')
        sr,x=wavfile.read(p)
    x=x.astype(float)/(32768 if x.dtype==np.int16 else 1)
    if x.ndim>1:x=x.mean(axis=1)
    hop=round(sr*.01);n=len(x)//hop
    rms=np.sqrt(np.mean(x[:n*hop].reshape(n,hop)**2,axis=1))
    # Relative 35 dB threshold avoids dependence on file gain. Bridge <80ms phoneme gaps.
    threshold=max(float(np.max(rms))*10**(-35/20),1e-9)
    active=rms>threshold
    for a,b in spans(~active):
        if a>0 and b<n and b-a<8:active[a:b]=True
    voiced=np.flatnonzero(active)
    first,last=(voiced[0]*.01,(voiced[-1]+1)*.01) if len(voiced) else (0,len(x)/sr)
    pauses=[(a*.01,b*.01) for a,b in spans(~active) if a*.01>first and b*.01<last and b-a>=65]
    return {'first':first,'last':last,'pauses':pauses,'threshold_relative_db':-35}
all_result={}
for act in ['开场','第二段']:
    mix=load(HERE/f'mix_report_{act}.json');manifest=load(HERE/'voices'/act/'manifest.json')
    jobs={j['file']:j for j in manifest['lines']};aligned=load(HERE/f'alignment_{act}.json')
    voices=mix['voices'];events=[];internal=[];boundaries=[];rates=[]
    for v in voices:
        j=jobs[v['file']];a=analyze(HERE/'voices'/act/v['file']);v['signal']=a;v['text']=j['text']
        events.append({'kind':'voice','id':v['id'],'who':v['who'],'start':v['start'],'end':v['end'],'audible_start':v['start']+a['first'],'audible_end':v['start']+a['last'],'text':j['text'],'file':v['file']})
        words=aligned[v['file']]['words']
        for lo,hi in a['pauses']:
            before=''.join(w['word'] for w in words if w['end']<=lo+.15)[-25:]
            after=''.join(w['word'] for w in words if w['start']>=hi-.15)[:25]
            internal.append({'id':v['id'],'who':v['who'],'time':v['start']+lo,'end':v['start']+hi,'duration':hi-lo,'relative_start':lo,'relative_end':hi,'before':before,'after':after,'file':v['file']})
        count=len(re.findall(r'[\u4e00-\u9fff]',j['text']))
        rates.append({'id':v['id'],'who':v['who'],'time':v['start'],'hanzi_per_second':count/max(.1,a['last']-a['first']),'text':j['text'],'file':v['file']})
    for i,r in enumerate(mix['rin']):events.append({'kind':'bubble','id':f'Rin卡{i+1}','who':'rin','start':r['start'],'end':r['sound_end'],'audible_start':r['start'],'audible_end':r['sound_end'],'text':r['text']})
    events.sort(key=lambda e:e['start'])
    for a,b in zip(events,events[1:]):
        if a['kind']=='voice' and b['kind']=='voice':
            gap=b['audible_start']-a['audible_end'];external=b['start']-a['end']
            boundaries.append({'time':a['audible_end'],'next_time':b['audible_start'],'gap':gap,'inserted_gap':external,'file_edge_silence':gap-external,'from':a['id'],'to':b['id'],'who':a['who']+' → '+b['who'],'before':a['text'][-35:],'after':b['text'][:35],'same_speaker':a['who']==b['who']})
    all_result[act]={'voices':voices,'boundaries':boundaries,'internal_pauses':internal,'rates':rates,'events':events}
    print(act,'voices',len(voices),'internal pauses >=.65s',len(internal),flush=True)
    print('LONG',json.dumps(sorted(boundaries,key=lambda x:-x['gap'])[:12],ensure_ascii=False),flush=True)
    print('SHORT',json.dumps(sorted(boundaries,key=lambda x:x['gap'])[:10],ensure_ascii=False),flush=True)
    print('INTERNAL',json.dumps(sorted(internal,key=lambda x:-x['duration'])[:12],ensure_ascii=False),flush=True)
    print('FAST',json.dumps(sorted(rates,key=lambda x:-x['hanzi_per_second'])[:6],ensure_ascii=False),flush=True)
(OUT/'audit.json').write_text(json.dumps(all_result,ensure_ascii=False,indent=2),encoding='utf8')
lines=['# 开场与第二段：配音节奏排查','', '时间使用各自单独视频的时间；合并视频的第二段需加 02:06.67。','', '这是原始 WAV 能量及既有 ASR 时间戳的客观排查，不是逐段人工听审。阈值只用于筛查；呼吸、停顿和演出留白须结合语境判断。ASR 仅供附近台词定位，不替换剧本。未重新生成或改动成片。','']
for act,result in all_result.items():
    lines += [f'## {act}','', '### 两个独立 WAV 之间的衔接（全部）','', '| 前句结束 → 后句开始 | 角色 / ID | 可听间隔 | 时间轴外加 / 文件边缘 | 附近台词 |','|---|---|---:|---:|---|']
    for b in result['boundaries']:
        lines.append(f"| {clock(b['time'])} → {clock(b['next_time'])} | {b['who']} / {b['from']}→{b['to']} | {b['gap']:.2f}s | {b['inserted_gap']:.2f}s / {b['file_edge_silence']:.2f}s | {b['before']} → {b['after']} |")
    lines+=['','### 原始单个 WAV 内 ≥0.65秒的低能量停顿','', '| 时间 | 角色 / ID | 长度 | ASR 附近词 |','|---|---|---:|---|']
    for p in result['internal_pauses']:lines.append(f"| {clock(p['time'])}–{clock(p['end'])} | {p['who']} / {p['id']} | {p['duration']:.2f}s | {p['before']} 【停顿】 {p['after']} |")
    lines+=['','### 各段整体语速（汉字/秒，仅筛查，不含英文、数字）','','| 开始 | 角色 / ID | 汉字/秒 | 台词 |','|---|---|---:|---|']
    for r in sorted(result['rates'],key=lambda r:-r['hanzi_per_second']):lines.append(f"| {clock(r['time'])} | {r['who']} / {r['id']} | {r['hanzi_per_second']:.2f} | {r['text']} |")
(OUT/'具体位置.md').write_text('\n'.join(lines)+'\n',encoding='utf8')
page=['<!doctype html><html lang="zh"><meta charset="utf-8"><title>配音节奏定位试听</title><style>body{font:16px system-ui;max-width:1200px;margin:30px auto;background:#faf8f3;color:#262932}table{border-collapse:collapse;width:100%}td,th{border:1px solid #ccc;padding:9px;text-align:left}button{padding:8px;cursor:pointer}header{position:sticky;top:0;background:#faf8f3;padding:15px;border-bottom:2px solid #888}audio{width:80%}small{color:#666}</style><header><b id="now">点击任一条试听</b><br><audio id="player" controls></audio></header><h1>配音节奏定位试听</h1><p>单段视频时间；合并版第二段加 02:06.67。每条带前后语境。只读测量筛查，未做主观听审；长停顿不自动等于错误。原声按钮直接读取单条 WAV，可核对是否为原生停顿。</p>']
def button(label,src,start,end):
    args=','.join(json.dumps(x,ensure_ascii=False) for x in [src,max(0,start),end,label])
    return '<button onclick="'+html.escape('play('+args+')',quote=True)+'">'+html.escape(label)+'</button>'
for act,r in all_result.items():
    page.append('<h2>'+act+'</h2><h3>独立录音之间：全部衔接</h3><table><tr><th>时间</th><th>角色 / 间隔</th><th>语境</th><th>试听</th></tr>')
    for b in r['boundaries']:
        src='../'+act+'_混音.wav';mark='较快候选' if b['gap']<.5 else '较长候选' if b['gap']>=2 else '对照'
        page.append(f"<tr><td>{clock(b['time'])} → {clock(b['next_time'])}</td><td>{b['who']}<br>{b['gap']:.2f}s · {mark}<br><small>外加 {b['inserted_gap']:.2f}s</small></td><td>{html.escape(b['before'])}<br>→ {html.escape(b['after'])}</td><td>{button(act+' '+clock(b['time']),src,b['time']-3,b['next_time']+4)}</td></tr>")
    page.append('</table><h3>单条录音内部：≥0.65秒低能量停顿</h3><table><tr><th>时间</th><th>角色 / 长度</th><th>附近词（ASR）</th><th>成片与原声</th></tr>')
    for p in r['internal_pauses']:
        page.append(f"<tr><td>{clock(p['time'])}–{clock(p['end'])}</td><td>{p['who']} / {p['duration']:.2f}s</td><td>{html.escape(p['before'])}<br>→ {html.escape(p['after'])}</td><td>{button('混音 '+clock(p['time']),'../'+act+'_混音.wav',p['time']-2,p['end']+3)} {button('原声 '+p['file'],'../voices/'+act+'/'+p['file'],p['relative_start']-2,p['relative_end']+3)}</td></tr>")
    page.append('</table>')
page.append('''<script>const a=document.getElementById('player');let stop=Infinity;function play(src,t,end,label){stop=end;document.getElementById('now').textContent=label;a.src=src;a.onloadedmetadata=()=>{a.currentTime=t;a.play().catch(()=>{});};a.load();}a.ontimeupdate=()=>{if(a.currentTime>=stop)a.pause();};</script></html>''')
(OUT/'定位试听.html').write_text('\n'.join(page),encoding='utf8')
import imageio_ffmpeg
ff=imageio_ffmpeg.get_ffmpeg_exe()
examples=[('开场_接话等待', '开场',97,108),('第二段_问答等待','第二段',42,55),('第二段_快速接话','第二段',522,532),('第二段_原生停顿','第二段',25,32)]
for name,act,start,end in examples:
    subprocess.run([ff,'-y','-v','error','-ss',str(start),'-i',str(HERE/f'{act}_混音.wav'),'-t',str(end-start),'-c:a','pcm_s16le',str(OUT/(name+'.wav'))],check=True)
print('REPORT',OUT,flush=True)
