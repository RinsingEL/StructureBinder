package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import java.util.*;
import java.util.regex.*;

/** Explains existing validator rules in the active district's input coordinates. */
final class CityD4SubmissionGuidance {
    private CityD4SubmissionGuidance() { }
    static String acceptanceInstruction() {
        return "格式、参数、引用错误需修正；设计后看本区和全城预览，可带 targetDistrictId、当前 baseDraftHash 和 assessment 重新提交该区完整 districtDesign。其他区设计与布局保留。"
                + "qualityFullySatisfied、allFunctionAreasFormed、allRequiredContentPresent、structureGraphConnected 等均为诊断，不要求清零警告或补齐建筑。"
                + "整城通过逐区选材与阵列修订形成整体性，允许隔河，不要求接触或固定距离。挤占不得清空或破坏其他区功能主体。";
    }

    static JsonObject describe(JsonObject state) {
        JsonObject result=new JsonObject(); JsonArray owners=new JsonArray();
        String current="";
        JsonArray districts=array(state,"districts"); int index=state.has("districtIndex")?state.get("districtIndex").getAsInt():0;
        if("DISTRICTS".equals(text(state,"stage")) && index<districts.size()) current=text(districts.get(index).getAsJsonObject(),"groupId");
        boolean occupiedElsewhere=false;
        for(var entry:object(state,"bodies").entrySet()) for(var item:array(entry.getValue().getAsJsonObject(),"groups")) {
            JsonObject group=item.getAsJsonObject(); if(!"CORE".equals(text(group,"priority"))) continue;
            JsonObject owner=new JsonObject(); owner.addProperty("districtId",entry.getKey());owner.addProperty("groupId",text(group,"groupId"));owners.add(owner);
            occupiedElsewhere |= !entry.getKey().equals(current);
        }
        result.add("savedCoreOwners",owners);
        result.addProperty("overviewCorrection", "首个有效分区保存前，可用当前 workflowRevision 重交 city_d4_overview 修正全城默认设置，无需 baseDraftHash；保存有效分区后总览锁定，不能借此重做已有初版。roadSurface 使用半砖 slab，roadStair/roadCurb 使用楼梯 stair；局部覆盖不会修正不合规的全城默认值。");
        result.addProperty("coreRule","每区一个显式 core:{groupId,structureRef}。核心必须列入该组 requiredStructureRefs；其他必需配套同列，可选填充用 fillPools。素材特色/通用、旧作者角色和全城 CORE 编译优先级均不决定本区角色。多个同类区可相邻，不强制共同核心或巨型阵列。");
        JsonArray rules=new JsonArray();
        for(String rule:List.of(
                "每个建筑组 requiredStructureRefs 至少一个；fillPools 不能替代必需结构声明。",
                "ADJACENCY/CONNECTION/HIERARCHY 等非 DISTANCE 关系：distancePreference=NONE；只有 DIRECTION 关系填写非 NONE 的 directionPreference。靠近用 ADJACENCY，不要为它填 NEAR。",
                "foundationGroupIds 声明显式共同台地与落位时的地形工程承诺。落位后的非 SPARSE、非 CONFORM、无景观份额的 GRID/COURTYARD/CENTER_SYMMETRIC 建筑组也会整理共同地面及近旁路肩。Compact 村落默认保留簇间自然地面；绿化无需加入台地名单，总览不填。",
                "核心结构必须按阵列同时安排配套，允许复用素材自带的完整院落装饰；不得为完整素材重复加一圈。使用角色标记与实际尺寸选择小型配套，COMPACT 可全部使用小模板。总览若仅留下孤立核心，需调整选材或阵列后重新审查。",
                "嵌套成员不能同时有独立 placementRelation；保留嵌套时删除该成员的 placementRelation。不要为了修参数删掉建筑组或换算法。",
                "ATTACHED 景观：提供 owner.groupId，instanceCount=1，省略 placementDomain；多块景观用所选 profile 允许范围内的 parcelCount。",
                "提交前核对上述参数规则；地形与碰撞由程序处理；初次编译自动推进；已保存区仍可重新选材、调整并再编译。")) rules.add(rule);
        result.add("beforeSubmit",rules);
        result.addProperty("acceptanceInstruction", acceptanceInstruction()); return result;
    }

