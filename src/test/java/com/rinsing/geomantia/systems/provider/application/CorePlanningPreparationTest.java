package com.rinsing.geomantia.systems.provider.application;
import com.rinsing.geomantia.systems.provider.application.*;
import com.rinsing.geomantia.systems.provider.application.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorePlanningPreparationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void t2DecisionIsCommittedByHostAndEndsTheTurn() throws Exception {
        List<String> calls = new ArrayList<>();
        PlanningToolExecutor gateway = (tool, args) -> {
            calls.add(tool);
            JsonObject result = new JsonObject(); result.addProperty("ok", true);
            if (tool.equals("patch_explorer_select_candidate")) result.addProperty("patchSelectionRef", "psel_frozen");
            else {
                assertEquals("psel_frozen", args.get("patchSelectionRef").getAsString());
                assertEquals("authored evidence", args.get("reason").getAsString());
            }
            return result;
        };
        var control = new PlanningTurnControl((tool, args) -> PreparedRealmDesignTurn.execute(
                ProviderPlanningDiscovery.Stage.T2, gateway, tool, args));
        JsonObject args = new JsonObject(); args.addProperty("selectionReason", "authored evidence");
        control.execute("patch_explorer_select_candidate", args);
        assertTrue(control.finished());
        assertEquals(List.of("patch_explorer_select_candidate", "realm_t2_select_coordinate"), calls);
    }

    @Test
    void failedCandidateFreezeNeverCommitsT2() throws Exception {
        List<String> calls = new ArrayList<>();
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> PreparedRealmDesignTurn.execute(
                ProviderPlanningDiscovery.Stage.T2, (tool, args) -> {
                    calls.add(tool); JsonObject result = new JsonObject(); result.addProperty("ok", false);
                    result.addProperty("reasonCode", "INVALID_CANDIDATE"); return result;
                }, "patch_explorer_select_candidate", new JsonObject()));
        assertEquals(List.of("patch_explorer_select_candidate"), calls);
    }

    @Test
    void t4CandidateChoicePreservesFunctionsAndCreatesNoExtraCity() throws Exception {
        List<String> calls = new ArrayList<>();
        JsonObject args = com.google.gson.JsonParser.parseString("{planningSessionId:'plan',sessionId:'explorer',candidateId:'PLAIN-01',"
                + "selectionReason:'capacity',coreFunctions:['author_role']}").getAsJsonObject();
        PreparedRealmDesignTurn.execute(ProviderPlanningDiscovery.Stage.T4, (tool, request) -> {
            calls.add(tool);
            JsonObject result = new JsonObject(); result.addProperty("ok", true);
            if (tool.equals("patch_explorer_select_candidate")) result.addProperty("patchSelectionRef", "psel_frozen");
            else {
                assertFalse(request.has("candidateId"));
                assertEquals(args.get("coreFunctions"), request.get("coreFunctions"));
                assertEquals("psel_frozen", request.get("patchSelectionRef").getAsString());
            }
            return result;
        }, "realm_t4_patch_planning_select_capital", args);
        assertEquals(List.of("patch_explorer_select_candidate", "realm_t4_patch_planning_select_capital"), calls);
        assertTrue(args.has("candidateId"));
    }

    @Test
    void hostPreparesCanonicalContextAndActualImagesBeforeWakingDesigner() throws Exception {
        Path image = temporaryDirectory.resolve("map.png");
        java.nio.file.Files.write(image, new byte[]{1, 2, 3});
        JsonObject context = new JsonObject(); context.addProperty("contextId", "frozen");
        JsonObject d3 = new JsonObject(); d3.addProperty("reviewMapImage", "map.png");
        context.add("d3ReviewPackage", d3);
        JsonObject output = new JsonObject(); output.addProperty("ok", true); output.add("cityBlueprintContext", context);
        output.addProperty("remainingFailureCount", 2);
        output.add("d4Workflow", com.google.gson.JsonParser.parseString("{\"nextAction\":\"city_d4_overview\",\"instruction\":\"submit overview\"}"));
        JsonObject queue = new JsonObject(); queue.addProperty("remainingFailureCount", 3);
        List<String> calls = new ArrayList<>();
        var input = PreparedCityDesignTurn.prepare(queue, (tool, args) -> { calls.add(tool); return output; }, temporaryDirectory);
        assertEquals(List.of("city_prepare_d4_blueprint_context"), calls);
        assertEquals("frozen", input.state().getAsJsonObject("preparedBlueprintContext").get("contextId").getAsString());
        assertEquals(2, input.state().get("remainingFailureCount").getAsInt());
        assertEquals(List.of(image), input.images());
        assertFalse(queue.has("preparedBlueprintContext"));
        assertFalse(PreparedCityDesignTurn.TOOLS.contains("city_design_queue_status"));
        assertFalse(PreparedCityDesignTurn.TOOLS.contains("city_prepare_d4_blueprint_context"));
        assertTrue(PreparedCityDesignTurn.TOOLS.contains("city_d4_overview"));
        assertFalse(PreparedCityDesignTurn.TOOLS.contains("city_submit_d4_blueprint"));
    }

    @Test
    void failedPreparationDoesNotWakeDesignerWithMissingAuthorData() {
        JsonObject output = new JsonObject(); output.addProperty("ok", false);
        output.addProperty("error", "PLANNING_PRESENTATION_TOO_LARGE");
        var error = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> PreparedCityDesignTurn.prepare(new JsonObject(), (tool, args) -> output, temporaryDirectory));
        assertEquals("PLANNING_PRESENTATION_TOO_LARGE", error.getMessage());
    }


}
