/* Mutate existing scene data so its renderer keeps all expressions and reactions. */
(()=>{
 const original=window.TIMELINE.map(c=>structuredClone(c));
 const markBase=structuredClone(window.MARKS||{}),stampBase=structuredClone(window.STAMPS||{});
 const beatBase=(window.BEATSTARTS||[]).map(b=>({...b}));
 const nearby=t=>{let best=0;original.forEach((c,i)=>{if(Math.abs(c.start-t)<Math.abs(original[best].start-t))best=i;});return best;};
 const shiftAt=(t,cards)=>cards[nearby(t)].start-original[nearby(t)].start;
 const camera=typeof Z0!=='undefined'?[Z0,Z1]:null;
 window.applyEditorCards=cards=>{
  if(cards.length!==original.length)throw Error('编辑数据与原幕不匹配');
  const merged=original.map((c,i)=>({...c,...cards[i]}));
  window.TIMELINE.splice(0,window.TIMELINE.length,...merged.sort((a,b)=>a.start-b.start));
  for(const [key,t]of Object.entries(markBase))window.MARKS[key]=t+shiftAt(t,cards);
  for(const [key,t]of Object.entries(stampBase))window.STAMPS[key]=t+shiftAt(t,cards);
  if(window.BEATSTARTS)window.BEATSTARTS.splice(0,window.BEATSTARTS.length,...beatBase.map(b=>({...b,t:b.t+shiftAt(b.t,cards)})).sort((a,b)=>a.t-b.t));
  if(camera){Z0=camera[0]+shiftAt(camera[0]+CAM,cards);Z1=camera[1]+shiftAt(camera[1]+CAM,cards);}
  if(window.editorRefreshInternals)window.editorRefreshInternals();
  else if(typeof boundaries!=='undefined')boundaries.splice(0,boundaries.length,...[...new Set(window.TIMELINE.flatMap(c=>[c.start,c.end,c.end+HOLD]))].sort((a,b)=>a-b));
 };
})();

// RIN_FIXED_REVEAL_V1: fixed geometry, full-text layout, visibility-only reveal.
(()=>{
 const css=document.createElement('style');css.textContent=`
 #sub.rin-fixed-sub{left:660px!important;right:auto!important;top:748px!important;width:600px!important;height:300px!important;justify-content:flex-start!important;transform:none!important}
 #line.rin-fixed-line{box-sizing:border-box!important;width:600px!important;min-width:600px!important;max-width:600px!important;height:170px!important;min-height:170px!important;padding:16px 26px!important;flex-shrink:0!important;text-align:left!important;text-wrap:wrap!important;white-space:pre-wrap!important;overflow-wrap:anywhere!important;font-size:var(--rin-size,30px)!important;line-height:1.5!important;color:var(--ink,#3a3631)!important;overflow:hidden}
 .rin-glyph-text{display:block;text-align:left;white-space:pre-wrap;overflow-wrap:anywhere}
 `;document.head.append(css);
 const renderBefore=window.render;let cachedText=null,layer=null,glyphs=[],shown=-1;
 window.render=function(time){
  const cue=window.TIMELINE.find(c=>time>=c.start&&time<c.end),rin=cue?.who==='rin';
  const el=document.getElementById('line'),sub=document.getElementById('sub');
  el?.classList.toggle('rin-fixed-line',rin);sub?.classList.toggle('rin-fixed-sub',rin);
  renderBefore(time);
  if(!rin||!el)return;
  if(cachedText!==cue.text){
   cachedText=cue.text;shown=-1;layer=document.createElement('span');layer.className='rin-glyph-text';
   glyphs=Array.from(cue.text).map(char=>{const span=document.createElement('span');span.textContent=char;span.style.visibility='hidden';layer.append(span);return span;});
   el.style.setProperty('--rin-size','30px');el.replaceChildren(layer);
   // A long user-edited card may use a smaller font, but never resize the box.
   let size=30;while(size>16&&layer.getBoundingClientRect().height>138.5){size--;el.style.setProperty('--rin-size',size+'px');}
  }else if(el.firstChild!==layer||el.childNodes.length!==1){el.replaceChildren(layer);}
  const step=cue.charStep||((cue.revealEnd-cue.start)/Math.max(1,glyphs.length))||.11462585034;
  const count=Math.min(glyphs.length,Math.max(1,Math.floor((time-cue.start)/step)+1));
  if(count!==shown){glyphs.forEach((span,i)=>{span.style.visibility=i<count?'visible':'hidden';});shown=count;}
 };
})();

// ACT2_SPEECH_PPT_V1: cue individual layers inside the spoken sentence.
if(typeof window.beatAt==='function'){
 const beforeSpeechPpt=window.beatAt;
 window.beatAt=function(time){
  const base=beforeSpeechPpt(time);if(!base)return base;
  const line=window.TIMELINE.find(c=>c.id==='2-7');
  if(line&&['W3a','W3b','W3c'].includes(base.id)){
   const start=line.lineStart,cues=[['W3a',start,1.1],['W3b',start+3.46,.55],['W3c',start+5.0,.55]];
   let cue=cues[0];for(const c of cues)if(time>=c[1])cue=c;
   return {index:window.ACT2.beats.indexOf(cue[0]),id:cue[0],progress:Math.max(0,Math.min(1,(time-cue[1])/cue[2])),since:time-cue[1]};
  }
  if(base.id==='T3'){
   const c=window.TIMELINE.find(c=>c.id==='2-15');if(c){const start=c.lineStart+2.54;return {...base,progress:Math.max(0,Math.min(1,(time-start)/14.5)),since:time-start};}
  }
  return base;
 };
}
