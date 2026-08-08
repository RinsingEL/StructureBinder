package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.algorithm.landuse.LandUseAutoConnectionPlanner;
import com.rinsing.geomantia.systems.city.algorithm.landuse.CityFoundationPlanner;
import com.rinsing.geomantia.systems.city.algorithm.landuse.LandUseExpansionResult;
import com.rinsing.geomantia.systems.city.algorithm.landuse.LandUseGeometryCompiler;
import com.rinsing.geomantia.systems.city.algorithm.landuse.StableLandUseExpander;
import com.rinsing.geomantia.systems.city.application.outdoor.CityUrbanResidualResolver;
import com.rinsing.geomantia.systems.city.application.outdoor.CityUrbanSpacePlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandscapeFillProgram;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRuleCatalog;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        String cityId = structureMaterializationPlan.get("cityId").getAsString();
        return plan(cityId, sources, d5ReservationMaskPlan, terrainField,
                CityUrbanResidualResolver.Config.disabled());
    }

    public Result plan(String cityId,
                       LandUseSourceResolver.Resolution sources,
                       JsonObject d5ReservationMaskPlan,
                       LandUseTerrainField terrainField,
                       CityUrbanResidualResolver.Config residualConfig) {
        if (cityId == null || cityId.isBlank()) throw new IllegalArgumentException("cityId is required");
        if (sources == null) throw new IllegalArgumentException("LandUse sources are required");
        if (terrainField == null) throw new IllegalArgumentException("LandUse terrain field is required");
        if (!cityId.equals(terrainField.cityId())) {
            throw new IllegalArgumentException("LAND_USE_TERRAIN_CITY_ID_MISMATCH");
        }
        StableLandUseExpander expander = new StableLandUseExpander();
        List<LandUseSeedGroup> foundations = sources.seedGroups().stream()
                .filter(group -> group.layerRole() == LandUseSeedGroup.LayerRole.FOUNDATION).toList();
        boolean layered = !foundations.isEmpty();
        List<LandUseAreaPlan.CorridorExclusion> corridors;
        LandUseExpansionResult probe;
        LandUseExpansionResult expansion;
        List<LandUseAutoConnectionPlanner.ConnectionOutcome> connectionOutcomes;
        CityUrbanResidualResolver.Result residualResult;
        int resolvedFoundationCloseRadius = 0;
        Set<String> skippedOptionalLandscapes = Set.of();
        if (layered) {
            if (foundations.size() != 1) {
                throw new IllegalArgumentException("CITY_FOUNDATION_GROUP_COUNT_INVALID:" + foundations.size());
            }
            if (sources.seedGroups().stream().anyMatch(group -> group.layerRole()
                    == LandUseSeedGroup.LayerRole.STANDARD)) {
                throw new IllegalArgumentException("CITY_LAYERED_LAND_USE_ROLE_INVALID:STANDARD");
            }
            corridors = List.of();
            LandUseSeedGroup foundation = foundations.get(0);
            CityFoundationPlanner.Plan foundationPlan = new CityFoundationPlanner().plan(
                    terrainField.planningBounds(), terrainField, foundation.structureFootprints(),
                    foundation.foundationSettings());
            List<LandUseSeedGroup> landscapes = sources.seedGroups().stream()
                    .filter(group -> group.layerRole() == LandUseSeedGroup.LayerRole.LANDSCAPE).toList();
            LandscapeExpansion landscapeResult = expandPrioritizedLandscapes(expander, cityId, terrainField,
                    landscapes, sources.seedSalt());
            LandUseExpansionResult landscapeExpansion = landscapeResult.expansion();
            skippedOptionalLandscapes = landscapeResult.skippedOptionalGroupIds();
            expansion = overlay(foundation, foundationPlan, landscapeExpansion);
            probe = expansion;
            connectionOutcomes = List.of();
            residualResult = new CityUrbanResidualResolver.Result(expansion,
                    CityUrbanSpacePlan.disabled(cityId), List.of());
            resolvedFoundationCloseRadius = foundationPlan.resolvedCloseRadiusBlocks();
        } else {
            LandUseCorridorExclusionResolver corridorResolver = new LandUseCorridorExclusionResolver();
            corridors = corridorResolver.stableMerge(sources.corridorExclusions(),
                    corridorResolver.fromD5ReservationMask(d5ReservationMaskPlan));
            probe = expander.expand(cityId, terrainField.planningBounds(), terrainField, sources.seedGroups(),
                    corridors, sources.seedSalt());
            LandUseAutoConnectionPlanner connectionPlanner = new LandUseAutoConnectionPlanner();
            LandUseAutoConnectionPlanner.Plan connectionPlan = connectionPlanner.plan(sources.seedGroups(), probe);
            expansion = connectionPlan.connections().isEmpty() ? probe : expander.expand(cityId,
                    terrainField.planningBounds(), terrainField, sources.seedGroups(), corridors,
                    sources.seedSalt(), connectionPlan);
            connectionOutcomes = connectionPlanner.evaluate(connectionPlan, sources.seedGroups(), expansion);
            residualResult = new CityUrbanResidualResolver().resolve(cityId, terrainField.planningBounds(),
                    terrainField, sources.seedGroups(), corridors, expansion, residualConfig);
        }
        LandUseExpansionResult resolvedExpansion = residualResult.expansion();
        LandUseGeometryCompiler.CompiledGeometry geometry = new LandUseGeometryCompiler().compile(
                terrainField.planningBounds(), sources.seedGroups(), resolvedExpansion);
        List<String> warnings = new ArrayList<>(sources.warnings());
        warnings.addAll(residualResult.warnings());
        skippedOptionalLandscapes.stream().sorted().forEach(groupId -> warnings.add(
                "CITY_LANDSCAPE_OPTIONAL_SKIPPED_INSUFFICIENT_SPACE:" + groupId));
        connectionOutcomes.stream().filter(value -> value.status().equals("not_reached"))
                .map(LandUseAutoConnectionPlanner.ConnectionOutcome::connection)
                .forEach(connection -> warnings.add("LAND_USE_AUTO_CONNECTION_NOT_REACHED:"
                        + connection.groupA() + ':' + connection.groupB()));
        for (LandUseSeedGroup group : sources.seedGroups()) {
            int claimed = resolvedExpansion.claimedBlocksByGroup().getOrDefault(group.groupId(), 0);
            boolean foundation = group.layerRole() == LandUseSeedGroup.LayerRole.FOUNDATION;
            boolean skippedOptional = skippedOptionalLandscapes.contains(group.groupId());
            if (!foundation && !skippedOptional && claimed < group.minAreaBlocks()) {
                warnings.add("LAND_USE_AREA_BELOW_MIN:" + group.groupId());
            }
            for (LandUseSeedGroup.GrowthRegion region : group.growthRegions()) {
                int regionClaimed = expansion.claimedBlocksByGrowthRegion().getOrDefault(region.regionId(), 0);
                if (!foundation && !skippedOptional && regionClaimed < region.minAreaBlocks()) {
                    warnings.add("LAND_USE_GROWTH_REGION_BELOW_MIN:" + group.groupId() + ':' + region.regionId());
                }
            }
        }
        LandUseAreaPlan rawPlan = new LandUseAreaPlan(LandUseAreaPlan.CURRENT_SCHEMA_VERSION,
                LandUseRuleCatalog.RULE_VERSION, cityId, "", terrainField.planningBounds(), geometry.areas(),
                geometry.unclaimedSpans(), corridors, warnings);
        LandUseAreaPlan plan = new LandUseAreaPlanCodec().withComputedHash(rawPlan);
        CityLandUseSurfacePrintPlan surfacePrintPlan = new CityLandUseSurfacePrintPlanner().plan(
                plan, sources.seedGroups(), terrainField);
        return new Result(plan, trace(sources, probe, resolvedExpansion, connectionOutcomes,
                residualResult.urbanSpacePlan(), resolvedFoundationCloseRadius, skippedOptionalLandscapes),
                quality(plan, sources, resolvedExpansion, connectionOutcomes, residualResult.urbanSpacePlan(),
                        skippedOptionalLandscapes),
                surfacePrintPlan,
                residualResult.urbanSpacePlan());
    }

    private static LandscapeExpansion expandPrioritizedLandscapes(StableLandUseExpander expander,
                                                                  String cityId,
                                                                  LandUseTerrainField terrain,
                                                                  List<LandUseSeedGroup> landscapes,
                                                                  String seedSalt) {
        List<LandUseSeedGroup> required = landscapes.stream()
                .filter(group -> group.admissionPolicy() == LandUseSeedGroup.AdmissionPolicy.REQUIRED).toList();
        List<LandUseSeedGroup> optional = landscapes.stream()
                .filter(group -> group.admissionPolicy() == LandUseSeedGroup.AdmissionPolicy.OPTIONAL)
                .sorted(Comparator.comparing(LandUseSeedGroup::groupId)).toList();
        LandUseExpansionResult requiredExpansion = expander.expand(cityId, terrain.planningBounds(), terrain,
                required, List.of(), seedSalt);
        for (LandUseSeedGroup group : required) {
            int claimed = requiredExpansion.claimedBlocksByGroup().getOrDefault(group.groupId(), 0);
            int minimumExecutable = minimumExecutableArea(group);
            if (claimed < minimumExecutable) {
                throw new IllegalArgumentException("CITY_LANDSCAPE_CORE_BELOW_MINIMUM:"
                        + group.groupId() + ":claimed=" + claimed + ":required=" + minimumExecutable);
            }
        }

        Map<BlockPoint, LandUseExpansionResult.Claim> claims = new LinkedHashMap<>(requiredExpansion.claims());
        Map<String, Integer> groupCounts = new LinkedHashMap<>(requiredExpansion.claimedBlocksByGroup());
        Map<String, Integer> regionCounts = new LinkedHashMap<>(requiredExpansion.claimedBlocksByGrowthRegion());
        Set<BlockPoint> reserved = new HashSet<>(claims.keySet());
        Set<String> skipped = new LinkedHashSet<>();
        int contested = requiredExpansion.contestedClaimCount();
        int blocked = requiredExpansion.blockedCandidateCount();
        for (LandUseSeedGroup group : optional) {
            LandUseExpansionResult candidate = expander.expand(cityId, terrain.planningBounds(), terrain,
                    List.of(group), List.of(), seedSalt, LandUseAutoConnectionPlanner.Plan.empty(), reserved);
            int claimed = candidate.claimedBlocksByGroup().getOrDefault(group.groupId(), 0);
            if (claimed < minimumExecutableArea(group)) {
                skipped.add(group.groupId());
                continue;
            }
            claims.putAll(candidate.claims());
            groupCounts.putAll(candidate.claimedBlocksByGroup());
            regionCounts.putAll(candidate.claimedBlocksByGrowthRegion());
            reserved.addAll(candidate.claims().keySet());
            contested += candidate.contestedClaimCount();
            blocked += candidate.blockedCandidateCount();
        }
        return new LandscapeExpansion(new LandUseExpansionResult(claims, groupCounts, regionCounts,
                contested, blocked), Set.copyOf(skipped));
    }

    private static int minimumExecutableArea(LandUseSeedGroup group) {
        int stageCount = group.landscapeFillProgram() == null ? 1 : group.landscapeFillProgram().roles().size();
        return Math.max(group.minAreaBlocks(), stageCount);
    }

    private static LandUseExpansionResult overlay(LandUseSeedGroup foundation,
                                                  CityFoundationPlanner.Plan foundationPlan,
                                                  LandUseExpansionResult landscapeExpansion) {
        Map<BlockPoint, LandUseExpansionResult.Claim> claims = new LinkedHashMap<>();
        foundationPlan.claims().stream().sorted(java.util.Comparator.comparingInt(BlockPoint::z)
                        .thenComparingInt(BlockPoint::x))
                .forEach(point -> claims.put(point, new LandUseExpansionResult.Claim(foundation.groupId(), 0.0)));
        claims.putAll(landscapeExpansion.claims());
        Map<String, Integer> groupCounts = new LinkedHashMap<>(landscapeExpansion.claimedBlocksByGroup());
        int visibleFoundationBlocks = (int) claims.values().stream()
                .filter(claim -> claim.groupId().equals(foundation.groupId())).count();
        groupCounts.put(foundation.groupId(), visibleFoundationBlocks);
        Map<String, Integer> regionCounts = new LinkedHashMap<>(
                landscapeExpansion.claimedBlocksByGrowthRegion());
        foundation.growthRegions().forEach(region -> regionCounts.put(region.regionId(), visibleFoundationBlocks));
        return new LandUseExpansionResult(claims, groupCounts, regionCounts,
                landscapeExpansion.contestedClaimCount(), landscapeExpansion.blockedCandidateCount());
    }

    private static JsonObject trace(LandUseSourceResolver.Resolution sources,
                                    LandUseExpansionResult probe,
                                    LandUseExpansionResult expansion,
                                    List<LandUseAutoConnectionPlanner.ConnectionOutcome> connectionOutcomes,
                                    CityUrbanSpacePlan urbanSpacePlan,
                                    int resolvedFoundationCloseRadius,
                                    Set<String> skippedOptionalLandscapes) {
        JsonObject trace = new JsonObject();
        trace.addProperty("schemaVersion", "city_land_use_planning_trace.v0.5");
        trace.addProperty("foundationResolvedCloseRadiusBlocks", resolvedFoundationCloseRadius);
        JsonArray groups = new JsonArray();
        for (LandUseSeedGroup group : sources.seedGroups()) {
            JsonObject value = new JsonObject();
            value.addProperty("groupId", group.groupId());
            value.addProperty("admissionPolicy", group.admissionPolicy().name().toLowerCase());
            value.addProperty("ruleRef", group.rule().ruleRef());
            value.addProperty("surfacePrintEnabled", group.surfaceSettings().surfacePrintEnabled());
            value.addProperty("autoConnect", group.surfaceSettings().autoConnect());
            value.addProperty("surfaceBlockId", group.surfaceSettings().surfaceBlockId());
            value.addProperty("cropBlockId", group.surfaceSettings().cropBlockId());
            value.addProperty("surfaceCompatibilityCategory",
                    group.surfaceSettings().compatibilityCategory());
            value.addProperty("surfaceAlgorithm",
                    group.surfaceSettings().surfaceAlgorithm().name().toLowerCase());
            if (group.surfaceSettings().algorithmAnchor() != null) {
                JsonObject center = new JsonObject();
                center.addProperty("x", group.surfaceSettings().algorithmAnchor().x());
                center.addProperty("z", group.surfaceSettings().algorithmAnchor().z());
                value.add("algorithmAnchor", center);
            }
            value.addProperty("surfaceCompatibilityKey",
                    LandUseAutoConnectionPlanner.surfaceCompatibilityKey(group));
            if (group.landscapeFillProgram() != null) {
                LandscapeFillProgram fill = group.landscapeFillProgram();
                value.addProperty("fillProfileRef", fill.fillProfileRef());
                value.addProperty("fillStableSeed", fill.stableSeed());
                value.addProperty("fillPrimaryRoleRef", fill.primaryRoleRef());
                JsonArray stages = new JsonArray();
                for (LandscapeFillProgram.RoleDefinition role : fill.roles()) {
                    JsonObject roleValue = new JsonObject();
                    roleValue.addProperty("roleRef", role.roleRef());
                    roleValue.addProperty("materialRole", role.materialRole().name());
                    roleValue.addProperty("growthForm", role.growthForm().name());
                    roleValue.addProperty("targetShare", role.targetShare());
                    stages.add(roleValue);
                }
                value.add("fillRelayStages", stages);
                JsonArray contentWeights = new JsonArray();
                for (LandscapeFillProgram.ContentWeight content : fill.contentWeights()) {
                    JsonObject contentValue = new JsonObject();
                    contentValue.addProperty("contentRef", content.contentRef());
                    contentValue.addProperty("weight", content.weight());
                    contentWeights.add(contentValue);
                }
                value.add("fillContentWeights", contentWeights);
            }
            value.addProperty("minAreaBlocks", group.minAreaBlocks());
            value.addProperty("preferredAreaBlocks", group.preferredAreaBlocks());
            value.addProperty("maxAreaBlocks", group.maxAreaBlocks());
            int claimedAreaBlocks = expansion.claimedBlocksByGroup().getOrDefault(group.groupId(), 0);
            value.addProperty("claimedAreaBlocks", claimedAreaBlocks);
            value.addProperty("admissionStatus", skippedOptionalLandscapes.contains(group.groupId())
                    ? "skipped_insufficient_space" : "admitted");
            if (group.layerRole() == LandUseSeedGroup.LayerRole.FOUNDATION) {
                value.addProperty("foundationBaseAreaBlocks", group.preferredAreaBlocks());
                value.addProperty("foundationVisibleAreaBlocks", claimedAreaBlocks);
            }
            value.addProperty("growthRegionCount", group.growthRegions().size());
            JsonArray growthRegions = new JsonArray();
            for (LandUseSeedGroup.GrowthRegion region : group.growthRegions()) {
                JsonObject regionValue = new JsonObject();
                regionValue.addProperty("regionId", region.regionId());
                JsonArray anchorIds = new JsonArray();
                region.anchorIds().forEach(anchorIds::add);
                regionValue.add("anchorIds", anchorIds);
                regionValue.addProperty("minAreaBlocks", region.minAreaBlocks());
                regionValue.addProperty("preferredAreaBlocks", region.preferredAreaBlocks());
                regionValue.addProperty("maxAreaBlocks", region.maxAreaBlocks());
                regionValue.addProperty("claimedAreaBlocks",
                        expansion.claimedBlocksByGrowthRegion().getOrDefault(region.regionId(), 0));
                growthRegions.add(regionValue);
            }
            value.add("growthRegions", growthRegions);
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
        addUrbanSpaceSummary(trace, urbanSpacePlan);
        return trace;
    }

    private static JsonObject quality(LandUseAreaPlan plan,
                                      LandUseSourceResolver.Resolution sources,
                                      LandUseExpansionResult expansion,
                                      List<LandUseAutoConnectionPlanner.ConnectionOutcome> connectionOutcomes,
                                      CityUrbanSpacePlan urbanSpacePlan,
                                      Set<String> skippedOptionalLandscapes) {
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
        quality.addProperty("skippedOptionalLandscapeCount", skippedOptionalLandscapes.size());
        int belowMinimum = 0;
        int belowMinimumRegions = 0;
        JsonArray groupResults = new JsonArray();
        for (LandUseSeedGroup group : sources.seedGroups()) {
            int claimed = expansion.claimedBlocksByGroup().getOrDefault(group.groupId(), 0);
            boolean foundation = group.layerRole() == LandUseSeedGroup.LayerRole.FOUNDATION;
            boolean skippedOptional = skippedOptionalLandscapes.contains(group.groupId());
            boolean below = !foundation && !skippedOptional && claimed < group.minAreaBlocks();
            if (below) belowMinimum++;
            JsonObject groupResult = new JsonObject();
            groupResult.addProperty("groupId", group.groupId());
            groupResult.addProperty("claimedAreaBlocks", claimed);
            groupResult.addProperty("minAreaBlocks", group.minAreaBlocks());
            groupResult.addProperty("preferredAreaBlocks", group.preferredAreaBlocks());
            groupResult.addProperty("maxAreaBlocks", group.maxAreaBlocks());
            groupResult.addProperty("status", foundation ? "covered_by_landscape_overlay"
                    : skippedOptional ? "skipped_insufficient_space" : below ? "below_minimum" : "accepted");
            JsonArray regionResults = new JsonArray();
            for (LandUseSeedGroup.GrowthRegion region : group.growthRegions()) {
                int regionClaimed = expansion.claimedBlocksByGrowthRegion().getOrDefault(region.regionId(), 0);
                boolean regionBelow = !foundation && !skippedOptional && regionClaimed < region.minAreaBlocks();
                if (regionBelow) belowMinimumRegions++;
                JsonObject regionResult = new JsonObject();
                regionResult.addProperty("regionId", region.regionId());
                regionResult.addProperty("claimedAreaBlocks", regionClaimed);
                regionResult.addProperty("minAreaBlocks", region.minAreaBlocks());
                regionResult.addProperty("preferredAreaBlocks", region.preferredAreaBlocks());
                regionResult.addProperty("maxAreaBlocks", region.maxAreaBlocks());
                regionResult.addProperty("status", foundation ? "covered_by_landscape_overlay"
                        : skippedOptional ? "skipped_insufficient_space"
                        : regionBelow ? "below_minimum" : "accepted");
                regionResults.add(regionResult);
            }
            groupResult.add("growthRegionResults", regionResults);
            groupResults.add(groupResult);
        }
        quality.addProperty("belowMinimumGroupCount", belowMinimum);
        quality.addProperty("belowMinimumGrowthRegionCount", belowMinimumRegions);
        quality.add("groupResults", groupResults);
        JsonArray warnings = new JsonArray();
        plan.warnings().forEach(warnings::add);
        quality.add("warnings", warnings);
        addUrbanSpaceSummary(quality, urbanSpacePlan);
        return quality;
    }

    private static void addUrbanSpaceSummary(JsonObject target, CityUrbanSpacePlan plan) {
        CityUrbanSpacePlan.CoverageSummary coverage = plan.coverageSummary();
        target.addProperty("urbanSpaceStatus", plan.enabled() ? "resolved" : "disabled");
        target.addProperty("urbanSpaceEnabled", plan.enabled());
        target.addProperty("urbanEnvelopeBlocks", coverage.envelopeBlocks());
        target.addProperty("urbanAbsorbedResidualBlocks", coverage.absorbedResidualBlocks());
        target.addProperty("urbanExplicitResidualBlocks", coverage.explicitResidualBlocks());
        target.addProperty("urbanUnknownResidualBlocks", coverage.unknownResidualBlocks());
        target.addProperty("urbanResidualRegionCount", plan.residualRegions().size());
        target.addProperty("urbanSpacePlanHash", plan.planHash());
    }

    private static JsonObject point(com.rinsing.geomantia.systems.city.domain.model.BlockPoint point) {
        JsonObject value = new JsonObject();
        value.addProperty("x", point.x());
        value.addProperty("z", point.z());
        return value;
    }

    private record LandscapeExpansion(LandUseExpansionResult expansion,
                                      Set<String> skippedOptionalGroupIds) {
    }

    public record Result(LandUseAreaPlan plan,
                         JsonObject trace,
                         JsonObject quality,
                         CityLandUseSurfacePrintPlan surfacePrintPlan,
                         CityUrbanSpacePlan urbanSpacePlan) {
    }
}
