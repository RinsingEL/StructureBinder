import {BlockDefinition, BlockModel, BlockState, NbtTag, Structure, StructureRenderer, TextureAtlas} from 'deepslate';
import {mat4, vec4} from 'gl-matrix';
import './style.css';
import {createSite} from './site.js';
import {createFilters} from './filters.js';
import {roleOf} from './functions.js';
import {createFrontageEditor} from './frontage.js';

const $ = id => document.getElementById(id);
const canvas = $('scene');
const gl = canvas.getContext('webgl', {antialias: true, preserveDrawingBuffer: true, alpha: false});
let renderer, resources, current, rows = [], visible, loadingGeneration = 0, library;
let yaw = .66, pitch = .5, distance = 40, target = [0, 0, 0], eye = [0, 0, 0], walk = false;
let markers = false, roof = false, floorMin = 0, clip = [1, 1, 1], selectedPreset = 'front';
let terrainRenderer,site,context=false;
const telemetry = {ready: false, renderErrors: [], missingTextures: [], source: 'serialized NBT'};
const view = mat4.create();
const projection = mat4.create();
const frontage = createFrontageEditor({
  focus(point) {
    reset(false); updateGeometry();
    markers = true; $('markers').classList.add('selected');
    walk = false;
    yaw = {north: Math.PI, south: 0, east: Math.PI / 2, west: -Math.PI / 2}[point.facing?.toLowerCase()] ?? Math.PI;
    pitch = .35;
    target = point.pos.map((v, i) => v + (i === 1 ? 1 : .5)); distance = 12;
    $('view-note').textContent = `入口 ${point.id} · ${point.facing ?? '朝向未标'} · ${point.pos.join(', ')}`;
    draw();
  },
  saved(source, result) {
    Object.assign(source, result);
    const row = rows.find(row => row.id === source.author.id);
    if (row) row.frontage = result.frontage;
    if (current === source) { telemetry.authorSha256 = result.author_sha256; renderChecks(source); }
    library?.refresh(); populateList();
  },
  reload(id) { load(id).catch(fail); },
});
function fail(err) { $('error').hidden = false; $('error').textContent = String(err); $('loading').hidden = true; telemetry.renderErrors.push(String(err)); }
const originalError = console.error;
console.error = (...args) => { telemetry.renderErrors.push(args.map(String).join(' ')); originalError(...args); };

class StudioRenderer extends StructureRenderer {
  getPerspective() {
    const matrix = mat4.create();
    mat4.perspective(matrix, (walk?75:48) * Math.PI / 180, canvas.clientWidth / canvas.clientHeight, .05, 2000);
    return matrix;
  }
  projection() { return this.projMatrix; }
}

