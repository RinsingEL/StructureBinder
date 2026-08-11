package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.landuse.BoundaryPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.VegetationPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRule;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRuleCatalog;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.TestDecorationCatalogs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandUsePlanningServiceTest {
    @Test
    void lockedD6PlanProducesDeterministicBlockAreasAndTemplateCorridor() {
        JsonObject d6 = d6Plan();
        LandUseTerrainField terrain = terrain();
        LandUsePlanningService service = new LandUsePlanningService();

        LandUsePlanningService.Result first = service.plan(d6, null, terrain);
        LandUsePlanningService.Result second = service.plan(d6, null, terrain);
        JsonObject firstJson = new LandUseAreaPlanCodec().toJson(first.plan());
        JsonObject secondJson = new LandUseAreaPlanCodec().toJson(second.plan());

        assertEquals(firstJson, secondJson);
        assertFalse(first.plan().planHash().isBlank());
        assertEquals(first.plan().planHash(), new LandUseAreaPlanCodec().fromJson(firstJson).planHash());
        assertEquals(first.surfacePrintPlan(), second.surfacePrintPlan());
        assertFalse(first.surfacePrintPlan().planHash().isBlank());
        assertEquals(first.plan().planHash(), first.surfacePrintPlan().sourceLandUsePlanHash());
        assertTrue(first.surfacePrintPlan().areas().stream().anyMatch(area ->
                area.recipe() instanceof CityLandUseSurfacePrintPlan.UniformRecipe));
        assertTrue(first.plan().areas().stream().anyMatch(area -> area.landUseType().equals("residential")));
        assertTrue(first.plan().areas().stream().anyMatch(area -> area.landUseType().equals("commercial")));
        assertTrue(first.plan().areas().stream().allMatch(area -> !area.memberSpans().isEmpty()));
        assertTrue(first.plan().areas().stream().allMatch(area -> !area.boundaryLoops().isEmpty()));
        assertEquals(1, first.plan().corridorExclusions().size());
        assertTrue(first.plan().areas().stream().flatMap(area -> area.gateSlots().stream())
                .anyMatch(gate -> gate.gateId().equals("house::front")));
        assertEquals("disabled", first.trace().get("urbanSpaceStatus").getAsString());
        assertFalse(first.trace().get("urbanSpaceEnabled").getAsBoolean());
        assertEquals(0, first.trace().get("urbanEnvelopeBlocks").getAsInt());
        assertEquals("disabled", first.quality().get("urbanSpaceStatus").getAsString());
        assertEquals(first.urbanSpacePlan().planHash(),
                first.quality().get("urbanSpacePlanHash").getAsString());
        assertNoOverlappingClaims(first.plan());
    }

    @Test
    void repeatedTemplateEntranceIdsAreScopedToTheirAnchor() {
        JsonObject d6 = d6Plan();
        JsonObject secondHouse = structure("house_two", "housing", 32, 35, 12, 16, "residential");
        secondHouse.add("templatePlacementPlan", templatePlan("front", "NORTH", 33, 12));
        d6.getAsJsonArray("plannedWorldgenStructures").add(secondHouse);

        LandUseAreaPlan plan = new LandUsePlanningService().plan(d6, null, terrain()).plan();

        assertTrue(plan.corridorExclusions().stream().anyMatch(value ->
                value.exclusionId().equals("house::front_corridor")));
        assertTrue(plan.corridorExclusions().stream().anyMatch(value ->
                value.exclusionId().equals("house_two::front_corridor")));
        assertTrue(plan.areas().stream().flatMap(area -> area.gateSlots().stream())
                .anyMatch(gate -> gate.gateId().equals("house_two::front")));
    }

    @Test
    void sameRuleGroupsFuseWhenTheirClaimsTouch() {
        JsonObject d6 = new JsonObject();
        d6.addProperty("cityId", "city_test");
        d6.addProperty("locked", true);
        JsonArray structures = new JsonArray();
        structures.add(structure("house_a", "block_a", 10, 14, 10, 14, "residential"));
        structures.add(structure("house_b", "block_b", 19, 23, 10, 14, "residential"));
        d6.add("plannedWorldgenStructures", structures);

        LandUseAreaPlan plan = new LandUsePlanningService().plan(d6, null, terrain()).plan();

        assertEquals(1, plan.areas().size());
        assertEquals(List.of("block_a", "block_b"), plan.areas().get(0).sourceGroupIds());
        assertEquals(2, plan.areas().get(0).structureFootprintExclusions().size());
    }

    @Test
    void automaticSurfaceConnectionsKeepEveryNearbyPair() {
        JsonObject d6 = plan("shop_a", "pave_a", 4, 6, 10, 12, "commercial",
                "shop_b", "pave_b", 28, 30, 10, 12, "commercial",
                "shop_c", "pave_c", 52, 54, 10, 12, "commercial");

        LandUsePlanningService.Result result = new LandUsePlanningService().plan(
                d6, null, null, null, terrain(64), smallCommercialCatalog());

        assertEquals("city_land_use_planning_trace.v0.6", result.trace().get("schemaVersion").getAsString());
        assertEquals(3, result.trace().getAsJsonArray("automaticSurfaceConnections").size());
        assertTrue(result.trace().getAsJsonArray("automaticSurfaceConnections").asList().stream()
                .allMatch(value -> "PAVE".equals(value.getAsJsonObject()
                        .get("surfaceCompatibilityKey").getAsString())));
    }

    @Test
    void groupedBuildingsContributeIndependentGrowthRegionBudgets() {
        JsonObject d6 = plan("shop_a", "market", 4, 6, 10, 12, "commercial",
                "shop_b", "market", 44, 46, 10, 12, "commercial");

        LandUsePlanningService.Result result = new LandUsePlanningService().plan(
                d6, null, null, null, terrain(64), smallCommercialCatalog());

        JsonObject market = traceGroup(result, "market");
        assertEquals(2, market.get("growthRegionCount").getAsInt());
        assertEquals(128, market.get("maxAreaBlocks").getAsInt());
        assertTrue(market.getAsJsonArray("growthRegions").asList().stream()
                .allMatch(value -> value.getAsJsonObject().get("maxAreaBlocks").getAsInt() == 64));
    }

    @Test
    void blockedAutomaticConnectionWarnsWithoutForcingABridge() {
        JsonObject d6 = plan("shop_a", "shop_block_a", 8, 10, 10, 12, "commercial",
                "shop_b", "shop_block_b", 35, 37, 10, 12, "commercial");

        LandUsePlanningService.Result result = new LandUsePlanningService().plan(d6, null, null, null,
                terrainWithBlockedColumn(4), smallCommercialCatalog());

        assertEquals("not_reached", result.trace().getAsJsonArray("automaticSurfaceConnections")
                .get(0).getAsJsonObject().get("status").getAsString());
        assertTrue(result.plan().warnings().contains(
                "LAND_USE_AUTO_CONNECTION_NOT_REACHED:shop_block_a:shop_block_b"));
    }

    @Test
    void rejectsD4PlannedFootprintFallbackWhenD6ActualFootprintMissing() {
        JsonObject d6 = d6Plan();
        JsonObject first = d6.getAsJsonArray("plannedWorldgenStructures").get(0).getAsJsonObject();
        first.remove("lockedActualFootprint");
        first.add("plannedFootprint", bounds(1, 2, 3, 4));

        assertThrows(IllegalArgumentException.class, () ->
                new LandUsePlanningService().plan(d6, null, terrain()));
    }

    @Test
    void unknownSemanticIsWarningAndSkipped() {
        JsonObject d6 = new JsonObject();
        d6.addProperty("cityId", "city_test");
        d6.addProperty("locked", true);
        JsonArray structures = new JsonArray();
        structures.add(structure("mystery", "mystery", 10, 12, 10, 12, "unmapped_xyz"));
        d6.add("plannedWorldgenStructures", structures);

        LandUseAreaPlan plan = new LandUsePlanningService().plan(d6, null, terrain()).plan();

        assertTrue(plan.areas().isEmpty());
        assertTrue(plan.warnings().contains("LAND_USE_SEMANTIC_UNKNOWN_SKIPPED:mystery"));
    }

    @Test
    void unknownRuleRefsFailBeforeExcludeOrGroupingCanBypassValidation() {
        JsonObject groupIntent = JsonParser.parseString("""
                {"schemaVersion":"city_land_use_intent_plan.v0.3","cityId":"city_test",
                 "groupOverrides":[{"groupId":"override","memberAnchorIds":["house"],"ruleRef":"missing"}],
                 "subjectOverrides":[{"targetType":"group","targetId":"override","mode":"exclude"}]}
                """).getAsJsonObject();
        JsonObject subjectIntent = JsonParser.parseString("""
                {"schemaVersion":"city_land_use_intent_plan.v0.3","cityId":"city_test",
                 "subjectOverrides":[{"targetType":"anchor","targetId":"house","mode":"set_rule","ruleRef":"missing"}]}
                """).getAsJsonObject();

        assertThrows(IllegalArgumentException.class, () ->
                new LandUsePlanningService().plan(d6Plan(), groupIntent, terrain()));
        assertThrows(IllegalArgumentException.class, () ->
                new LandUsePlanningService().plan(d6Plan(), subjectIntent, terrain()));
    }

    @Test
    void blockedTerrainReportsBelowMinimumInsteadOfPassing() {
        LandUseTerrainField open = terrain();
        List<LandUseTerrainField.Cell> blockedCells = open.cells().stream().map(cell ->
                new LandUseTerrainField.Cell(cell.cellX(), cell.cellZ(), cell.blockMinX(), cell.blockMinZ(),
                        cell.cellStepBlocks(), cell.elevation(), 50, cell.localRelief(), cell.roughness(),
                        cell.water(), cell.waterDepth(), cell.waterDistance(), cell.biomeId(), cell.landformType(),
                        cell.landformPatchId(), true)).toList();
        LandUseTerrainField blocked = new LandUseTerrainField(open.schemaVersion(), open.cityId(),
                open.planningBounds(), open.cellStepBlocks(), blockedCells);

        LandUsePlanningService.Result result = new LandUsePlanningService().plan(d6Plan(), null, blocked);

        assertTrue(result.plan().warnings().stream().anyMatch(value ->
                value.startsWith("LAND_USE_AREA_BELOW_MIN:")));
        assertEquals("warning", result.quality().get("status").getAsString());
        assertTrue(result.quality().get("belowMinimumGroupCount").getAsInt() > 0);
        assertTrue(result.quality().getAsJsonArray("groupResults").asList().stream()
                .anyMatch(value -> "below_minimum".equals(value.getAsJsonObject().get("status").getAsString())));
    }

    @Test
    void resolvesSurfaceDefaultsIntoTrace(@TempDir Path temp) throws Exception {
        JsonObject d6 = d6Plan();
        d6.getAsJsonArray("plannedWorldgenStructures").add(
                structure("farm", "fields", 14, 18, 24, 28, "agriculture"));
        LandUsePlanningService.Result result = new LandUsePlanningService().plan(
                d6, null, null, null, terrain(), LandUseRuleCatalog.defaults());

        JsonObject residential = traceGroup(result, "housing");
        assertFalse(residential.get("surfacePrintEnabled").getAsBoolean());
        assertFalse(residential.get("autoConnect").getAsBoolean());
        assertEquals("", residential.get("surfaceBlockId").getAsString());
        assertEquals("", residential.get("surfaceCompatibilityCategory").getAsString());
        JsonObject commercial = traceGroup(result, "market");
        assertTrue(commercial.get("surfacePrintEnabled").getAsBoolean());
        assertTrue(commercial.get("autoConnect").getAsBoolean());
        assertEquals("minecraft:stone_bricks", commercial.get("surfaceBlockId").getAsString());
        assertEquals("", commercial.get("cropBlockId").getAsString());
        assertEquals("PAVE", commercial.get("surfaceCompatibilityCategory").getAsString());
        JsonObject agriculture = traceGroup(result, "fields");
        assertTrue(agriculture.get("surfacePrintEnabled").getAsBoolean());
        assertTrue(agriculture.get("autoConnect").getAsBoolean());
        assertEquals("minecraft:farmland", agriculture.get("surfaceBlockId").getAsString());
        assertEquals("minecraft:wheat", agriculture.get("cropBlockId").getAsString());
        assertEquals("CULTIVATE", agriculture.get("surfaceCompatibilityCategory").getAsString());
    }

    @Test
    void runtimeAlgorithmDefaultThenGroupOverrideFreezeResolvedUniformBlocksInTrace() {
        JsonObject d6 = plan("shop_a", "shop_block_a", 8, 10, 10, 12, "commercial",
                "shop_b", "shop_block_b", 25, 27, 10, 12, "commercial");
        JsonObject intent = JsonParser.parseString("""
                {"schemaVersion":"city_land_use_intent_plan.v0.3","cityId":"city_test",
                 "surfaceAlgorithmDefaults":[{
                   "surfaceAlgorithm":"uniform",
                   "surfaceBlockId":"minecraft:sandstone"
                 }],
                 "surfaceOverrides":[{
                   "targetGroupId":"shop_block_a",
                   "surfacePrintEnabled":true,
                   "autoConnect":false,
                   "surfaceAlgorithm":"uniform",
                   "surfaceBlockId":"minecraft:polished_andesite"
                 }]}
                """).getAsJsonObject();

        LandUsePlanningService.Result result = new LandUsePlanningService().plan(
                d6, intent, null, null, terrain(), smallCommercialCatalog());

        assertTrue(result.trace().getAsJsonArray("automaticSurfaceConnections").isEmpty());
        JsonObject overridden = traceGroup(result, "shop_block_a");
        assertFalse(overridden.get("autoConnect").getAsBoolean());
        assertEquals("minecraft:polished_andesite", overridden.get("surfaceBlockId").getAsString());
        assertEquals("uniform", overridden.get("surfaceAlgorithm").getAsString());
        assertEquals("minecraft:sandstone", traceGroup(result, "shop_block_b")
                .get("surfaceBlockId").getAsString());
        CityLandUseSurfacePrintPlan.AreaPrint frozen = result.surfacePrintPlan().areas().stream()
                .filter(area -> area.sourceGroupIds().contains("shop_block_a"))
                .findFirst().orElseThrow();
        assertEquals(com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings.SurfaceAlgorithm.UNIFORM,
                frozen.surfaceAlgorithm());
        assertEquals("minecraft:polished_andesite", frozen.recipe().surfaceBlockId());
    }

    @Test
    void anchorOnlyOverrideInheritsContourAlgorithmAndRejectsUniformAlgorithm() {
        JsonObject agriculturalD6 = plan(
                "farm", "fields", 14, 18, 24, 28, "agriculture");
        JsonObject anchorOnly = JsonParser.parseString("""
                {"schemaVersion":"city_land_use_intent_plan.v0.3","cityId":"city_test",
                 "surfaceOverrides":[{
                   "targetGroupId":"fields",
                   "algorithmAnchor":{"x":16,"z":26}
                 }]}
                """).getAsJsonObject();

        LandUsePlanningService.Result result = new LandUsePlanningService().plan(
                agriculturalD6, anchorOnly, terrain());

        JsonObject trace = traceGroup(result, "fields");
        assertEquals("contour_bands", trace.get("surfaceAlgorithm").getAsString());
        assertEquals(16, trace.getAsJsonObject("algorithmAnchor").get("x").getAsInt());
        assertEquals(new com.rinsing.geomantia.systems.city.domain.model.BlockPoint(16, 26),
                result.surfacePrintPlan().areas().get(0).algorithmAnchor());

        JsonObject commercialD6 = plan(
                "shop", "market", 14, 18, 24, 28, "commercial");
        JsonObject invalidUniformAnchor = JsonParser.parseString("""
                {"schemaVersion":"city_land_use_intent_plan.v0.3","cityId":"city_test",
                 "surfaceOverrides":[{
                   "targetGroupId":"market",
                   "algorithmAnchor":{"x":48,"z":-12}
                 }]}
                """).getAsJsonObject();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new LandUsePlanningService().plan(commercialD6, invalidUniformAnchor, terrain()));
        assertTrue(failure.getMessage().contains(
                "LAND_USE_SURFACE_ALGORITHM_ANCHOR_REQUIRES_CONTOUR_BANDS"));
    }

    @Test
    void enablingPrintOnPreserveRulePromotesItToPaveCompatibility() {
        JsonObject d6 = plan("house_a", "housing_a", 8, 10, 10, 12, "residential",
                "house_b", "housing_b", 25, 27, 10, 12, "residential");
        JsonObject intent = JsonParser.parseString("""
                {"schemaVersion":"city_land_use_intent_plan.v0.3","cityId":"city_test",
                 "surfaceOverrides":[
                   {"targetGroupId":"housing_a","surfacePrintEnabled":true,"autoConnect":true,
                    "surfaceBlockId":"minecraft:cobblestone"},
                   {"targetGroupId":"housing_b","surfacePrintEnabled":true,"autoConnect":true,
                    "surfaceBlockId":"minecraft:stone_bricks"}
                 ]}
                """).getAsJsonObject();

        LandUsePlanningService.Result result = new LandUsePlanningService().plan(d6, intent, terrain());

        assertEquals(1, result.trace().getAsJsonArray("automaticSurfaceConnections").size());
        assertEquals("PAVE", traceGroup(result, "housing_a")
                .get("surfaceCompatibilityCategory").getAsString());
        assertEquals("PAVE", traceGroup(result, "housing_b")
                .get("surfaceCompatibilityCategory").getAsString());
    }

    @Test
    void rejectsUnknownSurfaceOverrideTargetAndEnabledSurfaceWithoutBlock() {
        JsonObject unknown = JsonParser.parseString("""
                {"schemaVersion":"city_land_use_intent_plan.v0.3","cityId":"city_test",
                 "surfaceOverrides":[{"targetGroupId":"missing","autoConnect":false}]}
                """).getAsJsonObject();
        JsonObject missingBlock = JsonParser.parseString("""
                {"schemaVersion":"city_land_use_intent_plan.v0.3","cityId":"city_test",
                 "surfaceOverrides":[{"targetGroupId":"housing","surfacePrintEnabled":true}]}
                """).getAsJsonObject();

        assertThrows(IllegalArgumentException.class, () ->
                new LandUsePlanningService().plan(d6Plan(), unknown, terrain()));
        assertThrows(IllegalArgumentException.class, () ->
                new LandUsePlanningService().plan(d6Plan(), missingBlock, terrain()));
    }

    @Test
    void d5GateCorridorsAreMergedWithoutConsumingOrdinaryMasks() {
        JsonObject d5 = new JsonObject();
        JsonArray gateCorridors = new JsonArray();
        JsonObject gate = new JsonObject();
        gate.addProperty("maskId", "d5_gate_corridor");
        gate.addProperty("maskType", "gate_corridor");
        gate.addProperty("sourceRef", "city_gate");
        gate.add("blockBounds", bounds(18, 8, 18, 20));
        gateCorridors.add(gate);
        d5.add("gateCorridorMask", gateCorridors);
        JsonArray ordinary = new JsonArray();
        JsonObject structureMask = new JsonObject();
        structureMask.addProperty("maskId", "ordinary_structure_mask");
        structureMask.add("blockBounds", bounds(1, 1, 4, 4));
        ordinary.add(structureMask);
        d5.add("noVegetationMask", ordinary);

        LandUseAreaPlan plan = new LandUsePlanningService().plan(d6Plan(), null, null, d5,
                terrain(), com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRuleCatalog.defaults()).plan();

        assertTrue(plan.corridorExclusions().stream().anyMatch(value ->
                value.exclusionId().equals("d5_gate_corridor")));
        assertFalse(plan.corridorExclusions().stream().anyMatch(value ->
                value.exclusionId().equals("ordinary_structure_mask")));
        assertFalse(contains(plan, 18, 10));
    }

    private static JsonObject d6Plan() {
        JsonObject d6 = new JsonObject();
        d6.addProperty("cityId", "city_test");
        d6.addProperty("locked", true);
        JsonArray structures = new JsonArray();
        JsonObject house = structure("house", "housing", 8, 12, 12, 16, "residential");
        house.add("templatePlacementPlan", templatePlan("front", "NORTH", 10, 12));
        structures.add(house);
        structures.add(structure("shop", "market", 26, 30, 12, 16, "commercial"));
        d6.add("plannedWorldgenStructures", structures);
        return d6;
    }

    private static JsonObject plan(Object... values) {
        JsonObject d6 = new JsonObject();
        d6.addProperty("cityId", "city_test");
        d6.addProperty("locked", true);
        JsonArray structures = new JsonArray();
        for (int index = 0; index < values.length; index += 7) {
            structures.add(structure((String) values[index], (String) values[index + 1],
                    (int) values[index + 2], (int) values[index + 3], (int) values[index + 4],
                    (int) values[index + 5], (String) values[index + 6]));
        }
        d6.add("plannedWorldgenStructures", structures);
        return d6;
    }

    private static LandUseRuleCatalog smallCommercialCatalog() {
        return new LandUseRuleCatalog(List.of(rule("commercial")));
    }

    private static LandUseRule rule(String ruleRef) {
        return new LandUseRule(ruleRef, ruleRef, List.of(ruleRef), 6.0, 0, 1, 64,
                100, 1.0, 0, 0, 10, 0, 1.0, true,
                SurfacePolicy.PAVE, VegetationPolicy.PRESERVE, BoundaryPolicy.OPEN, ruleRef);
    }

    private static JsonObject templatePlan(String entranceId, String direction, int x, int z) {
        JsonObject templatePlan = new JsonObject();
        JsonObject transformed = new JsonObject();
        JsonArray entrances = new JsonArray();
        JsonObject entrance = new JsonObject();
        entrance.addProperty("entranceId", entranceId);
        entrance.addProperty("direction", direction);
        JsonObject worldPosition = new JsonObject();
        worldPosition.addProperty("x", x);
        worldPosition.addProperty("z", z);
        entrance.add("worldPosition", worldPosition);
        entrances.add(entrance);
        transformed.add("roadEntrances", entrances);
        templatePlan.add("transformed", transformed);
        return templatePlan;
    }

    private static JsonObject structure(String anchorId, String groupId,
                                        int minX, int maxX, int minZ, int maxZ, String semantic) {
        JsonObject item = new JsonObject();
        item.addProperty("anchorId", anchorId);
        item.addProperty("placementGroupId", groupId);
        JsonObject provenance = new JsonObject();
        provenance.addProperty("slotId", anchorId);
        provenance.addProperty("arrayId", "");
        provenance.addProperty("parentArrayId", "");
        provenance.addProperty("subZoneId", "");
        item.add("placementProvenance", provenance);
        item.add("lockedActualFootprint", bounds(minX, minZ, maxX, maxZ));
        JsonArray terms = new JsonArray();
        terms.add(semantic);
        item.add("semanticTerms", terms);
        return item;
    }

    private static JsonObject bounds(int minX, int minZ, int maxX, int maxZ) {
        JsonObject bounds = new JsonObject();
        bounds.addProperty("minX", minX);
        bounds.addProperty("minZ", minZ);
        bounds.addProperty("maxX", maxX);
        bounds.addProperty("maxZ", maxZ);
        return bounds;
    }

    private static LandUseTerrainField terrain() {
        return terrain(40);
    }

    private static LandUseTerrainField terrain(int blocks) {
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        for (int z = 0; z < blocks / 4; z++) {
            for (int x = 0; x < blocks / 4; x++) {
                cells.add(new LandUseTerrainField.Cell(x, z, x * 4, z * 4, 4,
                        70, 0.5, 0.5, 0.25, false, 0, 40,
                        "minecraft:plains", "plain", "p", true));
            }
        }
        return new LandUseTerrainField(LandUseTerrainField.CURRENT_SCHEMA_VERSION, "city_test",
                new BlockBounds(0, 0, blocks - 1, blocks - 1), 4, cells);
    }

    private static LandUseTerrainField terrainWithBlockedColumn(int blockedCellX) {
        LandUseTerrainField open = terrain();
        List<LandUseTerrainField.Cell> cells = open.cells().stream().map(cell ->
                new LandUseTerrainField.Cell(cell.cellX(), cell.cellZ(), cell.blockMinX(), cell.blockMinZ(),
                        cell.cellStepBlocks(), cell.elevation(), cell.cellX() == blockedCellX ? 50 : cell.slope(),
                        cell.localRelief(), cell.roughness(), cell.water(), cell.waterDepth(), cell.waterDistance(),
                        cell.biomeId(), cell.landformType(), cell.landformPatchId(), cell.sampled())).toList();
        return new LandUseTerrainField(open.schemaVersion(), open.cityId(), open.planningBounds(),
                open.cellStepBlocks(), cells);
    }

    private static void assertNoOverlappingClaims(LandUseAreaPlan plan) {
        java.util.Set<String> claimed = new java.util.HashSet<>();
        for (LandUseAreaPlan.Area area : plan.areas()) {
            for (LandUseAreaPlan.ScanlineSpan span : area.memberSpans()) {
                for (int x = span.minX(); x <= span.maxX(); x++) {
                    assertTrue(claimed.add(x + ":" + span.z()));
                }
            }
        }
    }

    private static boolean contains(LandUseAreaPlan plan, int x, int z) {
        return plan.areas().stream().flatMap(area -> area.memberSpans().stream())
                .anyMatch(span -> span.z() == z && x >= span.minX() && x <= span.maxX());
    }

    private static JsonObject traceGroup(LandUsePlanningService.Result result, String groupId) {
        return result.trace().getAsJsonArray("seedGroups").asList().stream()
                .map(value -> value.getAsJsonObject())
                .filter(value -> groupId.equals(value.get("groupId").getAsString()))
                .findFirst().orElseThrow();
    }

}
