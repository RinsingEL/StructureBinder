package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import java.util.*;

/** Frozen design requirements for later realization; a planar preview is never proof of construction. */
final class CityD4GenerationHandoff {
    private CityD4GenerationHandoff() { }
    static JsonObject describe(JsonObject draft) {
        JsonObject blueprint=object(draft,"previousBlueprint"),layout=object(draft,"compiledLayout");
        JsonObject result=new JsonObject();result.addProperty("schema","city_d4_generation_handoff.v1");
        result.addProperty("baseDraftHash",text(draft,"baseDraftHash"));result.addProperty("contextId",text(draft,"contextId"));
        result.add("districtDesigns",array(blueprint,"districtDesigns").deepCopy());
        JsonArray required=new JsonArray(),missing=new JsonArray(),adaptations=new JsonArray();
        for(var e:array(blueprint,"groups")) {
            JsonObject group=e.getAsJsonObject();String groupId=text(group,"groupId");
            for(var ref:array(group,"requiredStructureRefs")) {
                JsonObject item=new JsonObject();item.addProperty("groupId",groupId);item.add("structureRef",ref.deepCopy());
                boolean retained=array(layout,"anchors").asList().stream().anyMatch(a->{
                    JsonObject anchor=a.getAsJsonObject();return groupId.equals(text(anchor,"placementGroupId"))&&ref.getAsString().equals(text(anchor,"blueprintStructureRef"));
                });
                boolean core=array(blueprint,"districtDesigns").asList().stream().anyMatch(d->{JsonObject role=d.getAsJsonObject();return groupId.equals(text(role,"coreGroupId"))&&ref.getAsString().equals(text(role,"coreStructureRef"));});
                item.addProperty("designRole",core?"CORE":"REQUIRED_SUPPORT");item.addProperty("retainedInLayout",retained);required.add(item);
                if(!retained)missing.add(item.deepCopy());
            }
        }
        for(var e:array(object(object(draft,"compiledResult"),"compileTrace"),"selections")) {
            JsonObject selection=e.getAsJsonObject(),terrain=object(selection,"terrainGateEvaluation");
            if(terrain.has("terrainAdaptationRequired")&&terrain.get("terrainAdaptationRequired").getAsBoolean()) {
                JsonObject item=new JsonObject();item.addProperty("groupId",text(selection,"groupId"));item.addProperty("structureRef",text(selection,"structureRef"));
                item.add("terrainEvidence",terrain.deepCopy());adaptations.add(item);
            }
        }
        result.add("requiredStructures",required);result.add("missingRequiredStructures",missing);
        result.add("terrainRequirements",adaptations);result.add("skippedMembers",array(layout,"skippedMembers").deepCopy());
        result.addProperty("terrainResolutionRequired",adaptations.size()>0);
        result.addProperty("requiredContentMissing",missing.size()>0);
        result.addProperty("instruction","D4 只冻结平面设计。后续生成保留每区核心与必需配套，解决 terrainRequirements 中的高差/水域适配，核对 missingRequiredStructures；不得把跳过内容或预览当作已施工。精确台地、支撑、跨水施工和真实游玩验收另行完成。");
        return result;
    }
    private static JsonObject object(JsonObject o,String k){return o.has(k)&&o.get(k).isJsonObject()?o.getAsJsonObject(k):new JsonObject();}
    private static JsonArray array(JsonObject o,String k){return o.has(k)&&o.get(k).isJsonArray()?o.getAsJsonArray(k):new JsonArray();}
    private static String text(JsonObject o,String k){return o.has(k)&&o.get(k).isJsonPrimitive()?o.get(k).getAsString():"";}
}
