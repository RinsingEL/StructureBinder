package com.rinsing.geomantia.systems.city;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityReservationMaskPlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureAnchorPlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureAnchorCandidatePlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureArrayCandidatePlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureClusterGroupCandidatePlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureMaterializationPlanner;
import com.rinsing.geomantia.systems.city.application.CityTemplateCatalog;
import com.rinsing.geomantia.systems.city.application.CityWallPlanner;
import com.rinsing.geomantia.systems.city.application.CityWallReservationPlanner;
import com.rinsing.geomantia.systems.city.application.CityWallTemplateCatalog;
import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.CitySiteContext;
import com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityReservationMaskRegistry;
import com.rinsing.geomantia.systems.city.infrastructure.world.MinecraftCityWallArtifactWriter;
import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.algorithm.landform.PatchMerger;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.landform.PatchFlag;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegion;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
                designSlotPlan(fixture.review()));

        JsonObject candidateSet = result.anchorCandidateSet();
        assertTrue(result.asJson().get("ok").getAsBoolean());
        assertEquals("city_d4_anchor_candidate_set", candidateSet.get("schema").getAsString());
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
        assertTrue(firstCandidate.has("estimatedMaskEnvelope"));
        assertTrue(firstCandidate.has("scoreBreakdown"));
        assertEquals("geomantia:test_house", firstCandidate.get("templateId").getAsString());
        assertFalse(firstCandidate.has("structureId"));
        assertFalse(firstCandidate.has("envelopeMode"));

        JsonObject selectionPlan = JsonParser.parseString("""
                {
                  "schema": "city_d4_anchor_selection_plan",
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

        assertEquals(CityStructureAnchorPlanner.PLAN_SCHEMA, anchorPlan.get("schema").getAsString());
        JsonObject anchor = anchorPlan.getAsJsonArray("anchors").get(0).getAsJsonObject();
        assertEquals("admin_core_01", anchor.get("anchorId").getAsString());
        assertEquals(firstCandidate.get("templateId").getAsString(), anchor.get("templateId").getAsString());
        assertFalse(anchor.has("structureId"));
        assertTrue(anchorPlan.has("candidateSelectionTrace"));
    }

    @Test
    void d4AnchorCandidatesStayInsideSelectedComponentOfLargerSourcePatch() throws Exception {
        Fixture fixture = arrayFixture();
        JsonObject plan = designSlotPlan(fixture.review());
        JsonObject slot = plan.getAsJsonArray("slots").get(0).getAsJsonObject();
        JsonArray onlySlot = new JsonArray();
        onlySlot.add(slot);
        plan.add("slots", onlySlot);
        plan.add("placementOrder", JsonParser.parseString("[\"admin_core\"]").getAsJsonArray());
        JsonObject selectedComponent = legalRegion("psel_anchor_component", fixture.review(),
                -64, -64, 63, 63);
        slot.add("candidateLegalRegion", selectedComponent);

        CityStructureAnchorCandidatePlanner.Result result = new CityStructureAnchorCandidatePlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), plan);

        JsonArray candidates = result.anchorCandidateSet().getAsJsonArray("slotCandidates")
                .get(0).getAsJsonObject().getAsJsonArray("candidates");
        assertFalse(candidates.isEmpty());
        assertTrue(result.qualityReport().get("passed").getAsBoolean(), result.qualityReport().toString());
        assertTrue(result.qualityReport().getAsJsonArray("hardBlocks").isEmpty(),
                result.qualityReport().toString());
        assertTrue(fixture.review().landformPatches().get(0).blockBounds().widthBlocks() > 128);
        for (JsonElement element : candidates) {
            BlockBounds collision = bounds(element.getAsJsonObject()
                    .getAsJsonObject("estimatedCollisionEnvelope"));
            assertTrue(regionContains(selectedComponent, collision),
                    "anchor collision escaped selected component: " + collision);
        }
    }

    @Test
    void d4CandidatePlannerRejectsConfiguredStructureIdentity() throws Exception {
        Fixture fixture = fixture();
        JsonObject plan = designSlotPlan(fixture.review());
        JsonObject slot = plan.getAsJsonArray("slots").get(0).getAsJsonObject();
        slot.addProperty("structureId", "minecraft:desert_pyramid");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new CityStructureAnchorCandidatePlanner()
                        .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), plan));
        assertTrue(ex.getMessage().contains("CITY_CONFIGURED_STRUCTURE_FLOW_REMOVED"));
    }

    @Test
    void d4CandidatePlannerPreservesTemplateMaterializationMetadata() throws Exception {
        Fixture fixture = fixture();
        JsonObject plan = designSlotPlan(fixture.review());

        CityStructureAnchorCandidatePlanner planner = new CityStructureAnchorCandidatePlanner();
        CityStructureAnchorCandidatePlanner.Result result = planner.plan(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), plan);
        JsonObject candidate = result.anchorCandidateSet().getAsJsonArray("slotCandidates")
                .get(0).getAsJsonObject().getAsJsonArray("candidates").get(0).getAsJsonObject();

        assertEquals("geomantia:test_house", candidate.get("templateId").getAsString());
        assertEquals("sha256:test-house", candidate.get("templateHash").getAsString());
        assertEquals("fixed_v1", candidate.get("variantId").getAsString());
        assertEquals("structure_template_nbt", candidate.get("materializationSource").getAsString());
        assertEquals(20, candidate.getAsJsonObject("rawSize").get("width").getAsInt());
        assertEquals(1, candidate.getAsJsonObject("templatePlacementPlan")
                .getAsJsonObject("transformed").getAsJsonArray("roadEntrances").size());

        JsonObject selectionPlan = JsonParser.parseString("""
                {
                  "schema": "city_d4_anchor_selection_plan",
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
                """.formatted(candidate.get("candidateId").getAsString())).getAsJsonObject();
        JsonObject anchor = planner.select(result.anchorCandidateSet(), selectionPlan)
                .getAsJsonArray("anchors").get(0).getAsJsonObject();

        assertEquals("geomantia:test_house", anchor.get("templateId").getAsString());
        assertEquals("geomantia:city/test_house", anchor.get("templateRef").getAsString());
        assertEquals("fixed_v1", anchor.get("variantId").getAsString());
        assertFalse(anchor.has("templateHash"));
        assertFalse(anchor.has("rawSize"));
    }

    @Test
    void d4CandidateSessionResolvesGeometryFromExplicitTemplateCatalog() throws Exception {
        Fixture fixture = fixture();
        JsonObject catalog = JsonParser.parseString("""
                {
                  "schema": "city_template_catalog",
                  "templates": [{
                    "buildingSemantic": "civic_hall",
                    "style": "test",
                    "templateId": "geomantia:test/hall",
                    "templateRef": "geomantia:test/hall",
                    "contentHash": "sha256:test-hall",
                    "variantId": "test_v1",
                    "rawSize": {"width": 9, "height": 7, "depth": 11},
                    "allowedRotations": ["NONE"],
                    "allowedMirrors": ["NONE"],
                    "roadEntrances": [{
                      "entranceId": "front",
                      "x": 4,
                      "z": 1,
                      "direction": "NORTH"
                    }],
                    "terrainPosePolicy": "flat_or_small_step",
                    "supportPolicy": "full_footprint_support",
                    "clearanceBlocks": 3
                  }]
                }
                """).getAsJsonObject();
        JsonObject plan = designSlotPlan(fixture.review());
        JsonObject firstSlot = plan.getAsJsonArray("slots").get(0).getAsJsonObject();
        firstSlot.remove("templateIds");
        firstSlot.addProperty("templateId", "geomantia:test/hall");
        firstSlot.addProperty("variantId", "test_v1");
        plan.getAsJsonArray("slots").remove(1);
        plan.add("placementOrder", JsonParser.parseString("[\"admin_core\"]").getAsJsonArray());
        plan.add("templateCatalog", catalog);

        CityStructureAnchorCandidatePlanner planner = new CityStructureAnchorCandidatePlanner();
        CityStructureAnchorCandidatePlanner.SessionResult created = planner.createSession(
                fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), plan, "template_session");
        assertTrue(created.qualityReport().get("passed").getAsBoolean());
        assertEquals(CityTemplateCatalog.SCHEMA,
                created.session().getAsJsonObject("templateCatalog").get("schema").getAsString());

        CityStructureAnchorCandidatePlanner.NextCandidateResult next = planner.planNext(
                fixture.baseDir(), fixture.review(), created.session());
        JsonObject candidate = next.slotCandidateSet().getAsJsonArray("slotCandidates")
                .get(0).getAsJsonObject().getAsJsonArray("candidates").get(0).getAsJsonObject();
        assertEquals("geomantia:test/hall", candidate.get("templateId").getAsString());
        assertFalse(candidate.has("structureId"));
        assertEquals("geomantia:test/hall", candidate.get("templateRef").getAsString());
        assertEquals("sha256:test-hall", candidate.get("templateHash").getAsString());
        assertEquals("test_v1", candidate.get("variantId").getAsString());
        assertEquals("structure_template_nbt", candidate.get("materializationSource").getAsString());
        assertEquals(9, candidate.getAsJsonObject("rawSize").get("width").getAsInt());
        assertEquals(1, candidate.getAsJsonArray("roadEntrances").size());
    }

    @Test
    void d4CandidatePlannerRejectsLegacyPayload() throws Exception {
        Fixture fixture = fixture();
        JsonObject plan = designSlotPlan(fixture.review());
        plan.addProperty("functionType", "civic_core");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new CityStructureAnchorCandidatePlanner()
                        .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), plan));

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
                fixture.baseDir(), fixture.review(), sessionResult.session());
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
        assertEquals("estimated_collision", occupied.get("envelopeType").getAsString());
        assertTrue(occupied.has("estimatedCollisionEnvelope"));
        assertFalse(occupied.has("estimatedSafetyEnvelope"));
        assertFalse(firstCandidate.has("diagnosticMaxObservedEnvelope"));
        assertFalse(firstCandidate.has("envelopeMode"));
        assertTrue(firstCandidate.has("plannedFootprint"));
        assertEquals("residential_01", selected.session().get("currentSlotId").getAsString());
        assertEquals("deferred_to_d6",
                selected.quickPreflightReport().get("status").getAsString());

        CityStructureAnchorCandidatePlanner.NextCandidateResult second = planner.planNext(
                fixture.baseDir(), fixture.review(), selected.session());
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
                fixture.baseDir(), fixture.review(), sessionResult.session());
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
                    fixture.baseDir(), fixture.review(), session);
            JsonObject candidate = next.slotCandidateSet().getAsJsonArray("slotCandidates")
                    .get(0).getAsJsonObject().getAsJsonArray("candidates").get(0).getAsJsonObject();
            session = planner.selectSession(next.session(), next.slotCandidateSet(), slotId,
                    candidate.get("candidateId").getAsString(), slotId + "_anchor", "test", false).session();
        }

        CityStructureAnchorCandidatePlanner.FinalizeResult finalized = planner.finalizeSession(session);
        JsonObject plan = finalized.structureAnchorPlan();
        assertEquals(CityStructureAnchorPlanner.PLAN_SCHEMA, plan.get("schema").getAsString());
        assertEquals(2, plan.getAsJsonArray("anchors").size());
        assertTrue(plan.has("candidateSelectionTrace"));
        assertEquals("city_d4_design_time_report",
                finalized.designTimeReport().get("schema").getAsString());
    }

    @Test
    void d4ArrayCandidatePlannerBuildsCompleteNonOverlappingGroups() throws Exception {
        Fixture fixture = arrayFixture();
        CityStructureArrayCandidatePlanner.Result result = new CityStructureArrayCandidatePlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                        arrayCandidatePlan(fixture.review(), 10),
                        new JsonObject(), new JsonArray());

        JsonObject candidateSet = result.arrayCandidateSet();
        assertTrue(result.asJson().get("ok").getAsBoolean());
        assertEquals("city_d4_array_candidate_set", candidateSet.get("schema").getAsString());
        JsonArray groups = candidateSet.getAsJsonArray("arrayCandidates");
        assertFalse(groups.isEmpty());
        Set<String> patterns = new HashSet<>();
        for (JsonElement elem : groups) {
            patterns.add(elem.getAsJsonObject().get("arrayPattern").getAsString());
        }
        assertTrue(patterns.contains("loose_cluster"));
        assertTrue(patterns.contains("patch_axis_band"));
        assertTrue(patterns.contains("scattered"));

        JsonObject firstGroup = groups.get(0).getAsJsonObject();
        assertEquals(10, firstGroup.getAsJsonArray("items").size());
        assertEquals(10, firstGroup.getAsJsonObject("expandedStructureAnchorPlan")
                .getAsJsonArray("anchors").size());
        assertGroupItemsDoNotOverlap(firstGroup);
    }

    @Test
    void d4ExactOriginsTryFallbackInOrderWithoutRepeatingAcrossSourcePatches() throws Exception {
        Fixture fixture = fixture();
        List<LandformPatchSummary> patches = fixture.review().landformPatches();
        BlockPoint first = patches.get(0).centerBlock();
        BlockPoint fallback = new BlockPoint(first.x() + 50, first.z());
        JsonObject plan = arrayCandidatePlan(fixture.review(), 1);
        plan.add("candidatePatchRefs", JsonParser.parseString("""
                ["%s","%s"]
                """.formatted(patches.get(0).landformPatchId(), patches.get(1).landformPatchId()))
                .getAsJsonArray());
        plan.add("templateIds", JsonParser.parseString("[\"geomantia:test_house\"]").getAsJsonArray());
        plan.add("patterns", JsonParser.parseString("[\"patch_axis_band\"]").getAsJsonArray());
        plan.addProperty("exactCandidateOriginsOnly", true);
        plan.add("candidateOrigins", JsonParser.parseString("""
                [{"x":%d,"z":%d},{"x":%d,"z":%d}]
                """.formatted(first.x(), first.z(), fallback.x(), fallback.z())).getAsJsonArray());
        JsonArray occupied = JsonParser.parseString("""
                [{"blockBounds":{"minX":%d,"minZ":%d,"maxX":%d,"maxZ":%d}}]
                """.formatted(first.x() - 20, first.z() - 20, first.x() + 20, first.z() + 20))
                .getAsJsonArray();

        CityStructureArrayCandidatePlanner.Result result = new CityStructureArrayCandidatePlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), plan,
                        new JsonObject(), occupied);

        JsonArray candidates = result.arrayCandidateSet().getAsJsonArray("arrayCandidates");
        assertEquals(1, candidates.size(),
                "exact origins describe one ordered landing attempt, not one candidate per source Patch");
        assertEquals(1, result.arrayCandidateSet().getAsJsonArray("generationReports").size());
        JsonObject anchor = candidates.get(0).getAsJsonObject()
                .getAsJsonObject("expandedStructureAnchorPlan").getAsJsonArray("anchors")
                .get(0).getAsJsonObject().getAsJsonObject("anchorBlock");
        assertEquals(fallback.x(), anchor.get("x").getAsInt());
        assertEquals(fallback.z(), anchor.get("z").getAsInt());
    }

    @Test
    void d4ArrayCandidatesStayInsideSelectedComponentOfLargerSourcePatch() throws Exception {
        Fixture fixture = arrayFixture();
        JsonObject plan = arrayCandidatePlan(fixture.review(), 2);
        JsonObject selectedComponent = legalRegion("psel_array_component", fixture.review(),
                96, -64, 255, 63);
        plan.add("candidateLegalRegion", selectedComponent);

        CityStructureArrayCandidatePlanner.Result result = new CityStructureArrayCandidatePlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), plan,
                        new JsonObject(), new JsonArray());

        JsonArray groups = result.arrayCandidateSet().getAsJsonArray("arrayCandidates");
        assertFalse(groups.isEmpty());
        for (JsonElement groupElement : groups) {
            for (JsonElement itemElement : groupElement.getAsJsonObject().getAsJsonArray("items")) {
                BlockBounds collision = bounds(itemElement.getAsJsonObject()
                        .getAsJsonObject("estimatedCollisionEnvelope"));
                assertTrue(regionContains(selectedComponent, collision),
                        "array collision escaped selected component: " + collision);
            }
        }
    }

    @Test
    void d4ArrayCandidatesMatchWorldMemberCellsWhenCityGridHasOffsetOrigin() throws Exception {
        Fixture fixture = arrayFixture();
        JsonObject reviewJson = fixture.review().asJson();
        JsonObject grid = reviewJson.getAsJsonObject("grid");
        int step = grid.get("cellStepBlocks").getAsInt();
        int originX = -7680;
        int originZ = 7168;
        grid.addProperty("originBlockX", originX);
        grid.addProperty("originBlockZ", originZ);

        JsonObject patch = reviewJson.getAsJsonArray("landformPatches").get(0).getAsJsonObject();
        JsonArray memberCells = new JsonArray();
        int minLocal = 2;
        int maxLocal = 17;
        for (int localZ = minLocal; localZ <= maxLocal; localZ++) {
            for (int localX = minLocal; localX <= maxLocal; localX++) {
                int blockMinX = originX + localX * step;
                int blockMinZ = originZ + localZ * step;
                JsonObject cell = new JsonObject();
                cell.addProperty("cellX", Math.floorDiv(blockMinX, step));
                cell.addProperty("cellZ", Math.floorDiv(blockMinZ, step));
                cell.addProperty("blockMinX", blockMinX);
                cell.addProperty("blockMinZ", blockMinZ);
                memberCells.add(cell);
            }
        }
        int minX = originX + minLocal * step;
        int minZ = originZ + minLocal * step;
        int maxX = originX + (maxLocal + 1) * step - 1;
        int maxZ = originZ + (maxLocal + 1) * step - 1;
        patch.add("memberCells", memberCells);
        patch.addProperty("cellCount", memberCells.size());
        patch.addProperty("areaBlocks", memberCells.size() * step * step);
        patch.add("blockBounds", JsonParser.parseString("""
                {"minX":%d,"minZ":%d,"maxX":%d,"maxZ":%d}
                """.formatted(minX, minZ, maxX, maxZ)).getAsJsonObject());
        patch.add("centerBlock", JsonParser.parseString("""
                {"x":%d,"z":%d}
                """.formatted((minX + maxX) / 2, (minZ + maxZ) / 2)).getAsJsonObject());

        CityLandformReviewPackage offsetReview = CityLandformReviewPackage.fromJson(reviewJson);
        JsonObject plan = arrayCandidatePlan(offsetReview, 1);
        JsonObject selectedComponent = legalRegion("psel_offset_component", offsetReview,
                minX, minZ, maxX, maxZ);
        plan.add("candidateLegalRegion", selectedComponent);

        CityStructureArrayCandidatePlanner.Result result = new CityStructureArrayCandidatePlanner()
                .plan(fixture.baseDir(), offsetReview, fixture.terraSenseSource(), plan,
                        new JsonObject(), new JsonArray());

        JsonArray groups = result.arrayCandidateSet().getAsJsonArray("arrayCandidates");
        assertFalse(groups.isEmpty(), result.qualityReport().toString());
        for (JsonElement groupElement : groups) {
            for (JsonElement itemElement : groupElement.getAsJsonObject().getAsJsonArray("items")) {
                BlockBounds collision = bounds(itemElement.getAsJsonObject()
                        .getAsJsonObject("estimatedCollisionEnvelope"));
                assertTrue(regionContains(selectedComponent, collision),
                        "offset array collision escaped selected component: " + collision);
            }
        }
    }

    @Test
    void d4ArrayCandidatePlannerSupportsCompoundShapePatterns() throws Exception {
        Fixture fixture = arrayFixture();
        assertArrayCandidateShape(fixture, "grid", "grid", 2, 2, 4);
        assertArrayCandidateShape(fixture, "courtyard", "courtyard", 3, 3, 8);
        assertArrayCandidateShape(fixture, "l_shape", "l_shape", 3, 3, 5);
        assertArrayCandidateShape(fixture, "u_shape", "u_shape", 3, 3, 7);
        assertArrayCandidateShape(fixture, "organic_compact", "organic_compact", 3, 4, 6);
        assertArrayCandidateShape(fixture, "compound_cluster", "grid", 2, 2, 4);
    }

    @Test
    void d4ArrayCandidatePlannerSpacingIgnoresMaskEnvelopeOverlap() throws Exception {
        Fixture fixture = arrayFixture();
        JsonObject plan = arrayCandidatePlan(fixture.review(), 2);
        plan.add("patterns", JsonParser.parseString("""
                ["compound_cluster"]
                """).getAsJsonArray());
        plan.add("structureIds", JsonParser.parseString("""
                ["minecraft:desert_pyramid"]
                """).getAsJsonArray());
        plan.add("compoundCluster", JsonParser.parseString("""
                {"shape": "grid", "rows": 1, "columns": 2, "spacingBlocks": 40}
                """).getAsJsonObject());

        CityStructureArrayCandidatePlanner.Result result = new CityStructureArrayCandidatePlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                        plan, new JsonObject(), new JsonArray());

        assertTrue(result.asJson().get("ok").getAsBoolean());
        JsonObject group = result.arrayCandidateSet().getAsJsonArray("arrayCandidates").get(0).getAsJsonObject();
        assertEquals(2, group.getAsJsonArray("items").size());
        assertGroupItemsDoNotOverlap(group);
        assertAnyArrayItemMaskOverlap(group);
    }

    @Test
    void d4StructureClusterGroupPlannerBuildsFiveCompleteNonOverlappingGroups() throws Exception {
        Fixture fixture = arrayFixture();
        CityStructureClusterGroupCandidatePlanner.Result result =
                new CityStructureClusterGroupCandidatePlanner()
                        .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                                designSlotPlan(fixture.review()),
                                CityStructureClusterGroupCandidatePlanner.Options.defaults());

        JsonObject candidateSet = result.structureClusterGroupCandidateSet();
        assertTrue(result.asJson().get("ok").getAsBoolean());
        assertEquals(CityStructureClusterGroupCandidatePlanner.CANDIDATE_SET_SCHEMA,
                candidateSet.get("schema").getAsString());
        assertEquals("structure_cluster_group_candidates",
                candidateSet.get("planningMode").getAsString());
        JsonArray groups = candidateSet.getAsJsonArray("groupCandidates");
        assertEquals(5, groups.size());
        for (JsonElement groupElem : groups) {
            JsonObject group = groupElem.getAsJsonObject();
            assertEquals(2, group.getAsJsonArray("items").size());
            assertEquals(2, group.getAsJsonObject("expandedStructureAnchorPlan")
                    .getAsJsonArray("anchors").size());
            assertClusterGroupItemsDoNotOverlap(group);
        }
    }

    @Test
    void d4ArrayCandidatePlannerAvoidsOccupiedEnvelopes() throws Exception {
        Fixture fixture = arrayFixture();
        JsonArray occupied = JsonParser.parseString("""
                [
                  {"blockBounds": {"minX": -72, "minZ": -72, "maxX": 72, "maxZ": 72}}
                ]
                """).getAsJsonArray();
        CityStructureArrayCandidatePlanner.Result result = new CityStructureArrayCandidatePlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                        arrayCandidatePlan(fixture.review(), 10),
                        new JsonObject(), occupied);

        assertTrue(result.asJson().get("ok").getAsBoolean());
        BlockBounds occupiedBounds = bounds(occupied.get(0).getAsJsonObject().getAsJsonObject("blockBounds"));
        for (JsonElement groupElem : result.arrayCandidateSet().getAsJsonArray("arrayCandidates")) {
            JsonObject group = groupElem.getAsJsonObject();
            for (JsonElement itemElem : group.getAsJsonArray("items")) {
                BlockBounds collision = bounds(itemElem.getAsJsonObject()
                        .getAsJsonObject("estimatedCollisionEnvelope"));
                assertFalse(collision.overlaps(occupiedBounds));
            }
        }
    }

    @Test
    void d4ArrayCandidatePlannerHardFailsWhenArrayCountCannotBeSatisfied() throws Exception {
        Fixture fixture = fixture();
        JsonArray occupied = JsonParser.parseString("""
                [{"blockBounds":{"minX":-100000,"minZ":-100000,"maxX":100000,"maxZ":100000}}]
                """).getAsJsonArray();
        CityStructureArrayCandidatePlanner.Result result = new CityStructureArrayCandidatePlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                        arrayCandidatePlan(fixture.review(), 10),
                        new JsonObject(), occupied);

        assertFalse(result.asJson().get("ok").getAsBoolean());
        assertTrue(result.qualityReport().getAsJsonArray("hardBlocks").toString()
                .contains("D4_ARRAY_COUNT_UNSATISFIED"));
        assertTrue(result.arrayCandidateSet().getAsJsonArray("arrayCandidates").isEmpty());
    }

    @Test
    void d4FixedTemplateGeometryDoesNotDependOnTerraSenseProfileApproval() throws Exception {
        Fixture fixture = fixture();
        Path profilePath = fixture.baseDir().resolve("StructureProfile.jsonl");
        JsonObject profile = JsonParser.parseString("""
                {
                  "structureId": "minecraft:desert_pyramid",
                   "profileType": "single",
                   "footprintMode": "fixed_footprint",
                   "functionTerms": ["function.landmark"],
                   "fixedFootprint": {"widthBlocks": 20, "depthBlocks": 12, "heightBlocks": 10}
                }
                """).getAsJsonObject();
        Files.writeString(profilePath, profile + "\n");
        JsonObject source = new JsonObject();
        source.addProperty("schema", "terrasense_structure_profile_source");
        source.addProperty("sourceType", "structure_profile_jsonl");
        source.addProperty("catalogMode", "official");
        source.addProperty("profilePath", profilePath.toString());

        JsonObject result = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), source, singleAnchorPlan(fixture.review()))
                .asJson();

        assertTrue(result.get("ok").getAsBoolean(), result.toString());
        JsonObject anchor = result.getAsJsonObject("structureAnchorMap")
                .getAsJsonArray("anchors").get(0).getAsJsonObject();
        assertEquals("geomantia:test_house", anchor.get("templateId").getAsString());
        assertEquals(20, anchor.getAsJsonObject("rawSize").get("width").getAsInt());
        assertFalse(anchor.has("structureId"));
    }

    @Test
    void d4FinalQualityCannotPassWhenCompiledPreviewCarriesVisualOrConnectivityHardBlocks()
            throws Exception {
        Fixture fixture = fixture();
        JsonObject plan = singleAnchorPlan(fixture.review());
        plan.add("arrayVisualQuality", JsonParser.parseString("""
                {"passed":false,"hardBlocks":["civic: COMPACT_ALLEY_MISSING"]}
                """).getAsJsonObject());
        plan.add("compilationAcceptance", JsonParser.parseString("""
                {"passed":false,"previewCompiled":true,
                 "hardBlocks":["REQUIRED_GROUP_RELATION_GRAPH_DISCONNECTED"]}
                """).getAsJsonObject());

        CityStructureAnchorPlanner.Result result = new CityStructureAnchorPlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(), plan);

        assertFalse(result.qualityReport().get("passed").getAsBoolean());
        assertEquals(0, result.qualityReport().get("score").getAsInt());
        assertTrue(result.qualityReport().getAsJsonArray("hardBlocks").toString()
                .contains("COMPACT_ALLEY_MISSING"));
        assertTrue(result.qualityReport().getAsJsonArray("hardBlocks").toString()
                .contains("REQUIRED_GROUP_RELATION_GRAPH_DISCONNECTED"));
        assertTrue(result.structureAnchorPlan().getAsJsonArray("anchors").size() > 0,
                "failed acceptance must preserve the compiled preview for review");
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
    void wallPlannerKeepsD5WallLineAndDeclaresNoRelineDowngradePolicies() {
        JsonObject reservation = syntheticWallReservation();
        JsonObject wallPlan = new CityWallPlanner().plan(syntheticWallLedger(), reservation,
                roadMaskFromBlocks("city_test", new int[][]{}), CityWallPlanner.Options.defaults());

        assertEquals("city_wall_plan", wallPlan.get("schema").getAsString());
        assertEquals("d5_final_wall_line", wallPlan.get("wallBoundaryMode").getAsString());
        assertEquals(reservation.getAsJsonArray("wallLine").toString(),
                wallPlan.getAsJsonArray("wallLine").toString());
        assertEquals("disabled_wall_no_reline_after_d5", wallPlan.get("wallContourMode").getAsString());
        assertEquals("keep_gate_opening_or_downgrade_without_reline",
                wallPlan.get("gateFailurePolicy").getAsString());
        assertEquals("downgrade_to_wall_or_skip_without_reline",
                wallPlan.get("beaconFailurePolicy").getAsString());
        assertEquals(8, wallPlan.get("wallUnitLengthBlocks").getAsInt());
        assertEquals(9, wallPlan.get("nominalWallHeightBlocks").getAsInt());
        assertEquals(32, wallPlan.get("waterRunMinBlocks").getAsInt());
        assertEquals(7, wallPlan.get("heightSegmentMaxDeltaBlocks").getAsInt());
        assertEquals(16, wallPlan.get("heightSteppedTransitionMaxDeltaBlocks").getAsInt());
        assertEquals(17, wallPlan.get("naturalBoundaryMinDeltaBlocks").getAsInt());
        JsonObject terrain = wallPlan.getAsJsonObject("terrainFitPolicy");
        assertEquals("segmented_surface_datum", terrain.get("heightStrategy").getAsString());
        assertEquals("natural_cliff_boundary_no_wall", terrain.get("cliffPolicy").getAsString());
        assertEquals("surfaceY/topBlock/fluid/biome/temperature/flags",
                wallPlan.getAsJsonObject("surfaceCachePolicy").get("requiredFields").getAsString());
        assertTrue(wallPlan.getAsJsonArray("wallUnits").toString()
                .contains("surface_cache_1_block_median_at_execute"));
        assertTrue(wallPlan.getAsJsonArray("wallUnits").toString().contains("D5_GATE_SLOT_OPENING"));
        assertTrue(wallPlan.getAsJsonArray("wallNodes").toString().contains("beacon_5x5"));
        assertEquals("X", wallNodeAxis(wallPlan, "node_0"));
        assertEquals("Z", wallNodeAxis(wallPlan, "node_2"));
        assertTrue(wallPlan.getAsJsonObject("wallGraphValidation").get("noRelineAfterD5").getAsBoolean());
    }

    @Test
    void wallPlannerHardStopsWhenD7ActualFootprintExceedsD5Coverage() {
        JsonObject reservation = syntheticWallReservation();
        JsonObject ledger = JsonParser.parseString("""
                {
                  "schema": "city_placed_structure_ledger",
                  "cityId": "city_test",
                  "placedStructures": [
                    {"anchorId": "outside", "actualFootprint": {"minX": 120, "minZ": 0, "maxX": 140, "maxZ": 20}}
                  ]
                }
                """).getAsJsonObject();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new CityWallPlanner().plan(ledger, reservation,
                        roadMaskFromBlocks("city_test", new int[][]{}), CityWallPlanner.Options.defaults()));

        assertTrue(ex.getMessage().contains("D5_LOCKED_FOOTPRINT_OUTSIDE_RESERVATION"));
    }

    @Test
    void landformReviewBuilderIncludesPaddingCellsAcrossGisRegions() {
        GisSampleConfig sampleConfig = GisSampleConfig.defaults().withCellStepBlocks(16);
        AtlasRegion west = new AtlasRegion("minecraft:overworld", 0, 0, sampleConfig);
        AtlasRegion east = new AtlasRegion("minecraft:overworld", 1, 0, sampleConfig);
        west.cell(31, 8).setLandformType(LandformType.PLAIN);
        east.cell(0, 8).setLandformType(LandformType.SLOPE);
        PatchMerger merger = new PatchMerger(GisClassifierConfig.defaults());
        merger.merge(west);
        merger.merge(east);

        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder(config)
                .build("city_region_edge", "realm_test", "minecraft:overworld",
                        "city_region_edge", "candidate_region_edge", 500, 128,
                        "village", "village", 8, 16, null);
        BlockBounds padded = new BlockBounds(context.bounds().minX() - 128, context.bounds().minZ() - 128,
                context.bounds().maxX() + 128, context.bounds().maxZ() + 128);

        CityLandformReviewPackage review = new com.rinsing.geomantia.systems.city.application.CityLandformReviewBuilder(config)
                .buildFromRegions(context, List.of(west, east), padded);

        assertTrue(review.landformPatches().stream()
                        .flatMap(patch -> patch.memberCells().stream())
                        .anyMatch(cell -> cell.blockMinX() == 512),
                review.asJson().toString());
    }

    @Test
    void wallTemplateLibraryIncludesGatehousesAndUsableTowers() {
        String library = CityWallTemplateCatalog.libraryJson().toString();

        assertTrue(library.contains("gatehouse_9"));
        assertTrue(library.contains("gatehouse_13"));
        assertTrue(library.contains("watchtower_5x5"));
        assertTrue(library.contains("beacon_5x5"));
    }

    @Test
    void beaconTemplateKeepsWalkableCenterAndStraightClimbAccess() throws Exception {
        Path dir = Files.createTempDirectory("city-wall-beacon-template-test");
        new MinecraftCityWallArtifactWriter().writeTemplates(dir);

        CompoundTag beacon = NbtIo.readCompressed(dir.resolve("beacon_5x5.nbt").toFile());
        assertEquals(5, beacon.getList("size", 3).getInt(0));
        assertEquals(16, beacon.getList("size", 3).getInt(1));
        assertEquals(5, beacon.getList("size", 3).getInt(2));

        ListTag palette = beacon.getList("palette", 10);
        int ladderState = -1;
        int stoneBricks = paletteState(beacon, "minecraft:stone_bricks");
        for (int i = 0; i < palette.size(); i++) {
            if ("minecraft:ladder".equals(palette.getCompound(i).getString("Name"))) {
                ladderState = i;
                break;
            }
        }
        assertTrue(ladderState >= 0, beacon.toString());

        ListTag blocks = beacon.getList("blocks", 10);
        for (int y = 1; y <= 6; y++) {
            assertFalse(hasTemplateBlockAt(blocks, 2, y, 2),
                    "center walkway must stay open at y=" + y);
        }
        assertEquals(stoneBricks, templateStateAt(blocks, 2, 7, 2),
                "wall-top passage floor must be walkable");
        for (int y = 8; y <= 12; y++) {
            assertFalse(hasTemplateBlockAt(blocks, 2, y, 2),
                    "wall-top passage headroom must stay open at y=" + y);
        }
        for (int y = 8; y <= 11; y++) {
            assertFalse(hasTemplateBlockAt(blocks, 0, y, 2),
                    "left wall-top connection must stay open at y=" + y);
            assertFalse(hasTemplateBlockAt(blocks, 4, y, 2),
                    "right wall-top connection must stay open at y=" + y);
        }
        for (int y = 1; y <= 3; y++) {
            assertFalse(hasTemplateBlockAt(blocks, 2, y, 0),
                    "front doorway must stay open at y=" + y);
            assertFalse(hasTemplateBlockAt(blocks, 2, y, 4),
                    "back doorway must stay open at y=" + y);
            assertFalse(hasTemplateBlockAt(blocks, 0, y, 2),
                    "left wall connection doorway must stay open at y=" + y);
            assertFalse(hasTemplateBlockAt(blocks, 4, y, 2),
                    "right wall connection doorway must stay open at y=" + y);
        }
        for (int y = 1; y <= 13; y++) {
            assertEquals(ladderState, templateStateAt(blocks, 2, y, 3),
                    "straight climb access must be continuous at y=" + y);
        }
    }

    @Test
    void gatehouseTemplateUsesCompactStoneCappedOpening() throws Exception {
        Path dir = Files.createTempDirectory("city-wall-gatehouse-template-test");
        new MinecraftCityWallArtifactWriter().writeTemplates(dir);

        CompoundTag gatehouse = NbtIo.readCompressed(dir.resolve("gatehouse_9.nbt").toFile());
        int stoneBricks = paletteState(gatehouse, "minecraft:stone_bricks");
        int oakFence = paletteState(gatehouse, "minecraft:oak_fence");
        ListTag blocks = gatehouse.getList("blocks", 10);

        assertEquals(oakFence, templateStateAt(blocks, 3, 1, 3));
        assertFalse(hasTemplateBlockAt(blocks, 4, 1, 3));
        assertEquals(oakFence, templateStateAt(blocks, 5, 1, 3));
        assertEquals(stoneBricks, templateStateAt(blocks, 4, 5, 3));
        assertEquals(stoneBricks, templateStateAt(blocks, 4, 6, 3));
    }

    @Test
    void wallBackendFoundationDepthUsesOriginalSurfaceSurfaceHeight() throws Exception {
        Method depth = Class.forName("com.rinsing.geomantia.systems.city.infrastructure.world.CityWallPlacementBackend")
                .getDeclaredMethod("foundationDepth", int.class, int.class, int.class);
        depth.setAccessible(true);

        assertEquals(3, ((Number) depth.invoke(null, 70, 67, 64)).intValue());
        assertEquals(0, ((Number) depth.invoke(null, 70, 71, 64)).intValue());
        assertEquals(2, ((Number) depth.invoke(null, 70, 60, 2)).intValue());
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
        source.addProperty("schema", "terrasense_structure_profile_source");
        source.addProperty("sourceType", "debug_catalog");
        source.addProperty("catalogMode", "debug");
        source.addProperty("debugCatalogPath", catalogPath.toString());
        return new Fixture(baseDir, context, review, source);
    }

    private static Fixture arrayFixture() throws Exception {
        Path baseDir = Files.createTempDirectory("city-structure-array");
        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder(config)
                .build("city_test", "realm_test", "minecraft:overworld",
                        "city_test", "candidate_test", 0, 0,
                        "village", "village", 220, 4, null);
        CityLandformReviewPackage review = new com.rinsing.geomantia.systems.city.application.CityLandformReviewBuilder(config)
                .build(context, List.of(
                        patch("plain_big", LandformType.PLAIN, -320, -320, 320, 320)));
        Path catalogPath = baseDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());
        JsonObject source = new JsonObject();
        source.addProperty("schema", "terrasense_structure_profile_source");
        source.addProperty("sourceType", "debug_catalog");
        source.addProperty("catalogMode", "debug");
        source.addProperty("debugCatalogPath", catalogPath.toString());
        return new Fixture(baseDir, context, review, source);
    }

    private static CityLandformReviewPackage preciseMemberCellReview() {
        return CityLandformReviewPackage.fromJson(JsonParser.parseString("""
                {
                  "schema": "city_landform_review",
                  "cityId": "city_test",
                  "grid": {
                    "originBlockX": -32,
                    "originBlockZ": -32,
                    "cellStepBlocks": 16,
                    "cellsX": 4,
                    "cellsZ": 4
                  },
                  "targetScale": {
                    "scale": "town",
                    "radiusBlocks": 64,
                    "cellStepBlocks": 16
                  },
                  "reviewMapImage": "review.png",
                  "legend": [
                    {"color": "#2196F3", "label": "水域", "landformType": "water"}
                  ],
                  "landformPatches": [
                    {
                      "landformPatchId": "water_cells",
                      "mapLabel": "水域01",
                      "displayLandformName": "水域",
                      "landformType": "water",
                      "areaBlocks": 512,
                      "cellCount": 2,
                      "areaClass": "small",
                      "centerBlock": {"x": 8, "z": 8},
                      "blockBounds": {"minX": 0, "minZ": 0, "maxX": 31, "maxZ": 15},
                      "geometryMode": "patch_member_cells",
                      "memberCells": [
                        {"cellX": 0, "cellZ": 0, "blockMinX": 0, "blockMinZ": 0},
                        {"cellX": 1, "cellZ": 0, "blockMinX": 16, "blockMinZ": 0}
                      ],
                      "metricsSummary": {
                        "meanElevation": 63,
                        "minElevation": 62,
                        "maxElevation": 64,
                        "meanSlope": 0.1,
                        "meanWaterDistance": 0
                      },
                      "landformTags": [],
                      "overlayTags": [],
                      "summaryFacts": ["精细水体格子"],
                      "neighborLandformPatchIds": []
                    }
                  ],
                  "planningContext": [],
                  "aiPromptContext": "city test",
                  "debugRefs": []
                }
                """).getAsJsonObject());
    }

    private static JsonObject anchorPlan(CityLandformReviewPackage review) {
        LandformPatchSummary first = review.landformPatches().get(0);
        LandformPatchSummary second = review.landformPatches().get(1);
        return JsonParser.parseString("""
                {
                  "schema": "city_structure_anchor_plan",
                  "cityId": "city_test",
                  "anchors": [
                    {
                      "anchorId": "anchor_pyramid",
                      "templateId": "geomantia:test_house",
                      "templateRef": "geomantia:city/test_house",
                      "templateHash": "sha256:test-house",
                      "variantId": "fixed_v1",
                      "rawSize": {"width": 20, "height": 10, "depth": 12},
                      "sourcePatchIds": ["%s"],
                      "anchorBlock": {"x": %d, "z": %d},
                      "rotation": "NONE",
                      "mirror": "NONE",
                      "clearanceBlocks": 8,
                      "terrainPosePolicy": "structure_start_beard_thin",
                      "materializationSource": "structure_template_nbt",
                      "intentTerms": ["function.landmark"],
                      "priority": 1,
                      "roadAccessIntent": "primary_access"
                    },
                    {
                      "anchorId": "anchor_village",
                      "templateId": "geomantia:test_village",
                      "templateRef": "geomantia:city/test_village",
                      "templateHash": "sha256:test-village",
                      "variantId": "fixed_v1",
                      "rawSize": {"width": 18, "height": 12, "depth": 18},
                      "sourcePatchIds": ["%s"],
                      "anchorBlock": {"x": %d, "z": %d},
                      "rotation": "NONE",
                      "mirror": "NONE",
                      "clearanceBlocks": 8,
                      "terrainPosePolicy": "structure_start_beard_thin",
                      "materializationSource": "structure_template_nbt",
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
        JsonObject plan = JsonParser.parseString("""
                {
                  "schema": "city_d4_design_slot_plan",
                  "cityId": "city_test",
                  "placementOrder": ["admin_core", "residential_01"],
                  "slots": [
                    {
                      "slotId": "admin_core",
                      "displayRole": "行政核心",
                      "candidatePatchRefs": ["%s"],
                      "templateIds": ["geomantia:test_house"],
                      "variantId": "fixed_v1",
                      "relationHints": []
                    },
                    {
                      "slotId": "residential_01",
                      "displayRole": "住宅",
                      "candidatePatchRefs": ["%s"],
                      "templateIds": ["geomantia:test_house"],
                      "variantId": "fixed_v1",
                      "relationHints": [
                        {"targetSlotId": "admin_core", "distanceBand": "near"}
                      ]
                    }
                  ]
                }
                """.formatted(first.landformPatchId(), second.landformPatchId())).getAsJsonObject();
        plan.add("templateCatalog", fixedTemplateCatalog());
        return plan;
    }

    private static JsonObject arrayCandidatePlan(CityLandformReviewPackage review, int arrayCount) {
        LandformPatchSummary first = review.landformPatches().get(0);
        JsonObject plan = JsonParser.parseString("""
                {
                  "schema": "city_d4_array_candidate_plan",
                  "cityId": "city_test",
                  "arrayId": "residential_cluster",
                  "displayRole": "住宅阵列",
                  "candidatePatchRefs": ["%s"],
                  "templateIds": ["geomantia:test_house", "geomantia:test_village"],
                  "variantId": "fixed_v1",
                  "arrayCount": %d,
                  "patterns": ["loose_cluster", "patch_axis_band", "scattered"]
                }
                """.formatted(first.landformPatchId(), arrayCount)).getAsJsonObject();
        plan.add("templateCatalog", fixedTemplateCatalog());
        return plan;
    }

    private static JsonObject singleAnchorPlan(CityLandformReviewPackage review) {
        LandformPatchSummary first = review.landformPatches().get(0);
        return JsonParser.parseString("""
                {
                  "schema": "city_structure_anchor_plan",
                  "cityId": "city_test",
                  "anchors": [
                    {
                      "anchorId": "anchor_pyramid",
                      "templateId": "geomantia:test_house",
                      "templateRef": "geomantia:city/test_house",
                      "templateHash": "sha256:test-house",
                      "variantId": "fixed_v1",
                      "rawSize": {"width": 20, "height": 10, "depth": 12},
                      "sourcePatchIds": ["%s"],
                      "anchorBlock": {"x": %d, "z": %d},
                      "rotation": "NONE",
                      "mirror": "NONE",
                      "clearanceBlocks": 8,
                      "terrainPosePolicy": "structure_start_beard_thin",
                      "materializationSource": "structure_template_nbt",
                      "intentTerms": ["function.landmark"],
                      "priority": 1,
                      "roadAccessIntent": "primary_access"
                    }
                  ]
                }
                """.formatted(first.landformPatchId(), first.centerBlock().x(), first.centerBlock().z()))
                .getAsJsonObject();
    }

    private static JsonObject fixedTemplateCatalog() {
        return JsonParser.parseString("""
                {"schema":"city_template_catalog","templates":[
                  {"buildingSemantic":"house","style":"test","templateId":"geomantia:test_house",
                   "templateRef":"geomantia:city/test_house","contentHash":"sha256:test-house",
                   "variantId":"fixed_v1","rawSize":{"width":20,"height":10,"depth":12},
                   "allowedRotations":["NONE","CLOCKWISE_90"],"allowedMirrors":["NONE"],
                   "roadEntrances":[{"entranceId":"front","x":10,"z":11,"direction":"SOUTH"}],
                   "terrainPosePolicy":"structure_start_beard_thin","supportPolicy":"none","clearanceBlocks":8},
                  {"buildingSemantic":"village","style":"test","templateId":"geomantia:test_village",
                   "templateRef":"geomantia:city/test_village","contentHash":"sha256:test-village",
                   "variantId":"fixed_v1","rawSize":{"width":18,"height":12,"depth":18},
                   "allowedRotations":["NONE","CLOCKWISE_90"],"allowedMirrors":["NONE"],
                   "roadEntrances":[{"entranceId":"front","x":9,"z":17,"direction":"SOUTH"}],
                   "terrainPosePolicy":"structure_start_beard_thin","supportPolicy":"none","clearanceBlocks":8}
                ]}
                """).getAsJsonObject();
    }

    private static JsonObject legalRegion(String selectionRef,
                                          CityLandformReviewPackage review,
                                          int minX,
                                          int minZ,
                                          int maxX,
                                          int maxZ) {
        int step = review.grid().cellStepBlocks();
        JsonObject region = new JsonObject();
        region.addProperty("schema", "patch_selection_legal_region");
        region.addProperty("patchSelectionRef", selectionRef);
        region.addProperty("cellStepBlocks", step);
        JsonObject bounds = new JsonObject();
        bounds.addProperty("minX", minX);
        bounds.addProperty("minZ", minZ);
        bounds.addProperty("maxX", maxX);
        bounds.addProperty("maxZ", maxZ);
        region.add("bounds", bounds);
        JsonArray members = new JsonArray();
        for (int z = minZ; z <= maxZ; z += step) {
            for (int x = minX; x <= maxX; x += step) {
                JsonObject cell = new JsonObject();
                cell.addProperty("gridX", Math.floorDiv(x, step));
                cell.addProperty("gridZ", Math.floorDiv(z, step));
                cell.addProperty("blockMinX", x);
                cell.addProperty("blockMinZ", z);
                cell.addProperty("blockMaxX", x + step - 1);
                cell.addProperty("blockMaxZ", z + step - 1);
                members.add(cell);
            }
        }
        region.add("memberCells", members);
        return region;
    }

    private static boolean regionContains(JsonObject region, BlockBounds bounds) {
        int step = region.get("cellStepBlocks").getAsInt();
        Set<String> members = new HashSet<>();
        for (JsonElement element : region.getAsJsonArray("memberCells")) {
            JsonObject cell = element.getAsJsonObject();
            members.add(cell.get("gridX").getAsInt() + ":" + cell.get("gridZ").getAsInt());
        }
        int minCellX = Math.floorDiv(bounds.minX(), step);
        int maxCellX = Math.floorDiv(bounds.maxX(), step);
        int minCellZ = Math.floorDiv(bounds.minZ(), step);
        int maxCellZ = Math.floorDiv(bounds.maxZ(), step);
        for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                if (!members.contains(cellX + ":" + cellZ)) {
                    return false;
                }
            }
        }
        return true;
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
                  "schema": "city_semantic_profile_catalog",
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
                      "reviewState": "approved",
                      "functionTerms": ["function.landmark"],
                      "planningRoleTerms": ["planning_role.key"],
                      "terrainModes": ["SURFACE"],
                      "fixedFootprint": {"widthBlocks": 20, "depthBlocks": 12, "heightBlocks": 10},
                      "allowedRotations": ["NONE", "CLOCKWISE_90"],
                      "clearanceBlocks": 2
                    },
                    {
                      "structureId": "minecraft:jungle_pyramid",
                      "sourceProfileRef": "synthetic://unit-test/jungle_pyramid",
                      "profileType": "single",
                      "sampleType": "structure_assembly",
                      "placementKind": "minecraft_place_structure",
                      "placementCommand": "place structure minecraft:jungle_pyramid <x> <y> <z>",
                      "footprintMode": "fixed_footprint",
                      "reviewState": "approved",
                      "functionTerms": ["function.house"],
                      "planningRoleTerms": ["planning_role.fill"],
                      "terrainModes": ["SURFACE"],
                      "fixedFootprint": {"widthBlocks": 16, "depthBlocks": 16, "heightBlocks": 10},
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
                      "reviewState": "approved",
                      "functionTerms": ["function.village"],
                      "planningRoleTerms": ["planning_role.fill"],
                      "terrainModes": ["SURFACE"],
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
                  "schema": "city_semantic_profile_catalog",
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
                      "reviewState": "approved",
                      "functionTerms": ["function.farm"],
                      "planningRoleTerms": ["planning_role.fill"],
                      "terrainModes": ["SURFACE"],
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

    private static JsonObject staticJigsawSource(Fixture fixture) throws Exception {
        JsonObject catalog = JsonParser.parseString(trekDebugCatalog()).getAsJsonObject();
        JsonObject structure = catalog.getAsJsonArray("structures").get(0).getAsJsonObject();
        structure.addProperty("footprintMode", "fixed_footprint");
        structure.add("fixedFootprint", JsonParser.parseString("""
                {"widthBlocks": 17, "depthBlocks": 23, "heightBlocks": 11}
                """).getAsJsonObject());
        structure.add("expectedAreaRange", JsonParser.parseString("""
                {"minAreaBlocks": 256, "maxAreaBlocks": 1024,
                 "startFootprint": {"widthBlocks": 17, "depthBlocks": 23, "heightBlocks": 11}}
                """).getAsJsonObject());
        structure.addProperty("maxDistanceFromCenterBlocks", 24);
        Path catalogPath = fixture.baseDir().resolve("static_jigsaw_debug_catalog.json");
        Files.writeString(catalogPath, CityJson.GSON.toJson(catalog));
        JsonObject source = new JsonObject();
        source.addProperty("schema", "terrasense_structure_profile_source");
        source.addProperty("sourceType", "debug_catalog");
        source.addProperty("catalogMode", "debug");
        source.addProperty("debugCatalogPath", catalogPath.toString());
        return source;
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private static JsonObject syntheticWallLedger() {
        return JsonParser.parseString("""
                {
                  "schema": "city_placed_structure_ledger",
                  "cityId": "city_test",
                  "placedStructures": [
                    {"anchorId": "a", "actualFootprint": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8}}
                  ]
                }
                """).getAsJsonObject();
    }

    private static JsonObject syntheticWideWallLedger() {
        return JsonParser.parseString("""
                {
                  "schema": "city_placed_structure_ledger",
                  "cityId": "city_test",
                  "placedStructures": [
                    {"anchorId": "a", "actualFootprint": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8}},
                    {"anchorId": "b", "actualFootprint": {"minX": 108, "minZ": -4, "maxX": 132, "maxZ": 12}}
                  ]
                }
                """).getAsJsonObject();
    }

    private static JsonObject syntheticWallReservation() {
        return JsonParser.parseString("""
                {
                  "schema": "city_wall_reservation_plan",
                  "cityId": "city_test",
                  "boundarySource": "d4_planned_footprint_envelope_rectilinear_hull",
                  "wallBounds": {"minX": -64, "minZ": -64, "maxX": 64, "maxZ": 64},
                  "wallCoverageBounds": {"minX": -96, "minZ": -96, "maxX": 96, "maxZ": 96},
                  "wallLine": [
                    {"lineId": "north", "segmentId": "north", "sideHint": "north",
                     "blockBounds": {"minX": -64, "minZ": -68, "maxX": 64, "maxZ": -60}},
                    {"lineId": "east", "segmentId": "east", "sideHint": "east",
                     "blockBounds": {"minX": 60, "minZ": -64, "maxX": 68, "maxZ": 64}}
                  ],
                  "wallCorridorMask": [
                    {"maskId": "wall_corridor_0", "maskType": "wall_reservation_corridor",
                     "blockBounds": {"minX": -64, "minZ": -68, "maxX": 64, "maxZ": -60}},
                    {"maskId": "wall_corridor_1", "maskType": "wall_reservation_corridor",
                     "blockBounds": {"minX": 60, "minZ": -64, "maxX": 68, "maxZ": 64}}
                  ],
                  "gateSlots": [
                    {"gateSlotId": "gate_0", "sideHint": "north",
                     "blockBounds": {"minX": -4, "minZ": -68, "maxX": 4, "maxZ": -60}}
                  ],
                  "wallNodeSlots": [
                    {"nodeSlotId": "node_0", "nodeType": "beacon_tower", "templateId": "beacon_5x5",
                     "blockBounds": {"minX": -34, "minZ": -66, "maxX": -30, "maxZ": -62}},
                    {"nodeSlotId": "node_1", "nodeType": "corner_tower", "templateId": "watchtower_5x5",
                     "blockBounds": {"minX": 62, "minZ": -66, "maxX": 66, "maxZ": -62}},
                    {"nodeSlotId": "node_2", "nodeType": "beacon_tower", "templateId": "beacon_5x5",
                     "blockBounds": {"minX": 62, "minZ": 30, "maxX": 66, "maxZ": 34}}
                  ]
                }
                """).getAsJsonObject();
    }

    private static JsonObject syntheticV4Reservation() {
        return JsonParser.parseString("""
                {
                  "schema": "city_wall_reservation_plan",
                  "cityId": "city_test",
                  "boundarySource": "actual_footprint_land_ring_deferred_to_d7",
                  "wallBounds": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": 32},
                  "seedPatches": [
                    {"landformPatchId": "plain_0", "landformType": "plain",
                     "blockBounds": {"minX": -64, "minZ": -64, "maxX": 64, "maxZ": 64}}
                  ],
                  "cityDomainMask": [
                    {"blockBounds": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": 32}}
                  ],
                  "wallCenterline": [],
                  "gateCandidateZones": []
                }
                """).getAsJsonObject();
    }

    private static JsonObject syntheticV4WaterReservation() {
        return JsonParser.parseString("""
                {
                  "schema": "city_wall_reservation_plan",
                  "cityId": "city_test",
                  "boundarySource": "actual_footprint_land_ring_deferred_to_d7",
                  "wallBounds": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": 32},
                  "seedPatches": [
                    {"landformPatchId": "lake_big", "landformType": "water",
                     "blockBounds": {"minX": -80, "minZ": -72, "maxX": 80, "maxZ": -40}},
                    {"landformPatchId": "plain_0", "landformType": "plain",
                     "blockBounds": {"minX": -80, "minZ": -32, "maxX": 80, "maxZ": 80}}
                  ],
                  "cityDomainMask": [
                    {"blockBounds": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": 32}}
                  ],
                  "wallCenterline": [],
                  "gateCandidateZones": []
                }
                """).getAsJsonObject();
    }

    private static JsonObject syntheticV4PreciseWaterReservation() {
        return JsonParser.parseString("""
                {
                  "schema": "city_wall_reservation_plan",
                  "cityId": "city_test",
                  "boundarySource": "actual_footprint_land_ring_deferred_to_d7",
                  "wallBounds": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": 32},
                  "seedPatches": [
                    {
                      "landformPatchId": "lake_big",
                      "landformType": "water",
                      "geometryMode": "patch_member_cells",
                      "cellStepBlocks": 16,
                      "blockBounds": {"minX": -80, "minZ": -80, "maxX": 80, "maxZ": 80},
                      "memberCells": [
                        {"cellX": -2, "cellZ": -3, "blockMinX": -32, "blockMinZ": -48},
                        {"cellX": -1, "cellZ": -3, "blockMinX": -16, "blockMinZ": -48},
                        {"cellX": 0, "cellZ": -3, "blockMinX": 0, "blockMinZ": -48},
                        {"cellX": 1, "cellZ": -3, "blockMinX": 16, "blockMinZ": -48},
                        {"cellX": 2, "cellZ": -3, "blockMinX": 32, "blockMinZ": -48}
                      ]
                    },
                    {"landformPatchId": "plain_0", "landformType": "plain",
                     "blockBounds": {"minX": -80, "minZ": -32, "maxX": 80, "maxZ": 80}}
                  ],
                  "cityDomainMask": [
                    {"blockBounds": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": 32}}
                  ],
                  "wallCenterline": [],
                  "gateCandidateZones": []
                }
                """).getAsJsonObject();
    }

    private static JsonObject syntheticV4TerrainContourReservation() {
        return JsonParser.parseString("""
                {
                  "schema": "city_wall_reservation_plan",
                  "cityId": "city_test",
                  "boundarySource": "actual_footprint_land_ring_deferred_to_d7",
                  "wallBounds": {"minX": -64, "minZ": -64, "maxX": 64, "maxZ": 64},
                  "seedPatches": [
                    {"landformPatchId": "plain_0", "landformType": "plain",
                     "blockBounds": {"minX": -96, "minZ": -96, "maxX": 96, "maxZ": 96}}
                  ],
                  "cityDomainMask": [
                    {"maskId": "city_domain_cell_0", "maskType": "city_domain_cell", "blockBounds": {"minX": -32, "minZ": -32, "maxX": -17, "maxZ": -17}},
                    {"maskId": "city_domain_cell_1", "maskType": "city_domain_cell", "blockBounds": {"minX": -16, "minZ": -32, "maxX": -1, "maxZ": -17}},
                    {"maskId": "city_domain_cell_2", "maskType": "city_domain_cell", "blockBounds": {"minX": 0, "minZ": -32, "maxX": 15, "maxZ": -17}},
                    {"maskId": "city_domain_cell_3", "maskType": "city_domain_cell", "blockBounds": {"minX": 16, "minZ": -16, "maxX": 31, "maxZ": -1}},
                    {"maskId": "city_domain_cell_4", "maskType": "city_domain_cell", "blockBounds": {"minX": -32, "minZ": -16, "maxX": -17, "maxZ": -1}},
                    {"maskId": "city_domain_cell_5", "maskType": "city_domain_cell", "blockBounds": {"minX": -16, "minZ": -16, "maxX": -1, "maxZ": -1}},
                    {"maskId": "city_domain_cell_6", "maskType": "city_domain_cell", "blockBounds": {"minX": 0, "minZ": -16, "maxX": 15, "maxZ": -1}},
                    {"maskId": "city_domain_cell_7", "maskType": "city_domain_cell", "blockBounds": {"minX": 16, "minZ": 0, "maxX": 31, "maxZ": 15}},
                    {"maskId": "city_domain_cell_8", "maskType": "city_domain_cell", "blockBounds": {"minX": -32, "minZ": 0, "maxX": -17, "maxZ": 15}},
                    {"maskId": "city_domain_cell_9", "maskType": "city_domain_cell", "blockBounds": {"minX": -16, "minZ": 0, "maxX": -1, "maxZ": 15}},
                    {"maskId": "city_domain_cell_10", "maskType": "city_domain_cell", "blockBounds": {"minX": 0, "minZ": 0, "maxX": 15, "maxZ": 15}},
                    {"maskId": "city_domain_cell_11", "maskType": "city_domain_cell", "blockBounds": {"minX": 16, "minZ": 16, "maxX": 31, "maxZ": 31}}
                  ],
                  "wallCenterline": [],
                  "gateCandidateZones": []
                }
                """).getAsJsonObject();
    }

    private static JsonObject syntheticEastWallReservation() {
        return JsonParser.parseString("""
                {
                  "schema": "city_wall_reservation_plan",
                  "cityId": "city_test",
                  "wallBounds": {"minX": -64, "minZ": -64, "maxX": 64, "maxZ": 64},
                  "cityDomainMask": [
                    {"blockBounds": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": 32}}
                  ],
                  "wallCenterline": [
                    {"segmentId": "east", "blockBounds": {"minX": 60, "minZ": -64, "maxX": 64, "maxZ": 64}}
                  ],
                  "gateCandidateZones": [
                    {"blockBounds": {"minX": 60, "minZ": -4, "maxX": 64, "maxZ": 4}}
                  ]
                }
                """).getAsJsonObject();
    }

    private static JsonObject roadMaskFromBlocks(String cityId, int[][] blocks) {
        return roadMaskFromBlocks(cityId, blocks, "");
    }

    private static JsonObject roadMaskFromBlocks(String cityId, int[][] blocks, String blockId) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schema", "city_actual_road_mask");
        obj.addProperty("cityId", cityId);
        obj.addProperty("status", blocks.length == 0 ? "empty" : "observed");
        JsonArray roadMask = new JsonArray();
        for (int i = 0; i < blocks.length; i++) {
            JsonObject mask = new JsonObject();
            mask.addProperty("maskId", "road_" + i);
            mask.addProperty("maskType", "actual_road");
            if (!blockId.isBlank()) {
                mask.addProperty("blockId", blockId);
            }
            mask.add("blockBounds", boundsJson(new BlockBounds(blocks[i][0], blocks[i][1], blocks[i][0], blocks[i][1])));
            roadMask.add(mask);
        }
        obj.add("roadMask", roadMask);
        return obj;
    }

    private static CityWallPlanner.TerrainOptions terrainOptions() {
        return new CityWallPlanner.TerrainOptions(
                24, 5, "v3.1", 7, 16, 6, 17, true,
                "v3.3", 48, 24, 4096, 32);
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(
                obj.get("minX").getAsInt(),
                obj.get("minZ").getAsInt(),
                obj.get("maxX").getAsInt(),
                obj.get("maxZ").getAsInt());
    }

    private static BlockBounds expand(BlockBounds bounds, int margin) {
        return new BlockBounds(bounds.minX() - margin, bounds.minZ() - margin,
                bounds.maxX() + margin, bounds.maxZ() + margin);
    }

    private static void assertGroupItemsDoNotOverlap(JsonObject group) {
        JsonArray items = group.getAsJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            BlockBounds a = bounds(items.get(i).getAsJsonObject()
                    .getAsJsonObject("estimatedCollisionEnvelope"));
            for (int j = i + 1; j < items.size(); j++) {
                BlockBounds b = bounds(items.get(j).getAsJsonObject()
                        .getAsJsonObject("estimatedCollisionEnvelope"));
                assertFalse(a.overlaps(b), "array items overlap: " + i + " / " + j);
            }
        }
    }

    private static void assertAnyArrayItemMaskOverlap(JsonObject group) {
        JsonArray items = group.getAsJsonArray("items");
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

    private static void assertArrayCandidateShape(Fixture fixture,
                                                  String pattern,
                                                  String shape,
                                                  int rows,
                                                  int columns,
                                                  int targetCount) throws Exception {
        JsonObject plan = arrayCandidatePlan(fixture.review(), targetCount);
        plan.add("patterns", JsonParser.parseString("""
                ["%s"]
                """.formatted(pattern)).getAsJsonArray());
        plan.add("structureIds", JsonParser.parseString("""
                ["minecraft:desert_pyramid"]
                """).getAsJsonArray());
        plan.add("compoundCluster", JsonParser.parseString("""
                {"shape": "%s", "rows": %d, "columns": %d, "spacingBlocks": 64}
                """.formatted(shape, rows, columns)).getAsJsonObject());

        CityStructureArrayCandidatePlanner.Result result = new CityStructureArrayCandidatePlanner()
                .plan(fixture.baseDir(), fixture.review(), fixture.terraSenseSource(),
                        plan, new JsonObject(), new JsonArray());

        assertTrue(result.asJson().get("ok").getAsBoolean(), pattern + "/" + shape);
        JsonArray groups = result.arrayCandidateSet().getAsJsonArray("arrayCandidates");
        assertFalse(groups.isEmpty());
        JsonObject group = groups.get(0).getAsJsonObject();
        assertEquals(pattern, group.get("arrayPattern").getAsString());
        assertEquals(shape, group.get("arrayShape").getAsString());
        assertEquals(64, group.get("spacingBlocks").getAsInt());
        assertEquals(targetCount, group.getAsJsonArray("items").size(), pattern + "/" + shape);
        assertGroupItemsDoNotOverlap(group);
        assertShapePoints(shape, group.getAsJsonArray("items"));
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
                assertEveryPoint(items, point ->
                        point.x() == minX || point.x() == maxX || point.z() == minZ || point.z() == maxZ);
                assertFalse(hasPoint(items, xs.get(1), zs.get(1)), "courtyard center must stay open");
            }
            case "l_shape" -> {
                assertEquals(3, xs.size(), "l_shape should expose three columns");
                assertEquals(3, zs.size(), "l_shape should expose three rows");
                int minX = xs.get(0);
                int maxZ = zs.get(zs.size() - 1);
                assertEveryPoint(items, point -> point.x() == minX || point.z() == maxZ);
            }
            case "u_shape" -> {
                assertEquals(3, xs.size(), "u_shape should expose three columns");
                assertEquals(3, zs.size(), "u_shape should expose three rows");
                int minX = xs.get(0);
                int maxX = xs.get(xs.size() - 1);
                int maxZ = zs.get(zs.size() - 1);
                assertEveryPoint(items, point ->
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

    private static void assertEveryPoint(JsonArray items, PointRule rule) {
        for (JsonElement elem : items) {
            JsonObject point = elem.getAsJsonObject().getAsJsonObject("anchorBlock");
            assertTrue(rule.accepts(new TestPoint(point.get("x").getAsInt(), point.get("z").getAsInt())),
                    "point should match expected shape outline: " + point);
        }
    }

    private static void assertClusterGroupItemsDoNotOverlap(JsonObject group) {
        JsonArray items = group.getAsJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            BlockBounds a = bounds(items.get(i).getAsJsonObject()
                    .getAsJsonObject("estimatedCollisionEnvelope"));
            for (int j = i + 1; j < items.size(); j++) {
                BlockBounds b = bounds(items.get(j).getAsJsonObject()
                        .getAsJsonObject("estimatedCollisionEnvelope"));
                assertFalse(a.overlaps(b), "cluster group items overlap: " + i + " / " + j);
            }
        }
    }

    private static boolean hasTemplateBlockAt(ListTag blocks, int x, int y, int z) {
        return templateStateAt(blocks, x, y, z) >= 0;
    }

    private static String wallNodeAxis(JsonObject wallPlan, String sourceNodeSlotId) {
        for (com.google.gson.JsonElement elem : wallPlan.getAsJsonArray("wallNodes")) {
            JsonObject node = elem.getAsJsonObject();
            if (sourceNodeSlotId.equals(node.get("sourceNodeSlotId").getAsString())) {
                return node.get("wallAxis").getAsString();
            }
        }
        return "";
    }

    private static int paletteState(CompoundTag template, String blockName) {
        ListTag palette = template.getList("palette", 10);
        for (int i = 0; i < palette.size(); i++) {
            if (blockName.equals(palette.getCompound(i).getString("Name"))) {
                return i;
            }
        }
        return -1;
    }

    private static int templateStateAt(ListTag blocks, int x, int y, int z) {
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            ListTag pos = block.getList("pos", 3);
            if (pos.getInt(0) == x && pos.getInt(1) == y && pos.getInt(2) == z) {
                return block.getInt("state");
            }
        }
        return -1;
    }

    private interface PointRule {
        boolean accepts(TestPoint point);
    }

    private record TestPoint(int x, int z) {
    }

    private record Fixture(Path baseDir, CitySiteContext context, CityLandformReviewPackage review,
                           JsonObject terraSenseSource) {
    }

}
