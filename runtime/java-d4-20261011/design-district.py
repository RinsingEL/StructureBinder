import json,pathlib,sys
root=pathlib.Path('E:/Mod_Dev/StructureBinder')
base=root/'run/saves/campus_java_acceptance_20261010/realm_debug/provider_3d25631f_r19200/city_test_runs/city_jingchao_capital/steps/blueprint'
w=json.loads((base/'city_d4_workflow.json').read_text(encoding='utf-8-sig'))
id=sys.argv[1]; out=sys.argv[2]
b=w['bodies'][id];g=b['groups'][0]
refs={'teaching':[1,4,21,16], 'library':[2,19,3], 'reception':[23,13,14,6], 'living':[7,5,17,18,20,15]}
g['requiredStructureRefs']=['studio:mg-%02d-v01'%i for i in refs[id]]
g['structureCount']=len(refs[id]);g['extentClass']='LARGE';g['terrainPolicy']='BALANCED'
b['foundationGroupIds']=[id]
answers=w['districtAnswers'][id]
for a in answers:a['foundation']=True;a.pop('noFoundationReason',None)
if id=='teaching':
 b['publicRealm']={'schema':'city_public_realm.v1','catalog':'academy','seed':20261011,'spaces':[],
 'greenery':{'pools':{'trees':'courtyard_trees','shrubs':'low_shrubs','flowers':'flower_patches','hedges':'hedge_segments','planters':'planters'},'spacing':{'trees':13,'shrubs':5,'flowers':4,'hedges':8,'planters':24},'slotLimits':{'trees':80,'shrubs':95,'flowers':220,'hedges':65,'planters':40},'maxPlacements':500,'layeredPlanting':True},
 'fringe':{'width':20,'pools':{'trees':'outer_trees','shrubs':'low_shrubs','flowers':'flower_patches'},'spacing':{'trees':10,'shrubs':6,'flowers':4},'slotLimits':{'trees':150,'shrubs':120,'flowers':230},'maxPlacements':500,'layeredPlanting':True}}
a=dict(runId='provider_3d25631f_r19200',citySeedId='city_jingchao_capital',contextId=w['contextId'],workflowRevision=w['revision'],baseDraftHash=w['activeDraftHash'],targetDistrictId=id,districtDesign=b,designAnswers=answers,assessment=sys.argv[3])
(root/'runtime/java-d4-20261011'/out).write_text(json.dumps({'tool':'city_d4_district','args':a},ensure_ascii=False,indent=2),encoding='utf-8')
