package com.rinsing.geomantia.platform.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityLandformReviewBuilder;
import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder;
import com.rinsing.geomantia.systems.city.application.CityStructureMaterializationPlanner;
import com.rinsing.geomantia.systems.city.application.CityTemplatePlacementGeometry;
import com.rinsing.geomantia.systems.city.application.CityWallPlanner;
import com.rinsing.geomantia.systems.city.application.CityWallReservationPlanner;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseAreaPlanCodec;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlanCodec;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseTerrainFieldCodec;
import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRuleCatalog;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.CitySiteContext;
import com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityReservationMaskRegistry;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseChunkStatusPreflight;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseWorldgenRegistry;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.landform.PatchFlag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.OptionalInt;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityPlanningEndpointHandlerTest {

    @Test
    void d3RejectsAnyCellStepOtherThanFixed16() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                CityPlanningEndpointHandler.handlePlanD3(Path.of("."), "run", "city", 32, null));

        assertEquals("CITY_D3_CELL_STEP_FIXED: cellStepBlocks must be 16.", error.getMessage());
    }

    @Test
    void d7OwnerCompletionExposesBoundedAsynchronousQueueState() {
        CityLandUseChunkStatusPreflight.OwnerChunk current =
                new CityLandUseChunkStatusPreflight.OwnerChunk(12, -7);
        CityLandUseWorldgenRegistry.BackfillSummary summary =
                new CityLandUseWorldgenRegistry.BackfillSummary(
                        "city_queue", "area_hash", "surface_hash", 3, 1, 0, 1,
                        List.of(current, new CityLandUseChunkStatusPreflight.OwnerChunk(13, -7)),
                        List.of(), new JsonArray(), "loading_chunk", 1, false, current);

        JsonObject json = CityPlanningEndpointHandler.landUseBackfillJson(summary);

        assertEquals("city_land_use_owner_completion.v0.2", json.get("schema").getAsString());
        assertFalse(json.get("complete").getAsBoolean());
        assertEquals("loading_chunk", json.get("status").getAsString());
        assertEquals(1, json.get("maxOwnerActionsPerTick").getAsInt());
        assertFalse(json.get("synchronousChunkLoads").getAsBoolean());
        assertEquals(12, json.getAsJsonObject("currentOwner").get("chunkX").getAsInt());
        assertEquals(-7, json.getAsJsonObject("currentOwner").get("chunkZ").getAsInt());
    }

    @Test
    void blueprintEndpointHandlersPrepareFailureBudgetAndAcceptCompleteRevision() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-blueprint-endpoint-test");
        String runId = "run_blueprint_endpoint";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        Path runDir = debugRoot.resolve(runId);
        writeBlueprintTerrainField(runDir, citySeedId);

        JsonObject prepared = CityPlanningEndpointHandler.handlePrepareD4BlueprintContext(debugRoot, runId,
                citySeedId, terraSenseSource(runDir.resolve("debug_structure_profile_catalog.json")),
                templateCatalogSource(runDir.resolve("template_catalog.json")), blueprintReferenceCatalog());
        assertEquals(0, prepared.get("aiCityDesignCallCount").getAsInt());
        assertEquals(0, prepared.get("failureCount").getAsInt());
        assertEquals(5, prepared.get("maximumFailureCount").getAsInt());

        JsonObject submitted = CityPlanningEndpointHandler.handleSubmitD4Blueprint(debugRoot, runId, citySeedId,
                prepared.get("contextId").getAsString(), blueprintForContext(
                        prepared.getAsJsonObject("cityBlueprintContext")));
        assertTrue(submitted.get("ok").getAsBoolean());
        assertEquals(0, submitted.get("failureCount").getAsInt());
        assertTrue(Files.isRegularFile(runDir.resolve("city_blueprint_city_test/city_blueprint.json")));
        assertTrue(Files.isRegularFile(runDir.resolve(
                "city_blueprint_city_test/city_blueprint_failure_budget.json")));
    }

    @Test
    void blueprintOutdoorPlannerPublishesCompleteIdentityBoundArtifacts() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-blueprint-outdoor-endpoint-test");
        String runId = "run_blueprint_outdoor";
        String citySeedId = "city_test";
        prepareAcceptedBlueprintD6(debugRoot, runId, citySeedId, "GENERATE");

        JsonObject response = CityPlanningEndpointHandler.handlePlanBlueprintOutdoor(
                debugRoot, runId, citySeedId);

        assertTrue(response.get("ok").getAsBoolean());
        assertEquals("city_blueprint", response.get("planningSource").getAsString());
        Path directory = debugRoot.resolve(runId).resolve("city_land_use_" + citySeedId);
        for (String file : List.of("city_outdoor_intent_plan.json", "city_urban_space_plan.json",
                "city_land_use_area_plan.json", "city_land_use_surface_print_plan.json",
                "land_use_plan_trace.json", "quality_report.json", "land_use_preview.png",
                "city_land_use_planning_complete.json")) {
            assertTrue(Files.isRegularFile(directory.resolve(file)), file);
        }
        JsonObject completion = JsonParser.parseString(Files.readString(
                directory.resolve("city_land_use_planning_complete.json"))).getAsJsonObject();
        assertEquals("city_land_use_planning_complete",
                completion.get("schema").getAsString());
        assertEquals(CityLandUseSurfacePrintPlan.SCHEMA,
                completion.get("surfacePrintPlanSchema").getAsString());
        for (String identity : List.of("sourceBlueprintHash", "sourceCatalogSnapshotHash",
                "sourceReferenceCatalogHash", "sourceTerrainFieldHash", "sourceD6Hash",
                "outdoorIntentPlanHash", "urbanSpacePlanHash", "planHash",
                "surfacePrintPlanHash", "ruleProfileHash")) {
            assertFalse(completion.get(identity).getAsString().isBlank(), identity);
        }
        JsonObject surfacePlan = JsonParser.parseString(Files.readString(
                directory.resolve("city_land_use_surface_print_plan.json"))).getAsJsonObject();
        Set<String> featureKinds = surfacePlan.getAsJsonArray("featureCells").asList().stream()
                .map(value -> value.getAsJsonObject().get("kind").getAsString())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(featureKinds.contains("ROAD_SLAB"));
        assertTrue(featureKinds.contains("ROAD_STAIR"));
        assertTrue(CityPlanningEndpointHandler.workflowBlueprintOutdoorArtifactsCurrent(
                debugRoot, debugRoot.resolve(runId), citySeedId));

        completion.addProperty("surfacePrintPlanSchema", "obsolete_city_land_use_surface_print_plan");
        Files.writeString(directory.resolve("city_land_use_planning_complete.json"),
                CityJson.GSON.toJson(completion));
        assertFalse(CityPlanningEndpointHandler.workflowBlueprintOutdoorArtifactsCurrent(
                debugRoot, debugRoot.resolve(runId), citySeedId),
                "surface print schema drift must force the Blueprint outdoor step to rerun");

        completion.addProperty("surfacePrintPlanSchema",
                CityLandUseSurfacePrintPlan.SCHEMA);
        completion.addProperty("sourceTerrainFieldHash", "sha256:stale");
        Files.writeString(directory.resolve("city_land_use_planning_complete.json"),
                CityJson.GSON.toJson(completion));
        assertFalse(CityPlanningEndpointHandler.workflowBlueprintOutdoorArtifactsCurrent(
                debugRoot, debugRoot.resolve(runId), citySeedId),
                "identity drift must force the Blueprint outdoor step to rerun");
    }

    @Test
    void planLandUseHttpRouteRequiresAcceptedBlueprintAndRejectsLegacyIntent() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-blueprint-outdoor-http-route-test");
        String runId = "run_blueprint_outdoor_http_route";
        String citySeedId = "city_test";
        prepareAcceptedBlueprintD6(debugRoot, runId, citySeedId, "GENERATE");
        JsonObject request = new JsonObject();
        request.addProperty("runId", runId);
        request.addProperty("citySeedId", citySeedId);

        JsonObject blueprintResponse = RealmPlanningHttpController.handleCityPlanLandUseRequest(
                debugRoot, request);

        assertEquals("city_blueprint", blueprintResponse.get("planningSource").getAsString());
        Path completionPath = debugRoot.resolve(runId).resolve("city_land_use_" + citySeedId)
                .resolve("city_land_use_planning_complete.json");
        assertEquals("city_land_use_planning_complete",
                JsonParser.parseString(Files.readString(completionPath)).getAsJsonObject()
                        .get("schema").getAsString());

        request.add("landUseIntentPlan", new JsonObject());
        IllegalArgumentException overrideFailure = assertThrows(IllegalArgumentException.class,
                () -> RealmPlanningHttpController.handleCityPlanLandUseRequest(debugRoot, request));
        assertTrue(overrideFailure.getMessage().contains("CITY_BLUEPRINT_WORKFLOW_LAND_USE_OVERRIDE_FORBIDDEN"));
        assertEquals("city_land_use_planning_complete",
                JsonParser.parseString(Files.readString(completionPath)).getAsJsonObject()
                        .get("schema").getAsString());

        Path legacyRoot = Files.createTempDirectory("city-legacy-outdoor-http-route-test");
        String legacyRunId = "run_legacy_outdoor_http_route";
        prepareD5Artifacts(legacyRoot, legacyRunId, citySeedId);
        Path legacyLandUse = legacyRoot.resolve(legacyRunId).resolve("city_land_use_" + citySeedId);
        Files.createDirectories(legacyLandUse);
        Files.writeString(legacyLandUse.resolve("land_use_terrain_field.json"),
                CityJson.GSON.toJson(new LandUseTerrainFieldCodec().toJson(endpointTerrain())));
        JsonObject legacyRequest = new JsonObject();
        legacyRequest.addProperty("runId", legacyRunId);
        legacyRequest.addProperty("citySeedId", citySeedId);
        legacyRequest.add("landUseIntentPlan", JsonParser.parseString("""
                {"schema":"city_land_use_intent_plan","cityId":"city_test"}
                """).getAsJsonObject());
        IllegalArgumentException legacyFailure = assertThrows(IllegalArgumentException.class,
                () -> RealmPlanningHttpController.handleCityPlanLandUseRequest(legacyRoot, legacyRequest));
        assertTrue(legacyFailure.getMessage().contains("CITY_BLUEPRINT_WORKFLOW_LAND_USE_OVERRIDE_FORBIDDEN"));
        assertFalse(Files.exists(legacyLandUse.resolve("city_land_use_planning_complete.json")));
    }

    @Test
    void planLandUseHttpRouteRejectsPartialBlueprintAuthorityAndMissingD6() throws Exception {
        String citySeedId = "city_test";
        Path partialRoot = Files.createTempDirectory("city-partial-blueprint-http-route-test");
        String partialRunId = "run_partial_blueprint_http_route";
        Path partialBlueprint = partialRoot.resolve(partialRunId)
                .resolve("city_blueprint_" + citySeedId);
        Files.createDirectories(partialBlueprint);
        Files.writeString(partialBlueprint.resolve("city_blueprint.json"), "{}");
        JsonObject partialRequest = new JsonObject();
        partialRequest.addProperty("runId", partialRunId);
        partialRequest.addProperty("citySeedId", citySeedId);

        IllegalArgumentException partialFailure = assertThrows(IllegalArgumentException.class,
                () -> RealmPlanningHttpController.handleCityPlanLandUseRequest(partialRoot, partialRequest));
        assertTrue(partialFailure.getMessage().contains("CITY_BLUEPRINT_LAND_USE_ROUTE_INCOMPLETE"));
        assertFalse(Files.exists(partialRoot.resolve(partialRunId)
                .resolve("city_land_use_" + citySeedId)
                .resolve("city_land_use_planning_complete.json")));

        Path missingD6Root = Files.createTempDirectory("city-blueprint-missing-d6-http-route-test");
        String missingD6RunId = "run_blueprint_missing_d6_http_route";
        prepareAcceptedBlueprintD6(missingD6Root, missingD6RunId, citySeedId, "GENERATE");
        Files.delete(missingD6Root.resolve(missingD6RunId).resolve("city_d6_" + citySeedId)
                .resolve("structure_materialization_plan.json"));
        JsonObject missingD6Request = new JsonObject();
        missingD6Request.addProperty("runId", missingD6RunId);
        missingD6Request.addProperty("citySeedId", citySeedId);

        IllegalArgumentException missingD6Failure = assertThrows(IllegalArgumentException.class,
                () -> RealmPlanningHttpController.handleCityPlanLandUseRequest(
                        missingD6Root, missingD6Request));
        assertTrue(missingD6Failure.getMessage().contains("CITY_BLUEPRINT_LAND_USE_D6_MISSING"));
        assertFalse(Files.exists(missingD6Root.resolve(missingD6RunId)
                .resolve("city_land_use_" + citySeedId)
                .resolve("city_land_use_planning_complete.json")));
    }

    @Test
    void blueprintOutdoorPreservePublishesIntentWithoutPlanning() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-blueprint-outdoor-preserve-test");
        String runId = "run_blueprint_outdoor_preserve";
        String citySeedId = "city_test";
        prepareAcceptedBlueprintD6(debugRoot, runId, citySeedId, "PRESERVE");

        JsonObject response = CityPlanningEndpointHandler.handlePlanBlueprintOutdoor(
                debugRoot, runId, citySeedId);

        Path directory = debugRoot.resolve(runId).resolve("city_land_use_" + citySeedId);
        assertTrue(response.get("ok").getAsBoolean());
        assertEquals("PRESERVE", response.get("outdoorMode").getAsString());
        assertFalse(response.get("planned").getAsBoolean());
        assertTrue(Files.isRegularFile(directory.resolve("city_outdoor_intent_plan.json")));
        assertFalse(Files.exists(directory.resolve("city_land_use_area_plan.json")));
        assertFalse(Files.exists(directory.resolve("city_land_use_surface_print_plan.json")));
        assertFalse(Files.exists(directory.resolve("city_land_use_planning_complete.json")));
    }

    @Test
    void blueprintWorkflowRejectsLegacyLandUseOverridesBeforePlanning() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-blueprint-outdoor-override-test");
        String runId = "run_blueprint_outdoor_override";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        JsonObject request = new JsonObject();
        request.addProperty("enableLandUseLayer", true);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleRunWorkflow(debugRoot,
                        Files.createTempDirectory("city-blueprint-outdoor-override-server"),
                        runId, citySeedId, request, null, null));

        assertTrue(failure.getMessage().contains("CITY_BLUEPRINT_WORKFLOW_LAND_USE_OVERRIDE_FORBIDDEN"));
    }

    @Test
    void runWorldIdentityAcceptsMatchingSeedAndDimension() throws Exception {
        Path runDir = Files.createTempDirectory("city-run-world-identity-match");
        Files.writeString(runDir.resolve("world_survey_context.json"), """
                {"dimensionId":"minecraft:overworld","worldSeed":"1269623911921362524"}
                """);

        assertDoesNotThrow(() -> CityPlanningEndpointHandler.requireMatchingRunWorldIdentity(
                runDir, "minecraft:overworld", 1269623911921362524L));
    }

    @Test
    void runWorldIdentityRejectsDifferentSeedBeforeD3Artifacts() throws Exception {
        Path runDir = Files.createTempDirectory("city-run-world-identity-seed-mismatch");
        Files.writeString(runDir.resolve("world_survey_context.json"), """
                {"dimensionId":"minecraft:overworld","worldSeed":"-281932406572342714"}
                """);
        Path d3Directory = runDir.resolve("city_d3_city_test");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.requireMatchingRunWorldIdentity(
                        runDir, "minecraft:overworld", 1269623911921362524L));

        assertTrue(failure.getMessage().contains("CITY_RUN_WORLD_IDENTITY_MISMATCH"), failure::getMessage);
        assertFalse(Files.exists(d3Directory));
    }

    @Test
    void runWorldIdentityRejectsDifferentDimension() throws Exception {
        Path runDir = Files.createTempDirectory("city-run-world-identity-dimension-mismatch");
        Files.writeString(runDir.resolve("world_survey_context.json"), """
                {"dimensionId":"minecraft:overworld","worldSeed":"42"}
                """);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.requireMatchingRunWorldIdentity(
                        runDir, "minecraft:the_nether", 42L));

        assertTrue(failure.getMessage().contains("CITY_RUN_WORLD_IDENTITY_MISMATCH"), failure::getMessage);
    }


    @Test
    void handlePlanLandUsePublishesAndFreezesSurfacePrintPlan() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-land-use-surface-plan-test");
        String runId = "run_land_use_surface_plan";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        Path landUseDirectory = debugRoot.resolve(runId).resolve("city_land_use_" + citySeedId);
        Files.createDirectories(landUseDirectory);
        Files.writeString(landUseDirectory.resolve("land_use_terrain_field.json"),
                CityJson.GSON.toJson(new LandUseTerrainFieldCodec().toJson(endpointTerrain())));

        JsonObject response = CityPlanningEndpointHandler.handlePlanLandUse(
                debugRoot, runId, citySeedId, null);

        Path surfacePath = landUseDirectory.resolve("city_land_use_surface_print_plan.json");
        assertTrue(Files.isRegularFile(surfacePath));
        JsonObject surfaceJson = JsonParser.parseString(Files.readString(surfacePath)).getAsJsonObject();
        var surfacePlan = new CityLandUseSurfacePrintPlanCodec().fromJson(surfaceJson);
        assertEquals(surfacePlan.planHash(), response.get("surfacePrintPlanHash").getAsString());
        assertEquals(surfaceJson, response.getAsJsonObject("landUseSurfacePrintPlan"));
        assertTrue(response.getAsJsonObject("artifacts").get("landUseSurfacePrintPlan")
                .getAsString().endsWith("city_land_use_surface_print_plan.json"));
        JsonObject completion = JsonParser.parseString(Files.readString(
                landUseDirectory.resolve("city_land_use_planning_complete.json"))).getAsJsonObject();
        assertEquals(surfacePlan.planHash(), completion.get("surfacePrintPlanHash").getAsString());
        assertFalse(completion.has("surfacePrintCatalogHash"));
    }

    @Test
    void handleExecuteD5RejectsUnknownSurfaceBlockBeforeLandUseRegistryMutation() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-land-use-unknown-block-test");
        Path serverRoot = Files.createTempDirectory("city-land-use-unknown-block-server");
        String runId = "run_land_use_unknown_block";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        Path landUseDirectory = debugRoot.resolve(runId).resolve("city_land_use_" + citySeedId);
        Files.createDirectories(landUseDirectory);
        Files.writeString(landUseDirectory.resolve("land_use_terrain_field.json"),
                CityJson.GSON.toJson(new LandUseTerrainFieldCodec().toJson(endpointTerrain())));
        CityPlanningEndpointHandler.handlePlanLandUse(debugRoot, runId, citySeedId, null);

        Path surfacePath = landUseDirectory.resolve("city_land_use_surface_print_plan.json");
        CityLandUseSurfacePrintPlanCodec codec = new CityLandUseSurfacePrintPlanCodec();
        JsonObject surfaceJson = JsonParser.parseString(Files.readString(surfacePath)).getAsJsonObject();
        JsonObject area = surfaceJson.getAsJsonArray("areas").get(0).getAsJsonObject();
        area.getAsJsonObject("surfaceSettings")
                .addProperty("surfaceBlockId", "minecraft:not_a_registered_block");
        area.getAsJsonObject("recipe")
                .addProperty("surfaceBlockId", "minecraft:not_a_registered_block");
        surfaceJson.remove("planHash");
        CityLandUseSurfacePrintPlan surfacePlan = codec.withComputedHash(codec.fromJson(surfaceJson));
        // Preserve the strict codec's explicit JSON nulls.
        Files.writeString(surfacePath, codec.toJson(surfacePlan).toString());
        Path completionPath = landUseDirectory.resolve("city_land_use_planning_complete.json");
        JsonObject completion = JsonParser.parseString(Files.readString(completionPath)).getAsJsonObject();
        completion.addProperty("surfacePrintPlanHash", surfacePlan.planHash());
        Files.writeString(completionPath, CityJson.GSON.toJson(completion));

        setLandUseSurfaceBlockPredicate(key ->
                !"minecraft:not_a_registered_block".equals(key.toString()));
        IllegalArgumentException failure;
        try {
            failure = assertThrows(IllegalArgumentException.class,
                    () -> CityPlanningEndpointHandler.handleExecuteD5(
                            debugRoot, serverRoot, runId, citySeedId, true, null,
                            null, true));
        } finally {
            resetLandUseRegistryTestState();
        }

        assertTrue(failure.getMessage().contains("CITY_LAND_USE_SURFACE_BLOCK_UNKNOWN"),
                failure::getMessage);
        assertFalse(Files.exists(CityLandUseWorldgenRegistry.activePlansPath(serverRoot)));
    }

    private static void setLandUseSurfaceBlockPredicate(Predicate<ResourceLocation> predicate)
            throws ReflectiveOperationException {
        java.lang.reflect.Method setter = CityLandUseWorldgenRegistry.class.getDeclaredMethod(
                "setSurfaceBlockExistsForTests", Predicate.class);
        setter.setAccessible(true);
        setter.invoke(null, predicate);
    }

    private static void resetLandUseRegistryTestState() throws ReflectiveOperationException {
        java.lang.reflect.Method reset = CityLandUseWorldgenRegistry.class.getDeclaredMethod("resetForTests");
        reset.setAccessible(true);
        reset.invoke(null);
    }

    @Test
    void handleExecuteD5RejectsCompletionThatDeclaresMissingSurfacePrintArtifact() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-land-use-missing-surface-test");
        Path serverRoot = Files.createTempDirectory("city-land-use-missing-surface-server");
        String runId = "run_land_use_missing_surface";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        Path landUseDirectory = debugRoot.resolve(runId).resolve("city_land_use_" + citySeedId);
        Files.createDirectories(landUseDirectory);
        Files.writeString(landUseDirectory.resolve("land_use_terrain_field.json"),
                CityJson.GSON.toJson(new LandUseTerrainFieldCodec().toJson(endpointTerrain())));
        CityPlanningEndpointHandler.handlePlanLandUse(debugRoot, runId, citySeedId, null);
        Files.delete(landUseDirectory.resolve("city_land_use_surface_print_plan.json"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        debugRoot, serverRoot, runId, citySeedId, true, null,
                        null, true));

        assertTrue(failure.getMessage().contains("CITY_LAND_USE_SURFACE_PRINT_PLAN_MISSING"));
        assertFalse(Files.exists(CityLandUseWorldgenRegistry.activePlansPath(serverRoot)));
    }

    @Test
    void handleExecuteD5RejectsOrphanSurfacePrintArtifact() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-land-use-orphan-surface-test");
        Path serverRoot = Files.createTempDirectory("city-land-use-orphan-surface-server");
        String runId = "run_land_use_orphan_surface";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        Path landUseDirectory = debugRoot.resolve(runId).resolve("city_land_use_" + citySeedId);
        Files.createDirectories(landUseDirectory);
        Files.writeString(landUseDirectory.resolve("city_land_use_surface_print_plan.json"), "{}");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        debugRoot, serverRoot, runId, citySeedId, true, null,
                        null, null));

        assertTrue(failure.getMessage().contains("CITY_LAND_USE_PLAN_INCOMPLETE"));
        assertFalse(Files.exists(CityLandUseWorldgenRegistry.activePlansPath(serverRoot)));
    }


    @Test
    void lockedTemplatePlanUsesBeardThinWithoutStructureStartSignature() {
        JsonObject plan = JsonParser.parseString("""
                {
                  "schema": "city_template_placement_plan",
                  "cityId": "city_test",
                  "locked": true,
                  "plannedWorldgenStructures": [{
                    "status": "planned_worldgen",
                    "anchorId": "template_house",
                    "templateId": "geomantia:test_house",
                    "materializationSource": "structure_template_nbt",
                    "templateRef": "geomantia:d6d7_fixture/house",
                    "templateHash": "sha256:test-house",
                    "variantId": "fixed_v1",
                    "rotation": "NONE",
                    "mirror": "NONE",
                    "rawSize": {"width":11,"height":1,"depth":11},
                    "anchorBlock": {"x":0,"z":0},
                    "terrainPosePolicy": "structure_start_beard_thin",
                    "templateDatumPolicy": "generator_base_height_motion_blocking_no_leaves",
                    "locked": true,
                    "actualFootprint": {"minX":0,"minZ":0,"maxX":10,"maxZ":10},
                    "lockedActualFootprint": {"minX":0,"minZ":0,"maxX":10,"maxZ":10},
                    "lockedCollisionEnvelope": {"minX":0,"minZ":0,"maxX":10,"maxZ":10},
                    "collisionEnvelope": {"minX":0,"minZ":0,"maxX":10,"maxZ":10},
                    "maskEnvelope": {"minX":0,"minZ":0,"maxX":10,"maxZ":10},
                    "ownerChunks": [{"x":0,"z":0}]
                  }]
                }
                """).getAsJsonObject();

        assertDoesNotThrow(() -> CityPlanningEndpointHandler.validateLockedMaterializationPlan(plan));
        JsonObject templateItem = plan.getAsJsonArray("plannedWorldgenStructures").get(0).getAsJsonObject();
        templateItem.addProperty("templateDatumPolicy", "worldgen_surface_motion_blocking_no_leaves");
        assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.validateLockedMaterializationPlan(plan));
        templateItem.addProperty("templateDatumPolicy", "unsupported_datum_policy");
        assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.validateLockedMaterializationPlan(plan));
        plan.getAsJsonArray("plannedWorldgenStructures").get(0).getAsJsonObject().remove("templateDatumPolicy");
        assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.validateLockedMaterializationPlan(plan));
        plan.getAsJsonArray("plannedWorldgenStructures").get(0).getAsJsonObject()
                .addProperty("templateDatumPolicy", "generator_base_height_motion_blocking_no_leaves");
        plan.getAsJsonArray("plannedWorldgenStructures").get(0).getAsJsonObject().remove("templateRef");
        plan.getAsJsonArray("plannedWorldgenStructures").get(0).getAsJsonObject().remove("materializationSource");
        assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.validateLockedMaterializationPlan(plan));
    }

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
        Path templateCatalogPath = runDir.resolve("template_catalog.json");
        Files.writeString(templateCatalogPath, fixedTemplateCatalog());

        JsonObject response = CityPlanningEndpointHandler.handlePlanD4(
                debugRoot, runId, citySeedId,
                terraSenseSource(catalogPath),
                templateCatalogSource(templateCatalogPath), structureAnchorPlan(review, 1));

        assertTrue(response.get("ok").getAsBoolean());
        JsonObject artifacts = response.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("structureAnchorMap").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("structureAnchorPreview").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("qualityReport").getAsString())));
        assertFalse(response.getAsJsonObject("structureAnchorMap").getAsJsonArray("anchors").isEmpty());
    }

    @Test
    void aiSelectedCapitalRequiresCurrentAcceptedD3SiteReviewBeforeD4() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d3-site-review-test");
        String runId = "run_capital_review";
        String citySeedId = "city_capital";
        Path runDir = debugRoot.resolve(runId);
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("city_seed_registry.json"), """
                {
                  "citySeeds": [
                    {
                      "citySeedId": "city_capital",
                      "realmId": "realm_test",
                      "role": "capital",
                      "theoreticalScale": "capital",
                      "anchorBlock": {"x": 0, "z": 0},
                      "planningRadiusCells": 64,
                      "candidateId": "MINECRAFT_PLAINS-01",
                      "source": {
                        "patchSelectionRef": "patch_selection_capital",
                        "siteSelectionMode": "ai_candidate_selection"
                      }
                    }
                  ]
                }
                """);

        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new CitySiteContextBuilder(config).build(
                citySeedId, "realm_test", "overworld", citySeedId, "MINECRAFT_PLAINS-01",
                0, 0, "capital", "capital", 64, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context,
                List.of(patch("plain", LandformType.PLAIN, -50, -50, 50, 50)));
        Path d3Dir = runDir.resolve("city_d3_" + citySeedId);
        Files.createDirectories(d3Dir);
        Path packagePath = d3Dir.resolve("city_landform_review_package.json");
        Files.writeString(packagePath, CityJson.GSON.toJson(review.asJson()));

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handlePlanD4(
                        debugRoot, runId, citySeedId, null, null, new JsonObject()));
        assertTrue(missing.getMessage().contains("CITY_D3_SITE_REVIEW_REQUIRED"), missing::getMessage);

        JsonObject reselect = CityPlanningEndpointHandler.handleReviewD3Site(
                debugRoot, runId, citySeedId, "reselect_required",
                "Local D3 terrain cannot carry the intended capital.", "ai");
        assertEquals("reselection_required", reselect.get("siteReviewStatus").getAsString());
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handlePlanD4(
                        debugRoot, runId, citySeedId, null, null, new JsonObject()));
        assertTrue(rejected.getMessage().contains("CITY_D3_SITE_RESELECTION_REQUIRED"), rejected::getMessage);

        JsonObject accepted = CityPlanningEndpointHandler.handleReviewD3Site(
                debugRoot, runId, citySeedId, "accept_selected_site",
                "Local D3 terrain supports the intended capital.", "ai");
        assertEquals("accepted", accepted.get("siteReviewStatus").getAsString());
        IllegalArgumentException afterGate = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handlePlanD4(
                        debugRoot, runId, citySeedId, null, null, new JsonObject()));
        assertTrue(afterGate.getMessage().contains("templateCatalogSource object is required"),
                afterGate::getMessage);

        Files.writeString(packagePath, Files.readString(packagePath) + " ");
        IllegalArgumentException stale = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handlePlanD4(
                        debugRoot, runId, citySeedId, null, null, new JsonObject()));
        assertTrue(stale.getMessage().contains("CITY_D3_SITE_REVIEW_STALE"), stale::getMessage);
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
        Path templateCatalogPath = runDir.resolve("template_catalog.json");
        Files.writeString(templateCatalogPath, fixedTemplateCatalog());
        JsonObject candidates = CityPlanningEndpointHandler.handlePlanD4Candidates(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath),
                designSlotPlan(review), templateCatalogSource(templateCatalogPath));

        assertTrue(candidates.get("ok").getAsBoolean(), candidates.toString());
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
                  "schema": "city_d4_anchor_selection_plan",
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
                selectionPlan, templateCatalogSource(templateCatalogPath), null);

        assertTrue(selected.get("ok").getAsBoolean());
        JsonObject selectedArtifacts = selected.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(selectedArtifacts.get("structureAnchorMap").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(selectedArtifacts.get("sourceAnchorCandidateSet").getAsString())));
        assertEquals("admin_core_01", selected.getAsJsonObject("structureAnchorMap")
                .getAsJsonArray("anchors").get(0).getAsJsonObject()
                .get("anchorId").getAsString());
    }

    @Test
    void handlePlanD4ArrayCandidates_writesArtifactsAndFeedsStandardD4() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d4-array-candidates-test");
        String runId = "run_d4_array_candidates";
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
                      "planningRadiusCells": 128,
                      "candidateId": "candidate_test"
                    }
                  ]
                }
                """);

        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new CitySiteContextBuilder(config).build(
                "city_test", "realm_test", "overworld",
                "city_test", "candidate_test", 0, 0,
                "village", "village", 128, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context, List.of(
                patch("plain_array", LandformType.PLAIN, -220, -220, 220, 220)));
        Path d3Dir = runDir.resolve("city_d3_city_test");
        Files.createDirectories(d3Dir);
        Files.writeString(d3Dir.resolve("city_landform_review_package.json"),
                CityJson.GSON.toJson(review.asJson()));

        Path catalogPath = runDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());
        Path templateCatalogPath = runDir.resolve("template_catalog.json");
        Files.writeString(templateCatalogPath, fixedTemplateCatalog());
        JsonObject arrayCandidates = CityPlanningEndpointHandler.handlePlanD4ArrayCandidates(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath),
                arrayCandidatePlan(review), templateCatalogSource(templateCatalogPath), null, new JsonArray());

        assertTrue(arrayCandidates.get("ok").getAsBoolean());
        JsonObject artifacts = arrayCandidates.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("arrayCandidateSet").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("arrayCandidatePreview").getAsString())));
        JsonObject firstGroup = arrayCandidates.getAsJsonObject("arrayCandidateSet")
                .getAsJsonArray("arrayCandidates").get(0).getAsJsonObject();
        JsonObject expandedPlan = firstGroup.getAsJsonObject("expandedStructureAnchorPlan");
        assertEquals(4, expandedPlan.getAsJsonArray("anchors").size());

        JsonObject d4 = CityPlanningEndpointHandler.handlePlanD4(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath),
                templateCatalogSource(templateCatalogPath), expandedPlan);

        assertTrue(d4.get("ok").getAsBoolean());
        assertEquals(4, d4.getAsJsonObject("structureAnchorMap")
                .getAsJsonArray("anchors").size());
    }

    @Test
    void handleD4DesignLoopState_writesReadsAppendsTwoRoundsAndWriteBack() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d4-design-loop-state-test");
        String runId = "run_d4_design_loop_state";
        String citySeedId = "city_test";
        Path runDir = debugRoot.resolve(runId);
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("city_seed_registry.json"), """
                {
                  "citySeeds": [
                    {
                      "citySeedId": "city_test",
                      "realmId": "realm_test",
                      "role": "town",
                      "theoreticalScale": "town",
                      "anchorBlock": {"x": 0, "z": 0},
                      "planningRadiusCells": 128,
                      "candidateId": "candidate_test"
                    }
                  ]
                }
                """);
        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new CitySiteContextBuilder(config).build(
                "city_test", "realm_test", "overworld",
                "city_test", "candidate_test", 0, 0,
                "town", "town", 128, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context, List.of(
                patch("plain_design", LandformType.PLAIN, -160, -160, 160, 160)));
        Path d3Dir = runDir.resolve("city_d3_city_test");
        Files.createDirectories(d3Dir);
        Files.writeString(d3Dir.resolve("city_landform_review_package.json"),
                CityJson.GSON.toJson(review.asJson()));

        JsonObject created = CityPlanningEndpointHandler.handleCreateD4DesignLoopState(
                debugRoot, runId, citySeedId, new JsonObject(), null);
        assertTrue(created.get("ok").getAsBoolean());
        assertEquals("d4_design_loop_state_0000", created.getAsJsonObject("designLoopState")
                .get("stateId").getAsString());
        JsonObject createArtifacts = created.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(createArtifacts.get("designLoopState").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(createArtifacts.get("designLoopOccupiedField").getAsString())));

        JsonObject firstRound = JsonParser.parseString("""
                {
                  "roundId": "round_01",
                  "anchors": [
                    {
                      "anchorId": "admin_core",
                      "structureId": "geomantia:test_house",
                      "collisionEnvelope": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8},
                      "safetyEnvelope": {"minX": -80, "minZ": -80, "maxX": 80, "maxZ": 80}
                    }
                  ],
                  "functionZones": [
                    {"zoneId": "civic_core", "blockBounds": {"minX": -16, "minZ": -16, "maxX": 16, "maxZ": 16}}
                  ],
                  "arrayZones": {
                    "arrayZones": [
                      {"arrayId": "core_ring", "blockBounds": {"minX": -24, "minZ": -24, "maxX": 24, "maxZ": 24}}
                    ]
                  },
                  "nextAiContextSummary": {"summary": "admin core placed"}
                }
                """).getAsJsonObject();
        JsonObject first = CityPlanningEndpointHandler.handleAppendD4DesignLoopRound(
                debugRoot, runId, citySeedId,
                created.getAsJsonObject("designLoopState").get("stateId").getAsString(),
                firstRound, null);
        JsonObject firstOccupied = first.getAsJsonObject("occupiedField")
                .getAsJsonArray("occupied").get(0).getAsJsonObject();
        assertEquals("collisionEnvelope", firstOccupied.get("envelopeSource").getAsString());
        assertEquals(8, firstOccupied.getAsJsonObject("blockBounds").get("maxX").getAsInt());
        assertFalse(first.getAsJsonObject("designLoopState")
                .getAsJsonArray("anchors").get(0).getAsJsonObject().has("safetyEnvelope"));

        JsonObject read = CityPlanningEndpointHandler.handleReadD4DesignLoopState(
                debugRoot, runId, citySeedId, null);
        assertEquals("d4_design_loop_state_0001", read.getAsJsonObject("designLoopState")
                .get("stateId").getAsString());

        JsonObject secondRound = JsonParser.parseString("""
                {
                  "roundId": "round_02",
                  "anchors": [
                    {
                      "anchorId": "market_01",
                      "structureId": "minecraft:jungle_pyramid",
                      "collisionEnvelope": {"minX": 40, "minZ": 40, "maxX": 58, "maxZ": 58}
                    }
                  ],
                  "nextAiContextSummary": {"summary": "market can avoid admin occupied field"}
                }
                """).getAsJsonObject();
        JsonObject second = CityPlanningEndpointHandler.handleAppendD4DesignLoopRound(
                debugRoot, runId, citySeedId,
                read.getAsJsonObject("designLoopState").get("stateId").getAsString(),
                secondRound, null);
        JsonArray occupied = second.getAsJsonObject("occupiedField").getAsJsonArray("occupied");
        assertEquals(2, occupied.size());
        assertEquals(8, occupied.get(0).getAsJsonObject()
                .getAsJsonObject("blockBounds").get("maxX").getAsInt());
        assertEquals(40, occupied.get(1).getAsJsonObject()
                .getAsJsonObject("blockBounds").get("minX").getAsInt());
        assertEquals(2, second.getAsJsonObject("nextAiContextSummary")
                .get("occupiedEnvelopeCount").getAsInt());

        JsonObject written = CityPlanningEndpointHandler.handleWriteD4DesignLoopState(
                debugRoot, runId, citySeedId,
                second.getAsJsonObject("designLoopState").get("stateId").getAsString(),
                second.getAsJsonObject("designLoopState"));
        assertTrue(written.get("ok").getAsBoolean());
        JsonObject writeArtifacts = written.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(writeArtifacts
                .get("designLoopNextAiContextSummary").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(writeArtifacts
                .get("designLoopExecutionTrace").getAsString())));

        IllegalArgumentException d5 = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId));
        assertTrue(d5.getMessage().contains("D4 artifacts not found"));

        IllegalArgumentException d6 = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handlePlanD6(debugRoot, runId, citySeedId, null, null));
        assertTrue(d6.getMessage().contains("D4 structure_anchor_map.json not found"));

        IllegalArgumentException executeD5 = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        debugRoot, Files.createTempDirectory("city-d4-loop-submit-server-root"),
                        runId, citySeedId, true, null));
        assertTrue(executeD5.getMessage().contains("D4 structure_anchor_map.json not found"));

        IllegalArgumentException executeD7 = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD7(
                        debugRoot, runId, citySeedId, 12345L, false, null, null));
        assertTrue(executeD7.getMessage().contains("D6 artifacts not found"));
    }

    @Test
    void handlePlanD4StructureClusterGroups_writesGroupArtifactsAndSelectsWholeGroup() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d4-cluster-groups-test");
        String runId = "run_d4_cluster_groups";
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
                      "planningRadiusCells": 128,
                      "candidateId": "candidate_test"
                    }
                  ]
                }
                """);

        CityPlanningConfig config = CityPlanningConfig.defaults();
        CitySiteContext context = new CitySiteContextBuilder(config).build(
                "city_test", "realm_test", "overworld",
                "city_test", "candidate_test", 0, 0,
                "village", "village", 128, 4, null);
        CityLandformReviewPackage review = new CityLandformReviewBuilder(config).build(context, List.of(
                patch("plain_cluster", LandformType.PLAIN, -220, -220, 220, 220)));
        Path d3Dir = runDir.resolve("city_d3_city_test");
        Files.createDirectories(d3Dir);
        Files.writeString(d3Dir.resolve("city_landform_review_package.json"),
                CityJson.GSON.toJson(review.asJson()));

        Path catalogPath = runDir.resolve("debug_structure_profile_catalog.json");
        Files.writeString(catalogPath, debugStructureCatalog());
        Path templateCatalogPath = runDir.resolve("template_catalog.json");
        Files.writeString(templateCatalogPath, fixedTemplateCatalog());
        JsonObject planned = CityPlanningEndpointHandler.handlePlanD4StructureClusterGroups(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath),
                designSlotPlan(review), templateCatalogSource(templateCatalogPath), null, null, null);

        assertTrue(planned.get("ok").getAsBoolean());
        JsonObject artifacts = planned.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("structureClusterGroupCandidateSet").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(artifacts.get("structureClusterGroupCandidatesPreview").getAsString())));
        JsonObject firstGroup = planned.getAsJsonObject("structureClusterGroupCandidateSet")
                .getAsJsonArray("groupCandidates").get(0).getAsJsonObject();
        assertEquals(2, firstGroup.getAsJsonArray("items").size());

        JsonObject selected = CityPlanningEndpointHandler.handleSelectD4StructureClusterGroup(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath),
                firstGroup.get("groupCandidateId").getAsString(),
                templateCatalogSource(templateCatalogPath), null);

        assertTrue(selected.get("ok").getAsBoolean());
        assertTrue(selected.getAsJsonObject("artifacts").has("sourceStructureClusterGroupCandidateSet"));
        assertEquals(2, selected.getAsJsonObject("structureAnchorMap")
                .getAsJsonArray("anchors").size());
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
        Path templateCatalogPath = runDir.resolve("template_catalog.json");
        Files.writeString(templateCatalogPath, fixedTemplateCatalog());
        JsonObject created = CityPlanningEndpointHandler.handleCreateD4CandidateSession(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath),
                designSlotPlan(review), templateCatalogSource(templateCatalogPath), "session_test");
        assertTrue(created.get("ok").getAsBoolean());
        assertEquals("admin_core", created.get("currentSlotId").getAsString());
        assertTrue(Files.exists(debugRoot.resolve(created.getAsJsonObject("artifacts")
                .get("d4CandidateSession").getAsString())));

        JsonObject next = CityPlanningEndpointHandler.handlePlanD4NextCandidates(
                debugRoot, runId, citySeedId, templateCatalogSource(templateCatalogPath));
        assertTrue(next.get("ok").getAsBoolean());
        assertFalse(next.getAsJsonObject("artifacts").has("structureClusterCandidateOverview"));
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
                debugRoot, runId, citySeedId, templateCatalogSource(templateCatalogPath));
        JsonObject secondCandidate = nextSecond.getAsJsonObject("slotCandidateSet")
                .getAsJsonArray("slotCandidates").get(0).getAsJsonObject()
                .getAsJsonArray("candidates").get(0).getAsJsonObject();
        CityPlanningEndpointHandler.handleSelectD4Candidate(
                debugRoot, runId, citySeedId, "session_test", "residential_01",
                secondCandidate.get("candidateId").getAsString(), "residential_01",
                "test second pick", false);

        JsonObject finalized = CityPlanningEndpointHandler.handleFinalizeD4CandidateSession(
                debugRoot, runId, citySeedId, terraSenseSource(catalogPath),
                templateCatalogSource(templateCatalogPath), "session_test");
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
        Path templateCatalogPath = runDir.resolve("template_catalog.json");
        Files.writeString(templateCatalogPath, fixedTemplateCatalog());
        CityPlanningEndpointHandler.handlePlanD4(debugRoot, runId, citySeedId,
                terraSenseSource(catalogPath), templateCatalogSource(templateCatalogPath),
                structureAnchorPlan(review, 2));

        JsonObject response = CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId);

        assertTrue(response.get("ok").getAsBoolean());
        assertTrue(response.getAsJsonObject("buildOperationPlan").getAsJsonArray("operations").isEmpty());
        assertEquals("d7_after_worldgen_ledger",
                response.getAsJsonObject("reservationMaskPlan").get("roadPlanningStage").getAsString());
        assertFalse(response.getAsJsonObject("reservationMaskPlan").getAsJsonArray("noVegetationMask").isEmpty());
        assertTrue(response.getAsJsonObject("reservationMaskPlan").has("wallReservationPlan"));
        assertEquals("city_wall_reservation_plan",
                response.getAsJsonObject("wallReservationPlan").get("schema").getAsString());
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

        Path d6Dir = debugRoot.resolve(runId).resolve("city_d6_" + citySeedId);
        JsonObject d6Plan = JsonParser.parseString(Files.readString(
                d6Dir.resolve("structure_materialization_plan.json"))).getAsJsonObject();
        JsonObject d6Trace = JsonParser.parseString(Files.readString(
                d6Dir.resolve("structure_materialization_trace.json"))).getAsJsonObject();
        Path d6Preview = new com.rinsing.geomantia.systems.city.infrastructure.preview.CityStructureLandingPreviewRenderer()
                .renderD6(d6Plan, d6Trace, d6Dir);

        assertTrue(d6Plan.get("locked").getAsBoolean());
        assertEquals("current_world_template_nbt", d6Plan.get("preflightMode").getAsString());
        assertFalse(d6Plan.getAsJsonArray("plannedWorldgenStructures").isEmpty());
        assertTrue(Files.exists(d6Dir.resolve("structure_materialization_plan.json")));
        assertTrue(Files.exists(d6Dir.resolve("placed_structure_ledger.json")));
        assertTrue(Files.exists(d6Dir.resolve("structure_materialization_trace.json")));
        assertTrue(Files.exists(d6Preview));

        CityPlanningEndpointHandler.handleExecuteD5(
                debugRoot, Files.createTempDirectory("city-d6d7-server-root"),
                runId, citySeedId, true, null);

        JsonObject d7 = CityPlanningEndpointHandler.handleExecuteD7(
                debugRoot,
                runId,
                citySeedId,
                12345L,
                false,
                null,
                null);

        assertTrue(d7.get("ok").getAsBoolean());
        assertTrue(d7.get("worldgenPlacementMode").getAsBoolean());
        JsonObject d7Artifacts = d7.getAsJsonObject("artifacts");
        assertTrue(Files.exists(debugRoot.resolve(d7Artifacts.get("placedStructureLedger").getAsString())));
        assertTrue(Files.exists(debugRoot.resolve(d7Artifacts.get("structureMaterializationTrace").getAsString())));
        Path placedPreview = debugRoot.resolve(d7Artifacts.get("placedStructurePreview").getAsString());
        assertTrue(Files.exists(placedPreview));
        assertTrue(d7Artifacts.has("sourceD3Package"));
        assertPreviewHasPatchBackdrop(placedPreview);
        assertEquals(0, d7.getAsJsonObject("placedStructureLedger")
                .getAsJsonArray("placedStructures")
                .size());
        assertTrue(d7.getAsJsonObject("structureMaterializationTrace")
                .getAsJsonObject("waitingSummary")
                .has("WORLDGEN_OBSERVATION_DISABLED"));
    }

    @Test
    void activeD6AndD7RejectLegacyCityArtifacts() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-legacy-artifact-rejection-test");
        String runId = "run_legacy_artifacts";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        Path runDir = debugRoot.resolve(runId);
        Path d4Dir = runDir.resolve("city_d4_" + citySeedId);
        Files.delete(d4Dir.resolve("structure_anchor_map.json"));
        Files.writeString(d4Dir.resolve("function_zone_map.json"), "{}");

        IllegalArgumentException d6Error = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handlePlanD6(
                        debugRoot, runId, citySeedId, null, null));

        assertTrue(d6Error.getMessage().contains("LEGACY_CITY_FUNCTION_ZONE_FLOW_REMOVED"));
        assertTrue(d6Error.getMessage().contains("D4 legacy artifact function_zone_map.json"));

        Path d6Dir = runDir.resolve("city_d6_" + citySeedId);
        Files.createDirectories(d6Dir);
        Files.writeString(d6Dir.resolve("buildable_area_map.json"), "{}");

        IllegalArgumentException d7Error = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD7(
                        debugRoot, runId, citySeedId, 12345L, false, null, null));

        assertTrue(d7Error.getMessage().contains("LEGACY_CITY_FUNCTION_ZONE_FLOW_REMOVED"));
        assertTrue(d7Error.getMessage().contains("D6 legacy artifact buildable_area_map.json"));
    }








    @Test
    void structureCatalogQueryResolvesVocabularyTagsFiltersAndStaysReadOnly() throws Exception {
        Path baseDirectory = Files.createTempDirectory("city-structure-catalog-query-test");
        Path catalogPath = baseDirectory.resolve("debug_structure_profile_catalog.json");
        Path vocabularyPath = baseDirectory.resolve("StructureVocabulary.snapshot.json");
        Files.writeString(catalogPath, """
                {
                  "catalogMode": "debug",
                  "structures": [
                    {
                      "structureId": "test:windmill",
                      "sourceProfileRef": "terrasense://windmill",
                      "reviewState": "approved",
                      "functionTerms": ["function.agriculture"],
                      "planningRoleTerms": ["planning_role.fill"],
                      "terrainModes": ["SURFACE"]
                    },
                    {
                      "structureId": "test:fishing_boat",
                      "sourceProfileRef": "terrasense://fishing_boat",
                      "reviewState": "approved",
                      "functionTerms": ["function.harbor"],
                      "planningRoleTerms": ["planning_role.key"],
                      "terrainModes": ["SURFACE"]
                    }
                  ]
                }
                """);
        Files.writeString(vocabularyPath, """
                {
                  "terms": [
                    {"term_id": "function.agriculture", "label": "农业", "aliases": ["farm"], "status": "approved"},
                    {"term_id": "function.harbor", "label": "港口", "aliases": ["harbor"], "status": "approved"},
                    {"term_id": "planning_role.fill", "label": "填充", "aliases": ["fill"], "status": "approved"},
                    {"term_id": "planning_role.key", "label": "关键", "aliases": ["key"], "status": "approved"},
                    {"term_id": "terrain.land_only", "label": "仅陆地", "aliases": ["land"], "status": "approved"},
                    {"term_id": "terrain.water_only", "label": "仅水上", "aliases": ["water"], "status": "approved"}
                  ]
                }
                """);
        JsonObject source = new JsonObject();
        source.addProperty("schema", "terrasense_structure_profile_source");
        source.addProperty("sourceType", "debug_catalog");
        source.addProperty("catalogMode", "debug");
        source.addProperty("debugCatalogPath", catalogPath.toString());
        source.addProperty("vocabularySnapshotPath", vocabularyPath.toString());

        JsonObject query = JsonParser.parseString("""
                {
                  "allOfTerms": ["填充"],
                  "anyOfTerms": ["farm", "港口"],
                  "excludeTerms": ["港口"],
                  "limit": 10
                }
                """).getAsJsonObject();
        JsonObject response = CityPlanningEndpointHandler.handleQueryStructureCatalog(baseDirectory, source, query);
        assertTrue(response.get("ok").getAsBoolean());
        assertTrue(response.get("readOnly").getAsBoolean());
        assertTrue(response.getAsJsonObject("vocabulary").get("available").getAsBoolean());
        assertEquals(1, response.get("matchedCount").getAsInt());
        JsonObject windmill = response.getAsJsonArray("candidates").get(0).getAsJsonObject();
        assertEquals("city_structure_catalog_query", response.get("schema").getAsString());
        assertEquals("test:windmill", windmill.get("semanticProfileId").getAsString());
        assertEquals("planning_role.fill", windmill.getAsJsonArray("matchedCanonicalTerms").get(0).getAsString());
        assertEquals("function.agriculture", windmill.getAsJsonArray("matchedCanonicalTerms").get(1).getAsString());
        JsonObject terms = windmill.getAsJsonObject("terms");
        assertEquals(4, terms.size());
        assertTrue(terms.has("functionTerms"));
        assertTrue(terms.has("planningRoleTerms"));
        assertTrue(terms.has("terrainModes"));
        assertTrue(terms.has("styleTerms"));
        assertFalse(terms.has("semanticTerms"));
        assertFalse(windmill.has("qualityTerms"));
        assertFalse(windmill.has("structureId"));
        assertFalse(windmill.has("hardFacts"));
        assertEquals("debug", windmill.getAsJsonObject("profileSource").get("catalogMode").getAsString());

        JsonObject canonicalQuery = JsonParser.parseString("""
                {"allOfTerms": ["function.harbor"]}
                """).getAsJsonObject();
        JsonObject canonicalResponse = CityPlanningEndpointHandler.handleQueryStructureCatalog(
                baseDirectory, source, canonicalQuery);
        assertEquals("test:fishing_boat", canonicalResponse.getAsJsonArray("candidates").get(0)
                .getAsJsonObject().get("semanticProfileId").getAsString());

        JsonObject chineseLabelQuery = JsonParser.parseString("""
                {"allOfTerms": ["农业"]}
                """).getAsJsonObject();
        JsonObject chineseLabelResponse = CityPlanningEndpointHandler.handleQueryStructureCatalog(
                baseDirectory, source, chineseLabelQuery);
        assertEquals("test:windmill", chineseLabelResponse.getAsJsonArray("candidates").get(0)
                .getAsJsonObject().get("semanticProfileId").getAsString());

        IllegalArgumentException unresolved = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleQueryStructureCatalog(baseDirectory, source,
                        JsonParser.parseString("{\"allOfTerms\":[\"未定义标签\"]}").getAsJsonObject()));
        assertTrue(unresolved.getMessage().contains("CITY_STRUCTURE_QUERY_TERM_UNRESOLVED"));
    }

    @Test
    void handleExecuteD5RequiresLockedD6Plan() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d5-requires-d6");
        String runId = "run_d5_requires_d6";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        Files.delete(debugRoot.resolve(runId).resolve("city_d6_" + citySeedId)
                .resolve("structure_materialization_plan.json"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        debugRoot, Files.createTempDirectory("city-d5-requires-d6-server-root"),
                        runId, citySeedId, true, null));

        assertTrue(ex.getMessage().contains("Run city_plan_d6 before city_execute_d5"));
    }

    @Test
    void handleExecuteD5RejectsInvalidLandUseCompletionBeforeRegistryMutation() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-land-use-completion-test");
        Path serverRoot = Files.createTempDirectory("city-land-use-completion-server-root");
        String runId = "run_land_use_completion";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);

        Path completionPath = writeEmptyLandUseArtifacts(debugRoot, runId, citySeedId);
        writeEmptyLandUseSurfaceArtifact(debugRoot, runId, citySeedId, completionPath);
        JsonObject valid = JsonParser.parseString(Files.readString(completionPath)).getAsJsonObject();
        List<MarkerMutation> invalidMarkers = List.of(
                new MarkerMutation("schema", "obsolete_city_land_use_planning_complete",
                        "CITY_LAND_USE_PLAN_INCOMPLETE", "completion schema is invalid"),
                new MarkerMutation("cityId", "wrong_city",
                        "CITY_LAND_USE_PLAN_INCOMPLETE", "completion cityId does not match plan"),
                new MarkerMutation("planHash", "stale_plan_hash",
                        "CITY_LAND_USE_PLAN_INCOMPLETE", "completion planHash does not match plan"),
                new MarkerMutation("surfacePrintCatalogHash", "legacy_catalog_hash",
                        "CITY_LAND_USE_PLAN_INCOMPLETE", "completion field is unsupported"),
                new MarkerMutation("ruleProfileHash", "",
                        "CITY_LAND_USE_RULE_PROFILE_HASH_MISMATCH", ""),
                new MarkerMutation("sourceD6Hash", "",
                        "CITY_LAND_USE_SOURCE_D6_HASH_MISMATCH", ""),
                new MarkerMutation("completedAt", "",
                        "CITY_LAND_USE_PLAN_INCOMPLETE", "completion completedAt is required"));

        for (MarkerMutation mutation : invalidMarkers) {
            JsonObject invalid = valid.deepCopy();
            invalid.addProperty(mutation.field(), mutation.value());
            Files.writeString(completionPath, CityJson.GSON.toJson(invalid));

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> CityPlanningEndpointHandler.handleExecuteD5(
                            debugRoot, serverRoot, runId, citySeedId, true, null,
                            null, true), mutation.field());

            assertTrue(failure.getMessage().contains(mutation.reasonCode()), mutation.field());
            assertTrue(mutation.message().isBlank()
                    || failure.getMessage().contains(mutation.message()), mutation.field());
            assertFalse(Files.exists(CityLandUseWorldgenRegistry.activePlansPath(serverRoot)),
                    "invalid completion must fail before LandUse registry mutation: " + mutation.field());
        }

        Path surfacePath = completionPath.getParent().resolve("city_land_use_surface_print_plan.json");
        JsonObject blankHashSurface = JsonParser.parseString(Files.readString(surfacePath)).getAsJsonObject();
        blankHashSurface.remove("planHash");
        Files.writeString(surfacePath, CityJson.GSON.toJson(blankHashSurface));
        JsonObject blankHashCompletion = valid.deepCopy();
        blankHashCompletion.addProperty("surfacePrintPlanHash", "");
        Files.writeString(completionPath, CityJson.GSON.toJson(blankHashCompletion));

        IllegalArgumentException blankHashFailure = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        debugRoot, serverRoot, runId, citySeedId, true, null,
                        null, true));

        assertTrue(blankHashFailure.getMessage().contains(
                "CITY_LAND_USE_SURFACE_PRINT_COMPLETION_MISMATCH"));
        assertFalse(Files.exists(CityLandUseWorldgenRegistry.activePlansPath(serverRoot)));
    }

    @Test
    void handleExecuteD5ExplicitFalseIgnoresIncompleteArtifactAndDeactivatesLandUse() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-land-use-explicit-disable-test");
        Path serverRoot = Files.createTempDirectory("city-land-use-explicit-disable-server-root");
        String runId = "run_land_use_explicit_disable";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        Path completionPath = writeEmptyLandUseArtifacts(debugRoot, runId, citySeedId);
        writeEmptyLandUseSurfaceArtifact(debugRoot, runId, citySeedId, completionPath);

        JsonObject activation = CityPlanningEndpointHandler.handleExecuteD5(
                debugRoot, serverRoot, runId, citySeedId, true, null,
                null, true);
        assertTrue(activation.get("landUseWorldgenMode").getAsBoolean());
        assertEquals(1, activation.getAsJsonObject("activeLandUseSummary")
                .get("activePlanCount").getAsInt());

        Files.delete(completionPath);
        JsonObject deactivation = CityPlanningEndpointHandler.handleExecuteD5(
                debugRoot, serverRoot, runId, citySeedId, true, null,
                null, false);

        assertFalse(deactivation.get("landUseWorldgenMode").getAsBoolean());
        assertEquals(0, deactivation.getAsJsonObject("activeLandUseSummary")
                .get("activePlanCount").getAsInt());
        JsonObject persisted = JsonParser.parseString(Files.readString(
                CityLandUseWorldgenRegistry.activePlansPath(serverRoot))).getAsJsonObject();
        assertTrue(persisted.getAsJsonArray("plans").isEmpty());
    }

    @Test
    void handleExecuteD5ActivatesPureSurfacePrintPlanWithoutCatalog() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-land-use-surface-activation-test");
        Path serverRoot = Files.createTempDirectory("city-land-use-surface-activation-server");
        String runId = "run_land_use_surface_activation";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        Path completionPath = writeEmptyLandUseArtifacts(debugRoot, runId, citySeedId);
        writeEmptyLandUseSurfaceArtifact(debugRoot, runId, citySeedId, completionPath);

        JsonObject activation = CityPlanningEndpointHandler.handleExecuteD5(
                debugRoot, serverRoot, runId, citySeedId, true, null,
                null, true);

        assertTrue(activation.get("landUseSurfacePrintMode").getAsBoolean());
        assertEquals(1, activation.getAsJsonObject("activeLandUseSummary")
                .get("surfacePrintPlanCount").getAsInt());
        assertTrue(activation.getAsJsonObject("artifacts").has("sourceLandUseSurfacePrintPlan"));
        JsonObject surface = JsonParser.parseString(Files.readString(
                completionPath.getParent().resolve("city_land_use_surface_print_plan.json"))).getAsJsonObject();
        assertEquals(CityLandUseSurfacePrintPlan.SCHEMA,
                surface.get("schema").getAsString());
    }

    @Test
    void handleExecuteD5RejectsPreviousSurfacePrintPlanBeforeActivation() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-land-use-old-surface-activation-test");
        Path serverRoot = Files.createTempDirectory("city-land-use-old-surface-activation-server");
        String runId = "run_land_use_old_surface_activation";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        Path completionPath = writeEmptyLandUseArtifacts(debugRoot, runId, citySeedId);
        writeEmptyLandUseSurfaceArtifact(debugRoot, runId, citySeedId, completionPath);
        Path surfacePath = completionPath.getParent().resolve("city_land_use_surface_print_plan.json");
        JsonObject oldSurface = JsonParser.parseString(Files.readString(surfacePath)).getAsJsonObject();
        oldSurface.addProperty("schema", "obsolete_city_land_use_surface_print_plan");
        Files.writeString(surfacePath, CityJson.GSON.toJson(oldSurface));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        debugRoot, serverRoot, runId, citySeedId, true, null,
                        null, true));

        assertTrue(failure.getMessage().contains("CITY_LAND_USE_SURFACE_PRINT_SCHEMA_UNSUPPORTED"),
                failure::getMessage);
        assertFalse(Files.exists(CityLandUseWorldgenRegistry.activePlansPath(serverRoot)));
    }

    @Test
    void handlePlanD6RequiresCurrentWorldTemplateMetadata() throws Exception {
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

        assertFalse(d6.get("ok").getAsBoolean());
        assertFalse(d6.getAsJsonObject("structureMaterializationPlan").get("locked").getAsBoolean());
        assertTrue(d6.getAsJsonObject("structureMaterializationPlan")
                .getAsJsonArray("plannedWorldgenStructures").isEmpty());
        assertTrue(d6.getAsJsonObject("structureMaterializationTrace")
                .getAsJsonArray("failures").toString().contains("TEMPLATE_RUNTIME_LEVEL_REQUIRED"));
    }

    @Test
    void handleExecuteD5_requiresExplicitConfirmation() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        Path.of("run/realm_debug"), Path.of("run"),
                        "run", "city", false, null));
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
                  "schema": "city_structure_anchor_map",
                  "cityId": "city_test",
                  "anchors": []
                }
                """);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        debugRoot, Files.createTempDirectory("city-d5-server-root"),
                        runId, citySeedId, true, null));
        assertTrue(ex.getMessage().contains("reservation_mask_plan.json"));
    }

    @Test
    void handleExecuteD5RejectsConfiguredD6RegistryPayload() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-d5-execute-no-level");
        Path serverRoot = Files.createTempDirectory("city-d5-server-root");
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
                  "schema": "city_structure_anchor_map",
                  "cityId": "city_test",
                  "anchors": [
                    {
                      "anchorId": "anchor_test",
                      "structureId": "geomantia:test_village",
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
                  "schema": "build_operation_plan",
                  "cityId": "city_test",
                  "templateDirectory": "geomantia_templates/d5",
                  "operations": []
                }
                """);
        Files.writeString(runDir.resolve("city_d5_city_test").resolve("reservation_mask_plan.json"), """
                {
                  "schema": "city_reservation_mask_plan",
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
                  "schema": "city_structure_materialization_plan",
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
                      "actualFootprint": {"minX": -4, "minZ": -4, "maxX": 4, "maxZ": 4},
                      "lockedActualFootprint": {"minX": -4, "minZ": -4, "maxX": 4, "maxZ": 4},
                      "lockedCollisionEnvelope": {"minX": -8, "minZ": -8, "maxX": 8, "maxZ": 8},
                      "lockedBBoxGroupKey": "anchor_test@0,0",
                      "pieceBoxes": [
                        {"pieceId": "anchor_test_start", "blockBounds": {"minX": -4, "minZ": -4, "maxX": 4, "maxZ": 4}}
                      ],
                      "maskEnvelope": {"minX": -12, "minZ": -12, "maxX": 12, "maxZ": 12},
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

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handleExecuteD5(
                        debugRoot, serverRoot, runId, citySeedId, true, null));
        assertTrue(failure.getMessage().contains("CITY_CONFIGURED_STRUCTURE_FLOW_REMOVED"));
        assertFalse(Files.exists(CityReservationMaskRegistry.plannedRegistryPath(serverRoot)));
    }

    @Test
    void handlePlanD5WritesFinalWallReservationArtifacts() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-wall-v5-d5-test");
        String runId = "run_wall_v5_d5";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);

        JsonObject response = CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId, 24, 4);

        JsonObject reservation = response.getAsJsonObject("wallReservationPlan");
        assertEquals("city_wall_reservation_plan", reservation.get("schema").getAsString());
        assertFalse(reservation.has("wallVersion"));
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
    void handlePlanD5RequiresPatchRescanWhenReservationTouchesD3Boundary() throws Exception {
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
                () -> CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId, 24, 4));

        assertTrue(ex.getMessage().contains("D5_REQUIRES_PATCH_RESCAN"));
    }

    @Test
    void handlePlanD5UsesPatchContextCoverageOutsideCoreGrid() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-wall-v5-padding-test");
        String runId = "run_wall_v5_padding";
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
        d3.add("patchContextBounds", JsonParser.parseString(
                "{\"minX\":-256,\"minZ\":-256,\"maxX\":256,\"maxZ\":256}"));
        Files.writeString(d3Path, CityJson.GSON.toJson(d3));

        JsonObject response = CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId, 24, 4);

        JsonObject coverage = response.getAsJsonObject("wallReservationPlan")
                .getAsJsonObject("coverageCheck");
        assertEquals("patch_context_bounds", coverage.get("coverageSource").getAsString());
        assertEquals(-16, coverage.getAsJsonObject("coreGridBounds").get("minX").getAsInt());
        assertEquals(256, coverage.getAsJsonObject("d3CoverageBounds").get("maxX").getAsInt());
    }

    @Test
    void handlePlanD5StillRequiresRescanOutsidePatchContextCoverage() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-wall-v5-padding-rescan-test");
        String runId = "run_wall_v5_padding_rescan";
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
        d3.add("patchContextBounds", JsonParser.parseString(
                "{\"minX\":-20,\"minZ\":-20,\"maxX\":20,\"maxZ\":20}"));
        Files.writeString(d3Path, CityJson.GSON.toJson(d3));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId, 24, 4));

        assertTrue(ex.getMessage().contains("D5_REQUIRES_PATCH_RESCAN"));
        assertTrue(ex.getMessage().contains("[-20,-20 -> 20,20]"));
    }

    @Test
    void handlePlanCityWallsKeepsD5WallLineAndBackfillsSurfaceCacheReport() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-wall-v5-plan-test");
        String runId = "run_wall_v5_plan";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId, 24, 4);
        Path reservationPath = debugRoot.resolve(runId).resolve("city_d5_" + citySeedId)
                .resolve("wall_reservation_plan.json");
        JsonObject reservation = JsonParser.parseString(Files.readString(reservationPath)).getAsJsonObject();

        Path d7Dir = debugRoot.resolve(runId).resolve("city_d7_" + citySeedId);
        Files.createDirectories(d7Dir);
        Files.writeString(d7Dir.resolve("placed_structure_ledger.json"), """
                {
                  "schema": "city_placed_structure_ledger",
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
                debugRoot, runId, citySeedId, null, 8, CityWallPlanner.Options.defaults());

        JsonObject wallPlan = response.getAsJsonObject("cityWallPlan");
        assertEquals("city_wall_plan", wallPlan.get("schema").getAsString());
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
    void handlePlanD6HardStopsWhenLockedFootprintExceedsD5Coverage() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-wall-v5-d6-stop-test");
        String runId = "run_wall_v5_d6_stop";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId, 24, 4);
        Path reservationPath = debugRoot.resolve(runId).resolve("city_d5_" + citySeedId)
                .resolve("wall_reservation_plan.json");
        JsonObject reservation = JsonParser.parseString(Files.readString(reservationPath)).getAsJsonObject();
        reservation.add("wallCoverageBounds", JsonParser.parseString("""
                {"minX": -4, "minZ": -4, "maxX": 4, "maxZ": 4}
                """).getAsJsonObject());
        Files.writeString(reservationPath, CityJson.GSON.toJson(reservation));
        JsonObject d6Plan = JsonParser.parseString(Files.readString(debugRoot.resolve(runId)
                .resolve("city_d6_" + citySeedId).resolve("structure_materialization_plan.json")))
                .getAsJsonObject();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CityPlanningEndpointHandler.validateFootprintsWithinWallReservation(
                        reservationPath, d6Plan, "plannedWorldgenStructures", "D6"));

        assertTrue(ex.getMessage().contains("D5_LOCKED_FOOTPRINT_OUTSIDE_RESERVATION"));
    }

    @Test
    void handleRunWorkflowDefaultsToBlueprintAndReturnsAwaitingContext() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-workflow-blueprint-awaiting-test");
        String runId = "run_workflow_blueprint_awaiting";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        JsonObject request = new JsonObject();
        request.addProperty("runId", runId);
        request.addProperty("citySeedId", citySeedId);
        request.addProperty("skipExisting", true);

        JsonObject response = CityPlanningEndpointHandler.handleRunWorkflow(
                debugRoot, Files.createTempDirectory("city-workflow-blueprint-awaiting-server-root"),
                runId, citySeedId, request, null, null);

        assertTrue(response.get("ok").getAsBoolean());
        assertEquals("awaiting_city_blueprint", response.get("status").getAsString());
        JsonObject report = response.getAsJsonObject("workflowReport");
        assertFalse(report.has("d4CandidateMode"));
        assertEquals("city_blueprint", report.get("landUseControlSource").getAsString());
        assertEquals("city_prepare_d4_blueprint_context", report.get("nextAction").getAsString());
        assertTrue(report.getAsJsonArray("steps").toString().contains("CITY_BLUEPRINT_CONTEXT_NOT_FOUND"));
    }

    @Test
    void handleRunWorkflowReturnsSubmitActionWhenContextExistsWithoutBlueprint() throws Exception {
        Path debugRoot = Files.createTempDirectory("city-workflow-blueprint-submit-test");
        String runId = "run_workflow_blueprint_submit";
        String citySeedId = "city_test";
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        Path blueprintDir = debugRoot.resolve(runId).resolve("city_blueprint_" + citySeedId);
        Files.createDirectories(blueprintDir);
        Files.writeString(blueprintDir.resolve("city_blueprint_context.json"), "{}");
        JsonObject request = new JsonObject();
        request.addProperty("runId", runId);
        request.addProperty("citySeedId", citySeedId);
        request.addProperty("skipExisting", true);

        JsonObject response = CityPlanningEndpointHandler.handleRunWorkflow(
                debugRoot, Files.createTempDirectory("city-workflow-blueprint-submit-server-root"),
                runId, citySeedId, request, null, null);

        assertTrue(response.get("ok").getAsBoolean());
        assertEquals("awaiting_city_blueprint", response.get("status").getAsString());
        assertEquals("city_submit_d4_blueprint", response.getAsJsonObject("workflowReport")
                .get("nextAction").getAsString());
    }

    @Test
    void workflowArtifactIdentityRequiresExactCurrentAnchorMap() throws Exception {
        Path directory = Files.createTempDirectory("city-workflow-artifact-identity-test");
        Path anchorMapPath = directory.resolve("structure_anchor_map.json");
        Path artifactPath = directory.resolve("reservation_mask_plan.json");
        JsonObject anchorMap = JsonParser.parseString("""
                {"schema":"structure_anchor_map","cityId":"city_test","anchors":[]}
                """).getAsJsonObject();
        JsonObject artifact = new JsonObject();
        artifact.add("sourceStructureAnchorMap", anchorMap.deepCopy());
        Files.writeString(anchorMapPath, anchorMap.toString());
        Files.writeString(artifactPath, artifact.toString());

        assertTrue(CityPlanningEndpointHandler.workflowArtifactMatchesAnchorMap(
                artifactPath, anchorMapPath));

        anchorMap.addProperty("cityId", "city_changed");
        Files.writeString(anchorMapPath, anchorMap.toString());
        assertFalse(CityPlanningEndpointHandler.workflowArtifactMatchesAnchorMap(
                artifactPath, anchorMapPath));

        Files.writeString(artifactPath, "not-json");
        assertFalse(CityPlanningEndpointHandler.workflowArtifactMatchesAnchorMap(
                artifactPath, anchorMapPath));
    }

    @Test
    void workflowD5SkipsOnlyWhenActivationMatchesAllCurrentInputs() throws Exception {
        Path directory = Files.createTempDirectory("city-workflow-d5-activation-identity-test");
        Path active = directory.resolve("active_planned_structure_registry.json");
        Path d5 = directory.resolve("reservation_mask_plan.json");
        Path d6 = directory.resolve("structure_materialization_plan.json");
        Path landUse = directory.resolve("city_land_use_planning_complete.json");
        Files.writeString(d5, "{\"plan\":\"d5\"}");
        Files.writeString(d6, "{\"plan\":\"d6\"}");
        Files.writeString(landUse, "{\"plan\":\"land_use\"}");

        Files.writeString(active, """
                {"activationProvenance":{
                  "schema":"city_d5_activation_provenance",
                  "sourceD5Hash":"%s",
                  "sourceD6Hash":"%s",
                  "sourceLandUseCompletionHash":"%s"
                }}
                """.formatted(sha256(Files.readString(d5)), sha256(Files.readString(d6)),
                sha256(Files.readString(landUse))));

        assertTrue(CityPlanningEndpointHandler.workflowD5ActivationCurrent(
                active, d5, d6, landUse));

        Files.writeString(d6, "{\"plan\":\"changed\"}");
        assertFalse(CityPlanningEndpointHandler.workflowD5ActivationCurrent(
                active, d5, d6, landUse));

        Files.writeString(d6, "{\"plan\":\"d6\"}");
        assertTrue(CityPlanningEndpointHandler.workflowD5ActivationCurrent(
                active, d5, d6, landUse));
    }

    @Test
    void workflowBlueprintD4SkipsOnlyForCurrentAcceptedSourceIdentity() throws Exception {
        Path directory = Files.createTempDirectory("city-workflow-blueprint-d4-identity-test");
        Path blueprintDir = directory.resolve("blueprint");
        Files.createDirectories(blueprintDir);
        Path anchorMapPath = directory.resolve("structure_anchor_map.json");
        String contextId = "sha256:context";
        String blueprint = "{\"schema\":\"city_blueprint\",\"cityId\":\"city_test\"}";
        String blueprintHash = sha256(blueprint);

        Files.writeString(blueprintDir.resolve("city_blueprint_context.json"), """
                {"schema":"city_blueprint_context","cityId":"city_test",
                 "contextId":"sha256:context"}
                """);
        Files.writeString(blueprintDir.resolve("city_blueprint_validation_report.json"), """
                {"valid":true,"contextId":"sha256:context"}
                """);
        Files.writeString(blueprintDir.resolve("city_blueprint_submission_trace.json"), """
                {"status":"accepted","contextId":"sha256:context",
                 "cityBlueprintHash":"%s"}
                """.formatted(blueprintHash));
        Files.writeString(blueprintDir.resolve("city_blueprint.json"), blueprint);
        Files.writeString(anchorMapPath, """
                {"schema":"structure_anchor_map","cityId":"city_test","anchors":[],
                 "cityBlueprintCompileProvenance":{"selectionMode":"programmatic_blueprint_compiler",
                 "contextId":"%s","sourceBlueprintHash":"%s"}}
                """.formatted(contextId, blueprintHash));

        assertTrue(CityPlanningEndpointHandler.workflowBlueprintAnchorMapCurrent(
                anchorMapPath, blueprintDir));

        Files.writeString(blueprintDir.resolve("city_blueprint.json"), blueprint + " ");
        assertFalse(CityPlanningEndpointHandler.workflowBlueprintAnchorMapCurrent(
                anchorMapPath, blueprintDir));

        Files.writeString(blueprintDir.resolve("city_blueprint.json"), blueprint);
        JsonObject staleAnchorMap = JsonParser.parseString(Files.readString(anchorMapPath)).getAsJsonObject();
        staleAnchorMap.getAsJsonObject("cityBlueprintCompileProvenance").remove("contextId");
        Files.writeString(anchorMapPath, staleAnchorMap.toString());
        assertFalse(CityPlanningEndpointHandler.workflowBlueprintAnchorMapCurrent(
                anchorMapPath, blueprintDir));
    }

    private static void assertPreviewHasPatchBackdrop(Path previewPath) throws Exception {
        BufferedImage image = ImageIO.read(previewPath.toFile());
        int patchPixels = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xff;
                int red = (argb >>> 16) & 0xff;
                int green = (argb >>> 8) & 0xff;
                int blue = argb & 0xff;
                if (alpha > 0 && green > red + 6 && green > blue + 12 && red > 150 && blue > 140) {
                    patchPixels++;
                }
            }
        }
        assertTrue(patchPixels > 500, "D7 placed preview should include D3 patch backdrop colors");
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
        Path templateCatalogPath = runDir.resolve("template_catalog.json");
        Files.writeString(templateCatalogPath, fixedTemplateCatalog());
        CityPlanningEndpointHandler.handlePlanD4(debugRoot, runId, citySeedId,
                terraSenseSource(catalogPath), templateCatalogSource(templateCatalogPath),
                structureAnchorPlan(review, 2));
        CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId);
        writeLockedD6Artifacts(debugRoot, runId, citySeedId);
    }

    private static void prepareAcceptedBlueprintD6(Path debugRoot,
                                                    String runId,
                                                    String citySeedId,
                                                    String outdoorMode) throws Exception {
        prepareD5Artifacts(debugRoot, runId, citySeedId);
        Path runDir = debugRoot.resolve(runId);
        addPatchMemberCells(debugRoot, runId, citySeedId, "plain");
        writeBlueprintTerrainField(runDir, citySeedId);
        JsonObject prepared = CityPlanningEndpointHandler.handlePrepareD4BlueprintContext(debugRoot, runId,
                citySeedId, terraSenseSource(runDir.resolve("debug_structure_profile_catalog.json")),
                templateCatalogSource(runDir.resolve("template_catalog.json")), blueprintReferenceCatalog());
        JsonObject blueprint = blueprintForContext(prepared.getAsJsonObject("cityBlueprintContext"));
        JsonObject outdoorPlan = blueprint.getAsJsonObject("outdoorPlan");
        outdoorPlan.addProperty("mode", outdoorMode);
        if ("PRESERVE".equals(outdoorMode)) {
            outdoorPlan.add("spatialGrounds", new JsonArray());
            outdoorPlan.add("landscapes", new JsonArray());
        }
        JsonObject submitted = CityPlanningEndpointHandler.handleSubmitD4Blueprint(debugRoot, runId, citySeedId,
                prepared.get("contextId").getAsString(), blueprint);
        if (!submitted.get("ok").getAsBoolean()) {
            throw new IllegalStateException("Blueprint fixture was rejected: " + submitted);
        }
        JsonObject compiled = CityPlanningEndpointHandler.handleCompileD4Blueprint(
                debugRoot, runId, citySeedId);
        if (!compiled.get("ok").getAsBoolean()) {
            throw new IllegalStateException("Blueprint fixture failed to compile: " + compiled);
        }
        CityPlanningEndpointHandler.handlePlanD5(debugRoot, runId, citySeedId);
        writeLockedD6Artifacts(debugRoot, runId, citySeedId);
    }

    private static void writeLockedD6Artifacts(Path debugRoot, String runId, String citySeedId) throws Exception {
        Path runDir = debugRoot.resolve(runId);
        JsonObject anchorMap = JsonParser.parseString(Files.readString(runDir
                .resolve("city_d4_" + citySeedId).resolve("structure_anchor_map.json"))).getAsJsonObject();
        CityStructureMaterializationPlanner.TemplateMetadataInspector metadataInspector = templateRef -> {
            for (JsonElement element : anchorMap.getAsJsonArray("anchors")) {
                JsonObject anchor = element.getAsJsonObject();
                if (!templateRef.equals(anchor.get("templateRef").getAsString())) {
                    continue;
                }
                JsonObject size = anchor.getAsJsonObject("rawSize");
                return CityStructureMaterializationPlanner.TemplateMetadata.readable(
                        anchor.get("templateHash").getAsString(),
                        new CityTemplatePlacementGeometry.Size(
                                size.get("width").getAsInt(),
                                size.get("height").getAsInt(),
                                size.get("depth").getAsInt()));
            }
            return CityStructureMaterializationPlanner.TemplateMetadata.unreadable(
                    "TEMPLATE_NOT_FOUND", templateRef);
        };
        CityStructureMaterializationPlanner.Result result = new CityStructureMaterializationPlanner()
                .planWorldgen(anchorMap, CityStructureMaterializationPlanner.ChunkStatusInspector.plannedOnly(),
                        null, metadataInspector);
        if (!result.structureMaterializationPlan().get("locked").getAsBoolean()) {
            throw new IllegalStateException("Test D6 fixture failed to lock: " + result.qualityReport());
        }
        Path d6Dir = runDir.resolve("city_d6_" + citySeedId);
        Files.createDirectories(d6Dir);
        Files.writeString(d6Dir.resolve("structure_materialization_plan.json"),
                CityJson.GSON.toJson(result.structureMaterializationPlan()));
        Files.writeString(d6Dir.resolve("placed_structure_ledger.json"),
                CityJson.GSON.toJson(result.placedStructureLedger()));
        Files.writeString(d6Dir.resolve("structure_materialization_trace.json"),
                CityJson.GSON.toJson(result.structureMaterializationTrace()));
        Files.writeString(d6Dir.resolve("inferred_function_area_map.json"),
                CityJson.GSON.toJson(result.inferredFunctionAreaMap()));
        Files.writeString(d6Dir.resolve("quality_report.json"), CityJson.GSON.toJson(result.qualityReport()));
    }

    private static LandUseTerrainField endpointTerrain() {
        List<LandUseTerrainField.Cell> cells = new java.util.ArrayList<>();
        int step = 4;
        for (int z = -128; z <= 188; z += step) {
            for (int x = -128; x <= 188; x += step) {
                cells.add(new LandUseTerrainField.Cell(Math.floorDiv(x, step), Math.floorDiv(z, step),
                        x, z, step, 70, 0, 0, 0, false, 0, 40,
                        "minecraft:plains", "plain", "plain", true));
            }
        }
        return new LandUseTerrainField(LandUseTerrainField.SCHEMA, "city_test",
                new BlockBounds(-128, -128, 191, 191), step, cells);
    }

    private static Path writeEmptyLandUseArtifacts(Path debugRoot, String runId, String citySeedId) throws Exception {
        Path directory = debugRoot.resolve(runId).resolve("city_land_use_" + citySeedId);
        Files.createDirectories(directory);
        LandUseAreaPlanCodec codec = new LandUseAreaPlanCodec();
        JsonObject unhashed = JsonParser.parseString("""
                {
                  "schema": "city_land_use_area_plan",
                  "ruleVersion": "city_land_use_rules",
                  "cityId": "city_test",
                  "planningBounds": {"minX": -64, "minZ": -64, "maxX": 64, "maxZ": 64},
                  "areas": [],
                  "sharedBoundarySpans": [],
                  "unclaimedSpans": [],
                  "corridorExclusions": [],
                  "warnings": []
                }
                """).getAsJsonObject();
        var plan = codec.withComputedHash(codec.fromJson(unhashed));
        Files.writeString(directory.resolve("city_land_use_area_plan.json"),
                CityJson.GSON.toJson(codec.toJson(plan)));
        JsonObject completion = new JsonObject();
        completion.addProperty("schema", "city_land_use_planning_complete");
        completion.addProperty("cityId", citySeedId);
        completion.addProperty("planHash", plan.planHash());
        completion.addProperty("ruleProfileHash", LandUseRuleCatalog.defaults().profileHash());
        Path d6Path = debugRoot.resolve(runId).resolve("city_d6_" + citySeedId)
                .resolve("structure_materialization_plan.json");
        JsonObject d6 = JsonParser.parseString(Files.readString(d6Path)).getAsJsonObject();
        completion.addProperty("sourceD6Hash",
                sha256(CityJson.GSON.toJson(d6)));
        completion.addProperty("completedAt", "2026-07-15T00:00:00Z");
        Path completionPath = directory.resolve("city_land_use_planning_complete.json");
        Files.writeString(completionPath, CityJson.GSON.toJson(completion));
        return completionPath;
    }

    private static void writeEmptyLandUseSurfaceArtifact(Path debugRoot,
                                                         String runId,
                                                         String citySeedId,
                                                         Path completionPath) throws Exception {
        Path directory = debugRoot.resolve(runId).resolve("city_land_use_" + citySeedId);
        LandUseAreaPlanCodec areaCodec = new LandUseAreaPlanCodec();
        var areaPlan = areaCodec.fromJson(JsonParser.parseString(Files.readString(
                directory.resolve("city_land_use_area_plan.json"))).getAsJsonObject());
        CityLandUseSurfacePrintPlanCodec surfaceCodec = new CityLandUseSurfacePrintPlanCodec();
        CityLandUseSurfacePrintPlan unhashed = new CityLandUseSurfacePrintPlan(
                CityLandUseSurfacePrintPlan.SCHEMA,
                citySeedId, areaPlan.planHash(), "", List.of());
        CityLandUseSurfacePrintPlan surfacePlan = unhashed.withPlanHash(surfaceCodec.computePlanHash(unhashed));
        Files.writeString(directory.resolve("city_land_use_surface_print_plan.json"),
                CityJson.GSON.toJson(surfaceCodec.toJson(surfacePlan)));
        JsonObject completion = JsonParser.parseString(Files.readString(completionPath)).getAsJsonObject();
        completion.addProperty("surfacePrintPlanHash", surfacePlan.planHash());
        Files.writeString(completionPath, CityJson.GSON.toJson(completion));
    }



    private record MarkerMutation(String field, String value, String reasonCode, String message) {
    }

    private static JsonObject terraSenseSource(Path catalogPath) {
        JsonObject source = new JsonObject();
        source.addProperty("schema", "terrasense_structure_profile_source");
        source.addProperty("sourceType", "debug_catalog");
        source.addProperty("catalogMode", "debug");
        source.addProperty("debugCatalogPath", catalogPath.toString());
        return source;
    }

    private static void writeBlueprintTerrainField(Path runDir, String citySeedId) throws Exception {
        JsonObject review = JsonParser.parseString(Files.readString(runDir.resolve("city_d3_" + citySeedId)
                .resolve("city_landform_review_package.json"))).getAsJsonObject();
        JsonObject grid = review.getAsJsonObject("grid");
        int step = grid.get("cellStepBlocks").getAsInt();
        int originX = grid.get("originBlockX").getAsInt();
        int originZ = grid.get("originBlockZ").getAsInt();
        int cellsX = grid.get("cellsX").getAsInt();
        int cellsZ = grid.get("cellsZ").getAsInt();
        List<LandUseTerrainField.Cell> cells = new java.util.ArrayList<>();
        for (int z = 0; z < cellsZ; z++) {
            for (int x = 0; x < cellsX; x++) {
                int blockX = originX + x * step;
                int blockZ = originZ + z * step;
                cells.add(new LandUseTerrainField.Cell(Math.floorDiv(blockX, step), Math.floorDiv(blockZ, step),
                        blockX, blockZ, step, 70, 0, 0, 0, false, 0, 40,
                        "minecraft:plains", "plain", "plain", true));
            }
        }
        LandUseTerrainField field = new LandUseTerrainField(LandUseTerrainField.SCHEMA,
                citySeedId, new BlockBounds(originX, originZ,
                originX + cellsX * step - 1, originZ + cellsZ * step - 1), step, cells);
        Path directory = runDir.resolve("city_land_use_" + citySeedId);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("land_use_terrain_field.json"),
                CityJson.GSON.toJson(new LandUseTerrainFieldCodec().toJson(field)));
    }

    private static String sha256(String value) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonObject templateCatalogSource(Path catalogPath) {
        JsonObject source = new JsonObject();
        source.addProperty("catalogPath", catalogPath.toString());
        return source;
    }

    private static JsonObject structureAnchorPlan(CityLandformReviewPackage review, int anchorCount) {
        List<LandformPatchSummary> patches = review.landformPatches();
        LandformPatchSummary first = patches.get(0);
        LandformPatchSummary second = patches.size() > 1 ? patches.get(1) : first;
        String secondAnchor = anchorCount > 1 ? """
                    {
                      "anchorId": "anchor_village",
                      "templateId": "geomantia:test_village",
                      "variant": "test_v1",
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
                  "schema": "city_structure_anchor_plan",
                  "cityId": "city_test",
                  "anchors": [
                    {
                      "anchorId": "anchor_pyramid",
                      "templateId": "geomantia:test_house",
                      "variant": "test_v1",
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
                  "schema": "city_d4_design_slot_plan",
                  "cityId": "city_test",
                  "placementOrder": ["admin_core", "residential_01"],
                  "slots": [
                    {
                      "slotId": "admin_core",
                      "displayRole": "行政核心",
                      "candidatePatchRefs": ["%s"],
                      "templateIds": ["geomantia:test_house"],
                      "variantId": "test_v1",
                      "relationHints": []
                    },
                    {
                      "slotId": "residential_01",
                      "displayRole": "住宅",
                      "candidatePatchRefs": ["%s"],
                      "templateIds": ["geomantia:test_house"],
                      "variantId": "test_v1",
                      "relationHints": [
                        {"targetSlotId": "admin_core", "distanceBand": "near"}
                      ]
                    }
                  ]
                }
                """.formatted(first.landformPatchId(), second.landformPatchId())).getAsJsonObject();
    }

    private static JsonObject stagedDesignSlotPlan(CityLandformReviewPackage review) {
        LandformPatchSummary first = review.landformPatches().get(0);
        return JsonParser.parseString("""
                {
                  "schema": "city_d4_design_slot_plan",
                  "cityId": "city_test",
                  "placementOrder": ["admin_core", "residential_array"],
                  "slots": [
                    {
                      "slotId": "admin_core",
                      "placementStrategy": "key_structure",
                      "displayRole": "行政核心",
                      "candidatePatchRefs": ["%s"],
                      "templateIds": ["geomantia:test_house"],
                      "variantId": "test_v1",
                      "relationHints": []
                    },
                    {
                      "slotId": "residential_array",
                      "placementStrategy": "array_fill",
                      "displayRole": "住宅阵列",
                      "candidatePatchRefs": ["%s"],
                      "templateIds": ["geomantia:test_house"],
                      "variantId": "test_v1",
                      "arrayCount": 4,
                      "variantSelectionMode": "round_robin",
                      "patterns": ["loose_cluster", "patch_axis_band", "scattered"]
                    }
                  ]
                }
                """.formatted(first.landformPatchId(), first.landformPatchId())).getAsJsonObject();
    }

    private static JsonObject arrayCandidatePlan(CityLandformReviewPackage review) {
        LandformPatchSummary first = review.landformPatches().get(0);
        return JsonParser.parseString("""
                {
                  "schema": "city_d4_array_candidate_plan",
                  "cityId": "city_test",
                  "arrayId": "residential_cluster",
                  "displayRole": "住宅阵列",
                  "candidatePatchRefs": ["%s"],
                  "templateIds": ["geomantia:test_house"],
                  "variantId": "test_v1",
                  "arrayCount": 4,
                  "patterns": ["loose_cluster", "patch_axis_band", "scattered"]
                }
                """.formatted(first.landformPatchId())).getAsJsonObject();
    }

    private static JsonObject arrayLayoutPlan(CityLandformReviewPackage review) {
        return JsonParser.parseString("""
                {
                  "schema": "city_d4_array_layout_plan",
                  "cityId": "city_test",
                  "cityScale": "town",
                  "maxArrayPlans": 4,
                  "layoutPlans": []
                }
                """).getAsJsonObject();
    }

    private static JsonObject arrayLayoutPlanV04() {
        return JsonParser.parseString("""
                {
                  "schema": "city_d4_array_layout_plan",
                  "planningMode": "array_candidate_selection_loop",
                  "cityId": "city_test",
                  "cityScale": "town",
                  "maxArrayPlans": 4,
                  "layoutPlans": []
                }
                """).getAsJsonObject();
    }

    private static JsonObject arrayExpansionRequest(CityLandformReviewPackage review) {
        LandformPatchSummary first = review.landformPatches().get(0);
        return JsonParser.parseString("""
                {
                  "focusRef": {"anchorId": "manor_core"},
                  "direction": "east",
                  "targetPatchRef": "%s",
                  "candidateCount": 3,
                  "nextArrayLayoutPlanItem": {
                    "arrayId": "east_residential_theme",
                    "plannerType": "compound_cluster",
                    "role": "residential",
                    "fillPool": [{"templateId": "geomantia:test_house", "variantId": "test_v1"}],
                    "countPolicy": {"minCount": 2, "targetCount": 2, "maxCount": 2},
                    "variantSelectionMode": "round_robin"
                  }
                }
                """.formatted(first.landformPatchId())).getAsJsonObject();
    }

    private static JsonObject arrayLayoutPlanWithItem(CityLandformReviewPackage review) {
        JsonObject plan = arrayLayoutPlan(review);
        JsonArray items = new JsonArray();
        items.add(arrayLayoutItem(review, "compound_cluster", "residential_cluster", 3));
        plan.add("layoutPlans", items);
        return plan;
    }

    private static JsonObject arrayLayoutItem(CityLandformReviewPackage review, String plannerType,
                                              String arrayId, int targetCount) {
        LandformPatchSummary first = review.landformPatches().get(0);
        return JsonParser.parseString("""
                {
                  "arrayId": "%s",
                  "plannerType": "%s",
                  "role": "residential",
                  "candidatePatchRefs": ["%s"],
                  "startSector": "southeast",
                  "fillPool": [
                    {"templateId": "geomantia:test_house", "variantId": "test_v1"}
                  ],
                  "countPolicy": {"minCount": %d, "targetCount": %d, "maxCount": %d},
                  "variantSelectionMode": "round_robin"
                }
                """.formatted(arrayId, plannerType, first.landformPatchId(),
                targetCount, targetCount, targetCount)).getAsJsonObject();
    }


    private static List<String> relativeFiles(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }







    private static void addPatchMemberCells(Path debugRoot, String runId, String citySeedId,
                                            String patchRef) throws Exception {
        Path path = debugRoot.resolve(runId).resolve("city_d3_" + citySeedId)
                .resolve("city_landform_review_package.json");
        JsonObject d3 = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        JsonObject grid = d3.getAsJsonObject("grid");
        int step = grid.get("cellStepBlocks").getAsInt();
        for (JsonElement element : d3.getAsJsonArray("landformPatches")) {
            JsonObject patch = element.getAsJsonObject();
            if (!patchRef.equals(patch.get("landformPatchId").getAsString())) {
                continue;
            }
            JsonObject patchBounds = patch.getAsJsonObject("blockBounds");
            JsonArray cells = new JsonArray();
            for (int z = patchBounds.get("minZ").getAsInt();
                 z + step - 1 <= patchBounds.get("maxZ").getAsInt(); z += step) {
                for (int x = patchBounds.get("minX").getAsInt();
                     x + step - 1 <= patchBounds.get("maxX").getAsInt(); x += step) {
                    JsonObject cell = new JsonObject();
                    cell.addProperty("cellX", Math.floorDiv(x, step));
                    cell.addProperty("cellZ", Math.floorDiv(z, step));
                    cell.addProperty("blockMinX", x);
                    cell.addProperty("blockMinZ", z);
                    cells.add(cell);
                }
            }
            patch.addProperty("geometryMode", "patch_member_cells");
            patch.add("memberCells", cells);
            Files.writeString(path, CityJson.GSON.toJson(d3));
            return;
        }
        throw new IllegalArgumentException("test patch not found: " + patchRef);
    }

    private static void ensureTwoPlannedRoadEndpoints(Path debugRoot, String runId, String citySeedId)
            throws Exception {
        Path path = debugRoot.resolve(runId).resolve("city_d6_" + citySeedId)
                .resolve("structure_materialization_plan.json");
        JsonObject plan = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        JsonArray structures = plan.getAsJsonArray("plannedWorldgenStructures");
        assertFalse(structures.isEmpty());
        for (int index = 0; index < structures.size(); index++) {
            JsonObject structure = structures.get(index).getAsJsonObject();
            structure.addProperty("status", "planned_worldgen");
            addTestTemplateRoadEntrance(structure, index * 36, index * 36);
        }
        if (structures.size() == 1) {
            JsonObject copy = structures.get(0).getAsJsonObject().deepCopy();
            copy.addProperty("anchorId", "test_road_endpoint_2");
            copy.addProperty("priority", 999);
            addTestTemplateRoadEntrance(copy, 36, 36);
            JsonObject actual = new JsonObject();
            actual.addProperty("minX", 36);
            actual.addProperty("minZ", 36);
            actual.addProperty("maxX", 42);
            actual.addProperty("maxZ", 42);
            JsonObject collision = new JsonObject();
            collision.addProperty("minX", 32);
            collision.addProperty("minZ", 32);
            collision.addProperty("maxX", 46);
            collision.addProperty("maxZ", 46);
            copy.add("actualFootprint", actual.deepCopy());
            copy.add("lockedActualFootprint", actual);
            copy.add("collisionEnvelope", collision.deepCopy());
            copy.add("lockedCollisionEnvelope", collision);
            structures.add(copy);
        }
        Files.writeString(path, CityJson.GSON.toJson(plan));
    }

    private static void addTestTemplateRoadEntrance(JsonObject structure, int anchorX, int anchorZ) {
        JsonObject anchor = new JsonObject();
        anchor.addProperty("x", anchorX);
        anchor.addProperty("z", anchorZ);
        JsonObject relativePosition = new JsonObject();
        relativePosition.addProperty("x", 0);
        relativePosition.addProperty("z", 0);
        JsonObject entrance = new JsonObject();
        entrance.addProperty("entranceId", "front");
        entrance.add("relativePosition", relativePosition);
        entrance.addProperty("direction", "NORTH");
        JsonArray entrances = new JsonArray();
        entrances.add(entrance);
        JsonObject transformed = new JsonObject();
        transformed.add("roadEntrances", entrances);
        JsonObject placement = new JsonObject();
        placement.addProperty("templateId", "geomantia:test_road_endpoint");
        placement.addProperty("templateHash", "sha256:test-road-endpoint");
        placement.add("anchorBlock", anchor);
        placement.add("transformed", transformed);
        structure.add("templatePlacementPlan", placement);
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(
                obj.get("minX").getAsInt(),
                obj.get("minZ").getAsInt(),
                obj.get("maxX").getAsInt(),
                obj.get("maxZ").getAsInt());
    }

    private static void recordPlannedWorldgenPlacementsFromRegistry() {
        JsonObject plannedRegistry = CityReservationMaskRegistry.plannedRegistrySummary();
        for (JsonElement elem : plannedRegistry.getAsJsonArray("plannedStructures")) {
            JsonObject plannedJson = elem.getAsJsonObject();
            JsonObject chunkJson = plannedJson.getAsJsonObject("anchorChunk");
            ChunkPos chunk = new ChunkPos(chunkJson.get("x").getAsInt(), chunkJson.get("z").getAsInt());
            CityReservationMaskRegistry.PlannedStructure planned = CityReservationMaskRegistry
                    .plannedStructuresForChunk(chunk)
                    .stream()
                    .filter(candidate -> candidate.anchorId().equals(plannedJson.get("anchorId").getAsString()))
                    .findFirst()
                    .orElseThrow();
            JsonObject actualJson = plannedJson.getAsJsonObject("lockedActualFootprint");
            BlockBounds actual = new BlockBounds(
                    actualJson.get("minX").getAsInt(),
                    actualJson.get("minZ").getAsInt(),
                    actualJson.get("maxX").getAsInt(),
                    actualJson.get("maxZ").getAsInt());
            assertEquals(CityReservationMaskRegistry.TemplateDatumPreparationStatus.READY,
                    CityReservationMaskRegistry.prepareTemplateTerrainStart(planned, 80).status());
            for (int ownerX = Math.floorDiv(actual.minX(), 16);
                 ownerX <= Math.floorDiv(actual.maxX(), 16); ownerX++) {
                for (int ownerZ = Math.floorDiv(actual.minZ(), 16);
                     ownerZ <= Math.floorDiv(actual.maxZ(), 16); ownerZ++) {
                    ChunkPos owner = new ChunkPos(ownerX, ownerZ);
                    CityReservationMaskRegistry.TemplateFragmentRecordResult recorded =
                            CityReservationMaskRegistry.recordTemplateWorldgenFragment(
                            planned, actual, owner, 80, "beard_thin",
                            "TEMPLATE_TERRAIN_START_PIECE_PLACED", "test placement");
                    assertTrue(recorded.recorded(), recorded.reasonCode());
                }
            }
        }
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
                      "reviewState": "approved",
                      "functionTerms": ["function.村庄"],
                      "planningRoleTerms": ["planning_role.fill"],
                      "terrainModes": ["SURFACE"],
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

    private static String fixedTemplateCatalog() {
        return """
                {
                  "schema": "city_template_catalog",
                  "templates": [
                    {
                      "buildingSemantic": "house",
                      "style": "debug",
                      "templateId": "geomantia:test_house",
                      "templateRef": "geomantia:city/test_house",
                      "contentHash": "sha256:test-house",
                      "variant": "test_v1",
                      "rawSize": {"width": 12, "height": 10, "depth": 12},
                      "allowedRotations": ["NONE", "CLOCKWISE_90", "CLOCKWISE_180", "COUNTERCLOCKWISE_90"],
                      "allowedMirrors": ["NONE"],
                      "roadEntrances": [{"entranceId":"front","x":6,"z":11,"direction":"SOUTH"}],
                      "terrainPosePolicy": "structure_start_beard_thin",
                      "supportPolicy": "none",
                      "clearanceBlocks": 2
                    },
                    {
                      "buildingSemantic": "village",
                      "style": "debug",
                      "templateId": "geomantia:test_village",
                      "templateRef": "geomantia:city/test_village",
                      "contentHash": "sha256:test-village",
                      "variant": "test_v1",
                      "rawSize": {"width": 10, "height": 8, "depth": 10},
                      "allowedRotations": ["NONE", "CLOCKWISE_90"],
                      "allowedMirrors": ["NONE"],
                      "roadEntrances": [{"entranceId":"front","x":5,"z":9,"direction":"SOUTH"}],
                      "terrainPosePolicy": "structure_start_beard_thin",
                      "supportPolicy": "none",
                      "clearanceBlocks": 0
                    }
                  ]
                }
                """;
    }

    private static JsonObject blueprintReferenceCatalog() {
        return JsonParser.parseString("""
                {
                  "schema":"city_blueprint_reference_catalog",
                  "structureRefs":[{"structureRef":"minecraft:desert_pyramid","templateCandidates":[{"templateId":"geomantia:test_house","variantId":"test_v1"}]}],
                  "fillPools":[{"poolRef":"pool:test","structureRefs":["minecraft:desert_pyramid"]}],
                  "algorithmProfiles":[{"algorithmProfileRef":"algorithm:grid","algorithm":"GRID"}],
                  "compositionProfiles":[{"compositionProfileRef":"composition:round_robin","mode":"ROUND_ROBIN"}],
                  "styleProfiles":[{"profileRef":"style:test"}],
                  "roadProfiles":[{"profileRef":"road:test","hierarchy":"SIMPLE","density":"BALANCED"}],
                  "surfaceDetailProfiles":[{"profileRef":"surface:test","intensity":"MEDIUM"}],
                  "landUseRuleProfile":{"schema":"city_land_use_rules","profileId":"blueprint_test","rules":[{
                    "ruleRef":"civic","landUseType":"civic","semanticTerms":["landmark"],
                    "footprintMultiplier":1.5,"extraAreaBlocks":80,"minAreaBlocks":80,"maxAreaBlocks":1536,
                    "actionBudget":300,"baseStepCost":1.0,"slopeCost":1.2,"reliefCost":1.2,"waterCost":8.0,
                    "forestAffinity":0.0,"competitionWeight":1.0,"mergeSameType":true,
                    "surfacePolicy":"PAVE","vegetationPolicy":"CLEAR","boundaryPolicy":"OPEN"
                  }]},
                  "surfaceRecipes":[{"surfaceRecipeRef":"surface_recipe:civic","surfacePrintEnabled":true,
                    "autoConnectDefault":true,"surfaceAlgorithm":"UNIFORM","surfaceBlockId":"minecraft:stone_bricks"}],
                  "foundationProfiles":[{"foundationProfileRef":"foundation:urban","landUseRuleRef":"civic",
                    "surfaceRecipeRef":"surface_recipe:civic","structureMarginBlocks":2,
                    "closeRadiusBlocks":8,"maxJoinDistanceBlocks":24}],
                  "landscapeProfiles":[{"landscapeProfileRef":"landscape:common_green","landscapeType":"COMMON_GREEN",
                    "landUseRuleRef":"civic","surfaceRecipeRef":"surface_recipe:civic","baseAreaSmall":256,
                    "baseAreaMedium":512,"baseAreaLarge":1024,"membership":"URBAN",
                    "parcelStyle":{"parcelCountMin":1,"parcelCountMax":6,
                    "parcelAreaMinBlocks":64,"parcelAreaMaxBlocks":512,
                    "minSharedBoundaryBlocks":3}}],
                  "landscapeFillProfiles":[{"fillProfileRef":"fill:relay_common_green","displayName":"接力城市绿地",
                    "visualIntent":"绿植区和自然地面区从父区域局部边界接力","algorithm":"SINGLE_SOURCE_REGION_RELAY",
                    "relayOrigin":"PARENT_REGION_LOCAL_BOUNDARY",
                    "compatibleLandscapeTypes":["COMMON_GREEN"],"primaryRoleRef":"GREEN",
                    "roles":[{"roleRef":"GREEN","materialRole":"PRIMARY_CONTENT","allowedGrowthForms":["PATCH"],"defaultGrowthForm":"PATCH","minShare":0.7,"maxShare":0.95,"defaultShare":0.85},
                      {"roleRef":"GROUND","materialRole":"GROUND","allowedGrowthForms":["PATCH","CORRIDOR"],"defaultGrowthForm":"PATCH","minShare":0.05,"maxShare":0.3,"defaultShare":0.15}],
                    "allowedContentRefs":["plant:grass"],
                    "examples":[{"exampleId":"simple_green","description":"绿植为主的连续城市绿地",
                      "roleShares":[{"roleRef":"GREEN","growthForm":"PATCH","targetShare":0.425},{"roleRef":"GROUND","growthForm":"PATCH","targetShare":0.15},{"roleRef":"GREEN","growthForm":"PATCH","targetShare":0.425}],
                      "contentWeights":[{"contentRef":"plant:grass","weight":1}]}]}]
                }
                """).getAsJsonObject();
    }

    private static JsonObject blueprintForContext(JsonObject context) {
        String patchRef = context.getAsJsonObject("d3ReviewPackage").getAsJsonArray("landformPatches")
                .get(0).getAsJsonObject().get("landformPatchId").getAsString();
        JsonObject blueprint = JsonParser.parseString("""
                {
                  "schema":"city_blueprint","cityId":"city_test","generationSeed":42,
                  "designIntent":{"cityIdentity":"test city","theme":"test","functionalRoles":["landmark"]},
                  "styleProfile":{"profileRef":"style:test"},
                  "groups":[{
                    "groupId":"core","groupKind":"STRUCTURE","preferredPatchRefs":["PATCH_REF"],
                    "preferredPatchZone":"CENTER",
                    "role":"landmark","priority":"CORE","extentClass":"SMALL","densityClass":"BALANCED",
                    "targetAreaShare":1.0,
                    "spaceComposition":{"buildingShare":0.6,"landscapeShare":0.2,"openSpaceShare":0.2},
                    "expansionPolicy":{"allowOutwardExpansion":true,"allowRelationConnection":true,"stopWhenTargetReached":true},
                    "algorithmProfileRef":"algorithm:grid","terrainPolicy":"BALANCED",
                    "requiredStructureRefs":["minecraft:desert_pyramid"],"fillPoolRef":"pool:test",
                    "compositionProfileRef":"composition:round_robin","attachedFeatures":[],
                    "buildingGreeneryPolicy":{"coverage":"BALANCED","patternPreference":"MIXED","densityPreference":"MEDIUM"}
                  }],
                  "arrayCompositions":[],
                  "relations":[],"roadProfile":{"profileRef":"road:test"},
                  "surfaceDetailProfile":{"profileRef":"surface:test"},
                  "outdoorPlan":{"mode":"GENERATE","envelopeProfile":"BALANCED",
                    "foundationProfileRef":"foundation:urban",
                    "spatialGrounds":[{"sourceGroupId":"core","sharedSpaceType":"CIVIC_SQUARE",
                    "hierarchyLevel":"PRIMARY","membership":"URBAN"}],"landscapes":[]}
                }
                """.replace("PATCH_REF", patchRef)).getAsJsonObject();
        blueprint.add("sourceD3Ref", context.getAsJsonObject("sourceD3Ref").deepCopy());
        blueprint.add("catalogSnapshotRef", context.getAsJsonObject("catalogSnapshotRef").deepCopy());
        return blueprint;
    }

}
