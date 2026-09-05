package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PlanningBoundaryTest {
    @TempDir Path directory;

    @Test void authorFunctionAndStyleAreRequiredAndNeverInferredFromAnId() {
        var profile = new com.rinsing.geomantia.systems.city.application.CityStructureProfileCatalog.StructureProfile(
                "pack:medieval_mill", "author", "approved", List.of("grain_processing"), List.of(), List.of(), List.of(), "official");
        var missingStyle = new com.rinsing.geomantia.systems.city.application.CityStructureProfileCatalog.ImportedCatalog(
                "official", new JsonObject(), List.of(profile), List.of(), List.of());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ManagedCityPlanningSources.authoringBrief(missingStyle, java.util.Set.of(profile.semanticProfileId())))
                .getMessage().contains("PLANNING_AUTHOR_ANNOTATION_REQUIRED"));
        var approved = new com.rinsing.geomantia.systems.city.application.CityStructureProfileCatalog.StructureProfile(
                profile.semanticProfileId(), "author", "approved", List.of("grain_processing"), List.of(), List.of(), List.of("author_style"), "official");
        var catalog = new com.rinsing.geomantia.systems.city.application.CityStructureProfileCatalog.ImportedCatalog(
                "official", new JsonObject(), List.of(approved), List.of(), List.of());
        var brief = ManagedCityPlanningSources.authoringBrief(catalog, java.util.Set.of(approved.semanticProfileId()));
        assertEquals("author_style", brief.getAsJsonArray("structures").get(0).getAsJsonObject().getAsJsonArray("styleTerms").get(0).getAsString());
    }

    @Test void compactViewPreservesAuthoredSemanticsAndDoesNotMutateTruth() throws Exception {
        JsonObject source = JsonParser.parseString("""
            {"contextId":"frozen","catalogSnapshot":{"structureCatalog":{"profiles":[{"functionTerms":["mill"],"styleTerms":["stone"]}]}},
             "cells":[{"x":0},{"x":1}],"artifacts":{"preview":"run/map.png"}}
            """).getAsJsonObject();
        Files.createDirectories(directory.resolve("run"));
        Files.write(directory.resolve("run/map.png"), new byte[]{1,2,3});
        JsonObject view = PlanningToolPresentation.present(source, directory);
        assertTrue(source.has("cells")); assertFalse(view.has("cells"));
        assertEquals(2, view.getAsJsonObject("cellsSummary").get("entryCount").getAsInt());
        assertEquals(source.get("catalogSnapshot"), view.get("catalogSnapshot"));
        assertEquals("AQID", view.getAsJsonArray("imageEvidence").get(0).getAsJsonObject().get("data").getAsString());
    }

    @Test void imageViewRejectsTraversalOutsideTheCurrentWorld() throws Exception {
        JsonObject source = new JsonObject(); source.addProperty("preview", "../outside.png");
        JsonObject result = PlanningToolPresentation.present(source, directory);
        assertFalse(result.has("imageEvidence")); assertTrue(result.has("previewWarnings"));
    }

    @Test void blueprintViewKeepsAuthorChoicesAndPagesTerrainInsteadOfTruncating() throws Exception {
        JsonObject context = JsonParser.parseString("""
            {"schema":"city_blueprint_context","contextId":"frozen","d3ReviewPackage":{"landformPatches":[]},
             "catalogSnapshot":{"structureCatalog":{"semanticProfiles":[{"semanticProfileId":"author:mill",
                "functionTerms":["grain_processing"],"styleTerms":["stone"],"reviewState":"approved"}]},
              "templateCatalog":{"templates":[{"templateId":"pack:mill","rawSize":{"width":10,"height":12,"depth":14}}]},
              "referenceCatalog":{"structureRefs":[{"structureRef":"author:mill","templateCandidates":[{"templateId":"pack:mill"}]}],
                "fillPools":[{"poolRef":"pool:mill","structureRefs":["author:mill"]}],"landscapeFillProfiles":[{"fillProfileRef":"fill:field"}]}}}
            """).getAsJsonObject();
        JsonArray patches = context.getAsJsonObject("d3ReviewPackage").getAsJsonArray("landformPatches");
        for (int i = 0; i < 445; i++) {
            JsonObject patch = new JsonObject();
            patch.addProperty("landformPatchId", "patch_" + i);
            patch.addProperty("landformType", i % 2 == 0 ? "plain" : "valley");
            patch.addProperty("areaBlocks", i * 100);
            patches.add(patch);
        }
        String original = context.toString();
        JsonObject view = PlanningToolPresentation.present(context, directory);
        assertEquals(original, context.toString());
        assertEquals("frozen", view.get("contextId").getAsString());
        assertEquals(2, view.getAsJsonObject("d3ReviewPackage").getAsJsonArray("landformPatches").size());
        JsonObject catalog = view.getAsJsonObject("catalogSnapshot");
        assertEquals(context.getAsJsonObject("catalogSnapshot").get("structureCatalog"), catalog.get("structureCatalog"));
        assertEquals(context.getAsJsonObject("catalogSnapshot").getAsJsonObject("referenceCatalog").get("fillPools"),
                catalog.getAsJsonObject("referenceCatalog").get("fillPools"));
        assertTrue(catalog.getAsJsonObject("structureGeometry").has("author:mill"));
    }

    @Test void compiledGeometryUsesArtifactReferencesWhileQualityRemainsComplete() throws Exception {
        JsonObject source = JsonParser.parseString("""
                {"ok":true,"qualityReport":{"passed":true,"qualityFullySatisfied":false,
                  "hardBlocks":[],"warnings":["entrance unresolved"]},
                 "artifacts":{"cityGenerationCompileTrace":"run/trace.json"},
                 "cityGenerationCompileTrace":{"status":"compiled","compilationAcceptance":{"passed":true}}}
                """).getAsJsonObject();
        source.getAsJsonObject("cityGenerationCompileTrace").addProperty("selections", "x".repeat(100_000));
        for (String field : List.of("reservationMaskPlan", "structureMaterializationPlan", "landUseOwnerCompletion")) {
            JsonObject plan = new JsonObject();
            plan.addProperty("locked", true);
            plan.addProperty("geometry", "x".repeat(100_000));
            source.add(field, plan);
            source.getAsJsonObject("artifacts").addProperty(field, "run/" + field + ".json");
        }
        source.getAsJsonObject("landUseOwnerCompletion").addProperty("complete", false);
        source.getAsJsonObject("landUseOwnerCompletion").addProperty("plannedOwnerCount", 276);
        source.getAsJsonObject("landUseOwnerCompletion").addProperty("appliedAfterCount", 61);
        source.getAsJsonObject("landUseOwnerCompletion").add("failures",
                JsonParser.parseString("[{\"reasonCode\":\"CITY_LAND_USE_BLOCK_WRITE_FAILED\"}]"));
        String original = source.toString();
        JsonObject result = PlanningToolPresentation.present(source, directory);
        assertEquals(original, source.toString());
        assertEquals(source.get("qualityReport"), result.get("qualityReport"));
        assertTrue(result.getAsJsonObject("structureMaterializationPlan").get("locked").getAsBoolean());
        assertFalse(result.getAsJsonObject("landUseOwnerCompletion").get("complete").getAsBoolean());
        assertEquals(61, result.getAsJsonObject("landUseOwnerCompletion").get("appliedAfterCount").getAsInt());
        assertEquals(source.getAsJsonObject("landUseOwnerCompletion").get("failures"),
                result.getAsJsonObject("landUseOwnerCompletion").get("failures"));
        assertEquals("run/trace.json", result.getAsJsonObject("cityGenerationCompileTrace")
                .get("artifactPath").getAsString());
        assertFalse(result.getAsJsonObject("cityGenerationCompileTrace").has("selections"));
        source.remove("artifacts");
        assertThrows(java.io.IOException.class, () -> PlanningToolPresentation.present(source, directory));
    }

    @Test void oversizedViewsBecomeHostBlockersNotSuccessfulTruncatedResponses() throws Exception {
        JsonObject source = new JsonObject(); source.addProperty("authorData", "a".repeat(100_000));
        var control = new PlanningTurnControl((tool, args) -> PlanningToolPresentation.present(source, directory));
        control.execute("city_prepare_d4_blueprint_context", new JsonObject());
        assertTrue(control.finished());
        assertTrue(control.result(1).errorCode().contains("PLANNING_PRESENTATION_TOO_LARGE"));
    }

    @Test void capturedLiveContextFitsHermesWithoutLosingAnyAuthorProfile() throws Exception {
        String fixture = System.getenv("GEOMANTIA_PRESENTATION_FIXTURE");
        org.junit.jupiter.api.Assumptions.assumeTrue(fixture != null && !fixture.isBlank());
        JsonObject context = JsonParser.parseString(Files.readString(Path.of(fixture))).getAsJsonObject();
        JsonObject response = new JsonObject(); response.addProperty("ok", true);
        response.add("cityBlueprintContext", context);
        response.addProperty("additionalEnvelopeHeadroom", "artifact_path_budget_metadata".repeat(200));
        JsonObject compact = PlanningToolPresentation.compact(context).getAsJsonObject();
        compact.entrySet().forEach(entry -> System.out.println(entry.getKey() + " chars=" + entry.getValue().toString().length()));
        JsonObject view = PlanningToolPresentation.present(response, directory);
        System.out.println("Live context decision chars=" + view.toString().length());
        JsonObject expectedCatalog = context.getAsJsonObject("catalogSnapshot").getAsJsonObject("structureCatalog").deepCopy();
        expectedCatalog.getAsJsonArray("semanticProfiles").forEach(profile -> profile.getAsJsonObject().remove("sourceProfileRef"));
        assertEquals(expectedCatalog,
                view.getAsJsonObject("cityBlueprintContext").getAsJsonObject("catalogSnapshot").get("structureCatalog"));
        assertTrue(view.toString().length() < 90_000);
    }

    @Test void rejectsModelSuppliedCatalogsBeforeReadingAnyFiles() {
        JsonObject input = new JsonObject(); input.add("templateCatalogSource", new JsonObject());
        var error = assertThrows(IllegalArgumentException.class,
                () -> new ManagedCityPlanningSources(directory).bindDesignRequest(input));
        assertTrue(error.getMessage().contains("PLANNING_SOURCE_HOST_OWNED"));
    }

    @Test void ambiguousAuthorBundlesCannotBeChosenByModificationTime() throws Exception {
        for (String name : List.of("a", "b")) {
            Path bundle = directory.resolve("config/structureTemplate/terrasense/" + name);
            Files.createDirectories(bundle);
            for (String file : List.of("TerraSenseStructureProfileSource.official.json", "template_catalog.json", "blueprint_reference_catalog.json")) Files.writeString(bundle.resolve(file), "{}");
        }
        assertTrue(assertThrows(java.io.IOException.class,
                () -> new ManagedCityPlanningSources(directory).resolve()).getMessage().contains("AMBIGUOUS"));
    }

    @Test void stopsImmediatelyAfterAcceptedBlueprintOrHostFailure() throws Exception {
        var accepted = new PlanningTurnControl((tool,args) -> JsonParser.parseString("{\"ok\":true}"));
        accepted.execute("city_submit_d4_blueprint", new JsonObject());
        assertTrue(accepted.finished()); assertTrue(accepted.result(1).success());
        assertThrows(IllegalStateException.class, () -> accepted.execute("city_plan_d3", new JsonObject()));
        var blocked = new PlanningTurnControl((tool,args) -> JsonParser.parseString("{\"ok\":false,\"error\":\"CITY_TEMPLATE_CONTENT_PREFLIGHT_FAILED\"}"));
        blocked.execute("city_prepare_d4_blueprint_context", new JsonObject());
        assertTrue(blocked.finished()); assertFalse(blocked.result(1).success());
    }

    @Test void identicalValidationRejectionCannotLoopForeverButOneFailureCanBeRevised() throws Exception {
        var control = new PlanningTurnControl((tool,args) -> JsonParser.parseString("{\"ok\":false,\"reasonCode\":\"INVALID_DESIGN\"}"));
        control.execute("city_submit_d4_blueprint", new JsonObject()); assertFalse(control.finished());
        control.execute("city_submit_d4_blueprint", new JsonObject()); assertFalse(control.finished());
        control.execute("city_submit_d4_blueprint", new JsonObject()); assertTrue(control.finished());
        assertTrue(control.result(3).errorCode().startsWith("PLANNING_REPEATED_REJECTION"));
    }

    @Test void hermesInitialMessageContainsActualImages() throws Exception {
        Path image = directory.resolve("preview.png"); Files.write(image, new byte[]{1,2,3});
        JsonArray content = HermesAgentClient.promptContent(new JsonObject(), List.of(image));
        assertEquals("data:image/png;base64,AQID", content.get(1).getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString());
    }

    @Test void hermesInitialImagesRespectSessionBodyLimitAndExplainMissingEvidence() throws Exception {
        Path preview = directory.resolve("large-preview.png"); Files.write(preview, new byte[6_000_001]);
        JsonArray content = HermesAgentClient.promptContent(new JsonObject(), List.of(preview));
        assertEquals(1, content.size());
        assertTrue(content.get(0).getAsJsonObject().get("text").getAsString().contains("previewWarnings"));
        assertTrue(content.toString().length() < 10_000_000);
    }

    @Test void sidecarBridgeUsesHostExecutorAndChecksCapabilityAndWhitelist() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var bridge = new ProviderToolBridge()) {
            bridge.bind(List.of("design"), (tool,args) -> { calls.incrementAndGet(); return new JsonPrimitive("{\"ok\":true}"); });
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder(URI.create(bridge.url())).header("X-Geomantia-Bridge-Key", bridge.token())
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"design\",\"arguments\":{}}"));
            assertEquals(200, client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(1, calls.get()); bridge.unbind();
            assertEquals(403, client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(1, calls.get());
        }
    }
}
