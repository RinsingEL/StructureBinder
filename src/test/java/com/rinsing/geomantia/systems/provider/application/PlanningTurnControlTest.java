package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanningTurnControlTest {
    @Test void retargetFinishesTurnSoNextTaskPreparesFreshContinentCandidates() throws Exception {
        var control = new PlanningTurnControl((tool, args) -> JsonParser.parseString("{ok:true,status:'completed'}"));
        control.execute("realm_t2_retarget", new JsonObject());
        assertTrue(control.finished());
        assertTrue(PlanningStepPolicy.toolsFor(ProviderPlanningDiscovery.Stage.T2).contains("realm_t2_retarget"));
        assertFalse(PlanningStepPolicy.toolsFor(ProviderPlanningDiscovery.Stage.T4).contains("realm_t2_retarget"));
    }
    @Test void savedStageProgressSurvivesAProviderTurnWithoutNewGeometry() throws Exception {
        var initial=com.google.gson.JsonParser.parseString("{d4Workflow:{stage:'DISTRICTS',revision:1}}").getAsJsonObject();
        var control=new PlanningTurnControl((tool,args)->com.google.gson.JsonParser.parseString("{ok:true,designInProgress:true,d4Workflow:{stage:'DISTRICTS',revision:2}}"),initial);
        control.execute("city_d4_district",new JsonObject());
        assertTrue(control.permitsDesignContinuation());
        assertFalse(control.finished());
        var unchanged=new PlanningTurnControl((tool,args)->com.google.gson.JsonParser.parseString("{ok:true,designInProgress:true,d4Workflow:{stage:'DISTRICTS',revision:1}}"),initial);
        unchanged.execute("city_d4_district",new JsonObject());
        assertFalse(unchanged.permitsDesignContinuation());
    }

    @Test void completedReviewAllowsResumeButRepeatedEvidenceIsNotNewProgress() throws Exception {
        JsonObject initial = JsonParser.parseString("{designReviewWorkflow:{groupAssessments:{}}}").getAsJsonObject();
        JsonObject completed = JsonParser.parseString("{ok:true,designInProgress:true,designReviewWorkflow:{groupAssessments:{court:'retain'}}}").getAsJsonObject();
        var changed = new PlanningTurnControl((tool, input) -> completed, initial);
        changed.execute("city_submit_d4_blueprint", new JsonObject());
        assertTrue(changed.permitsDesignContinuation());
        assertFalse(changed.finished());
        var repeated = new PlanningTurnControl((tool, input) -> completed, completed);
        repeated.execute("city_submit_d4_blueprint", new JsonObject());
        assertFalse(repeated.permitsDesignContinuation());
        assertFalse(repeated.finished());
    }
    @Test void imageRequestsAssessmentsAndPendingReviewDoNotCommitOrStopTheTurn() throws Exception {
        var control = new PlanningTurnControl((tool, input) -> JsonParser.parseString(
                "{ok:true,designInProgress:true,designReviewWorkflow:{readyForFinal:false}}"));
        for (int i = 0; i < 12; i++) {
            control.execute("city_submit_d4_blueprint", new JsonObject());
            assertFalse(control.finished());
        }
    }
    @Test void intentAndMaterialSearchContinueUntilRenderedDraftIsAvailable() throws Exception {
        int[] calls = {0};
        var control = new PlanningTurnControl((tool, input) -> JsonParser.parseString(++calls[0] < 3
                ? "{ok:true,designInProgress:true,designSession:{groups:[]}}"
                : "{ok:true,designInProgress:true,designSession:{groups:[]},revisionEvidence:{baseDraftHash:'new'}}"));
        control.execute("city_submit_d4_blueprint", new JsonObject()); assertFalse(control.finished());
        control.execute("city_submit_d4_blueprint", new JsonObject()); assertFalse(control.finished());
        control.execute("city_submit_d4_blueprint", new JsonObject()); assertTrue(control.finished());
        assertTrue(control.permitsDesignContinuation());
    }
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
