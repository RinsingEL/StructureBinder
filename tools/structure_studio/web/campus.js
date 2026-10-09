import './campus.css';
import {Structure,BlockState,StructureRenderer} from 'deepslate';
import {mat4,vec4} from 'gl-matrix';
import {loadResources} from './resources.js';
import {createTerrainContext} from './terrain-context.js';

const $=id=>document.getElementById(id),canvas=$('viewport');
const telemetry={ready:false,renderErrors:[],missingTextures:[]};
const algorithmNames={GRID:'网格',LINEAR:'线性',COURTYARD:'庭院',COMPACT:'紧凑',ORGANIC_COMPACT:'自然紧凑',CENTER_SYMMETRIC:'中心对称',CONTIGUOUS:'连续地块'};
let resources,renderer,data,scene,context,terrainMesh,examples=[],selected='teaching',target=[0,0,0],distance=340,yaw=.85,pitch=.61,roof=false,terrain=true,labels=true;
const gl=canvas.getContext('webgl',{antialias:true,preserveDrawingBuffer:true});
if(!gl)throw new Error('浏览器未能启动 WebGL。');
class Renderer extends StructureRenderer {
 getPerspective(){const m=mat4.create();mat4.perspective(m,45*Math.PI/180,canvas.clientWidth/canvas.clientHeight,.1,6000);return m;}
 projection(){return this.projMatrix;}
}
async function json(url,options){const r=await fetch(url,options),p=await r.json();if(!r.ok||p.error)throw new Error(p.error||`${r.status}`);return p;}
function error(e){telemetry.renderErrors.push(String(e));$('status').textContent=String(e);$('status').classList.add('error');$('loading').hidden=true;$('build').disabled=false;}
function choice(select,rows,value){select.replaceChildren(...rows.map(([v,t])=>new Option(t,v)));if(value)select.value=value;}
function layoutNodes(){return [...scene.compositions,...scene.arrays];}
function currentArray(){return layoutNodes().find(a=>(a.arrayId||a.compositionId)===$('array').value);}
function hierarchyChoices(){
 const nodes=layoutNodes(),byId=new Map(nodes.map(n=>[n.arrayId||n.compositionId,n]));
 const children=new Set(scene.compositions.flatMap(n=>n.memberIds)),rows=[];
 function visit(n,depth){const id=n.arrayId||n.compositionId;rows.push([id,`${'　'.repeat(depth)}${n.layout?.purpose||n.function||id} · ${algorithmNames[n.algorithm]}${n.count?` / ${n.count} 栋`:` / ${n.memberIds.length} 组`}`]);for(const id of n.memberIds||[])visit(byId.get(id),depth+1);}
 for(const n of nodes)if(!children.has(n.arrayId||n.compositionId))visit(n,0);return rows;
}
function loadControls(){
 choice($('array'),hierarchyChoices());
 choice($('algorithm'),Object.entries(algorithmNames));
 choice($('asset'),data.catalog.filter(a=>a.consumer==='array').map(a=>[a.assetId,`${a.id} · ${a.name.split(' · ')[0]}`]));
 choice($('node'),hierarchyChoices());
 $('greenery').checked=scene.greenery!==false;$('gap-landscape').checked=!!scene.gapLandscape;$('gap-landscape').disabled=!scene.publicSpaces?.length;$('small-gap-max').value=scene.smallGapMaxBlocks||32;$('small-gap-max').disabled=!scene.gapLandscape;$('terrain-source').value=scene.terrainSource||'D3_INTERPOLATED';arrayControls();nodeControls();
 const styles=scene.terraceStyles||[];choice($('terrace-style'),[['','各台地原配置'],...styles.map(s=>[s.styleId,s.name||s.styleId])]);
 $('terrace-style').disabled=!styles.length;$('terrace-features').disabled=!styles.length;$('terrace-features').checked=!!styles.length&&scene.terraceFeatures!==false;
 $('fixture-note').textContent=styles.length?`已放置 ${data.metadata.summary.fixtures||0} 个构件，入口与道路保留通行空间。`:'当前方案未配置台地设施风格。';
 choice($('example-scene'),examples.map(e=>[e.id,e.name]));
}
function arrayControls(){const a=currentArray(),composition=!!a.compositionId;$('algorithm').value=a.algorithm;$('density').value=a.densityClass||'BALANCED';$('count').value=a.count||a.memberIds.length;$('rotation').value=a.rotation||0;$('asset').value=a.coreAssetId||a.requiredAssetIds?.[0]||a.fill?.[0]?.assetId||'';
 for(const id of ['count','rotation','asset'])$(id).disabled=composition||(id==='rotation'&&a.layout?.faceInward);
 $('density').disabled=!!a.layout;
 $('array-note').textContent=composition?`整体组合：${a.memberIds.join('、')}。子组内部布局随整体一起移动。`:a.layout?.faceInward?'建筑入口朝向院内；中心保留花园与通路。':a.coreAssetId?'核心与配套按实际占地排列。':'配套建筑按实际占地排列。';
 if(a.layout)$('array-note').textContent+=` 间距 ${a.layout.gapBlocks} 格。`;
}
function nodeControls(){const n=[...scene.compositions,...scene.arrays].find(a=>(a.compositionId||a.arrayId)===$('node').value);$('anchor-x').value=n.anchorBlock.x;$('anchor-z').value=n.anchorBlock.z;}
function updateGeometry(){
 const world=data.world,palette=world.palette.map(p=>new BlockState(p.name,p.properties));
 const members=new Map(data.metadata.members.map(m=>[m.memberId,m]));const assets=new Map(data.catalog.map(a=>[a.assetId,a]));
 const visible=world.blocks.filter(b=>{
  if(!terrain&&b.layer==='terrain')return false;
  if(roof&&b.layer.startsWith('building:')){const m=members.get(b.layer.slice(9)),a=assets.get(m.assetId);if(a.roofMinY!=null&&b.pos[1]+world.origin[1]>=m.placementBaseY+a.roofMinY)return false;}
  return true;
 });
 const structure=new Structure(world.size,palette,visible.map(b=>({pos:b.pos,state:b.state})));
 if(!renderer)renderer=new Renderer(gl,structure,resources,{useInvisibleBlockBuffer:false});else renderer.setStructure(structure);
 resize();
}
function resize(){const w=Math.round(canvas.clientWidth*devicePixelRatio),h=Math.round(canvas.clientHeight*devicePixelRatio);canvas.width=w;canvas.height=h;renderer?.setViewport(0,0,w,h);draw();}
function draw(){if(!renderer)return;const view=mat4.create(),eye=[target[0]+Math.sin(yaw)*Math.cos(pitch)*distance,target[1]+Math.sin(pitch)*distance,target[2]+Math.cos(yaw)*Math.cos(pitch)*distance];mat4.lookAt(view,eye,target,[0,1,0]);
 gl.clearColor(.72,.79,.75,1);gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);
 if(terrain&&terrainMesh){renderer.setShader(renderer.gridShaderProgram);renderer.prepareDraw(view);renderer.drawMesh(terrainMesh.mesh,{pos:true,color:true});}
 renderer.drawStructure(view);$('labels').replaceChildren();
 if(labels)for(const d of scene.districts){const m=data.metadata.members.find(m=>m.memberId===d.coreMemberId);if(!m)continue;const origin=data.world.origin,p=vec4.fromValues(m.originBlock.x+m.width/2-origin[0],m.surfaceY+m.height+5-origin[1],m.originBlock.z+m.depth/2-origin[2],1);vec4.transformMat4(p,p,view);vec4.transformMat4(p,p,renderer.projection());
  if(p[3]<=0||Math.abs(p[0]/p[3])>1||Math.abs(p[1]/p[3])>1)continue;const el=document.createElement('button');el.className='pin';el.textContent=d.name;const small=document.createElement('small');small.textContent=d.platform?`台地 ${m.surfaceY}`:'自然地形';el.append(small);el.style.left=`${(p[0]/p[3]+1)*.5*canvas.clientWidth}px`;el.style.top=`${(1-p[1]/p[3])*.5*canvas.clientHeight}px`;el.onclick=()=>focus(d.districtId);$('labels').append(el);
 }
}
function viewControl(id){for(const key of ['overview','landscape','top','close'])$(key).classList.toggle('selected',key===id);telemetry.view=id;}
function overview(){target=[data.world.size[0]/2,4,data.world.size[2]/2];distance=Math.max(data.world.size[0],data.world.size[2])*1.3;pitch=.66;yaw=.65;viewControl('overview');draw();}
function landscape(){const b=context.bounds,o=data.world.origin;target=[(b.minX+b.maxX+1)/2-o[0],context.waterDisplayY-o[1]+4,(b.minZ+b.maxZ+1)/2-o[2]];distance=Math.max(b.maxX-b.minX+1,b.maxZ-b.minZ+1)*1.6;pitch=.93;yaw=.18;viewControl('landscape');draw();}
function focus(id,close=false){selected=id;const members=data.metadata.members.filter(m=>m.districtId===id),origin=data.world.origin;
 const minx=Math.min(...members.map(m=>m.originBlock.x)),maxx=Math.max(...members.map(m=>m.originBlock.x+m.width)),minz=Math.min(...members.map(m=>m.originBlock.z)),maxz=Math.max(...members.map(m=>m.originBlock.z+m.depth));
 target=[(minx+maxx)/2-origin[0],members[0].surfaceY-origin[1]+12,(minz+maxz)/2-origin[2]];distance=Math.max(maxx-minx,maxz-minz)*(close?1.2:1.65);pitch=.5;viewControl('close');
 document.querySelectorAll('.district').forEach(b=>b.classList.toggle('active',b.dataset.id===id));
 const district=scene.districts.find(d=>d.districtId===id),terraces=data.metadata.terraces.filter(t=>t.districtId===id);
 $('detail').textContent=`${district.name}：${members.length} 个结构与地块。${district.platform?`${terraces.length} 个核心台地，高程 ${terraces.map(t=>t.datumY).join(' / ')}；各组内部同高。`:'保持自然地形。'} 构件 ${data.metadata.fixtures?.filter(f=>terraces.some(t=>t.terraceId===f.terraceId)).length||0} 个。`;draw();
}
function focusNode(id){
 const members=data.metadata.members.filter(m=>m.arrayId===id||m.compositionPath?.includes(id));if(!members.length)return;
 const origin=data.world.origin,minx=Math.min(...members.map(m=>m.originBlock.x)),maxx=Math.max(...members.map(m=>m.originBlock.x+m.width)),minz=Math.min(...members.map(m=>m.originBlock.z)),maxz=Math.max(...members.map(m=>m.originBlock.z+m.depth));
 target=[(minx+maxx)/2-origin[0],members.reduce((v,m)=>v+m.surfaceY,0)/members.length-origin[1]+12,(minz+maxz)/2-origin[2]];distance=Math.max(maxx-minx,maxz-minz)*1.5;pitch=.75;
 $('detail').textContent=`${id}：${members.length} 栋建筑，${new Set(members.map(m=>m.arrayId)).size} 个子阵列。`;
 draw();
}
async function refresh(){telemetry.ready=false;$('loading').hidden=false;let terrainState;[data,context,terrainState,examples]=await Promise.all([json('/api/campus'),json('/api/campus/terrain/context'),json('/api/campus/terrain'),json('/api/campus/examples')]);scene=structuredClone(data.metadata.scene);const meta=data.metadata;$('revision').textContent=`R${String(meta.revision).padStart(2,'0')}`;
 if(!scene.districts.some(d=>d.districtId===selected))selected=scene.districts[0].districtId;
 $('facility-summary').textContent=`${meta.summary.fixtures||0} 个台地构件`;
 for(const [id,key] of [['buildings','buildings'],['terraces','terraces'],['roads','roadSegments'],['lamps','lamps']])$(id).textContent=meta.summary[key];
 $('districts').replaceChildren();for(const d of scene.districts){const b=document.createElement('button');b.className='district';b.dataset.id=d.districtId;const strong=document.createElement('strong');strong.textContent=d.name;const small=document.createElement('small');small.textContent=`${data.metadata.members.filter(m=>m.districtId===d.districtId).length} 个成员 · ${d.platform?'核心台地':'自然地形'}`;b.append(strong,small);b.onclick=()=>focus(d.districtId);$('districts').append(b);}
 const demOption=$('terrain-source').querySelector('[value="DEM_STEP_1"]');demOption.disabled=!terrainState.demAvailable;demOption.textContent=terrainState.demAvailable?'逐格 DEM · 1 格直接采样':'逐格 DEM · 尚未导入';$('terrain-detail').textContent=(meta.terrain?.detail||'D3 真实采样，逐格高度由插值得到。')+' '+context.detail;$('evidence-note').textContent=meta.terrain?.mode==='DEM_STEP_1'?'真实结构 / 逐格生成器 DEM':'真实结构 / D3 采样插值';
 terrainMesh?.dispose();terrainMesh=createTerrainContext(gl,context,data.world.origin);telemetry.terrainContext=terrainMesh.stats;
 loadControls();updateGeometry();overview();
 const requestedDistrict=new URLSearchParams(location.search).get('district');
 if(scene.districts.some(d=>d.districtId===requestedDistrict)){focus(requestedDistrict);yaw=Math.PI-.65;pitch=.65;draw();}
 $('loading').hidden=true;$('status').classList.remove('error');$('status').textContent=`R${meta.revision} 已生成 · ${meta.summary.owners} 个区块完成模拟`;
 telemetry.ready=true;telemetry.revision=meta.revision;telemetry.blockCount=meta.summary.blockCount;
}
$('array').onchange=()=>{arrayControls();focusNode($('array').value);};$('node').onchange=nodeControls;
for(const id of ['algorithm','density','count','rotation','asset'])$(id).onchange=()=>{const a=currentArray();a.algorithm=$('algorithm').value;a.densityClass=$('density').value;if(!a.compositionId){a.count=Number($('count').value);if(!a.layout?.faceInward)a.rotation=Number($('rotation').value);}
 if(id==='asset'){if(a.coreAssetId)a.coreAssetId=$('asset').value;else if(a.requiredAssetIds?.length)a.requiredAssetIds[0]=$('asset').value;else a.fill=[{assetId:$('asset').value,weight:1}];} $('status').textContent='参数已修改，点击“生成并查看”更新场景。';};
