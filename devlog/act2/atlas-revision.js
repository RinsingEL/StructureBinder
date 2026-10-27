// Public request fields drive the explanation; animation timing is editorial.
const revision=data.revision;
const originalBeats=['W1','W2','W3a','W3b','W3c','T1','T2','T3','T3-end','T4','D1','D2','D3','D4','D5','D6','D7','D8-preview','D8-photo'];
function legacyScene(id){return id.startsWith('D1')?10:originalBeats.indexOf(id)}
const beat=()=>beats[index][0];
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const districtName=d=>d.role.replace(/【.*?】/g,'');
const districtOrder=[...revision.districts.filter(d=>d.groupId==='student_south'),...revision.districts.filter(d=>d.groupId!=='student_south')];
function progressDistrict(p){return p<.3?clamp(p/.3):1+clamp((p-.3)/.7)*15}
function requestGroup(line){return revision.requests[line].districtDesign.groups[0]}
function paramRows(g){
 const a=g.anchorBlock;
 return [['锚点','anchorBlock',a?`(${a.x}, ${a.z})`:'由父组合定位'],['排法','algorithmProfileRef',g.algorithmProfileRef],['数量','structureCount',g.structureCount],['间距','layout.gapBlocks',g.layout?.gapBlocks],['素材','requiredStructureRefs',(g.requiredStructureRefs||[]).join(' · ')]] .filter(r=>r[2]!==undefined);
}
function renderParams(title,g,p=1,subtitle=''){
 return `<h2>${esc(title)}</h2>${subtitle?`<p class="purpose">${esc(subtitle)}</p>`:''}`+paramRows(g).map(([n,k,v],i)=>`<div class="param" style="opacity:${clamp(p*5-i)}">${esc(n)}<small>${esc(k)}</small><b>${esc(v)}</b></div>`).join('');
}
function revisionPanels(s,p){
 const id=beat(),roster=$('roster'),explain=$('explain'),params=$('params'),stack=$('stack');
 roster.hidden=!['T1','T2','T3','T3-end','T4'].includes(id);
 if(!roster.hidden){
  roster.classList.toggle('compact',id!=='T1');roster.style.opacity=id==='T4'?1-p:1;
  roster.innerHTML='<header>季节供给、工艺传授与海路往来<br><small>七国名册</small></header>'+data.realms.map((r,i)=>{
   const a=id==='T1'?clamp(p*8-i):id==='T2'?(i<Math.ceil(s.seeds*7)?1:.35):1;
   return `<div class="realm" style="opacity:${a}"><i style="background:${COLORS[i]}"></i><span>${esc(r.name)}</span><span>${esc(revision.realms[i].short)}${r.fixed?'<small>固定领土</small>':''}</span></div>`;
  }).join('');
 }
 explain.hidden=!['D1b','D1c','D1d'].includes(id);explain.classList.toggle('declarations',id==='D1d');
 if(id==='D1b')explain.innerHTML='<h2>镜潮 · 城市职责</h2>'+['教学 · 藏书 · 议事','研究 · 维护 · 生活供应','学生生活 · 港口访客服务'].map((t,i)=>`<p style="opacity:${clamp(p*3-i)}">${t}</p>`).join('')+'<small>国度设定 · 职责摘要</small>';
 if(id==='D1c'){
  const tags=revision.overviewTags, n=Math.ceil(p*Math.min(tags.length,12));
  explain.innerHTML='<h2>魔法学院新制 · 用途标签</h2><div class="tag-grid">'+tags.slice(0,12).map(([tag,count],i)=>`<p style="opacity:${i<n?1:.15}">${esc(tag)} <small>× ${count}</small></p>`).join('')+'</div><small>常见用途节选 · 标签可重叠<br>总方案前已读取 '+Object.keys(revision.overviewMaterials).length+' 种学院素材标签</small>';
 }
 if(id==='D1d'){
  const {active,count:n}=declarationState(p),d=revision.districts[active];
  explain.innerHTML='<h2>职责 → 功能区</h2>'+revision.districts.map((d,i)=>`<div id="district-${i}" class="district-row" style="opacity:${i<n?1:0};color:${i===active?'#B07A4F':'inherit'};font-weight:${i===active?700:400}">${String(i+1).padStart(2,'0')} · ${esc(districtName(d))}<span class="direction">${esc(intentDirections[d.groupId])}</span></div>`).join('')+`<div class="intent">${esc(d.intent)}</div><small>总方案 · 方位来自 intent，尚未确定具体坐标</small>`;
 }
 params.hidden=!['D3','D4','D5','D6','D7'].includes(id);params.classList.toggle('fast',id==='D7');
 if(id==='D3')params.innerHTML=renderParams('南宿院 · 申请四栋',requestGroup('345'),p,'四栋小宿舍围合公共内庭');
 if(id==='D4'){
  const planned=requestGroup('345').structureCount,actual=snap(61).anchors.filter(south).length;
  params.innerHTML=`<h2>从设计要求到计算结果</h2><div class="param">LLM 计划<b>${planned} 栋</b><small>structureCount · 设计输入</small></div><p class="purpose" style="opacity:${clamp(p/.25)}">↓ Java 计算选材与可落位结果</p><div class="param" style="opacity:${clamp((p-.35)/.4)}">Java 计算结果<b>${actual} 个建筑落位</b><small>本次编译实际输出</small></div><p class="purpose" style="opacity:${clamp((p-.75)/.2)}">计划数量不等于实际落位数量。<br>根据结果继续检查与修订。</p><small>设计阶段的计算结果，尚未进行游戏内施工验收。</small>`;
 }
 if(id==='D5'){
  const moved=p>.3,g=requestGroup(moved?'501':'383');
  params.innerHTML=renderParams('南宿院 · 平移',g,1,moved?'东 12 · 南 6；连接仍需调整':'中间修订：调整停止条件仍为一栋；更换核心与明确配套后展开四栋。现在检查接路。');
 }
 if(id==='D6'){
  const design=revision.requests['508'].districtDesign, q=clamp((p-.2)/.6);
  params.innerHTML='<h2>南宿院 · 改为生活巷</h2>'+design.groups.map((g,i)=>`<div class="param" style="opacity:${clamp(q*2-i)}">${i?'三栋小宿舍':'一栋核心'}<small>${esc(g.groupId)}</small><b>${esc(g.algorithmProfileRef)}</b><small>structureCount · ${g.structureCount}</small><small>${esc(g.requiredStructureRefs.join(' · '))}</small></div>`).join('')+`<p class="purpose" style="opacity:${q}">核心＋配套 → 组合生活巷<br>连向市场与食堂</p><small>arrayCompositions · ${esc(design.arrayCompositions[0].algorithmProfileRef)}</small>`;
 }
 stack.hidden=id!=='D7';
 if(id==='D7'){
  const clock=clamp((p-.12)/.78)*15;
  const currentIndex=Math.min(15,Math.floor(clock)+1),entry=districtOrder[currentIndex],q=clamp(clock-(currentIndex-1));
  params.hidden=p<.12||p>=.9;
  if(!params.hidden){const design=revision.designs[entry.groupId].design,part=fastPart(design,q);params.innerHTML=renderParams(districtName(entry)+' · '+(part.index+1)+'/'+design.groups.length,part.group,part.fill,part.group.layout?.purpose||part.group.role||'')+`<small>${q<.2?'选材':q<.7?'填写组团参数':'按真实结果展开'} · 压缩回放</small>`;}
  stack.innerHTML=districtOrder.map((d,i)=>{
   const q=i===0?1:clamp(clock-(i-1)),active=i>0&&q>0&&q<1,design=revision.designs[d.groupId].design;
   const count=design.groups.reduce((n,g)=>n+(g.structureCount||0),0);
   const alg=[...new Set(design.groups.map(g=>g.algorithmProfileRef).filter(Boolean))].map(a=>a.replace('algorithm:','')).join(' / ');
   return `<div class="slip ${q>=1?'done':''} ${active?'active':''}" style="visibility:${i===0||q>0?'visible':'hidden'}"><b>${String(i+1).padStart(2,'0')} · ${esc(districtName(d))}</b>${active?`<small>${esc(alg)} · ${count} 个请求单元<br>${q<.25?'选素材':q<.55?'写参数 · 规划虚线':'展开真实占地'}</small>`:''}</div>`;
  }).join('');
 }
 if(id==='D1')$('source').textContent='D3 地形资料 · 真实数据';
 if(id==='D1b')$('source').textContent='realm_profiles.json · 职责摘要';
 if(id==='D1c')$('source').textContent='上下文作者标签 L72–74 · 真实数据';
 if(id==='D1d')$('source').textContent='总览 L80 · 方位原文；地图位置近似';
 if(id==='D4')$('source').textContent='请求 / 编译结果 · 真实数量与占地；虚线空位示意';
 if(id==='D3')$('source').textContent='请求 L345 · 空位近似';
 if(id==='D7')$('source').textContent=p>.9?'真实占地 · 范围归并近似':'请求摘要 / 真实占地 · 压缩回放';
}
// These quotations describe broad intent, never submitted district coordinates.
const intentDirections={starhall:'平原28北半部',seminars:'星厅西北',library:'北部平原接台地',observatory:'北部台地',laboratory:'教学区东北',council:'主轴东侧',arrival:'东南岸线陆侧',market:'迎客和常住区之间',dining:'中心庭院南侧',student_west:'中央平原西侧',student_south:'中央平原南段',faculty:'西北平原林缘',care:'两宿院与教学交界',scriptorium:'藏书区外缘接教学',botanical:'东北平原29',logistics:'东岸迎客后侧'};
const intentShortNames=['星厅','教学','藏书','观测','实验','议事','迎客','市集','餐饮','西宿院','南宿院','教师住区','医护卫生','抄写','植物教学','后勤'];
function intentMapTargets(s){
 const patch=name=>{const p=revision.patches.find(p=>p.mapLabel===name);return xy(p.centerBlock.x,p.centerBlock.z,s)};
 const c=patch('平原28'),n=patch('平原27'),e=patch('平原29'),k=580/s.range;
 const offset=(a,x,z)=>[a[0]+x*k,a[1]+z*k],between=(a,b)=>[(a[0]+b[0])/2,(a[1]+b[1])/2],h={};
 h.starhall=offset(c,0,-130);h.seminars=offset(h.starhall,-140,-90);
 h.library=offset(n,40,65);h.observatory=offset(n,-80,-120);
 h.laboratory=offset(h.seminars,210,-75);h.council=offset(h.starhall,140,45);
 h.arrival=offset(c,240,210);h.market=offset(c,130,115);h.dining=offset(c,0,65);
 h.student_west=offset(c,-180,30);h.student_south=offset(c,-50,220);
 h.faculty=offset(n,-165,0);h.care=between(h.student_west,h.seminars);
 h.scriptorium=between(h.library,h.seminars);h.botanical=e;h.logistics=offset(h.arrival,30,-110);
 return h;
}
function declarationState(p){const q=progressDistrict(p),active=Math.min(15,Math.floor(q));return {active,count:active+1,phase:p>=1?1:q-Math.floor(q)}}
function intentFlight(i,phase,target){
 // The first part of each beat is reserved for reading the highlighted phrase.
 const t=ease(clamp((phase-.3)/.55));let source=[654,173+i*24];
 const row=$('district-'+i),sheet=$('sheet');
 if(row?.getBoundingClientRect&&sheet.getBoundingClientRect){const r=row.getBoundingClientRect(),b=sheet.getBoundingClientRect(),scale=b.width/1600;if(scale>0&&r.height>0)source=[(r.right-b.left)/scale+4,(r.top+r.height/2-b.top)/scale]}
 return {t,x:mix(source[0],target[0],t),y:mix(source[1],target[1],t)-Math.sin(Math.PI*t)*28};
}
function introMap(s,p){
 const id=beat();
 for(const patch of revision.patches){
  if(!['plain','terrace','shore'].includes(patch.landformType)||patch.memberCells.length<30)continue;
  const [x,y]=xy(patch.centerBlock.x,patch.centerBlock.z,s);
  if(x>(id==='D1d'?690:420)&&x<1500&&y>100&&y<590)text(patch.mapLabel,x,y,12,.5);
 }
 if(id!=='D1d')return;
 const {active,count,phase}=declarationState(p),d=revision.districts[active],targets=intentMapTargets(s);
 const patch=revision.patches.find(p=>d.intent.includes(p.mapLabel));
 if(patch&&phase>.2){ctx.save();ctx.fillStyle='#C9A63E';ctx.globalAlpha=.23;for(const c of patch.memberCells){const [px,py]=xy(c.blockMinX,c.blockMinZ,s);ctx.fillRect(px,py,16*580/s.range,16*580/s.range)}ctx.restore()}
 revision.districts.slice(0,count).forEach((item,i)=>{
  const selected=i===active,target=targets[item.groupId],f=selected?intentFlight(i,phase,target):{t:1,x:target[0],y:target[1]};
  if(selected&&phase<.3)return;
  if(selected){ctx.save();ctx.globalAlpha=.13*f.t;dot(target[0],target[1],28,'#B07A4F');ctx.restore()}
  dot(f.x,f.y,selected?9:6,selected?'#B07A4F':'#5E8C9A');
  if(f.t>.9){const label=String(i+1).padStart(2,'0')+' '+intentShortNames[i];ctx.fillStyle=PAPER;ctx.fillRect(f.x+11,f.y-12,label.length*13+8,20);text(label,f.x+15,f.y+3,13,selected?1:.8);}
 });
 text(String(active+1).padStart(2,'0')+' · '+districtName(d),700,600,15);
 text('总方案：先说明用途和大致关系，具体坐标在后续组团设计时确定。',700,625,13,.85);
 $('sheet').dataset.activeDistrict=d.groupId;
 $('sheet').dataset.intentPresentation='approximate-map-flight';
}
function parameterMap(s,p){
 const g=requestGroup('345'),a=g.anchorBlock,[x,y]=xy(a.x,a.z,s);
 if(p>.04){line(x-8,y,x+8,y);line(x,y-8,x,y+8)}
 if(p>.2){ctx.save();ctx.setLineDash([4,5]);const k=580/s.range;ctx.strokeStyle='#2b2a2677';ctx.strokeRect(x-14*k,y-12*k,28*k,24*k);ctx.restore()}
 const slots=p>.4?planning(s,clamp((p-.4)/.2),.65,clamp((p-.6)/.2)):[];
 if(p>.6){const k=580/s.range;line(x,y+38*k,x+6*k,y+38*k);text('6',x,y+38*k+16,12,.8,mono)}
 if(p>.8){
  const card=academy.cards[0],t=ease(clamp((p-.8)/.2));
  slots.forEach(b=>{const [tx,ty]=xy(b.minX,b.minZ,s),xx=mix(1120,tx,t),yy=mix(175,ty,t),k=Math.min((b.maxX-b.minX+1)*580/s.range/card.width,(b.maxZ-b.minZ+1)*580/s.range/card.depth);
   ctx.save();ctx.globalAlpha=t*.65;for(const [cx,cz,w,band] of card.rows){ctx.fillStyle=['#D6CCAF','#C4B594','#A8987A','#6C685B'][band];ctx.fillRect(xx+cx*k,yy+cz*k,w*k,k+.1)}ctx.restore();
  });
 }

}
function fastPart(design,q){
 const t=clamp((q-.2)/.5)*design.groups.length,index=Math.min(design.groups.length-1,Math.floor(t));
 return {index,group:design.groups[index],fill:q>=.7?1:t-index};
}
function materialCard(ref,x,y,alpha=1){
 const card=academy.cards.find(c=>'studio:'+c.id.toLowerCase()===ref);
 const metadata=revision.materials[ref]?.authoredMetadata;
 ctx.save();ctx.globalAlpha=alpha;ctx.fillStyle=PAPER;ctx.fillRect(x,y,230,100);ctx.strokeStyle='#2b2a2655';ctx.strokeRect(x,y,230,100);
 if(card){const k=Math.min(65/card.width,65/card.depth);for(const [cx,cz,w,band] of card.rows){ctx.fillStyle=['#D6CCAF','#C4B594','#A8987A','#6C685B'][band];ctx.fillRect(x+10+cx*k,y+12+cz*k,w*k,k+.1)}}
 text(card?.name||ref.replace('studio:',''),x+(card?85:12),y+29,12,alpha);
 const terms=metadata?.functionTerms?.slice(0,2).join(' · ');
 text(terms||(card?`${card.beds} 床`:'请求指定素材'),x+(card?85:12),y+53,11,alpha);
 text(ref,x+12,y+84,10,alpha,mono);ctx.restore();
}
function compositionMap(s,p){
 const design=revision.requests['508'].districtDesign,composition=design.arrayCompositions[0];
 const center=snap(71).anchors.find(a=>a.placementGroupId===composition.centerGroupId);
 const members=snap(71).anchors.filter(a=>composition.memberGroupIds.includes(a.placementGroupId));
 if(!center||p<.25||p>.9)return;
 const [x,y]=xy(center.anchorBlock.x,center.anchorBlock.z,s),q=clamp((p-.25)/.55);
 ctx.save();ctx.setLineDash([5,5]);for(const [i,a] of members.entries()){const [xx,yy]=xy(a.anchorBlock.x,a.anchorBlock.z,s);line(x,y,mix(x,xx,clamp(q*3-i)),mix(y,yy,clamp(q*3-i)),'#B07A4F',1.6)}ctx.restore();
 text('一栋核心',x,y-15,13);text('三栋小宿舍 → 生活巷',460,115,16);
 text(`组合 ${composition.algorithmProfileRef} · 间距 ${composition.layout.gapBlocks}`,460,140,12,.8,mono);
}
function finalCity(s,p){
 const final=beat()!=='D7',clock=final?16:clamp((p-.12)/.78)*15;
 let shown=0;
 districtOrder.forEach((d,i)=>{
  const design=revision.designs[d.groupId].design,ids=new Set(design.groups.map(g=>g.groupId));
  const members=snap(74).anchors.filter(a=>ids.has(a.placementGroupId));
  const q=i===0?1:clamp(clock-(i-1));if(q<=0)return;
  const part=fastPart(design,q);
  for(const [j,a] of members.entries()){
   const groupIndex=design.groups.findIndex(g=>g.groupId===a.placementGroupId);
   if(q>=.7){const alpha=clamp((q-.7)/.3*members.length-j);if(alpha>0){building(a,s,alpha);shown++}else building(a,s,.25,true)}
   else if(q>.2&&(groupIndex<part.index||(groupIndex===part.index&&part.fill>.4)))building(a,s,.45,true);
  }
  if(q<1&&members.length){
   const g=part.group,ref=(g.requiredStructureRefs||[])[0];
   if(q<.2&&ref)materialCard(ref,460+ease(q/.2)*90,120,1);
   if(q>=.2&&q<.7){
    const root=g.anchorBlock||design.arrayCompositions?.find(c=>c.memberGroupIds?.includes(g.groupId)||c.centerGroupId===g.groupId)?.anchorBlock;
    if(root){const [x,y]=xy(root.x,root.z,s);line(x-6,y,x+6,y);line(x,y-6,x,y+6);}
    const first=members.find(a=>a.placementGroupId===g.groupId);
    if(first&&part.fill>.8&&ref){const [x,y]=xy(first.anchorBlock.x,first.anchorBlock.z,s),t=ease((part.fill-.8)/.2);materialCard(ref,mix(460,x,t),mix(120,y,t),1-t*.5)}
   }
  }
  if((final||p>.9)&&members.length){
   const bs=members.map(a=>a.plannedFootprint),bounds={minX:Math.min(...bs.map(b=>b.minX))-4,maxX:Math.max(...bs.map(b=>b.maxX))+4,minZ:Math.min(...bs.map(b=>b.minZ))-4,maxZ:Math.max(...bs.map(b=>b.maxZ))+4};
   box(bounds,s,null,final?.35:clamp((p-.9)/.1)*.35,true);
   const [x,y]=xy(bounds.minX,bounds.minZ,s);text(districtName(d),x,y-4,10,.75);
  }
 });
 roads(74,s,final?1:clamp((p-.85)/.15),false);
 $('stats').textContent=beat()==='D7'?`${shown} / 86`:'';
}
