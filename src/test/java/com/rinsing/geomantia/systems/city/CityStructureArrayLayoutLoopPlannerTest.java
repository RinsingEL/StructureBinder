package com.rinsing.geomantia.systems.city;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityLandformReviewBuilder;
import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder;
import com.rinsing.geomantia.systems.city.application.CityStructureArrayLayoutLoopPlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureEnvelopeFacts;
import com.rinsing.geomantia.systems.city.application.CityStructureEnvelopeProfiler;
import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.CitySiteContext;
import com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary;
import com.rinsing.geomantia.systems.city.domain.model.PatchMemberCell;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.landform.PatchFlag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityStructureArrayLayoutLoopPlannerTest {

    @Test
    void loopExecutesFivePlannerTypesAndProducesNonOverlappingAnchors() throws Exception {
        Fixture fixture = fixture();
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        for (String plannerType : new String[]{
                "plaza_ring", "compound_cluster", "guide_line_dual_side", "riverbank_dual_side", "contour_band"}) {
            CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                    fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                    arrayLayoutPlan(), CityStructureEnvelopeFacts.empty(), new JsonObject(), new JsonObject());
            CityStructureArrayLayoutLoopPlanner.ExecuteResult executed = planner.execute(
                    fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                    created.loopState(), layoutItem(fixture.review(), plannerType, plannerType + "_array", 3),
                    CityStructureEnvelopeFacts.empty());

            assertTrue(executed.asJson().get("ok").getAsBoolean());
            assertEquals("loop_state_0001", executed.loopState().get("stateId").getAsString());
            assertEquals(1, executed.functionalArrayZones().getAsJsonArray("arrayZones").size());
            assertEquals(3, executed.loopState().getAsJsonArray("arrayAnchors").size());
            assertNoItemCollisionOverlap(executed.functionalArrayZones().getAsJsonArray("arrayZones")
                    .get(0).getAsJsonObject());
        }
    }

    @Test
    void compoundClusterSupportsShapeRowsColumnsAndSpacing() throws Exception {
        Fixture fixture = fixture();
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        assertCompoundClusterShape(planner, fixture, "grid", 2, 2, 4);
        assertCompoundClusterShape(planner, fixture, "courtyard", 3, 3, 8);
        assertCompoundClusterShape(planner, fixture, "l_shape", 3, 3, 5);
        assertCompoundClusterShape(planner, fixture, "u_shape", 3, 3, 7);
        assertCompoundClusterShape(planner, fixture, "organic_compact", 3, 4, 6);
    }

    @Test
    void loopCarriesOccupiedFieldAcrossIterations() throws Exception {
        Fixture fixture = fixture();
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                arrayLayoutPlan(), CityStructureEnvelopeFacts.empty(), new JsonObject(), new JsonObject());
        CityStructureArrayLayoutLoopPlanner.ExecuteResult first = planner.execute(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                created.loopState(), layoutItem(fixture.review(), "compound_cluster", "residential_01", 4),
                CityStructureEnvelopeFacts.empty());
        CityStructureArrayLayoutLoopPlanner.ExecuteResult second = planner.execute(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                first.loopState(), layoutItem(fixture.review(), "guide_line_dual_side", "residential_02", 4),
                CityStructureEnvelopeFacts.empty());

        assertTrue(second.asJson().get("ok").getAsBoolean());
        assertEquals(2, second.functionalArrayZones().getAsJsonArray("arrayZones").size());
        assertEquals(8, second.loopState().getAsJsonArray("arrayAnchors").size());
        assertNoOccupiedOverlap(second.loopState().getAsJsonArray("occupiedEnvelopes"));
    }

    @Test
    void collisionOverlapRulesAllowDiagnosticMaxObservedOverlap() throws Exception {
        Fixture fixture = fixture();
        CityStructureEnvelopeFacts facts = wideDiagnosticFacts(fixture, "minecraft:desert_pyramid");
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                arrayLayoutPlan(), facts, new JsonObject(), new JsonObject());
        JsonObject item = layoutItem(fixture.review(), "compound_cluster", "close_collision_only", 2);
        item.add("fillPool", JsonParser.parseString("""
                [{"structureId": "minecraft:desert_pyramid", "weight": 1}]
                """).getAsJsonArray());

        CityStructureArrayLayoutLoopPlanner.ExecuteResult executed = planner.execute(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                created.loopState(), item, facts);

        assertTrue(executed.asJson().get("ok").getAsBoolean());
        JsonObject zone = executed.functionalArrayZones().getAsJsonArray("arrayZones").get(0).getAsJsonObject();
        assertNoItemCollisionOverlap(zone);
        assertFalse(zone.has("groupSafetyEnvelope"));
        assertAnyItemDiagnosticMaxObservedOverlap(zone);
    }

    @Test
    void spacingUsesCollisionEnvelopeEvenWhenMaskEnvelopesOverlap() throws Exception {
        Fixture fixture = fixture();
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                arrayLayoutPlan(), CityStructureEnvelopeFacts.empty(), new JsonObject(), new JsonObject());
        JsonObject item = layoutItem(fixture.review(), "compound_cluster", "mask_overlap_spacing", 2);
        item.add("fillPool", JsonParser.parseString("""
                [{"structureId": "minecraft:desert_pyramid", "weight": 1}]
                """).getAsJsonArray());
        item.add("compoundCluster", JsonParser.parseString("""
                {"shape": "grid", "rows": 1, "columns": 2, "spacingBlocks": 40}
                """).getAsJsonObject());

        CityStructureArrayLayoutLoopPlanner.ExecuteResult executed = planner.execute(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                created.loopState(), item, CityStructureEnvelopeFacts.empty());

        assertTrue(executed.asJson().get("ok").getAsBoolean());
        JsonObject zone = executed.functionalArrayZones().getAsJsonArray("arrayZones").get(0).getAsJsonObject();
        assertEquals(2, zone.getAsJsonArray("items").size());
        assertNoItemCollisionOverlap(zone);
        assertAnyItemMaskOverlap(zone);
    }

    @Test
    void roadAccessMarginDoesNotChangeD4ArrayAnchors() throws Exception {
        Fixture fixture = fixture();
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult baseCreated = planner.create(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                arrayLayoutPlan(), CityStructureEnvelopeFacts.empty(), new JsonObject(), new JsonObject());
        JsonObject baseItem = layoutItem(fixture.review(), "compound_cluster", "road_margin_base", 3);
        baseItem.addProperty("roadAccessMarginBlocks", 0);
        CityStructureArrayLayoutLoopPlanner.ExecuteResult baseExecuted = planner.execute(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                baseCreated.loopState(), baseItem, CityStructureEnvelopeFacts.empty());

        CityStructureArrayLayoutLoopPlanner.CreateResult wideCreated = planner.create(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                arrayLayoutPlan(), CityStructureEnvelopeFacts.empty(), new JsonObject(), new JsonObject());
        JsonObject wideItem = layoutItem(fixture.review(), "compound_cluster", "road_margin_base", 3);
        wideItem.addProperty("roadAccessMarginBlocks", 96);
        CityStructureArrayLayoutLoopPlanner.ExecuteResult wideExecuted = planner.execute(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                wideCreated.loopState(), wideItem, CityStructureEnvelopeFacts.empty());

        assertTrue(baseExecuted.asJson().get("ok").getAsBoolean());
        assertTrue(wideExecuted.asJson().get("ok").getAsBoolean());
        assertEquals(baseExecuted.loopState().getAsJsonArray("arrayAnchors").toString(),
                wideExecuted.loopState().getAsJsonArray("arrayAnchors").toString());
    }

    @Test
    void sparseMemberCellsDoNotFallBackToPatchBoundsGrid() throws Exception {
        Fixture fixture = fixture();
        CityLandformReviewPackage sparse = reviewWithMemberCells(fixture.review(),
                List.of(memberCell(fixture.review(), 0, 0)));
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), sparse, fixture.terraSenseSource(),
                arrayLayoutPlan(), CityStructureEnvelopeFacts.empty(), new JsonObject(), new JsonObject());
        JsonObject item = layoutItem(sparse, "compound_cluster", "sparse_members", 2);

        CityStructureArrayLayoutLoopPlanner.ExecuteResult executed = planner.execute(
                fixture.baseDir(), sparse, fixture.terraSenseSource(),
                created.loopState(), item, CityStructureEnvelopeFacts.empty());

        assertFalse(executed.asJson().get("ok").getAsBoolean());
        assertTrue(executed.qualityReport().getAsJsonArray("hardBlocks").toString()
                .contains("D4_ARRAY_LAYOUT_MIN_COUNT_UNSATISFIED"));
        assertEquals(0, executed.loopState().getAsJsonArray("arrayAnchors").size());
    }

    @Test
    void missingMinCountDefaultsToTargetCount() throws Exception {
        Fixture fixture = fixture();
        CityLandformReviewPackage sparse = reviewWithMemberCells(fixture.review(),
                List.of(memberCell(fixture.review(), 0, 0)));
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), sparse, fixture.terraSenseSource(),
                arrayLayoutPlan(), CityStructureEnvelopeFacts.empty(), new JsonObject(), new JsonObject());
        JsonObject item = layoutItemWithoutMinCount(sparse, "default_min_target", 3);

        CityStructureArrayLayoutLoopPlanner.ExecuteResult executed = planner.execute(
                fixture.baseDir(), sparse, fixture.terraSenseSource(),
                created.loopState(), item, CityStructureEnvelopeFacts.empty());

        assertFalse(executed.asJson().get("ok").getAsBoolean());
        assertTrue(executed.qualityReport().getAsJsonArray("hardBlocks").toString()
                .contains("placed 1 of minCount 3"));
    }

    @Test
    void explicitMinBelowTargetAllowsShortfallAndRecordsTrace() throws Exception {
        Fixture fixture = fixture();
        CityLandformReviewPackage sparse = reviewWithMemberCells(fixture.review(),
                List.of(memberCell(fixture.review(), 0, 0)));
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), sparse, fixture.terraSenseSource(),
                arrayLayoutPlan(), CityStructureEnvelopeFacts.empty(), new JsonObject(), new JsonObject());
        JsonObject item = layoutItemWithCounts(sparse, "explicit_min_shortfall", 1, 3, 3);

        CityStructureArrayLayoutLoopPlanner.ExecuteResult executed = planner.execute(
                fixture.baseDir(), sparse, fixture.terraSenseSource(),
                created.loopState(), item, CityStructureEnvelopeFacts.empty());

        assertTrue(executed.asJson().get("ok").getAsBoolean());
        assertEquals(1, executed.loopState().getAsJsonArray("arrayAnchors").size());
        JsonObject trace = executed.executionTrace().getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals(2, trace.get("targetShortfallCount").getAsInt());
    }

    @Test
    void compositeArrayCreatesParentAndChildZones() throws Exception {
        Fixture fixture = fixture();
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                arrayLayoutPlanV03(), CityStructureEnvelopeFacts.empty(), new JsonObject(), new JsonObject());
        CityStructureArrayLayoutLoopPlanner.ExecuteResult executed = planner.execute(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                created.loopState(), compositeLayoutItem(fixture.review()), CityStructureEnvelopeFacts.empty());

        assertTrue(executed.asJson().get("ok").getAsBoolean());
        assertEquals("array_layout_loop_v0_3", executed.asJson().get("planningMode").getAsString());
        JsonArray zones = executed.functionalArrayZones().getAsJsonArray("arrayZones");
        assertEquals(3, zones.size());
        assertEquals("parent_composite", zones.get(0).getAsJsonObject().get("zoneKind").getAsString());
        assertEquals("child_array", zones.get(1).getAsJsonObject().get("zoneKind").getAsString());
        assertEquals(4, executed.loopState().getAsJsonArray("arrayAnchors").size());
        CityStructureArrayLayoutLoopPlanner.FinalizeResult finalized = planner.finalizeLoop(executed.loopState());
        assertEquals(4, finalized.structureAnchorPlan().getAsJsonArray("anchors").size());
    }

    @Test
    void requiredItemFailureHardBlocksButDoesNotThrow() throws Exception {
        Fixture fixture = fixture();
        JsonObject occupiedMap = JsonParser.parseString("""
                {
                  "anchors": [
                    {
                      "anchorId": "key_core",
                      "collisionEnvelope": {"minX": -420, "minZ": -420, "maxX": 420, "maxZ": 420}
                    }
                  ]
                }
                """).getAsJsonObject();
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                arrayLayoutPlan(), CityStructureEnvelopeFacts.empty(), new JsonObject(), occupiedMap);
        JsonObject item = layoutItem(fixture.review(), "compound_cluster", "blocked_required", 1);
        item.add("requiredItems", JsonParser.parseString("""
                [{"itemId":"required_hall","structureId":"minecraft:desert_pyramid"}]
                """).getAsJsonArray());
        item.add("fillPool", new JsonArray());

        CityStructureArrayLayoutLoopPlanner.ExecuteResult executed = planner.execute(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                created.loopState(), item, CityStructureEnvelopeFacts.empty());

        assertFalse(executed.asJson().get("ok").getAsBoolean());
        assertTrue(executed.qualityReport().getAsJsonArray("hardBlocks").toString()
                .contains("D4_ARRAY_LAYOUT_REQUIRED_ITEM_UNPLACED"));
    }

    private static void assertNoItemCollisionOverlap(JsonObject zone) {
        JsonArray items = zone.getAsJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            BlockBounds a = bounds(items.get(i).getAsJsonObject().getAsJsonObject("estimatedCollisionEnvelope"));
            for (int j = i + 1; j < items.size(); j++) {
                BlockBounds b = bounds(items.get(j).getAsJsonObject().getAsJsonObject("estimatedCollisionEnvelope"));
                assertFalse(a.overlaps(b), "item collision envelopes should not overlap");
            }
        }
    }

    private static void assertCompoundClusterShape(CityStructureArrayLayoutLoopPlanner planner,
                                                   Fixture fixture,
                                                   String shape,
                                                   int rows,
                                                   int columns,
                                                   int targetCount) throws Exception {
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                arrayLayoutPlan(), CityStructureEnvelopeFacts.empty(), new JsonObject(), new JsonObject());
        JsonObject item = layoutItem(fixture.review(), "compound_cluster", shape + "_array", targetCount);
        item.add("compoundCluster", JsonParser.parseString("""
                {"shape": "%s", "rows": %d, "columns": %d, "spacingBlocks": 64}
                """.formatted(shape, rows, columns)).getAsJsonObject());

        CityStructureArrayLayoutLoopPlanner.ExecuteResult executed = planner.execute(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                created.loopState(), item, CityStructureEnvelopeFacts.empty());

        assertTrue(executed.asJson().get("ok").getAsBoolean(), shape);
        JsonObject zone = executed.functionalArrayZones().getAsJsonArray("arrayZones").get(0).getAsJsonObject();
        assertEquals(shape, zone.get("arrayShape").getAsString());
        assertEquals(64, zone.get("spacingBlocks").getAsInt());
        assertEquals(targetCount, zone.getAsJsonArray("items").size(), shape);
        assertNoItemCollisionOverlap(zone);
        assertShapePoints(shape, zone.getAsJsonArray("items"));
    }

    private static void assertShapePoints(String shape, JsonArray items) {
        List<Integer> xs = sortedUniqueCoordinates(items, "x");
        List<Integer> zs = sortedUniqueCoordinates(items, "z");
        switch (shape) {
            case "grid" -> {
                assertEquals(2, xs.size(), "grid should form two columns");
                assertEquals(2, zs.size(), "grid should form two rows");
                assertTrue(hasPoint(items, xs.get(0), zs.get(0)), "grid should contain northwest corner");
                assertTrue(hasPoint(items, xs.get(1), zs.get(0)), "grid should contain northeast corner");
                assertTrue(hasPoint(items, xs.get(0), zs.get(1)), "grid should contain southwest corner");
                assertTrue(hasPoint(items, xs.get(1), zs.get(1)), "grid should contain southeast corner");
            }
            case "courtyard" -> {
                assertEquals(3, xs.size(), "courtyard should keep three columns around the court");
                assertEquals(3, zs.size(), "courtyard should keep three rows around the court");
                int minX = xs.get(0);
                int maxX = xs.get(xs.size() - 1);
                int minZ = zs.get(0);
                int maxZ = zs.get(zs.size() - 1);
                assertPointCountOnOuterRule(items, point ->
                        point.x() == minX || point.x() == maxX || point.z() == minZ || point.z() == maxZ);
                assertFalse(hasPoint(items, xs.get(1), zs.get(1)), "courtyard center must stay open");
            }
            case "l_shape" -> {
                assertEquals(3, xs.size(), "l_shape should expose three columns");
                assertEquals(3, zs.size(), "l_shape should expose three rows");
                int minX = xs.get(0);
                int maxZ = zs.get(zs.size() - 1);
                assertPointCountOnOuterRule(items, point -> point.x() == minX || point.z() == maxZ);
            }
            case "u_shape" -> {
                assertEquals(3, xs.size(), "u_shape should expose three columns");
                assertEquals(3, zs.size(), "u_shape should expose three rows");
                int minX = xs.get(0);
                int maxX = xs.get(xs.size() - 1);
                int maxZ = zs.get(zs.size() - 1);
                assertPointCountOnOuterRule(items, point ->
                        point.x() == minX || point.x() == maxX || point.z() == maxZ);
                assertFalse(hasPoint(items, xs.get(1), zs.get(0)), "u_shape should keep its open side empty");
                assertFalse(hasPoint(items, xs.get(1), zs.get(1)), "u_shape center must stay open");
            }
            case "organic_compact" -> {
                assertTrue(xs.size() >= 2, "organic_compact should not collapse to one column");
                assertTrue(zs.size() >= 2, "organic_compact should not collapse to one row");
                assertTrue(xs.get(xs.size() - 1) - xs.get(0) <= 256,
                        "organic_compact should remain locally compact");
                assertTrue(zs.get(zs.size() - 1) - zs.get(0) <= 256,
                        "organic_compact should remain locally compact");
            }
            default -> throw new AssertionError("unexpected shape " + shape);
        }
    }

    private static List<Integer> sortedUniqueCoordinates(JsonArray items, String axis) {
        List<Integer> values = new ArrayList<>();
        for (JsonElement elem : items) {
            int value = elem.getAsJsonObject().getAsJsonObject("anchorBlock").get(axis).getAsInt();
            if (!values.contains(value)) {
                values.add(value);
            }
        }
        Collections.sort(values);
        return values;
    }

    private static boolean hasPoint(JsonArray items, int x, int z) {
        for (JsonElement elem : items) {
            JsonObject point = elem.getAsJsonObject().getAsJsonObject("anchorBlock");
            if (point.get("x").getAsInt() == x && point.get("z").getAsInt() == z) {
                return true;
            }
        }
        return false;
    }

    private static void assertPointCountOnOuterRule(JsonArray items, PointRule rule) {
        for (JsonElement elem : items) {
            JsonObject point = elem.getAsJsonObject().getAsJsonObject("anchorBlock");
            assertTrue(rule.accepts(new TestPoint(point.get("x").getAsInt(), point.get("z").getAsInt())),
                    "point should match expected shape outline: " + point);
        }
    }

    private static void assertAnyItemDiagnosticMaxObservedOverlap(JsonObject zone) {
        JsonArray items = zone.getAsJsonArray("items");
        boolean found = false;
        for (int i = 0; i < items.size(); i++) {
            JsonObject item = items.get(i).getAsJsonObject();
            assertFalse(item.has("estimatedSafetyEnvelope"));
            BlockBounds a = bounds(item.getAsJsonObject("diagnosticMaxObservedEnvelope"));
            for (int j = i + 1; j < items.size(); j++) {
                JsonObject other = items.get(j).getAsJsonObject();
                assertFalse(other.has("estimatedSafetyEnvelope"));
                BlockBounds b = bounds(other.getAsJsonObject("diagnosticMaxObservedEnvelope"));
                found |= a.overlaps(b);
            }
        }
        assertTrue(found, "diagnostic max observed envelopes should be allowed to overlap");
    }

    private static void assertAnyItemMaskOverlap(JsonObject zone) {
        JsonArray items = zone.getAsJsonArray("items");
        boolean found = false;
        for (int i = 0; i < items.size(); i++) {
            JsonObject item = items.get(i).getAsJsonObject();
            assertFalse(item.has("estimatedSafetyEnvelope"));
            BlockBounds a = bounds(item.getAsJsonObject("estimatedMaskEnvelope"));
            for (int j = i + 1; j < items.size(); j++) {
                JsonObject other = items.get(j).getAsJsonObject();
                assertFalse(other.has("estimatedSafetyEnvelope"));
                BlockBounds b = bounds(other.getAsJsonObject("estimatedMaskEnvelope"));
                found |= a.overlaps(b);
            }
        }
        assertTrue(found, "mask envelopes should be allowed to overlap when collision envelopes are clear");
    }

    private static void assertNoOccupiedOverlap(JsonArray occupiedEnvelopes) {
        for (int i = 0; i < occupiedEnvelopes.size(); i++) {
            BlockBounds a = bounds(occupiedEnvelopes.get(i).getAsJsonObject().getAsJsonObject("blockBounds"));
            for (int j = i + 1; j < occupiedEnvelopes.size(); j++) {
                BlockBounds b = bounds(occupiedEnvelopes.get(j).getAsJsonObject().getAsJsonObject("blockBounds"));
                assertFalse(a.overlaps(b), "occupied envelopes should not overlap");
            }
        }
    }

    private static JsonObject arrayLayoutPlan() {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_d4_array_layout_plan.v0.2",
                  "cityId": "city_test",
                  "cityScale": "town",
                  "maxArrayPlans": 8,
                  "layoutPlans": []
                }
                """).getAsJsonObject();
    }

    private static JsonObject arrayLayoutPlanV03() {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_d4_array_layout_plan.v0.3",
                  "planningMode": "array_layout_loop_v0_3",
                  "cityId": "city_test",
                  "cityScale": "town",
                  "maxArrayPlans": 8,
                  "layoutPlans": []
                }
                """).getAsJsonObject();
    }

    private static JsonObject layoutItem(CityLandformReviewPackage review, String plannerType,
                                         String arrayId, int targetCount) {
        return layoutItemWithCounts(review, plannerType, arrayId, targetCount, targetCount, targetCount);
    }

    private static JsonObject layoutItemWithCounts(CityLandformReviewPackage review, String arrayId,
                                                   int minCount, int targetCount, int maxCount) {
        return layoutItemWithCounts(review, "compound_cluster", arrayId, minCount, targetCount, maxCount);
    }

    private static JsonObject layoutItemWithCounts(CityLandformReviewPackage review, String plannerType,
                                                   String arrayId, int minCount, int targetCount, int maxCount) {
        LandformPatchSummary first = review.landformPatches().get(0);
        return JsonParser.parseString("""
                {
                  "arrayId": "%s",
                  "plannerType": "%s",
                  "role": "residential",
                  "candidatePatchRefs": ["%s"],
                  "startSector": "center",
                  "fillPool": [
                    {"structureId": "minecraft:desert_pyramid", "weight": 1},
                    {"structureId": "minecraft:jungle_pyramid", "weight": 1}
                  ],
                  "countPolicy": {"minCount": %d, "targetCount": %d, "maxCount": %d},
                  "variantSelectionMode": "round_robin"
                }
                """.formatted(arrayId, plannerType, first.landformPatchId(),
                minCount, targetCount, maxCount)).getAsJsonObject();
    }

    private static JsonObject layoutItemWithoutMinCount(CityLandformReviewPackage review, String arrayId,
                                                        int targetCount) {
        LandformPatchSummary first = review.landformPatches().get(0);
        return JsonParser.parseString("""
                {
                  "arrayId": "%s",
                  "plannerType": "compound_cluster",
                  "role": "residential",
                  "candidatePatchRefs": ["%s"],
                  "startSector": "center",
                  "fillPool": [
                    {"structureId": "minecraft:desert_pyramid", "weight": 1}
                  ],
                  "countPolicy": {"targetCount": %d, "maxCount": %d},
                  "variantSelectionMode": "round_robin"
                }
                """.formatted(arrayId, first.landformPatchId(), targetCount, targetCount)).getAsJsonObject();
    }

    private static JsonObject compositeLayoutItem(CityLandformReviewPackage review) {
        LandformPatchSummary first = review.landformPatches().get(0);
        return JsonParser.parseString("""
                {
                  "arrayId": "residential_composite",
                  "plannerType": "composite_array",
                  "candidatePatchRefs": ["%s"],
                  "startSector": "center",
                  "subZonePolicy": "grid",
                  "childLayoutPlans": [
                    {
                      "arrayId": "residential_block_a",
                      "plannerType": "compound_cluster",
                      "targetSubZoneId": "subzone_01",
                      "fillPool": [{"structureId": "minecraft:desert_pyramid", "weight": 1}],
                      "countPolicy": {"minCount": 2, "targetCount": 2, "maxCount": 2},
                      "variantSelectionMode": "round_robin"
                    },
                    {
                      "arrayId": "residential_block_b",
                      "plannerType": "guide_line_dual_side",
                      "targetSubZoneId": "subzone_02",
                      "fillPool": [{"structureId": "minecraft:jungle_pyramid", "weight": 1}],
                      "countPolicy": {"minCount": 2, "targetCount": 2, "maxCount": 2},
                      "variantSelectionMode": "round_robin"
                    }
                  ]
                }
                """.formatted(first.landformPatchId())).getAsJsonObject();
    }

    private static Fixture fixture() throws Exception {
        Path baseDir = Files.createTempDirectory("city-array-layout-loop");
        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new CitySiteContextBuilder(config)
                .build("city_test", "realm_test", "minecraft:overworld",
                        "city_test", "candidate_test", 0, 0,
                        "town", "town", 260, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context,
                java.util.List.of(patch("plain_big", LandformType.PLAIN, -320, -320, 320, 320)));
        Path catalogPath = baseDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());
        JsonObject source = new JsonObject();
        source.addProperty("schemaVersion", "terrasense_structure_profile_source.v0.1");
        source.addProperty("sourceType", "debug_catalog");
        source.addProperty("catalogMode", "debug");
        source.addProperty("debugCatalogPath", catalogPath.toString());
        return new Fixture(baseDir, review, source);
    }

    private static CityStructureEnvelopeFacts wideDiagnosticFacts(Fixture fixture, String structureId) throws Exception {
        CityStructureEnvelopeProfiler.Result result = new CityStructureEnvelopeProfiler()
                .profile(fixture.baseDir(), fixture.terraSenseSource(), List.of(structureId), 6,
                        (profile, sampleIndex) -> CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex,
                                sampleIndex < 5
                                        ? new BlockBounds(-3, -3, 3, 3)
                                        : new BlockBounds(-90, -90, 90, 90),
                                1, "fixed_config_hash", "pack_hash"));
        Path factsPath = fixture.baseDir().resolve("wide_diagnostic_structure_envelope_facts.json");
        Files.writeString(factsPath, CityJson.GSON.toJson(result.structureEnvelopeFacts()));
        return CityStructureEnvelopeFacts.load(factsPath);
    }

    private static CityLandformReviewPackage reviewWithMemberCells(CityLandformReviewPackage review,
                                                                   List<PatchMemberCell> cells) {
        ArrayList<LandformPatchSummary> patches = new ArrayList<>(review.landformPatches());
        patches.set(0, patches.get(0).withMemberCells(cells));
        return new CityLandformReviewPackage(review.schemaVersion(), review.cityId(), review.grid(),
                review.targetScale(), review.reviewMapImage(), review.legend(), patches,
                review.planningContext(), review.aiPromptContext(), review.debugRefs());
    }

    private static PatchMemberCell memberCell(CityLandformReviewPackage review, int blockMinX, int blockMinZ) {
        return new PatchMemberCell(review.grid().blockToCellX(blockMinX), review.grid().blockToCellZ(blockMinZ),
                blockMinX, blockMinZ);
    }

    private static LandformPatch patch(String id, LandformType type, int minX, int minZ, int maxX, int maxZ) {
        return new LandformPatch(id, "region_0", type,
                Math.max(1, (maxX - minX) * (maxZ - minZ) / 256), minX, minZ, maxX, maxZ,
                70.0, 65.0, 75.0, 0.08, 50.0,
                false, false, 0.9, EnumSet.noneOf(PatchFlag.class));
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(obj.get("minX").getAsInt(), obj.get("minZ").getAsInt(),
                obj.get("maxX").getAsInt(), obj.get("maxZ").getAsInt());
    }

    private static String debugStructureCatalog() {
        return """
                {
                  "schemaVersion": "city_structure_profile_catalog.v0.1",
                  "catalogMode": "debug",
                  "source": {"basis": "synthetic unit-test fixture"},
                  "structures": [
                    {
                      "structureId": "minecraft:desert_pyramid",
                      "profileType": "single",
                      "footprintMode": "fixed_footprint",
                      "semanticTerms": ["function.landmark", "quality.debug_usable"],
                      "functionTerms": ["function.landmark"],
                      "qualityTerms": ["quality.debug_usable"],
                      "fixedFootprint": {"widthBlocks": 20, "depthBlocks": 12, "heightBlocks": 10},
                      "clearanceBlocks": 2
                    },
                    {
                      "structureId": "minecraft:jungle_pyramid",
                      "profileType": "single",
                      "footprintMode": "fixed_footprint",
                      "semanticTerms": ["function.residence", "quality.debug_usable"],
                      "functionTerms": ["function.residence"],
                      "qualityTerms": ["quality.debug_usable"],
                      "fixedFootprint": {"widthBlocks": 18, "depthBlocks": 18, "heightBlocks": 12},
                      "clearanceBlocks": 2
                    }
                  ]
                }
                """;
    }

    private interface PointRule {
        boolean accepts(TestPoint point);
    }

    private record TestPoint(int x, int z) {
    }

    private record Fixture(Path baseDir, CityLandformReviewPackage review, JsonObject terraSenseSource) {
    }
}
