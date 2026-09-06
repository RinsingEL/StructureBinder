package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class QadirCompilationReplayTest {
    @TempDir Path temporary;
    @Test void replayAcceptedDesignWithoutMutatingTheWorld() throws Exception {
        String fixture = System.getenv("GEOMANTIA_QADIR_RUN");
        org.junit.jupiter.api.Assumptions.assumeTrue(fixture != null);
        Path run = Path.of(fixture);
        String city = "city_realm_qadir_capital";
        Path source = run.resolve("city_test_runs").resolve(city);
        Path target = temporary.resolve(run.getFileName()).resolve("city_test_runs").resolve(city);
        for (String step : new String[]{"blueprint", "d3", "land_use"}) {
            Path directory = source.resolve("steps").resolve(step);
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).toList()) {
                    Path destination = target.resolve(source.relativize(file));
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination);
                }
            }
        }
        long started = System.nanoTime();
        var result = new CityBlueprintCompilerService().compile(temporary, run.getFileName().toString(), city);
        JsonObject trace = result.compileTrace();
        System.out.println("QADIR_REPLAY seconds=" + (System.nanoTime() - started) / 1e9 + " ok=" + result.ok() + " reason=" + result.reasonCode());
        if (!result.ok()) {
            JsonObject response = new JsonObject(); response.addProperty("ok", false);
            response.add("cityGenerationCompileTrace", trace);
            System.out.println("QADIR_FAILURE " + CityWorkflowStepRunner.compactFailureSummary(response));
        }
        for (JsonElement event : trace.getAsJsonArray("selections")) {
            String status = event.getAsJsonObject().has("status") ? event.getAsJsonObject().get("status").getAsString() : "";
            assertNotEquals("skipped_runtime_gap_member", status);
        }
        if (trace.has("compilationAcceptance")) {
            JsonObject acceptance = trace.getAsJsonObject("compilationAcceptance");
            System.out.println("QADIR_ACCEPTANCE " + acceptance.get("hardBlocks"));
            assertTrue(acceptance.get("structureGraphConnected").getAsBoolean());
            assertTrue(acceptance.get("allRequiredContentPresent").getAsBoolean());
        }
        var committed = trace.getAsJsonArray("selections").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(event -> event.has("status") && "committed".equals(event.get("status").getAsString()))
                .map(event -> event.get("structureRef").getAsString()).toList();
        assertTrue(committed.contains("trek:village/desert/houses/desert_library_1"));
        assertTrue(committed.contains("trek:village/desert/houses/desert_cartographer_1"));
        if (!result.ok()) {
            assertEquals("CITY_BLUEPRINT_REQUIRED_STRUCTURE_NO_LEGAL_PLACEMENT", result.reasonCode());
            assertFalse(CityBlueprintFailureRouting.isProgramFailure(result.reasonCode(), trace));
            assertFalse(trace.has("cityMainRoadPlan"), "Do not do optional road/fill work after required placement fails.");
        }
        JsonObject original = JsonParser.parseString(Files.readString(source.resolve("steps/d4/city_generation_compile_trace.json"))).getAsJsonObject();
        assertTrue(new CityTrafficConnectivity(original.getAsJsonObject("cityMainRoadPlan")).connected(
                java.util.Set.of("civic_core", "agri_belt", "market_district", "residential_quarter", "defense_picket")));
    }
}
