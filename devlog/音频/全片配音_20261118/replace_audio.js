function hasVoiceCuts(v,s,duration){const cs=(s.clips||[]).filter(c=>c.key===v.key);return !!s.audioEdits?.[v.key]?.length || (s.clips!==undefined&&(cs.length!==1||Math.abs(cs[0].in)>.001||Math.abs(cs[0].out-duration)>.003));}
function prepareAudioChoices(){const s=state();s.audioChoice??={};for(const v of act().voices){if(s.importedAudio?.[v.key]?.length){v.alternatives??=[{id:'original',label:'原版',url:v.url,duration:v.duration,gain_db:v.gain_db}];for(const x of s.importedAudio[v.key])if(!v.alternatives.some(y=>y.id===x.id))v.alternatives.push(x);}if(!v.alternatives)continue;const original=v.alternatives.find(x=>x.id==='original');let id=s.audioChoice[v.key];if(!id){id=hasVoiceCuts(v,s,original.duration)?'original':v.preferredAudio||'original';s.audioChoice[v.key]=id;if(id!=='original'&&s.clips){const c=s.clips.find(c=>c.key===v.key);if(c)c.out=v.alternatives.find(x=>x.id===id).duration;}}
 const chosen=v.alternatives.find(x=>x.id===id)||original;v.url=chosen.url;v.duration=chosen.duration;v.gain_db=chosen.gain_db;v.activeAudio=chosen.id;}save();}
function replacementPanel(){let box=$('replaceBox');if(!box){box=document.createElement('div');box.id='replaceBox';box.innerHTML='<hr><h3>替换录音</h3><select id="replaceChoice" aria-label="录音版本"></select><button id="replaceApply">使用这个版本</button><p id="replaceHint" class="hint"></p>';$('properties').insertBefore(box,$('fields'));} $('replaceApply').onclick=applyReplacement;ensureFilePicker(box);const v=selectedVoice();box.hidden=!v;if(!v)return;$('replaceScope').options[0].disabled=!chosenPiece();$('replaceScope').value=chosenPiece()?'piece':'whole';const versions=v.alternatives||[{id:'original',label:'原版'}];$('replaceChoice').replaceChildren();for(const x of versions){const o=document.createElement('option');o.value=x.id;o.textContent=x.label;$('replaceChoice').append(o);}$('replaceChoice').value=v.activeAudio||'original';$('replaceApply').disabled=versions.length<2;$('replaceApply').textContent='切换整句版本';$('replaceHint').textContent=versions.length<2?'先选择替换范围，再从电脑选择文件。选中片段之外的录音保持原样。':'本地文件按上方范围替换；下方候选版本切换仍针对整句。字幕可在 S2 调整。';}
async function applyReplacement(options={}){const v=selectedVoice(),id=$('replaceChoice').value,alt=v?.alternatives?.find(x=>x.id===id);if(!alt||id===v.activeAudio)return;const s=state(),cut=hasVoiceCuts(v,s,v.duration);if(cut&&!options.confirmed&&!confirm('这句已有分割、裁剪或删除。替换整句将恢复为一块新录音，仅清除这句话的音频剪辑；字幕和其他配音保留。继续吗？'))return;stop();pushUndo();s.audioChoice??={};s.audioChoice[v.key]=id;if(s.audioEdits)delete s.audioEdits[v.key];if(s.clips){const prior=s.clips.filter(c=>c.key===v.key);const at=prior.length?Math.min(...prior.map(c=>c.at)):v.start+(s.voice[v.key]||0);s.clips=s.clips.filter(c=>c.key!==v.key);s.clips.push({id:freshId(),key:v.key,at:Math.max(0,Math.min(act().duration-alt.duration,at)),in:0,out:alt.duration,gain:s.gain[v.key]||0});}save();const t=current();await loadAct(idx);seek(t);}
const sourceUndo=$('undo').onclick;$('undo').onclick=()=>{const before=JSON.stringify(state().audioChoice||{}),i=idx,t=current();sourceUndo();if(before!==JSON.stringify(state().audioChoice||{}))loadAct(i).then(()=>seek(t));};
const sourceImport=$('file').onchange;$('file').onchange=async()=>{await sourceImport();await loadAct(idx);};

