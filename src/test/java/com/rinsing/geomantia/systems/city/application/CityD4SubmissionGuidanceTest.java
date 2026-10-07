package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CityD4SubmissionGuidanceTest {
    private JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
    private JsonObject state(){return json("{stage:'DISTRICTS',districtIndex:1,districts:[{groupId:'admin'},{groupId:'market'}],bodies:{admin:{groups:[{groupId:'hall',priority:'CORE'},{groupId:'wing',priority:'STANDARD'}]},market:{groups:[{groupId:'shop',priority:'STANDARD'}]}}}");}
    @Test void tellsNextDistrictWhoAlreadyOwnsTheGlobalCore(){
        var guidance=CityD4SubmissionGuidance.describe(state());
        assertEquals("hall",guidance.getAsJsonArray("savedCoreOwners").get(0).getAsJsonObject().get("groupId").getAsString());
        assertTrue(guidance.get("coreRule").getAsString().contains("每区一个显式 core"));
        var first=state();first.addProperty("districtIndex",0);
        assertTrue(CityD4SubmissionGuidance.describe(first).get("coreRule").getAsString().contains("每区一个显式 core"));
    }
    @Test void translatesMergedIndicesWithoutPointingAnEarlierDistrictAtCurrentInput(){
        var response=json("{validationReport:{issues:[{fieldPath:'$.groups[2].requiredStructureRefs'},{fieldPath:'$.groups[0].priority'},{fieldPath:'$.outdoorPlan.foundationGroupIds'}]}}");
        CityD4SubmissionGuidance.annotate(response,state(),"market","districtDesign");
        var issues=response.getAsJsonArray("stageValidationIssues");
        assertEquals("$.districtDesign.groups[0].requiredStructureRefs",issues.get(0).getAsJsonObject().get("fieldPath").getAsString());
        assertEquals("$.groups[2].requiredStructureRefs",issues.get(0).getAsJsonObject().get("canonicalFieldPath").getAsString());
        assertEquals("admin",issues.get(1).getAsJsonObject().get("districtId").getAsString());
        assertTrue(issues.get(1).getAsJsonObject().has("repairAction"));
        assertEquals("$.districtDesign.foundationGroupIds",issues.get(2).getAsJsonObject().get("fieldPath").getAsString());
        assertEquals("$.groups[2].requiredStructureRefs",response.getAsJsonObject("validationReport").getAsJsonArray("issues").get(0).getAsJsonObject().get("fieldPath").getAsString());
    }
    @Test void labelsGlobalCoreScopeAndIntegrationIndices(){
        var s=state();s.add("integrationDesign",json("{groups:[{groupId:'link',priority:'STANDARD'}]}"));
        var response=json("{validationReport:{issues:[{fieldPath:'$.groups',reasonCode:'CITY_BLUEPRINT_GROUP_PRIORITY_HIGHEST_COUNT_INVALID'},{fieldPath:'$.groups[3].structureCount'}]}}");
        CityD4SubmissionGuidance.annotate(response,s,"","integrationDesign");
        var issues=response.getAsJsonArray("stageValidationIssues");
        assertEquals("whole_city",issues.get(0).getAsJsonObject().get("constraintScope").getAsString());
        assertEquals("$.integrationDesign.groups[0].structureCount",issues.get(1).getAsJsonObject().get("fieldPath").getAsString());
    }
}
