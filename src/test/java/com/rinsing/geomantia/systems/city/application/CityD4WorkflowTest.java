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
            if(retainIntegration || !id.equals("link")) { JsonObject a=new JsonObject();a.addProperty("placementGroupId",id);anchors.add(a); }
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
    private void review(String tool,String... ids) throws Exception {
        JsonObject r=new JsonObject(); r.add("baseDraftHash",CityBlueprintDraft.current(dir,"ctx","city").get("baseDraftHash"));
        if(ids.length==0) r.addProperty("overview",true); else {JsonArray a=new JsonArray();Arrays.stream(ids).forEach(a::add);r.add("groupIds",a);}
        JsonObject q=new JsonObject();q.add("designReview",r);
        assertTrue(call(tool,q.deepCopy()).has("requestedPreviews"));
        r.addProperty("assessment","该空间符合意图，向外街区衔接两区，空段缩短。");
        assertTrue(call(tool,q).get("ok").getAsBoolean());
    }
    private void districts() throws Exception {
        overview(); design("a");review("city_d4_district","a"); assertTrue(call("city_d4_district",json("{complete:true}")).get("ok").getAsBoolean());
        design("b");review("city_d4_district","b"); assertTrue(call("city_d4_district",json("{complete:true}")).get("ok").getAsBoolean());
    }
    private JsonObject integration() { return json("{integrationIntent:'连接行政区和市场',integrationDesign:{groups:[{groupId:'link',placementRelation:{kind:'BETWEEN_GROUPS',groupRefs:['a','b']}}]}}"); }

    @Test void persistedFourStagesMergeDistrictsAndFinalizeOnlyReviewedIntegration() throws Exception {
        districts(); assertEquals("INTEGRATION",state().get("stage").getAsString());
        assertEquals(2,lastCity.getAsJsonArray("groups").size());
        assertFalse(call("city_d4_integrate",integration()).get("ok").getAsBoolean());
        review("city_d4_integrate");assertTrue(call("city_d4_integrate",integration()).get("ok").getAsBoolean());
        assertEquals(3,lastCity.getAsJsonArray("groups").size());
        assertFalse(call("city_d4_integrate",json("{complete:true}")).get("ok").getAsBoolean());
        review("city_d4_integrate","link");review("city_d4_integrate");
        assertTrue(call("city_d4_integrate",json("{complete:true}")).get("ok").getAsBoolean());
        assertEquals("FINAL",state().get("stage").getAsString());
        JsonObject confirm=new JsonObject();confirm.add("baseDraftHash",CityBlueprintDraft.current(dir,"ctx","city").get("baseDraftHash"));
        assertTrue(call("city_d4_finalize",confirm).get("ok").getAsBoolean());
        assertEquals("COMPLETE",state().get("stage").getAsString());assertEquals(3,compiled);
    }
    @Test void rejectsWholeCitySkippingDistrictReviewAndStaleReplay() throws Exception {
        JsonObject old=json("{cityBlueprint:{groups:[]}}");
        assertFalse(CityD4Workflow.submit(dir,"ctx","city",old,this::compile).get("ok").getAsBoolean());
        overview();design("a");
        assertFalse(call("city_d4_finalize",json("{baseDraftHash:'fake'}")).get("ok").getAsBoolean());
        assertFalse(call("city_d4_district",json("{complete:true}")).get("ok").getAsBoolean());
        assertEquals("civic",state().getAsJsonObject("currentDistrict").get("groupId").getAsString());
        JsonObject stale=json("{d4Tool:'city_d4_district',workflowRevision:0,complete:true}");
        assertEquals("CITY_D4_REVISION_STALE",CityD4Workflow.submit(dir,"ctx","city",stale,this::compile).get("reasonCode").getAsString());
        assertEquals(1,compiled);
    }
    @Test void unrelatedOrAllSkippedAdditionCannotSatisfyIntegration() throws Exception {
        districts();review("city_d4_integrate");
        assertFalse(call("city_d4_integrate",json("{integrationIntent:'random',integrationDesign:{groups:[{groupId:'other'}]}}")).get("ok").getAsBoolean());
        retainIntegration=false;call("city_d4_integrate",integration());review("city_d4_integrate","link");review("city_d4_integrate");
        assertFalse(call("city_d4_integrate",json("{complete:true}")).get("ok").getAsBoolean());assertEquals("INTEGRATION",state().get("stage").getAsString());
    }
    @Test void reopeningPreservesOtherDistrictsAndInvalidatesFinalReadiness() throws Exception {
        districts();
        assertTrue(call("city_d4_overview",json("{reopenDistrictId:'civic'}")).get("ok").getAsBoolean());
        assertEquals(2,state().getAsJsonArray("savedDistricts").size());
        assertFalse(call("city_d4_district",json("{complete:true}")).get("ok").getAsBoolean());
        design("a2");assertEquals(2,lastCity.getAsJsonArray("groups").size());
        assertEquals("b",lastCity.getAsJsonArray("groups").get(1).getAsJsonObject().get("groupId").getAsString());
    }
    @Test void districtsCannotOverwriteOtherDistrictGroupIds() throws Exception {
        overview();design("a");review("city_d4_district","a");call("city_d4_district",json("{complete:true}"));
        assertFalse(call("city_d4_district",json("{districtDesign:{groups:[{groupId:'a'}]}}")).get("ok").getAsBoolean());assertEquals(1,compiled);
    }
}
