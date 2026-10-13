import json,pathlib,copy,sys
r=pathlib.Path('runtime/java-d4-20261011');b=pathlib.Path('run/saves/campus_java_acceptance_20261010/realm_debug/provider_3d25631f_r19200/city_test_runs/city_jingchao_capital/steps/blueprint');w=json.loads((b/'city_d4_workflow.json').read_text(encoding='utf-8'))
id=sys.argv[1];body=w['bodies'][id];g=body['groups'][0];new=copy.deepcopy(g)
if id=='reception':
 g['requiredStructureRefs']=['studio:mg-23-v01','studio:mg-13-v01'];g['structureCount']=2
 new.update(groupId='arrival_services',role='Visitor registration and market',priority='STANDARD',structureCount=2,requiredStructureRefs=['studio:mg-14-v01','studio:mg-06-v01'],preferredPatchZone='NORTH',extentClass='MEDIUM')
 new['placementRelation']={'kind':'BETWEEN_GROUPS','patchRefs':[],'groupRefs':['reception','library']}
 assessment='当前总览西侧接待实际仅两栋，新增两栋原槽位向东挤入教学区已跳过。保留港务和办事主体，登记与零售作为同区独立服务子阵列，安排在接待与北侧藏书之间；保留全部计划用途，不减量。'
else:
 g['requiredStructureRefs']=['studio:mg-01-v01','studio:mg-04-v01','studio:mg-16-v01'];g['structureCount']=3
 new.update(groupId='botanical_court',role='Botanical teaching and garden court',priority='STANDARD',structureCount=3,requiredStructureRefs=['studio:mg-21-v01','studio:mg-12-v01','studio:mg-10-v01'],preferredPatchZone='SOUTH',extentClass='MEDIUM')
 assessment='当前核心与南侧研究配套保留，东侧生活成片；植物教学原槽位在核心东侧与生活冲突。把植物教学与花草庭园、星泉组成南侧独立教学子阵列，沿原教学主体向南展开，维持其他功能区。'
body['groups'].append(new);body['foundationGroupIds'].append(new['groupId'])
body['relations'].append({'fromGroupId':new['groupId'],'toGroupId':id,'relationKind':'CONNECTION','strength':'SOFT','distancePreference':'NONE','directionPreference':'NONE'})
answers=w['districtAnswers'][id];answer=copy.deepcopy(answers[0]);answer['groupId']=new['groupId'];answers.append(answer)
a=dict(runId='provider_3d25631f_r19200',citySeedId='city_jingchao_capital',contextId=w['contextId'],workflowRevision=w['revision'],baseDraftHash=w['activeDraftHash'],targetDistrictId=id,districtDesign=body,designAnswers=answers,assessment=assessment)
(r/sys.argv[2]).write_text(json.dumps({'tool':'city_d4_district','args':a},ensure_ascii=False),encoding='utf-8')