async function fetchJSON(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${url}: ${res.status}`);
  return res.json();
}
async function loadResources() {
  const [data, registry] = await Promise.all([fetchJSON('/resources/resources.json'), fetchJSON('/resources/registry.json')]);
  const img = new Image(); img.src = '/resources/atlas.png'; await img.decode();
  const ctx = document.createElement('canvas').getContext('2d'); ctx.canvas.width = img.width; ctx.canvas.height = img.height; ctx.drawImage(img, 0, 0);
  const atlas = new TextureAtlas(ctx.getImageData(0, 0, img.width, img.height), data.textures);
  const definitions = Object.fromEntries(Object.entries(data.blocks).map(([k,v]) => [k,BlockDefinition.fromJson(v)]));
  const models = Object.fromEntries(Object.entries(data.models).map(([k,v]) => [k,BlockModel.fromJson(v)]));
  const modelProvider = {getBlockModel: id => models[id.toString()] ?? null};
  for (const model of Object.values(models)) model.flatten(modelProvider);
  function cube(name) {
    const definition = data.blocks[name];
    if (!definition?.variants) return false;
    return Object.values(definition.variants).flat().every(v => {
      const key = v.model.includes(':') ? v.model : `minecraft:${v.model}`;
      const es = models[key]?.elements;
      return es?.length === 1 && !es[0].rotation && es[0].from.join() === '0,0,0' && es[0].to.join() === '16,16,16' && Object.keys(es[0].faces ?? {}).length === 6;
    });
  }
  const flags = Object.fromEntries(Object.entries(registry).map(([id, v]) => [id, {opaque: !v.transparent && cube(id), semi_transparent: /(?:water|stained_glass|^minecraft:glass$|ice$|slime_block|honey_block)/.test(id), self_culling: /glass|ice$|water/.test(id)}]));
  return {...modelProvider,
    getBlockDefinition: id => definitions[id.toString()] ?? null,
    getBlockFlags: id => flags[id.toString()] ?? null,
    getBlockProperties: id => registry[id.toString()]?.properties ?? null,
    getDefaultBlockProperties: id => registry[id.toString()]?.default ?? null,
    getTextureAtlas: () => atlas.getTextureAtlas(),
    getTextureUV: id => { const key=id.toString(); if (!data.textures[key] && !telemetry.missingTextures.includes(key)) telemetry.missingTextures.push(key); return atlas.getTextureUV(id); },
    getPixelSize: () => atlas.getPixelSize(),
  };
}

function populateList() {
  const filtered = library?.results() ?? rows;
  $('count').textContent = `${filtered.length} / ${rows.length}`;
  $('selection-note').hidden = !current || filtered.some(row => row.id === current.author.id);
  $('assets').replaceChildren();
  for (const row of filtered) {
    const button = document.createElement('button');
    if (current?.author.id === row.id) button.classList.add('active');
    const small = document.createElement('small'); small.textContent = `${row.id} · ${row.size.join(' × ')}`;
    const roleTag = document.createElement('span'); roleTag.className = 'role-badge'; roleTag.textContent = roleOf(row).label; small.append(roleTag);
    if (row.frontage?.status !== 'ready') {
      const badge = document.createElement('span'); badge.className = 'role-badge warning'; badge.textContent = '入口待核对'; small.append(badge);
    }
    const label = document.createElement('span'); label.textContent = row.name;
    const tags = document.createElement('span'); tags.className = 'result-tags';
    tags.textContent = (row.function_terms ?? []).join(' · ') || '未标注功能';
    button.append(small, label, tags); button.onclick = () => load(row.id).catch(fail); $('assets').append(button);
  }
  if (!filtered.length) { const el=document.createElement('p'); el.className='empty'; el.textContent='没有匹配的结构。可移除一项用途、切换“任一具备”，或放宽其他筛选。'; $('assets').append(el); }
}

async function load(id) {
  if (current && !frontage.canLeave()) return;
  const generation = ++loadingGeneration;
  frontage.select(null);
  telemetry.ready = false; $('loading').hidden = false; $('error').hidden = true;
  const data = await fetchJSON(`/api/model?id=${encodeURIComponent(id)}`);
  if (generation !== loadingGeneration) return;
  current = data;
  const row = rows.find(row => row.id === data.author.id);
  if (row) row.frontage = data.frontage;
  library?.refresh();
  telemetry.renderErrors = []; telemetry.missingTextures = [];
  $('title').textContent = data.author.name;
  $('eyebrow').textContent = `${data.author.id} / ${data.author.civilization}`;
  const count = data.blocks.filter(b => !['minecraft:air','minecraft:cave_air','minecraft:void_air'].includes(data.palette[b.state].name)).length;
  $('subtitle').textContent = `${data.size.join(' × ')} 格 · ${count.toLocaleString()} 个方块 · 规划角色：${roleOf(data.author).label}`;
  context=false;site=null;$('context').classList.remove('selected');$('context').disabled=!data.author.preview_context;$('axis').textContent='X 东 · Y 上 · Z 南';
  $('floor').replaceChildren(new Option('全部楼层',''));
  for (const [i, floor] of (data.author.floors ?? []).entries()) $('floor').append(new Option(floor.name,String(i)));
  $('rooms').replaceChildren();
  for (const room of data.author.rooms) { const button=document.createElement('button');button.textContent=room.name;button.onclick=()=>focusRoom(room);$('rooms').append(button); }
  const notes = document.createElement('div'); notes.textContent = (data.author.design_notes ?? []).join(' '); $('rooms').append(notes);
  library?.select(data.author);
  frontage.select(data);
  renderChecks(data);
  reset(false); populateList(); updateGeometry(); preset('front');
  telemetry.ready = true; telemetry.id=id;telemetry.sha256=data.sha256;telemetry.authorSha256=data.author_sha256;telemetry.visibleBlocks=count;
  $('loading').hidden = true;
  requestAnimationFrame(draw);
}

function renderChecks(data) {
  $('checks').replaceChildren();
  for (const [text,cls] of [
    [data.validation?.passed && data.validation?.nbt_sha256 === data.sha256 ? `数据检查通过 · ${data.validation.warnings.length} 项待核对` : '数据检查待完成', data.validation?.passed && data.validation?.nbt_sha256 === data.sha256?'good':'warning'],
    [data.review?.nbt_sha256 === data.sha256 && data.review?.author_sha256 === data.author_sha256 && data.review?.status === 'accepted' ? '视觉验收已记录' : '视觉验收待完成',''],
    [`包含 ${data.author.rooms.length} 个空间、${data.author.points.length} 处标记。标记为作者记录。`, ''],
  ]) { const line=document.createElement('div');line.className=cls;line.textContent=text;$('checks').append(line); }
}

function updateGeometry() {
  if (!current) return;
  const palette = current.palette.map(p => new BlockState(p.name,p.properties));
  let maxY = clip[1];
  if (roof && current.author.roof_min_y != null) maxY = Math.min(maxY, current.author.roof_min_y-1);
  const blocks = current.blocks.filter(b => {
    const [x,y,z]=b.pos;
    return x <= clip[0] && y <= maxY && z <= clip[2] && y >= floorMin && !['minecraft:air','minecraft:cave_air','minecraft:void_air','minecraft:structure_void'].includes(current.palette[b.state].name);
  }).map(b => ({...b, nbt:b.nbt ? NbtTag.fromString(b.nbt) : undefined}));
  visible = new Structure(current.size,palette,blocks);
  if (!renderer) renderer = new StudioRenderer(gl,visible,resources,{useInvisibleBlockBuffer:false});
  else renderer.setStructure(visible);
  resize(); draw();
}
function reset(rebuild=true) {
  roof=false;floorMin=0;walk=false;$('roof').classList.remove('selected');$('walk').classList.remove('selected');$('floor').value='';
  clip=current.size.map(n=>n-1);
  for (const [i,a] of ['x','y','z'].entries()) { $(`clip-${a}`).max=clip[i];$(`clip-${a}`).value=clip[i];$(`value-${a}`).textContent=clip[i]; }
  if (rebuild) {updateGeometry();preset('front');}
  $('view-note').textContent='拖动旋转 · 滚轮缩放 · 右键平移';
}
function preset(name) {
  walk=false;$('walk').classList.remove('selected');selectedPreset=name;
  renderer?.setViewport(0,0,canvas.width,canvas.height);
  yaw={front:Math.PI-.66,back:-.66,left:Math.PI+.95,right:.95,top:Math.PI}[name]??Math.PI-.66;
  pitch=name==='top'?Math.PI/2-.001:.5;
  fitCamera();
  for(const button of document.querySelectorAll('[data-preset]'))button.classList.toggle('selected',button.dataset.preset===name);
  draw();
}
function fitCamera(bounds) {
  let min=bounds?.min??[Infinity,Infinity,Infinity],max=bounds?.max??[-Infinity,-Infinity,-Infinity];
  if(!bounds)for(const b of (visible?.getBlocks()??[])) for(let i=0;i<3;i++){min[i]=Math.min(min[i],b.pos[i]);max[i]=Math.max(max[i],b.pos[i]+1);}
  if(!Number.isFinite(min[0]))return;
  target=min.map((n,i)=>(n+max[i])/2);
  const forward=[Math.sin(yaw)*Math.cos(pitch),Math.sin(pitch),Math.cos(yaw)*Math.cos(pitch)];
  const right=[Math.cos(yaw),0,-Math.sin(yaw)],up=[-Math.sin(yaw)*Math.sin(pitch),Math.cos(pitch),-Math.cos(yaw)*Math.sin(pitch)];
  const tanY=Math.tan(24*Math.PI/180)*.82,tanX=tanY*canvas.clientWidth/canvas.clientHeight;
  distance=2;
  const dot=(a,b)=>a.reduce((s,v,i)=>s+v*b[i],0);
  for(const x of [min[0],max[0]])for(const y of [min[1],max[1]])for(const z of [min[2],max[2]]) {
    const p=[x-target[0],y-target[1],z-target[2]],depth=dot(p,forward);
    distance=Math.max(distance,depth+Math.abs(dot(p,right))/tanX,depth+Math.abs(dot(p,up))/tanY);
  }
}
function focusRoom(room) {
  const floor=(current.author.floors??[]).find(f=>f.y<=room.min[1] && f.max_y>=room.min[1]);
  floorMin=floor?.y??room.min[1]-1;clip[1]=Math.min(current.size[1]-1,room.max[1]-1);roof=false;walk=false;
  for(const a of ['x','y','z']) {const i=['x','y','z'].indexOf(a);$(`clip-${a}`).value=clip[i];$(`value-${a}`).textContent=clip[i];}
  pitch=1;yaw=.4;
  updateGeometry();fitCamera({min:room.min.map(v=>v-1),max:room.max.map(v=>v+2)});draw();
}
function resize() {
  const ratio=Math.min(devicePixelRatio,2),w=Math.round(canvas.clientWidth*ratio),h=Math.round(canvas.clientHeight*ratio);
  if(canvas.width!==w||canvas.height!==h) {canvas.width=w;canvas.height=h;}
  renderer?.setViewport(0,0,w,h);draw();
}
function draw() {
  if(!renderer||!current)return;
  if(walk) {
    const dir=[Math.sin(yaw)*Math.cos(pitch),Math.sin(pitch),Math.cos(yaw)*Math.cos(pitch)];
    mat4.lookAt(view,eye,eye.map((v,i)=>v+dir[i]),[0,1,0]);
  } else {
    eye=[target[0]+Math.sin(yaw)*Math.cos(pitch)*distance,target[1]+Math.sin(pitch)*distance,target[2]+Math.cos(yaw)*Math.cos(pitch)*distance];
    mat4.lookAt(view,eye,target,[0,1,0]);
  }
  gl.clearColor(.105,.15,.19,1);gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);
  renderer.drawStructure(view);
  if(context&&terrainRenderer&&site) {
    const siteView=mat4.clone(view);mat4.translate(siteView,siteView,site.origin);
    terrainRenderer.setViewport(0,0,canvas.width,canvas.height);terrainRenderer.drawStructure(siteView);
  }
  mat4.copy(projection,renderer.projection());
  $('labels').replaceChildren();
  if(markers) for(const point of current.author.points) {
    if(point.pos.some((v,i)=>v>clip[i])||point.pos[1]<floorMin)continue;
    const p=vec4.fromValues(point.pos[0]+.5,point.pos[1]+1,point.pos[2]+.5,1);vec4.transformMat4(p,p,view);vec4.transformMat4(p,p,projection);
    if(p[3]<=0||Math.abs(p[0]/p[3])>1||Math.abs(p[1]/p[3])>1)continue;
    const el=document.createElement('button');el.className='pin';el.textContent=point.kind === 'entrance' ? `${point.id} · ${point.name}` : point.name;el.title=`${point.kind} · ${point.pos.join(', ')}（透视标记）`;
    el.style.left=`${(p[0]/p[3]+1)*.5*canvas.clientWidth}px`;el.style.top=`${(1-p[1]/p[3])*.5*canvas.clientHeight}px`;
    el.onclick=()=>enterWalk(point);$('labels').append(el);
  }
}
function showContext(value) {
  if(value&&!current.author.preview_context)return;
  context=value;$('context').classList.toggle('selected',value);
  if(value&&!site) {
    site=createSite(current);
    if(!terrainRenderer)terrainRenderer=new StudioRenderer(gl,site.structure,resources,{useInvisibleBlockBuffer:false});
    else terrainRenderer.setStructure(site.structure);
  }
  $('axis').textContent=value?'地形为适用条件示意 · 不写入 NBT':'X 东 · Y 上 · Z 南';draw();
}
function enterWalk(point) {
  reset(false);updateGeometry();walk=true;$('walk').classList.add('selected');
  point=point??current.author.points.find(p=>p.kind==='entrance');
  const p=point?.approach??point?.pos??[current.size[0]/2,2,current.size[2]-2];
  eye=[p[0]+.5,p[1]+1.62,p[2]+.5];
  yaw={north:0,south:Math.PI,east:-Math.PI/2,west:Math.PI/2}[point?.facing]??0;pitch=0;
  const focus=point?.look_at??(point?.approach?point.pos:undefined);
  if(focus) {const dx=focus[0]+.5-eye[0],dy=focus[1]+.5-eye[1],dz=focus[2]+.5-eye[2];yaw=Math.atan2(dx,dz);pitch=Math.atan2(dy,Math.hypot(dx,dz));}
  renderer.setViewport(0,0,canvas.width,canvas.height);
  $('view-note').textContent='拖动转头 · W A S D 移动 · Q E 升降 · 漫游不模拟碰撞';draw();
}
let pointer;
canvas.addEventListener('pointerdown',e=>{canvas.setPointerCapture(e.pointerId);pointer={x:e.clientX,y:e.clientY,button:e.button};});
canvas.addEventListener('pointerup',()=>{pointer=null;});
canvas.addEventListener('pointermove',e=>{
  if(!pointer)return;const dx=e.clientX-pointer.x,dy=e.clientY-pointer.y;pointer.x=e.clientX;pointer.y=e.clientY;
  if(pointer.button===2&&!walk) { const s=distance*.0017;target[0]+=-Math.cos(yaw)*dx*s;target[2]+=Math.sin(yaw)*dx*s;target[1]+=dy*s; }
  else {yaw-=dx*.007;pitch=Math.max(walk?-1.5:.05,Math.min(1.56,pitch+(walk?-dy:dy)*.007));}draw();
});
canvas.addEventListener('contextmenu',e=>e.preventDefault());
canvas.addEventListener('wheel',e=>{e.preventDefault();distance=Math.max(2,Math.min(900,distance*Math.exp(e.deltaY*.001)));draw();},{passive:false});
window.addEventListener('keydown',e=>{
  if(!walk||['INPUT','SELECT'].includes(document.activeElement.tagName))return;
  const key=e.key.toLowerCase(),s=e.shiftKey?1.5:.35;
  if(!'wasdqe'.includes(key)||key.length!==1)return;e.preventDefault();
  if(key==='w'||key==='s') {const m=key==='w'?s:-s;eye[0]+=Math.sin(yaw)*m;eye[2]+=Math.cos(yaw)*m;}
  if(key==='a'||key==='d') {const m=key==='a'?s:-s;eye[0]+=Math.cos(yaw)*m;eye[2]-=Math.sin(yaw)*m;}
  if(key==='q'||key==='e')eye[1]+=key==='e'?s:-s;draw();
});
for(const button of document.querySelectorAll('[data-preset]'))button.onclick=()=>preset(button.dataset.preset);
for(const [i,a] of ['x','y','z'].entries())$(`clip-${a}`).oninput=e=>{clip[i]=Number(e.target.value);$(`value-${a}`).textContent=clip[i];updateGeometry();};
$('roof').onclick=()=>{roof=!roof;$('roof').classList.toggle('selected',roof);updateGeometry();};
$('context').onclick=()=>showContext(!context);
$('floor').onchange=()=>{const value=$('floor').value;reset(false);$('floor').value=value;if(value!==''){const f=current.author.floors[Number(value)];floorMin=f.y;clip[1]=f.max_y;$('clip-y').value=clip[1];$('value-y').textContent=clip[1];}updateGeometry();};
$('markers').onclick=()=>{markers=!markers;$('markers').classList.toggle('selected',markers);draw();};
$('walk').onclick=()=>walk?preset('front'):enterWalk();
$('reset').onclick=()=>reset();
$('save').onclick=()=>{draw();const a=document.createElement('a');a.download=`${current.author.id}-${selectedPreset}.png`;a.href=canvas.toDataURL('image/png');a.click();};
$('frontage-next').onclick = () => {
  const candidates = library?.results() ?? rows;
  const index = candidates.findIndex(row => row.id === current?.author.id);
  const next = [...candidates.slice(index + 1), ...candidates.slice(0, index + 1)]
    .find(row => row.id !== current?.author.id && row.frontage?.status !== 'ready');
  if (next) load(next.id).catch(fail);
  else $('frontage-status').textContent = '当前筛选中没有其他待标注素材。';
};
new ResizeObserver(resize).observe(canvas);

// Read-only automation surface, also used by the screenshot acceptance runner.
window.studio={telemetry,load,preset,reset,draw,focusRoom,enterWalk,showContext,
  get model(){return current;},setMarkers(value){markers=value;draw();},
  slice(axis,value){clip[['x','y','z'].indexOf(axis)]=value;updateGeometry();},
  hideRoof(){roof=true;updateGeometry();},floor(index){$('floor').value=String(index);$('floor').onchange();},
};
try {
  if(!gl)throw new Error('浏览器无法创建 WebGL 上下文');
  [resources,rows]=await Promise.all([loadResources(),fetchJSON('/api/catalog')]);
  library = createFilters(rows, populateList);
  populateList();
  const requested=new URLSearchParams(location.search).get('asset');
  if(rows.length)await load(rows.some(r=>r.id===requested)?requested:rows[0].id);
  else {$('title').textContent='结构库尚无模型';$('loading').textContent='先运行样板建模脚本，再刷新工作台。';}
}catch(err){fail(err);}
