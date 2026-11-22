const freshId=()=>crypto.randomUUID();let waveLeft=0,waveRight=0,waveAt=0,waveScale=1;
function voiceClips(){return state().clips??act().voices.map(v=>({id:'original:'+v.file,key:v.key,at:v.start+(state().voice[v.key]||0),in:0,out:voiceDuration(v),gain:state().gain[v.key]||0}));}
function ensureClips(){return state().clips??=voiceClips().map(c=>({...c}));}
function chosenPiece(){return selection?.segmentId?voiceClips().find(c=>c.id===selection.segmentId):null;}
function pieceVoice(c){return act().voices.find(v=>v.key===c.key);}
function clipBuffer(c){return c.sourceId?buffers.get('source:'+c.sourceId):editedVoice(pieceVoice(c));}
function voiceClipEvents(){return voiceClips().map(c=>{const v=pieceVoice(c);return {id:c.id,at:c.at,buffer:clipBuffer(c),sourceOffset:c.in,length:c.out-c.in,gain:db((c.sourceId?0:v.gain_db)+act().masterGain+c.gain),track:'voice'};});}
function validateVoiceClips(s,a){for(const [key,list] of Object.entries(s.importedAudio||{})){if(!a.voices.some(v=>v.key===key)||!Array.isArray(list))throw Error('导入录音来源无效');for(const x of list)if(!/^import-[0-9a-f]{64}$/.test(x.id)||x.url!=='imports/'+x.id.slice(7)+'.wav'||!Number.isFinite(x.duration)||x.duration<=0||x.duration>1200||x.gain_db!==0||typeof x.label!=='string')throw Error('导入录音参数无效');}if(s.clips===undefined)return;if(!Array.isArray(s.clips)||s.clips.length>4000)throw Error('音频片段列表无效');const ids=new Set();for(const c of s.clips){const v=a.voices.find(v=>v.key===c.key);if(!v||typeof c.id!=='string'||c.id.length>100||ids.has(c.id))throw Error('音频片段来源或ID无效');ids.add(c.id);const external=c.sourceId?(s.importedAudio?.[c.key]||[]).find(x=>x.id===c.sourceId):null;if(c.sourceId&&!external)throw Error('替换片段音源缺失');if(external&&c.out>external.duration+.002)throw Error('替换片段超出音源');if(![c.at,c.in,c.out,c.gain].every(Number.isFinite)||c.in<0||c.out-c.in<.0099||c.at<0||c.at+c.out-c.in>a.duration+.001||c.out>Math.max(v.duration,...(v.alternatives||[]).map(x=>x.duration),...(s.importedAudio?.[v.key]||[]).map(x=>x.duration))+.002||c.gain< -96||c.gain>12)throw Error('音频片段范围无效');}}
function selectPiece(c){selection={kind:'voice',key:c.key,segmentId:c.id};properties();document.querySelectorAll('.clip').forEach(el=>el.classList.toggle('selected',el.dataset.segment===c.id));}
function replacePiece(c,pieces){commit(()=>{const all=ensureClips(),i=all.findIndex(x=>x.id===c.id);if(i<0)throw Error('片段已不存在');all.splice(i,1,...pieces);selection=pieces.length?{kind:'voice',key:pieces[0].key,segmentId:pieces[0].id}:null;});refreshDialog();}
function splitPiece(at=current()){const c=chosenPiece();if(!c){status('先选中一个配音片段');return;}const pieces=ClipOps.split(c,at-c.at,freshId());if(!pieces){status('播放头需要位于片段内部，离边缘至少 0.01 秒');return;}replacePiece(c,pieces);status('已切成两个独立片段，可以分别拖动或删除');}
function deletePiece(){const c=chosenPiece();if(!c)return;replacePiece(c,[]);status('已删除选中音频块，空位保留；字幕不受影响。');}
function drawVoiceClips(lane){for(const c of voiceClips()){
 const v=pieceVoice(c),el=document.createElement('div');el.className='clip voice piece';el.dataset.kind='voice';el.dataset.key=c.key;el.dataset.segment=c.id;el.classList.toggle('selected',selection?.segmentId===c.id);el.style.left=(120+c.at*pps)+'px';el.style.width=Math.max(4,(c.out-c.in)*pps)+'px';
 const text=document.createElement('span');text.className='piece-name';text.textContent=names[v.who]+' · '+(c.out-c.in).toFixed(2)+'s';el.title='双击放大编辑；拖动块移动；拖左右边缘裁剪';
 const canvas=document.createElement('canvas');canvas.width=Math.min(1600,Math.max(20,Math.round((c.out-c.in)*pps)));canvas.height=28;el.append(canvas,text);paintMini(canvas,c);
 for(const side of ['left','right']){const handle=document.createElement('i');handle.className='trim-handle '+side;handle.dataset.side=side;el.append(handle);}
 el.onclick=e=>{e.stopPropagation();selectPiece(c);};el.ondblclick=e=>{e.preventDefault();selectPiece(c);openPiece();};
 let suppressClick=false;
 const priorClick=el.onclick,priorDoubleClick=el.ondblclick;
 el.onclick=e=>{if(suppressClick){suppressClick=false;e.preventDefault();return;}priorClick(e);};
 el.ondblclick=e=>{if(!suppressClick)priorDoubleClick(e);};
 el.onpointerdown=e=>{
  if(e.button!==0||!ready)return;e.preventDefault();stop();selectPiece(c);el.setPointerCapture(e.pointerId);
  const x=e.clientX,side=e.target.closest?.('.trim-handle')?.dataset.side;let changed={...c},moved=false;
  const update=ev=>{if(!moved&&Math.abs(ev.clientX-x)<4)return;moved=true;const delta=(ev.clientX-x)/pps;
   changed=side?ClipOps.trim(c,side,delta,clipBuffer(c).duration,act().duration):ClipOps.move(c,c.at+delta,act().duration);
   el.style.left=(120+changed.at*pps)+'px';el.style.width=Math.max(4,(changed.out-changed.in)*pps)+'px';};
  const cleanup=()=>{el.onpointermove=el.onpointerup=el.onpointercancel=el.onlostpointercapture=null;if(el.hasPointerCapture(e.pointerId))el.releasePointerCapture(e.pointerId);};
  el.onpointermove=ev=>{if(ev.pointerId===e.pointerId)update(ev);};
  el.onpointerup=ev=>{if(ev.pointerId!==e.pointerId)return;update(ev);cleanup();if(moved){suppressClick=true;replacePiece(c,[changed]);}};
  const cancel=()=>{cleanup();el.style.left=(120+c.at*pps)+'px';el.style.width=Math.max(4,(c.out-c.in)*pps)+'px';};
  el.onpointercancel=cancel;el.onlostpointercapture=cancel;
 };lane.append(el);
 }}
