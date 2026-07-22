package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.algorithm.landuse.LandUseAutoConnectionPlanner;
import com.rinsing.geomantia.systems.city.algorithm.landuse.LandUseExpansionResult;
import com.rinsing.geomantia.systems.city.algorithm.landuse.LandUseGeometryCompiler;
import com.rinsing.geomantia.systems.city.algorithm.landuse.StableLandUseExpander;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRuleCatalog;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationContentCatalog;

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
        return plan(structureMaterializationPlan, landUseIntentPlan, functionalArrayZones,
                d5ReservationMaskPlan, terrainField, ruleCatalog, null);
    }

    public Result plan(JsonObject structureMaterializationPlan,
                       JsonObject landUseIntentPlan,
                       JsonObject functionalArrayZones,
                       JsonObject d5ReservationMaskPlan,
                       LandUseTerrainField terrainField,
                       LandUseRuleCatalog ruleCatalog,
                       CityDecorationContentCatalog decorationCatalog) {
        LandUseSourceResolver.Resolution sources = new LandUseSourceResolver().resolve(
                structureMaterializationPlan, landUseIntentPlan, functionalArrayZones, ruleCatalog);
        LandUseCorridorExclusionResolver corridorResolver = new LandUseCorridorExclusionResolver();
        List<LandUseAreaPlan.CorridorExclusion> corridors = corridorResolver.stableMerge(
                sources.corridorExclusions(), corridorResolver.fromD5ReservationMask(d5ReservationMaskPlan));
        String cityId = structureMaterializationPlan.get("cityId").getAsString();
        StableLandUseExpander expander = new StableLandUseExpander();
        LandUseExpansionResult probe = expander.expand(cityId,
                terrainField.planningBounds(), terrainField, sources.seedGroups(), corridors,
                sources.seedSalt());
        LandUseAutoConnectionPlanner connectionPlanner = new LandUseAutoConnectionPlanner();
        LandUseAutoConnectionPlanner.Plan connectionPlan = connectionPlanner.plan(sources.seedGroups(), probe);
        LandUseExpansionResult expansion = connectionPlan.connections().isEmpty() ? probe : expander.expand(cityId,
                terrainField.planningBounds(), terrainField, sources.seedGroups(), corridors,
                sources.seedSalt(), connectionPlan);
        List<LandUseAutoConnectionPlanner.ConnectionOutcome> connectionOutcomes = connectionPlanner.evaluate(
                connectionPlan, sources.seedGroups(), expansion);
        LandUseGeometryCompiler.CompiledGeometry geometry = new LandUseGeometryCompiler().compile(
                terrainField.planningBounds(), sources.seedGroups(), expansion);
        List<String> warnings = new ArrayList<>(sources.warnings());
        connectionOutcomes.stream().filter(value -> value.status().equals("not_reached"))
                .map(LandUseAutoConnectionPlanner.ConnectionOutcome::connection)
                .forEach(connection -> warnings.add("LAND_USE_AUTO_CONNECTION_NOT_REACHED:"
                        + connection.groupA() + ':' + connection.groupB()));
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
        CityLandUseSurfacePrintPlan surfacePrintPlan = new CityLandUseSurfacePrintPlanner().plan(
                plan, sources.seedGroups(), terrainField, decorationCatalog);
        return new Result(plan, trace(sources, probe, expansion, connectionOutcomes),
                quality(plan, sources, expansion, connectionOutcomes), surfacePrintPlan);
    }

    private static JsonObject trace(LandUseSourceResolver.Resolution sources,
                                    LandUseExpansionResult probe,
                                    LandUseExpansionResult expansion,
                                    List<LandUseAutoConnectionPlanner.ConnectionOutcome> connectionOutcomes) {
        JsonObject trace = new JsonObject();
        trace.addProperty("schemaVersion", "city_land_use_planning_trace.v0.3");
        JsonArray groups = new JsonArray();
        for (LandUseSeedGroup group : sources.seedGroups()) {
            JsonObject value = new JsonObject();
            value.addProperty("groupId", group.groupId());
            value.addProperty("ruleRef", group.rule().ruleRef());
            value.addProperty("surfacePrintEnabled", group.surfaceSettings().surfacePrintEnabled());
            value.addProperty("autoConnect", group.surfaceSettings().autoConnect());
            value.addProperty("surfaceBlockId", group.surfaceSettings().surfaceBlockId());
            value.addProperty("cropBlockId", group.surfaceSettings().cropBlockId());
            value.addProperty("surfaceCompatibilityCategory",
                    group.surfaceSettings().compatibilityCategory());
            value.addProperty("directionMode",
                    group.surfaceSettings().directionMode().name().toLowerCase());
            if (group.surfaceSettings().directionCenter() != null) {
                JsonObject center = new JsonObject();
                center.addProperty("x", group.surfaceSettings().directionCenter().x());
                center.addProperty("z", group.surfaceSettings().directionCenter().z());
                value.add("directionCenter", center);
            }
            value.addProperty("surfaceCompatibilityKey",
                    LandUseAutoConnectionPlanner.surfaceCompatibilityKey(group));
            value.addProperty("minAreaBlocks", group.minAreaBlocks());
            value.addProperty("preferredAreaBlocks", group.preferredAreaBlocks());
            value.addProperty("maxAreaBlocks", group.maxAreaBlocks());
            value.addProperty("claimedAreaBlocks", expansion.claimedBlocksByGroup().getOrDefault(group.groupId(), 0));
            groups.add(value);
        }
        trace.add("seedGroups", groups);
        JsonArray connectionValues = new JsonArray();
        for (LandUseAutoConnectionPlanner.ConnectionOutcome outcome : connectionOutcomes) {
            LandUseAutoConnectionPlanner.Connection connection = outcome.connection();
            JsonObject value = new JsonObject();
            value.addProperty("connectionId", connection.connectionId());
            value.addProperty("surfaceCompatibilityKey", connection.surfaceCompatibilityKey());
            JsonArray sourceGroupIds = new JsonArray();
            sourceGroupIds.add(connection.groupA());
            sourceGroupIds.add(connection.groupB());
            value.add("sourceGroupIds", sourceGroupIds);
            value.addProperty("initialBoundaryGapBlocks", connection.initialBoundaryGapBlocks());
            value.add("boundaryA", point(connection.boundaryA()));
            value.add("boundaryB", point(connection.boundaryB()));
            value.addProperty("status", outcome.status());
            connectionValues.add(value);
        }
        trace.add("automaticSurfaceConnections", connectionValues);
        trace.addProperty("probeClaimedBlockCount", probe.claims().size());
        trace.addProperty("guidedExpansionApplied", !connectionOutcomes.isEmpty());
        trace.addProperty("contestedClaimCount", expansion.contestedClaimCount());
        trace.addProperty("blockedCandidateCount", expansion.blockedCandidateCount());
        return trace;
    }

    private static JsonObject quality(LandUseAreaPlan plan,
                                      LandUseSourceResolver.Resolution sources,
                                      LandUseExpansionResult expansion,
                                      List<LandUseAutoConnectionPlanner.ConnectionOutcome> connectionOutcomes) {
        JsonObject quality = new JsonObject();
        quality.addProperty("schemaVersion", "city_land_use_quality.v0.1");
        quality.addProperty("status", plan.warnings().isEmpty() ? "pass" : "warning");
        quality.addProperty("seedGroupCount", sources.seedGroups().size());
        quality.addProperty("areaCount", plan.areas().size());
        quality.addProperty("corridorExclusionCount", plan.corridorExclusions().size());
        quality.addProperty("claimedBlockCount", expansion.claims().size());
        quality.addProperty("automaticSurfaceConnectionCount", connectionOutcomes.size());
        quality.addProperty("unreachedAutomaticSurfaceConnectionCount", connectionOutcomes.stream()
                .filter(value -> value.status().equals("not_reached")).count());
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

    private static JsonObject point(com.rinsing.geomantia.systems.city.domain.model.BlockPoint point) {
        JsonObject value = new JsonObject();
        value.addProperty("x", point.x());
        value.addProperty("z", point.z());
        return value;
    }

    public record Result(LandUseAreaPlan plan,
                         JsonObject trace,
                         JsonObject quality,
                         CityLandUseSurfacePrintPlan surfacePrintPlan) {
    }
}