for(const id of ['anchor-x','anchor-z'])$(id).onchange=()=>{const n=[...scene.compositions,...scene.arrays].find(a=>(a.compositionId||a.arrayId)===$('node').value);n.anchorBlock={x:Number($('anchor-x').value),z:Number($('anchor-z').value)};$('status').textContent='组团位置已修改，点击生成。';};
async function generate(nextScene){
 $('build').disabled=true;$('apply-plan').disabled=true;$('load-example').disabled=true;$('status').classList.remove('error');$('status').textContent='正在生成道路、台地与场景…';
 try{await json('/api/campus/build',{method:'POST',headers:{'Content-Type':'application/json','X-Campus-Write':'build'},body:JSON.stringify({scene:nextScene,baseRevision:data.metadata.revision})});await refresh();}
 finally{$('build').disabled=false;$('apply-plan').disabled=false;$('load-example').disabled=false;}
}
$('build').onclick=async()=>{try{scene.greenery=$('greenery').checked;scene.gapLandscape=$('gap-landscape').checked;scene.smallGapMaxBlocks=Number($('small-gap-max').value);scene.terraceFeatures=$('terrace-features').checked;scene.terrainSource=$('terrain-source').value;await generate(scene);}catch(e){$('status').textContent=String(e);$('status').classList.add('error');}};
$('gap-landscape').onchange=()=>{$('small-gap-max').disabled=!$('gap-landscape').checked;};
$('terrace-style').onchange=()=>{const ref=$('terrace-style').value;if(!ref)return;
 if(!scene.terraceGroups)scene.terraceGroups=scene.districts.filter(d=>d.platform).map(d=>({terraceId:`${d.districtId}-platform`,districtId:d.districtId,coreMemberId:d.coreMemberId,arrayIds:[...d.arrayIds]}));
 for(const group of scene.terraceGroups)group.styleRef=ref;$('status').textContent='设施风格已修改，点击生成查看。';};
