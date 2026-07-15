package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import org.junit.jupiter.api.Test;

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
        assertTrue(first.plan().areas().stream().anyMatch(area -> area.landUseType().equals("residential")));
        assertTrue(first.plan().areas().stream().anyMatch(area -> area.landUseType().equals("commercial")));
        assertTrue(first.plan().areas().stream().allMatch(area -> !area.memberSpans().isEmpty()));
        assertTrue(first.plan().areas().stream().allMatch(area -> !area.boundaryLoops().isEmpty()));
        assertEquals(1, first.plan().corridorExclusions().size());
        assertTrue(first.plan().areas().stream().flatMap(area -> area.gateSlots().stream())
                .anyMatch(gate -> gate.gateId().equals("front")));
        assertNoOverlappingClaims(first.plan());
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
                {"schemaVersion":"city_land_use_intent_plan.v0.1","cityId":"city_test",
                 "groupOverrides":[{"groupId":"override","memberAnchorIds":["house"],"ruleRef":"missing"}],
                 "subjectOverrides":[{"targetType":"group","targetId":"override","mode":"exclude"}]}
                """).getAsJsonObject();
        JsonObject subjectIntent = JsonParser.parseString("""
                {"schemaVersion":"city_land_use_intent_plan.v0.1","cityId":"city_test",
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
        JsonObject templatePlan = new JsonObject();
        JsonObject transformed = new JsonObject();
        JsonArray entrances = new JsonArray();
        JsonObject entrance = new JsonObject();
        entrance.addProperty("entranceId", "front");
        entrance.addProperty("direction", "NORTH");
        JsonObject worldPosition = new JsonObject();
        worldPosition.addProperty("x", 10);
        worldPosition.addProperty("z", 12);
        entrance.add("worldPosition", worldPosition);
        entrances.add(entrance);
        transformed.add("roadEntrances", entrances);
        templatePlan.add("transformed", transformed);
        house.add("templatePlacementPlan", templatePlan);
        structures.add(house);
        structures.add(structure("shop", "market", 26, 30, 12, 16, "commercial"));
        d6.add("plannedWorldgenStructures", structures);
        return d6;
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
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        for (int z = 0; z < 10; z++) {
            for (int x = 0; x < 10; x++) {
                cells.add(new LandUseTerrainField.Cell(x, z, x * 4, z * 4, 4,
                        70, 0.5, 0.5, 0.25, false, 0, 40,
                        "minecraft:plains", "plain", "p", true));
            }
        }
        return new LandUseTerrainField(LandUseTerrainField.CURRENT_SCHEMA_VERSION, "city_test",
                new BlockBounds(0, 0, 39, 39), 4, cells);
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
}