function paintMini(canvas,c){const b=clipBuffer(c);if(!b)return;paintSamples(canvas,b,c.in,c.out,false);}
function paintSamples(canvas,b,lo,hi,stereo){const ctx2=canvas.getContext('2d'),w=canvas.width,h=canvas.height,ch=stereo?Math.min(2,b.numberOfChannels):1;ctx2.clearRect(0,0,w,h);const first=Math.floor(lo*b.sampleRate),last=Math.min(b.length,Math.floor(hi*b.sampleRate));for(let channel=0;channel<ch;channel++){const samples=b.getChannelData(channel),height=h/ch,mid=channel*height+height/2;ctx2.strokeStyle=stereo?'#83d3bd':'#b6eadb';ctx2.beginPath();for(let x=0;x<w;x++){const a=first+Math.floor(x*(last-first)/w),z=Math.min(last,first+Math.max(1,Math.floor((x+1)*(last-first)/w)));let mn=0,mx=0;const step=Math.max(1,Math.floor((z-a)/70));for(let k=a;k<z;k+=step){mn=Math.min(mn,samples[k]);mx=Math.max(mx,samples[k]);}ctx2.moveTo(x,mid-mx*height*.85);ctx2.lineTo(x,mid-mn*height*.85);}ctx2.stroke();if(stereo){ctx2.fillStyle='#9da4b1';ctx2.font='14px system-ui';ctx2.fillText(ch===1?'Mono':channel?'R 右声道':'L 左声道',12,channel*height+22);ctx2.strokeStyle='#38414e';ctx2.beginPath();ctx2.moveTo(0,mid);ctx2.lineTo(w,mid);ctx2.stroke();}}}
function segmentProperties(){const c=chosenPiece();let button=$('expandPiece');if(!button){button=document.createElement('button');button.id='expandPiece';button.textContent='双击片段 / 打开放大编辑';button.onclick=openPiece;$('fields').append(button);}button.hidden=!c;$('splitClip').disabled=$('deleteClip').disabled=!c;if(!c)return;$('title').textContent=names[pieceVoice(c).who]+' · 独立音频片段';$('start').value=c.at.toFixed(3);$('duration').textContent=`片段 ${(c.out-c.in).toFixed(3)}s · 源范围 ${c.in.toFixed(3)}–${c.out.toFixed(3)}s`;$('gain').value=c.gain;$('gain').min=-96;$('linkedLabel').hidden=true;$('audioEdit').hidden=true;}
const oldMoveSelected=moveSelected;moveSelected=value=>{const c=chosenPiece();if(!c)return oldMoveSelected(value);if(Number.isFinite(value))replacePiece(c,[ClipOps.move(c,value,act().duration)]);};
const originalWarnings=warnings;warnings=()=>{originalWarnings();const clips=voiceClips().slice().sort((a,b)=>a.at-b.at);const overlap=clips.some((c,i)=>clips.slice(0,i).some(x=>x.at+x.out-x.in>c.at+.001));$('warning').textContent=$('warning').textContent.replace('当前有配音片段重叠，请复听。 ','');if(overlap)$('warning').textContent='当前有音频片段重叠，请复听。 '+$('warning').textContent;};
function openPiece(){const c=chosenPiece();if(!c||!ready)return;stop();waveLeft=waveRight=waveAt=0;waveScale=1;$('waveZoom').value=1;$('clipDialog').showModal();refreshDialog();}
function refreshDialog(){if(!$('clipDialog').open)return;const c=chosenPiece();if(!c){$('clipDialog').close();return;}const length=c.out-c.in;waveLeft=Math.max(0,Math.min(waveLeft,length));waveRight=Math.max(waveLeft,Math.min(waveRight,length));waveAt=Math.max(0,Math.min(waveAt,length));$('clipTitle').textContent=names[pieceVoice(c).who]+' · '+length.toFixed(3)+' 秒';$('pieceStart').value=c.at.toFixed(3);$('pieceCursor').value=waveAt.toFixed(3);$('pieceLeft').value=waveLeft.toFixed(3);$('pieceRight').value=waveRight.toFixed(3);$('pieceNotice').textContent='片段独立移动和删除；字幕不自动跟随。需要时在 S2 轨道拖动字幕。';
 const w=Math.max(500,$('waveScroll').clientWidth)*waveScale;$('waveInner').style.width=w+'px';const canvas=$('largeWave');canvas.width=Math.round(w);canvas.height=320;paintSamples(canvas,clipBuffer(c),c.in,c.out,true);updateHandles();}
