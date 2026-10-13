// Offline experiment only. Events are simulated input, never Minecraft evidence.
export const BUILD='objective-types-20261013-v1';
export const ADAPTERS={
  'realmfolk:deliver_items':{label:'交付物品',kind:'item'},
  'realmfolk:kill_entities':{label:'击杀生物',kind:'entity'},
  'ars_nouveau:learn_glyphs':{label:'学会不同符文',kind:'glyph',mod:'ars_nouveau'}
};
export const NAMES={
  'minecraft:paper':'纸','minecraft:iron_ingot':'铁锭','minecraft:bread':'面包','minecraft:lapis_lazuli':'青金石',
  'minecraft:zombie':'僵尸','minecraft:skeleton':'骷髅','minecraft:cow':'牛',
  'ars_nouveau:glyph_light':'光亮符文','ars_nouveau:glyph_harm':'伤害符文','ars_nouveau:glyph_heal':'治疗符文',
  'ars_nouveau:glyph_freeze':'冻结符文','ars_nouveau:glyph_grow':'生长符文'
};
const copy=x=>structuredClone(x),require=(x,m)=>{if(!x)throw Error(m)},id=x=>typeof x==='string'&&/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(x);
const ENTITIES=new Set(['minecraft:zombie','minecraft:skeleton','minecraft:cow']);
const GLYPHS=new Set(Object.keys(NAMES).filter(x=>x.startsWith('ars_nouveau:')));
const ITEMS=new Set(Object.keys(NAMES).filter(x=>!ENTITIES.has(x)));
const integer=(n,min,max)=>Number.isInteger(n)&&n>=min&&n<=max;
function only(object,keys){require(object&&typeof object==='object'&&!Array.isArray(object),'配置需要对象');require(Object.keys(object).every(k=>keys.includes(k)),'存在未知配置字段');}
function unique(xs){return Array.isArray(xs)&&xs.length>0&&xs.length<=128&&new Set(xs).size===xs.length;}
export function compile(catalog,variant,options={}){
  only(catalog,['version','types','rewards']);require(catalog.version===1,'未知作者目录版本');
  const mods=options.ars===false?[]:['ars_nouveau'];
  const countExisting=options.countExisting??true;
  require(typeof countExisting==='boolean','已有学习规则必须为布尔值');
  for(const [key,r] of Object.entries(catalog.rewards)){
    only(r,['items','coins','affection','reputation']);require(id(key)&&integer(r.coins,0,4096)&&integer(r.affection,0,30)&&integer(r.reputation,0,20),'奖励超出上限');
    require(Object.keys(r.items).length<=4,'奖励种类过多');
    for(const [item,n] of Object.entries(r.items))require(ITEMS.has(item)&&integer(n,1,64),'奖励物品无效');
  }
  for(const [key,t] of Object.entries(catalog.types)){
    only(t,['adapter','allowedTargets','minCount','maxCount','countExisting','rewardChoices']);
    const a=ADAPTERS[t.adapter];require(id(key)&&a,'没有实现这个目标适配器');
    require(integer(t.minCount,1,64)&&integer(t.maxCount,t.minCount,64),'数量范围无效');
    const registry=a.kind==='entity'?ENTITIES:a.kind==='glyph'?GLYPHS:ITEMS;
    require(unique(t.allowedTargets)&&t.allowedTargets.every(x=>registry.has(x)),'目标不在实验注册表中');
    require(unique(t.rewardChoices)&&t.rewardChoices.every(x=>catalog.rewards[x]),'奖励候选无效');
    require(t.countExisting===undefined||typeof t.countExisting==='boolean','学习规则无效');
  }
  require(variant.actors.length>0&&new Set(variant.actors.map(a=>a.id)).size===variant.actors.length,'NPC 标识重复');
  const quests={},disabled=[];
  for(const [key,q] of Object.entries(variant.quests)){
    only(q,['type','title','text','issuerRole','targets','count','reward']);const t=catalog.types[q.type];
    require(id(key)&&t,'未知任务类型');require(typeof q.title==='string'&&q.title.length>0&&q.title.length<=64&&typeof q.text==='string'&&q.text.length<=800,'文本无效');
    require(unique(q.targets)&&q.targets.every(x=>t.allowedTargets.includes(x)),'AI 目标不在作者候选中');
    require(integer(q.count,t.minCount,t.maxCount)&&t.rewardChoices.includes(q.reward),'AI 数量或奖励超出作者许可');
    const a=ADAPTERS[t.adapter];
    require(a.kind!=='item'||q.targets.length===1,'交付任务应指定一种物品');
    require(a.kind!=='glyph'||q.count<=q.targets.length,'不同符文要求超过候选数');
    const issuers=variant.actors.filter(x=>x.role===q.issuerRole);require(issuers.length===1,'需要唯一可用发布者');
    if(a.mod&&!mods.includes(a.mod)){disabled.push({id:key,title:q.title,reason:'实验环境未启用 Ars Nouveau，不能发布学习符文任务'});continue;}
    quests[key]={...copy(q),id:key,issuer:issuers[0].id,adapter:t.adapter,countExisting:t.countExisting===false?false:countExisting,rewardData:copy(catalog.rewards[q.reward])};
  }
  const result={build:BUILD,variant:variant.id,name:variant.name,intro:variant.intro,actors:copy(variant.actors),mods,quests,disabled,catalog:copy(catalog),input:copy(variant.quests)};
  result.signature=JSON.stringify([BUILD,variant,catalog,mods,countExisting]);return result;
}
export function newState(pack,returning=false){
  return {build:BUILD,signature:pack.signature,variant:pack.variant,at:pack.actors[0].id,inventory:{},known:returning&&pack.mods.includes('ars_nouveau')?['ars_nouveau:glyph_light','ars_nouveau:glyph_heal']:[],quests:{},coins:0,favor:{},reputation:0,log:['你来到学院。先选一位居民，听听他们的委托。'],receipts:{},revision:0};
}
export function progress(state,q){
  const p=state.quests[q.id];if(p?.state==='DONE')return q.count;
  if(q.adapter==='realmfolk:deliver_items')return Math.min(q.count,state.inventory[q.targets[0]]||0);
  if(q.adapter==='ars_nouveau:learn_glyphs')return Math.min(q.count,new Set(state.known.filter(x=>q.targets.includes(x)&&!(p?.baseline||(q.countExisting?[]:state.known)).includes(x))).size);
  return p?.count||0;
}
export function perform(pack,original,event){
  require(original.build===BUILD&&original.signature===pack.signature,'配置已变更，请重新开始实验');
  only(event,['id','action','target','count']);require(typeof event.id==='string'&&event.id.length>0&&event.id.length<=100,'缺少操作标识');
  const signature=JSON.stringify(event);
  if(original.receipts[event.id]){require(original.receipts[event.id]===signature,'同一操作标识不能改写');return copy(original);}
  const s=copy(original);const log=m=>{s.log.push(m);s.log=s.log.slice(-40)};
  const q=pack.quests[event.target];
  switch(event.action){
    case 'visit':
      require(pack.actors.some(a=>a.id===event.target),'找不到这位居民');s.at=event.target;
      log('你来到'+pack.actors.find(a=>a.id===s.at).place+'。');break;
    case 'accept':
      require(q&&q.issuer===s.at,'请向发布者本人接取委托');require(!s.quests[q.id],'已接取过这项委托');
      require(q.adapter!=='ars_nouveau:learn_glyphs'||q.countExisting||q.targets.filter(x=>!s.known.includes(x)).length>=q.count,'剩余未学的候选不足；请调整目标或允许计入已有学习');
      s.quests[q.id]={state:'ACTIVE',count:0,baseline:q.countExisting?[]:[...s.known]};log('接下委托：'+q.title+'。');break;
    case 'turnin':{
      require(q&&q.issuer===s.at,'请回到原发布者身边');require(s.quests[q.id]?.state==='ACTIVE','没有待回报的委托，或奖励已经领取');
      require(progress(s,q)>=q.count,'目标尚未完成');
      if(q.adapter==='realmfolk:deliver_items')s.inventory[q.targets[0]]-=q.count;
      s.quests[q.id].state='DONE';s.coins+=q.rewardData.coins;s.reputation+=q.rewardData.reputation;s.favor[q.issuer]=(s.favor[q.issuer]||0)+q.rewardData.affection;
      for(const [item,n] of Object.entries(q.rewardData.items))s.inventory[item]=(s.inventory[item]||0)+n;
      log('回报「'+q.title+'」：获得 '+q.rewardData.coins+' 通币，'+pack.actors.find(a=>a.id===q.issuer).name+' 的好感增加 '+q.rewardData.affection+'。');break;
    }
    case 'obtain':
      require(ITEMS.has(event.target)&&integer(event.count,1,16),'模拟物品或数量无效');
      require(!GLYPHS.has(event.target)||pack.mods.includes('ars_nouveau'),'未启用 Ars Nouveau');
      s.inventory[event.target]=(s.inventory[event.target]||0)+event.count;log('【模拟背包】获得 '+NAMES[event.target]+' × '+event.count+'。');break;
    case 'learn':
      require(pack.mods.includes('ars_nouveau')&&GLYPHS.has(event.target),'符文目标或模组不可用');
      require(!s.known.includes(event.target),'这种符文已经学会，重复学习不增加种类');
      require((s.inventory[event.target]||0)>0,'先在实验工具中取得这枚符文物品');
      s.inventory[event.target]--;s.known.push(event.target);log('【模拟模组能力】已学会 '+NAMES[event.target]+'；符文物品消耗一枚。');break;
    case 'kill':
      require(ENTITIES.has(event.target),'未知模拟生物');
      for(const task of Object.values(pack.quests)){
        const p=s.quests[task.id];if(p?.state==='ACTIVE'&&task.adapter==='realmfolk:kill_entities'&&task.targets.includes(event.target))p.count=Math.min(task.count,p.count+1);
      }
      log('【模拟击杀事件】你击败了一只'+NAMES[event.target]+'。');break;
    default:throw Error('未知实验操作');
  }
  require(s.coins>=0&&Object.values(s.inventory).every(n=>Number.isInteger(n)&&n>=0),'状态不合法');
  s.receipts[event.id]=signature;s.revision++;return s;
}
export function restore(pack,text){
  const s=JSON.parse(text);require(s.build===BUILD&&s.signature===pack.signature,'旧实验进度与当前配置不兼容');
  require(Array.isArray(s.known)&&Array.isArray(s.log)&&s.inventory&&s.quests&&s.receipts&&Number.isInteger(s.revision),'实验存档不完整');return s;
}
