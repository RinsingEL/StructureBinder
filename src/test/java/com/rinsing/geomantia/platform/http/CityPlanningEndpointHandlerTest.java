package com.rinsing.geomantia.platform.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityLandformReviewBuilder;
import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityPlanningEndpointHandlerTest {

    @Test
    void handlePlanD2_restoresSealedRunMetadataWhenCellStepOmitted() throws Exception {
        Path debugRoot = Path.of("run/realm_debug");
        Path runDir = debugRoot.resolve("realm_reterraforged_t4_crop_4096_20260614");
        if (!Files.exists(runDir.resolve("city_seed_registry.json"))) {
            return;
        }

        JsonObject response = CityPlanningEndpointHandler.handlePlanD2(
                debugRoot,
                "realm_reterraforged_t4_crop_4096_20260614",
                "city_realm_salt_kingdom_0_capital",
                null);
        JsonObject context = response.getAsJsonObject("citySiteContext");
        JsonObject grid = context.getAsJsonObject("grid");
        JsonObject anchor = context.getAsJsonObject("anchorBlock");

        assertEquals("minecraft:overworld", context.get("dimensionId").getAsString());
        assertEquals(32, grid.get("cellStepBlocks").getAsInt(),
                "City planning grid clamps the sealed W/T cell step into the city-scale range");
        assertEquals(4096, anchor.get("x").getAsInt());
        assertEquals(-3584, anchor.get("z").getAsInt());
        assertEquals("capital_realm_salt_kingdom_0", context.get("siteCandidateId").getAsString());
        assertNotEquals("unknown", context.get("territoryCheckResult").getAsString());
    }

    @Test
    void handlePlanD4_readsD3PackageAndWritesArtifacts() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d4-test");
        String runId = "run_d4";
        String citySeedId = "city_test";
        Path runDir = debugRoot.resolve(runId);
        Files.createDirectories(runDir.resolve("city_d4_city_test"));
        Files.writeString(runDir.resolve("city_seed_registry.json"), """
                {
                  "citySeeds": [
                    {
                      "citySeedId": "city_test",
                      "realmId": "realm_test",
                      "role": "village",
                      "theoreticalScale": "village",
                      "anchorBlock": {"x": 0, "z": 0},
                      "planningRadiusCells": 64,
                      "candidateId": "candidate_test"
                    }
                  ]
                }
                """);

        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new CitySiteContextBuilder(config).build(
                "city_test", "realm_test", "overworld",
                "city_test", "candidate_test", 0, 0,
                "village", "village", 64, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context, List.of(
                patch("plain", LandformType.PLAIN, -50, -50, -10, -10),
                patch("shore", LandformType.SHORE, 0, 0, 50, 50)));
        Path d3Dir = runDir.resolve("city_d3_city_test");
        Files.createDirectories(d3Dir);
        Files.writeString(d3Dir.resolve("city_landform_review_package.json"),
                CityJson.GSON.toJson(review.asJson()));

        Path catalogPath = runDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());

        JsonObject response = CityPlanningEndpointHandler.handlePlanD4(
                debugRoot, runId, citySeedId,
                terraSenseSource(catalogPath),
                structureAnchorPlan(review, 1));

        assertTrue(response.get("ok").getAsBoolean());
        JsonObject artifacts = response.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("structureAnchorMap").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("structureAnchorPreview").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("qualityReport").getAsString())));
        assertFalse(response.getAsJsonObject("structureAnchorMap").getAsJsonArray("anchors").isEmpty());
    }

    @Test
    void handlePlanD4CandidatesAndSelect_writeCandidateAndStandardD4Artifacts() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d4-candidates-test");
        String runId = "run_d4_candidates";
        String citySeedId = "city_test";
        Path runDir = debugRoot.resolve(runId);
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("city_seed_registry.json"), """
                {
                  "citySeeds": [
                    {
                      "citySeedId": "city_test",
                      "realmId": "realm_test",
                      "role": "village",
                      "theoreticalScale": "village",
                      "anchorBlock": {"x": 0, "z": 0},
                      "planningRadiusCells": 64,
                      "candidateId": "candidate_test"
                    }
                  ]
                }
                """);

        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new CitySiteContextBuilder(config).build(
                "city_test", "realm_test", "overworld",
                "city_test", "candidate_test", 0, 0,
                "village", "village", 64, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context, List.of(
                patch("plain", LandformType.PLAIN, -50, -50, -10, -10),
                patch("shore", LandformType.SHORE, 0, 0, 50, 50)));
        Path d3Dir = runDir.resolve("city_d3_city_test");
        Files.createDirectories(d3Dir);
        Files.writeString(d3Dir.resolve("city_landform_review_package.json"),
                CityJson.GSON.toJson(review.asJson()));

        Path catalogPath = runDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());
        JsonObject candidates = CityPlanningEndpointHandler.handlePlanD4Candidates(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath),
                designSlotPlan(review), null);

        assertTrue(candidates.get("ok").getAsBoolean());
        assertEquals("all_slots_tentative_order_debug",
                candidates.getAsJsonObject("anchorCandidateSet").get("planningMode").getAsString());
        JsonObject artifacts = candidates.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("anchorCandidateSet").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("anchorCandidatePreview").getAsString())));
        JsonObject firstCandidate = candidates.getAsJsonObject("anchorCandidateSet")
                .getAsJsonArray("slotCandidates").get(0).getAsJsonObject()
                .getAsJsonArray("candidates").get(0).getAsJsonObject();
        JsonObject selectionPlan = JsonParser.parseString("""
                {
                  "schemaVersion": "city_d4_anchor_selection_plan.v0.1",
                  "cityId": "city_test",
                  "selectedCandidates": [
                    {
                      "slotId": "admin_core",
                      "candidateId": "%s",
                      "anchorId": "admin_core_01",
                      "selectionReason": "test selection"
                    }
                  ]
                }
                """.formatted(firstCandidate.get("candidateId").getAsString())).getAsJsonObject();

        JsonObject selected = CityPlanningEndpointHandler.handleSelectD4Candidates(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath),
                selectionPlan, null, null);

        assertTrue(selected.get("ok").getAsBoolean());
        JsonObject selectedArtifacts = selected.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(selectedArtifacts.get("structureAnchorMap").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(selectedArtifacts.get("sourceAnchorCandidateSet").getAsString())));
        assertEquals("admin_core_01", selected.getAsJsonObject("structureAnchorMap")
                .getAsJsonArray("anchors").get(0).getAsJsonObject()
                .get("anchorId").getAsString());
    }

    @Test
    void handleD4CandidateSession_writesSequentialArtifactsAndFinalizesD4() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d4-session-test");
        String runId = "run_d4_session";
        String citySeedId = "city_test";
        Path runDir = debugRoot.resolve(runId);
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("city_seed_registry.json"), """
                {
                  "citySeeds": [
                    {
                      "citySeedId": "city_test",
                      "realmId": "realm_test",
                      "role": "village",
                      "theoreticalScale": "village",
                      "anchorBlock": {"x": 0, "z": 0},
                      "planningRadiusCells": 64,
                      "candidateId": "candidate_test"
                    }
                  ]
                }
                """);

        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new CitySiteContextBuilder(config).build(
                "city_test", "realm_test", "overworld",
                "city_test", "candidate_test", 0, 0,
                "village", "village", 64, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context, List.of(
                patch("plain", LandformType.PLAIN, -50, -50, -10, -10),
                patch("shore", LandformType.SHORE, 0, 0, 50, 50),
                patch("offscreen_plain", LandformType.PLAIN, 1000, 1000, 1040, 1040)));
        Path d3Dir = runDir.resolve("city_d3_city_test");
        Files.createDirectories(d3Dir);
        Files.writeString(d3Dir.resolve("city_landform_review_package.json"),
                CityJson.GSON.toJson(review.asJson()));

        Path catalogPath = runDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());
        JsonObject created = CityPlanningEndpointHandler.handleCreateD4CandidateSession(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath),
                designSlotPlan(review), null, "session_test");
        assertTrue(created.get("ok").getAsBoolean());
        assertEquals("admin_core", created.get("currentSlotId").getAsString());
        assertTrue(Files.exists(debugRoot.resolve(created.getAsJsonObject("artifacts")
                .get("d4CandidateSession").getAsString())));

        JsonObject next = CityPlanningEndpointHandler.handlePlanD4NextCandidates(
                debugRoot, runId, citySeedId, null);
        assertTrue(next.get("ok").getAsBoolean());
        JsonObject firstCandidate = next.getAsJsonObject("slotCandidateSet")
                .getAsJsonArray("slotCandidates").get(0).getAsJsonObject()
                .getAsJsonArray("candidates").get(0).getAsJsonObject();
        JsonObject selected = CityPlanningEndpointHandler.handleSelectD4Candidate(
                debugRoot, runId, citySeedId, "session_test", "admin_core",
                firstCandidate.get("candidateId").getAsString(), "admin_core_01",
                "test first pick", true);
        assertTrue(selected.get("ok").getAsBoolean());
        assertEquals("deferred_to_d6",
                selected.getAsJsonObject("quickPreflightReport").get("status").getAsString());
        assertEquals("residential_01", selected.get("nextSlotId").getAsString());

        JsonObject nextSecond = CityPlanningEndpointHandler.handlePlanD4NextCandidates(
                debugRoot, runId, citySeedId, null);
        JsonObject secondCandidate = nextSecond.getAsJsonObject("slotCandidateSet")
                .getAsJsonArray("slotCandidates").get(0).getAsJsonObject()
                .getAsJsonArray("candidates").get(0).getAsJsonObject();
        CityPlanningEndpointHandler.handleSelectD4Candidate(
                debugRoot, runId, citySeedId, "session_test", "residential_01",
                secondCandidate.get("candidateId").getAsString(), "residential_01",
                "test second pick", false);

        JsonObject finalized = CityPlanningEndpointHandler.handleFinalizeD4CandidateSession(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath), null, "session_test");
        assertTrue(finalized.get("ok").getAsBoolean());
        assertEquals("finalized", finalized.get("status").getAsString());
        JsonObject artifacts = finalized.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("structureAnchorMap").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("d4DesignTimeReport").getAsString())));
        assertEquals(2, finalized.getAsJsonObject("structureAnchorMap")
                .getAsJsonArray("anchors").size());
    }

    @Test
    void handlePlanD5_readsD4ArtifactsAndWritesPreview() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d5-test");
        String runId = "run_d5";
        String citySeedId = "city_test";
        Path runDir = debugRoot.resolve(runId);
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("city_seed_registry.json"), """
                {
                  "citySeeds": [
                    {
                      "citySeedId": "city_test",
                      "realmId": "realm_test",
                      "role": "village",
                      "theoreticalScale": "village",
                      "anchorBlock": {"x": 0, "z": 0},
                      "planningRadiusCells": 64,
                      "candidateId": "candidate_test"
                    }
                  ]
                }
                """);
        Files.writeString(runDir.resolve("world_survey_manifest.json"), """
                {"config":{"cellStepBlocks":4,"dimensionId":"minecraft:overworld"}}
                """);

        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new CitySiteContextBuilder(config).build(
                "city_test", "realm_test", "minecraft:overworld",
                "city_test", "candidate_test", 0, 0,
                "village", "village", 64, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context, List.of(
                patch("plain", LandformType.PLAIN, -50, -50, -10, -10),
                patch("shore", LandformType.SHORE, 0, 0, 50, 50)));
        Path d3Dir = runDir.resolve("city_d3_city_test");
        Files.createDirectories(d3Dir);
        Files.writeString(d3Dir.resolve("city_landform_review_package.json"),
                CityJson.GSON.toJson(review.asJson()));

        Path catalogPath = runDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());
        CityPlanningEndpointHandler.handlePlanD4(debugRoot, runId, citySeedId,
                terraSenseSource(catalogPath), structureAnchorPlan(review, 2));

        JsonObject response = CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId);

        assertTrue(response.get("ok").getAsBoolean());
        assertTrue(response.getAsJsonObject("buildOperationPlan").getAsJsonArray("operations").isEmpty());
        assertEquals("d7_after_worldgen_ledger",
                response.getAsJsonObject("reservationMaskPlan").get("roadPlanningStage").getAsString());
        assertFalse(response.getAsJsonObject("reservationMaskPlan").getAsJsonArray("noVegetationMask").isEmpty());
        assertTrue(response.getAsJsonObject("reservationMaskPlan").has("wallReservationPlan"));
        assertEquals("city_wall_reservation_plan.v0.2",
                response.getAsJsonObject("wallReservationPlan").get("schemaVersion").getAsString());
        assertFalse(response.getAsJsonObject("wallReservationPlan").getAsJsonArray("wallCorridorMask").isEmpty());
        JsonObject artifacts = response.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("reservationMaskPlan").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("wallReservationPlan").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("roadAccessPlan").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("reservationMaskPreview").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("wallReservationPreview").getAsString())));
    }

    @Test
    void handlePlanD6AndExecuteD7_writeStructureArtifactsAndPreviews() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d6d7-http-test");
        String runId = "run_d6d7";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);

        JsonObject d6 = CityPlanningEndpointHandler.handlePlanD6(
                debugRoot,
                runId,
                citySeedId,
                null,
                null);

        assertTrue(d6.get("ok").getAsBoolean());
        assertEquals("worldgen_time_planned_registry",
                d6.getAsJsonObject("structureMaterializationPlan").get("dryRunMode").getAsString());
        assertFalse(d6.getAsJsonObject("structureMaterializationPlan")
                .getAsJsonArray("plannedWorldgenStructures")
                .isEmpty());
        JsonObject d6Artifacts = d6.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(d6Artifacts.get("structureMaterializationPlan").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(d6Artifacts.get("placedStructureLedger").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(d6Artifacts.get("structureMaterializationTrace").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(d6Artifacts.get("structureMaterializationPreview").getAsString())));

        CityPlanningEndpointHandler.handleExecuteD5(
                debugRoot, Files.createTempDirectory("city-d6d7-server-root"),
                runId, citySeedId, true, null, "auto");

        JsonObject d7 = CityPlanningEndpointHandler.handleExecuteD7(
                debugRoot,
                runId,
                citySeedId,
                12345L,
                false,
                false,
                null,
                null);

        assertTrue(d7.get("ok").getAsBoolean());
        assertTrue(d7.get("worldgenPlacementMode").getAsBoolean());
        JsonObject d7Artifacts = d7.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(d7Artifacts.get("placedStructureLedger").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(d7Artifacts.get("structureMaterializationTrace").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(d7Artifacts.get("placedStructurePreview").getAsString())));
        assertEquals(0, d7.getAsJsonObject("placedStructureLedger")
                .getAsJsonArray("placedStructures")
                .size());
        assertTrue(d7.getAsJsonObject("structureMaterializationTrace")
                .getAsJsonObject("waitingSummary")
                .has("WAITING_FOR_WORLDGEN"));
    }

    @Test
    void handleExecuteD7BuildsRoadPlanFromWorldgenLedgerActualFootprints() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d7-ledger-road-test");
        Path serverRoot = Files.createTempDirectory("city-d7-ledger-road-server-root");
        String runId = "run_d7_ledger_road";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);

        JsonObject d6 = CityPlanningEndpointHandler.handlePlanD6(
                debugRoot, runId, citySeedId, null, null);
        CityPlanningEndpointHandler.handleExecuteD5(debugRoot, serverRoot, runId, citySeedId, true, null, "auto");
        JsonObject plannedRegistry = CityReservationMaskRegistry.plannedRegistrySummary();
        for (JsonElement elem : plannedRegistry.getAsJsonArray("plannedStructures")) {
            JsonObject plannedJson = elem.getAsJsonObject();
            JsonObject chunkJson = plannedJson.getAsJsonObject("anchorChunk");
            ChunkPos chunk = new ChunkPos(chunkJson.get("x").getAsInt(), chunkJson.get("z").getAsInt());
            CityReservationMaskRegistry.PlannedStructure planned = CityReservationMaskRegistry
                    .plannedStructuresForChunk(chunk)
                    .get(0);
            JsonObject actualJson = plannedJson.getAsJsonObject("lockedActualFootprint");
            BlockBounds actual = new BlockBounds(
                    actualJson.get("minX").getAsInt(),
                    actualJson.get("minZ").getAsInt(),
                    actualJson.get("maxX").getAsInt(),
                    actualJson.get("maxZ").getAsInt());
            CityReservationMaskRegistry.recordWorldgenPlacement(planned, actual,
                    planned.expectedStartSignature(), new JsonArray(), chunk,
                    "none", "WORLDGEN_PLACEMENT_RECORDED", "test placement");
        }

        JsonObject d7 = CityPlanningEndpointHandler.handleExecuteD7(
                debugRoot, runId, citySeedId, 12345L,
                true, false, null, null);

        JsonObject roadReport = d7.getAsJsonObject("deferredRoadPostprocessReport");
        assertEquals("worldgen_ledger_actual_footprint",
                roadReport.get("roadPostprocessSource").getAsString());
        assertEquals(3, roadReport.get("roadAvoidanceMarginBlocks").getAsInt());
        assertEquals("actual_footprint_union", roadReport.get("boundarySource").getAsString());
        JsonArray operations = roadReport.getAsJsonObject("generatedBuildOperationPlan")
                .getAsJsonArray("operations");
        assertFalse(operations.isEmpty());
        assertEquals(d6.getAsJsonObject("structureMaterializationPlan")
                        .getAsJsonArray("plannedWorldgenStructures").size(),
                d7.getAsJsonObject("placedStructureLedger").getAsJsonArray("placedStructures").size());
    }

    @Test
    void handleExecuteD5RequiresLockedD6Plan() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d5-requires-d6");
        String runId = "run_d5_requires_d6";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        debugRoot, Files.createTempDirectory("city-d5-requires-d6-server-root"),
                        runId, citySeedId, true, null, "auto"));

        assertTrue(ex.getMessage().contains("Run city_plan_d6 before city_execute_d5"));
    }

    @Test
    void handlePlanD6_allowsPreflightBeforeActiveWorldgenRegistry() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d6-registry-missing");
        String runId = "run_d6_missing_registry";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);

        JsonObject d6 = CityPlanningEndpointHandler.handlePlanD6(
                debugRoot,
                runId,
                citySeedId,
                null,
                null);

        assertTrue(d6.get("ok").getAsBoolean());
        assertEquals("worldgen_time_planned_registry",
                d6.getAsJsonObject("structureMaterializationPlan").get("dryRunMode").getAsString());
        JsonObject planned = d6.getAsJsonObject("structureMaterializationPlan")
                .getAsJsonArray("plannedWorldgenStructures").get(0).getAsJsonObject();
        assertEquals("planned_worldgen", planned.get("status").getAsString());
        assertEquals("WAITING_FOR_WORLDGEN", planned.get("reasonCode").getAsString());
    }

    @Test
    void handleExecuteD5_requiresExplicitConfirmation() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        Path.of("run/realm_debug"), Path.of("run"),
                        "run", "city", false, null, "auto"));
        assertTrue(ex.getMessage().contains("confirmWorldMutation"));
    }

    @Test
    void handleExecuteD5_requiresPlannedOperationFile() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d5-execute-missing-plan");
        String runId = "run_d5_execute";
        String citySeedId = "city_test";
        Path runDir = debugRoot.resolve(runId);
        Files.createDirectories(runDir.resolve("city_d4_city_test"));
        Files.writeString(runDir.resolve("city_seed_registry.json"), """
                {
                  "citySeeds": [
                    {
                      "citySeedId": "city_test",
                      "realmId": "realm_test",
                      "role": "village",
                      "theoreticalScale": "village",
                      "anchorBlock": {"x": 0, "z": 0}
                    }
                  ]
                }
                """);
        Files.writeString(runDir.resolve("city_d4_city_test").resolve("structure_anchor_map.json"), """
                {
                  "schemaVersion": "city_structure_anchor_map.v0.1",
                  "cityId": "city_test",
                  "anchors": []
                }
                """);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        debugRoot, Files.createTempDirectory("city-d5-server-root"),
                        runId, citySeedId, true, null, "auto"));
        assertTrue(ex.getMessage().contains("reservation_mask_plan.json"));
    }

    @Test
    void handleExecuteD5_activatesRegistryWithoutLoadedWorld() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d5-execute-no-level");
        String runId = "run_d5_execute";
        String citySeedId = "city_test";
        Path runDir = debugRoot.resolve(runId);
        Files.createDirectories(runDir.resolve("city_d4_city_test"));
        Files.createDirectories(runDir.resolve("city_d5_city_test"));
        Files.writeString(runDir.resolve("city_seed_registry.json"), """
                {
                  "citySeeds": [
                    {
                      "citySeedId": "city_test",
                      "realmId": "realm_test",
                      "role": "village",
                      "theoreticalScale": "village",
                      "anchorBlock": {"x": 0, "z": 0}
                    }
                  ]
                }
                """);
        Files.writeString(runDir.resolve("city_d4_city_test").resolve("structure_anchor_map.json"), """
                {
                  "schemaVersion": "city_structure_anchor_map.v0.1",
                  "cityId": "city_test",
                  "anchors": [
                    {
                      "anchorId": "anchor_test",
                      "structureId": "minecraft:village_plains",
                      "anchorBlock": {"x": 0, "z": 0},
                      "commandAnchorBlock": {"x": 0, "z": 0},
                      "rotation": "NONE",
                      "plannedFootprint": {"minX": -4, "minZ": -4, "maxX": 4, "maxZ": 4},
                      "reservedEnvelope": {"minX": -32, "minZ": -32, "maxX": 32, "maxZ": 32},
                      "sourcePatches": [],
                      "semanticTerms": [],
                      "functionTerms": []
                    }
                  ]
                }
                """);
        Files.writeString(runDir.resolve("city_d5_city_test").resolve("build_operation_plan.json"), """
                {
                  "schemaVersion": "build_operation_plan.v0.1",
                  "cityId": "city_test",
                  "templateDirectory": "geomantia_templates/d5",
                  "operations": []
                }
                """);
        Files.writeString(runDir.resolve("city_d5_city_test").resolve("reservation_mask_plan.json"), """
                {
                  "schemaVersion": "city_reservation_mask_plan.v0.1",
                  "cityId": "city_test",
                  "noVegetationMask": [],
                  "vegetationLimitedMask": [],
                  "noVanillaStructureMask": [],
                  "reservationReason": []
                }
                """);

        Files.createDirectories(runDir.resolve("city_d6_city_test"));
        Files.writeString(runDir.resolve("city_d6_city_test").resolve("structure_materialization_plan.json"), """
                {
                  "schemaVersion": "city_structure_materialization_plan.v0.1",
                  "cityId": "city_test",
                  "worldgenPlacementMode": true,
                  "locked": true,
                  "plannedWorldgenStructures": [
                    {
                      "anchorId": "anchor_test",
                      "structureId": "minecraft:village_plains",
                      "anchorBlock": {"x": 0, "z": 0},
                      "commandAnchorBlock": {"x": 0, "z": 0},
                      "anchorChunk": {"x": 0, "z": 0},
                      "rotation": "NONE",
                      "plannedFootprint": {"minX": -4, "minZ": -4, "maxX": 4, "maxZ": 4},
                      "reservedEnvelope": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8},
                      "collisionEnvelope": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8},
                      "lockedActualFootprint": {"minX": -4, "minZ": -4, "maxX": 4, "maxZ": 4},
                      "lockedCollisionEnvelope": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8},
                      "maskEnvelope": {"minX": -12, "minZ": -12, "maxX": 12, "maxZ": 12},
                      "safetyEnvelope": {"minX": -16, "minZ": -16, "maxX": 16, "maxZ": 16},
                      "locked": true,
                      "expectedStartSignature": "sig_anchor_test",
                      "status": "planned_worldgen",
                      "reasonCode": "WAITING_FOR_WORLDGEN",
                      "sourcePatches": [],
                      "semanticTerms": [],
                      "functionTerms": []
                    }
                  ]
                }
                """);

        JsonObject response = CityPlanningEndpointHandler.handleExecuteD5(
                debugRoot, Files.createTempDirectory("city-d5-server-root"),
                runId, citySeedId, true, null, "auto");
        assertTrue(response.get("ok").getAsBoolean());
        assertTrue(response.get("worldgenPlacementMode").getAsBoolean());
        assertEquals(1, response.get("activePlannedStructureCount").getAsInt());
        assertFalse(response.getAsJsonObject("worldMutationReport").get("executed").getAsBoolean());
    }

    @Test
    void handleExecuteD5_roadweaverProviderHardFailsWhenModMissing() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d5-roadweaver-missing");
        String runId = "run_d5_roadweaver_missing";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        CityPlanningEndpointHandler.handlePlanD6(debugRoot, runId, citySeedId, null, null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        debugRoot, Files.createTempDirectory("city-d5-roadweaver-server-root"),
                        runId, citySeedId, true, null, "roadweaver"));

        assertTrue(ex.getMessage().contains("ROADWEAVER_UNAVAILABLE"));
    }

    @Test
    void handlePlanCityWallsWritesPlanPreviewAndReadableNbtTemplates() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-wall-plan-test");
        String runId = "run_wall_plan";
        String citySeedId = "city_test";
        Path runDir = debugRoot.resolve(runId);
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("city_seed_registry.json"), """
                {
                  "citySeeds": [
                    {
                      "citySeedId": "city_test",
                      "realmId": "realm_test",
                      "role": "village",
                      "theoreticalScale": "village",
                      "anchorBlock": {"x": 0, "z": 0},
                      "planningRadiusCells": 64,
                      "candidateId": "candidate_test"
                    }
                  ]
                }
                """);
        Path d7Dir = runDir.resolve("city_d7_" + citySeedId);
        Files.createDirectories(d7Dir);
        Files.writeString(d7Dir.resolve("placed_structure_ledger.json"), """
                {
                  "schemaVersion": "city_placed_structure_ledger.v0.1",
                  "cityId": "city_test",
                  "placedStructures": [
                    {
                      "anchorId": "admin_core",
                      "structureId": "minecraft:desert_pyramid",
                      "actualFootprint": {"minX": -10, "minZ": -12, "maxX": 18, "maxZ": 20}
                    },
                    {
                      "anchorId": "farm",
                      "structureId": "minecraft:village_plains",
                      "actualFootprint": {"minX": 60, "minZ": 24, "maxX": 82, "maxZ": 44}
                    }
                  ]
                }
                """);

        JsonObject response = CityPlanningEndpointHandler.handlePlanCityWalls(
                debugRoot, runId, citySeedId, 24, 15, 7,
                "v1_debug", null, 8, 2, 8, 7);

        assertTrue(response.get("ok").getAsBoolean());
        JsonObject artifacts = response.getAsJsonObject("artifacts");
        Path planPath = debugRoot.resolve(artifacts.get("cityWallPlan").getAsString());
        Path previewPath = debugRoot.resolve(artifacts.get("cityWallPreview").getAsString());
        Path templateDir = debugRoot.resolve(artifacts.get("cityWallTemplateDirectory").getAsString());
        assertTrue(Files.exists(planPath));
        assertTrue(Files.exists(previewPath));
        assertTrue(Files.exists(templateDir.resolve("wall_template_library.json")));
        assertTrue(Files.exists(templateDir.resolve("wall_straight_15.nbt")));
        assertTrue(Files.exists(templateDir.resolve("wall_tower_small.nbt")));
        assertTrue(Files.exists(templateDir.resolve("wall_gap_gate_7.nbt")));
        JsonArray segments = response.getAsJsonObject("cityWallPlan").getAsJsonArray("wallSegments");
        assertTrue(segments.toString().contains("gate_gap"));
        assertTrue(segments.toString().contains("tower"));

        CompoundTag straight = NbtIo.readCompressed(templateDir.resolve("wall_straight_15.nbt").toFile());
        assertEquals(15, straight.getList("size", 3).getInt(0));
        assertFalse(straight.getList("blocks", 10).isEmpty());
    }

    @Test
    void handlePlanCityWallsAcceptsV32DesignPolicyAndWritesDesignTemplates() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-wall-v32-plan-test");
        String runId = "run_wall_v32_plan";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId,
                "v3", 24, 15, 4, new CityWallReservationPlanner.V3Options(24, 2, 64, 0.6));

        Path d7Dir = debugRoot.resolve(runId).resolve("city_d7_" + citySeedId);
        Files.createDirectories(d7Dir);
        Files.writeString(d7Dir.resolve("placed_structure_ledger.json"), """
                {
                  "schemaVersion": "city_placed_structure_ledger.v0.1",
                  "cityId": "city_test",
                  "placedStructures": [
                    {
                      "anchorId": "admin_core",
                      "structureId": "minecraft:desert_pyramid",
                      "actualFootprint": {"minX": -10, "minZ": -12, "maxX": 18, "maxZ": 20}
                    }
                  ]
                }
                """);

        JsonObject response = CityPlanningEndpointHandler.handlePlanCityWalls(
                debugRoot, runId, citySeedId, 24, 15, 9,
                "v3", null, 8, 2, 8, 7,
                new CityWallPlanner.V3Options(24, 5, "v3.1", 7, 16, 6, 17, true,
                        "v3.2", 48, 24, 4096));

        assertTrue(response.get("ok").getAsBoolean());
        JsonObject wallPlan = response.getAsJsonObject("cityWallPlan");
        assertEquals("v3.2", wallPlan.get("wallDesignPolicy").getAsString());
        assertEquals("domain_hull_then_natural_boundary_and_gatehouse_nodes",
                wallPlan.get("wallPlanningMode").getAsString());
        Path templateDir = debugRoot.resolve(response.getAsJsonObject("artifacts")
                .get("cityWallTemplateDirectory").getAsString());
        assertTrue(Files.exists(templateDir.resolve("gatehouse_9.nbt")));
        assertTrue(Files.exists(templateDir.resolve("gatehouse_13.nbt")));
        assertTrue(Files.exists(templateDir.resolve("watchtower_5x5.nbt")));
        assertTrue(Files.exists(templateDir.resolve("beacon_5x5.nbt")));
    }

    @Test
    void handlePlanCityWallsAcceptsV4AndWritesWallGraphPreview() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-wall-v4-plan-test");
        String runId = "run_wall_v4_plan";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId,
                "v4", 24, 15, 4, new CityWallReservationPlanner.V3Options(24, 2, 64, 0.6));

        Path d7Dir = debugRoot.resolve(runId).resolve("city_d7_" + citySeedId);
        Files.createDirectories(d7Dir);
        Files.writeString(d7Dir.resolve("placed_structure_ledger.json"), """
                {
                  "schemaVersion": "city_placed_structure_ledger.v0.1",
                  "cityId": "city_test",
                  "placedStructures": [
                    {
                      "anchorId": "admin_core",
                      "structureId": "minecraft:desert_pyramid",
                      "actualFootprint": {"minX": -10, "minZ": -12, "maxX": 18, "maxZ": 20}
                    },
                    {
                      "anchorId": "outside_patch_structure",
                      "structureId": "minecraft:village_plains",
                      "actualFootprint": {"minX": 108, "minZ": 8, "maxX": 136, "maxZ": 32}
                    }
                  ]
                }
                """);

        JsonObject response = CityPlanningEndpointHandler.handlePlanCityWalls(
                debugRoot, runId, citySeedId, 24, 15, 9,
                "v4", null, 8, 2, 8, 7,
                new CityWallPlanner.V3Options(24, 5, "v3.1", 7, 16, 6, 17, true),
                CityWallPlanner.V4Options.defaults());

        assertTrue(response.get("ok").getAsBoolean());
        JsonObject wallPlan = response.getAsJsonObject("cityWallPlan");
        assertEquals("city_wall_plan.v0.4", wallPlan.get("schemaVersion").getAsString());
        assertEquals("actual_footprint_land_ring", wallPlan.get("wallBoundaryMode").getAsString());
        assertFalse(wallPlan.getAsJsonArray("wallNodes").isEmpty());
        assertFalse(wallPlan.getAsJsonArray("wallUnits").isEmpty());
        assertFalse(wallPlan.getAsJsonArray("nodeConnectorUnits").isEmpty());
        assertTrue(wallPlan.has("cityWallDatumY"));
        BlockBounds wallBounds = bounds(wallPlan.getAsJsonObject("wallBounds"));
        assertTrue(wallBounds.contains(136, 32), wallPlan.toString());
        JsonObject artifacts = response.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("cityWallPlan").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("cityWallPreview").getAsString())));
    }

    @Test
    void handlePlanD5CanWriteV5FinalWallReservationArtifacts() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-wall-v5-d5-test");
        String runId = "run_wall_v5_d5";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);

        JsonObject response = CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId,
                "v5", 24, 15, 4, CityWallReservationPlanner.V3Options.defaults());

        JsonObject reservation = response.getAsJsonObject("wallReservationPlan");
        assertEquals("city_wall_reservation_plan.v0.5", reservation.get("schemaVersion").getAsString());
        assertEquals("v5", reservation.get("wallVersion").getAsString());
        assertEquals("d4_planned_footprint_envelope_rectilinear_hull",
                reservation.get("boundarySource").getAsString());
        assertEquals("semantic_and_coverage_check_only", reservation.get("patchUsage").getAsString());
        assertFalse(reservation.get("finalBoundaryDeferredToD7").getAsBoolean());
        assertFalse(reservation.getAsJsonArray("wallLine").isEmpty());
        assertFalse(reservation.getAsJsonArray("wallCorridorMask").isEmpty());
        assertFalse(reservation.getAsJsonArray("gateSlots").isEmpty());
        assertFalse(reservation.getAsJsonArray("wallNodeSlots").isEmpty());
        assertFalse(reservation.getAsJsonObject("coverageCheck")
                .get("patchBoundaryIsFinalWallLine").getAsBoolean());
        JsonObject mask = response.getAsJsonObject("reservationMaskPlan");
        assertTrue(mask.has("gateCorridorMask"));
        assertTrue(mask.has("worldgenMaskChannels"));
        assertTrue(mask.getAsJsonObject("worldgenMaskChannels").has("noRoadsideStructure"));
    }

    @Test
    void handlePlanD5V5RequiresPatchRescanWhenReservationTouchesD3Boundary() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-wall-v5-rescan-test");
        String runId = "run_wall_v5_rescan";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        Path d3Path = debugRoot.resolve(runId).resolve("city_d3_" + citySeedId)
                .resolve("city_landform_review_package.json");
        JsonObject d3 = JsonParser.parseString(Files.readString(d3Path)).getAsJsonObject();
        JsonObject grid = d3.getAsJsonObject("grid");
        grid.addProperty("originBlockX", -16);
        grid.addProperty("originBlockZ", -16);
        grid.addProperty("cellStepBlocks", 4);
        grid.addProperty("cellsX", 8);
        grid.addProperty("cellsZ", 8);
        Files.writeString(d3Path, CityJson.GSON.toJson(d3));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId,
                        "v5", 24, 15, 4, CityWallReservationPlanner.V3Options.defaults()));

        assertTrue(ex.getMessage().contains("D5_V5_REQUIRES_PATCH_RESCAN"));
    }

    @Test
    void handlePlanCityWallsV5KeepsD5WallLineAndBackfillsSurfaceCacheReport() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-wall-v5-plan-test");
        String runId = "run_wall_v5_plan";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId,
                "v5", 24, 15, 4, CityWallReservationPlanner.V3Options.defaults());
        Path reservationPath = debugRoot.resolve(runId).resolve("city_d5_" + citySeedId)
                .resolve("wall_reservation_plan.json");
        JsonObject reservation = JsonParser.parseString(Files.readString(reservationPath)).getAsJsonObject();

        Path d7Dir = debugRoot.resolve(runId).resolve("city_d7_" + citySeedId);
        Files.createDirectories(d7Dir);
        Files.writeString(d7Dir.resolve("placed_structure_ledger.json"), """
                {
                  "schemaVersion": "city_placed_structure_ledger.v0.1",
                  "cityId": "city_test",
                  "placedStructures": [
                    {
                      "anchorId": "admin_core",
                      "structureId": "minecraft:desert_pyramid",
                      "actualFootprint": {"minX": -10, "minZ": -12, "maxX": 18, "maxZ": 20}
                    }
                  ]
                }
                """);

        JsonObject response = CityPlanningEndpointHandler.handlePlanCityWalls(
                debugRoot, runId, citySeedId, 24, 15, 9,
                "v5", null, 8, 2, 8, 7);

        JsonObject wallPlan = response.getAsJsonObject("cityWallPlan");
        assertEquals("city_wall_plan.v0.5", wallPlan.get("schemaVersion").getAsString());
        assertEquals("d5_final_wall_line", wallPlan.get("wallBoundaryMode").getAsString());
        assertEquals(reservation.getAsJsonArray("wallLine").toString(),
                wallPlan.getAsJsonArray("wallLine").toString());
        assertEquals(8, wallPlan.get("wallUnitLengthBlocks").getAsInt());
        assertEquals(9, wallPlan.get("nominalWallHeightBlocks").getAsInt());
        assertTrue(wallPlan.getAsJsonObject("wallGraphValidation").get("noRelineAfterD5").getAsBoolean());
        assertTrue(wallPlan.getAsJsonArray("wallUnits").toString().contains("surface_cache_1_block_median_at_execute"));
        assertEquals("skipped", wallPlan.getAsJsonObject("surfaceCacheBackfill").get("status").getAsString());
        assertTrue(response.getAsJsonObject("artifacts").has("surfaceCacheBackfill"));
    }

    @Test
    void handlePlanD6HardStopsWhenV5LockedFootprintExceedsD5Coverage() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-wall-v5-d6-stop-test");
        String runId = "run_wall_v5_d6_stop";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId,
                "v5", 24, 15, 4, CityWallReservationPlanner.V3Options.defaults());
        Path reservationPath = debugRoot.resolve(runId).resolve("city_d5_" + citySeedId)
                .resolve("wall_reservation_plan.json");
        JsonObject reservation = JsonParser.parseString(Files.readString(reservationPath)).getAsJsonObject();
        reservation.add("wallCoverageBounds", JsonParser.parseString("""
                {"minX": -4, "minZ": -4, "maxX": 4, "maxZ": 4}
                """).getAsJsonObject());
        Files.writeString(reservationPath, CityJson.GSON.toJson(reservation));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handlePlanD6(debugRoot, runId, citySeedId, null, null));

        assertTrue(ex.getMessage().contains("D5_V5_LOCKED_FOOTPRINT_OUTSIDE_RESERVATION"));
    }

    @Test
    void handlePlanD5CanWriteV3WallReservationArtifacts() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-wall-v3-d5-test");
        String runId = "run_wall_v3_d5";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);

        JsonObject response = CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId,
                "v3", 24, 15, 4, new CityWallReservationPlanner.V3Options(24, 2, 64, 0.6));

        assertTrue(response.get("ok").getAsBoolean());
        JsonObject artifacts = response.getAsJsonObject("artifacts");
        Path reservationPath = debugRoot.resolve(artifacts.get("wallReservationPlan").getAsString());
        Path previewPath = debugRoot.resolve(artifacts.get("wallReservationPreview").getAsString());
        assertTrue(Files.exists(reservationPath));
        assertTrue(Files.exists(previewPath));
        JsonObject reservation = JsonParser.parseString(Files.readString(reservationPath)).getAsJsonObject();
        assertEquals("city_wall_reservation_plan.v0.3", reservation.get("schemaVersion").getAsString());
        assertEquals("structure_seeded_patch_region_hull", reservation.get("boundarySource").getAsString());
        assertFalse(reservation.getAsJsonArray("cityDomainMask").isEmpty());
    }

    @Test
    void handleRunWorkflowSkipsExistingArtifactsAndWritesTimingReport() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-workflow-test");
        String runId = "run_workflow";
        String citySeedId = "city_test";
        Path runDir = debugRoot.resolve(runId);
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        Files.createDirectories(runDir.resolve("city_structure_envelopes_" + citySeedId));
        Files.writeString(runDir.resolve("city_structure_envelopes_" + citySeedId)
                .resolve("structure_envelope_facts.json"), "{}");
        Files.createDirectories(runDir.resolve("city_d6_" + citySeedId));
        Files.writeString(runDir.resolve("city_d6_" + citySeedId)
                .resolve("structure_materialization_plan.json"), "{}");

        JsonObject request = JsonParser.parseString("""
                {
                  "runId": "run_workflow",
                  "citySeedId": "city_test",
                  "skipExisting": true
                }
                """).getAsJsonObject();

        JsonObject response = CityPlanningEndpointHandler.handleRunWorkflow(
                debugRoot,
                Files.createTempDirectory("city-workflow-server-root"),
                runId,
                citySeedId,
                request,
                null,
                null);

        assertTrue(response.get("ok").getAsBoolean());
        assertEquals("waiting_for_confirmation", response.get("status").getAsString());
        JsonObject report = response.getAsJsonObject("workflowReport");
        assertEquals("city_workflow_report.v0.1", report.get("schemaVersion").getAsString());
        assertTrue(report.has("durationMs"));
        assertTrue(report.getAsJsonArray("steps").toString().contains("WORKFLOW_EXISTING_ARTIFACT"));
        assertTrue(report.getAsJsonArray("steps").toString().contains("WORKFLOW_CONFIRM_WORLD_MUTATION_REQUIRED"));
        Path reportPath = debugRoot.resolve(response.getAsJsonObject("artifacts")
                .get("workflowReport").getAsString());
        assertTrue(Files.exists(reportPath));
    }

    private static LandformPatch patch(String id, LandformType type, int minX, int minZ, int maxX, int maxZ) {
        boolean water = type == LandformType.SHORE || type == LandformType.WATER;
        return new LandformPatch(id, "region_0", type,
                Math.max(1, (maxX - minX) * (maxZ - minZ) / 256),
                minX, minZ, maxX, maxZ,
                70.0, 65.0, 75.0, 1.5, water ? 8.0 : 50.0,
                water, false, 0.9, EnumSet.noneOf(PatchFlag.class));
    }

    private static void prepareD5Artifacts(Path debugRoot, String runId, String citySeedId) throws Exception {
        Path runDir = debugRoot.resolve(runId);
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("city_seed_registry.json"), """
                {
                  "citySeeds": [
                    {
                      "citySeedId": "city_test",
                      "realmId": "realm_test",
                      "role": "village",
                      "theoreticalScale": "village",
                      "anchorBlock": {"x": 0, "z": 0},
                      "planningRadiusCells": 64,
                      "candidateId": "candidate_test"
                    }
                  ]
                }
                """);
        Files.writeString(runDir.resolve("world_survey_manifest.json"), """
                {"config":{"cellStepBlocks":4,"dimensionId":"minecraft:overworld"}}
                """);

        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new CitySiteContextBuilder(config).build(
                "city_test", "realm_test", "minecraft:overworld",
                "city_test", "candidate_test", 0, 0,
                "village", "village", 64, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context, List.of(
                patch("plain", LandformType.PLAIN, -64, -64, 64, 64),
                patch("shore", LandformType.SHORE, 80, 0, 140, 60)));
        Path d3Dir = runDir.resolve("city_d3_" + citySeedId);
        Files.createDirectories(d3Dir);
        Files.writeString(d3Dir.resolve("city_landform_review_package.json"),
                CityJson.GSON.toJson(review.asJson()));

        Path catalogPath = runDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());
        CityPlanningEndpointHandler.handlePlanD4(debugRoot, runId, citySeedId,
                terraSenseSource(catalogPath), structureAnchorPlan(review, 2));
        CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId);
    }

    private static JsonObject terraSenseSource(Path catalogPath) {
        JsonObject source = new JsonObject();
        source.addProperty("schemaVersion", "terrasense_structure_profile_source.v0.1");
        source.addProperty("sourceType", "debug_catalog");
        source.addProperty("catalogMode", "debug");
        source.addProperty("debugCatalogPath", catalogPath.toString());
        return source;
    }

    private static JsonObject structureAnchorPlan(CityLandformReviewPackage review, int anchorCount) {
        List<LandformPatchSummary> patches = review.landformPatches();
        LandformPatchSummary first = patches.get(0);
        LandformPatchSummary second = patches.size() > 1 ? patches.get(1) : first;
        String secondAnchor = anchorCount > 1 ? """
                    {
                      "anchorId": "anchor_village",
                      "structureId": "minecraft:village_plains",
                      "sourcePatchIds": ["%s"],
                      "anchorBlock": {"x": %d, "z": %d},
                      "rotation": "NONE",
                      "intentTerms": ["function.village"],
                      "priority": 2,
                      "roadAccessIntent": "secondary_access",
                      "clearanceBlocks": 8,
                      "roadAccessMarginBlocks": 6
                    }
                """.formatted(second.landformPatchId(), second.centerBlock().x(), second.centerBlock().z()) : "";
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
                      "roadAccessIntent": "primary_access",
                      "clearanceBlocks": 8,
                      "roadAccessMarginBlocks": 6
                    }
                    %s
                  ]
                }
                """.formatted(first.landformPatchId(), first.centerBlock().x(), first.centerBlock().z(),
                secondAnchor.isBlank() ? "" : "," + secondAnchor)).getAsJsonObject();
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

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(
                obj.get("minX").getAsInt(),
                obj.get("minZ").getAsInt(),
                obj.get("maxX").getAsInt(),
                obj.get("maxZ").getAsInt());
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
                      "fixedFootprint": {"widthBlocks": 12, "depthBlocks": 12, "heightBlocks": 10},
                      "visibleAreaCost": 256,
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
                      "semanticTerms": ["function.村庄", "style.debug", "placement.inside_zone", "usage.filler", "quality.debug_usable"],
                      "functionTerms": ["function.村庄"],
                      "styleTerms": ["style.debug"],
                      "placementTerms": ["placement.inside_zone"],
                      "usageTerms": ["usage.filler"],
                      "qualityTerms": ["quality.debug_usable"],
                      "expectedAreaRange": {
                        "minAreaBlocks": 128,
                        "maxAreaBlocks": 25600,
                        "startFootprint": {"widthBlocks": 10, "depthBlocks": 10, "heightBlocks": 8}
                      },
                      "allowedRotations": ["NONE", "CLOCKWISE_90"],
                      "clearanceBlocks": 0
                    }
                  ],
                  "quality": {
                    "passed": true,
                    "score": 100,
                    "hardBlocks": [],
                    "warnings": ["debug_catalog_semantic_mismatch_allowed"],
                    "needsReview": [],
                    "metrics": {}
                  }
                }
                """;
    }

}