function updateHandles(){const c=chosenPiece();if(!c)return;const scale=$('largeWave').width/(c.out-c.in);$('rangeLeft').style.left=waveLeft*scale+'px';$('rangeRight').style.left=waveRight*scale+'px';$('rangeShade').style.left=waveLeft*scale+'px';$('rangeShade').style.width=(waveRight-waveLeft)*scale+'px';$('waveCursor').style.left=waveAt*scale+'px';$('pieceLeft').value=waveLeft.toFixed(3);$('pieceRight').value=waveRight.toFixed(3);$('pieceCursor').value=waveAt.toFixed(3);$('wavePosition').textContent=fmt(waveAt);}
function validRange(){const c=chosenPiece();return c&&waveRight-waveLeft>=.01&&waveRight<=c.out-c.in+.0001;}
function editRange(kind){const c=chosenPiece();if(!validRange()){$('pieceNotice').textContent='先拖出一个至少 0.01 秒的选区。';return;}if(kind==='cut'||kind==='ripple'){const pieces=ClipOps.remove(c,waveLeft,waveRight,kind==='ripple',freshId());replacePiece(c,pieces);}
 else{const pieces=[];if(waveLeft>=.01)pieces.push({...c,out:c.in+waveLeft});pieces.push({...c,id:pieces.length?freshId():c.id,at:c.at+waveLeft,in:c.in+waveLeft,out:c.in+waveRight,gain:kind==='mute'?-96:Math.max(-96,c.gain-15)});if(c.out-c.in-waveRight>=.01)pieces.push({...c,id:freshId(),at:c.at+waveRight,in:c.in+waveRight});replacePiece(c,pieces);}waveLeft=waveRight=waveAt=0;refreshDialog();}
