// Chinese presentation layer. Original identifiers remain in the embedded evidence.
const round2=data.round2;
const materialImages=new Map();let latestProgress=1;
for(const [ref,m] of Object.entries(round2.materials))if(m.imageData){const img=new Image();img.onload=()=>{if(!frame)draw(current,latestProgress)};img.src=m.imageData;materialImages.set(ref,img)}
function materialName(ref){return round2.materials[ref]?.name||'选定配套素材'}
function layoutName(id){return ({'algorithm:courtyard':'围合院子','algorithm:linear':'一字排开','algorithm:grid':'成行成列','algorithm:compact':'紧凑成组','algorithm:organic_compact':'自然成组','algorithm:contiguous':'相连成片','algorithm:center_symmetric':'围绕中心对称排列'})[id]||'按组团组织'}
function seedTiming(p){
 const q=p<.35?p/.35:p<.5?1+(p-.35)/.15:2+(p-.5)/.5*5;
 return {index:Math.min(6,Math.floor(q)),phase:p>=1?1:q-Math.floor(q)};
}
function seedReveal(i,s,p){if(beat()!=='T2')return clamp(s.seeds*7-i);const t=seedTiming(p);return i<t.index?1:i===t.index?clamp((t.phase-.6)/.3):0}
function reasonMap(s,p){
 const t=seedTiming(p),r=data.realms[t.index];if(t.phase<.25||r.fixed)return;
 const [x,y]=xy(r.x,r.z,s);
 // Emphasize nearby measured coarse cells, not invented exact candidate patches.
 const shore=/岸|湾/.test(round2.reasons[t.index].quote),gx=Math.floor(r.x/128),gz=Math.floor(r.z/128);
 ctx.save();ctx.fillStyle='#C9A63E';ctx.globalAlpha=.25*clamp((t.phase-.25)/.25);
 for(let dz=-3;dz<=3;dz++)for(let dx=-3;dx<=3;dx++){
  const xx=gx+dx,zz=gz+dz,at=(zz+152)*304+xx+152;
  if(at<0||at>=92416)continue;const water=bytes[at*4+2]/255;
  if(shore?water>0:water<.5){const [px,py]=xy(xx*128,zz*128,s);ctx.fillRect(px,py,128*580/s.range+1,128*580/s.range+1)}
 }
 ctx.restore();text('选点周边地形',x+12,y-20,12,.8);
}
function drawMaterialCard(ref,x,y,w=360,h=140,alpha=1){
 const m=round2.materials[ref],img=materialImages.get(ref);if(!m)return;
 ctx.save();ctx.globalAlpha=alpha;ctx.fillStyle=PAPER;ctx.fillRect(x,y,w,h);ctx.strokeStyle='#2b2a2633';ctx.strokeRect(x,y,w,h);
 const imageWidth=w*.43;
 if(img?.complete&&img.naturalWidth){const k=Math.min((imageWidth-12)/img.naturalWidth,(h-12)/img.naturalHeight);ctx.drawImage(img,x+6+(imageWidth-12-img.naturalWidth*k)/2,y+(h-img.naturalHeight*k)/2,img.naturalWidth*k,img.naturalHeight*k)}
 text(m.name,x+imageWidth,y+35,13,alpha);
 text(`${m.beds?m.beds+' 床 · ':''}${m.width} × ${m.depth} 格`,x+imageWidth,y+59,12,alpha);
 text(m.terms.slice(0,2).join(' · '),x+imageWidth,y+82,11,alpha*.75);ctx.restore();
}
function selectionCards(p){
 text('南宿院需要容纳 56 人',450,112,20);
 text('从住宿素材中挑选',450,145,16,clamp(p/.18));
 const refs=['studio:mg-27-v02','studio:mg-37-v03','studio:mg-05-v02','studio:mg-17-v02'];
 refs.forEach((ref,i)=>{const enter=ease(clamp((p-.18)*3-i*.14)),keep=i===0?1:1-clamp((p-.76)/.2);drawMaterialCard(ref,1120+(1-enter)*300,92+i*132,420,120,enter*keep)});
}
function stylePiles(p){
 const selected=round2.styles.findIndex(x=>x.term==='魔法学院新制');
 round2.styles.forEach((style,i)=>{
  const x=500+(i%4)*245,y=150+Math.floor(i/4)*150,keep=i===selected;
  const alpha=keep?clamp(p*4):clamp(p*4)*(1-clamp((p-.5)/.4));
  ctx.save();ctx.globalAlpha=alpha;ctx.fillStyle='#E6E1CC';ctx.fillRect(x+8,y+8,210,110);ctx.fillStyle=PAPER;ctx.fillRect(x,y,210,110);ctx.strokeStyle='#2b2a2633';ctx.strokeRect(x,y,210,110);ctx.restore();
  text(style.term,x+16,y+34,18,alpha);text(`${style.count} 种`,x+16,y+62,14,alpha);
  if(keep){const img=materialImages.get('studio:mg-27-v02');if(img?.complete&&img.naturalWidth){ctx.save();ctx.globalAlpha=alpha;ctx.drawImage(img,x+110,y+8,90,90);ctx.restore()}}
 });
}
function usagePiles(p){
 round2.piles.forEach((pile,i)=>{
  const x=500+(i%3)*340,y=145+Math.floor(i/3)*235,q=ease(clamp(p*3-i*.2));
  ctx.save();ctx.globalAlpha=q;ctx.fillStyle='#E6E1CC';ctx.fillRect(x,y,310,195);ctx.restore();
  text(`${pile.label} · ${pile.count} 种`,x+15,y+27,18,q);
  pile.refs.filter(ref=>materialImages.has(ref)).slice(0,3).forEach((ref,j)=>{const img=materialImages.get(ref);if(img?.complete&&img.naturalWidth){ctx.save();ctx.globalAlpha=q;ctx.drawImage(img,x+10+j*88,y+55+(j%2)*12,110,110);ctx.restore()}});
 });
 text('按作者用途归堆 · 一栋素材可以有多种用途',500,620,13,.8);
}
function arrow(x,y,xx,yy,color='#B07A4F',width=1.5){line(x,y,xx,yy,color,width);const a=Math.atan2(yy-y,xx-x);line(xx,yy,xx-8*Math.cos(a-.5),yy-8*Math.sin(a-.5),color,width);line(xx,yy,xx-8*Math.cos(a+.5),yy-8*Math.sin(a+.5),color,width)}
function layoutDemo(s,p){
 const g=requestGroup('345'),a=g.anchorBlock,[x,y]=xy(a.x,a.z,s),k=580/s.range;
 if(p>.04){line(x-8,y,x+8,y);line(x,y-8,x,y+8)}
 const court=snap(61).courts.find(c=>c.ownerGroupId==='student_south');
 if(p>.2){box(court,s,'#E6E1CC',.4);text('院子 28 × 24 格',x-55,y+5,13,clamp((p-.2)/.1));}
 const slots=planning(s,0,.65,clamp((p-.6)/.2));
 if(p>.25)slots.forEach((b,i)=>{
  const q=ease(clamp((p-.25)/.3*4-i)),[tx,ty]=xy((b.minX+b.maxX)/2,(b.minZ+b.maxZ)/2,s);
  const xx=mix(x-80+i*45,tx,q),yy=mix(y-115,ty,q),w=(b.maxX-b.minX+1)*k,h=(b.maxZ-b.minZ+1)*k;
  ctx.save();ctx.globalAlpha=q;ctx.setLineDash([5,5]);ctx.strokeStyle=INK;ctx.strokeRect(xx-w/2,yy-h/2,w,h);ctx.restore();
  if(q>.8){const dx=x-tx,dy=y-ty,len=Math.hypot(dx,dy);arrow(tx,ty,tx+dx/len*20,ty+dy/len*20)}
  if(p>.8){const img=materialImages.get('studio:mg-27-v02'),t=ease(clamp((p-.8)/.2));if(img?.complete&&img.naturalWidth){ctx.save();ctx.globalAlpha=t;ctx.drawImage(img,mix(1120,tx-35,t),mix(130,ty-35,t),70,70);ctx.restore()}}
 });
 if(p>.6){const yy=y+57*k;line(x,yy,x+6*k,yy);line(x,yy-4,x,yy+4);line(x+6*k,yy-4,x+6*k,yy+4);text('6 格',x,yy+18,14);}
}
function closestMarketGap(rev){
 const items=snap(rev).anchors,a=items.filter(south),b=items.filter(x=>x.placementGroupId.startsWith('market'));let best=null;
 for(const x of a)for(const y of b){const u=x.plannedFootprint,v=y.plannedFootprint,dx=Math.max(0,u.minX-v.maxX-1,v.minX-u.maxX-1),dz=Math.max(0,u.minZ-v.maxZ-1,v.minZ-u.maxZ-1),distance=Math.hypot(dx,dz);if(!best||distance<best.distance)best={u,v,distance};}
 return best;
}
function gapDimension(s,move){
 const g=closestMarketGap(69),x=Math.max(g.u.minX,g.v.minX)+12*move,z1=g.v.maxZ+1,z2=g.u.minZ+6*move;
 const [px,py]=xy(x,z1,s),[,yy]=xy(x,z2,s);line(px,py,px,yy,'#B07A4F',2);line(px-7,py,px+7,py,'#B07A4F',2);line(px-7,yy,px+7,yy,'#B07A4F',2);
 text(`净空 ${Math.round(g.distance+6*move)} 格`,px+14,(py+yy)/2+5,17);
}
function blockedRoutes(s,rev,p=1){
 const gaps=snap(rev).gaps.filter(g=>g.fromGroupId==='student_south');
 for(const gap of gaps){const [x,y]=xy(gap.toInterface.x,gap.toInterface.z,s),[xx,yy]=xy(gap.fromInterface.x,gap.fromInterface.z,s),t=.75*clamp(p);ctx.save();ctx.setLineDash([7,5]);arrow(x,y,mix(x,xx,t),mix(y,yy,t),'#A65E66',2);ctx.restore();const ex=mix(x,xx,t),ey=mix(y,yy,t);line(ex-5,ey-5,ex+5,ey+5,'#A65E66',2);line(ex+5,ey-5,ex-5,ey+5,'#A65E66',2)}
}
function accessStory(s,p){
 const moving=beat()==='D5b',move=moving?ease(clamp((p-.15)/.6)):0;
 for(const a of snap(69).anchors.filter(a=>a.placementGroupId.startsWith('market')||a.placementGroupId==='dining'))building(a,s,.6);
 for(const a of snap(69).anchors.filter(south)){const b={...a,plannedFootprint:{...a.plannedFootprint}};for(const key in b.plannedFootprint)b.plannedFootprint[key]+=(key.endsWith('X')?12:6)*move;building(b,s)}
 gapDimension(s,move);blockedRoutes(s,moving&&move>.9?70:69,clamp(p*2));
 if(moving&&p>.7){const c=snap(70).courts.find(c=>c.ownerGroupId==='student_south');box(c,s,null,clamp((p-.7)/.2),false,'#A65E66');text('围合院落 · 主路尚未接通',450,125,17)}
 const [mx,my]=xy(-3065,5397,s),[dx,dy]=xy(-2904,5489,s);text('市场',mx-30,my-18,17);text('食堂',dx+10,dy,17);
}
function miniLayout(phase){
 const q=ease(clamp(phase));
 const positions=[[140,8],[240,40],[140,72],[40,40]];
 return '<div class="mini-layout">'+positions.map(([x,y],i)=>`<i style="left:${mix(35+i*60,x,q)}px;top:${mix(0,y,q)}px"></i>`).join('')+'<span>院子</span></div>';
}
function round2Panels(s,p){
 latestProgress=p;const id=beat(),params=$('params'),explain=$('explain');params.classList.toggle('demo',id==='D3');
 explain.classList.toggle('style-note',id==='D1c'||id==='D1d');
 if(id==='T2'){
  const timing=seedTiming(p);
  $('roster').innerHTML='<header>AI 选择扩张起点</header>'+data.realms.map((r,i)=>`<div class="realm ${i===timing.index?'reason-active':''}" style="opacity:${i<=timing.index?1:.35}"><i style="background:${COLORS[i]}"></i><span>${esc(r.name)}</span><span>${esc(revision.realms[i].short)}</span>${i===timing.index?`<div class="reason">${esc(round2.reasons[i].quote)}</div>`:''}</div>`).join('');
  $('source').textContent='选点理由原文节选 · 周边地形粗扫';
 }
 if(id==='D1c'){
  explain.hidden=false;explain.innerHTML='<h2>镜潮的职责：<strong class="strong">学院</strong></h2><p>教学、藏书、研究与学生生活</p><p class="strong">采用魔法学院新制素材</p><p>北欧建议不作为锁定风格</p><small>系统有筛选接口。<br>当时 Astra 直接读取已有目录，按学院素材编号选出对应条目。</small>';
  $('source').textContent='历史目录风格统计 / 总览意图 · 真实数据';
 }
 if(id==='D1d'){
  explain.hidden=false;explain.innerHTML='<h2>学院素材 · 按用途</h2><p>45 种素材</p><p>先了解能做什么，再展开功能区。</p><p class="strong">住宿、教学、研究、餐饮……</p><small>按作者用途标签整理，同一素材可出现在多个用途堆中。</small>';
  $('source').textContent='总览前读取的作者标签 · 真实数据';
 }
 if(id==='D3')params.innerHTML=renderParams('南宿院 · 四栋围合',requestGroup('345'),p,'四栋小宿舍围合共享内庭')+miniLayout(clamp((p-.2)/.35));
 if(id==='D5a'){
  params.hidden=false;params.innerHTML='<h2>先看问题</h2><p class="purpose">更换核心与明确配套后，四栋已经展开。</p><div class="param">宿院与市场之间<b>净空 8 格</b></div><p class="purpose">通向市场与食堂的两条主路尚未接通。</p><small>虚线箭头表示希望接通的方向。</small>';
  $('source').textContent='真实占地净空 8 格；评估原文 9 格为边界坐标差';
 }
 if(id==='D5b'){
  const move=ease(clamp((p-.15)/.6));params.hidden=false;
  params.innerHTML='<h2>整体平移</h2><div class="param">向东 12 格 · 向南 6 格</div><div class="param">北侧净空<b>'+Math.round(8+6*move)+' 格</b></div><p class="purpose">北侧通道变宽，西侧宿舍退离水边。<br>但围合院落仍未接通主路。</p>';
  $('source').textContent='修订评估 / 实际占地与道路缺口 · 真实数据';
 }
 if(id==='D6'){
  const q=clamp((p-.2)/.6);params.innerHTML='<h2>打开生活巷</h2><div class="param">住房保持不变<b>四栋 · 56 人</b></div><div class="param" style="opacity:'+q+'">一栋自带庭院的宿舍<br>＋ 三栋小宿舍成排</div><p class="purpose" style="opacity:'+q+'">北侧接市场<br>东侧接食堂</p><small>开口与连路以真实编译结果为依据。</small>';
 }
}

