package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.algorithm.landuse.LandUseExpansionResult;
import com.rinsing.geomantia.systems.city.algorithm.landuse.LandUseGeometryCompiler;
import com.rinsing.geomantia.systems.city.algorithm.landuse.NearbySameTypeBridgePlanner;
import com.rinsing.geomantia.systems.city.algorithm.landuse.StableLandUseExpander;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRuleCatalog;

import java.util.ArrayList;
import java.util.List;

public final class LandUsePlanningService {
    public Result plan(JsonObject structureMaterializationPlan,
                       JsonObject landUseIntentPlan,
                       LandUseTerrainField terrainField) {
        return plan(structureMaterializationPlan, landUseIntentPlan, null, terrainField,
                LandUseRuleCatalog.defaults());
    }

    public Result plan(JsonObject structureMaterializationPlan,
                       JsonObject landUseIntentPlan,
                       JsonObject functionalArrayZones,
                       LandUseTerrainField terrainField,
                       LandUseRuleCatalog ruleCatalog) {
        return plan(structureMaterializationPlan, landUseIntentPlan, functionalArrayZones, null,
                terrainField, ruleCatalog);
    }

    public Result plan(JsonObject structureMaterializationPlan,
                       JsonObject landUseIntentPlan,
                       JsonObject functionalArrayZones,
                       JsonObject d5ReservationMaskPlan,
                       LandUseTerrainField terrainField,
                       LandUseRuleCatalog ruleCatalog) {
        LandUseSourceResolver.Resolution sources = new LandUseSourceResolver().resolve(
                structureMaterializationPlan, landUseIntentPlan, functionalArrayZones, ruleCatalog);
        LandUseCorridorExclusionResolver corridorResolver = new LandUseCorridorExclusionResolver();
        List<LandUseAreaPlan.CorridorExclusion> corridors = corridorResolver.stableMerge(
                sources.corridorExclusions(), corridorResolver.fromD5ReservationMask(d5ReservationMaskPlan));
        String cityId = structureMaterializationPlan.get("cityId").getAsString();
        LandUseExpansionResult expansion = new StableLandUseExpander().expand(cityId,
                terrainField.planningBounds(), terrainField, sources.seedGroups(), corridors,
                sources.seedSalt());
        NearbySameTypeBridgePlanner.Result bridgeResult = new NearbySameTypeBridgePlanner().bridge(
                terrainField.planningBounds(), terrainField, sources.seedGroups(), corridors, expansion);
        expansion = bridgeResult.expansion();
        LandUseGeometryCompiler.CompiledGeometry geometry = new LandUseGeometryCompiler().compile(
                terrainField.planningBounds(), sources.seedGroups(), expansion);
        List<String> warnings = new ArrayList<>(sources.warnings());
        for (LandUseSeedGroup group : sources.seedGroups()) {
            int claimed = expansion.claimedBlocksByGroup().getOrDefault(group.groupId(), 0);
            if (claimed < group.minAreaBlocks()) {
                warnings.add("LAND_USE_AREA_BELOW_MIN:" + group.groupId());
            }
        }
        LandUseAreaPlan rawPlan = new LandUseAreaPlan(LandUseAreaPlan.CURRENT_SCHEMA_VERSION,
                LandUseRuleCatalog.RULE_VERSION, cityId, "", terrainField.planningBounds(), geometry.areas(),
                geometry.unclaimedSpans(), corridors, warnings);
        LandUseAreaPlan plan = new LandUseAreaPlanCodec().withComputedHash(rawPlan);
        return new Result(plan, trace(sources, expansion, bridgeResult.bridges()), quality(plan, sources, expansion));
    }

    private static JsonObject trace(LandUseSourceResolver.Resolution sources,
                                    LandUseExpansionResult expansion,
                                    List<NearbySameTypeBridgePlanner.Bridge> bridges) {
        JsonObject trace = new JsonObject();
        trace.addProperty("schemaVersion", "city_land_use_planning_trace.v0.1");
        JsonArray groups = new JsonArray();
        for (LandUseSeedGroup group : sources.seedGroups()) {
            JsonObject value = new JsonObject();
            value.addProperty("groupId", group.groupId());
            value.addProperty("ruleRef", group.rule().ruleRef());
            value.addProperty("minAreaBlocks", group.minAreaBlocks());
            value.addProperty("preferredAreaBlocks", group.preferredAreaBlocks());
            value.addProperty("maxAreaBlocks", group.maxAreaBlocks());
            value.addProperty("claimedAreaBlocks", expansion.claimedBlocksByGroup().getOrDefault(group.groupId(), 0));
            groups.add(value);
        }
        trace.add("seedGroups", groups);
        JsonArray bridgeValues = new JsonArray();
        for (NearbySameTypeBridgePlanner.Bridge bridge : bridges) {
            JsonObject value = new JsonObject();
            value.addProperty("ruleRef", bridge.ruleRef());
            JsonArray sourceGroupIds = new JsonArray();
            bridge.sourceGroupIds().forEach(sourceGroupIds::add);
            value.add("sourceGroupIds", sourceGroupIds);
            value.addProperty("bridgeBlockCount", bridge.bridgeBlockCount());
            bridgeValues.add(value);
        }
        trace.add("nearbySameTypeBridges", bridgeValues);
        trace.addProperty("contestedClaimCount", expansion.contestedClaimCount());
        trace.addProperty("blockedCandidateCount", expansion.blockedCandidateCount());
        return trace;
    }

    private static JsonObject quality(LandUseAreaPlan plan,
                                      LandUseSourceResolver.Resolution sources,
                                      LandUseExpansionResult expansion) {
        JsonObject quality = new JsonObject();
        quality.addProperty("schemaVersion", "city_land_use_quality.v0.1");
        quality.addProperty("status", plan.warnings().isEmpty() ? "pass" : "warning");
        quality.addProperty("seedGroupCount", sources.seedGroups().size());
        quality.addProperty("areaCount", plan.areas().size());
        quality.addProperty("corridorExclusionCount", plan.corridorExclusions().size());
        quality.addProperty("claimedBlockCount", expansion.claims().size());
        int belowMinimum = 0;
        JsonArray groupResults = new JsonArray();
        for (LandUseSeedGroup group : sources.seedGroups()) {
            int claimed = expansion.claimedBlocksByGroup().getOrDefault(group.groupId(), 0);
            boolean below = claimed < group.minAreaBlocks();
            if (below) belowMinimum++;
            JsonObject groupResult = new JsonObject();
            groupResult.addProperty("groupId", group.groupId());
            groupResult.addProperty("claimedAreaBlocks", claimed);
            groupResult.addProperty("minAreaBlocks", group.minAreaBlocks());
            groupResult.addProperty("preferredAreaBlocks", group.preferredAreaBlocks());
            groupResult.addProperty("maxAreaBlocks", group.maxAreaBlocks());
            groupResult.addProperty("status", below ? "below_minimum" : "accepted");
            groupResults.add(groupResult);
        }
        quality.addProperty("belowMinimumGroupCount", belowMinimum);
        quality.add("groupResults", groupResults);
        JsonArray warnings = new JsonArray();
        plan.warnings().forEach(warnings::add);
        quality.add("warnings", warnings);
        return quality;
    }

    public record Result(LandUseAreaPlan plan, JsonObject trace, JsonObject quality) {
    }
}
