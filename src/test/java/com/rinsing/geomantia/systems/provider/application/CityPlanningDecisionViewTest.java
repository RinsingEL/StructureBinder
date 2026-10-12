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
    @Test void coordinateViewRemovesNumericAdviceAndPatchSelectionWithoutChangingFrozenContext() {
        JsonObject source = JsonParser.parseString("""
                {schema:'city_blueprint_context',contextId:'frozen',sourceD3Ref:{path:'d3.json'},
                 patchReviewEvidence:{sessionId:'old'},
                 scaleDesignTask:{scale:'town',suggestedInitialLeafArraysMin:4,suggestedInitialLeafArraysMax:7,counting:'old'},
                 designGuide:{recovery:'Submit once'},
                 d3ReviewPackage:{reviewMapImage:'map.png',grid:{minBlockX:-32},
                  landformPatches:[{landformPatchId:'old',landformType:'PLAIN',areaBlocks:100},
                                   {landformPatchId:'other',landformType:'PLAIN',areaBlocks:200}]}}
                """).getAsJsonObject();
        JsonObject frozen = source.deepCopy();
        JsonObject view = CityPlanningDecisionView.from(source);
        assertEquals(frozen, source);
        JsonObject repeated = new JsonObject(); repeated.add("designSession", source.deepCopy());
        assertFalse(PlanningToolPresentation.compact(repeated).toString().contains("suggestedInitialLeafArrays"));
        assertEquals("frozen", view.get("contextId").getAsString());
        assertFalse(view.has("patchReviewEvidence"));
        assertFalse(view.getAsJsonObject("scaleDesignTask").has("suggestedInitialLeafArraysMin"));
        assertFalse(view.getAsJsonObject("scaleDesignTask").has("suggestedInitialLeafArraysMax"));
        JsonObject terrain = view.getAsJsonObject("d3ReviewPackage");
        assertFalse(terrain.has("landformPatches"));
        assertFalse(terrain.has("candidateCoverage"));
        assertEquals(300, terrain.getAsJsonObject("landformAreaBlocksByType").get("PLAIN").getAsInt());
        assertEquals("map.png", terrain.get("reviewMapImage").getAsString());
        assertFalse(view.toString().contains("patch_explorer"));
        assertTrue(view.getAsJsonObject("designGuide").get("recovery").getAsString().contains("正常生成"));
    }
}
