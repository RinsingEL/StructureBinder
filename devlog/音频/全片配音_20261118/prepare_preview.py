from pathlib import Path
import json,re,urllib.parse
HERE=Path(__file__).resolve().parent
ACTS=['开场','第二段','第三段','第四段','第五段']
def load(p):return json.loads(p.read_text(encoding='utf8'))
def clock(t):return f'{int(t)//60:02}:{t%60:05.2f}'
audit=load(HERE/'节奏排查/audit.json');offset=0;acts=[];issues=[]
for act in ACTS:
    timing=load(HERE/f'timeline_{act}_配音.json')
    p=HERE/f'{act}_配音预览.html';s=p.read_text(encoding='utf8')
    s=re.sub(r'<base href="[^"]+">',f'<base href="../../视频素材/{act}/">',s,count=1)
    if act=='第二段' and 'LIVE_ATLAS_PREVIEW' not in s:
        s=s.replace('</body>','''<script>
// LIVE_ATLAS_PREVIEW: use the real atlas renderer, not static JPG pages.
window.externalPpt=true;const atlasFrame=document.createElement('iframe');
atlasFrame.src=new URL('../../act2/wtd-atlas.html',document.baseURI).href;
Object.assign(atlasFrame.style,{position:'absolute',left:'0',top:'0',width:'1600px',height:'900px',border:'0',transform:'scale(0.89875)',transformOrigin:'0 0',pointerEvents:'none'});
document.getElementById('board').style.overflow='hidden';document.getElementById('board').appendChild(atlasFrame);
let atlasReady=false,lastTime=0;
atlasFrame.onload=()=>{atlasFrame.contentWindow.eval(`
cancelAnimationFrame(frame);frame=0;
window.previewSeek=(i,pr)=>{cancelAnimationFrame(frame);frame=0;
 const prevT=i>0?beats[i-1][4]:base;index=i;scene=legacyScene(beats[i][0]);displayMeta();
 const target=beats[i][4],tt=ease(Math.max(0,Math.min(1,pr)));current={};
 for(const k in target)current[k]=mix(prevT[k]??target[k],target[k],tt);if(scene===7)current.claims=mix(Math.max(-.001,prevT.claims),1,clamp(pr/.88));draw(current,pr);
};`);atlasReady=true;window.render(lastTime);};
const voicedRender=window.render;window.render=t=>{lastTime=t;voicedRender(t);const b=window.beatAt(t);atlasFrame.style.visibility=b?'visible':'hidden';if(b&&atlasReady)atlasFrame.contentWindow.previewSeek(b.index,b.progress);};
</script></body>''')
    p.write_text(s,encoding='utf8')
    acts.append({'name':act,'offset':offset,'duration':timing['duration'],'cards':timing['cards'],'html':p.name,'audio':act+'_试听.m4a'})
    for b in audit[act]['boundaries']:
        if b['gap']<.5:kind='接话较紧'
        elif b['gap']>=2:kind='较长间隔'
        else:continue
        marknames=[k for k,t in timing.get('marks',{}).items() if b['time']-.1<=t<=b['next_time']+.5]
        notes='连续讲解' if b['same_speaker'] else '换人接话'
        if marknames:notes+='；附近有画面事件 '+','.join(marknames)+'，需结合转场判断'
        issues.append({'act':act,'type':kind,'time':b['time'],'global':offset+b['time'],'end':b['next_time'],'duration':b['gap'],'who':b['who'],'text':b['before']+' → '+b['after'],'note':notes+f"；时间轴外加 {b['inserted_gap']:.2f}s，录音边缘 {b['file_edge_silence']:.2f}s"})
    for p in audit[act]['internal_pauses']:
        issues.append({'act':act,'type':'原声内部停顿','time':p['time'],'global':offset+p['time'],'end':p['end'],'duration':p['duration'],'who':p['who'],'text':p['before']+' 【停顿】 '+p['after'],'note':'单个原始 WAV 中已存在；句号、呼吸或表演停顿可能合理。附近词来自 ASR，可能有错字。'})
    offset+=timing['duration']
issues.sort(key=lambda x:x['global'])
data={'duration':offset,'acts':acts,'issues':issues}
(HERE/'preview_data.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf8')
lines=['# 全片接话节奏候选清单','','这是一份波形和时间轴筛查清单，尚未逐条人工听审；候选不等于错误。Rin 的逐字气泡后阅读停留不计入 AI 句间问题。全片原速，总长约37:40。','','| 幕 | 接话 <0.5s | 间隔 ≥2s | 原声内部 ≥0.65s |','|---|---:|---:|---:|']
for act in ACTS:
    counts=[sum(i['act']==act and i['type']==k for i in issues) for k in ['接话较紧','较长间隔','原声内部停顿']]
    lines.append('| '+act+' | '+' | '.join(map(str,counts))+' |')
lines+=['','阈值只用于发现候选；涉及看图、镜头运动和主题切换的间隔应按语境保留。可在预览播放器的候选列表中点选，自动跳到事发前3秒。','','| 全片 / 幕内 | 幕与角色 | 类型 / 时长 | 附近台词 | 判断提示 |','|---|---|---|---|---|']
for i in issues:
    lines.append(f"| {clock(i['global'])} / {clock(i['time'])} | {i['act']} {i['who']} | {i['type']} {i['duration']:.2f}s | {i['text'].replace(chr(10),' ')} | {i['note']} |")
(HERE/'节奏排查/全片问题清单.md').write_text('\n'.join(lines)+'\n',encoding='utf8')
print('PREVIEW_DATA',len(issues),'candidates',offset,'seconds')
