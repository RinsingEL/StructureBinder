from pathlib import Path
p=Path('tools/structure_studio/web/campus.js');s=p.read_text(encoding='utf-8');pos=s.index('async function refresh()');s=s[:pos]+'''function focusNode(id){
 const members=data.metadata.members.filter(m=>m.arrayId===id||m.compositionPath?.includes(id));if(!members.length)return;
 const origin=data.world.origin,minx=Math.min(...members.map(m=>m.originBlock.x)),maxx=Math.max(...members.map(m=>m.originBlock.x+m.width)),minz=Math.min(...members.map(m=>m.originBlock.z)),maxz=Math.max(...members.map(m=>m.originBlock.z+m.depth));
 target=[(minx+maxx)/2-origin[0],members.reduce((v,m)=>v+m.surfaceY,0)/members.length-origin[1]+12,(minz+maxz)/2-origin[2]];distance=Math.max(maxx-minx,maxz-minz)*1.5;pitch=.75;
 $('detail').textContent=`${id}：${members.length} 栋建筑，${new Set(members.map(m=>m.arrayId)).size} 个子阵列。`;
 draw();
}
''' +s[pos:];s=s.replace("$('array').onchange=arrayControls;", "$('array').onchange=()=>{arrayControls();focusNode($('array').value);};");s=s.replace('window.campus={telemetry,focus,overview', 'window.campus={telemetry,focus,focusNode,overview');p.write_text(s,encoding='utf-8')
p=Path('tools/structure_studio/scripts/capture-campus-groups.mjs');s=p.read_text(encoding='utf-8').replace(" const choices=await page.locator", " await shot('garden',()=>{window.campus.focusNode('main-court');window.campus.setView({yaw:Math.PI-.65,pitch:.95,distance:235});});\n const choices=await page.locator");p.write_text(s,encoding='utf-8')
