package com.rinsing.geomantia.systems.city;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityReservationMaskPlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureAnchorPlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureAnchorCandidatePlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureEnvelopeFacts;
import com.rinsing.geomantia.systems.city.application.CityStructureEnvelopeProfiler;
import com.rinsing.geomantia.systems.city.application.CityStructureMaterializationPlanner;
import com.rinsing.geomantia.systems.city.application.CityWallPlanner;
import com.rinsing.geomantia.systems.city.application.CityWallReservationPlanner;
import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.CitySiteContext;
import com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityReservationMaskRegistry;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.landform.PatchFlag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CityStructureLandingFlowTest {
    @Test
    void d4BuildsStructureAnchorMapWithFixedAndJigsawReservationEnvelope() throws Exception {
        Fixture fixture = fixture();
        CityStructureAnchorPlanner.Result result = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), anchorPlan(fixture.review()));

        JsonObject anchorMap = result.structureAnchorMap();
        assertTrue(result.asJson().get("ok").getAsBoolean());
        assertEquals(2, anchorMap.getAsJsonArray("anchors").size());

        JsonObject fixed = anchorMap.getAsJsonArray("anchors").get(0).getAsJsonObject();
        JsonObject jigsaw = anchorMap.getAsJsonArray("anchors").get(1).getAsJsonObject();
        assertEquals("fixedFootprint+clearance", fixed.get("reservedEnvelopePolicy").getAsString());
        assertEquals("startFootprint+jigsawMaxExpansionRadius+clearance+roadAccessMargin",
                jigsaw.get("reservedEnvelopePolicy").getAsString());
        assertEquals(8, fixed.get("reservedEnvelopeRadiusBlocks").getAsInt());
        assertEquals(78, jigsaw.get("reservedEnvelopeRadiusBlocks").getAsInt());
        assertEquals("function.village", jigsaw.getAsJsonArray("functionTerms").get(0).getAsString());
    }

    @Test
    void envelopeProfilerBuildsPercentileFactsAndD4ConsumesThem() throws Exception {
        Fixture fixture = fixture();
        CityStructureEnvelopeProfiler.Result factsResult = new CityStructureEnvelopeProfiler()
                .profile(fixture.baseDir(), fixture.terraSenseSource(), List.of("minecraft:village_plains"), 20,
                        (profile, sampleIndex) -> CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex,
                                new BlockBounds(-10 - sampleIndex, -12, 20 + sampleIndex, 24),
                                4 + sampleIndex,
                                "config_hash",
                                "pack_hash"));
        JsonObject fact = factsResult.structureEnvelopeFacts().getAsJsonArray("structures")
                .get(0).getAsJsonObject();
        assertTrue(fact.has("generationConfigHash"));
        assertEquals(20, fact.getAsJsonArray("validSamples").size());
        assertEquals(20, fact.getAsJsonArray("bboxGroups").size());
        assertTrue(fact.getAsJsonArray("validSamples").get(0).getAsJsonObject().has("bboxGroupKey"));
        Path factsPath = fixture.baseDir().resolve("structure_envelope_facts.json");
        Files.writeString(factsPath, CityJson.GSON.toJson(factsResult.structureEnvelopeFacts()));

        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), anchorPlan(fixture.review()),
                        CityStructureEnvelopeFacts.load(factsPath))
                .structureAnchorMap();

        JsonObject jigsaw = anchorMap.getAsJsonArray("anchors").get(1).getAsJsonObject();
        assertEquals("structureEnvelopeFacts:fixedDepthP95+clearance/fixedDepthP99+vegetationMargin",
                jigsaw.get("reservedEnvelopePolicy").getAsString());
        assertEquals("fixed_depth_statistics", jigsaw.get("envelopeMode").getAsString());
        assertTrue(jigsaw.has("maskEnvelope"));
        assertTrue(jigsaw.has("safetyEnvelope"));
        assertTrue(jigsaw.has("structureEnvelopeFact"));
    }

    @Test
    void d4UsesDominantFixedBBoxGroupWithSmallClearance() throws Exception {
        Fixture fixture = fixture();
        CityStructureEnvelopeProfiler.Result factsResult = new CityStructureEnvelopeProfiler()
                .profile(fixture.baseDir(), fixture.terraSenseSource(), List.of("minecraft:desert_pyramid"), 6,
                        (profile, sampleIndex) -> {
                            BlockBounds dominant = new BlockBounds(-4, -5, 15, 6);
                            BlockBounds rotated = new BlockBounds(-7, -2, 4, 17);
                            return CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex,
                                    sampleIndex < 4 ? dominant : rotated, 1,
                                    "fixed_config_hash", "pack_hash");
                        });
        Path factsPath = fixture.baseDir().resolve("fixed_structure_envelope_facts.json");
        Files.writeString(factsPath, CityJson.GSON.toJson(factsResult.structureEnvelopeFacts()));

        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), singleAnchorPlan(fixture.review()),
                        CityStructureEnvelopeFacts.load(factsPath))
                .structureAnchorMap();

        JsonObject fixed = anchorMap.getAsJsonArray("anchors").get(0).getAsJsonObject();
        JsonObject group = fixed.getAsJsonObject("selectedEnvelopeGroup");
        JsonObject collision = fixed.getAsJsonObject("collisionEnvelope");
        JsonObject anchorBlock = fixed.getAsJsonObject("anchorBlock");
        int originX = Math.floorDiv(anchorBlock.get("x").getAsInt(), 16) * 16;
        int originZ = Math.floorDiv(anchorBlock.get("z").getAsInt(), 16) * 16;

        assertEquals("fixed_bbox_group", fixed.get("envelopeMode").getAsString());
        assertEquals("structureEnvelopeFacts:fixedBBoxGroup+smallClearance",
                fixed.get("reservedEnvelopePolicy").getAsString());
        assertEquals(4, fixed.get("clearanceBlocks").getAsInt());
        assertEquals(4, fixed.get("smallClearanceBlocks").getAsInt());
        assertEquals(4, group.get("sampleCount").getAsInt());
        assertEquals(originX - 8, collision.get("minX").getAsInt());
        assertEquals(originZ - 9, collision.get("minZ").getAsInt());
        assertEquals(originX + 19, collision.get("maxX").getAsInt());
        assertEquals(originZ + 10, collision.get("maxZ").getAsInt());
    }

    @Test
    void d4RejectsUnknownFixedBBoxGroupKey() throws Exception {
        Fixture fixture = fixture();
        CityStructureEnvelopeProfiler.Result factsResult = new CityStructureEnvelopeProfiler()
                .profile(fixture.baseDir(), fixture.terraSenseSource(), List.of("minecraft:desert_pyramid"), 2,
                        (profile, sampleIndex) -> CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex,
                                new BlockBounds(-4, -5, 15, 6), 1,
                                "fixed_config_hash", "pack_hash"));
        Path factsPath = fixture.baseDir().resolve("fixed_structure_envelope_facts.json");
        Files.writeString(factsPath, CityJson.GSON.toJson(factsResult.structureEnvelopeFacts()));
        JsonObject plan = singleAnchorPlan(fixture.review());
        plan.getAsJsonArray("anchors").get(0).getAsJsonObject()
                .addProperty("envelopeGroupKey", "missing_group");

        JsonObject result = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), plan,
                        CityStructureEnvelopeFacts.load(factsPath))
                .asJson();

        assertFalse(result.get("ok").getAsBoolean());
        assertTrue(result.getAsJsonObject("qualityReport").getAsJsonArray("hardBlocks")
                .toString()
                .contains("requested envelopeGroupKey is not in structure envelope facts"));
    }

    @Test
    void d4RequiresEnvelopeFactsForTrekStructures() throws Exception {
        Fixture fixture = fixture();
        Path catalogPath = fixture.baseDir().resolve("trek_debug_catalog.json");
        Files.writeString(catalogPath, trekDebugCatalog());
        JsonObject source = new JsonObject();
        source.addProperty("schemaVersion", "terrasense_structure_profile_source.v0.1");
        source.addProperty("sourceType", "debug_catalog");
        source.addProperty("catalogMode", "debug");
        source.addProperty("debugCatalogPath", catalogPath.toString());

        JsonObject plan = singleAnchorPlan(fixture.review());
        plan.getAsJsonArray("anchors").get(0).getAsJsonObject()
                .addProperty("structureId", "trek:overworld/medium/farm");
        JsonObject result = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), source, plan)
                .asJson();

        assertFalse(result.get("ok").getAsBoolean());
        assertTrue(result.getAsJsonObject("qualityReport").getAsJsonArray("hardBlocks")
                .toString()
                .contains("structure envelope facts are required"));
    }

    @Test
    void d4RejectsLegacyFunctionZonePayload() throws Exception {
        Fixture fixture = fixture();
        JsonObject legacy = anchorPlan(fixture.review());
        legacy.addProperty("functionType", "civic_core");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new CityStructureAnchorPlanner()
                        .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), legacy));
        assertTrue(ex.getMessage().contains("LEGACY_CITY_FUNCTION_ZONE_FLOW_REMOVED"));
    }

    @Test
    void d4CandidatePlannerBuildsSafeCandidatesAndSelectionAnchorPlan() throws Exception {
        Fixture fixture = fixture();
        CityStructureAnchorCandidatePlanner planner = new CityStructureAnchorCandidatePlanner();

        CityStructureAnchorCandidatePlanner.Result result = planner.plan(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                designSlotPlan(fixture.review()), CityStructureEnvelopeFacts.empty());

        JsonObject candidateSet = result.anchorCandidateSet();
        assertTrue(result.asJson().get("ok").getAsBoolean());
        assertEquals("city_d4_anchor_candidate_set.v0.1", candidateSet.get("schemaVersion").getAsString());
        assertEquals(2, candidateSet.getAsJsonArray("slotCandidates").size());
        JsonObject firstSlot = candidateSet.getAsJsonArray("slotCandidates").get(0).getAsJsonObject();
        assertFalse(firstSlot.getAsJsonArray("candidates").isEmpty());
        JsonObject firstCandidate = firstSlot.getAsJsonArray("candidates").get(0).getAsJsonObject();
        Set<String> candidateIds = new HashSet<>();
        for (int i = 0; i < firstSlot.getAsJsonArray("candidates").size(); i++) {
            String candidateId = firstSlot.getAsJsonArray("candidates").get(i).getAsJsonObject()
                    .get("candidateId").getAsString();
            assertTrue(candidateIds.add(candidateId), "duplicate candidateId: " + candidateId);
        }
        assertTrue(firstCandidate.has("estimatedCollisionEnvelope"));
        assertTrue(firstCandidate.has("scoreBreakdown"));
        assertEquals("fallback_fixed_footprint", firstCandidate.get("envelopeMode").getAsString());

        JsonObject selectionPlan = JsonParser.parseString("""
                {
                  "schemaVersion": "city_d4_anchor_selection_plan.v0.1",
                  "cityId": "city_test",
                  "selectedCandidates": [
                    {
                      "slotId": "admin_core",
                      "candidateId": "%s",
                      "anchorId": "admin_core_01",
                      "selectionReason": "test"
                    }
                  ]
                }
                """.formatted(firstCandidate.get("candidateId").getAsString())).getAsJsonObject();
        JsonObject anchorPlan = planner.select(candidateSet, selectionPlan);

        assertEquals(CityStructureAnchorPlanner.PLAN_SCHEMA, anchorPlan.get("schemaVersion").getAsString());
        JsonObject anchor = anchorPlan.getAsJsonArray("anchors").get(0).getAsJsonObject();
        assertEquals("admin_core_01", anchor.get("anchorId").getAsString());
        assertEquals(firstCandidate.get("structureId").getAsString(), anchor.get("structureId").getAsString());
        assertTrue(anchorPlan.has("candidateSelectionTrace"));
    }

    @Test
    void d4CandidatePlannerAcceptsSingleStructureIdSlot() throws Exception {
        Fixture fixture = fixture();
        JsonObject plan = designSlotPlan(fixture.review());
        JsonObject slot = plan.getAsJsonArray("slots").get(0).getAsJsonObject();
        slot.addProperty("structureId", slot.getAsJsonArray("structureIds").get(0).getAsString());
        slot.remove("structureIds");

        CityStructureAnchorCandidatePlanner.Result result = new CityStructureAnchorCandidatePlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), plan,
                        CityStructureEnvelopeFacts.empty());

        JsonArray candidates = result.anchorCandidateSet().getAsJsonArray("slotCandidates")
                .get(0).getAsJsonObject().getAsJsonArray("candidates");
        assertFalse(candidates.isEmpty());
        assertEquals("minecraft:desert_pyramid",
                candidates.get(0).getAsJsonObject().get("structureId").getAsString());
    }

    @Test
    void d4CandidatePlannerRejectsLegacyPayload() throws Exception {
        Fixture fixture = fixture();
        JsonObject plan = designSlotPlan(fixture.review());
        plan.addProperty("functionType", "civic_core");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new CityStructureAnchorCandidatePlanner()
                        .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                                plan, CityStructureEnvelopeFacts.empty()));

        assertTrue(ex.getMessage().contains("LEGACY_CITY_FUNCTION_ZONE_FLOW_REMOVED"));
    }

    @Test
    void d4CandidateSessionPlansOneSlotThenFreezesSelectionForNextSlot() throws Exception {
        Fixture fixture = fixture();
        CityStructureAnchorCandidatePlanner planner = new CityStructureAnchorCandidatePlanner();
        JsonObject design = designSlotPlan(fixture.review());
        CityStructureAnchorCandidatePlanner.SessionResult sessionResult = planner.createSession(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), design, "session_test");

        assertEquals("admin_core", sessionResult.session().get("currentSlotId").getAsString());
        assertEquals(0, sessionResult.session().getAsJsonArray("selectedAnchors").size());

        CityStructureAnchorCandidatePlanner.NextCandidateResult first = planner.planNext(
                fixture.baseDir(), fixture.review(), sessionResult.session(), CityStructureEnvelopeFacts.empty());
        JsonObject firstSlot = first.slotCandidateSet().getAsJsonArray("slotCandidates").get(0).getAsJsonObject();
        assertEquals("admin_core", firstSlot.get("slotId").getAsString());
        JsonObject firstCandidate = firstSlot.getAsJsonArray("candidates").get(0).getAsJsonObject();

        CityStructureAnchorCandidatePlanner.SelectionResult selected = planner.selectSession(
                first.session(), first.slotCandidateSet(), "admin_core",
                firstCandidate.get("candidateId").getAsString(), "admin_core_01", "test", false);

        assertEquals(1, selected.session().getAsJsonArray("selectedAnchors").size());
        assertEquals(1, selected.session().getAsJsonArray("occupiedEnvelopes").size());
        JsonObject occupied = selected.session().getAsJsonArray("occupiedEnvelopes")
                .get(0).getAsJsonObject();
        assertEquals("estimated_safety", occupied.get("envelopeType").getAsString());
        assertTrue(occupied.has("estimatedCollisionEnvelope"));
        assertTrue(occupied.has("estimatedSafetyEnvelope"));
        assertEquals("residential_01", selected.session().get("currentSlotId").getAsString());
        assertEquals("deferred_to_d6",
                selected.quickPreflightReport().get("status").getAsString());

        CityStructureAnchorCandidatePlanner.NextCandidateResult second = planner.planNext(
                fixture.baseDir(), fixture.review(), selected.session(), CityStructureEnvelopeFacts.empty());
        JsonObject secondSlot = second.slotCandidateSet().getAsJsonArray("slotCandidates").get(0).getAsJsonObject();
        assertEquals("residential_01", secondSlot.get("slotId").getAsString());
        assertEquals(1, second.slotCandidateSet().getAsJsonArray("occupiedEnvelopes").size());
        assertTrue(second.slotCandidateSet().has("selectedAnchors"));
        assertTrue(second.session().getAsJsonObject("timing").has("stepTimings"));
    }

    @Test
    void d4CandidateSessionRejectsOutOfOrderSelectionAndIncompleteFinalize() throws Exception {
        Fixture fixture = fixture();
        CityStructureAnchorCandidatePlanner planner = new CityStructureAnchorCandidatePlanner();
        CityStructureAnchorCandidatePlanner.SessionResult sessionResult = planner.createSession(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                designSlotPlan(fixture.review()), "session_test");
        CityStructureAnchorCandidatePlanner.NextCandidateResult first = planner.planNext(
                fixture.baseDir(), fixture.review(), sessionResult.session(), CityStructureEnvelopeFacts.empty());
        JsonObject firstCandidate = first.slotCandidateSet().getAsJsonArray("slotCandidates")
                .get(0).getAsJsonObject().getAsJsonArray("candidates").get(0).getAsJsonObject();

        IllegalArgumentException order = assertThrows(IllegalArgumentException.class,
                () -> planner.selectSession(first.session(), first.slotCandidateSet(), "residential_01",
                        firstCandidate.get("candidateId").getAsString(), "bad", "bad", false));
        assertTrue(order.getMessage().contains("D4_SLOT_ORDER_VIOLATION"));

        IllegalArgumentException finalize = assertThrows(IllegalArgumentException.class,
                () -> planner.finalizeSession(first.session()));
        assertTrue(finalize.getMessage().contains("D4_SESSION_NOT_FINALIZABLE"));
    }

    @Test
    void d4CandidateSessionFinalizesStandardAnchorPlan() throws Exception {
        Fixture fixture = fixture();
        CityStructureAnchorCandidatePlanner planner = new CityStructureAnchorCandidatePlanner();
        JsonObject session = planner.createSession(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                designSlotPlan(fixture.review()), "session_test").session();

        for (String slotId : List.of("admin_core", "residential_01")) {
            CityStructureAnchorCandidatePlanner.NextCandidateResult next = planner.planNext(
                    fixture.baseDir(), fixture.review(), session, CityStructureEnvelopeFacts.empty());
            JsonObject candidate = next.slotCandidateSet().getAsJsonArray("slotCandidates")
                    .get(0).getAsJsonObject().getAsJsonArray("candidates").get(0).getAsJsonObject();
            session = planner.selectSession(next.session(), next.slotCandidateSet(), slotId,
                    candidate.get("candidateId").getAsString(), slotId + "_anchor", "test", false).session();
        }

        CityStructureAnchorCandidatePlanner.FinalizeResult finalized = planner.finalizeSession(session);
        JsonObject plan = finalized.structureAnchorPlan();
        assertEquals(CityStructureAnchorPlanner.PLAN_SCHEMA, plan.get("schemaVersion").getAsString());
        assertEquals(2, plan.getAsJsonArray("anchors").size());
        assertTrue(plan.has("candidateSelectionTrace"));
        assertEquals("city_d4_design_time_report.v0.2",
                finalized.designTimeReport().get("schemaVersion").getAsString());
    }

    @Test
    void d4OfficialProfileRequiresApprovedReviewState() throws Exception {
        Fixture fixture = fixture();
        Path profilePath = fixture.baseDir().resolve("StructureProfile.jsonl");
        JsonObject profile = JsonParser.parseString("""
                {
                  "structureId": "minecraft:desert_pyramid",
                  "profileType": "single",
                  "footprintMode": "fixed_footprint",
                  "functionTerms": ["function.landmark"],
                  "qualityTerms": ["quality.usable"],
                  "fixedFootprint": {"widthBlocks": 20, "depthBlocks": 12, "heightBlocks": 10}
                }
                """).getAsJsonObject();
        Files.writeString(profilePath, profile + "\n");
        JsonObject source = new JsonObject();
        source.addProperty("schemaVersion", "terrasense_structure_profile_source.v0.1");
        source.addProperty("sourceType", "structure_profile_jsonl");
        source.addProperty("catalogMode", "official");
        source.addProperty("profilePath", profilePath.toString());

        JsonObject result = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), source, singleAnchorPlan(fixture.review()))
                .asJson();

        assertFalse(result.get("ok").getAsBoolean());
        assertTrue(result.getAsJsonObject("qualityReport").getAsJsonArray("hardBlocks")
                .toString()
                .contains("approved TerraSense catalog"));
    }

    @Test
    void d5ReservationMaskCoversStructureEnvelopeWithoutFixedRoadAccess() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), anchorPlan(fixture.review()))
                .structureAnchorMap();

        CityReservationMaskPlanner.Result result = new CityReservationMaskPlanner()
                .plan(fixture.context(), anchorMap);

        JsonObject mask = result.reservationMaskPlan();
        assertTrue(result.asJson().get("ok").getAsBoolean());
        assertEquals(2, mask.getAsJsonArray("noVanillaStructureMask").size());
        assertEquals(2, mask.getAsJsonArray("noVegetationMask").size());
        assertTrue(mask.getAsJsonObject("hookRequirements").get("required").getAsBoolean());
        assertTrue(mask.get("requiresLockedMaterializationPlan").getAsBoolean());
        assertEquals("d7_after_worldgen_ledger", mask.get("roadPlanningStage").getAsString());
        String operations = result.buildOperationPlan().getAsJsonArray("operations").toString();
        assertFalse(operations.contains("clearVegetation"));
        assertFalse(operations.contains("surfaceFill"));
    }

    @Test
    void wallReservationAddsNonRectangularCorridorAndRoadMaskCutsGate() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), anchorPlan(fixture.review()))
                .structureAnchorMap();
        JsonObject reservation = new CityWallReservationPlanner().plan(
                fixture.review(), anchorMap, "v2", 24, 15, 4);

        assertEquals("city_wall_reservation_plan.v0.2", reservation.get("schemaVersion").getAsString());
        assertEquals("d3_patch_member_cell_outer_boundary", reservation.get("boundarySource").getAsString());
        assertFalse(reservation.getAsJsonArray("wallCenterline").isEmpty());
        assertFalse(reservation.getAsJsonArray("wallCorridorMask").isEmpty());

        JsonObject mask = new CityReservationMaskPlanner()
                .plan(fixture.context(), anchorMap, reservation)
                .reservationMaskPlan();
        assertTrue(mask.getAsJsonArray("noVegetationMask").toString().contains("wall_reservation_corridor"));
        assertTrue(mask.getAsJsonArray("noVanillaStructureMask").toString().contains("wall_reservation_corridor"));

        JsonObject firstLine = reservation.getAsJsonArray("wallCenterline").get(0).getAsJsonObject();
        JsonObject lineBounds = firstLine.getAsJsonObject("blockBounds");
        int roadX = (lineBounds.get("minX").getAsInt() + lineBounds.get("maxX").getAsInt()) / 2;
        int roadZ = (lineBounds.get("minZ").getAsInt() + lineBounds.get("maxZ").getAsInt()) / 2;
        JsonObject actualRoadMask = JsonParser.parseString("""
                {
                  "schemaVersion": "city_actual_road_mask.v0.2",
                  "cityId": "city_test",
                  "status": "observed",
                  "roadMask": [
                    {
                      "maskId": "road_0",
                      "maskType": "actual_road",
                      "blockBounds": {"minX": %d, "minZ": %d, "maxX": %d, "maxZ": %d}
                    }
                  ]
                }
                """.formatted(roadX, roadZ, roadX, roadZ)).getAsJsonObject();
        JsonObject ledger = JsonParser.parseString("""
                {
                  "schemaVersion": "city_placed_structure_ledger.v0.1",
                  "cityId": "city_test",
                  "placedStructures": [
                    {"anchorId": "a", "actualFootprint": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8}}
                  ]
                }
                """).getAsJsonObject();

        JsonObject wallPlan = new CityWallPlanner().planV2(ledger, reservation, actualRoadMask, 9, 2, 8, 7);
        assertEquals("city_wall_plan.v0.2", wallPlan.get("schemaVersion").getAsString());
        assertFalse(wallPlan.getAsJsonArray("generatedGates").isEmpty());
        assertTrue(wallPlan.getAsJsonArray("wallSegments").toString().contains("WALL_GATE_FROM_ROAD"));
    }

    @Test
    void wallReservationV3BuildsStructureSeededDomainHullAndMaskContribution() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), anchorPlan(fixture.review()))
                .structureAnchorMap();

        JsonObject reservation = new CityWallReservationPlanner().plan(
                fixture.review(), anchorMap, "v3", 24, 15, 4,
                new CityWallReservationPlanner.V3Options(24, 2, 64, 0.6));

        assertEquals("city_wall_reservation_plan.v0.3", reservation.get("schemaVersion").getAsString());
        assertEquals("structure_seeded_patch_region_hull", reservation.get("boundarySource").getAsString());
        assertFalse(reservation.getAsJsonArray("seedPatches").isEmpty());
        assertFalse(reservation.getAsJsonArray("cityDomainMask").isEmpty());
        assertFalse(reservation.getAsJsonArray("wallCenterline").isEmpty());
        assertTrue(reservation.getAsJsonObject("domainCleanupReport").has("filledCellCount"));

        JsonObject mask = new CityReservationMaskPlanner()
                .plan(fixture.context(), anchorMap, reservation)
                .reservationMaskPlan();
        assertTrue(mask.getAsJsonArray("noVegetationMask").toString().contains("wall_reservation_corridor"));
        assertTrue(mask.getAsJsonArray("noVanillaStructureMask").toString().contains("wall_reservation_corridor"));
    }

    @Test
    void wallPlannerV3ClustersExternalRoadGatesAndIgnoresInsideRoads() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), singleAnchorPlan(fixture.review()))
                .structureAnchorMap();
        JsonObject reservation = new CityWallReservationPlanner().plan(
                fixture.review(), anchorMap, "v3", 24, 15, 4,
                new CityWallReservationPlanner.V3Options(24, 2, 64, 0.6));
        JsonObject line = reservation.getAsJsonArray("wallCenterline").get(0).getAsJsonObject();
        BlockBounds lineBounds = bounds(line.getAsJsonObject("blockBounds"));
        BlockBounds domain = bounds(reservation.getAsJsonArray("cityDomainMask").get(0)
                .getAsJsonObject().getAsJsonObject("blockBounds"));
        int roadX = lineBounds.center().x();
        int roadZ = lineBounds.center().z();
        BlockBounds externalRoad = lineBounds.widthBlocks() >= lineBounds.heightBlocks()
                ? new BlockBounds(roadX - 1, lineBounds.minZ() - 2, roadX + 1, lineBounds.maxZ() + 2)
                : new BlockBounds(lineBounds.minX() - 2, roadZ - 1, lineBounds.maxX() + 2, roadZ + 1);
        int insideX = domain.center().x();
        int insideZ = domain.center().z();
        JsonObject actualRoadMask = JsonParser.parseString("""
                {
                  "schemaVersion": "city_actual_road_mask.v0.2",
                  "cityId": "city_test",
                  "status": "observed",
                  "roadMask": [
                    {"maskId": "road_external_a", "maskType": "actual_road", "blockBounds": {"minX": %d, "minZ": %d, "maxX": %d, "maxZ": %d}},
                    {"maskId": "road_external_b", "maskType": "actual_road", "blockBounds": {"minX": %d, "minZ": %d, "maxX": %d, "maxZ": %d}},
                    {"maskId": "road_inside_a", "maskType": "actual_road", "blockBounds": {"minX": %d, "minZ": %d, "maxX": %d, "maxZ": %d}},
                    {"maskId": "road_inside_b", "maskType": "actual_road", "blockBounds": {"minX": %d, "minZ": %d, "maxX": %d, "maxZ": %d}}
                  ]
                }
                """.formatted(
                externalRoad.minX(), externalRoad.minZ(), externalRoad.maxX(), externalRoad.maxZ(),
                externalRoad.minX() + 1, externalRoad.minZ(), externalRoad.maxX() + 1, externalRoad.maxZ(),
                insideX, insideZ, insideX, insideZ,
                insideX + 16, insideZ, insideX + 16, insideZ)).getAsJsonObject();
        JsonObject ledger = JsonParser.parseString("""
                {
                  "schemaVersion": "city_placed_structure_ledger.v0.1",
                  "cityId": "city_test",
                  "placedStructures": [
                    {"anchorId": "a", "actualFootprint": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8}}
                  ]
                }
                """).getAsJsonObject();

        JsonObject wallPlan = new CityWallPlanner().planV3(ledger, reservation, actualRoadMask, 9, 2, 8, 7,
                new CityWallPlanner.V3Options(24, 5));

        assertEquals("city_wall_plan.v0.3", wallPlan.get("schemaVersion").getAsString());
        assertEquals("v3", wallPlan.get("wallVersion").getAsString());
        assertEquals("structure_seeded_patch_region_hull", wallPlan.get("wallBoundaryMode").getAsString());
        assertFalse(wallPlan.getAsJsonArray("gateClusters").isEmpty());
        assertTrue(wallPlan.getAsJsonArray("classifiedRoadComponents").toString().contains("insideRoad"));
        assertTrue(wallPlan.getAsJsonArray("insideRoadIgnoredIntersections").toString().contains("insideRoad"));
        assertTrue(wallPlan.getAsJsonArray("wallSegments").toString().contains("DOMAIN_HULL_WALL_SEGMENT"));
        assertEquals("v3", wallPlan.getAsJsonObject("terrainFitPolicy").get("policyVersion").getAsString());
    }

    @Test
    void wallPlannerV3CanEmitTerrainPolicyV31() {
        JsonObject reservation = JsonParser.parseString("""
                {
                  "schemaVersion": "city_wall_reservation_plan.v0.3",
                  "cityId": "city_test",
                  "wallBounds": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": 32},
                  "cityDomainMask": [
                    {"blockBounds": {"minX": -16, "minZ": -16, "maxX": 16, "maxZ": 16}}
                  ],
                  "wallCenterline": [
                    {"segmentId": "north", "blockBounds": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": -28}}
                  ],
                  "gateCandidateZones": []
                }
                """).getAsJsonObject();
        JsonObject actualRoadMask = JsonParser.parseString("""
                {"schemaVersion":"city_actual_road_mask.v0.1","cityId":"city_test","roadMask":[]}
                """).getAsJsonObject();
        JsonObject ledger = JsonParser.parseString("""
                {
                  "schemaVersion": "city_placed_structure_ledger.v0.1",
                  "cityId": "city_test",
                  "placedStructures": [
                    {"anchorId": "a", "actualFootprint": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8}}
                  ]
                }
                """).getAsJsonObject();

        JsonObject wallPlan = new CityWallPlanner().planV3(ledger, reservation, actualRoadMask, 9, 2, 8, 7,
                new CityWallPlanner.V3Options(24, 5, "v3.1", 7, 16, 6, 17, true));

        JsonObject policy = wallPlan.getAsJsonObject("terrainFitPolicy");
        assertEquals("v3.1", wallPlan.get("wallTerrainPolicy").getAsString());
        assertEquals("v3.1", policy.get("policyVersion").getAsString());
        assertEquals(7, policy.get("flatMaxDeltaBlocks").getAsInt());
        assertEquals(16, policy.get("steppedMaxDeltaBlocks").getAsInt());
        assertEquals(6, policy.get("mountainProbeDistanceBlocks").getAsInt());
        assertEquals(17, policy.get("naturalBoundaryMinDeltaBlocks").getAsInt());
        assertTrue(policy.get("embeddedSlopeTower").getAsBoolean());
        assertEquals("low_flat_mid_stepped_high_embedded_or_cliff", policy.get("slopeMode").getAsString());
    }

    @Test
    void wallPlannerV3MarksWallAxisForExecution() {
        JsonObject reservation = JsonParser.parseString("""
                {
                  "schemaVersion": "city_wall_reservation_plan.v0.3",
                  "cityId": "city_test",
                  "wallBounds": {"minX": -40, "minZ": -40, "maxX": 40, "maxZ": 40},
                  "cityDomainMask": [
                    {"blockBounds": {"minX": -16, "minZ": -16, "maxX": 16, "maxZ": 16}}
                  ],
                  "wallCenterline": [
                    {"segmentId": "north", "blockBounds": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": -24}},
                    {"segmentId": "west", "blockBounds": {"minX": -32, "minZ": -32, "maxX": -24, "maxZ": 32}}
                  ],
                  "gateCandidateZones": []
                }
                """).getAsJsonObject();
        JsonObject actualRoadMask = JsonParser.parseString("""
                {"schemaVersion":"city_actual_road_mask.v0.1","cityId":"city_test","roadMask":[]}
                """).getAsJsonObject();
        JsonObject ledger = JsonParser.parseString("""
                {
                  "schemaVersion": "city_placed_structure_ledger.v0.1",
                  "cityId": "city_test",
                  "placedStructures": [
                    {"anchorId": "a", "actualFootprint": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8}}
                  ]
                }
                """).getAsJsonObject();

        JsonObject wallPlan = new CityWallPlanner().planV3(ledger, reservation, actualRoadMask, 9, 2, 8, 7,
                new CityWallPlanner.V3Options(24, 5, "v3.1", 7, 16, 6, 17, true));

        String segments = wallPlan.getAsJsonArray("wallSegments").toString();
        assertTrue(segments.contains("\"wallAxis\":\"X\""));
        assertTrue(segments.contains("\"wallAxis\":\"Z\""));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void wallBackendSplitsTerrainUnitsAlongWallAxis() throws Exception {
        Class<?> axisClass = Class.forName("com.rinsing.geomantia.systems.city.infrastructure.world.CityWallPlacementBackend$Axis");
        Object zAxis = Enum.valueOf((Class<Enum>) axisClass, "Z");
        Method split = Class.forName("com.rinsing.geomantia.systems.city.infrastructure.world.CityWallPlacementBackend")
                .getDeclaredMethod("splitUnits", BlockBounds.class, int.class, axisClass);
        split.setAccessible(true);

        List<BlockBounds> units = (List<BlockBounds>) split.invoke(null, new BlockBounds(-4, 0, 4, 31), 8, zAxis);

        assertEquals(4, units.size());
        for (BlockBounds unit : units) {
            assertEquals(-4, unit.minX());
            assertEquals(4, unit.maxX());
        }
        assertEquals(0, units.get(0).minZ());
        assertEquals(7, units.get(0).maxZ());
        assertEquals(24, units.get(3).minZ());
        assertEquals(31, units.get(3).maxZ());
    }

    @Test
    void d5ActivateWritesPlannedStructureRegistryForWorldgenHook() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), singleAnchorPlan(fixture.review()))
                .structureAnchorMap();
        JsonObject mask = new CityReservationMaskPlanner()
                .plan(fixture.context(), anchorMap)
                .reservationMaskPlan();
        Path serverRoot = Files.createTempDirectory("city-mask-registry");

        JsonObject active = CityReservationMaskRegistry.activate(mask, anchorMap,
                "run_test", fixture.context().cityId(), serverRoot);

        assertEquals(1, active.getAsJsonArray("plannedStructures").size());
        assertTrue(Files.exists(CityReservationMaskRegistry.plannedRegistryPath(serverRoot)));
        JsonObject planned = active.getAsJsonArray("plannedStructures").get(0).getAsJsonObject();
        assertTrue(planned.has("collisionEnvelope"));
        assertTrue(planned.has("maskEnvelope"));
        assertTrue(planned.has("safetyEnvelope"));
        assertTrue(planned.has("envelopeMode"));
        ChunkPos anchorChunk = new ChunkPos(
                planned.getAsJsonObject("anchorChunk").get("x").getAsInt(),
                planned.getAsJsonObject("anchorChunk").get("z").getAsInt());
        assertEquals(1, CityReservationMaskRegistry.plannedStructuresForChunk(anchorChunk).size());
    }

    @Test
    void d5ActiveRegistryLedgerIsScopedToWorldRoot() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), singleAnchorPlan(fixture.review()))
                .structureAnchorMap();
        JsonObject mask = new CityReservationMaskPlanner()
                .plan(fixture.context(), anchorMap)
                .reservationMaskPlan();
        Path worldRootA = Files.createTempDirectory("city-mask-world-a");
        Path worldRootB = Files.createTempDirectory("city-mask-world-b");

        JsonObject activeA = CityReservationMaskRegistry.activate(mask, anchorMap,
                "run_a", fixture.context().cityId(), worldRootA);
        JsonObject plannedJson = activeA.getAsJsonArray("plannedStructures").get(0).getAsJsonObject();
        JsonObject anchorChunkJson = plannedJson.getAsJsonObject("anchorChunk");
        ChunkPos anchorChunk = new ChunkPos(
                anchorChunkJson.get("x").getAsInt(),
                anchorChunkJson.get("z").getAsInt());
        CityReservationMaskRegistry.PlannedStructure plannedA = CityReservationMaskRegistry
                .plannedStructuresForChunk(anchorChunk)
                .get(0);
        CityReservationMaskRegistry.recordWorldgenPlacement(plannedA, plannedA.plannedFootprint(),
                "sig_a", new JsonArray(), anchorChunk,
                "none", "WORLDGEN_PLACEMENT_RECORDED", "test placement");
        assertEquals(1, CityReservationMaskRegistry.ledgerForCity(fixture.context().cityId())
                .getAsJsonArray("placedStructures").size());

        CityReservationMaskRegistry.activate(mask, anchorMap,
                "run_b", fixture.context().cityId(), worldRootB);

        assertTrue(Files.exists(CityReservationMaskRegistry.worldgenLedgerPath(worldRootA)));
        assertTrue(Files.exists(CityReservationMaskRegistry.worldgenLedgerPath(worldRootB)));
        assertEquals(0, CityReservationMaskRegistry.ledgerForCity(fixture.context().cityId())
                .getAsJsonArray("placedStructures").size());
    }

    @Test
    void d6WorldgenPlanDoesNotCallLatePlacementBackend() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), anchorPlan(fixture.review()))
                .structureAnchorMap();
        FakePlacementBackend backend = new FakePlacementBackend("sig", true);

        CityStructureMaterializationPlanner planner = new CityStructureMaterializationPlanner();
        CityStructureMaterializationPlanner.Result dryRun = planner.planWorldgen(anchorMap,
                CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(), backend, null);

        assertEquals("worldgen_time_planned_registry",
                dryRun.structureMaterializationPlan().get("dryRunMode").getAsString());
        assertEquals("registry_structure_start_no_world_mutation",
                dryRun.structureMaterializationPlan().get("preflightMode").getAsString());
        assertEquals(2, dryRun.structureMaterializationPlan().getAsJsonArray("plannedWorldgenStructures").size());
        assertEquals(0, dryRun.structureMaterializationPlan().getAsJsonArray("structures").size());
        assertEquals(0, dryRun.placedStructureLedger().getAsJsonArray("placedStructures").size());
        assertEquals(2, backend.planCalls);
        assertEquals(0, backend.placeCalls);
        JsonObject planned = dryRun.structureMaterializationPlan()
                .getAsJsonArray("plannedWorldgenStructures").get(0).getAsJsonObject();
        assertTrue(dryRun.structureMaterializationPlan().get("locked").getAsBoolean());
        assertTrue(planned.get("locked").getAsBoolean());
        assertTrue(planned.has("lockedActualFootprint"));
        assertTrue(planned.has("lockedCollisionEnvelope"));
        assertTrue(planned.has("lockedBBoxGroupKey"));
        assertTrue(planned.has("expectedStartSignature"));
        assertTrue(planned.has("pieceBoxes"));

        backend.planCalls = 0;
        backend.placeCalls = 0;
        CityStructureMaterializationPlanner.Result recheck = planner.executeWorldgen(
                dryRun.structureMaterializationPlan(), new JsonObject(),
                CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(), true);
        assertEquals(0, recheck.placedStructureLedger().getAsJsonArray("placedStructures").size());
        assertEquals(0, backend.planCalls);
        assertEquals(0, backend.placeCalls);
        assertTrue(recheck.structureMaterializationTrace()
                .getAsJsonObject("waitingSummary")
                .has("WAITING_FOR_WORLDGEN"));
    }

    @Test
    void d6PreflightLocksActualBBoxEvenWhenItDiffersFromD4Envelope() throws Exception {
        Fixture fixture = fixture();
        CityStructureEnvelopeProfiler.Result factsResult = new CityStructureEnvelopeProfiler()
                .profile(fixture.baseDir(), fixture.terraSenseSource(), List.of("minecraft:desert_pyramid"), 2,
                        (profile, sampleIndex) -> CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex,
                                new BlockBounds(-4, -5, 15, 6), 1,
                                "fixed_config_hash", "pack_hash"));
        Path factsPath = fixture.baseDir().resolve("fixed_structure_envelope_facts.json");
        Files.writeString(factsPath, CityJson.GSON.toJson(factsResult.structureEnvelopeFacts()));
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), singleAnchorPlan(fixture.review()),
                        CityStructureEnvelopeFacts.load(factsPath))
                .structureAnchorMap();
        JsonObject anchor = anchorMap.getAsJsonArray("anchors").get(0).getAsJsonObject();
        JsonObject anchorBlock = anchor.getAsJsonObject("anchorBlock");
        int originX = Math.floorDiv(anchorBlock.get("x").getAsInt(), 16) * 16;
        int originZ = Math.floorDiv(anchorBlock.get("z").getAsInt(), 16) * 16;
        BlockBounds actual = new BlockBounds(originX - 4, originZ - 5, originX + 15, originZ + 6);

        CityStructureMaterializationPlanner.Result result = new CityStructureMaterializationPlanner()
                .planWorldgen(anchorMap, CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(),
                        new FakePlacementBackend("sig", true, actual), null);

        JsonObject attempt = result.structureMaterializationTrace()
                .getAsJsonArray("attempts").get(0).getAsJsonObject();
        assertTrue(result.asJson().get("ok").getAsBoolean());
        assertTrue(attempt.get("locked").getAsBoolean());
        assertTrue(attempt.has("actualLocalBounds"));
        assertTrue(attempt.has("actualBBoxGroupKey"));
        assertTrue(attempt.has("lockedCollisionEnvelope"));
    }

    @Test
    void d6PreflightRejectsFixedBBoxGroupMissingFromFacts() throws Exception {
        Fixture fixture = fixture();
        CityStructureEnvelopeProfiler.Result factsResult = new CityStructureEnvelopeProfiler()
                .profile(fixture.baseDir(), fixture.terraSenseSource(), List.of("minecraft:desert_pyramid"), 2,
                        (profile, sampleIndex) -> CityStructureEnvelopeProfiler.EnvelopeSample.valid(sampleIndex,
                                new BlockBounds(-4, -5, 15, 6), 1,
                                "fixed_config_hash", "pack_hash"));
        Path factsPath = fixture.baseDir().resolve("fixed_structure_envelope_facts.json");
        Files.writeString(factsPath, CityJson.GSON.toJson(factsResult.structureEnvelopeFacts()));
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), singleAnchorPlan(fixture.review()),
                        CityStructureEnvelopeFacts.load(factsPath))
                .structureAnchorMap();
        JsonObject anchor = anchorMap.getAsJsonArray("anchors").get(0).getAsJsonObject();
        JsonObject anchorBlock = anchor.getAsJsonObject("anchorBlock");
        int originX = Math.floorDiv(anchorBlock.get("x").getAsInt(), 16) * 16;
        int originZ = Math.floorDiv(anchorBlock.get("z").getAsInt(), 16) * 16;
        BlockBounds unprofiledShape = new BlockBounds(originX - 9, originZ - 5, originX + 15, originZ + 6);

        CityStructureMaterializationPlanner.Result result = new CityStructureMaterializationPlanner()
                .planWorldgen(anchorMap, CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(),
                        new FakePlacementBackend("sig", true, unprofiledShape), null);

        assertFalse(result.asJson().get("ok").getAsBoolean());
        JsonObject attempt = result.structureMaterializationTrace()
                .getAsJsonArray("attempts").get(0).getAsJsonObject();
        assertEquals("BBOX_GROUP_NOT_IN_FACTS", attempt.get("reasonCode").getAsString());
        assertTrue(attempt.has("availableEnvelopeGroupKeys"));
    }

    @Test
    void d6PreflightRejectsOverlappingLockedCollisionEnvelope() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), anchorPlan(fixture.review()))
                .structureAnchorMap();
        JsonObject anchor = anchorMap.getAsJsonArray("anchors").get(0).getAsJsonObject();
        BlockBounds first = bounds(anchor.getAsJsonObject("reservedEnvelope"));

        CityStructureMaterializationPlanner.Result result = new CityStructureMaterializationPlanner()
                .planWorldgen(anchorMap, CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(),
                        new FakePlacementBackend("sig", true, first), null);

        assertFalse(result.asJson().get("ok").getAsBoolean());
        assertTrue(result.structureMaterializationTrace()
                .getAsJsonObject("failureSummary")
                .has("LEDGER_OCCUPIED_OVERLAP"));
    }

    @Test
    void debugLateMaterializeStillRequiresSameStartSignatureBeforeLedgerWrite() throws Exception {
        Fixture fixture = fixture();
        JsonObject anchorMap = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), anchorPlan(fixture.review()))
                .structureAnchorMap();
        CityStructureMaterializationPlanner planner = new CityStructureMaterializationPlanner();
        CityStructureMaterializationPlanner.Result dryRun = planner.plan(anchorMap,
                new FakePlacementBackend("sig_a", true), null);

        CityStructureMaterializationPlanner.Result result = planner.execute(
                dryRun.structureMaterializationPlan(), new FakePlacementBackend("sig_b", true),
                null, true);

        assertEquals(0, result.placedStructureLedger().getAsJsonArray("placedStructures").size());
        assertTrue(result.structureMaterializationTrace()
                .getAsJsonObject("failureSummary")
                .has("START_SIGNATURE_MISMATCH"));
    }

    private static Fixture fixture() throws Exception {
        Path baseDir = Files.createTempDirectory("city-structure-landing");
        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder(config)
                .build("city_test", "realm_test", "minecraft:overworld",
                        "city_test", "candidate_test", 0, 0,
                        "village", "village", 160, 4, null);
        CityLandformReviewPackage review = new com.rinsing.geomantia.systems.city.application.CityLandformReviewBuilder(config)
                .build(context, List.of(
                        patch("plain", LandformType.PLAIN, -360, -360, -260, -260),
                        patch("slope", LandformType.PLAIN, 260, 260, 360, 360)));
        Path catalogPath = baseDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());
        JsonObject source = new JsonObject();
        source.addProperty("schemaVersion", "terrasense_structure_profile_source.v0.1");
        source.addProperty("sourceType", "debug_catalog");
        source.addProperty("catalogMode", "debug");
        source.addProperty("debugCatalogPath", catalogPath.toString());
        return new Fixture(baseDir, context, review, source);
    }

    private static JsonObject anchorPlan(CityLandformReviewPackage review) {
        LandformPatchSummary first = review.landformPatches().get(0);
        LandformPatchSummary second = review.landformPatches().get(1);
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_structure_anchor_plan.v0.1",
                  "cityId": "city_test",
                  "anchors": [
                    {
                      "anchorId": "anchor_pyramid",
                      "structureId": "minecraft:desert_pyramid",
                      "sourcePatchIds": ["%s"],
                      "anchorBlock": {"x": %d, "z": %d},
                      "rotation": "NONE",
                      "intentTerms": ["function.landmark"],
                      "priority": 1,
                      "roadAccessIntent": "primary_access"
                    },
                    {
                      "anchorId": "anchor_village",
                      "structureId": "minecraft:village_plains",
                      "sourcePatchIds": ["%s"],
                      "anchorBlock": {"x": %d, "z": %d},
                      "rotation": "NONE",
                      "intentTerms": ["function.village"],
                      "priority": 2,
                      "roadAccessIntent": "secondary_access"
                    }
                  ]
                }
                """.formatted(first.landformPatchId(), first.centerBlock().x(), first.centerBlock().z(),
                second.landformPatchId(), second.centerBlock().x(), second.centerBlock().z())).getAsJsonObject();
    }

    private static JsonObject designSlotPlan(CityLandformReviewPackage review) {
        List<LandformPatchSummary> patches = review.landformPatches();
        LandformPatchSummary first = patches.get(0);
        LandformPatchSummary second = patches.size() > 1 ? patches.get(1) : first;
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_d4_design_slot_plan.v0.1",
                  "cityId": "city_test",
                  "placementOrder": ["admin_core", "residential_01"],
                  "slots": [
                    {
                      "slotId": "admin_core",
                      "displayRole": "行政核心",
                      "candidatePatchRefs": ["%s"],
                      "structureIds": ["minecraft:desert_pyramid"],
                      "relationHints": []
                    },
                    {
                      "slotId": "residential_01",
                      "displayRole": "住宅",
                      "candidatePatchRefs": ["%s"],
                      "structureIds": ["minecraft:desert_pyramid"],
                      "relationHints": [
                        {"targetSlotId": "admin_core", "distanceBand": "near"}
                      ]
                    }
                  ]
                }
                """.formatted(first.landformPatchId(), second.landformPatchId())).getAsJsonObject();
    }

    private static JsonObject singleAnchorPlan(CityLandformReviewPackage review) {
        LandformPatchSummary first = review.landformPatches().get(0);
        return JsonParser.parseString("""
                {
                  "schemaVersion": "city_structure_anchor_plan.v0.1",
                  "cityId": "city_test",
                  "anchors": [
                    {
                      "anchorId": "anchor_pyramid",
                      "structureId": "minecraft:desert_pyramid",
                      "sourcePatchIds": ["%s"],
                      "anchorBlock": {"x": %d, "z": %d},
                      "rotation": "NONE",
                      "intentTerms": ["function.landmark"],
                      "priority": 1,
                      "roadAccessIntent": "primary_access"
                    }
                  ]
                }
                """.formatted(first.landformPatchId(), first.centerBlock().x(), first.centerBlock().z()))
                .getAsJsonObject();
    }

    private static LandformPatch patch(String id, LandformType type, int minX, int minZ, int maxX, int maxZ) {
        return new LandformPatch(id, "region_0", type,
                Math.max(1, (maxX - minX) * (maxZ - minZ) / 256), minX, minZ, maxX, maxZ,
                70.0, 65.0, 75.0, 1.5, 50.0,
                false, false, 0.9, EnumSet.noneOf(PatchFlag.class));
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
                      "sourceProfileRef": "synthetic://unit-test/desert_pyramid",
                      "profileType": "single",
                      "sampleType": "structure_assembly",
                      "placementKind": "minecraft_place_structure",
                      "placementCommand": "place structure minecraft:desert_pyramid <x> <y> <z>",
                      "footprintMode": "fixed_footprint",
                      "semanticTerms": ["function.landmark", "style.debug", "placement.inside_zone", "usage.public_core", "quality.debug_usable"],
                      "functionTerms": ["function.landmark"],
                      "styleTerms": ["style.debug"],
                      "placementTerms": ["placement.inside_zone"],
                      "usageTerms": ["usage.public_core"],
                      "qualityTerms": ["quality.debug_usable"],
                      "fixedFootprint": {"widthBlocks": 20, "depthBlocks": 12, "heightBlocks": 10},
                      "allowedRotations": ["NONE", "CLOCKWISE_90"],
                      "clearanceBlocks": 2
                    },
                    {
                      "structureId": "minecraft:village_plains",
                      "sourceProfileRef": "synthetic://unit-test/village_plains",
                      "profileType": "jigsaw_system",
                      "sampleType": "structure_assembly",
                      "placementKind": "minecraft_place_structure",
                      "placementCommand": "place structure minecraft:village_plains <x> <y> <z>",
                      "footprintMode": "variable_area",
                      "semanticTerms": ["function.village", "style.debug", "placement.inside_zone", "usage.filler", "quality.debug_usable"],
                      "functionTerms": ["function.village"],
                      "styleTerms": ["style.debug"],
                      "placementTerms": ["placement.inside_zone"],
                      "usageTerms": ["usage.filler"],
                      "qualityTerms": ["quality.debug_usable"],
                      "expectedAreaRange": {
                        "minAreaBlocks": 128,
                        "maxAreaBlocks": 4096,
                        "startFootprint": {"widthBlocks": 10, "depthBlocks": 10, "heightBlocks": 8}
                      },
                      "maxDistanceFromCenter": 64,
                      "allowedRotations": ["NONE", "CLOCKWISE_90"],
                      "clearanceBlocks": 0
                    }
                  ],
                  "quality": {
                    "passed": true,
                    "score": 100,
                    "hardBlocks": [],
                    "warnings": [],
                    "needsReview": [],
                    "metrics": {}
                  }
                }
                """;
    }

    private static String trekDebugCatalog() {
        return """
                {
                  "schemaVersion": "city_structure_profile_catalog.v0.1",
                  "catalogMode": "debug",
                  "source": {"basis": "synthetic trek unit-test fixture"},
                  "structures": [
                    {
                      "structureId": "trek:overworld/medium/farm",
                      "sourceProfileRef": "synthetic://unit-test/trek_farm",
                      "profileType": "jigsaw_system",
                      "sampleType": "structure_assembly",
                      "placementKind": "minecraft_place_structure",
                      "placementCommand": "place structure trek:overworld/medium/farm <x> <y> <z>",
                      "footprintMode": "variable_area",
                      "semanticTerms": ["function.farm", "quality.debug_usable"],
                      "functionTerms": ["function.farm"],
                      "styleTerms": ["style.trek"],
                      "placementTerms": ["placement.plains"],
                      "usageTerms": ["usage.test"],
                      "qualityTerms": ["quality.debug_usable"],
                      "expectedAreaRange": {
                        "minAreaBlocks": 128,
                        "maxAreaBlocks": 4096,
                        "startFootprint": {"widthBlocks": 17, "depthBlocks": 23, "heightBlocks": 11}
                      },
                      "maxDistanceFromCenter": 64,
                      "allowedRotations": ["NONE"],
                      "clearanceBlocks": 0
                    }
                  ],
                  "quality": {"passed": true, "score": 100, "hardBlocks": [], "warnings": [], "needsReview": [], "metrics": {}}
                }
                """;
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(
                obj.get("minX").getAsInt(),
                obj.get("minZ").getAsInt(),
                obj.get("maxX").getAsInt(),
                obj.get("maxZ").getAsInt());
    }

    private record Fixture(Path baseDir, CitySiteContext context, CityLandformReviewPackage review,
                           JsonObject terraSenseSource) {
    }

    private static final class FakePlacementBackend implements CityStructureMaterializationPlanner.PlacementBackend {
        private final String signaturePrefix;
        private final boolean success;
        private final BlockBounds actualFootprintOverride;
        private int planCalls;
        private int placeCalls;

        private FakePlacementBackend(String signaturePrefix, boolean success) {
            this(signaturePrefix, success, null);
        }

        private FakePlacementBackend(String signaturePrefix, boolean success, BlockBounds actualFootprintOverride) {
            this.signaturePrefix = signaturePrefix;
            this.success = success;
            this.actualFootprintOverride = actualFootprintOverride;
        }

        @Override
        public CityStructureMaterializationPlanner.PlacementResult plan(
                CityStructureMaterializationPlanner.StructureTask task) {
            planCalls++;
            return result(task, false);
        }

        @Override
        public CityStructureMaterializationPlanner.PlacementResult place(
                CityStructureMaterializationPlanner.StructureTask task) {
            placeCalls++;
            return result(task, true);
        }

        private CityStructureMaterializationPlanner.PlacementResult result(
                CityStructureMaterializationPlanner.StructureTask task, boolean applied) {
            if (!success) {
                return CityStructureMaterializationPlanner.PlacementResult.failed("TEST_FAILURE", "synthetic failure");
            }
            JsonArray pieces = new JsonArray();
            JsonObject piece = new JsonObject();
            piece.addProperty("pieceIndex", 0);
            piece.add("box", boundsJson(task.plannedFootprint()));
            piece.addProperty("type", "synthetic");
            pieces.add(piece);
            return CityStructureMaterializationPlanner.PlacementResult.success(applied, "ok",
                    actualFootprintOverride == null ? task.plannedFootprint() : actualFootprintOverride,
                    signaturePrefix + ":" + task.anchorId(), pieces);
        }
    }
}
