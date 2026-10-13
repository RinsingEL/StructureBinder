from pathlib import Path
p=Path('tools/structure_studio/web/campus.js');s=p.read_text(encoding='utf-8')
s=s.replace('function loadControls(){','''function layoutNodes(){return [...scene.compositions,...scene.arrays];}
function currentArray(){return layoutNodes().find(a=>(a.arrayId||a.compositionId)===$('array').value);}
function hierarchyChoices(){
 const nodes=layoutNodes(),byId=new Map(nodes.map(n=>[n.arrayId||n.compositionId,n]));
 const children=new Set(scene.compositions.flatMap(n=>n.memberIds)),rows=[];
 function visit(n,depth){const id=n.arrayId||n.compositionId;rows.push([id,`${'　'.repeat(depth)}${n.layout?.purpose||n.function||id} · ${algorithmNames[n.algorithm]}${n.count?` / ${n.count} 栋`:` / ${n.memberIds.length} 组`}`]);for(const id of n.memberIds||[])visit(byId.get(id),depth+1);}
 for(const n of nodes)if(!children.has(n.arrayId||n.compositionId))visit(n,0);return rows;
}
function loadControls(){''')
s=s.replace("choice($('array'),scene.arrays.map(a=>[a.arrayId,`${a.function||'组团'} · ${a.arrayId}`]),scene.arrays[0].arrayId);", "choice($('array'),hierarchyChoices());")
s=s.replace("choice($('node'),[...scene.compositions,...scene.arrays].map(a=>[a.compositionId||a.arrayId,a.compositionId||a.arrayId]),scene.compositions[1]?.compositionId);", "choice($('node'),hierarchyChoices());")
a=s.index('function arrayControls()');b=s.index('function nodeControls()',a)
s=s[:a]+'''function arrayControls(){const a=currentArray(),composition=!!a.compositionId;$('algorithm').value=a.algorithm;$('density').value=a.densityClass||'BALANCED';$('count').value=a.count||a.memberIds.length;$('rotation').value=a.rotation||0;$('asset').value=a.coreAssetId||a.requiredAssetIds?.[0]||a.fill?.[0]?.assetId||'';
 for(const id of ['count','rotation','asset'])$(id).disabled=composition||(id==='rotation'&&a.layout?.faceInward);
 $('density').disabled=!!a.layout;
 $('array-note').textContent=composition?`整体组合：${a.memberIds.join('、')}。子组内部布局随整体一起移动。`:a.layout?.faceInward?'建筑入口朝向院内；中心保留花园与通路。':a.coreAssetId?'核心与配套按实际占地排列。':'配套建筑按实际占地排列。';
 if(a.layout)$('array-note').textContent+=` 间距 ${a.layout.gapBlocks} 格。`;
}
''' +s[b:]
s=s.replace("const a=scene.arrays.find(a=>a.arrayId===$('array').value);a.algorithm=$('algorithm').value;a.densityClass=$('density').value;a.count=Number($('count').value);a.rotation=Number($('rotation').value);", "const a=currentArray();a.algorithm=$('algorithm').value;a.densityClass=$('density').value;if(!a.compositionId){a.count=Number($('count').value);if(!a.layout?.faceInward)a.rotation=Number($('rotation').value);}")
p.write_text(s,encoding='utf-8')
p=Path('tools/structure_studio/campus.html');s=p.read_text(encoding='utf-8').replace('当前阵列<select','阵列与整体组合<select');p.write_text(s,encoding='utf-8')
