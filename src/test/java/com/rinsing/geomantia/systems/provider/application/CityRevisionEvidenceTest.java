package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class CityRevisionEvidenceTest {
    @TempDir Path root;

    @Test void firstDesignNeedsNoOldArtifacts() throws Exception {
        assertNull(CityRevisionEvidence.load(root, new JsonObject(), new JsonObject()));
    }

    @Test void revisionIncludesExactBlueprintLocalConflictsAndBudgetWithoutDenseGeometry() throws Exception {
        Path steps = root.resolve("run_1/city_test_runs/city_1/steps");
        Files.createDirectories(steps.resolve("blueprint"));
        Files.createDirectories(steps.resolve("d4"));
        String blueprint = "{\"cityId\":\"city_1\",\"groups\":[{\"groupId\":\"market\"}]}";
        Files.writeString(steps.resolve("blueprint/city_blueprint.json"), blueprint);
        JsonObject accepted = json("{\"cityId\":\"city_1\",\"contextId\":\"frozen\",\"status\":\"accepted\"}");
        accepted.addProperty("cityBlueprintHash", "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(blueprint.getBytes(StandardCharsets.UTF_8))));
        Files.writeString(steps.resolve("blueprint/city_blueprint_submission_trace.json"), accepted.toString());
        Files.writeString(steps.resolve("d4/city_generation_compile_trace.json"),
                "{\"cityId\":\"city_1\",\"groupResults\":[{\"groupId\":\"market\",\"stopReason\":\"TERRAIN_UNFIT\",\"cells\":[1,2]}]}");
        Files.writeString(steps.resolve("d4/quality_report.json"), "{\"passed\":false,\"hardBlocks\":[\"ROAD_OVERLAPS_STRUCTURE:house\"]}");
        JsonObject context = json("{\"runId\":\"run_1\",\"cityId\":\"city_1\",\"contextId\":\"frozen\"}");
        JsonObject budget = json("{\"failureCount\":1,\"remainingFailureCount\":4}");
        JsonObject result = CityRevisionEvidence.load(root, context, budget);
        assertEquals(json(blueprint), result.get("previousBlueprint"));
        assertEquals(budget, result.get("failureBudget"));
        assertTrue(result.toString().contains("ROAD_OVERLAPS_STRUCTURE:house"));
        assertTrue(result.toString().contains("TERRAIN_UNFIT"));
        assertFalse(result.toString().contains("\"cells\""));
        Files.writeString(steps.resolve("blueprint/city_blueprint.json"), "{}");
        assertThrows(java.io.IOException.class, () -> CityRevisionEvidence.load(root, context, budget));
    }

    @Test void capturedRealFailurePreservesAllReportedHardBlocks() throws Exception {
        String fixture = System.getenv("GEOMANTIA_PRESENTATION_FIXTURE");
        Assumptions.assumeTrue(fixture != null && Files.isRegularFile(Path.of(fixture)));
        Path contextFile = Path.of(fixture);
        Path blueprintDir = contextFile.getParent();
        JsonObject context = json(Files.readString(contextFile));
        JsonObject budget = json(Files.readString(blueprintDir.resolve("city_blueprint_failure_budget.json")));
        Assumptions.assumeTrue(budget.get("failureCount").getAsInt() > 0);
        Path debugRoot = blueprintDir.getParent().getParent().getParent().getParent().getParent();
        JsonObject evidence = CityRevisionEvidence.load(debugRoot, context, budget);
        assertEquals(json(Files.readString(blueprintDir.getParent().resolve("d4/quality_report.json"))).get("hardBlocks"),
                evidence.getAsJsonObject("quality").get("hardBlocks"));
        assertTrue(evidence.has("compiledPreview"));
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
}