$('load-example').onclick=async()=>{try{await generate(structuredClone(examples.find(e=>e.id===$('example-scene').value).scene));}catch(e){$('status').textContent=String(e);$('status').classList.add('error');}};
$('import-dem').onchange=async e=>{try{const file=e.target.files[0];if(!file)return;if(file.size>24000000)throw new Error('DEM 文件超过 24 MB。');const dem=JSON.parse(await file.text());await json('/api/campus/terrain/import',{method:'POST',headers:{'Content-Type':'application/json','X-Campus-Write':'build'},body:JSON.stringify(dem)});const option=$('terrain-source').querySelector('[value="DEM_STEP_1"]');option.disabled=false;option.textContent='逐格 DEM · 1 格直接采样';$('terrain-source').value='DEM_STEP_1';$('status').textContent='DEM 已导入，点击生成查看逐格地形。';}catch(e){$('status').textContent=String(e);$('status').classList.add('error');}finally{e.target.value='';}};
$('edit-plan').onclick=()=>{$('plan-json').value=JSON.stringify(scene,null,2);$('plan-error').textContent='';$('plan-dialog').showModal();};
$('default-plan').onclick=async()=>{try{$('plan-json').value=JSON.stringify(await json('/api/campus/default'),null,2);$('plan-error').textContent='';}catch(e){$('plan-error').textContent=String(e);}};
$('import-plan').onchange=async e=>{try{const file=e.target.files[0];if(!file)return;if(file.size>200000)throw new Error('方案文件超过 200 KB。');const imported=JSON.parse(await file.text());$('plan-json').value=JSON.stringify(imported,null,2);$('plan-error').textContent='';}catch(e){$('plan-error').textContent=String(e);}finally{e.target.value='';}};
$('apply-plan').onclick=async()=>{try{const nextScene=JSON.parse($('plan-json').value);await generate(nextScene);$('plan-dialog').close();}catch(e){$('plan-error').textContent=String(e);$('status').textContent=String(e);$('status').classList.add('error');}};
$('overview').onclick=overview;$('landscape').onclick=landscape;$('top').onclick=()=>{overview();pitch=1.55;yaw=0;viewControl('top');draw();};$('close').onclick=()=>focus(selected,true);
for(const id of ['roof','terrain','labels-toggle'])$(id).onclick=()=>{if(id==='roof'){roof=!roof;$(id).classList.toggle('selected',roof);updateGeometry();}else if(id==='terrain'){terrain=!terrain;telemetry.terrainVisible=terrain;$(id).classList.toggle('selected',terrain);updateGeometry();}else{labels=!labels;$(id).classList.toggle('selected',labels);draw();}};
function download(url,name){const a=document.createElement('a');a.href=url;a.download=name;a.click();}
$('export').onclick=()=>{const url=URL.createObjectURL(new Blob([JSON.stringify(scene,null,2)],{type:'application/json'}));download(url,`建筑方案_R${data.metadata.revision}.json`);setTimeout(()=>URL.revokeObjectURL(url),1000);};
$('capture').onclick=()=>{draw();canvas.toBlob(b=>{const url=URL.createObjectURL(b);download(url,`建筑预览_R${data.metadata.revision}.png`);setTimeout(()=>URL.revokeObjectURL(url),1000);});};
let pointer;canvas.onpointerdown=e=>{canvas.setPointerCapture(e.pointerId);pointer={x:e.clientX,y:e.clientY,button:e.button};};canvas.onpointerup=()=>pointer=null;canvas.onpointermove=e=>{if(!pointer)return;const dx=e.clientX-pointer.x,dy=e.clientY-pointer.y;pointer.x=e.clientX;pointer.y=e.clientY;
 if(pointer.button===2){const s=distance*.0017;target[0]-=Math.cos(yaw)*dx*s;target[2]+=Math.sin(yaw)*dx*s;target[1]+=dy*s;}else{yaw-=dx*.007;pitch=Math.max(.04,Math.min(1.56,pitch+dy*.007));}draw();};canvas.oncontextmenu=e=>e.preventDefault();canvas.addEventListener('wheel',e=>{e.preventDefault();distance=Math.max(12,Math.min(2800,distance*Math.exp(e.deltaY*.001)));draw();},{passive:false});
new ResizeObserver(resize).observe(canvas);
window.campus={telemetry,focus,focusNode,overview,landscape,draw,get data(){return data},get scene(){return scene},get context(){return context},setView(v){if(v.target?.length===3&&v.target.every(Number.isFinite))target=[...v.target];if(v.pitch!=null)pitch=v.pitch;if(v.yaw!=null)yaw=v.yaw;if(v.distance!=null)distance=v.distance;draw();},refresh};
window.addEventListener('error',e=>error(e.message));
try{resources=await loadResources(telemetry);await refresh();}catch(e){error(e);}
