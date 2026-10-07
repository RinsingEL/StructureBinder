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
    private JsonObject correctedOverview() throws Exception {
        JsonObject saved=CityD4Workflow.load(dir,"ctx");
        JsonObject request=json("{d4Tool:'city_d4_overview'}"), overview=new JsonObject();
        overview.add("citySettings",saved.get("citySettings").deepCopy());
        overview.add("districts",saved.get("districts").deepCopy());
        overview.getAsJsonObject("citySettings").getAsJsonObject("surfaceMaterials")
                .getAsJsonObject("defaults").addProperty("roadSurface","minecraft:oak_slab");
        request.add("overview",overview);
        request.add("designAnswers",saved.get("overviewAnswers").deepCopy());
        request.getAsJsonObject("designAnswers").addProperty("roadSurface","minecraft:oak_slab");
        request.add("workflowRevision",saved.get("revision"));
        return request;
    }

    @Test void lockedInvalidDefaultsCanBeCorrectedWithoutAnyDraft() throws Exception {
        overview();
        JsonObject saved=CityD4Workflow.load(dir,"ctx");
        saved.getAsJsonObject("citySettings").getAsJsonObject("surfaceMaterials")
                .getAsJsonObject("defaults").addProperty("roadSurface","minecraft:gravel");
        saved.getAsJsonObject("overviewAnswers").addProperty("roadSurface","minecraft:gravel");
        saved.add("districtDisposition",JsonParser.parseString("[{districtId:'civic',independent:false},{districtId:'market',independent:false}]"));
        Files.writeString(dir.resolve("city_d4_workflow.json"),saved.toString());
        assertNull(CityBlueprintDraft.current(dir,"ctx","city"));
        assertTrue(state().getAsJsonArray("availableActions").contains(new JsonPrimitive("city_d4_overview")));
        JsonObject request=correctedOverview();
        JsonObject result=CityD4Workflow.submit(dir,"ctx","city",request,this::compile);
        assertTrue(result.get("ok").getAsBoolean(),result.toString());
        assertNull(CityBlueprintDraft.current(dir,"ctx","city"));
        JsonObject corrected=CityD4Workflow.load(dir,"ctx");
        assertEquals("minecraft:oak_slab",corrected.getAsJsonObject("citySettings").getAsJsonObject("surfaceMaterials").getAsJsonObject("defaults").get("roadSurface").getAsString());
        assertEquals(saved.get("districts"),corrected.get("districts"));
        assertEquals(0,corrected.getAsJsonObject("bodies").size());
        assertFalse(corrected.has("districtDisposition"));
        assertEquals("DISTRICTS",corrected.get("stage").getAsString());
        assertEquals("civic",state().getAsJsonObject("currentDistrict").get("groupId").getAsString());
        assertEquals("CITY_D4_REVISION_STALE",CityD4Workflow.submit(dir,"ctx","city",request,this::compile).get("reasonCode").getAsString());
        JsonObject district=json("{d4Tool:'city_d4_district',districtDesign:{groups:[{groupId:'a',expansionPolicy:{allowRelationConnection:true}}]},designAnswers:[{groupId:'a',roadConnected:true,foundation:false,noFoundationReason:'fixture',ground:'minecraft:stone',roadSurface:'minecraft:oak_slab',styles:['test']}]}");
        addCore(district.getAsJsonObject("districtDesign"));
        district.add("workflowRevision",corrected.get("revision"));
        result=CityD4Workflow.submit(dir,"ctx","city",district,this::compile);
        assertTrue(result.get("ok").getAsBoolean(),result.toString());
        assertEquals("minecraft:oak_slab",lastCity.getAsJsonObject("surfaceMaterials").getAsJsonObject("defaults").get("roadSurface").getAsString());
        assertEquals("market",state().getAsJsonObject("currentDistrict").get("groupId").getAsString());
    }

    @Test void invalidOverviewCorrectionDoesNotOverwriteSavedSettings() throws Exception {
        overview();
        JsonObject before=CityD4Workflow.load(dir,"ctx"), request=correctedOverview();
        request.getAsJsonObject("designAnswers").addProperty("roadSurface","minecraft:gravel");
        assertFalse(CityD4Workflow.submit(dir,"ctx","city",request,this::compile).get("ok").getAsBoolean());
        assertEquals(before,CityD4Workflow.load(dir,"ctx"));
    }

    @Test void overviewCorrectionCannotReopenAnEffectiveInitialDistrict() throws Exception {
        overview();design("a");
        JsonObject before=CityD4Workflow.load(dir,"ctx");
        String draft=Files.readString(dir.resolve(CityBlueprintDraft.FILE));
        assertFalse(state().getAsJsonArray("availableActions").contains(new JsonPrimitive("city_d4_overview")));
        assertFalse(CityD4Workflow.submit(dir,"ctx","city",correctedOverview(),this::compile).get("ok").getAsBoolean());
        assertEquals(before,CityD4Workflow.load(dir,"ctx"));
        assertEquals(draft,Files.readString(dir.resolve(CityBlueprintDraft.FILE)));
    }

    @Test void legacySavedDesignCanSupplyMandatoryAnswersWithoutRestartingDistricts() throws Exception {
        districts();
        JsonObject saved=CityD4Workflow.load(dir,"ctx");
        JsonObject request=new JsonObject();request.add("overviewAnswers",saved.remove("overviewAnswers"));request.add("districtAnswers",saved.remove("districtAnswers"));
        saved.getAsJsonObject("citySettings").remove("surfaceMaterials");
        Files.writeString(dir.resolve("city_d4_workflow.json"),saved.toString());
        assertEquals("city_d4_answers",state().get("nextAction").getAsString());
        request.add("baseDraftHash",CityBlueprintDraft.current(dir,"ctx","city").get("baseDraftHash"));
        JsonObject result=call("city_d4_answers",request);
        assertTrue(result.get("ok").getAsBoolean(),result.toString());
        assertEquals("INTEGRATION",state().get("stage").getAsString());
        assertEquals(2,lastCity.getAsJsonArray("groups").size());
    }

    @Test void unrelatedDistrictAdjustmentDoesNotConsumeCoreReworkBudget() throws Exception {
        districts();mark();
        JsonObject draft=CityBlueprintDraft.current(dir,"ctx","city");
        draft.getAsJsonObject("compiledLayout").add("designReview",json("{isolatedCoreGroupIds:['a']}"));
        Files.writeString(dir.resolve(CityBlueprintDraft.FILE),draft.toString());
        assertTrue(call("city_d4_integrate",expansion("market","b")).get("ok").getAsBoolean());
        assertEquals(0,CityDesignReviewWorkflow.status(dir,"ctx",CityBlueprintDraft.current(dir,"ctx","city"))
                .get("coreReworkCount").getAsInt());
    }

    @Test void coreRepairChangesSelectionOnlyForADistrictWithAnActualIsolatedCore() throws Exception {
        districts();mark();
        JsonObject repair=expansion("civic","a");repair.addProperty("expansionMode","REPAIR_CORE");
        repair.getAsJsonObject("changes").getAsJsonArray("groups").get(0).getAsJsonObject()
                .add("requiredStructureRefs",JsonParser.parseString("['core','small_shop']"));
        assertFalse(call("city_d4_integrate",repair).get("ok").getAsBoolean());
        JsonObject draft=CityBlueprintDraft.current(dir,"ctx","city");
        draft.getAsJsonObject("compiledLayout").add("designReview",json("{isolatedCoreGroupIds:['a']}"));
        Files.writeString(dir.resolve(CityBlueprintDraft.FILE),draft.toString());
        assertTrue(call("city_d4_integrate",repair).get("ok").getAsBoolean());
        assertEquals(JsonParser.parseString("['core','small_shop']"),lastCity.getAsJsonArray("groups").get(0)
                .getAsJsonObject().get("requiredStructureRefs"));
        assertEquals(1,CityDesignReviewWorkflow.status(dir,"ctx",CityBlueprintDraft.current(dir,"ctx","city"))
                .get("coreReworkCount").getAsInt());
    }
    private void addCore(JsonObject body) {
        JsonObject first=body.getAsJsonArray("groups").get(0).getAsJsonObject();
        for(var e:body.getAsJsonArray("groups")) {
            JsonObject group=e.getAsJsonObject();if(!group.has("requiredStructureRefs")) group.add("requiredStructureRefs",JsonParser.parseString("['core']"));
        }
        JsonObject core=new JsonObject();core.add("groupId",first.get("groupId"));core.addProperty("structureRef","core");body.add("core",core);
    }
    @Test void savedDistrictCanChangeMaterialsAndLayoutWithoutOverwritingOthers() throws Exception {
        districts();
        JsonObject before=CityD4Workflow.load(dir,"ctx");
        JsonObject request=currentRequest();request.addProperty("targetDistrictId","civic");
        request.add("districtDesign",json("{core:{groupId:'a',structureRef:'small_shop'},groups:[{groupId:'a',requiredStructureRefs:['small_shop'],structureCount:4}]}"));
        // fixture helper would use its standard core; explicitly submit authored new core instead.
        request.getAsJsonObject("districtDesign").getAsJsonArray("groups").get(0).getAsJsonObject().add("expansionPolicy",json("{allowRelationConnection:true}"));
        request.add("designAnswers",JsonParser.parseString("[{groupId:'a',roadConnected:true,foundation:false,noFoundationReason:'fixture',ground:'minecraft:stone',roadSurface:'minecraft:stone',styles:['test']}]"));
        request.addProperty("d4Tool","city_d4_district");request.add("workflowRevision",before.get("revision"));
        JsonObject result=CityD4Workflow.submit(dir,"ctx","city",request,this::compile);
        assertTrue(result.get("ok").getAsBoolean(),result.toString());
        JsonObject after=CityD4Workflow.load(dir,"ctx");
        assertEquals(before.getAsJsonObject("bodies").get("market"),after.getAsJsonObject("bodies").get("market"));
        assertEquals("small_shop",after.getAsJsonObject("bodies").getAsJsonObject("civic").getAsJsonObject("core").get("structureRef").getAsString());
        assertEquals(2,lastCity.getAsJsonArray("districtDesigns").size());
        assertEquals(2,result.getAsJsonObject("requestedPreviews").size());
        assertEquals("CITY_D4_REVISION_STALE",CityD4Workflow.submit(dir,"ctx","city",request,this::compile).get("reasonCode").getAsString());
    }

    @Test void failedRevisionCanRetryFromLastSuccessfulPreviewAndCannotFinalizeMissingRequiredContent() throws Exception {
        districts();JsonObject saved=CityD4Workflow.load(dir,"ctx");
        JsonObject valid=CityBlueprintDraft.current(dir,"ctx","city");valid.add("landscapeLayout",new JsonObject());
        Files.writeString(dir.resolve("city_blueprint_last_valid_preview.json"),valid.toString());
        JsonObject failedBlueprint=valid.getAsJsonObject("previousBlueprint").deepCopy();failedBlueprint.getAsJsonArray("groups").get(0).getAsJsonObject().addProperty("structureCount",99);
        JsonObject rejected=CityBlueprintDraft.create(dir,"ctx",failedBlueprint,new JsonObject(),false);
        Files.writeString(dir.resolve(CityBlueprintDraft.FILE),rejected.toString());
        JsonObject request=json("{d4Tool:'city_d4_district',targetDistrictId:'civic',assessment:'Retry changed core after failed compile',districtDesign:{core:{groupId:'a',structureRef:'small_shop'},groups:[{groupId:'a',requiredStructureRefs:['small_shop'],expansionPolicy:{allowRelationConnection:true}}]},designAnswers:[{groupId:'a',roadConnected:true,foundation:false,noFoundationReason:'fixture',ground:'minecraft:stone',roadSurface:'minecraft:stone',styles:['test']}]}");
        request.add("workflowRevision",saved.get("revision"));request.add("baseDraftHash",valid.get("baseDraftHash"));
        JsonObject result=CityD4Workflow.submit(dir,"ctx","city",request,this::compile);
        assertTrue(result.get("ok").getAsBoolean(),result.toString());
        JsonObject current=CityBlueprintDraft.current(dir,"ctx","city");
        for(var e:current.getAsJsonObject("compiledLayout").getAsJsonArray("anchors"))if(e.getAsJsonObject().get("placementGroupId").getAsString().equals("a"))e.getAsJsonObject().addProperty("blueprintStructureRef","optional-decoration");
        Files.writeString(dir.resolve(CityBlueprintDraft.FILE),current.toString());
        JsonObject finalize=currentRequest();finalize.addProperty("functionsPreserved",true);
        assertFalse(call("city_d4_finalize",finalize).get("ok").getAsBoolean());
        assertNotEquals("COMPLETE",state().get("stage").getAsString());
    }

    private boolean retainIntegration=true;
    private boolean empty=false;
    private JsonObject lastCity;
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private JsonObject state() throws Exception { return CityD4Workflow.status(dir,"ctx"); }
    private JsonObject call(String tool,JsonObject request) throws Exception {
        // These layout fixtures use explicit, valid material decisions; omission tests call submit directly.
        if(tool.equals("city_d4_overview") && request.has("overview") && request.getAsJsonObject("overview").has("citySettings")) {
            var settings=request.getAsJsonObject("overview").getAsJsonObject("citySettings");
            settings.add("roadProfile",json("{profileRef:'road'}"));
            settings.add("surfaceMaterials",json("{defaults:{ground:'minecraft:stone',roadSurface:'minecraft:stone'}}"));
            settings.getAsJsonObject("outdoorPlan").addProperty("mode","GENERATE");
            request.add("designAnswers",json("{styles:['test'],roadProfileRef:'road',ground:'minecraft:stone',roadSurface:'minecraft:stone',groundTreatment:'GENERATE'}"));
            Files.writeString(dir.resolve("city_blueprint_catalog_snapshot.json"),"{referenceCatalog:{structureRefs:[{structureRef:'core',styleTerms:['test']},{structureRef:'small_shop',styleTerms:['test']}],fillPools:[]},structureCatalog:{semanticProfiles:[]}}");
        }
        if(tool.equals("city_d4_district") || tool.equals("city_d4_integrate")) {
            boolean initial=tool.equals("city_d4_district");
            JsonObject body=initial?request.getAsJsonObject("districtDesign"):CityD4Workflow.load(dir,"ctx").getAsJsonObject("bodies").getAsJsonObject(request.get("targetDistrictId").getAsString());
            if(body!=null) {
                if(initial) addCore(body);
                JsonArray answers=new JsonArray();
                for(var e:body.getAsJsonArray("groups")) {
                    JsonObject g=e.getAsJsonObject();if(initial)g.add("expansionPolicy",json("{allowRelationConnection:true}"));
                    var a=json("{roadConnected:true,foundation:false,noFoundationReason:'layout-only fixture',ground:'minecraft:stone',roadSurface:'minecraft:stone',styles:['test']}");
                    a.add("groupId",g.get("groupId"));answers.add(a);
                }
                request.add("designAnswers",answers);
            }
        }
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
            if(!empty && (retainIntegration || !id.equals("link"))) { JsonObject a=new JsonObject();a.addProperty("placementGroupId",id);a.add("design",group.deepCopy());
                for(var ref:group.getAsJsonObject().getAsJsonArray("requiredStructureRefs")) {JsonObject retained=a.deepCopy();retained.add("blueprintStructureRef",ref);anchors.add(retained);} }
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
        assertEquals("city_d4_finalize",state().get("nextAction").getAsString());
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
