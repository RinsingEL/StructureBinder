package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.CityD4Workflow;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProviderD4ToolSchemaTest {
    @Test void versionOperationsBindCurrentIdentityAndExposeNoWorldMutation() {
        var reopen=schema("city_d4_reopen");assertTrue(reopen.getAsJsonArray("required").toString().contains("baseBlueprintHash"));
        var restore=schema("city_d4_restore");assertTrue(restore.getAsJsonArray("required").toString().contains("versionId"));
        assertTrue(restore.getAsJsonObject("properties").has("baseDraftHash"));
        assertTrue(schema("city_d4_history").getAsJsonArray("required").toString().contains("workflowRevision"));
        for(String name:List.of("city_d4_reopen","city_d4_restore"))assertFalse(schema(name).getAsJsonObject("properties").has("confirmWorldMutation"));
    }
    @Test void materialToolExposesNestedFiltersAndBoundedBrowsing() {
        JsonObject fields = schema("city_d4_materials").getAsJsonObject("properties").getAsJsonObject("materialSelections")
                .getAsJsonObject("items").getAsJsonObject("properties");
        JsonObject filters = fields.getAsJsonObject("filters").getAsJsonObject("properties");
        for (String key : List.of("roles", "functionIds", "functionMode", "styles", "rawFunctionTerms", "categories", "assetTags")) assertTrue(filters.has(key));
        assertTrue(filters.getAsJsonObject("roles").getAsJsonObject("items").getAsJsonArray("enum").toString().contains("core"));
        assertEquals(0, fields.getAsJsonObject("limit").get("minimum").getAsInt());
        assertEquals(100, fields.getAsJsonObject("limit").get("maximum").getAsInt());
    }
    private JsonObject schema(String name) {
        return ProviderPlanningToolCatalog.definitions(List.of(name)).get(0).getAsJsonObject().getAsJsonObject("parameters");
    }
    @Test void stageToolsExposeOnlyTheirOwnOperations() {
        for(String name:CityD4Workflow.TOOLS) assertNotNull(schema(name));
        JsonObject design=schema("city_d4_district").getAsJsonObject("properties");
        for(String field:List.of("designReview","complete","materialSelections","blockMaterials","integrationDesign")) assertFalse(design.has(field));
        assertTrue(design.has("targetDistrictId")&&design.has("baseDraftHash")&&design.has("assessment"));
        assertTrue(design.getAsJsonObject("districtDesign").getAsJsonArray("required").contains(new com.google.gson.JsonPrimitive("core")));
        assertFalse(design.getAsJsonObject("districtDesign").getAsJsonObject("properties").has("spatialGrounds"));
        JsonObject settings=schema("city_d4_overview").getAsJsonObject("properties").getAsJsonObject("overview").getAsJsonObject("properties").getAsJsonObject("citySettings").getAsJsonObject("properties");
        assertFalse(settings.getAsJsonObject("outdoorPlan").getAsJsonObject("properties").has("landscapes"));
        assertFalse(schema("city_d4_preview").getAsJsonObject("properties").has("assessment"));
        assertTrue(schema("city_d4_finalize").getAsJsonArray("required").toString().contains("assessment"));
        assertTrue(schema("city_d4_integrate").getAsJsonObject("properties").has("targetDistrictId"));
    }
    @Test void partialUpdatesDoNotRequireCompleteSiblingParameters() {
        JsonObject update=schema("city_d4_integrate").getAsJsonObject("properties").getAsJsonObject("changes").getAsJsonObject("properties");
        JsonObject group=update.getAsJsonObject("groups").getAsJsonObject("items");
        assertEquals("[\"groupId\"]",group.getAsJsonArray("required").toString());
        assertFalse(group.getAsJsonObject("properties").getAsJsonObject("spaceComposition").has("required"));
        assertFalse(update.has("surfaceMaterials"));
        assertFalse(update.has("removeGroupIds"));
    }
}
