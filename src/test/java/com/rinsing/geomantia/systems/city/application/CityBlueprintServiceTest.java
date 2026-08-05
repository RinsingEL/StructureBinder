package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityBlueprintServiceTest {
    @TempDir
    Path temporary;

    @Test
    void prepareDoesNotCountAsAiCallAndOneValidSubmissionIsFrozen() throws Exception {
        Fixture fixture = fixture("run_valid", "city:test");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());

        assertEquals(0, prepared.get("aiCityDesignCallCount").getAsInt());
        JsonObject context = prepared.getAsJsonObject("cityBlueprintContext");
        assertEquals("city_blueprint_catalog_snapshot.v0.5",
                context.getAsJsonObject("catalogSnapshotRef").get("schemaVersion").getAsString());
        assertEquals("city_blueprint_catalog_snapshot.v0.5",
                context.getAsJsonObject("catalogSnapshot").get("schemaVersion").getAsString());
        JsonObject semanticProfile = context.getAsJsonObject("catalogSnapshot")
                .getAsJsonObject("structureCatalog")
                .getAsJsonArray("semanticProfiles").get(0).getAsJsonObject();
        assertEquals("city_semantic_profile_catalog.v0.4",
                context.getAsJsonObject("catalogSnapshot").getAsJsonObject("structureCatalog")
                        .get("schemaVersion").getAsString());
        assertEquals("approved", semanticProfile.get("reviewState").getAsString());
        assertTrue(semanticProfile.has("functionTerms"));
        assertTrue(semanticProfile.has("planningRoleTerms"));
        assertTrue(semanticProfile.has("terrainModes"));
        assertEquals("SURFACE", semanticProfile.getAsJsonArray("terrainModes").get(0).getAsString());
        assertTrue(semanticProfile.has("styleTerms"));
        assertFalse(semanticProfile.has("semanticTerms"));
        assertFalse(semanticProfile.has("qualityTerms"));
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"));
        JsonObject submitted = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint);
        assertTrue(submitted.get("ok").getAsBoolean());
        assertEquals(1, submitted.get("aiCityDesignSubmissionCount").getAsInt());

        Path blueprintPath = fixture.runDir().resolve("city_blueprint_city_test/city_blueprint.json");
        Path reportPath = fixture.runDir().resolve(
                "city_blueprint_city_test/city_blueprint_validation_report.json");
        Path tracePath = fixture.runDir().resolve(
                "city_blueprint_city_test/city_blueprint_submission_trace.json");
        String accepted = Files.readString(blueprintPath);
        String acceptedReport = Files.readString(reportPath);
        String acceptedTrace = Files.readString(tracePath);
        JsonObject second = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint);
        assertFalse(second.get("ok").getAsBoolean());
        assertEquals(1, second.get("aiCityDesignSubmissionCount").getAsInt());
        assertEquals(accepted, Files.readString(blueprintPath), "a rejected retry must not overwrite the valid Blueprint");
        assertEquals(acceptedReport, Files.readString(reportPath),
                "a rejected retry must not overwrite the accepted validation report");
        assertEquals(acceptedTrace, Files.readString(tracePath),
                "a rejected retry must not overwrite the accepted submission trace");
    }

    @Test
    void concurrentSubmissionsAtomicallyConsumeOneBudget() throws Exception {
        Fixture fixture = fixture("run_concurrent", "city:concurrent");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        String contextId = prepared.get("contextId").getAsString();
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"));
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                start.await();
                return service.submit(temporary, fixture.runId(), fixture.cityId(), contextId,
                        blueprint.deepCopy());
            });
            var second = executor.submit(() -> {
                start.await();
                return service.submit(temporary, fixture.runId(), fixture.cityId(), contextId,
                        blueprint.deepCopy());
            });
            start.countDown();
            List<JsonObject> results = List.of(first.get(), second.get());
            assertEquals(1, results.stream().filter(result -> result.get("ok").getAsBoolean()).count());
            assertEquals(1, results.stream().filter(result -> !result.get("ok").getAsBoolean()
                    && "CITY_BLUEPRINT_AI_SUBMISSION_ALREADY_CONSUMED".equals(
                    result.getAsJsonObject("validationReport").getAsJsonArray("issues")
                            .get(0).getAsJsonObject().get("reasonCode").getAsString())).count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invalidLandscapeSubmissionWritesReportButNoBlueprint() throws Exception {
        Fixture fixture = fixture("run_landscape", "city:landscape");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"));
        blueprint.getAsJsonArray("groups").get(0).getAsJsonObject().addProperty("groupKind", "LANDSCAPE");

        JsonObject result = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint);
        assertFalse(result.get("ok").getAsBoolean());
        assertEquals("CITY_BLUEPRINT_GROUP_KIND_UNSUPPORTED",
                result.getAsJsonObject("validationReport").getAsJsonArray("issues")
                        .get(0).getAsJsonObject().get("reasonCode").getAsString());
        assertFalse(Files.exists(fixture.runDir().resolve("city_blueprint_city_landscape/city_blueprint.json")));
    }

    @Test
    void changedD3ArtifactMakesPreparedContextStale() throws Exception {
        Fixture fixture = fixture("run_stale", "city:stale");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        Files.writeString(fixture.d3Path(), Files.readString(fixture.d3Path()) + "\n");

        JsonObject result = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint(prepared.getAsJsonObject("cityBlueprintContext")));
        assertFalse(result.get("ok").getAsBoolean());
        assertEquals("CITY_BLUEPRINT_CONTEXT_STALE",
                result.getAsJsonObject("validationReport").getAsJsonArray("issues")
                        .get(0).getAsJsonObject().get("reasonCode").getAsString());
    }

    @Test
    void distanceRelationCarriesNearOrFarIntentForCompiler() throws Exception {
        Fixture fixture = fixture("run_distance", "city:distance");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"));
        JsonObject secondGroup = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject().deepCopy();
        secondGroup.addProperty("groupId", "market");
        secondGroup.add("preferredPatchRefs", JsonParser.parseString("[\"patch:plain:2\"]"));
        secondGroup.addProperty("priority", "STANDARD");
        blueprint.getAsJsonArray("groups").add(secondGroup);
        blueprint.getAsJsonArray("relations").add(JsonParser.parseString("""
                {
                  "fromGroupId":"civic","toGroupId":"market","relationKind":"DISTANCE",
                  "strength":"SOFT","distancePreference":"NEAR","directionPreference":"NONE"
                }
                """).getAsJsonObject());

        JsonObject submitted = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint);
        assertTrue(submitted.get("ok").getAsBoolean());
    }

    @Test
    void groupsMaySharePreferredPatch() throws Exception {
        Fixture fixture = fixture("run_shared_patch", "city:shared_patch");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"));
        JsonObject second = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject().deepCopy();
        second.addProperty("groupId", "market");
        second.addProperty("priority", "STANDARD");
        blueprint.getAsJsonArray("groups").add(second);

        JsonObject submitted = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint);
        assertTrue(submitted.get("ok").getAsBoolean(), submitted.toString());
    }

    @Test
    void connectionPlanRejectsParametersFromTheWrongPlannerFamily() throws Exception {
        Fixture fixture = fixture("run_bad_connection_parameters", "city:bad_connection_parameters");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"));
        JsonObject connectionPlan = JsonParser.parseString("""
                {"algorithmProfileRef":"algorithm:street_band","parameters":{"clusterShape":"GRID"}}
                """).getAsJsonObject();
        blueprint.getAsJsonArray("groups").get(0).getAsJsonObject()
                .add("connectionPlan", connectionPlan);

        JsonObject submitted = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint);
        assertFalse(submitted.get("ok").getAsBoolean());
        assertEquals("CITY_BLUEPRINT_CONNECTION_PARAMETERS_INVALID",
                submitted.getAsJsonObject("validationReport").getAsJsonArray("issues")
                        .get(0).getAsJsonObject().get("reasonCode").getAsString());
    }

    private Fixture fixture(String runId, String cityId) throws Exception {
        Path runDir = temporary.resolve(runId);
        Files.createDirectories(runDir.resolve("city_d3_" + safe(cityId)));
        JsonObject registry = new JsonObject();
        JsonArray seeds = new JsonArray();
        JsonObject seed = new JsonObject();
        seed.addProperty("citySeedId", cityId);
        seed.addProperty("realmId", "realm:test");
        seed.addProperty("role", "town");
        seed.addProperty("theoreticalScale", "town");
        seeds.add(seed);
        registry.add("citySeeds", seeds);
        Files.writeString(runDir.resolve("city_seed_registry.json"), registry.toString());
        JsonObject d3 = new JsonObject();
        d3.addProperty("schemaVersion", "city_landform_review.v0.1");
        d3.addProperty("cityId", cityId);
        d3.add("grid", JsonParser.parseString("""
                {"originBlockX":0,"originBlockZ":0,"cellStepBlocks":16,"cellsX":1,"cellsZ":1}
                """).getAsJsonObject());
        JsonArray patches = new JsonArray();
        JsonObject patch = new JsonObject();
        patch.addProperty("landformPatchId", "patch:plain:1");
        patches.add(patch);
        JsonObject secondPatch = new JsonObject();
        secondPatch.addProperty("landformPatchId", "patch:plain:2");
        patches.add(secondPatch);
        d3.add("landformPatches", patches);
        Path d3Path = runDir.resolve("city_d3_" + safe(cityId) + "/city_landform_review_package.json");
        Files.writeString(d3Path, d3.toString());
        Path terrainDirectory = runDir.resolve("city_land_use_" + safe(cityId));
        Files.createDirectories(terrainDirectory);
        Files.writeString(terrainDirectory.resolve("land_use_terrain_field.json"), """
                {
                  "schemaVersion":"city_land_use_terrain_field.v0.1","cityId":"%s",
                  "planningBounds":{"minX":0,"minZ":0,"maxX":15,"maxZ":15},"cellStepBlocks":16,
                  "cells":[{"cellX":0,"cellZ":0,"blockMinX":0,"blockMinZ":0,"cellStepBlocks":16,
                    "elevation":64,"slope":0.2,"localRelief":1,"roughness":0.1,"water":false,
                    "waterDepth":0,"waterDistance":100,"biomeId":"minecraft:plains","landformType":"plain",
                    "landformPatchId":"patch:plain:1","sampled":true}]
                }
                """.formatted(cityId));
        Path structureCatalog = runDir.resolve("structure_debug_catalog.json");
        Files.writeString(structureCatalog, """
                {"catalogMode":"debug","structures":[{
                  "semanticProfileId":"geomantia:town_hall",
                  "reviewState":"approved",
                  "functionTerms":["administration"],
                  "planningRoleTerms":["planning_role.key"],
                  "terrainModes":["surface"],
                  "styleTerms":["style.wood_stone"]
                }]}
                """);
        JsonObject terraSource = new JsonObject();
        terraSource.addProperty("sourceType", "debug_catalog");
        terraSource.addProperty("schemaVersion", "terrasense_structure_profile_source.v0.1");
        terraSource.addProperty("catalogMode", "debug");
        terraSource.addProperty("debugCatalogPath", "structure_debug_catalog.json");
        JsonObject templateSource = new JsonObject();
        templateSource.add("catalog", JsonParser.parseString("""
                {
                  "schemaVersion":"city_template_catalog.v0.1",
                  "templates":[{
                    "buildingSemantic":"administration","style":"stone",
                    "templateId":"geomantia:town_hall","templateRef":"geomantia:town_hall",
                    "contentHash":"sha256:fixture","variant":"default",
                    "rawSize":{"width":9,"height":8,"depth":9},
                    "allowedRotations":["NONE"],"allowedMirrors":["NONE"],"roadEntrances":[],
                    "terrainPosePolicy":"flat_or_small_step","supportPolicy":"full_footprint_support",
                    "clearanceBlocks":1
                  }]
                }
                """).getAsJsonObject());
        return new Fixture(runId, cityId, runDir, d3Path, terraSource, templateSource, referenceCatalog());
    }

    private static JsonObject referenceCatalog() {
        return JsonParser.parseString("""
                {
                  "schemaVersion":"city_blueprint_reference_catalog.v0.2",
                  "structureRefs":[{"structureRef":"geomantia:town_hall","templateCandidates":[{"templateId":"geomantia:town_hall","variantId":"default"}]}],
                  "fillPools":[{"poolRef":"pool:civic","structureRefs":["geomantia:town_hall"]}],
                  "algorithmProfiles":[
                    {"algorithmProfileRef":"algorithm:compact","algorithm":"COMPACT"},
                    {"algorithmProfileRef":"algorithm:street_band","algorithm":"LINEAR"}
                  ],
                  "compositionProfiles":[{"compositionProfileRef":"composition:round_robin","mode":"ROUND_ROBIN"}],
                  "styleProfiles":[{"profileRef":"style:river_stone"}],
                  "roadProfiles":[{"profileRef":"road:town","hierarchy":"HIERARCHICAL","density":"BALANCED"}],
                  "surfaceDetailProfiles":[{"profileRef":"surface:working","intensity":"MEDIUM"}]
                }
                """).getAsJsonObject();
    }

    private static JsonObject blueprint(JsonObject context) {
        JsonObject blueprint = new JsonObject();
        blueprint.addProperty("schemaVersion", "city_blueprint.v0.4");
        blueprint.addProperty("cityId", context.get("cityId").getAsString());
        blueprint.add("sourceD3Ref", context.getAsJsonObject("sourceD3Ref").deepCopy());
        blueprint.add("catalogSnapshotRef", context.getAsJsonObject("catalogSnapshotRef").deepCopy());
        blueprint.addProperty("generationSeed", context.get("generationSeedSuggestion").getAsLong());
        blueprint.add("designIntent", JsonParser.parseString("""
                {"cityIdentity":"river town","theme":"working waterfront","functionalRoles":["administration"]}
                """).getAsJsonObject());
        blueprint.add("styleProfile", JsonParser.parseString("{" +
                "\"profileRef\":\"style:river_stone\"}").getAsJsonObject());
        JsonArray groups = new JsonArray();
        groups.add(JsonParser.parseString("""
                {
                  "groupId":"civic","groupKind":"STRUCTURE","preferredPatchRefs":["patch:plain:1"],
                  "preferredPatchZone":"CENTER",
                  "role":"administration","priority":"CORE","extentClass":"MEDIUM","densityClass":"BALANCED",
                  "algorithmProfileRef":"algorithm:compact","terrainPolicy":"BALANCED",
                  "requiredStructureRefs":["geomantia:town_hall"],"fillPoolRef":"pool:civic",
                  "compositionProfileRef":"composition:round_robin","attachedFeatures":[]
                }
                """).getAsJsonObject());
        blueprint.add("groups", groups);
        blueprint.add("relations", new JsonArray());
        blueprint.add("roadProfile", JsonParser.parseString("{\"profileRef\":\"road:town\"}").getAsJsonObject());
        blueprint.add("surfaceDetailProfile", JsonParser.parseString("{\"profileRef\":\"surface:working\"}").getAsJsonObject());
        return blueprint;
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private record Fixture(String runId, String cityId, Path runDir, Path d3Path,
                           JsonObject terraSenseSource, JsonObject templateSource,
                           JsonObject referenceCatalog) {
    }
}