$('waveInner').onpointerdown=e=>{
 if(e.button!==0)return;const c=chosenPiece();if(!c)return;e.preventDefault();e.stopPropagation();stop();
 const el=$('waveInner'),rect=el.getBoundingClientRect(),duration=c.out-c.in;
 const toT=x=>Math.max(0,Math.min(duration,(x-rect.left)/rect.width*duration));
 const x0=e.clientX,start=toT(x0),previous={left:waveLeft,right:waveRight,cursor:waveAt,position};
 const mode=e.target.closest?.('#rangeLeft')?'left':e.target.closest?.('#rangeRight')?'right':'new';
 let dragging=false;el.setPointerCapture(e.pointerId);
 const apply=x=>{
  if(!dragging&&Math.abs(x-x0)<4)return;
  dragging=true;const t=toT(x);
  if(mode==='left')waveLeft=Math.min(t,previous.right);
  else if(mode==='right')waveRight=Math.max(t,previous.left);
  else{waveLeft=Math.min(start,t);waveRight=Math.max(start,t);}
  updateHandles();
 };
 const cleanup=()=>{el.onpointermove=el.onpointerup=el.onpointercancel=el.onlostpointercapture=null;if(el.hasPointerCapture(e.pointerId))el.releasePointerCapture(e.pointerId);};
 el.onpointermove=ev=>{if(ev.pointerId===e.pointerId)apply(ev.clientX);};
 el.onpointerup=ev=>{
  if(ev.pointerId!==e.pointerId)return;apply(ev.clientX);
  if(!dragging&&mode==='new'){waveAt=toT(ev.clientX);position=c.at+waveAt;draw();}
  // A click never clears a selected range. Only an intentional drag creates one.
  cleanup();updateHandles();
 };
 const cancel=()=>{waveLeft=previous.left;waveRight=previous.right;waveAt=previous.cursor;position=previous.position;cleanup();updateHandles();};
 el.onpointercancel=cancel;el.onlostpointercapture=cancel;
};
$('pieceLeft').onchange=()=>{waveLeft=Math.max(0,Math.min(waveRight,+$('pieceLeft').value||0));updateHandles();};$('pieceRight').onchange=()=>{waveRight=Math.max(waveLeft,Math.min(chosenPiece().out-chosenPiece().in,+$('pieceRight').value||0));updateHandles();};$('pieceCursor').onchange=()=>{waveAt=Math.max(0,Math.min(chosenPiece().out-chosenPiece().in,+$('pieceCursor').value||0));seek(chosenPiece().at+waveAt);updateHandles();};$('pieceStart').onchange=()=>moveSelected(+$('pieceStart').value);$('waveZoom').oninput=()=>{waveScale=+$('waveZoom').value;refreshDialog();};
$('splitClip').onclick=()=>splitPiece();$('deleteClip').onclick=deletePiece;$('pieceSplit').onclick=()=>splitPiece(chosenPiece().at+waveAt);$('pieceDelete').onclick=deletePiece;$('pieceCut').onclick=()=>editRange('cut');$('pieceRipple').onclick=()=>editRange('ripple');$('pieceMute').onclick=()=>editRange('mute');$('pieceSoft').onclick=()=>editRange('soft');$('clipUndo').onclick=()=>{$('undo').click();refreshDialog();};
$('clipPlay').onclick=()=>{if(playing)stop();else{seek(chosenPiece().at+waveAt);play();}};$('pieceAudition').onclick=async()=>{if(!validRange())return;stop();position=chosenPiece().at+waveLeft;await play();regionStop=setTimeout(stop,(waveRight-waveLeft)*1000);};$('closeClip').onclick=()=>$('clipDialog').close();$('clipDialog').addEventListener('close',()=>stop());
document.addEventListener('keydown',e=>{if(e.isComposing||e.repeat||e.target.closest('input,textarea,select,[contenteditable="true"]'))return;if((e.ctrlKey||e.metaKey)&&e.code==='KeyZ'){e.preventDefault();$('undo').click();refreshDialog();return;}if(e.ctrlKey||e.metaKey||e.altKey)return;if(e.code==='KeyS'){e.preventDefault();splitPiece($('clipDialog').open&&chosenPiece()?chosenPiece().at+waveAt:current());}if(e.code==='Delete'||e.code==='Backspace'){e.preventDefault();deletePiece();}});
setInterval(()=>{if($('clipDialog').open&&playing){const c=chosenPiece();if(c){waveAt=Math.max(0,Math.min(c.out-c.in,current()-c.at));updateHandles();if(current()>=c.at+c.out-c.in)stop();}}},40);