    static void annotate(JsonObject response,JsonObject candidate,String currentDistrict,String inputRoot) {
        JsonArray issues=array(object(response,"validationReport"),"issues"); if(issues.isEmpty()) return;
        JsonArray mapped=new JsonArray();
        for(var element:issues) {
            JsonObject issue=element.getAsJsonObject().deepCopy(); String path=text(issue,"fieldPath");
            issue.addProperty("canonicalFieldPath",path);
            Matcher match=Pattern.compile("^\\$\\.(?:outdoorPlan\\.)?(groups|arrayCompositions|relations|foundationGroupIds|landscapes)\\[(\\d+)\\](.*)$").matcher(path);
            if(match.matches()) {
                String collection=match.group(1);int offset=Integer.parseInt(match.group(2));
                List<Map.Entry<String,JsonObject>> bodies=new ArrayList<>();
                object(candidate,"bodies").entrySet().forEach(e->bodies.add(Map.entry(e.getKey(),e.getValue().getAsJsonObject())));
                if(candidate.has("integrationDesign")) bodies.add(Map.entry("",object(candidate,"integrationDesign")));
                for(var body:bodies) {
                    int count=array(body.getValue(),collection).size();
                    if(offset>=count) {offset-=count;continue;}
                    boolean active=body.getKey().equals(currentDistrict);
                    issue.addProperty("districtId",body.getKey());
                    issue.addProperty("fieldPath",(active?"$."+inputRoot:"savedDistrict["+body.getKey()+"]")+"."+collection+"["+offset+"]"+match.group(3));
                    if(active && inputRoot.equals("changes")) {
                        JsonElement item=array(body.getValue(),collection).get(offset);
                        if(item.isJsonObject()) {
                            String idKey=collection.equals("groups")?"groupId":collection.equals("arrayCompositions")?"compositionId":collection.equals("landscapes")?"landscapeId":"";
                            if(!idKey.isBlank()) {
                                issue.addProperty("objectId",text(item.getAsJsonObject(),idKey));
                                issue.addProperty("fieldPath","$.changes."+collection+"["+idKey+"="+text(item.getAsJsonObject(),idKey)+"]"+match.group(3));
                            }
                        }
                    }
                    if(!active) issue.addProperty("repairAction","这是已保存区的错误；使用 targetDistrictId 定向修订，保留其他区。");
                    break;
                }
            } else if(path.equals("$.outdoorPlan.foundationGroupIds")) {
                issue.addProperty("fieldPath","$."+inputRoot+".foundationGroupIds");
                issue.addProperty("repairAction","检查 foundationGroupIds 引用存在且不重复的本区建筑组，不要求全覆盖。");
            } else if(text(issue,"reasonCode").equals("CITY_BLUEPRINT_GROUP_PRIORITY_HIGHEST_COUNT_INVALID")) {
                issue.addProperty("fieldPath","$."+inputRoot+".groups[*].priority");
                issue.addProperty("constraintScope","whole_city");
                issue.add("coreOwnership",describe(candidate).get("savedCoreOwners"));
                issue.addProperty("repairAction","全城合并后只能有一个 CORE；若其他已保存区已有 CORE，把本区新增 CORE 改为 STANDARD，保留建筑、算法和嵌套中心。");
            }
            mapped.add(issue);
        }
        response.add("stageValidationIssues",mapped);
        response.addProperty("validationPathInstruction","优先按 stageValidationIssues.fieldPath 修改当前阶段输入；canonicalFieldPath 是宿主合并后的路径，不能直接当成本区数组下标。");
    }
    private static JsonObject object(JsonObject o,String k){return o.has(k)&&o.get(k).isJsonObject()?o.getAsJsonObject(k):new JsonObject();}
    private static JsonArray array(JsonObject o,String k){return o.has(k)&&o.get(k).isJsonArray()?o.getAsJsonArray(k):new JsonArray();}
    private static String text(JsonObject o,String k){return o.has(k)&&o.get(k).isJsonPrimitive()?o.get(k).getAsString():"";}
}
