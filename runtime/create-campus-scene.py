from pathlib import Path
p=Path('tools/structure_studio/studio/campus_daily.py');s=p.read_text(encoding='utf-8').replace("'MG-43':lab,'MG-44':washhouse,'MG-45':shop", "'MG-43-v01':lab,'MG-44-v01':washhouse,'MG-45-v01':shop");p.write_text(s,encoding='utf-8')
root=Path('asset_catalogs/original_civilizations/R02_arcane_reborn/models').resolve()
for ident in ('MG-43','MG-44','MG-45'):
    old=(root/ident).resolve();new=(root/(ident+'-v01')).resolve()
    assert old.parent==root and new.parent==root
    if old.exists():old.rename(new)
# A four-group campus around a dining-front public space. Local anchors carry no handwritten building positions.
import json
A=lambda n:'studio:'+n.lower()
def leaf(ident,func,algorithm,core,required,count=None,rotation=0,**layout):
    a=dict(arrayId=ident,function=func,anchorBlock=dict(x=0,z=0),algorithm=algorithm,count=count or len(required)+bool(core),rotation=rotation,densityClass='DENSE',layout=dict(gapBlocks=8,**layout))
    if core:a['coreAssetId']=A(core)
    if required:a['requiredAssetIds']=[A(x) for x in required]
    return a
arrays=[leaf('main-court','主教学院落','COURTYARD','MG-01-v01',['MG-34-v02','MG-34-v02','MG-38-v01'],courtWidth=65,courtDepth=45,faceInward=True,purpose='安静阅读花园'),
 leaf('theory-classrooms','普通教学组团','GRID','MG-25-v01',['MG-26-v01']*3,columns=2),
 leaf('practice-classrooms','实验教学组团','GRID','MG-25-v01',['MG-43-v01','MG-43-v01','MG-31-v01'],columns=2),
 leaf('services','食堂与生活服务','LINEAR','MG-07-v01',['MG-44-v01','MG-45-v01'],rotation=180),
 leaf('collective-dorms','集体住宿组团','GRID',None,['MG-37-v02']+['MG-27-v01']*3,columns=2),
 leaf('small-dorms','小宿舍组团','GRID',None,['MG-27-v01']*4,columns=2)]
def comp(ident,alg,children,**extra):
    return dict(compositionId=ident,algorithm=alg,anchorBlock=dict(x=0,z=0),memberIds=children,layout=dict(gapBlocks=12),**extra)
comps=[comp('teaching-support','LINEAR',['theory-classrooms','practice-classrooms'],directionDegrees=90),
       comp('residential-group','LINEAR',['collective-dorms','small-dorms'],directionDegrees=90),
       comp('campus-center','COURTYARD',['services','main-court','teaching-support','residential-group'])]
comps[-1]['anchorBlock']=dict(x=-2950,z=5320);comps[-1]['layout'].update(courtWidth=56,courtDepth=42,purpose='食堂前共享广场')
districts=[dict(districtId='teaching',name='教学组团',coreMemberId='main-court:1',arrayIds=['main-court','theory-classrooms','practice-classrooms'],platform=True),
 dict(districtId='living',name='食堂广场与住宿',coreMemberId='services:1',arrayIds=['services','collective-dorms','small-dorms'],platform=True)]
terraces=[dict(terraceId='main-platform',districtId='teaching',coreMemberId='main-court:1',arrayIds=['main-court'],styleRef='stone_edge'),
 dict(terraceId='theory-platform',districtId='teaching',coreMemberId='theory-classrooms:1',arrayIds=['theory-classrooms'],styleRef='stone_edge'),
 dict(terraceId='practice-platform',districtId='teaching',coreMemberId='practice-classrooms:1',arrayIds=['practice-classrooms'],styleRef='stone_edge'),
 dict(terraceId='living-platform',districtId='living',coreMemberId='services:1',arrayIds=['services','collective-dorms','small-dorms'],styleRef='stone_edge')]
style=json.loads(Path('tools/city_experiments/terrace-trial.json').read_text(encoding='utf-8'))['terraceStyles'][0];style.pop('courtAssetId',None)
scene=dict(seed=20261009,greenery=True,arrays=arrays,compositions=comps,districts=districts,terraceGroups=terraces,terraceStyles=[style],terraceFeatures=True,foundationMarginBlocks=8,
 connections=[dict(**{'from':'teaching','to':'living'})],publicSpaces=[dict(nodeId='main-court',kind='GARDEN'),dict(nodeId='campus-center',kind='PLAZA')])
Path('tools/city_experiments/campus-groups.json').write_text(json.dumps(scene,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
