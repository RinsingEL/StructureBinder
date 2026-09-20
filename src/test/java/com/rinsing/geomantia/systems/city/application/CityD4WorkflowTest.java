package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityD4WorkflowTest {
    @TempDir Path dir;
    private int compiled;
    private boolean retainIntegration=true;
    private boolean empty=false;
    private JsonObject lastCity;
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private JsonObject state() throws Exception { return CityD4Workflow.status(dir,"ctx"); }
    private JsonObject call(String tool,JsonObject request) throws Exception {
        request.addProperty("d4Tool",tool); request.addProperty("workflowRevision",state().get("revision").getAsInt());
        return CityD4Workflow.submit(dir,"ctx","city",request,this::compile);
    }
    private JsonObject compile(JsonObject request) throws java.io.IOException {
        if(request.has("designReview")) return CityDesignReviewWorkflow.submitRequest(dir,"ctx",CityBlueprintDraft.current(dir,"ctx","city"),request);
        if(!request.has("cityBlueprint")) return json("{ok:true,designInProgress:true}");
        JsonObject city=request.getAsJsonObject("cityBlueprint").deepCopy(); city.addProperty("cityId","city"); lastCity=city;
        if("FINAL".equals(request.get("submissionMode").getAsString())) {
            JsonObject gate=CityDesignReviewWorkflow.finalGate(dir,"ctx",city);
            return gate==null?json("{ok:true}"):gate;
        }
        compiled++;
        JsonObject draft=CityBlueprintDraft.create(dir,"ctx",city,new JsonObject(),false);
        draft.addProperty("status","preview_valid");
        JsonObject previews=new JsonObject(); JsonArray anchors=new JsonArray();
        for(var group:city.getAsJsonArray("groups")) {
            String id=group.getAsJsonObject().get("groupId").getAsString();
            Path path=dir.resolve(id+".png"); Files.writeString(path,group.toString()); previews.addProperty(id,path.toString());
            if(!empty && (retainIntegration || !id.equals("link"))) { JsonObject a=new JsonObject();a.addProperty("placementGroupId",id);a.add("design",group.deepCopy());anchors.add(a); }
        }
        Path overview=dir.resolve("overview.png");Files.writeString(overview,city.toString());
        draft.addProperty("compiledPreview",overview.toString());draft.add("compiledGroupPreviews",previews);
        JsonObject layout=new JsonObject();layout.add("anchors",anchors);draft.add("compiledLayout",layout);
        Files.writeString(dir.resolve(CityBlueprintDraft.FILE),draft.toString());
        JsonObject response=json("{ok:true,designInProgress:true}"); response.add("revisionEvidence",CityBlueprintDraft.evidence(draft));return response;
    }
    private void overview() throws Exception {
        assertTrue(call("city_d4_overview",json("{overview:{citySettings:{outdoorPlan:{}},districts:[{groupId:'civic',role:'civic',intent:'court',preferredPatchRefs:['p']},{groupId:'market',role:'market',intent:'street',preferredPatchRefs:['p']}]}}")).get("ok").getAsBoolean());
    }
    private void design(String id) throws Exception {
        assertTrue(call("city_d4_district",json("{districtDesign:{groups:[{groupId:'"+id+"'}]}}")).get("ok").getAsBoolean());
    }
    private JsonObject currentRequest() throws Exception {
        JsonObject r=json("{assessment:'隔河两区尺度与朝向协调，具有整体性；各区功能主体保留。'}");
        r.add("baseDraftHash",CityBlueprintDraft.current(dir,"ctx","city").get("baseDraftHash"));return r;
    }
    private void districts() throws Exception { overview();design("a");design("b"); }
    private void mark() throws Exception {
        JsonObject r=currentRequest();r.add("districtDisposition",JsonParser.parseString("[{districtId:'civic',independent:false},{districtId:'market',independent:false}]"));
        JsonObject response=call("city_d4_mark",r);assertTrue(response.get("ok").getAsBoolean(),response.toString());
    }
    private JsonObject expansion(String district,String group) throws Exception {
        JsonObject r=currentRequest();r.addProperty("targetDistrictId",district);r.addProperty("expansionMode","ADJUST_ARRAY");
        r.addProperty("integrationIntent","向对岸市场扩大主体，保留河流与市场用途");r.add("protectedDistrictIds",new JsonArray());
        r.add("changes",json("{groups:[{groupId:'"+group+"',structureCount:9}]}"));return r;
    }
    @Test void successfulInitialAutomaticallyAdvancesWithoutReviewsAndSurvivesReload() throws Exception {
        overview();design("a");assertEquals("market",state().getAsJsonObject("currentDistrict").get("groupId").getAsString());
        assertFalse(call("city_d4_reopen",json("{districtId:'civic'}")).get("ok").getAsBoolean());
        assertFalse(call("city_d4_district_refine",json("{changes:{groups:[]}}")).get("ok").getAsBoolean());
        assertFalse(call("city_d4_overview",json("{overview:{}}")).get("ok").getAsBoolean());
        design("b");assertEquals("INTEGRATION",state().get("stage").getAsString());assertEquals(2,compiled);
        assertEquals("city_d4_mark",state().get("nextAction").getAsString());
    }
    @Test void whollyEmptyInitialCanRetryButValidInitialCannotBeReopened() throws Exception {
        overview();empty=true;JsonObject r=call("city_d4_district",json("{districtDesign:{groups:[{groupId:'a'}]}}"));
        assertTrue(r.get("initialDistrictEmpty").getAsBoolean());assertEquals("civic",state().getAsJsonObject("currentDistrict").get("groupId").getAsString());
        empty=false;design("a");assertEquals("market",state().getAsJsonObject("currentDistrict").get("groupId").getAsString());
    }
    @Test void initialMayContainEmptySubarraysWithoutRequiringTheirRepair() throws Exception {
        overview();retainIntegration=false;
        JsonObject r=call("city_d4_district",json("{districtDesign:{groups:[{groupId:'a'},{groupId:'link'}]}}"));
        assertTrue(r.get("ok").getAsBoolean());assertEquals("market",state().getAsJsonObject("currentDistrict").get("groupId").getAsString());
    }
    @Test void canFinalizeCoherentInitialAcrossRiverWithoutAnyExpansionOrLocalAssessment() throws Exception {
        districts();mark();JsonObject confirm=currentRequest();confirm.addProperty("functionsPreserved",true);
        JsonObject r=call("city_d4_finalize",confirm);assertTrue(r.get("ok").getAsBoolean(),r.toString());
        assertEquals("COMPLETE",state().get("stage").getAsString());assertEquals(2,compiled);
        assertEquals("city_post_d4_auto_compile_status", state().get("nextAction").getAsString());
        assertTrue(state().getAsJsonArray("availableActions").isEmpty());
    }
    @Test void cannotFinalizeWithoutFunctionPreservationOrCurrentOverview() throws Exception {
        districts();mark();JsonObject r=currentRequest();r.addProperty("functionsPreserved",false);
        assertFalse(call("city_d4_finalize",r).get("ok").getAsBoolean());
        r.addProperty("functionsPreserved",true);r.addProperty("baseDraftHash","stale");assertFalse(call("city_d4_finalize",r).get("ok").getAsBoolean());
    }
    @Test void mustKeepWorkingOnSameDistrictUntilItsCoherenceIsConfirmed() throws Exception {
        districts();mark();assertTrue(call("city_d4_integrate",expansion("civic","a")).get("ok").getAsBoolean());
        assertFalse(call("city_d4_integrate",expansion("market","b")).get("ok").getAsBoolean());
        JsonObject next=expansion("market","b");next.addProperty("previousExpansionComplete",true);
        assertTrue(call("city_d4_integrate",next).get("ok").getAsBoolean());assertEquals("market",state().get("activeExpansionDistrictId").getAsString());
    }
    @Test void isolatedDistrictRequiresExplicitPeripheralRoleAndCannotBeExpansionTarget() throws Exception {
        districts();JsonObject r=currentRequest();r.add("districtDisposition",JsonParser.parseString("[{districtId:'civic',independent:false},{districtId:'market',independent:true}]"));
        assertFalse(call("city_d4_mark",r).get("ok").getAsBoolean());
        JsonObject m=r.getAsJsonArray("districtDisposition").get(1).getAsJsonObject();m.addProperty("peripheralRole","SUBURBAN_INDUSTRY");m.addProperty("reason","郊区工业区应与居住主体分开");
        assertTrue(call("city_d4_mark",r).get("ok").getAsBoolean());assertFalse(call("city_d4_integrate",expansion("market","b")).get("ok").getAsBoolean());
    }
    @Test void rejectsUnscopedChangesBadProtectionAndRetiredActions() throws Exception {
        districts();mark();JsonObject r=expansion("civic","a");r.getAsJsonArray("protectedDistrictIds").add("unknown");
        assertFalse(call("city_d4_integrate",r).get("ok").getAsBoolean());
        r=expansion("civic","a");r.getAsJsonObject("changes").add("removeGroupIds",JsonParser.parseString("['a']"));
        assertFalse(call("city_d4_integrate",r).get("ok").getAsBoolean());
        r=expansion("civic","a");r.getAsJsonObject("changes").getAsJsonArray("groups").get(0).getAsJsonObject().addProperty("role","other");
        assertFalse(call("city_d4_integrate",r).get("ok").getAsBoolean());
        assertFalse(call("city_d4_complete",json("{}")).get("ok").getAsBoolean());
    }
    @Test void noDistrictCanOverwriteOtherGroupIdsOrReplayStaleRevision() throws Exception {
        overview();design("a");assertFalse(call("city_d4_district",json("{districtDesign:{groups:[{groupId:'a'}]}}")).get("ok").getAsBoolean());
        JsonObject r=json("{d4Tool:'city_d4_district',workflowRevision:0,districtDesign:{groups:[]}}");
        assertEquals("CITY_D4_REVISION_STALE",CityD4Workflow.submit(dir,"ctx","city",r,this::compile).get("reasonCode").getAsString());assertEquals(1,compiled);
    }
    @Test void nestedPartialUpdatesPreserveOmittedFieldsAndRejectDeletionOfIds() {
        JsonObject before=json("{groups:[{groupId:'a',structureCount:5,placementRelation:{kind:'BETWEEN_GROUPS',groupRefs:['x','y']}}]}");
        JsonObject after=CityD4Workflow.mergeChanges(before,json("{groups:[{groupId:'a',clearFields:['placementRelation']}] }"));
        assertFalse(after.getAsJsonArray("groups").get(0).getAsJsonObject().has("placementRelation"));
        assertEquals(5,after.getAsJsonArray("groups").get(0).getAsJsonObject().get("structureCount").getAsInt());
        assertThrows(IllegalArgumentException.class,()->CityD4Workflow.mergeChanges(before,json("{groups:[{groupId:'a',clearFields:['groupId']}]}")));
    }
}
