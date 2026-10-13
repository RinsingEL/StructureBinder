import json,pathlib,copy,sys
r=pathlib.Path('runtime/java-d4-20261011');b=pathlib.Path('run/saves/campus_java_acceptance_20261010/realm_debug/provider_3d25631f_r19200/city_test_runs/city_jingchao_capital/steps/blueprint');w=json.loads((b/'city_d4_workflow.json').read_text(encoding='utf-8'));id=sys.argv[1];body=w['bodies'][id]
if id=='teaching':
 g=body['groups'][0];g['requiredStructureRefs']=['studio:mg-01-v01','studio:mg-04-v01'];g['structureCount']=2
 new=copy.deepcopy(g);new.update(groupId='arcane_workshop',role='Magical instrument workshop',priority='STANDARD',structureCount=1,requiredStructureRefs=['studio:mg-16-v01'],extentClass='SMALL')
 new['placementRelation']={'kind':'BETWEEN_GROUPS','patchRefs':[],'groupRefs':['botanical_court','library']}
 assessment='当前图北部星泉、植物教学和花园三栋全部保留，通过南北通道关联藏书，但通道中段仍较空。把被挤掉的器材制作楼放在植物庭园与藏书之间，提供有用途的中间节点；中央主楼和实验楼保留。'
else:
 g=body['groups'][1];g['requiredStructureRefs']=['studio:mg-14-v01'];g['structureCount']=1
 new=copy.deepcopy(g);new.update(groupId='academy_market',role='Academy supply market',priority='STANDARD',structureCount=1,requiredStructureRefs=['studio:mg-06-v01'],extentClass='SMALL')
 new['placementRelation']={'kind':'BETWEEN_GROUPS','patchRefs':[],'groupRefs':['arrival_services','botanical_court']}
 assessment='当前西侧接待和登记保留，零售槽位仍与港务碰撞。将零售作为同一区的校园供应节点安排在登记楼与北部花园之间，保留全部用途；核对它和工坊之间的实际间隔。'
body['groups'].append(new);body['foundationGroupIds'].append(new['groupId']);body['relations'].append({'fromGroupId':new['groupId'],'toGroupId':id,'relationKind':'CONNECTION','strength':'SOFT','distancePreference':'NONE','directionPreference':'NONE'})
answers=w['districtAnswers'][id];answer=copy.deepcopy(answers[0]);answer['groupId']=new['groupId'];answers.append(answer)
a=dict(runId='provider_3d25631f_r19200',citySeedId='city_jingchao_capital',contextId=w['contextId'],workflowRevision=w['revision'],baseDraftHash=w['activeDraftHash'],targetDistrictId=id,districtDesign=body,designAnswers=answers,assessment=assessment)
(r/sys.argv[2]).write_text(json.dumps({'tool':'city_d4_district','args':a},ensure_ascii=False),encoding='utf-8')
