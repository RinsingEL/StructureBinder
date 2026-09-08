package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Optional replay against an isolated copy of real artifacts, never the live save. */
class CitySavedBlueprintReplayTest {
    @Test
    @EnabledIfEnvironmentVariable(named="GEOMANTIA_CITY_REPLAY", matches="true")
    void renderSavedReplay() throws Exception {
        Path root = Path.of("build/validation03-replay");
        JsonObject map = JsonParser.parseString(Files.readString(root.resolve("anchor-plan.json"))).getAsJsonObject();
        JsonObject context = JsonParser.parseString(Files.readString(root.resolve("realm_debug/provider_bd973b975f0bead3_r8192/city_test_runs/city_realm_qareth_capital/steps/blueprint/city_blueprint_context.json"))).getAsJsonObject();
        JsonObject d3 = context.getAsJsonObject("d3ReviewPackage"); map.add("grid",d3.get("grid"));
        JsonObject trace = JsonParser.parseString(Files.readString(root.resolve("compile-trace.json"))).getAsJsonObject();
        new com.rinsing.geomantia.systems.city.infrastructure.preview.CityStructureLandingPreviewRenderer()
                .renderD4(map,com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage.fromJson(d3),
                        trace.getAsJsonObject("landscapeCapacityReservationPlan"),
                        trace.getAsJsonObject("functionAreaFormationPlan"),root.resolve("preview"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named="GEOMANTIA_CITY_REPLAY", matches="true")
    void savedCapitalDraftCompilesWithCurrentRoadPlanner() throws Exception {
        Path root = Path.of("build/validation03-replay/realm_debug").toAbsolutePath();
        String run = "provider_bd973b975f0bead3_r8192";
        String city = "city_realm_qareth_capital";
        JsonObject draft = JsonParser.parseString(Files.readString(root.resolve(run)
                .resolve("city_test_runs/"+city+"/steps/blueprint/city_blueprint_draft.json"))).getAsJsonObject();
        var result = new CityBlueprintCompilerService().compileProposal(root,run,city,draft.getAsJsonObject("previousBlueprint"));
        Files.writeString(root.getParent().resolve("compile-trace.json"),result.compileTrace().toString());
        JsonObject outcome = new JsonObject(); outcome.addProperty("ok",result.ok());
        outcome.addProperty("reasonCode",result.reasonCode()); outcome.addProperty("message",result.message());
        Files.writeString(root.getParent().resolve("outcome.json"),outcome.toString());
        assertTrue(result.ok(),outcome.toString());
        Files.writeString(root.getParent().resolve("anchor-plan.json"),result.structureAnchorPlan().toString());
    }
}
