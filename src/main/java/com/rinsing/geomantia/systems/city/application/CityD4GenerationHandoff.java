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
                if(!retained) {
                    JsonObject gap = item.deepCopy();
                    JsonArray districts = new JsonArray(), evidence = new JsonArray();
                    for (var d : array(blueprint, "districtDesigns")) {
                        JsonObject district = d.getAsJsonObject();
                        if (array(district, "groupIds").asList().stream().anyMatch(id -> groupId.equals(id.getAsString())))
                            districts.add(text(district, "districtId"));
                    }
                    for (var s : array(layout, "skippedMembers")) {
                        JsonObject skipped = s.getAsJsonObject();
                        if (!groupId.equals(text(skipped, "groupId")) || !ref.getAsString().equals(text(skipped, "structureRef"))) continue;
                        JsonObject attempt = skipped.deepCopy();
                        JsonArray overlaps = new JsonArray();
                        JsonObject planned = object(skipped, "plannedBounds");
                        if (hasBounds(planned)) for (var a : array(layout, "anchors")) {
                            JsonObject anchor = a.getAsJsonObject(), occupied = object(anchor, "collisionEnvelope");
                            if (hasBounds(occupied) && CityStructureCandidateEnvelope.bounds(planned)
                                    .overlaps(CityStructureCandidateEnvelope.bounds(occupied))) {
                                JsonObject collision = new JsonObject();
                                for (String key : List.of("anchorId", "placementGroupId", "blueprintStructureRef", "collisionEnvelope"))
                                    if (anchor.has(key)) collision.add(key, anchor.get(key).deepCopy());
                                overlaps.add(collision);
                            }
                        }
                        // Geometric evidence at the nominal slot, not a claim about every attempted origin.
                        attempt.add("plannedBoundsOverlaps", overlaps);
                        evidence.add(attempt);
                    }
                    gap.add("districtIds", districts);
                    gap.add("placementEvidence", evidence);
                    missing.add(gap);
                }
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
    private static boolean hasBounds(JsonObject o) { return List.of("minX", "minZ", "maxX", "maxZ").stream().allMatch(o::has); }
    private static JsonObject object(JsonObject o,String k){return o.has(k)&&o.get(k).isJsonObject()?o.getAsJsonObject(k):new JsonObject();}
    private static JsonArray array(JsonObject o,String k){return o.has(k)&&o.get(k).isJsonArray()?o.getAsJsonArray(k):new JsonArray();}
    private static String text(JsonObject o,String k){return o.has(k)&&o.get(k).isJsonPrimitive()?o.get(k).getAsString():"";}
}