// Photo pins identify functional-area footprint centroids, not camera positions.
const photoCards=[
 ['arrival','迎宾',-2833,5419,1160,456],
 ['housing','宿舍生活区',-3012,5490,100,456],
 ['starhall','星厅与庭院',-2988,5262,100,266],
 ['library','图书馆',-2913,5024,100,76],
 ['observatory','天象台',-2689,5027,1160,76],
 ['botanical','绿植教学区',-2547,5174,1160,266]
];
const photoImages=new Map();
for(const [id] of photoCards){const img=new Image();img.onload=()=>{if(!frame&&beat()==='D-photos')draw(current,latestProgress)};img.src=data.photos[id];photoImages.set(id,img)}
function drawPhotoCards(s,p){
 photoCards.forEach(([id,label,x,z,left,top],i)=>{
  const q=ease(clamp(p*6-i));if(q<=0)return;
  const [px,py]=xy(x,z,s),edge=left<800?left+340:left;
  ctx.save();ctx.globalAlpha=q;
  line(px,py,edge,top+88,INK,.8);dot(px,py,3,INK);
  // Briefly emphasize the region as its photograph arrives.
  if(q<1){ctx.beginPath();ctx.arc(px,py,10+12*q,0,Math.PI*2);ctx.strokeStyle=INK;ctx.stroke();}
  ctx.fillStyle='#fffdf7';ctx.fillRect(left,top,340,178);
  const img=photoImages.get(id);
  if(img?.complete&&img.naturalWidth){const ratio=324/140,actual=img.naturalWidth/img.naturalHeight;let sw=img.naturalWidth,sh=img.naturalHeight;if(actual>ratio)sw=sh*ratio;else sh=sw/ratio;ctx.drawImage(img,(img.naturalWidth-sw)/2,(img.naturalHeight-sh)/2,sw,sh,left+8,top+8,324,140)}
  text(label,left+12,top+168,17,q);ctx.restore();
 });
}
