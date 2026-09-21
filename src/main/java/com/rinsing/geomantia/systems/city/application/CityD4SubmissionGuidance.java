package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import java.util.*;
import java.util.regex.*;

/** Explains existing validator rules in the active district's input coordinates. */
final class CityD4SubmissionGuidance {
    private CityD4SubmissionGuidance() { }
    static String acceptanceInstruction() {
        return "格式、参数、引用错误需修正；正常地形与碰撞逐栋跳过。本区有有效落位即自动推进，只有整区全空允许重做初版。"
                + "qualityFullySatisfied、allFunctionAreasFormed、allRequiredContentPresent、structureGraphConnected 等均为诊断，不要求清零警告或补齐建筑。"
                + "整城只通过调整阵列/嵌套或向外阵列形成整体性，允许隔河，不要求接触或固定距离。挤占不得清空或破坏其他区功能主体。";
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
        result.addProperty("coreRule",occupiedElsewhere
                ? "全城唯一 CORE 已被其他区占用。当前新增区/整体修饰的组使用 STANDARD 或 PERIPHERAL；本区的构图中心仍可用 centerGroupId 表达，不需要 CORE。"
                : "CORE 是全城唯一的优先级，不是每区的构图中心。当前区与已保存其他区合并后必须恰好一个 CORE；嵌套中心使用 centerGroupId，与 CORE 无关。");
        JsonArray rules=new JsonArray();
        for(String rule:List.of(
                "每个建筑组 requiredStructureRefs 至少一个；fillPools 不能替代必需结构声明。",
                "ADJACENCY/CONNECTION/HIERARCHY 等非 DISTANCE 关系：distancePreference=NONE；只有 DIRECTION 关系填写非 NONE 的 directionPreference。靠近用 ADJACENCY，不要为它填 NEAR。",
                "foundationGroupIds 只列需要共同台地的本区建筑组 ID。普通村庄默认直接兼容地形落地，不逐栋垫台；地表整理与绿化无需加入台地名单。合理的共同台地与高差仍可保留，总览不填。",
                "核心结构必须按阵列同时安排配套，允许复用素材自带的完整院落装饰；不得为完整素材重复加一圈。使用角色标记与实际尺寸选择小型配套，COMPACT 可全部使用小模板。总览若仅留下孤立核心，需调整选材或阵列后重新审查。",
                "嵌套成员不能同时有独立 placementRelation；保留嵌套时删除该成员的 placementRelation。不要为了修参数删掉建筑组或换算法。",
                "ATTACHED 景观：提供 owner.groupId，instanceCount=1，省略 placementDomain；多块景观用所选 profile 允许范围内的 parcelCount。",
                "提交前核对上述参数规则；地形与碰撞由程序处理；有效初版自动推进，不局部重试。")) rules.add(rule);
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
                    if(!active) issue.addProperty("repairAction","这是已保存区的错误，保留该区并报告宿主；不要重做有效初版。");
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
