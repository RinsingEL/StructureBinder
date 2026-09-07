package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DesignFirstCapturedReplayTest {
    @TempDir Path root;
    @Test void unchangedFiveDistrictDesignCompilesFromCapturedTerrain() throws Exception {
        String fixture = System.getenv("GEOMANTIA_DESIGN_FIRST_REPLAY_RUN");
        String requestFile = System.getenv("GEOMANTIA_DESIGN_FIRST_REPLAY_REQUEST");
        Assumptions.assumeTrue(fixture != null && requestFile != null);
        Path run = Path.of(fixture);
        JsonObject request = JsonParser.parseString(Files.readString(Path.of(requestFile))).getAsJsonObject()
                .getAsJsonObject("params").getAsJsonObject("arguments");
        String city = request.get("citySeedId").getAsString();
        Path source = run.resolve("city_test_runs").resolve(city);
        Path target = root.resolve(run.getFileName()).resolve("city_test_runs").resolve(city);
        for (String step : new String[]{"blueprint", "d3", "land_use"}) {
            try (var files = Files.list(source.resolve("steps").resolve(step))) {
                for (Path file : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).toList()) {
                    Path destination = target.resolve(source.relativize(file));
                    Files.createDirectories(destination.getParent()); Files.copy(file, destination);
                }
            }
        }
        var context = JsonParser.parseString(Files.readString(target.resolve("steps/blueprint/city_blueprint_context.json"))).getAsJsonObject();
        var design = CityBlueprintDesignInput.bind(request.getAsJsonObject("cityBlueprint"), context, false);
        long start = System.nanoTime();
        var result = new CityBlueprintCompilerService().compileProposal(root, run.getFileName().toString(), city, design);
        System.out.println("DESIGN_FIRST_REPLAY seconds=" + (System.nanoTime()-start)/1e9 + " ok=" + result.ok() + " reason=" + result.reasonCode());
        if (!result.ok()) {
            System.out.println(CityDesignFailureFeedback.summarize(design, result.compileTrace(), result.reasonCode()));
            System.out.println(result.compileTrace().get("compilationAcceptance"));
        }
        assertTrue(result.ok(), result.reasonCode() + ": " + result.message());
    }
}
