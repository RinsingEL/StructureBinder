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
import com.rinsing.geomantia.systems.city.infrastructure.world.CityRoadWeaverBridge;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityStructureArrayLayoutLoopPlannerTest {

    @Test
    void templateArrayUsesNbtFootprintsAndEmitsRoadWeaverEntrances() throws Exception {
        Fixture fixture = fixture();
        JsonObject plan = arrayLayoutPlan();
        plan.add("templateCatalog", templateCatalog());
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), plan,
                CityStructureEnvelopeFacts.empty(), new JsonObject(), new JsonObject());

        JsonObject item = JsonParser.parseString("""
                {
                  "arrayId": "template_market_row",
                  "plannerType": "compound_cluster",
                  "role": "commercial",
                  "candidatePatchRefs": ["plain_big"],
                  "startSector": "center",
                  "fillPool": [
                    {"templateId":"test:market_stall","variantId":"oak","rotation":"CLOCKWISE_90"}
                  ],
                  "countPolicy":{"minCount":2,"targetCount":2,"maxCount":2},
                  "variantSelectionMode":"round_robin"
                }
                """).getAsJsonObject();
        CityStructureArrayLayoutLoopPlanner.ExecuteResult executed = planner.execute(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), created.loopState(), item,
                CityStructureEnvelopeFacts.empty());

        assertTrue(executed.asJson().get("ok").getAsBoolean());
        JsonArray anchors = executed.loopState().getAsJsonArray("arrayAnchors");
        assertEquals(2, anchors.size());
        for (JsonElement anchorElement : anchors) {
            JsonObject anchor = anchorElement.getAsJsonObject();
            assertEquals("template:test:market/stall", anchor.get("structureId").getAsString());
            assertEquals("test:market_stall", anchor.get("templateId").getAsString());
            assertEquals("CLOCKWISE_90", anchor.get("rotation").getAsString());
            BlockBounds footprint = bounds(anchor.getAsJsonObject("actualFootprint"));
            assertEquals(5, footprint.widthBlocks());
            assertEquals(9, footprint.heightBlocks());
            assertEquals(9, anchor.getAsJsonObject("templateSize").get("width").getAsInt());
            assertFalse(anchor.has("templateFootprint"));
            JsonObject placement = anchor.getAsJsonObject("templatePlacementPlan");
            assertEquals(9, placement.getAsJsonObject("templateSize").get("width").getAsInt());
            assertEquals(1, placement.getAsJsonObject("transformed").getAsJsonArray("roadEntrances").size());
        }

        JsonObject materialization = new JsonObject();
        JsonArray plannedWorldgenStructures = anchors.deepCopy();
        for (JsonElement element : plannedWorldgenStructures) {
            element.getAsJsonObject().addProperty("status", "planned_worldgen");
        }
        materialization.add("plannedWorldgenStructures", plannedWorldgenStructures);
        CityRoadWeaverBridge.EndpointExtraction endpoints = CityRoadWeaverBridge.extractRoadEndpoints(materialization);
        assertTrue(endpoints.valid(), endpoints.errors().toString());
        assertEquals(2, endpoints.endpoints().size());
    }

    @Test
    void templateArrayRejectsCallerSuppliedFootprintGeometry() throws Exception {
        Fixture fixture = fixture();
        JsonObject plan = arrayLayoutPlan();
        plan.add("templateCatalog", templateCatalog());
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), plan,
                CityStructureEnvelopeFacts.empty(), new JsonObject(), new JsonObject());

        JsonObject item = JsonParser.parseString("""
                {
                  "arrayId": "invalid_template_geometry",
                  "plannerType": "compound_cluster",
                  "candidatePatchRefs": ["plain_big"],
                  "startSector": "center",
                  "fillPool": [{
                    "templateId":"test:market_stall",
                    "variantId":"oak",
                    "templateFootprint":{"minX":0,"minZ":0,"maxX":99,"maxZ":99}
                  }],
                  "countPolicy":{"minCount":1,"targetCount":1,"maxCount":1}
                }
                """).getAsJsonObject();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> planner.execute(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                        created.loopState(), item, CityStructureEnvelopeFacts.empty()));
        assertTrue(error.getMessage().contains("D4_ARRAY_LAYOUT_TEMPLATE_GEOMETRY_INPUT_FORBIDDEN"));
    }

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
        assertEquals(23, zone.get("spacingBlocks").getAsInt());
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
    void v04ExpansionCandidatesDoNotMutateStateUntilWholeCandidateIsSelected() throws Exception {
        Fixture fixture = fixture();
        JsonObject occupiedMap = JsonParser.parseString("""
                {
                  "anchors": [
                    {"anchorId":"manor_core", "collisionEnvelope":{"minX":-48,"minZ":-48,"maxX":48,"maxZ":48}}
                  ]
                }
                """).getAsJsonObject();
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), arrayLayoutPlanV04(),
                CityStructureEnvelopeFacts.empty(), new JsonObject(), occupiedMap);
        JsonObject request = expansionRequest(fixture.review());

        CityStructureArrayLayoutLoopPlanner.ExpansionSpaceResult space = planner.queryExpansionSpace(
                fixture.review(), created.loopState(), request);
        assertEquals("focus_nearby_expansion", space.expansionSpace().get("searchScope").getAsString());
        assertTrue(space.expansionSpace().getAsJsonArray("nearbyPatches").size() > 0);
        assertTrue(space.expansionSpace().get("selectedRemainingCapacity").getAsInt() > 0);

        CityStructureArrayLayoutLoopPlanner.ExpansionCandidateSetResult planned = planner.planExpansionCandidates(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), created.loopState(), request,
                CityStructureEnvelopeFacts.empty());
        assertEquals(3, planned.candidateSet().getAsJsonArray("arrayCandidates").size());
        assertEquals(0, created.loopState().getAsJsonArray("arrayAnchors").size(),
                "candidate generation must not commit anchors");
        assertEquals(0, created.loopState().get("iteration").getAsInt(),
                "candidate generation must not advance the loop state");
        for (JsonElement candidateElem : planned.candidateSet().getAsJsonArray("arrayCandidates")) {
            for (JsonElement itemElem : candidateElem.getAsJsonObject().getAsJsonArray("items")) {
                BlockBounds collision = bounds(itemElem.getAsJsonObject().getAsJsonObject("estimatedCollisionEnvelope"));
                assertTrue(collision.minX() > 48, "east expansion must not refill the manor occupied side");
            }
        }

        assertThrows(IllegalArgumentException.class, () -> planner.selectExpansionCandidate(
                fixture.review(), created.loopState(), planned.candidateSet(), "", false, ""));
        String candidateId = planned.candidateSet().getAsJsonArray("arrayCandidates").get(0)
                .getAsJsonObject().get("candidateId").getAsString();
        CityStructureArrayLayoutLoopPlanner.ExpansionSelectionResult selected = planner.selectExpansionCandidate(
                fixture.review(), created.loopState(), planned.candidateSet(), candidateId, false, "AI 选择东侧住宅簇");
        assertEquals(1, selected.loopState().get("iteration").getAsInt());
        assertEquals(2, selected.loopState().getAsJsonArray("arrayAnchors").size());
        assertEquals(1, selected.loopState().getAsJsonObject("functionalArrayZones")
                .getAsJsonArray("arrayZones").size());
        JsonObject trace = selected.loopState().getAsJsonObject("executionTrace")
                .getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("ai_or_human_selected", trace.get("decisionSource").getAsString());
        assertTrue(selected.loopState().has("remainingExpansionSpace"));

        CityStructureArrayLayoutLoopPlanner.ExpansionSelectionResult autoSelected = planner.selectExpansionCandidate(
                fixture.review(), created.loopState(), planned.candidateSet(), "", true, "");
        JsonObject autoTrace = autoSelected.loopState().getAsJsonObject("executionTrace")
                .getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("auto_highest_score_explicit", autoTrace.get("decisionSource").getAsString());
    }

    @Test
    void v04ContinuousExpansionUsesParentBodyGapWithoutTargetPatch() throws Exception {
        Fixture fixture = fixture();
        JsonObject occupiedMap = JsonParser.parseString("""
                {"anchors":[{"anchorId":"manor_core",
                "plannedFootprint":{"minX":-40,"minZ":-40,"maxX":40,"maxZ":40},
                "collisionEnvelope":{"minX":-48,"minZ":-48,"maxX":48,"maxZ":48}}]}
                """).getAsJsonObject();
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), arrayLayoutPlanV04(),
                CityStructureEnvelopeFacts.empty(), new JsonObject(), occupiedMap);
        JsonObject request = expansionRequest(fixture.review());
        request.remove("targetPatchRef");
        request.add("expansionPolicy", JsonParser.parseString("""
                {"actualBodyGapMin":16,"actualBodyGapMax":30,"frontierExpansionStepBlocks":16}
                """).getAsJsonObject());

        CityStructureArrayLayoutLoopPlanner.ExpansionSpaceResult space = planner.queryExpansionSpace(
                fixture.review(), created.loopState(), request);
        assertEquals("continuous_focus_frontier", space.expansionSpace().get("searchScope").getAsString());
        assertEquals(-40, space.expansionSpace().getAsJsonObject("focusBodyEnvelope").get("minX").getAsInt());

        CityStructureArrayLayoutLoopPlanner.ExpansionCandidateSetResult planned = planner.planExpansionCandidates(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), created.loopState(), request,
                CityStructureEnvelopeFacts.empty());
        assertEquals(3, planned.candidateSet().getAsJsonArray("arrayCandidates").size());
        for (JsonElement candidateElement : planned.candidateSet().getAsJsonArray("arrayCandidates")) {
            JsonObject candidate = candidateElement.getAsJsonObject();
            assertEquals("continuous_focus_frontier", candidate.get("expansionMode").getAsString());
            assertFalse(candidate.has("targetPatchRef"));
            assertTrue(candidate.get("actualBodyGapBlocks").getAsInt() >= 16);
            assertTrue(candidate.get("actualBodyGapBlocks").getAsInt() <= 30);
            assertFalse(candidate.getAsJsonArray("terrainPatchRefs").isEmpty());
        }
    }

    @Test
    void v04ContinuousExpansionAllowsExplicitTwoCandidateMinimumButDefaultsToThree() throws Exception {
        Fixture fixture = fixture();
        CityLandformReviewPackage sparse = reviewWithMemberCells(fixture.review(), List.of(
                memberCell(fixture.review(), 80, 0), memberCell(fixture.review(), 80, 28)));
        JsonObject occupiedMap = JsonParser.parseString("""
                {"anchors":[{"anchorId":"manor_core",
                "plannedFootprint":{"minX":-40,"minZ":-40,"maxX":40,"maxZ":40},
                "collisionEnvelope":{"minX":-48,"minZ":-48,"maxX":48,"maxZ":48}}]}
                """).getAsJsonObject();
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), sparse, fixture.terraSenseSource(), arrayLayoutPlanV04(),
                CityStructureEnvelopeFacts.empty(), new JsonObject(), occupiedMap);
        JsonObject request = expansionRequest(sparse);
        request.remove("targetPatchRef");
        request.getAsJsonObject("nextArrayLayoutPlanItem").add("countPolicy", JsonParser.parseString("""
                {"minCount":1,"targetCount":1,"maxCount":1}
                """).getAsJsonObject());

        IllegalArgumentException defaultThree = assertThrows(IllegalArgumentException.class,
                () -> planner.planExpansionCandidates(fixture.baseDir(), sparse, fixture.terraSenseSource(),
                        created.loopState(), request, CityStructureEnvelopeFacts.empty()));
        assertTrue(defaultThree.getMessage().contains("D4_ARRAY_LAYOUT_CONTINUOUS_FRONTIER_UNSATISFIED"));

        request.addProperty("minCandidateCount", 2);
        CityStructureArrayLayoutLoopPlanner.ExpansionCandidateSetResult planned = planner.planExpansionCandidates(
                fixture.baseDir(), sparse, fixture.terraSenseSource(), created.loopState(), request,
                CityStructureEnvelopeFacts.empty());
        assertEquals(2, planned.candidateSet().getAsJsonArray("arrayCandidates").size());
    }

    @Test
    void v04ContinuousExpansionOnlyMovesToMidRingAfterNearRingHasNoCompleteCandidate() throws Exception {
        Fixture fixture = fixture();
        CityLandformReviewPackage sparse = reviewWithMemberCells(fixture.review(), List.of(
                memberCell(fixture.review(), 88, -84), memberCell(fixture.review(), 92, -56),
                memberCell(fixture.review(), 88, -28), memberCell(fixture.review(), 92, 0),
                memberCell(fixture.review(), 88, 28), memberCell(fixture.review(), 92, 56),
                memberCell(fixture.review(), 88, 84), memberCell(fixture.review(), 92, 112)));
        JsonObject occupiedMap = JsonParser.parseString("""
                {"anchors":[{"anchorId":"manor_core",
                "plannedFootprint":{"minX":-40,"minZ":-40,"maxX":40,"maxZ":40},
                "collisionEnvelope":{"minX":-48,"minZ":-48,"maxX":48,"maxZ":48}}]}
                """).getAsJsonObject();
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), sparse, fixture.terraSenseSource(), arrayLayoutPlanV04(),
                CityStructureEnvelopeFacts.empty(), new JsonObject(), occupiedMap);
        JsonObject request = expansionRequest(sparse);
        request.remove("targetPatchRef");
        request.add("expansionPolicy", JsonParser.parseString("""
                {"actualBodyGapMin":16,"actualBodyGapMax":30,"frontierExpansionStepBlocks":16}
                """).getAsJsonObject());

        CityStructureArrayLayoutLoopPlanner.ExpansionCandidateSetResult planned = planner.planExpansionCandidates(
                fixture.baseDir(), sparse, fixture.terraSenseSource(), created.loopState(), request,
                CityStructureEnvelopeFacts.empty());
        JsonArray trace = planned.candidateSet().getAsJsonArray("frontierSearchTrace");
        assertEquals("near", trace.get(0).getAsJsonObject().get("frontierRing").getAsString());
        assertEquals("skipped", trace.get(0).getAsJsonObject().get("result").getAsString());
        assertEquals("D4_ARRAY_LAYOUT_FRONTIER_BODY_GAP_UNSATISFIED",
                trace.get(0).getAsJsonObject().get("reasonCode").getAsString());
        for (JsonElement candidateElement : planned.candidateSet().getAsJsonArray("arrayCandidates")) {
            assertEquals("mid", candidateElement.getAsJsonObject().get("frontierRing").getAsString());
        }
    }

    @Test
    void v04ContinuousExpansionTreatsPatchesAsTerrainFiltersAndCanCrossTheirBoundary() throws Exception {
        Fixture fixture = fixture();
        CityLandformReviewPackage split = reviewWithSplitPatches(fixture.review());
        JsonObject occupiedMap = JsonParser.parseString("""
                {"anchors":[{"anchorId":"manor_core",
                "plannedFootprint":{"minX":-40,"minZ":-40,"maxX":40,"maxZ":40},
                "collisionEnvelope":{"minX":-48,"minZ":-48,"maxX":48,"maxZ":48}}]}
                """).getAsJsonObject();
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), split, fixture.terraSenseSource(), arrayLayoutPlanV04(),
                CityStructureEnvelopeFacts.empty(), new JsonObject(), occupiedMap);
        JsonObject request = expansionRequest(split);
        request.remove("targetPatchRef");

        CityStructureArrayLayoutLoopPlanner.ExpansionCandidateSetResult planned = planner.planExpansionCandidates(
                fixture.baseDir(), split, fixture.terraSenseSource(), created.loopState(), request,
                CityStructureEnvelopeFacts.empty());
        JsonObject candidate = planned.candidateSet().getAsJsonArray("arrayCandidates").get(0).getAsJsonObject();
        assertFalse(candidate.has("targetPatchRef"));
        assertTrue(candidate.getAsJsonArray("terrainPatchRefs").size() >= 2,
                "one continuous cluster should retain terrain membership on both sides of the D3 patch boundary");
    }

    @Test
    void v04ContinuousExpansionUsesD2ParentAndChildBodiesForTheFrontierGap() throws Exception {
        Fixture fixture = fixture();
        CityStructureEnvelopeFacts childFacts = stableD2Facts(fixture, "minecraft:desert_pyramid", 24);
        JsonObject parentFact = JsonParser.parseString("""
                {"collisionEnvelopeSource":"stableMaxEnvelope",
                "localEnvelopeP95":{"minX":-18,"minZ":-18,"maxX":18,"maxZ":18},
                "stableMaxEnvelope":{"minX":-18,"minZ":-18,"maxX":18,"maxZ":18}}
                """).getAsJsonObject();
        JsonObject occupiedMap = JsonParser.parseString("""
                {"anchors":[{"anchorId":"manor_core","structureId":"minecraft:desert_pyramid",
                "anchorBlock":{"x":0,"z":0},
                "plannedFootprint":{"minX":-10,"minZ":-6,"maxX":9,"maxZ":5},
                "collisionEnvelope":{"minX":-26,"minZ":-26,"maxX":26,"maxZ":26}}]}
                """).getAsJsonObject();
        occupiedMap.getAsJsonArray("anchors").get(0).getAsJsonObject().add("structureEnvelopeFact", parentFact);
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), arrayLayoutPlanV04(),
                childFacts, new JsonObject(), occupiedMap);
        JsonObject occupied = created.loopState().getAsJsonArray("occupiedEnvelopes").get(0).getAsJsonObject();
        assertEquals("d2_structure_envelope_fact", occupied.get("bodyEnvelopeSource").getAsString());
        assertEquals(-18, occupied.getAsJsonObject("bodyBounds").get("minX").getAsInt());
        assertEquals(18, occupied.getAsJsonObject("bodyBounds").get("maxX").getAsInt());

        JsonObject request = expansionRequest(fixture.review());
        request.remove("targetPatchRef");
        request.getAsJsonObject("nextArrayLayoutPlanItem").add("countPolicy", JsonParser.parseString("""
                {"minCount":1,"targetCount":1,"maxCount":1}
                """).getAsJsonObject());
        CityStructureArrayLayoutLoopPlanner.ExpansionCandidateSetResult planned = planner.planExpansionCandidates(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), created.loopState(), request,
                childFacts);
        for (JsonElement candidateElement : planned.candidateSet().getAsJsonArray("arrayCandidates")) {
            int gap = candidateElement.getAsJsonObject().get("actualBodyGapBlocks").getAsInt();
            assertTrue(gap >= 16 && gap <= 30,
                    "D2 37-block parent and 49-block child must still use the requested body gap, not spacing");
        }
    }

    @Test
    void v05ContinuousGuideLineKeepsItsInnerRowAtTheRequestedBodyGap() throws Exception {
        Fixture fixture = fixture();
        CityStructureEnvelopeFacts childFacts = stableD2Facts(fixture, "minecraft:desert_pyramid", 24);
        JsonObject parentFact = JsonParser.parseString("""
                {"collisionEnvelopeSource":"stableMaxEnvelope",
                "localEnvelopeP95":{"minX":-18,"minZ":-18,"maxX":18,"maxZ":18},
                "stableMaxEnvelope":{"minX":-18,"minZ":-18,"maxX":18,"maxZ":18}}
                """).getAsJsonObject();
        JsonObject occupiedMap = JsonParser.parseString("""
                {"anchors":[{"anchorId":"manor_core","structureId":"minecraft:desert_pyramid",
                "anchorBlock":{"x":0,"z":0},
                "plannedFootprint":{"minX":-10,"minZ":-6,"maxX":9,"maxZ":5},
                "collisionEnvelope":{"minX":-26,"minZ":-26,"maxX":26,"maxZ":26}}]}
                """).getAsJsonObject();
        occupiedMap.getAsJsonArray("anchors").get(0).getAsJsonObject().add("structureEnvelopeFact", parentFact);
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), arrayLayoutPlanV04(),
                childFacts, new JsonObject(), occupiedMap);
        JsonObject request = expansionRequest(fixture.review());
        request.remove("targetPatchRef");
        request.add("expansionPolicy", JsonParser.parseString("""
                {"actualBodyGapMin":16,"actualBodyGapMax":30,"frontierExpansionStepBlocks":16}
                """).getAsJsonObject());
        JsonObject item = request.getAsJsonObject("nextArrayLayoutPlanItem");
        item.addProperty("arrayId", "market_street");
        item.addProperty("plannerType", "guide_line_dual_side");
        item.add("countPolicy", JsonParser.parseString("""
                {"minCount":4,"targetCount":4,"maxCount":4}
                """).getAsJsonObject());

        CityStructureArrayLayoutLoopPlanner.ExpansionCandidateSetResult planned = planner.planExpansionCandidates(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), created.loopState(), request,
                childFacts);
        assertEquals(3, planned.candidateSet().getAsJsonArray("arrayCandidates").size());
        for (JsonElement candidateElement : planned.candidateSet().getAsJsonArray("arrayCandidates")) {
            JsonObject candidate = candidateElement.getAsJsonObject();
            assertEquals(4, candidate.getAsJsonArray("items").size());
            assertTrue(candidate.get("actualBodyGapBlocks").getAsInt() >= 16);
            assertTrue(candidate.get("actualBodyGapBlocks").getAsInt() <= 30);
            for (JsonElement itemElement : candidate.getAsJsonArray("items")) {
                JsonObject body = itemElement.getAsJsonObject().getAsJsonObject("plannedFootprint");
                assertTrue(body.get("minX").getAsInt() - 18 - 1 >= 16,
                        "every guide-line member must remain beyond the continuous frontier minimum gap");
            }
        }
    }

    @Test
    void v05ContinuousMixedBodyClusterUsesTheLargestChildForItsFrontier() throws Exception {
        Fixture fixture = fixture();
        CityStructureEnvelopeFacts childFacts = stableD2Facts(fixture, Map.of(
                "minecraft:desert_pyramid", 24,
                "minecraft:jungle_pyramid", 52));
        JsonObject parentFact = JsonParser.parseString("""
                {"collisionEnvelopeSource":"stableMaxEnvelope",
                "localEnvelopeP95":{"minX":-18,"minZ":-18,"maxX":18,"maxZ":18},
                "stableMaxEnvelope":{"minX":-18,"minZ":-18,"maxX":18,"maxZ":18}}
                """).getAsJsonObject();
        JsonObject occupiedMap = JsonParser.parseString("""
                {"anchors":[{"anchorId":"manor_core","structureId":"minecraft:desert_pyramid",
                "anchorBlock":{"x":0,"z":0},
                "plannedFootprint":{"minX":-10,"minZ":-6,"maxX":9,"maxZ":5},
                "collisionEnvelope":{"minX":-26,"minZ":-26,"maxX":26,"maxZ":26}}]}
                """).getAsJsonObject();
        occupiedMap.getAsJsonArray("anchors").get(0).getAsJsonObject().add("structureEnvelopeFact", parentFact);
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), arrayLayoutPlanV04(),
                childFacts, new JsonObject(), occupiedMap);
        JsonObject request = expansionRequest(fixture.review());
        request.remove("targetPatchRef");
        request.addProperty("direction", "north");
        request.add("expansionPolicy", JsonParser.parseString("""
                {"actualBodyGapMin":16,"actualBodyGapMax":30,"frontierExpansionStepBlocks":16}
                """).getAsJsonObject());
        JsonObject item = request.getAsJsonObject("nextArrayLayoutPlanItem");
        item.addProperty("arrayId", "mixed_farmstead");
        item.add("fillPool", JsonParser.parseString("""
                [{"structureId":"minecraft:desert_pyramid","weight":1},
                 {"structureId":"minecraft:jungle_pyramid","weight":1}]
                """).getAsJsonArray());
        item.add("countPolicy", JsonParser.parseString("""
                {"minCount":2,"targetCount":2,"maxCount":2}
                """).getAsJsonObject());
        item.add("compoundCluster", JsonParser.parseString("""
                {"shape":"grid","rows":1,"columns":2}
                """).getAsJsonObject());

        CityStructureArrayLayoutLoopPlanner.ExpansionCandidateSetResult planned = planner.planExpansionCandidates(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), created.loopState(), request,
                childFacts);
        assertEquals(3, planned.candidateSet().getAsJsonArray("arrayCandidates").size());
        for (JsonElement candidateElement : planned.candidateSet().getAsJsonArray("arrayCandidates")) {
            JsonObject candidate = candidateElement.getAsJsonObject();
            assertEquals(2, candidate.getAsJsonArray("items").size());
            assertTrue(candidate.get("actualBodyGapBlocks").getAsInt() >= 16);
            assertTrue(candidate.get("actualBodyGapBlocks").getAsInt() <= 30);
            int nearestGap = Integer.MAX_VALUE;
            for (JsonElement itemElement : candidate.getAsJsonArray("items")) {
                JsonObject body = itemElement.getAsJsonObject().getAsJsonObject("plannedFootprint");
                nearestGap = Math.min(nearestGap, -18 - body.get("maxZ").getAsInt() - 1);
            }
            assertEquals(candidate.get("actualBodyGapBlocks").getAsInt(), nearestGap);
        }
    }

    @Test
    void v04SouthFourHouseGridKeepsItsFirstBodyInsideTheNearFrontierRing() throws Exception {
        Fixture fixture = fixture();
        CityStructureEnvelopeFacts childFacts = stableD2Facts(fixture, "minecraft:desert_pyramid", 24);
        JsonObject parentFact = JsonParser.parseString("""
                {"collisionEnvelopeSource":"stableMaxEnvelope",
                "localEnvelopeP95":{"minX":-55,"minZ":-55,"maxX":55,"maxZ":55},
                "stableMaxEnvelope":{"minX":-55,"minZ":-55,"maxX":55,"maxZ":55}}
                """).getAsJsonObject();
        JsonObject occupiedMap = JsonParser.parseString("""
                {"anchors":[{"anchorId":"manor_core","structureId":"minecraft:desert_pyramid",
                "anchorBlock":{"x":0,"z":0},
                "plannedFootprint":{"minX":-10,"minZ":-6,"maxX":9,"maxZ":5},
                "collisionEnvelope":{"minX":-63,"minZ":-63,"maxX":63,"maxZ":63}}]}
                """).getAsJsonObject();
        occupiedMap.getAsJsonArray("anchors").get(0).getAsJsonObject().add("structureEnvelopeFact", parentFact);
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), arrayLayoutPlanV04(),
                childFacts, new JsonObject(), occupiedMap);
        JsonObject request = expansionRequest(fixture.review());
        request.remove("targetPatchRef");
        request.addProperty("direction", "south");
        JsonObject item = request.getAsJsonObject("nextArrayLayoutPlanItem");
        item.add("countPolicy", JsonParser.parseString("""
                {"minCount":4,"targetCount":4,"maxCount":4}
                """).getAsJsonObject());
        item.add("compoundCluster", JsonParser.parseString("""
                {"shape":"grid","rows":2,"columns":2,"spacingBlocks":80}
                """).getAsJsonObject());

        CityStructureArrayLayoutLoopPlanner.ExpansionCandidateSetResult planned = planner.planExpansionCandidates(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), created.loopState(), request,
                childFacts);
        assertEquals(3, planned.candidateSet().getAsJsonArray("arrayCandidates").size());
        for (JsonElement candidateElement : planned.candidateSet().getAsJsonArray("arrayCandidates")) {
            JsonObject candidate = candidateElement.getAsJsonObject();
            assertEquals("near", candidate.get("frontierRing").getAsString());
            assertTrue(candidate.get("actualBodyGapBlocks").getAsInt() >= 16);
            assertTrue(candidate.get("actualBodyGapBlocks").getAsInt() <= 30);
            assertEquals(4, candidate.getAsJsonArray("items").size());
        }
    }

    @Test
    void v04ContinuousExpansionRejectsWaterForGroundedStructuresBeforeExpanding() throws Exception {
        Fixture fixture = fixture();
        CityLandformReviewPackage waterFrontier = reviewWithWaterFrontier(fixture.review());
        JsonObject occupiedMap = JsonParser.parseString("""
                {"anchors":[{"anchorId":"manor_core",
                "plannedFootprint":{"minX":-40,"minZ":-40,"maxX":40,"maxZ":40},
                "collisionEnvelope":{"minX":-48,"minZ":-48,"maxX":48,"maxZ":48}}]}
                """).getAsJsonObject();
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), waterFrontier, fixture.terraSenseSource(), arrayLayoutPlanV04(),
                CityStructureEnvelopeFacts.empty(), new JsonObject(), occupiedMap);
        JsonObject request = expansionRequest(waterFrontier);
        request.remove("targetPatchRef");

        CityStructureArrayLayoutLoopPlanner.ExpansionCandidateSetResult planned = planner.planExpansionCandidates(
                fixture.baseDir(), waterFrontier, fixture.terraSenseSource(), created.loopState(), request,
                CityStructureEnvelopeFacts.empty());
        JsonObject near = planned.candidateSet().getAsJsonArray("frontierSearchTrace").get(0).getAsJsonObject();
        assertEquals("near", near.get("frontierRing").getAsString());
        assertTrue(near.getAsJsonArray("excludedTerrainPatches").toString()
                .contains("D4_ARRAY_LAYOUT_FRONTIER_WATER_REJECTED_FOR_GROUNDED_STRUCTURE"));
        for (JsonElement candidateElement : planned.candidateSet().getAsJsonArray("arrayCandidates")) {
            assertFalse(candidateElement.getAsJsonObject().getAsJsonArray("terrainPatchRefs").toString()
                    .contains("water_frontier"));
        }
    }

    @Test
    void v04ContinuousExpansionAllowsGroundedStructuresOnNearWaterGentleSlope() throws Exception {
        Fixture fixture = fixture();
        CityLandformReviewPackage nearWaterSlope = reviewWithNearWaterGentleSlope(fixture.review());
        JsonObject occupiedMap = JsonParser.parseString("""
                {"anchors":[{"anchorId":"manor_core",
                "plannedFootprint":{"minX":-40,"minZ":-40,"maxX":40,"maxZ":40},
                "collisionEnvelope":{"minX":-48,"minZ":-48,"maxX":48,"maxZ":48}}]}
                """).getAsJsonObject();
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), nearWaterSlope, fixture.terraSenseSource(), arrayLayoutPlanV04(),
                CityStructureEnvelopeFacts.empty(), new JsonObject(), occupiedMap);
        JsonObject request = expansionRequest(nearWaterSlope);
        request.remove("targetPatchRef");

        CityStructureArrayLayoutLoopPlanner.ExpansionCandidateSetResult planned = planner.planExpansionCandidates(
                fixture.baseDir(), nearWaterSlope, fixture.terraSenseSource(), created.loopState(), request,
                CityStructureEnvelopeFacts.empty());
        JsonObject near = planned.candidateSet().getAsJsonArray("frontierSearchTrace").get(0).getAsJsonObject();
        assertFalse(near.getAsJsonArray("excludedTerrainPatches").toString()
                .contains("D4_ARRAY_LAYOUT_FRONTIER_WATER_REJECTED_FOR_GROUNDED_STRUCTURE"));
        for (JsonElement candidateElement : planned.candidateSet().getAsJsonArray("arrayCandidates")) {
            assertTrue(candidateElement.getAsJsonObject().getAsJsonArray("terrainPatchRefs").toString()
                    .contains("near_water_slope"));
        }
    }

    @Test
    void v04OutwardCompositeKeepsParentSubZonesAndHalfRingChildArray() throws Exception {
        Fixture fixture = fixture();
        JsonObject occupiedMap = JsonParser.parseString("""
                {"anchors":[{"anchorId":"manor_core","collisionEnvelope":{"minX":-48,"minZ":-48,"maxX":48,"maxZ":48}}]}
                """).getAsJsonObject();
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), arrayLayoutPlanV04(),
                CityStructureEnvelopeFacts.empty(), new JsonObject(), occupiedMap);
        JsonObject request = expansionRequest(fixture.review());
        request.add("nextArrayLayoutPlanItem", outwardCompositeItem());

        CityStructureArrayLayoutLoopPlanner.ExpansionCandidateSetResult planned = planner.planExpansionCandidates(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), created.loopState(), request,
                CityStructureEnvelopeFacts.empty());
        JsonObject firstCandidate = planned.candidateSet().getAsJsonArray("arrayCandidates").get(0).getAsJsonObject();
        JsonArray zones = firstCandidate.getAsJsonArray("arrayZones");
        assertEquals(3, zones.size());
        assertEquals("parent_composite", zones.get(0).getAsJsonObject().get("zoneKind").getAsString());
        assertEquals("plaza_ring", zones.get(1).getAsJsonObject().get("plannerType").getAsString());
        assertEquals("child_array", zones.get(1).getAsJsonObject().get("zoneKind").getAsString());
        for (JsonElement itemElem : firstCandidate.getAsJsonArray("items")) {
            assertTrue(bounds(itemElem.getAsJsonObject().getAsJsonObject("estimatedCollisionEnvelope")).minX() > 48);
        }

        CityStructureArrayLayoutLoopPlanner.ExpansionSelectionResult selected = planner.selectExpansionCandidate(
                fixture.review(), created.loopState(), planned.candidateSet(),
                firstCandidate.get("candidateId").getAsString(), false, "庄园东侧半环住宅簇");
        assertEquals(3, selected.loopState().getAsJsonObject("functionalArrayZones")
                .getAsJsonArray("arrayZones").size());
        assertNoOccupiedOverlap(selected.loopState().getAsJsonArray("occupiedEnvelopes"));
    }

    @Test
    void v04OutwardGuideLineDualSideKeepsCandidatesTentativeAndCommitsOnlyTheSelectedWholeArray() throws Exception {
        Fixture fixture = fixture();
        JsonObject occupiedMap = JsonParser.parseString("""
                {"anchors":[{"anchorId":"manor_core","collisionEnvelope":{"minX":-48,"minZ":-48,"maxX":48,"maxZ":48}}]}
                """).getAsJsonObject();
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), arrayLayoutPlanV04(),
                CityStructureEnvelopeFacts.empty(), new JsonObject(), occupiedMap);
        JsonObject request = expansionRequest(fixture.review());
        JsonObject item = request.getAsJsonObject("nextArrayLayoutPlanItem");
        item.addProperty("arrayId", "east_dual_side_theme");
        item.addProperty("plannerType", "guide_line_dual_side");

        CityStructureArrayLayoutLoopPlanner.ExpansionCandidateSetResult planned = planner.planExpansionCandidates(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), created.loopState(), request,
                CityStructureEnvelopeFacts.empty());
        assertEquals(3, planned.candidateSet().getAsJsonArray("arrayCandidates").size());
        assertEquals(0, created.loopState().get("iteration").getAsInt());
        assertEquals(0, created.loopState().getAsJsonArray("arrayAnchors").size());
        assertExpansionCandidatesAvoidManor(planned.candidateSet(), new BlockBounds(-48, -48, 48, 48));

        JsonObject candidate = planned.candidateSet().getAsJsonArray("arrayCandidates").get(0).getAsJsonObject();
        CityStructureArrayLayoutLoopPlanner.ExpansionSelectionResult selected = planner.selectExpansionCandidate(
                fixture.review(), created.loopState(), planned.candidateSet(), candidate.get("candidateId").getAsString(),
                false, "AI 选择东侧沿线两侧布局");
        assertEquals(1, selected.loopState().get("iteration").getAsInt());
        assertEquals(2, selected.loopState().getAsJsonArray("arrayAnchors").size());
        assertNoOccupiedOverlap(selected.loopState().getAsJsonArray("occupiedEnvelopes"));
    }

    @Test
    void v04GlobalNewFunctionalAreaSearchRequiresExplicitPatchSelectionAndHardFailsWhenNoCapacity() throws Exception {
        Fixture fixture = fixture();
        CityLandformReviewPackage globalReview = reviewWithGlobalPatches(fixture.review());
        CityStructureArrayLayoutLoopPlanner planner = new CityStructureArrayLayoutLoopPlanner();
        CityStructureArrayLayoutLoopPlanner.CreateResult created = planner.create(
                fixture.baseDir(), globalReview, fixture.terraSenseSource(), arrayLayoutPlanV04(),
                CityStructureEnvelopeFacts.empty(), new JsonObject(), new JsonObject());
        JsonObject search = globalExpansionRequest();

        CityStructureArrayLayoutLoopPlanner.ExpansionSpaceResult space = planner.queryExpansionSpace(
                globalReview, created.loopState(), search);
        JsonObject searchResult = space.expansionSpace();
        assertEquals("explicit_global_new_functional_area", searchResult.get("searchScope").getAsString());
        assertTrue(searchResult.get("selectedGlobalPatchRequired").getAsBoolean());
        JsonArray globalCandidates = searchResult.getAsJsonArray("globalPatchCandidates");
        assertEquals("global_large", globalCandidates.get(0).getAsJsonObject().get("patchRef").getAsString());
        assertTrue(globalCandidates.get(0).getAsJsonObject().get("available").getAsBoolean());
        assertTrue(globalCandidates.get(0).getAsJsonObject().has("expansionEntryPoint"));

        JsonObject unselectedPlan = search.deepCopy();
        unselectedPlan.add("nextArrayLayoutPlanItem", globalCompoundItem());
        IllegalArgumentException selectionRequired = assertThrows(IllegalArgumentException.class,
                () -> planner.planExpansionCandidates(fixture.baseDir(), globalReview, fixture.terraSenseSource(),
                        created.loopState(), unselectedPlan, CityStructureEnvelopeFacts.empty()));
        assertTrue(selectionRequired.getMessage().contains("D4_ARRAY_LAYOUT_GLOBAL_PATCH_SELECTION_REQUIRED"));

        JsonObject selectedPlan = unselectedPlan.deepCopy();
        selectedPlan.addProperty("selectedGlobalPatchRef", "global_large");
        CityStructureArrayLayoutLoopPlanner.ExpansionCandidateSetResult planned = planner.planExpansionCandidates(
                fixture.baseDir(), globalReview, fixture.terraSenseSource(), created.loopState(), selectedPlan,
                CityStructureEnvelopeFacts.empty());
        assertEquals(3, planned.candidateSet().getAsJsonArray("arrayCandidates").size());
        assertEquals(0, created.loopState().get("iteration").getAsInt(),
                "global candidate planning must remain tentative");
        JsonObject selectedCandidate = planned.candidateSet().getAsJsonArray("arrayCandidates").get(0).getAsJsonObject();
        assertTrue(selectedCandidate.get("newFunctionalArea").getAsBoolean());
        assertEquals("global_large", selectedCandidate.get("selectedGlobalPatchRef").getAsString());

        CityStructureArrayLayoutLoopPlanner.ExpansionSelectionResult committed = planner.selectExpansionCandidate(
                globalReview, created.loopState(), planned.candidateSet(),
                selectedCandidate.get("candidateId").getAsString(), false, "AI 选择新功能区 patch");
        assertEquals(1, committed.loopState().get("iteration").getAsInt());
        assertNoOccupiedOverlap(committed.loopState().getAsJsonArray("occupiedEnvelopes"));

        JsonObject fullyOccupied = JsonParser.parseString("""
                {"anchors":[{"anchorId":"blocked_patch","collisionEnvelope":{"minX":-1000,"minZ":-1000,"maxX":1000,"maxZ":1000}}]}
                """).getAsJsonObject();
        CityStructureArrayLayoutLoopPlanner.CreateResult blocked = planner.create(
                fixture.baseDir(), globalReview, fixture.terraSenseSource(), arrayLayoutPlanV04(),
                CityStructureEnvelopeFacts.empty(), new JsonObject(), fullyOccupied);
        IllegalArgumentException noCapacity = assertThrows(IllegalArgumentException.class,
                () -> planner.planExpansionCandidates(fixture.baseDir(), globalReview, fixture.terraSenseSource(),
                        blocked.loopState(), selectedPlan, CityStructureEnvelopeFacts.empty()));
        assertTrue(noCapacity.getMessage().contains("D4_ARRAY_LAYOUT_GLOBAL_PATCH_NO_CAPACITY"));
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

    private static void assertExpansionCandidatesAvoidManor(JsonObject candidateSet, BlockBounds manor) {
        for (JsonElement candidateElement : candidateSet.getAsJsonArray("arrayCandidates")) {
            for (JsonElement itemElement : candidateElement.getAsJsonObject().getAsJsonArray("items")) {
                BlockBounds collision = bounds(itemElement.getAsJsonObject()
                        .getAsJsonObject("estimatedCollisionEnvelope"));
                assertFalse(collision.overlaps(manor), "expansion collision must not overlap base occupied");
            }
        }
    }

    private static JsonObject templateCatalog() {
        return JsonParser.parseString("""
                {
                  "schemaVersion":"city_template_catalog.v0.1",
                  "templates":[
                    {
                      "buildingSemantic":"commerce.market_stall",
                      "style":"oak",
                      "templateId":"test:market_stall",
                      "templateRef":"test:market/stall",
                      "contentHash":"market-stall-v1",
                      "variantId":"oak",
                      "rawSize":{"width":9,"height":5,"depth":5},
                      "allowedRotations":["NONE","CLOCKWISE_90"],
                      "allowedMirrors":["NONE"],
                      "roadEntrances":[{"entranceId":"front","x":4,"z":0,"direction":"NORTH"}],
                      "terrainPosePolicy":"grounded",
                      "supportPolicy":"none",
                      "clearanceBlocks":2
                    }
                  ]
                }
                """).getAsJsonObject();
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

    private static JsonObject arrayLayoutPlanV04() {
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_d4_array_layout_plan.v0.4",
                  "planningMode": "array_candidate_selection_loop_v0_4",
                  "cityId": "city_test",
                  "cityScale": "town",
                  "maxArrayPlans": 8,
                  "layoutPlans": []
                }
                """).getAsJsonObject();
    }

    private static JsonObject expansionRequest(CityLandformReviewPackage review) {
        LandformPatchSummary first = review.landformPatches().get(0);
        return JsonParser.parseString("""
                {
                  "focusRef": {"anchorId": "manor_core"},
                  "direction": "east",
                  "targetPatchRef": "%s",
                  "candidateCount": 3,
                  "minCandidateCount": 3,
                  "nextArrayLayoutPlanItem": {
                    "arrayId": "east_residential_theme",
                    "plannerType": "compound_cluster",
                    "role": "residential",
                    "fillPool": [{"structureId": "minecraft:desert_pyramid", "weight": 1}],
                    "countPolicy": {"minCount": 2, "targetCount": 2, "maxCount": 2},
                    "variantSelectionMode": "round_robin"
                  }
                }
                """.formatted(first.landformPatchId())).getAsJsonObject();
    }

    private static JsonObject globalExpansionRequest() {
        return JsonParser.parseString("""
                {
                  "newFunctionalArea": true,
                  "candidateCount": 3,
                  "minCandidateCount": 3
                }
                """).getAsJsonObject();
    }

    private static JsonObject globalCompoundItem() {
        return JsonParser.parseString("""
                {
                  "arrayId": "global_residential_theme",
                  "plannerType": "compound_cluster",
                  "role": "residential",
                  "fillPool": [{"structureId": "minecraft:desert_pyramid", "weight": 1}],
                  "countPolicy": {"minCount": 2, "targetCount": 2, "maxCount": 2},
                  "variantSelectionMode": "round_robin"
                }
                """).getAsJsonObject();
    }

    private static JsonObject outwardCompositeItem() {
        return JsonParser.parseString("""
                {
                  "arrayId": "manor_outward_composite",
                  "plannerType": "composite_array",
                  "subZonePolicy": "grid",
                  "childLayoutPlans": [
                    {
                      "arrayId": "outward_half_ring",
                      "plannerType": "plaza_ring",
                      "targetSubZoneId": "subzone_01",
                      "fillPool": [{"structureId": "minecraft:desert_pyramid", "weight": 1}],
                      "countPolicy": {"minCount": 2, "targetCount": 2, "maxCount": 2},
                      "variantSelectionMode": "round_robin"
                    },
                    {
                      "arrayId": "outward_residential_clusters",
                      "plannerType": "compound_cluster",
                      "targetSubZoneId": "subzone_02",
                      "fillPool": [{"structureId": "minecraft:jungle_pyramid", "weight": 1}],
                      "countPolicy": {"minCount": 2, "targetCount": 2, "maxCount": 2},
                      "variantSelectionMode": "round_robin"
                    }
                  ]
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
                .profile(fixture.baseDir(), fixture.terraSenseSource(), List.of(structureId), 21,
                        (profile, sampleIndex) -> CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex,
                                sampleIndex < 20
                                        ? new BlockBounds(-3, -3, 3, 3)
                                        : new BlockBounds(-90, -90, 90, 90),
                                1, "fixed_config_hash", "pack_hash"));
        JsonObject factsJson = result.structureEnvelopeFacts().deepCopy();
        JsonObject fact = factsJson.getAsJsonArray("structures").get(0).getAsJsonObject();
        fact.add("localEnvelopeP95", JsonParser.parseString("""
                {"minX": -3, "minZ": -3, "maxX": 3, "maxZ": 3}
                """).getAsJsonObject());
        fact.addProperty("stabilityClassification", "stable");
        fact.getAsJsonObject("placementRecommendation").addProperty("allowCompactArray", true);
        fact.getAsJsonObject("placementRecommendation").addProperty("collisionEnvelopeSource",
                "localEnvelopeP95");
        fact.addProperty("requiresReview", false);
        Path factsPath = fixture.baseDir().resolve("wide_diagnostic_structure_envelope_facts.json");
        Files.writeString(factsPath, CityJson.GSON.toJson(factsJson));
        return CityStructureEnvelopeFacts.load(factsPath);
    }

    private static CityStructureEnvelopeFacts stableD2Facts(Fixture fixture, String structureId,
                                                            int halfExtent) throws Exception {
        return stableD2Facts(fixture, Map.of(structureId, halfExtent));
    }

    private static CityStructureEnvelopeFacts stableD2Facts(Fixture fixture,
                                                            Map<String, Integer> halfExtents) throws Exception {
        CityStructureEnvelopeProfiler.Result result = new CityStructureEnvelopeProfiler()
                .profile(fixture.baseDir(), fixture.terraSenseSource(), new ArrayList<>(halfExtents.keySet()), 16,
                        (profile, sampleIndex) -> CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex,
                                new BlockBounds(-halfExtents.get(profile.structureId()),
                                        -halfExtents.get(profile.structureId()), halfExtents.get(profile.structureId()),
                                        halfExtents.get(profile.structureId())),
                                1, "fixed_config_hash", "pack_hash"));
        JsonObject factsJson = result.structureEnvelopeFacts().deepCopy();
        for (JsonElement element : factsJson.getAsJsonArray("structures")) {
            JsonObject fact = element.getAsJsonObject();
            int halfExtent = halfExtents.get(fact.get("structureId").getAsString());
            JsonObject bounds = JsonParser.parseString("""
                    {"minX":%d,"minZ":%d,"maxX":%d,"maxZ":%d}
                    """.formatted(-halfExtent, -halfExtent, halfExtent, halfExtent)).getAsJsonObject();
            fact.add("localEnvelopeP95", bounds.deepCopy());
            fact.add("stableMaxEnvelope", bounds.deepCopy());
            fact.addProperty("stabilityClassification", "stable");
            fact.getAsJsonObject("placementRecommendation").addProperty("allowCompactArray", true);
            fact.getAsJsonObject("placementRecommendation").addProperty("collisionEnvelopeSource",
                    "stableMaxEnvelope");
            fact.addProperty("requiresReview", false);
        }
        Path factsPath = fixture.baseDir().resolve("stable_d2_structure_envelope_facts.json");
        Files.writeString(factsPath, CityJson.GSON.toJson(factsJson));
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

    private static CityLandformReviewPackage reviewWithGlobalPatches(CityLandformReviewPackage review) {
        LandformPatchSummary template = review.landformPatches().get(0);
        return new CityLandformReviewPackage(review.schemaVersion(), review.cityId(), review.grid(),
                review.targetScale(), review.reviewMapImage(), review.legend(), List.of(
                LandformPatchSummary.fromGisPatch(
                        patch("global_large", LandformType.PLAIN, -250, -250, -50, -50),
                        "global_large", "global_large", template.areaClass(), List.of()),
                LandformPatchSummary.fromGisPatch(
                        patch("global_small", LandformType.PLAIN, 80, 80, 120, 120),
                        "global_small", "global_small", template.areaClass(), List.of())),
                review.planningContext(), review.aiPromptContext(), review.debugRefs());
    }

    private static CityLandformReviewPackage reviewWithSplitPatches(CityLandformReviewPackage review) {
        LandformPatchSummary template = review.landformPatches().get(0);
        return new CityLandformReviewPackage(review.schemaVersion(), review.cityId(), review.grid(),
                review.targetScale(), review.reviewMapImage(), review.legend(), List.of(
                LandformPatchSummary.fromGisPatch(
                        patch("frontier_west", LandformType.PLAIN, -320, -320, 100, 320),
                        "frontier_west", "frontier_west", template.areaClass(), List.of()),
                LandformPatchSummary.fromGisPatch(
                        patch("frontier_east", LandformType.PLAIN, 100, -320, 320, 320),
                        "frontier_east", "frontier_east", template.areaClass(), List.of())),
                review.planningContext(), review.aiPromptContext(), review.debugRefs());
    }

    private static CityLandformReviewPackage reviewWithWaterFrontier(CityLandformReviewPackage review) {
        LandformPatchSummary template = review.landformPatches().get(0);
        return new CityLandformReviewPackage(review.schemaVersion(), review.cityId(), review.grid(),
                review.targetScale(), review.reviewMapImage(), review.legend(), List.of(
                LandformPatchSummary.fromGisPatch(
                        patch("water_frontier", LandformType.WATER, -320, -320, 100, 320),
                        "water_frontier", "water_frontier", template.areaClass(), List.of()),
                LandformPatchSummary.fromGisPatch(
                        patch("ground_beyond_water", LandformType.PLAIN, 100, -320, 320, 320),
                        "ground_beyond_water", "ground_beyond_water", template.areaClass(), List.of())),
                review.planningContext(), review.aiPromptContext(), review.debugRefs());
    }

    private static CityLandformReviewPackage reviewWithNearWaterGentleSlope(CityLandformReviewPackage review) {
        LandformPatchSummary template = review.landformPatches().get(0);
        LandformPatchSummary slope = LandformPatchSummary.fromGisPatch(
                        patch("near_water_slope", LandformType.SLOPE, -320, -320, 320, 320),
                        "near_water_slope", "near_water_slope", template.areaClass(), List.of())
                .withTags(List.of("gentle", "low_confidence"), List.of("near_water"));
        return new CityLandformReviewPackage(review.schemaVersion(), review.cityId(), review.grid(),
                review.targetScale(), review.reviewMapImage(), review.legend(), List.of(slope),
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

