package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanningTurnControlTest {
    @Test void formatCorrectionsReachTenDespiteRepeatedError() throws Exception {
        int[] calls = {0};
        var control = new PlanningTurnControl((tool, input) -> {
            JsonObject failure = new JsonObject(); failure.addProperty("ok", false);
            failure.addProperty("error", "MISSING_FIELD");
            JsonObject budget = new JsonObject(); budget.addProperty("retryAllowed", ++calls[0] < 10);
            failure.add("formatRetryBudget", budget); return failure;
        });
        for (int i=1;i<=10;i++) {
            control.execute("city_submit_d4_blueprint", new JsonObject());
            assertEquals(i == 10, control.finished());
            assertEquals(i < 10, control.permitsDesignContinuation());
        }
        assertEquals("PLANNING_FORMAT_RETRIES_EXHAUSTED", control.result(10).errorCode());
    }
    @Test void unchangedRevisionAcrossTurnsIsNotProgress() throws Exception {
        JsonObject initial = JsonParser.parseString("{revisionEvidence:{baseDraftHash:'same'}}").getAsJsonObject();
        var control = new PlanningTurnControl((tool, input) -> JsonParser.parseString(
                "{ok:false,error:'ROAD_BLOCKED',revisionEvidence:{baseDraftHash:'same'}}"), initial);
        control.execute("city_submit_d4_blueprint", new JsonObject());
        assertFalse(control.permitsDesignContinuation());
    }
    @Test void validDraftYieldsForNewPreviewWithoutFinalizingCity() throws Exception {
        var control = new PlanningTurnControl((tool, input) -> JsonParser.parseString(
                "{ok:true,designInProgress:true,revisionEvidence:{baseDraftHash:'new'}}"));
        control.execute("city_submit_d4_blueprint", new JsonObject());
        assertTrue(control.finished());
        assertTrue(control.permitsDesignContinuation());
        assertTrue(control.result(1).success());
    }
}