function ensureFilePicker(box){if($('pickVoiceFile'))return;const scope=document.createElement('select');scope.id='replaceScope';scope.innerHTML='<option value="piece">只替换选中片段</option><option value="whole">替换整句录音</option>';scope.style.cssText='width:100%;margin-bottom:8px';box.insertBefore(scope,$('replaceChoice'));const button=document.createElement('button');button.id='pickVoiceFile';button.textContent='选择本地音频文件并替换…';button.style.cssText='width:100%;margin-bottom:10px;background:#426d58';const input=document.createElement('input');input.id='voiceFile';input.type='file';input.accept='audio/*,.wav,.mp3,.m4a,.ogg,.flac';input.hidden=true;box.insertBefore(button,$('replaceChoice'));box.append(input);button.onclick=()=>input.click();input.onchange=()=>importVoiceFile(input.files[0]).finally(()=>input.value='');}
function encodeReplacement(b){const ch=Math.min(2,b.numberOfChannels),n=b.length,out=new ArrayBuffer(44+n*ch*2),d=new DataView(out),str=(at,s)=>{for(let i=0;i<s.length;i++)d.setUint8(at+i,s.charCodeAt(i));};str(0,'RIFF');d.setUint32(4,out.byteLength-8,true);str(8,'WAVE');str(12,'fmt ');d.setUint32(16,16,true);d.setUint16(20,1,true);d.setUint16(22,ch,true);d.setUint32(24,b.sampleRate,true);d.setUint32(28,b.sampleRate*ch*2,true);d.setUint16(32,ch*2,true);d.setUint16(34,16,true);str(36,'data');d.setUint32(40,n*ch*2,true);for(let c=0;c<ch;c++){const x=b.getChannelData(c);for(let i=0;i<n;i++)d.setInt16(44+(i*ch+c)*2,Math.round(Math.max(-1,Math.min(1,x[i]))*32767),true);}return out;}
function extraClipSources(){const used=new Set((state().clips||[]).map(c=>c.sourceId).filter(Boolean)),found=new Map();for(const list of Object.values(state().importedAudio||{}))for(const a of list)if(used.has(a.id))found.set(a.id,['source:'+a.id,a.url]);return [...found.values()];}
async function importVoiceFile(file){
 if(!file)return;const v=selectedVoice();if(!v)return;const piece=$('replaceScope').value==='piece'?chosenPiece():null;
 if($('replaceScope').value==='piece'&&!piece){status('请先在A1选中一个音频块');return;}
 const index=idx,key=v.key,st=state();
 if(!piece&&hasVoiceCuts(v,st,v.duration)&&!confirm('替换整句会清除这句话已有的音频分割/裁剪。其他录音与字幕保留。继续吗？'))return;
 const button=$('pickVoiceFile');button.disabled=true;button.textContent='正在导入…';stop();
 try{
  if(file.size>64*1024*1024)throw Error('请选择小于64MB的录音');
  const b=await ctx.decodeAudioData(await file.arrayBuffer()),start=piece?piece.at:v.start+(st.voice[key]||0);
  if(b.duration>act().duration-start)throw Error('新录音超过本幕剩余时长，请先裁短');
  const pcm=encodeReplacement(b);if(pcm.byteLength>64*1024*1024)throw Error('解码后音频过大，请先裁短');
  const r=await fetch('/api/import-audio',{method:'POST',headers:{'Content-Type':'application/octet-stream'},body:pcm});if(!r.ok)throw Error('导入失败：HTTP '+r.status);
  const imported=await r.json();imported.label=file.name;
  if(idx!==index||selectedVoice()?.key!==key||(piece&&chosenPiece()?.id!==piece.id))throw Error('选择已改变，请重新选中目标后导入');
  const register=()=>{st.importedAudio??={};st.importedAudio[key]??=[];if(!st.importedAudio[key].some(x=>x.id===imported.id))st.importedAudio[key].push(imported);};
  if(piece){
   buffers.set('source:'+imported.id,b);
   commit(()=>{register();const clips=ensureClips(),i=clips.findIndex(c=>c.id===piece.id);if(i<0)throw Error('目标片段已不存在');clips[i]={...clips[i],sourceId:imported.id,in:0,out:imported.duration};});
   status('已仅替换选中片段。新时长 '+imported.duration.toFixed(2)+' 秒；其他片段和字幕未移动，请复听接缝。');
  }else{
   register();v.alternatives??=[{id:'original',label:'原版',url:v.url,duration:v.duration,gain_db:v.gain_db}];if(!v.alternatives.some(x=>x.id===imported.id))v.alternatives.push(imported);
   replacementPanel();$('replaceChoice').value=imported.id;await applyReplacement({confirmed:true});status('已替换整句录音，字幕可在S2调整。');
  }
 }catch(e){status(e.message);}finally{button.disabled=false;button.textContent='选择本地音频文件并替换…';}
}
