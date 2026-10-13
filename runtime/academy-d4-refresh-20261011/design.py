import json,pathlib,sys
p=pathlib.Path('runtime/academy-d4-refresh-20261011')
def read(n): return json.loads(json.load(open(p/(n+'.response.json'),encoding='utf-8'))['content'][0]['text'])
def group(id,patch,refs,n,pool,alg='grid',core=False,zone='CENTER',**layout):
 return dict(groupId=id,groupKind='STRUCTURE',preferredPatchRefs=[f'minecraft:overworld:step.16:city.city_jingchao_capital:p.{patch}'],preferredPatchZone=zone,role=id,priority='CORE' if core else 'STANDARD',structureCount=n,extentClass='LARGE',densityClass='BALANCED',algorithmProfileRef='algorithm:'+alg,terrainPolicy='BALANCED',requiredStructureRefs=['studio:mg-'+r for r in refs],fillPools=[dict(poolRef=f'pool:studio_small_{pool:03}',weight=1)],compositionProfileRef='composition:round_robin',attachedFeatures=[],targetAreaShare=.25,spaceComposition=dict(buildingShare=.65,landscapeShare=0,openSpaceShare=.35),expansionPolicy=dict(allowOutwardExpansion=True,allowRelationConnection=True,stopWhenTargetReached=True),buildingGreeneryPolicy=dict(coverage='BALANCED',patternPreference='TEMPLATE_DEFAULT',densityPreference='TEMPLATE_DEFAULT'),layout=dict(gapBlocks=8,**layout))
def conn(a,b): return dict(fromGroupId=a,toGroupId=b,relationKind='CONNECTION',strength='HARD',distancePreference='NONE',directionPreference='NONE')
def comp(id,center,members): return dict(compositionId=id,centerGroupId=center,memberGroupIds=members,algorithmProfileRef='algorithm:grid',layout=dict(gapBlocks=12,columns=2))
which,previous,out=sys.argv[1:]
d=read(previous); w=d['d4Workflow']; args=dict(runId='provider_3d25631f_r19200',citySeedId='city_jingchao_capital',contextId=w['contextId'],workflowRevision=w['revision'])
if which=='teaching':
 gs=[group('teaching',100,['01-v01','34-v02','38-v01','25-v01'],4,18,'courtyard',True,courtWidth=65,courtDepth=45,faceInward=True),group('classrooms',100,['25-v01','26-v01'],6,18,columns=2),group('practice',100,['43-v01','31-v01'],4,13,columns=2)]
 cs=[comp('teaching-campus','teaching',['classrooms','practice'])]; rs=[conn('teaching','classrooms'),conn('classrooms','practice')]
elif which=='library':
 gs=[group('library',61,['02-v01','19-v01','29-v01'],3,4,'linear')]; cs=[]; rs=[conn('teaching','library')]
elif which=='living':
 gs=[group('living',71,['37-v02','27-v01'],9,12,columns=3),group('services',71,['28-v01','44-v01','45-v01'],3,29,'linear')]; cs=[comp('living-block','living',['services'])]; rs=[conn('teaching','living'),conn('living','services')]
elif which=='reception':
 gs=[group('reception',3,['23-v01','13-v01','45-v01','28-v01'],4,29,'linear',zone='SOUTH')]; cs=[]; rs=[conn('reception','library')]
args['districtDesign']=dict(groups=gs,core=dict(groupId=gs[0]['groupId'],structureRef=gs[0]['requiredStructureRefs'][0]),arrayCompositions=cs,relations=rs,foundationGroupIds=[g['groupId'] for g in gs])
args['designAnswers']=[dict(groupId=g['groupId'],roadConnected=True,foundation=True,ground='minecraft:gravel',roadSurface='minecraft:stone_brick_slab',styles=['魔法学院新制']) for g in gs]
(p/(out+'.json')).write_text(json.dumps(dict(tool='city_d4_district',args=args),ensure_ascii=False),encoding='utf-8')
