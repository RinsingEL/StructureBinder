const esc=x=>String(x??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const groups=[['archives','文库','#b5c6d0'],['forest_school','林业与植物教学','#9bbf9c'],['observation','观测与研究','#b9acd0'],['daily_care','生活服务','#d7c69c'],['reception','接待与物资','#d8b49c'],['residence','居住','#d3cebf'],['resident_rooms','居住','#d3cebf'],['teaching','教学','#9dbfba'],['council','议事与研讨','#b0bfc2'],['seminars','议事与研讨','#b0bfc2']];
const group=b=>groups.find(([prefix])=>b.id.startsWith(prefix))||['other','其他','#c7cdc3'];
export function createMap(data){
  if(data.version!==1||!data.buildings.length)throw Error('布局底板无效');
  const buildings=new Map(data.buildings.map(b=>[b.id,b]));
  for(const b of buildings.values()){const r=b.bounds;if(!['minX','maxX','minZ','maxZ'].every(k=>Number.isFinite(r[k]))||r.maxX<r.minX||r.maxZ<r.minZ)throw Error('建筑边界无效')}
  for(const id of Object.values(data.bindings))if(!buildings.has(id))throw Error('人物绑定建筑不存在');
  const bounds=data.buildings.map(b=>b.bounds),x0=Math.min(...bounds.map(b=>b.minX)),x1=Math.max(...bounds.map(b=>b.maxX)),z0=Math.min(...bounds.map(b=>b.minZ)),z1=Math.max(...bounds.map(b=>b.maxZ));
  const scale=Math.min(650/(x1-x0+1),420/(z1-z0+1)),w=(x1-x0+1)*scale,h=(z1-z0+1)*scale;
  const x=v=>((v-x0)*scale+65),y=v=>((v-z0)*scale+60);
  function render(pack,state,selected){
    const current=data.bindings[state.at],chosen=buildings.get(selected)||buildings.get(current),svg=document.getElementById('campusMap');
    svg.setAttribute('viewBox',`0 0 ${w+130} ${h+125}`);
    svg.innerHTML='<title>学院 D4 建筑布局。点击建筑查看用途，点击人物切换对话。</title>'+data.buildings.map(b=>{const r=b.bounds,active=b.id===chosen.id;return `<rect class="building ${active?'selected':''}" role="button" tabindex="0" aria-label="${esc(b.terms.join('、'))}" data-building="${esc(b.id)}" x="${x(r.minX)}" y="${y(r.minZ)}" width="${Math.max(3,(r.maxX-r.minX+1)*scale)}" height="${Math.max(3,(r.maxZ-r.minZ+1)*scale)}" rx="2" fill="${group(b)[2]}"><title>${esc(group(b)[1]+' · '+b.terms.join(' / '))}</title></rect>`}).join('')+pack.actors.map(a=>{
      const b=buildings.get(data.bindings[a.id]);if(!b)return '';const cx=x((b.bounds.minX+b.bounds.maxX)/2),cy=y((b.bounds.minZ+b.bounds.maxZ)/2),active=a.id===state.at;
      const tasks=Object.values(pack.quests).filter(q=>q.issuer===a.id),status=tasks.length===0?'无委托':tasks.every(q=>state.quests[q.id]?.state==='DONE')?'已回报':tasks.some(q=>state.quests[q.id]?.state==='ACTIVE')?'进行中':'有委托';
      return `<g class="map-person ${active?'active':''}" role="button" tabindex="0" data-map-npc="${esc(a.id)}" aria-label="与${esc(a.name)}交谈"><circle cx="${cx}" cy="${cy}" r="13"/><text x="${cx}" y="${cy+4}" text-anchor="middle" class="initial">${esc(a.name[0])}</text><rect class="pin-label" x="${cx-51}" y="${cy-37}" width="102" height="20" rx="5"/><text class="pin-text" x="${cx}" y="${cy-23}" text-anchor="middle">${esc(a.name)} · ${status}</text></g>`;
    }).join('')+`<text x="${w+98}" y="25" text-anchor="middle" class="compass">北 ↑</text>`;
    const seen=new Set();document.getElementById('mapLegend').innerHTML=groups.filter(([,n])=>{if(seen.has(n))return false;seen.add(n);return true}).map(([,name,color])=>`<span><i style="background:${color}"></i>${name}</span>`).join('');
    const actors=pack.actors.filter(a=>data.bindings[a.id]===chosen.id);
    document.getElementById('buildingDetail').innerHTML=`<strong>${esc(group(chosen)[1])}</strong><p>${esc(chosen.terms.join(' · '))}</p><span class="note">${actors.length?actors.map(a=>esc(a.name+' · '+a.job)).join('、'):'这栋建筑尚未安排本轮实验人物。'}</span>`;
  }
  return {render,placeFor:id=>data.bindings[id],count:buildings.size};
}
