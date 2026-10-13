import {BUILD,ADAPTERS,NAMES,compile,newState,perform,progress,restore} from './engine.mjs';
import {createMap} from './map.mjs';
const $=id=>document.getElementById(id),esc=x=>String(x??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const KEY='realmfolk-objective-types-v1';let catalog,variants,pack,state,settings,campus,selectedBuilding;
function error(message){$('error').textContent=message||'';$('error').classList.toggle('hidden',!message)}
function readSettings(){return {variant:$('variant').value,returning:$('loadout').value==='returning',ars:$('ars').checked,countExisting:$('existing').checked}}
function configure(s){const c=structuredClone(catalog);c.types['ars_school:basic_glyphs'].countExisting=s.countExisting;return compile(c,variants.find(v=>v.id===s.variant),{ars:s.ars});}
function persist(){try{localStorage.setItem(KEY,JSON.stringify({settings,state}))}catch{error('浏览器无法保存进度；当前页面仍可体验。')}}
function start(){settings=readSettings();pack=configure(settings);state=newState(pack,settings.returning);selectedBuilding=campus.placeFor(state.at);error('');persist();render()}
function act(action,target,count){try{const e={id:crypto.randomUUID(),action,target};if(count!==undefined)e.count=count;state=perform(pack,state,e);if(action==='visit')selectedBuilding=campus.placeFor(state.at);error('');persist();render()}catch(e){error(e.message)}}
function button(label,action,target,disabled=false){return `<button data-action="${action}" data-target="${esc(target)}" ${disabled?'disabled':''}>${esc(label)}</button>`}
function tools(){const glyph=$('glyph').value,enabled=pack.mods.includes('ars_nouveau'),known=state.known.includes(glyph),owned=state.inventory[glyph]||0;$('getGlyph').disabled=!enabled;$('learnGlyph').disabled=!enabled||known||owned<1;$('glyph').disabled=!enabled;$('glyphHint').textContent=!enabled?'当前实验未启用 Ars Nouveau。':`持有 ${owned} 枚 · ${known?'已经学会':'尚未学会'}。仅取得物品不会增加学习进度。`}
function render(){
  const actor=pack.actors.find(a=>a.id===state.at),qs=Object.values(pack.quests).filter(q=>q.issuer===actor.id),done=qs.some(q=>state.quests[q.id]?.state==='DONE');
  $('academy').textContent=pack.name;$('intro').textContent=pack.intro;
  $('residents').innerHTML=pack.actors.map(a=>`<button class="resident ${state.at===a.id?'active':''}" data-action="visit" data-target="${esc(a.id)}"><span class="seal">${esc(a.name[0])}</span><span><strong>${esc(a.name)}</strong><small>${esc(a.job)}<br>${esc(a.place)}</small></span></button>`).join('');
  $('dialogue').innerHTML=`<div class="place">${esc(actor.place)}</div><div class="speaker"><div><h2>${esc(actor.name)}</h2><span class="note">${esc(actor.job)}</span></div><span class="favor">与你的好感 ${state.favor[actor.id]||0}</span></div><p class="speech">“${esc(done?actor.thanks:actor.greeting)}”</p>`+qs.map(q=>{
    const p=state.quests[q.id],n=progress(state,q),learn=q.adapter==='ars_nouveau:learn_glyphs';
    const status=p?.state==='DONE'?'已回报':p?'已接取':'可接取';
    const names=q.targets.map(x=>NAMES[x]).join('、');
    const condition=learn?`在 ${names} 中掌握 ${q.count} 种`:(q.adapter==='realmfolk:deliver_items'?`交付 ${names} × ${q.count}`:`接取后亲手击败 ${names} × ${q.count}`);
    const learningRule=learn?(q.countExisting?'已学会的也计入；按不同种类计数。':'只计接取后新学会的种类；已有知识不会重复计入。'):q.adapter==='realmfolk:deliver_items'?'回报时扣除物品。':'接取之前的击杀不计入。';
    return `<article class="quest" data-quest="${esc(q.id)}"><div class="row"><span class="pill">${learn?'Ars 扩展目标':ADAPTERS[q.adapter].label}</span><span class="note" data-status>${status}</span></div><h3>${esc(q.title)}</h3><p>${esc(q.text)}</p><div class="objective"><div class="row"><strong>${esc(condition)}</strong><span data-progress>${n} / ${q.count}</span></div><div class="bar"><i style="width:${100*n/q.count}%"></i></div><p class="note">${esc(learningRule)}</p>${learn?`<div class="targets">${q.targets.map(x=>`<span class="target ${state.known.includes(x)?'known':''}">${esc(NAMES[x])}${state.known.includes(x)?(p?.baseline.includes(x)?' · 已有，不计':' ✓'):''}</span>`).join('')}</div>`:''}</div><div class="reward">回报：${q.rewardData.coins} 通币 · ${Object.entries(q.rewardData.items).map(([i,n])=>`${esc(NAMES[i])} × ${n}`).join('、')}<br>好感 +${q.rewardData.affection} · 学院声望 +${q.rewardData.reputation}</div><div class="actions">${p?.state==='DONE'?'<span class="done">✓ 委托已回报，奖励已经收入行囊。</span>':!p?button('这件事交给我','accept',q.id):button(n>=q.count?'我完成了，向你回报':'还未完成，回来再聊','turnin',q.id,n<q.count)}</div></article>`;
  }).join('')+(qs.length?'':'<div class="quest"><h3>这轮没有可接取的委托</h3><p class="note">实验环境未启用 Ars Nouveau，符文学习委托没有生成。其他居民的通用委托仍可接取。</p></div>');
  $('stats').innerHTML=`<div><strong data-coins>${state.coins}</strong><small>通币</small></div><div><strong>${state.reputation}</strong><small>学院声望</small></div>`;
  $('inventory').innerHTML=Object.entries(state.inventory).filter(([,n])=>n>0).map(([i,n])=>`<div class="entry" data-item="${esc(i)}"><span>${esc(NAMES[i]||i)}</span><strong>${n}</strong></div>`).join('')||'<div class="empty">行囊还是空的。</div>';
  $('known').innerHTML=state.known.map(i=>`<span class="target known">${esc(NAMES[i])}</span>`).join('')||'<span class="empty">还没有学会符文。</span>';
  $('journal').innerHTML=[...state.log].reverse().map(x=>`<div>${esc(x)}</div>`).join('');
  $('author').textContent=JSON.stringify(pack.catalog,null,2);$('ai').textContent=JSON.stringify(pack.input,null,2);
  $('metrics').textContent=`同一套 3 个目标类型 → 当前生成 ${Object.keys(pack.quests).length} 项独立委托。作者定义的 Ars 类型仍是 ars_school:basic_glyphs；更换学院只改变实例。${pack.disabled.length?' 未启用 Ars：已跳过 1 项学习委托。':''}`;
  campus.render(pack,state,selectedBuilding);tools();document.body.dataset.ready=BUILD;
}
function mapAction(e){const npc=e.target.closest('[data-map-npc]'),building=e.target.closest('[data-building]');if(npc)act('visit',npc.dataset.mapNpc);else if(building){selectedBuilding=building.dataset.building;campus.render(pack,state,selectedBuilding)}}
$('campusMap').addEventListener('click',mapAction);
$('campusMap').addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();mapAction(e)}});
document.addEventListener('click',e=>{const b=e.target.closest('[data-action]');if(b&&!b.disabled)act(b.dataset.action,b.dataset.target,b.dataset.count?Number(b.dataset.count):undefined)});
$('getGlyph').onclick=()=>act('obtain',$('glyph').value,1);$('learnGlyph').onclick=()=>act('learn',$('glyph').value);$('kill').onclick=()=>act('kill',$('mob').value);$('glyph').onchange=tools;
$('restart').onclick=()=>{try{start()}catch(e){error(e.message)}};
try{
  const responses=await Promise.all([fetch('./author-catalog.json'),fetch('./ai-instances.json'),fetch('./campus-layout.json')]);if(responses.some(r=>!r.ok))throw Error('配置读取失败');
  catalog=await responses[0].json();variants=(await responses[1].json()).variants;
  campus=createMap(await responses[2].json());
  $('variant').innerHTML=variants.map(v=>`<option value="${esc(v.id)}">${esc(v.name)}</option>`).join('');
  $('glyph').innerHTML=catalog.types['ars_school:basic_glyphs'].allowedTargets.map(x=>`<option value="${esc(x)}">${esc(NAMES[x])}</option>`).join('');
  let saved;try{saved=JSON.parse(localStorage.getItem(KEY)||'null')}catch{}
  if(saved){try{settings=saved.settings;pack=configure(settings);state=restore(pack,JSON.stringify(saved.state));$('variant').value=settings.variant;$('loadout').value=settings.returning?'returning':'new';$('ars').checked=settings.ars;$('existing').checked=settings.countExisting;render()}catch{start();error('之前的实验进度已不兼容，已开始新实验。')}}else start();
}catch(e){error(e.message)}
