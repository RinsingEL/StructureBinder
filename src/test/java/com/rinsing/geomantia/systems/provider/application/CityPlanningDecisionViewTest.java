package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CityPlanningDecisionViewTest {
    @Test void initialAiViewIncludesBrowseCountsAndPreservesAuthorAuthorityWithoutChangingSnapshot() {
        JsonObject source = JsonParser.parseString("""
                {schema:'city_blueprint_context',catalogSnapshot:{
                  structureCatalog:{semanticProfiles:[{semanticProfileId:'shop',sourceProfileRef:'authored:shop',
                    functionTerms:['果蔬销售'],planningRoleTerms:['planning_role.fill'],styleTerms:['中世纪'],terrainModes:['SURFACE']}]},
                  referenceCatalog:{structureRefs:[{structureRef:'shop',templateCandidates:[{templateId:'shop',variantId:'default'}]}]},
                  templateCatalog:{templates:[{templateId:'shop',rawSize:{width:5,height:7,depth:6}}]}}}
                """).getAsJsonObject();
        var original = source.deepCopy();
        JsonObject view = CityPlanningDecisionView.from(source);
        assertEquals(1, view.getAsJsonObject("materialCatalog").get("matchedCount").getAsInt());
        assertTrue(view.getAsJsonObject("materialCatalog").getAsJsonObject("facets").getAsJsonArray("functions").toString().contains("retail.food.produce"));
        JsonObject authored = view.getAsJsonObject("catalogSnapshot").getAsJsonObject("structureCatalog")
                .getAsJsonArray("semanticProfiles").get(0).getAsJsonObject();
        assertEquals(JsonParser.parseString("['SURFACE']"), authored.get("terrainModes"));
        assertEquals(JsonParser.parseString("['果蔬销售']"), authored.get("functionTerms"));
        assertEquals(JsonParser.parseString("[[5,7,6,0]]"), view.getAsJsonObject("catalogSnapshot").getAsJsonObject("structureGeometry").get("shop"));
        assertEquals(original, source);
    }
}
