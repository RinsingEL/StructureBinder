package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprintContractException;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprintReasonCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals("city_blueprint_context.v0.10", context.get("schemaVersion").getAsString());
        assertEquals("city_blueprint_catalog_snapshot.v0.10",
                context.getAsJsonObject("catalogSnapshotRef").get("schemaVersion").getAsString());
        assertEquals("city_blueprint_catalog_snapshot.v0.10",
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
        assertEquals("city_blueprint_validation_report.v0.4",
                submitted.getAsJsonObject("validationReport").get("schemaVersion").getAsString());
        assertEquals("city_blueprint_submission_trace.v0.4",
                submitted.getAsJsonObject("submissionTrace").get("schemaVersion").getAsString());

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
    void centerSymmetricIgnoresAiBuildingListAndLetsPcgChooseStructures() throws Exception {
        Fixture fixture = fixture("run_center_symmetric_invalid", "city:center_symmetric_invalid");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"));
        JsonObject group = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
        group.addProperty("algorithmProfileRef", "algorithm:center_symmetric");

        JsonObject result = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint);

        assertTrue(result.get("ok").getAsBoolean());
        JsonObject accepted = JsonParser.parseString(Files.readString(
                fixture.runDir().resolve("city_blueprint_city_center_symmetric_invalid/city_blueprint.json")))
                .getAsJsonObject();
        assertTrue(accepted.getAsJsonArray("groups").get(0).getAsJsonObject()
                .getAsJsonArray("requiredStructureRefs").size() >= 1);
    }

    @Test
    void intentOnlyGroupMustProvideBuildingPoolAndComposition() throws Exception {
        Fixture fixture = fixture("run_intent_only", "city:intent_only");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"));
        JsonObject group = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
        group.remove("requiredStructureRefs");
        group.remove("fillPoolRef");
        group.remove("compositionProfileRef");

        JsonObject result = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint);

        assertFalse(result.get("ok").getAsBoolean());
        assertTrue(result.getAsJsonObject("validationReport").getAsJsonArray("issues").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .anyMatch(issue -> issue.get("reasonCode").getAsString().equals("CITY_BLUEPRINT_FIELD_MISSING")));
    }

    @Test
    void placementRelationRequiresTheExactEndpointShape() throws Exception {
        Fixture fixture = fixture("run_placement_relation_invalid", "city:placement_relation_invalid");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"));
        blueprint.getAsJsonArray("groups").get(0).getAsJsonObject().add("placementRelation",
                JsonParser.parseString("""
                        {"kind":"BETWEEN_PATCHES","patchRefs":["patch:plain:1"],"groupRefs":[]}
                        """).getAsJsonObject());

        JsonObject result = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint);

        assertFalse(result.get("ok").getAsBoolean());
        assertTrue(result.getAsJsonObject("validationReport").getAsJsonArray("issues").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .anyMatch(issue -> "CITY_BLUEPRINT_PLACEMENT_RELATION_INVALID"
                        .equals(issue.get("reasonCode").getAsString())));
    }

    @Test
    void arrayCompositionRejectsUnknownAndMultiplyOwnedGroups() throws Exception {
        Fixture fixture = fixture("run_array_composition_invalid", "city:array_composition_invalid");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"));
        JsonObject source = blueprint.getAsJsonArray("groups").get(0).getAsJsonObject();
        JsonObject market = source.deepCopy();
        market.addProperty("groupId", "market");
        JsonObject warehouse = source.deepCopy();
        warehouse.addProperty("groupId", "warehouse");
        blueprint.getAsJsonArray("groups").add(market);
        blueprint.getAsJsonArray("groups").add(warehouse);
        addStructureGround(blueprint, "market");
        addStructureGround(blueprint, "warehouse");
        blueprint.getAsJsonArray("arrayCompositions").add(JsonParser.parseString("""
                {"compositionId":"first","algorithmProfileRef":"algorithm:compact",
                 "centerGroupId":"civic","memberGroupIds":["market"]}
                """).getAsJsonObject());
        blueprint.getAsJsonArray("arrayCompositions").add(JsonParser.parseString("""
                {"compositionId":"second","algorithmProfileRef":"algorithm:compact",
                 "centerGroupId":"warehouse","memberGroupIds":["market","missing"]}
                """).getAsJsonObject());

        JsonObject result = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint);

        assertFalse(result.get("ok").getAsBoolean());
        Set<String> reasonCodes = result.getAsJsonObject("validationReport").getAsJsonArray("issues")
                .asList().stream().map(JsonElement::getAsJsonObject)
                .map(issue -> issue.get("reasonCode").getAsString()).collect(java.util.stream.Collectors.toSet());
        assertTrue(reasonCodes.contains("CITY_BLUEPRINT_ARRAY_COMPOSITION_GROUP_UNKNOWN"));
        assertTrue(reasonCodes.contains("CITY_BLUEPRINT_ARRAY_COMPOSITION_GROUP_REUSED"));
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
    void previousContextSchemaIsRejectedAsStale() throws Exception {
        Fixture fixture = fixture("run_old_context", "city:old_context");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        Path contextPath = fixture.runDir().resolve("city_blueprint_" + safe(fixture.cityId()))
                .resolve("city_blueprint_context.json");
        JsonObject context = JsonParser.parseString(Files.readString(contextPath)).getAsJsonObject();
        context.addProperty("schemaVersion", "city_blueprint_context.v0.7");
        Files.writeString(contextPath, context.toString());

        JsonObject result = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint(prepared.getAsJsonObject("cityBlueprintContext")));

        assertFalse(result.get("ok").getAsBoolean());
        assertEquals("CITY_BLUEPRINT_CONTEXT_STALE",
                result.getAsJsonObject("validationReport").getAsJsonArray("issues")
                        .get(0).getAsJsonObject().get("reasonCode").getAsString());
    }

    @Test
    void previousSnapshotSchemaIsRejectedAsStale() throws Exception {
        Fixture fixture = fixture("run_old_snapshot", "city:old_snapshot");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        Path snapshotPath = fixture.runDir().resolve("city_blueprint_" + safe(fixture.cityId()))
                .resolve("city_blueprint_catalog_snapshot.json");
        JsonObject snapshot = JsonParser.parseString(Files.readString(snapshotPath)).getAsJsonObject();
        snapshot.addProperty("schemaVersion", "city_blueprint_catalog_snapshot.v0.8");
        Files.writeString(snapshotPath, snapshot.toString());

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
        blueprint.getAsJsonArray("groups").get(0).getAsJsonObject().addProperty("targetAreaShare", 0.5);
        secondGroup.addProperty("targetAreaShare", 0.5);
        blueprint.getAsJsonArray("groups").add(secondGroup);
        addStructureGround(blueprint, "market");
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
        blueprint.getAsJsonArray("groups").get(0).getAsJsonObject().addProperty("targetAreaShare", 0.5);
        second.addProperty("targetAreaShare", 0.5);
        blueprint.getAsJsonArray("groups").add(second);
        addStructureGround(blueprint, "market");

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

    @Test
    void generateRequiresExactlyOneSpatialGroundPerStructureGroup() throws Exception {
        Fixture fixture = fixture("run_missing_ground", "city:missing_ground");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"));
        blueprint.getAsJsonObject("outdoorPlan").add("spatialGrounds", new JsonArray());

        JsonObject result = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint);

        assertFalse(result.get("ok").getAsBoolean());
        assertEquals("CITY_BLUEPRINT_OUTDOOR_GROUND_COVERAGE_INVALID",
                result.getAsJsonObject("validationReport").getAsJsonArray("issues")
                        .get(0).getAsJsonObject().get("reasonCode").getAsString());
    }

    @Test
    void preserveRejectsGeneratedOutdoorContent() throws Exception {
        Fixture fixture = fixture("run_preserve_content", "city:preserve_content");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"));
        blueprint.getAsJsonObject("outdoorPlan").addProperty("mode", "PRESERVE");

        JsonObject result = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint);

        assertFalse(result.get("ok").getAsBoolean());
        assertEquals("CITY_BLUEPRINT_OUTDOOR_MODE_INVALID",
                result.getAsJsonObject("validationReport").getAsJsonArray("issues")
                        .get(0).getAsJsonObject().get("reasonCode").getAsString());
    }

    @Test
    void acceptsLandscapeUsingFrozenProfileAndAttachedGroup() throws Exception {
        Fixture fixture = fixture("run_landscape_intent", "city:landscape_intent");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"));
        blueprint.getAsJsonObject("outdoorPlan").getAsJsonArray("landscapes").add(
                JsonParser.parseString("""
                        {"landscapeId":"central_green","landscapeProfileRef":"landscape:greenbelt",
                         "purpose":"FUNCTIONAL","originMode":"ATTACHED",
                         "owner":{"groupId":"civic","requiredStructureRef":"geomantia:town_hall"},
                         "instanceCount":1,"parcelCount":1,"preferredPatchRefs":[],
                         "terrainPolicy":"CONFORM","required":true,
                         "fillSelection":{"variants":[{"fillProfileRef":"fill:relay_common_green","selectionWeight":1,
                           "roleShares":[{"roleRef":"GREEN","growthForm":"PATCH","targetShare":0.425},{"roleRef":"GROUND","growthForm":"PATCH","targetShare":0.15},{"roleRef":"GREEN","growthForm":"PATCH","targetShare":0.425}],
                           "contentWeights":[{"contentRef":"plant:grass","weight":1}]}]}}
                        """).getAsJsonObject());

        JsonObject result = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint);

        assertTrue(result.get("ok").getAsBoolean(), result.toString());
    }

    @Test
    void rejectsFillProfileIncompatibleWithLandscapeType() throws Exception {
        Fixture fixture = fixture("run_incompatible_fill", "city:incompatible_fill");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"));
        blueprint.getAsJsonObject("outdoorPlan").getAsJsonArray("landscapes").add(
                landscapeWithFill("landscape:greenbelt", "fill:relay_irrigated_farmland",
                        """
                        [{"roleRef":"CULTIVATED","growthForm":"PATCH","targetShare":0.82},
                         {"roleRef":"BANK","growthForm":"CORRIDOR","targetShare":0.12},{"roleRef":"WATER","growthForm":"CORRIDOR","targetShare":0.06}]
                        """, "[]"));

        JsonObject result = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint);

        assertFalse(result.get("ok").getAsBoolean());
        assertTrue(result.getAsJsonObject("validationReport").getAsJsonArray("issues").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .anyMatch(issue -> issue.get("fieldPath").getAsString().endsWith("fillProfileRef")
                        && issue.get("message").getAsString().contains("incompatible")));
    }

    @Test
    void rejectsInvalidFillShareSumAndContentOutsideWhitelist() throws Exception {
        Fixture fixture = fixture("run_invalid_fill_values", "city:invalid_fill_values");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"));
        blueprint.getAsJsonObject("outdoorPlan").getAsJsonArray("landscapes").add(
                landscapeWithFill("landscape:greenbelt", "fill:relay_common_green",
                        """
                        [{"roleRef":"GREEN","growthForm":"PATCH","targetShare":0.8},{"roleRef":"GROUND","growthForm":"PATCH","targetShare":0.15}]
                        """, "[{\"contentRef\":\"minecraft:grass_block\",\"weight\":1}]"));

        JsonObject result = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint);

        assertFalse(result.get("ok").getAsBoolean());
        JsonArray issues = result.getAsJsonObject("validationReport").getAsJsonArray("issues");
        assertTrue(issues.asList().stream().map(JsonElement::getAsJsonObject)
                .anyMatch(issue -> issue.get("fieldPath").getAsString().endsWith("roleShares")));
        assertTrue(issues.asList().stream().map(JsonElement::getAsJsonObject)
                .anyMatch(issue -> issue.get("fieldPath").getAsString().endsWith("contentRef")));
    }

    @Test
    void rejectsGrowthFormOutsideRoleFrontierBiasWhitelist() throws Exception {
        Fixture fixture = fixture("run_invalid_growth_form", "city:invalid_growth_form");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"));
        blueprint.getAsJsonObject("outdoorPlan").getAsJsonArray("landscapes").add(
                landscapeWithFill("landscape:greenbelt", "fill:relay_common_green",
                        """
                        [{"roleRef":"GREEN","growthForm":"CORRIDOR","targetShare":0.85},
                         {"roleRef":"GROUND","growthForm":"PATCH","targetShare":0.15}]
                        """, "[]"));

        JsonObject result = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint);

        assertFalse(result.get("ok").getAsBoolean());
        assertTrue(result.getAsJsonObject("validationReport").getAsJsonArray("issues").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .anyMatch(issue -> issue.get("fieldPath").getAsString().endsWith("growthForm")));
    }

    @Test
    void rejectsFillVariantThatOmitsADeclaredRoleEvenWhenSharesSumToOne() throws Exception {
        Fixture fixture = fixture("run_missing_fill_role", "city:missing_fill_role");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"));
        blueprint.getAsJsonObject("outdoorPlan").getAsJsonArray("landscapes").add(
                landscapeWithFill("landscape:farmland_fenced", "fill:relay_irrigated_farmland",
                        """
                        [{"roleRef":"CULTIVATED","growthForm":"PATCH","targetShare":0.85},
                         {"roleRef":"BANK","growthForm":"CORRIDOR","targetShare":0.15}]
                        """, "[]"));

        JsonObject result = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint);

        assertFalse(result.get("ok").getAsBoolean());
        assertTrue(result.getAsJsonObject("validationReport").getAsJsonArray("issues").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .anyMatch(issue -> issue.get("fieldPath").getAsString().endsWith("roleShares")
                        && issue.get("message").getAsString().contains("every role")));
    }

    @Test
    void rejectsLegacyLayerSequenceAndGeometryFallbackFields() throws Exception {
        Fixture fixture = fixture("run_forbidden_fill_geometry", "city:forbidden_fill_geometry");
        for (String field : List.of("layerSequence", "repeatLayers", "fixedShape", "distanceRings",
                "geometryFallback")) {
            JsonObject catalog = fixture.referenceCatalog().deepCopy();
            catalog.getAsJsonArray("landscapeFillProfiles").get(0).getAsJsonObject()
                    .addProperty(field, field.equals("repeatLayers"));
            CityBlueprintContractException failure = assertThrows(CityBlueprintContractException.class,
                    () -> new CityBlueprintService().prepare(temporary, fixture.runId(), fixture.cityId(),
                            fixture.terraSenseSource(), fixture.templateSource(), catalog));
            assertEquals(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                    failure.reasonCode(), field);
        }
        JsonObject oldAlgorithmCatalog = fixture.referenceCatalog().deepCopy();
        oldAlgorithmCatalog.getAsJsonArray("landscapeFillProfiles").get(0).getAsJsonObject()
                .addProperty("algorithm", "SINGLE_SOURCE_LAYERS");
        CityBlueprintContractException oldAlgorithmFailure = assertThrows(CityBlueprintContractException.class,
                () -> new CityBlueprintService().prepare(temporary, fixture.runId(), fixture.cityId(),
                        fixture.terraSenseSource(), fixture.templateSource(), oldAlgorithmCatalog));
        assertEquals(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                oldAlgorithmFailure.reasonCode());
    }

    @Test
    void rejectsCatalogThatExposesLandscapeWithoutCompatibleFillProfile() throws Exception {
        Fixture fixture = fixture("run_missing_landscape_fill", "city:missing_landscape_fill");
        JsonObject catalog = fixture.referenceCatalog().deepCopy();
        JsonArray fillProfiles = catalog.getAsJsonArray("landscapeFillProfiles");
        fillProfiles.remove(fillProfiles.size() - 1);

        CityBlueprintContractException failure = assertThrows(CityBlueprintContractException.class,
                () -> new CityBlueprintService().prepare(temporary, fixture.runId(), fixture.cityId(),
                        fixture.terraSenseSource(), fixture.templateSource(), catalog));

        assertEquals(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                failure.reasonCode());
        assertTrue(failure.getMessage().contains("compatible landscapeFillProfile"));
    }

    @Test
    void rejectsFillRoleShareAboveOne() throws Exception {
        Fixture fixture = fixture("run_fill_share_over_one", "city:fill_share_over_one");
        JsonObject catalog = fixture.referenceCatalog().deepCopy();
        catalog.getAsJsonArray("landscapeFillProfiles").get(0).getAsJsonObject()
                .getAsJsonArray("roles").get(0).getAsJsonObject().addProperty("maxShare", 1.1);

        CityBlueprintContractException failure = assertThrows(CityBlueprintContractException.class,
                () -> new CityBlueprintService().prepare(temporary, fixture.runId(), fixture.cityId(),
                        fixture.terraSenseSource(), fixture.templateSource(), catalog));

        assertEquals(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                failure.reasonCode());
    }

    @Test
    void rejectsNonFiniteFillRoleShare() throws Exception {
        Fixture fixture = fixture("run_fill_share_nan", "city:fill_share_nan");
        JsonObject catalog = fixture.referenceCatalog().deepCopy();
        catalog.getAsJsonArray("landscapeFillProfiles").get(0).getAsJsonObject()
                .getAsJsonArray("roles").get(0).getAsJsonObject().addProperty("maxShare", Double.NaN);

        CityBlueprintContractException failure = assertThrows(CityBlueprintContractException.class,
                () -> new CityBlueprintService().prepare(temporary, fixture.runId(), fixture.cityId(),
                        fixture.terraSenseSource(), fixture.templateSource(), catalog));

        assertEquals(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                failure.reasonCode());
    }

    @Test
    void freezesFourStrictLandscapeProfilesWithDistinctSurfaceSemantics() throws Exception {
        Fixture fixture = fixture("run_landscape_catalog", "city:landscape_catalog");

        new CityBlueprintService().prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());

        JsonObject snapshot = JsonParser.parseString(Files.readString(fixture.runDir()
                .resolve("city_blueprint_" + safe(fixture.cityId()))
                .resolve("city_blueprint_catalog_snapshot.json"))).getAsJsonObject();
        JsonObject catalog = snapshot.getAsJsonObject("referenceCatalog");
        assertEquals(4, catalog.getAsJsonArray("landscapeProfiles").size());
        assertEquals(5, catalog.getAsJsonArray("landscapeFillProfiles").size());
        JsonObject irrigated = catalog.getAsJsonArray("landscapeFillProfiles").get(0).getAsJsonObject();
        assertEquals("SINGLE_SOURCE_REGION_RELAY", irrigated.get("algorithm").getAsString());
        assertEquals("PARENT_REGION_LOCAL_BOUNDARY", irrigated.get("relayOrigin").getAsString());
        assertFalse(irrigated.has("layerSequence"));
        assertFalse(irrigated.has("geometryFallback"));
        JsonObject farmlandProfile = catalog.getAsJsonArray("landscapeProfiles").get(0).getAsJsonObject();
        assertEquals("landscape:farmland_fenced", farmlandProfile.get("landscapeProfileRef").getAsString());
        assertEquals(1, farmlandProfile.getAsJsonObject("parcelStyle").get("parcelCountMin").getAsInt());
        assertEquals(12, farmlandProfile.getAsJsonObject("parcelStyle").get("parcelCountMax").getAsInt());
        assertEquals(4, farmlandProfile.getAsJsonObject("parcelStyle")
                .get("minSharedBoundaryBlocks").getAsInt());
        assertTrue(catalog.getAsJsonArray("landscapeProfiles").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .anyMatch(profile -> "landscape:forestry".equals(
                        profile.get("landscapeProfileRef").getAsString())
                        && "forestry".equals(profile.get("landUseRuleRef").getAsString())));
        assertTrue(catalog.getAsJsonArray("surfaceRecipes").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .anyMatch(recipe -> "surface_recipe:forestry".equals(
                        recipe.get("surfaceRecipeRef").getAsString())
                        && "minecraft:spruce_fence".equals(recipe.get("boundaryBlockId").getAsString())));
        assertTrue(catalog.getAsJsonArray("landscapeFillProfiles").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .anyMatch(profile -> "fill:relay_woodland".equals(
                        profile.get("fillProfileRef").getAsString())
                        && "WOODLAND".equals(profile.getAsJsonArray("compatibleLandscapeTypes")
                        .get(0).getAsString())));
        assertTrue(catalog.getAsJsonArray("surfaceRecipes").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .anyMatch(recipe -> "surface_recipe:flower_field".equals(
                        recipe.get("surfaceRecipeRef").getAsString())
                        && "minecraft:poppy".equals(recipe.get("cropBlockId").getAsString())));
    }

    @Test
    void contourSurfaceRecipeRequiresCompleteFrozenMaterials() throws Exception {
        Fixture fixture = fixture("run_bad_surface_recipe", "city:bad_surface_recipe");
        JsonObject catalog = fixture.referenceCatalog().deepCopy();
        catalog.getAsJsonArray("surfaceRecipes").get(0).getAsJsonObject()
                .addProperty("surfaceAlgorithm", "CONTOUR_BANDS");

        CityBlueprintContractException failure = assertThrows(CityBlueprintContractException.class,
                () -> new CityBlueprintService().prepare(temporary, fixture.runId(), fixture.cityId(),
                        fixture.terraSenseSource(), fixture.templateSource(), catalog));

        assertEquals(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                failure.reasonCode());
    }

    @Test
    void acceptsConfiguredContourWidthsAndOptionalBoundaryBlock() throws Exception {
        Fixture fixture = fixture("run_contour_surface_recipe", "city:contour_surface_recipe");
        JsonObject catalog = fixture.referenceCatalog().deepCopy();
        JsonObject recipe = catalog.getAsJsonArray("surfaceRecipes").get(0).getAsJsonObject();
        recipe.addProperty("surfaceAlgorithm", "CONTOUR_BANDS");
        recipe.addProperty("cropBlockId", "minecraft:wheat");
        recipe.addProperty("channelBankBlockId", "minecraft:dirt");
        recipe.addProperty("channelWaterBlockId", "minecraft:water");
        recipe.addProperty("channelBankOverlayBlockId", "minecraft:oak_slab");
        recipe.addProperty("boundaryBlockId", "minecraft:cobblestone");
        recipe.addProperty("fieldBeforeBlocks", 7);
        recipe.addProperty("channelWidthBlocks", 2);
        recipe.addProperty("fieldAfterBlocks", 4);

        new CityBlueprintService().prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), catalog);
        JsonObject snapshot = JsonParser.parseString(Files.readString(fixture.runDir()
                .resolve("city_blueprint_" + safe(fixture.cityId()))
                .resolve("city_blueprint_catalog_snapshot.json"))).getAsJsonObject();
        JsonObject frozenRecipe = snapshot.getAsJsonObject("referenceCatalog")
                .getAsJsonArray("surfaceRecipes").get(0).getAsJsonObject();
        assertEquals(7, frozenRecipe.get("fieldBeforeBlocks").getAsInt());
        assertEquals("minecraft:cobblestone", frozenRecipe.get("boundaryBlockId").getAsString());
    }

    @Test
    void oldResidualPolicyIsRejectedInsteadOfSilentlyMigrated() throws Exception {
        Fixture fixture = fixture("run_old_residual", "city:old_residual");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"));
        blueprint.getAsJsonObject("outdoorPlan").add("residualPolicy", new JsonObject());

        JsonObject result = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint);

        assertFalse(result.get("ok").getAsBoolean());
    }

    @Test
    void spatialGroundRejectsRemovedMaterialAuthorityFields() throws Exception {
        Fixture fixture = fixture("run_spatial_ground_material", "city:spatial_ground_material");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"));
        blueprint.getAsJsonObject("outdoorPlan").getAsJsonArray("spatialGrounds")
                .get(0).getAsJsonObject().addProperty("surfaceRecipeRef", "surface_recipe:civic");

        JsonObject result = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint);

        assertFalse(result.get("ok").getAsBoolean());
        assertEquals("CITY_BLUEPRINT_FIELD_UNKNOWN",
                result.getAsJsonObject("validationReport").getAsJsonArray("issues")
                        .get(0).getAsJsonObject().get("reasonCode").getAsString());
    }

    @Test
    void rejectsUnknownFoundationProfile() throws Exception {
        Fixture fixture = fixture("run_unknown_foundation", "city:unknown_foundation");
        CityBlueprintService service = new CityBlueprintService();
        JsonObject prepared = service.prepare(temporary, fixture.runId(), fixture.cityId(),
                fixture.terraSenseSource(), fixture.templateSource(), fixture.referenceCatalog());
        JsonObject blueprint = blueprint(prepared.getAsJsonObject("cityBlueprintContext"));
        blueprint.getAsJsonObject("outdoorPlan").addProperty("foundationProfileRef", "foundation:missing");

        JsonObject result = service.submit(temporary, fixture.runId(), fixture.cityId(),
                prepared.get("contextId").getAsString(), blueprint);

        assertFalse(result.get("ok").getAsBoolean());
        assertEquals("CITY_BLUEPRINT_OUTDOOR_FOUNDATION_PROFILE_UNKNOWN",
                result.getAsJsonObject("validationReport").getAsJsonArray("issues")
                        .get(0).getAsJsonObject().get("reasonCode").getAsString());
    }

    @Test
    void rejectsNonMonotonicFoundationDistances() throws Exception {
        Fixture fixture = fixture("run_bad_foundation_ranges", "city:bad_foundation_ranges");
        JsonObject catalog = fixture.referenceCatalog().deepCopy();
        catalog.getAsJsonArray("foundationProfiles").get(0).getAsJsonObject()
                .addProperty("closeRadiusBlocks", 1);

        CityBlueprintContractException failure = assertThrows(CityBlueprintContractException.class,
                () -> new CityBlueprintService().prepare(temporary, fixture.runId(), fixture.cityId(),
                        fixture.terraSenseSource(), fixture.templateSource(), catalog));

        assertEquals(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                failure.reasonCode());
    }

    @Test
    void rejectsInvalidLandscapeParcelStyleRanges() throws Exception {
        Fixture fixture = fixture("run_bad_parcel_style", "city:bad_parcel_style");
        JsonObject catalog = fixture.referenceCatalog().deepCopy();
        catalog.getAsJsonArray("landscapeProfiles").get(0).getAsJsonObject()
                .getAsJsonObject("parcelStyle").addProperty("minSharedBoundaryBlocks", 1000);

        CityBlueprintContractException failure = assertThrows(CityBlueprintContractException.class,
                () -> new CityBlueprintService().prepare(temporary, fixture.runId(), fixture.cityId(),
                        fixture.terraSenseSource(), fixture.templateSource(), catalog));

        assertEquals(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_INVALID,
                failure.reasonCode());
    }

    @Test
    void rejectsPreviousReferenceCatalogSchema() throws Exception {
        Fixture fixture = fixture("run_old_reference_catalog", "city:old_reference_catalog");
        JsonObject catalog = fixture.referenceCatalog().deepCopy();
        catalog.addProperty("schemaVersion", "city_blueprint_reference_catalog.v0.6");

        CityBlueprintContractException failure = assertThrows(CityBlueprintContractException.class,
                () -> new CityBlueprintService().prepare(temporary, fixture.runId(), fixture.cityId(),
                        fixture.terraSenseSource(), fixture.templateSource(), catalog));

        assertEquals(CityBlueprintReasonCode.CITY_BLUEPRINT_REFERENCE_CATALOG_SCHEMA_UNSUPPORTED,
                failure.reasonCode());
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
                  "schemaVersion":"city_blueprint_reference_catalog.v0.9",
                  "structureRefs":[{"structureRef":"geomantia:town_hall","templateCandidates":[{"templateId":"geomantia:town_hall","variantId":"default"}]}],
                  "fillPools":[{"poolRef":"pool:civic","structureRefs":["geomantia:town_hall"]}],
                  "algorithmProfiles":[
                    {"algorithmProfileRef":"algorithm:compact","algorithm":"COMPACT"},
                    {"algorithmProfileRef":"algorithm:street_band","algorithm":"LINEAR"},
                    {"algorithmProfileRef":"algorithm:center_symmetric","algorithm":"CENTER_SYMMETRIC"}
                  ],
                  "compositionProfiles":[{"compositionProfileRef":"composition:round_robin","mode":"ROUND_ROBIN"}],
                  "styleProfiles":[{"profileRef":"style:river_stone"}],
                  "roadProfiles":[{"profileRef":"road:town","hierarchy":"HIERARCHICAL","density":"BALANCED"}],
                  "surfaceDetailProfiles":[{"profileRef":"surface:working","intensity":"MEDIUM"}],
                  "landUseRuleProfile":{"schemaVersion":"city_land_use_rules.v0.1","profileId":"blueprint_test","rules":[{
                    "ruleRef":"civic","landUseType":"civic","semanticTerms":["administration"],
                    "footprintMultiplier":1.5,"extraAreaBlocks":80,"minAreaBlocks":80,"maxAreaBlocks":1536,
                    "actionBudget":300,"baseStepCost":1.0,"slopeCost":1.2,"reliefCost":1.2,"waterCost":8.0,
                    "forestAffinity":0.0,"competitionWeight":1.0,"mergeSameType":true,
                    "surfacePolicy":"PAVE","vegetationPolicy":"CLEAR","boundaryPolicy":"OPEN","decorationPolicy":"none"
                  },{
                    "ruleRef":"agriculture","landUseType":"agriculture","semanticTerms":["farm"],
                    "footprintMultiplier":1.0,"extraAreaBlocks":256,"minAreaBlocks":128,"maxAreaBlocks":4096,
                    "actionBudget":600,"baseStepCost":1.0,"slopeCost":1.4,"reliefCost":1.2,"waterCost":4.0,
                    "forestAffinity":0.2,"competitionWeight":1.0,"mergeSameType":true,
                    "surfacePolicy":"CULTIVATE","vegetationPolicy":"CLEAR","boundaryPolicy":"FENCE","decorationPolicy":"none"
                  },{
                    "ruleRef":"meadow","landUseType":"meadow","semanticTerms":["flower"],
                    "footprintMultiplier":0.8,"extraAreaBlocks":192,"minAreaBlocks":96,"maxAreaBlocks":3072,
                    "actionBudget":480,"baseStepCost":1.0,"slopeCost":1.0,"reliefCost":1.0,"waterCost":6.0,
                    "forestAffinity":0.1,"competitionWeight":0.9,"mergeSameType":true,
                    "surfacePolicy":"CULTIVATE","vegetationPolicy":"SELECTIVE_CLEAR","boundaryPolicy":"OPEN","decorationPolicy":"none"
                  },{
                    "ruleRef":"greenbelt","landUseType":"greenbelt","semanticTerms":["park"],
                    "footprintMultiplier":0.6,"extraAreaBlocks":128,"minAreaBlocks":64,"maxAreaBlocks":2048,
                    "actionBudget":420,"baseStepCost":1.0,"slopeCost":1.0,"reliefCost":1.0,"waterCost":5.0,
                    "forestAffinity":0.0,"competitionWeight":0.8,"mergeSameType":true,
                    "surfacePolicy":"PAVE","vegetationPolicy":"PRESERVE","boundaryPolicy":"HEDGE","decorationPolicy":"none"
                  },{
                    "ruleRef":"forestry","landUseType":"forestry","semanticTerms":["woodland"],
                    "footprintMultiplier":1.0,"extraAreaBlocks":320,"minAreaBlocks":160,"maxAreaBlocks":4096,
                    "actionBudget":640,"baseStepCost":1.0,"slopeCost":0.8,"reliefCost":0.8,"waterCost":7.0,
                    "forestAffinity":-0.4,"competitionWeight":0.85,"mergeSameType":true,
                    "surfacePolicy":"PRESERVE","vegetationPolicy":"PRESERVE","boundaryPolicy":"FENCE","decorationPolicy":"none"
                  }]},
                  "surfaceRecipes":[
                    {"surfaceRecipeRef":"surface_recipe:civic","surfacePrintEnabled":true,
                    "autoConnectDefault":true,"surfaceAlgorithm":"UNIFORM","surfaceBlockId":"minecraft:stone_bricks"},
                    {"surfaceRecipeRef":"surface_recipe:farmland_fenced","surfacePrintEnabled":true,
                    "autoConnectDefault":true,"surfaceAlgorithm":"CONTOUR_BANDS","surfaceBlockId":"minecraft:farmland",
                    "cropBlockId":"minecraft:wheat","channelBankBlockId":"minecraft:dirt",
                    "channelWaterBlockId":"minecraft:water","channelBankOverlayBlockId":"minecraft:oak_slab",
                    "boundaryBlockId":"minecraft:oak_fence","fieldBeforeBlocks":5,"channelWidthBlocks":3,"fieldAfterBlocks":5},
                    {"surfaceRecipeRef":"surface_recipe:flower_field","surfacePrintEnabled":true,
                    "autoConnectDefault":true,"surfaceAlgorithm":"CONTOUR_BANDS","surfaceBlockId":"minecraft:grass_block",
                    "cropBlockId":"minecraft:poppy","channelBankBlockId":"minecraft:dirt",
                    "channelWaterBlockId":"minecraft:water","channelBankOverlayBlockId":"minecraft:moss_carpet",
                    "fieldBeforeBlocks":7,"channelWidthBlocks":1,"fieldAfterBlocks":7},
                    {"surfaceRecipeRef":"surface_recipe:greenbelt","surfacePrintEnabled":true,
                    "autoConnectDefault":true,"surfaceAlgorithm":"UNIFORM","surfaceBlockId":"minecraft:grass_block",
                    "boundaryBlockId":"minecraft:oak_leaves"},
                    {"surfaceRecipeRef":"surface_recipe:forestry","surfacePrintEnabled":true,
                    "autoConnectDefault":false,"surfaceAlgorithm":"UNIFORM","surfaceBlockId":"minecraft:podzol",
                    "channelBankBlockId":"minecraft:gravel","channelBankOverlayBlockId":"minecraft:oak_leaves",
                    "boundaryBlockId":"minecraft:spruce_fence"}
                  ],
                  "foundationProfiles":[{"foundationProfileRef":"foundation:urban","landUseRuleRef":"civic",
                    "surfaceRecipeRef":"surface_recipe:civic","structureMarginBlocks":2,
                    "closeRadiusBlocks":16,"maxJoinDistanceBlocks":48}],
                  "landscapeProfiles":[
                    {"landscapeProfileRef":"landscape:farmland_fenced","landscapeType":"FARMLAND",
                    "landUseRuleRef":"agriculture","surfaceRecipeRef":"surface_recipe:farmland_fenced","baseAreaSmall":512,
                    "baseAreaMedium":1024,"baseAreaLarge":2048,"membership":"LANDSCAPE",
                    "parcelStyle":{"parcelCountMin":1,"parcelCountMax":12,
                    "parcelAreaMinBlocks":64,"parcelAreaMaxBlocks":256,"minSharedBoundaryBlocks":4}},
                    {"landscapeProfileRef":"landscape:flower_field","landscapeType":"MEADOW",
                    "landUseRuleRef":"meadow","surfaceRecipeRef":"surface_recipe:flower_field","baseAreaSmall":256,
                    "baseAreaMedium":512,"baseAreaLarge":1024,"membership":"LANDSCAPE",
                    "parcelStyle":{"parcelCountMin":1,"parcelCountMax":8,
                    "parcelAreaMinBlocks":48,"parcelAreaMaxBlocks":192,"minSharedBoundaryBlocks":3}},
                    {"landscapeProfileRef":"landscape:greenbelt","landscapeType":"COMMON_GREEN",
                    "landUseRuleRef":"greenbelt","surfaceRecipeRef":"surface_recipe:greenbelt","baseAreaSmall":128,
                    "baseAreaMedium":384,"baseAreaLarge":768,"membership":"URBAN",
                    "parcelStyle":{"parcelCountMin":1,"parcelCountMax":6,
                    "parcelAreaMinBlocks":32,"parcelAreaMaxBlocks":128,"minSharedBoundaryBlocks":2}},
                    {"landscapeProfileRef":"landscape:forestry","landscapeType":"WOODLAND",
                    "landUseRuleRef":"forestry","surfaceRecipeRef":"surface_recipe:forestry","baseAreaSmall":512,
                    "baseAreaMedium":1536,"baseAreaLarge":4096,"membership":"LANDSCAPE",
                    "parcelStyle":{"parcelCountMin":1,"parcelCountMax":8,
                    "parcelAreaMinBlocks":96,"parcelAreaMaxBlocks":384,"minSharedBoundaryBlocks":4}}
                  ],
                  "landscapeFillProfiles":[
                    {"fillProfileRef":"fill:relay_irrigated_farmland","displayName":"接力灌溉农田",
                    "visualIntent":"耕作区、田埂和水渠从父区域局部边界逐区接力","algorithm":"SINGLE_SOURCE_REGION_RELAY",
                    "relayOrigin":"PARENT_REGION_LOCAL_BOUNDARY",
                    "compatibleLandscapeTypes":["FARMLAND"],"primaryRoleRef":"CULTIVATED",
                    "roles":[
                      {"roleRef":"CULTIVATED","materialRole":"PRIMARY_CONTENT","allowedGrowthForms":["PATCH"],"defaultGrowthForm":"PATCH","minShare":0.65,"maxShare":0.92,"defaultShare":0.82},
                      {"roleRef":"BANK","materialRole":"BANK","allowedGrowthForms":["CORRIDOR"],"defaultGrowthForm":"CORRIDOR","minShare":0.06,"maxShare":0.2,"defaultShare":0.12},
                      {"roleRef":"WATER","materialRole":"WATER","allowedGrowthForms":["CORRIDOR"],"defaultGrowthForm":"CORRIDOR","minShare":0.02,"maxShare":0.12,"defaultShare":0.06}],
                    "allowedContentRefs":["crop:wheat","crop:carrot","crop:potato"],
                    "examples":[{"exampleId":"balanced_irrigation","description":"耕地为主题，窄水渠周期分隔",
                      "roleShares":[{"roleRef":"CULTIVATED","growthForm":"PATCH","targetShare":0.41},{"roleRef":"BANK","growthForm":"CORRIDOR","targetShare":0.06},{"roleRef":"WATER","growthForm":"CORRIDOR","targetShare":0.06},{"roleRef":"BANK","growthForm":"CORRIDOR","targetShare":0.06},{"roleRef":"CULTIVATED","growthForm":"PATCH","targetShare":0.41}],
                      "contentWeights":[{"contentRef":"crop:wheat","weight":60},{"contentRef":"crop:carrot","weight":25},{"contentRef":"crop:potato","weight":15}]}]},
                    {"fillProfileRef":"fill:relay_dry_farmland","displayName":"接力旱地农田",
                    "visualIntent":"耕地区和土路区从父区域局部边界自然接力","algorithm":"SINGLE_SOURCE_REGION_RELAY",
                    "relayOrigin":"PARENT_REGION_LOCAL_BOUNDARY",
                    "compatibleLandscapeTypes":["FARMLAND"],"primaryRoleRef":"CULTIVATED",
                    "roles":[
                      {"roleRef":"CULTIVATED","materialRole":"PRIMARY_CONTENT","allowedGrowthForms":["PATCH"],"defaultGrowthForm":"PATCH","minShare":0.75,"maxShare":0.95,"defaultShare":0.88},
                      {"roleRef":"DIRT_PATH","materialRole":"GROUND","allowedGrowthForms":["CORRIDOR"],"defaultGrowthForm":"CORRIDOR","minShare":0.05,"maxShare":0.25,"defaultShare":0.12}],
                    "allowedContentRefs":["crop:wheat","crop:potato"],
                    "examples":[{"exampleId":"wheat_dry_fields","description":"麦田占主导，土路只作间隔",
                      "roleShares":[{"roleRef":"CULTIVATED","growthForm":"PATCH","targetShare":0.44},{"roleRef":"DIRT_PATH","growthForm":"CORRIDOR","targetShare":0.12},{"roleRef":"CULTIVATED","growthForm":"PATCH","targetShare":0.44}],
                      "contentWeights":[{"contentRef":"crop:wheat","weight":75},{"contentRef":"crop:potato","weight":25}]}]},
                    {"fillProfileRef":"fill:relay_flower_meadow","displayName":"接力花田叶带",
                    "visualIntent":"花丛区和叶带区从父区域局部边界接力生长","algorithm":"SINGLE_SOURCE_REGION_RELAY",
                    "relayOrigin":"PARENT_REGION_LOCAL_BOUNDARY",
                    "compatibleLandscapeTypes":["MEADOW"],"primaryRoleRef":"FLOWER",
                    "roles":[
                      {"roleRef":"FLOWER","materialRole":"PRIMARY_CONTENT","allowedGrowthForms":["PATCH"],"defaultGrowthForm":"PATCH","minShare":0.7,"maxShare":0.94,"defaultShare":0.84},
                      {"roleRef":"LEAF_BREAK","materialRole":"BANK","allowedGrowthForms":["CORRIDOR"],"defaultGrowthForm":"CORRIDOR","minShare":0.06,"maxShare":0.3,"defaultShare":0.16}],
                    "allowedContentRefs":["flower:poppy","flower:dandelion","flower:cornflower"],
                    "examples":[{"exampleId":"warm_wildflowers","description":"暖色花丛为主并用叶带过渡",
                      "roleShares":[{"roleRef":"FLOWER","growthForm":"PATCH","targetShare":0.42},{"roleRef":"LEAF_BREAK","growthForm":"CORRIDOR","targetShare":0.16},{"roleRef":"FLOWER","growthForm":"PATCH","targetShare":0.42}],
                      "contentWeights":[{"contentRef":"flower:poppy","weight":55},{"contentRef":"flower:dandelion","weight":30},{"contentRef":"flower:cornflower","weight":15}]}]},
                    {"fillProfileRef":"fill:relay_common_green","displayName":"接力城市绿地",
                    "visualIntent":"绿植区和自然地面区从父区域局部边界接力","algorithm":"SINGLE_SOURCE_REGION_RELAY",
                    "relayOrigin":"PARENT_REGION_LOCAL_BOUNDARY",
                    "compatibleLandscapeTypes":["COMMON_GREEN"],"primaryRoleRef":"GREEN",
                    "roles":[
                      {"roleRef":"GREEN","materialRole":"PRIMARY_CONTENT","allowedGrowthForms":["PATCH"],"defaultGrowthForm":"PATCH","minShare":0.7,"maxShare":0.95,"defaultShare":0.85},
                      {"roleRef":"GROUND","materialRole":"GROUND","allowedGrowthForms":["PATCH","CORRIDOR"],"defaultGrowthForm":"PATCH","minShare":0.05,"maxShare":0.3,"defaultShare":0.15}],
                    "allowedContentRefs":["plant:grass"],
                    "examples":[{"exampleId":"simple_green","description":"绿植为主的连续城市绿地",
                      "roleShares":[{"roleRef":"GREEN","growthForm":"PATCH","targetShare":0.425},{"roleRef":"GROUND","growthForm":"PATCH","targetShare":0.15},{"roleRef":"GREEN","growthForm":"PATCH","targetShare":0.425}],
                      "contentWeights":[{"contentRef":"plant:grass","weight":1}]}]}
                    ,{"fillProfileRef":"fill:relay_woodland","displayName":"接力林场",
                    "visualIntent":"树林、灌木和石子路区域从父区域局部边界接力","algorithm":"SINGLE_SOURCE_REGION_RELAY",
                    "relayOrigin":"PARENT_REGION_LOCAL_BOUNDARY",
                    "compatibleLandscapeTypes":["WOODLAND"],"primaryRoleRef":"TREE_GROVE",
                    "roles":[
                      {"roleRef":"TREE_GROVE","materialRole":"PRIMARY_CONTENT","allowedGrowthForms":["PATCH"],"defaultGrowthForm":"PATCH","minShare":0.58,"maxShare":0.82,"defaultShare":0.7},
                      {"roleRef":"SHRUB_BREAK","materialRole":"BANK","allowedGrowthForms":["PATCH","CORRIDOR"],"defaultGrowthForm":"PATCH","minShare":0.08,"maxShare":0.28,"defaultShare":0.18},
                      {"roleRef":"GRAVEL_PATH","materialRole":"GROUND","allowedGrowthForms":["CORRIDOR"],"defaultGrowthForm":"CORRIDOR","minShare":0.05,"maxShare":0.2,"defaultShare":0.12}],
                    "allowedContentRefs":["tree:oak","tree:birch","tree:spruce"],
                    "examples":[{"exampleId":"mixed_working_woodland","description":"树木占主导，灌木和石子路只作间隔",
                      "roleShares":[{"roleRef":"TREE_GROVE","growthForm":"PATCH","targetShare":0.35},{"roleRef":"SHRUB_BREAK","growthForm":"PATCH","targetShare":0.09},{"roleRef":"GRAVEL_PATH","growthForm":"CORRIDOR","targetShare":0.12},{"roleRef":"SHRUB_BREAK","growthForm":"PATCH","targetShare":0.09},{"roleRef":"TREE_GROVE","growthForm":"PATCH","targetShare":0.35}],
                      "contentWeights":[{"contentRef":"tree:oak","weight":55},{"contentRef":"tree:birch","weight":30},{"contentRef":"tree:spruce","weight":15}]}]}
                  ]
                }
                """).getAsJsonObject();
    }

    private static JsonObject blueprint(JsonObject context) {
        JsonObject blueprint = new JsonObject();
        blueprint.addProperty("schemaVersion", "city_blueprint.v0.11");
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
                  "compositionProfileRef":"composition:round_robin","attachedFeatures":[],
                  "targetAreaShare":1.0,
                  "spaceComposition":{"buildingShare":1.0,"landscapeShare":0.0,"openSpaceShare":0.0},
                  "expansionPolicy":{"allowOutwardExpansion":true,"allowRelationConnection":true,"stopWhenTargetReached":true}
                }
                """).getAsJsonObject());
        blueprint.add("groups", groups);
        blueprint.add("arrayCompositions", new JsonArray());
        blueprint.add("relations", new JsonArray());
        blueprint.add("roadProfile", JsonParser.parseString("{\"profileRef\":\"road:town\"}").getAsJsonObject());
        blueprint.add("surfaceDetailProfile", JsonParser.parseString("{\"profileRef\":\"surface:working\"}").getAsJsonObject());
        blueprint.add("outdoorPlan", JsonParser.parseString("""
                {
                  "mode":"GENERATE","envelopeProfile":"BALANCED",
                  "foundationProfileRef":"foundation:urban",
                  "spatialGrounds":[{"sourceGroupId":"civic","sharedSpaceType":"CIVIC_SQUARE",
                    "hierarchyLevel":"PRIMARY","membership":"URBAN"}],
                  "landscapes":[]
                }
                """).getAsJsonObject());
        return blueprint;
    }

    private static JsonObject landscapeWithFill(String landscapeProfileRef, String fillProfileRef,
                                                String roleShares, String contentWeights) {
        return JsonParser.parseString("""
                {"landscapeId":"test_green","landscapeProfileRef":"%s",
                 "purpose":"FUNCTIONAL","originMode":"ATTACHED",
                 "owner":{"groupId":"civic","requiredStructureRef":"geomantia:town_hall"},
                 "instanceCount":1,"parcelCount":1,"preferredPatchRefs":[],
                 "terrainPolicy":"CONFORM","required":true,
                 "fillSelection":{"variants":[{"fillProfileRef":"%s","selectionWeight":1,
                   "roleShares":%s,"contentWeights":%s}]}}
                """.formatted(landscapeProfileRef, fillProfileRef, roleShares, contentWeights)).getAsJsonObject();
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void addStructureGround(JsonObject blueprint, String groupId) {
        JsonObject ground = blueprint.getAsJsonObject("outdoorPlan").getAsJsonArray("spatialGrounds")
                .get(0).getAsJsonObject().deepCopy();
        ground.addProperty("sourceGroupId", groupId);
        blueprint.getAsJsonObject("outdoorPlan").getAsJsonArray("spatialGrounds").add(ground);
    }

    private record Fixture(String runId, String cityId, Path runDir, Path d3Path,
                           JsonObject terraSenseSource, JsonObject templateSource,
                           JsonObject referenceCatalog) {
    }
}
