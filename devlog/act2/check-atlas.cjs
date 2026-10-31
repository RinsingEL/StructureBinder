// Offline JavaScript execution checks, not browser/visual acceptance.
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const path=require('node:path');
const html=fs.readFileSync(path.join(__dirname,'wtd-atlas.html'),'utf8');
const payload=html.match(/<script id="atlas-data" type="application\/json">([\s\S]*?)<\/script>/)[1];
const scripts=[...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m=>m[1]);
const elements=new Map(),handlers={},frames=new Map();let serial=0;
let drawCalls=0;const paintedText=[];
const context=new Proxy({}, {get(o,k){if(k in o)return o[k];return (...args)=>{for(const x of args)if(typeof x==='number')assert(Number.isFinite(x),`${String(k)}: nonfinite coordinate`);if(k==='fillText')paintedText.push(args[0]);drawCalls++;};},set(o,k,v){o[k]=v;return true;}});
function element(id=''){
 return {id,hidden:false,style:{},dataset:{},children:[],textContent:'',innerHTML:'',classList:{toggle(){}},
  getContext(){return context},append(...items){this.children.push(...items)},replaceChildren(...items){this.children=items},focus(){}};
}
const document={getElementById(id){if(!elements.has(id))elements.set(id,element(id));return elements.get(id)},createElement:()=>element(),createTextNode:t=>({textContent:t}),addEventListener(){},documentElement:{}};
document.getElementById('atlas-data').textContent=payload;
const sandbox={Image:class {complete=false;naturalWidth=0;},document,console,Uint8Array,Map,Set,Math,JSON,atob:s=>Buffer.from(s,'base64').toString('binary'),innerWidth:1600,innerHeight:900,matchMedia:()=>({matches:false}),performance:{now:()=>0},requestAnimationFrame:fn=>{frames.set(++serial,fn);return serial},cancelAnimationFrame:id=>frames.delete(id),window:{addEventListener:(name,fn)=>handlers[name]=fn}};
vm.createContext(sandbox);scripts.forEach(s=>vm.runInContext(s,sandbox));
function run(s){return vm.runInContext(s,sandbox)}
const count=run('beats.length');assert.equal(count,23);
assert(run("beats.findIndex(b=>b[0]==='D1c') < beats.findIndex(b=>b[0]==='D1d')"));
assert.equal(run('Object.keys(revision.overviewMaterials).length'),45);
assert.equal(run('revision.provenance.materialsBeforeOverview'),true);
for(const direction of [1,-1]){
 for(let n=0;n<count;n++){
  const i=direction===1?n:count-1-n;
  run(`go(${i})`);
  for(const fraction of [0,.15,.4,.65,.9,1]){
   paintedText.length=0;
   run(`current={...beats[index][4]};draw(current,${fraction})`);
   const visiblePanels=['params','explain','stack','roster'].filter(id=>!elements.get(id).hidden).map(id=>elements.get(id).innerHTML).join(' ');
   assert(!/algorithm:|studio:|anchorBlock|structureCount|gapBlocks|arrayCompositions|requiredStructureRefs/.test(visiblePanels),'technical field leaked into presentation');
   assert(!paintedText.some(t=>/algorithm:|studio:|gapBlocks|anchorBlock/.test(String(t))),'technical identifier painted on map');
  }
 }
}
function settled(id){run(`go(beats.findIndex(b=>b[0]===${JSON.stringify(id)}));current={...beats[index][4]};draw(current,1)`)}
settled('T1');assert.equal((elements.get('roster').innerHTML.match(/class="realm"/g)||[]).length,7);
settled('T2');assert(!elements.get('roster').hidden);
assert(run("seedReveal(0,beats[index][4],.1)===0"));
assert(run("seedReveal(0,beats[index][4],.3)>0"));
assert(run('round2.reasons.every(r=>r.fixed||r.reason.includes(r.quote))'));
settled('T3');assert(!elements.get('roster').hidden);
settled('D1c');assert(elements.get('explain').innerHTML.includes('系统有筛选接口'));assert(elements.get('explain').innerHTML.includes('按学院素材编号'));
settled('D1d');assert(elements.get('explain').innerHTML.includes('45 种素材'));
assert(run('round2.piles.every(p=>p.refs.length===p.count && p.refs.every(ref=>round2.materials[ref]))'));
assert(run("['studio:mg-27-v02','studio:mg-37-v03','studio:mg-05-v02','studio:mg-17-v02'].every(ref=>round2.materials[ref].imageData)"));
settled('D1e');assert.equal((elements.get('explain').innerHTML.match(/class="district-row"/g)||[]).length,16);
assert.equal(run('Object.keys(intentDirections).length'),16);
assert(run('revision.districts.every(d=>d.intent.includes(intentDirections[d.groupId]))'));
assert(run('intentFlight(0,.2,[1000,200]).t===0 && intentFlight(0,1,[1000,200]).x===1000'));
assert.equal(elements.get('sheet').dataset.intentPresentation,'approximate-map-flight');
assert(paintedText.includes('总方案：先说明用途和大致关系，具体坐标在后续组团设计时确定。'));
for(let i=0;i<16;i++){
 const p=i===0?.15:.3+(i-.5)/15*.7;
 paintedText.length=0;
 run(`draw(current,${p})`);
 const id=run(`revision.districts[${i}].groupId`),name=run(`districtName(revision.districts[${i}])`);
 assert.equal(elements.get('sheet').dataset.activeDistrict,id);
 assert(paintedText.includes(String(i+1).padStart(2,'0')+' · '+name),'No active map label: '+id);
 assert(elements.get('explain').innerHTML.includes('color:#B07A4F;font-weight:700'));
}
settled('D3');assert(elements.get('params').innerHTML.includes('围合院子'));assert(!elements.get('params').innerHTML.includes('patternPreference'));
settled('D4');
assert(elements.get('params').innerHTML.includes('AI 计划<b>4 栋'));
assert(elements.get('params').innerHTML.includes('程序计算结果<b>1 个建筑落位'));
assert(paintedText.includes('实线：程序实际计算出的落位'));
settled('D5a');assert(elements.get('params').innerHTML.includes('净空 8 格'));
settled('D5b');assert(elements.get('params').innerHTML.includes('14 格'));
assert.equal(run('closestMarketGap(69).distance'),8);assert.equal(run('closestMarketGap(70).distance'),14);
settled('D6');assert(elements.get('params').innerHTML.includes('三栋小宿舍成排'));
settled('D7');assert.equal((elements.get('stack').innerHTML.match(/class="slip /g)||[]).length,16);assert.equal(elements.get('stats').textContent,'86 / 86');
for(let i=1;i<16;i++){
 for(const q of [.1,.4,.8]){
  const p=.12+(i-1+q)/15*.78;
  paintedText.length=0;run(`draw(current,${p})`);
  assert(!elements.get('params').hidden,'missing full parameter sheet');
  assert(elements.get('params').innerHTML.includes(run(`districtName(districtOrder[${i}])`)),'wrong parameter sheet');
  assert(elements.get('params').innerHTML.includes(q<.2?'选材':q<.7?'填写组团参数':'按真实结果展开'));
  if(q<.2)assert(paintedText.some(t=>Object.values(JSON.parse(payload).round2.materials).some(m=>m.name===t)),'missing named material card');
  assert(!/algorithm:|studio:|anchorBlock|structureCount|gapBlocks|arrayCompositions/.test(elements.get('params').innerHTML));
 }
}
const unique=run(`new Set(districtOrder.flatMap(d=>{const ids=new Set(revision.designs[d.groupId].design.groups.map(g=>g.groupId));return snap(74).anchors.filter(a=>ids.has(a.placementGroupId)).map(a=>a.anchorId)})).size`);assert.equal(unique,86);
settled('D-photos');for(const id of ['roster','params','explain','stack'])assert(elements.get(id).hidden);
assert(!elements.get('safe').hidden);assert.equal(run('photoCards.length'),6);
assert.equal(run('Object.keys(data.photos).length'),6);
assert(run("beats.every(b=>!b[0].startsWith('D8'))"));
handlers.keydown({key:'ArrowLeft',preventDefault(){}});assert.equal(run('beat()'),'D7');
handlers.keydown({key:'ArrowRight',preventDefault(){}});assert.equal(run('beat()'),'D-photos');
console.log(`PASS: ${count} beats forward/backward at six checkpoints; 7 realms, 16 districts, 86 unique units; keyboard and overlay cleanup; ${drawCalls} finite drawing calls. Browser layout not tested.`);
