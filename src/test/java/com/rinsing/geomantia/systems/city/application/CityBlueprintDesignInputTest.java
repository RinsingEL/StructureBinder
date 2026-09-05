package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CityBlueprintDesignInputTest {
    private static JsonObject object(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    @Test void bindingPreservesExplicitConflictsForTheCanonicalValidator() {
        JsonObject context = object("{cityId:'city',sourceD3Ref:{path:'d3'},catalogSnapshotRef:{path:'catalog'},generationSeedSuggestion:42}");
        JsonObject input = object("{cityId:'wrong',designIntent:{theme:'author'}}");
        JsonObject bound = CityBlueprintDesignInput.bind(input, context, false);
        assertEquals("wrong", bound.get("cityId").getAsString());
        assertEquals(42, bound.get("generationSeed").getAsInt());
        assertEquals(context.get("sourceD3Ref"), bound.get("sourceD3Ref"));
        assertFalse(input.has("sourceD3Ref"));
    }

    @Test void explicitWeightsNormalizeAllThreeDomainsWithoutChangingTheInput() {
        JsonObject context = object("{cityId:'city',sourceD3Ref:{},catalogSnapshotRef:{},generationSeedSuggestion:42}");
        JsonObject input = object("{groups:[{targetAreaShare:3,spaceComposition:{buildingShare:2,landscapeShare:1,openSpaceShare:1}},"
                + "{targetAreaShare:1,spaceComposition:{buildingShare:1,landscapeShare:0,openSpaceShare:0}}],"
                + "outdoorPlan:{landscapes:[{fillSelection:{variants:[{roleShares:[{targetShare:3},{targetShare:1}]}]}}]}}");
        JsonObject result = CityBlueprintDesignInput.bind(input, context, true);
        assertEquals(0.75, result.getAsJsonArray("groups").get(0).getAsJsonObject().get("targetAreaShare").getAsDouble());
        assertEquals(0.5, result.getAsJsonArray("groups").get(0).getAsJsonObject().getAsJsonObject("spaceComposition").get("buildingShare").getAsDouble());
        assertEquals(0.75, result.getAsJsonObject("outdoorPlan").getAsJsonArray("landscapes").get(0).getAsJsonObject()
                .getAsJsonObject("fillSelection").getAsJsonArray("variants").get(0).getAsJsonObject().getAsJsonArray("roleShares")
                .get(0).getAsJsonObject().get("targetShare").getAsDouble());
        assertEquals(3, CityBlueprintDesignInput.bind(input, context, false).getAsJsonArray("groups").get(0).getAsJsonObject().get("targetAreaShare").getAsInt());
        input.getAsJsonArray("groups").get(0).getAsJsonObject().addProperty("targetAreaShare", -1);
        assertThrows(IllegalArgumentException.class, () -> CityBlueprintDesignInput.bind(input, context, true));
    }

    @Test void localReplacementPreservesOtherChoicesAndRejectsMissingPathsAndIdentityEdits() {
        JsonObject original = object("{groups:[{groupId:'core',densityClass:'DENSE'}],designIntent:{theme:'original'}}");
        JsonArray patch = JsonParser.parseString("[{op:'replace',path:'/groups/0/densityClass',value:'SPARSE'}]").getAsJsonArray();
        JsonObject revised = CityBlueprintDesignInput.revise(original, patch);
        assertEquals("SPARSE", revised.getAsJsonArray("groups").get(0).getAsJsonObject().get("densityClass").getAsString());
        assertEquals(original.get("designIntent"), revised.get("designIntent"));
        assertEquals("DENSE", original.getAsJsonArray("groups").get(0).getAsJsonObject().get("densityClass").getAsString());
        for (String path : new String[]{"/groups/99", "/groups/0/missing", "/sourceD3Ref/path", "/generationSeed"}) {
            patch.get(0).getAsJsonObject().addProperty("path", path);
            assertThrows(IllegalArgumentException.class, () -> CityBlueprintDesignInput.revise(original, patch));
        }
    }

    @Test void searchBudgetAndFinalizerFailuresAreNotDesignRejections() {
        assertTrue(CityBlueprintFailureRouting.isProgramFailure("CITY_BLUEPRINT_REQUIRED_STRUCTURE_SEARCH_LIMIT_EXHAUSTED"));
        assertTrue(CityBlueprintFailureRouting.isProgramFailure("CITY_BLUEPRINT_COMPILED_ANCHOR_FINALIZATION_FAILED"));
        assertFalse(CityBlueprintFailureRouting.isProgramFailure("CITY_BLUEPRINT_REQUIRED_STRUCTURE_NO_LEGAL_PLACEMENT"));
        assertTrue(CityBlueprintFailureRouting.isProgramFailure("CITY_BLUEPRINT_REQUIRED_STRUCTURE_NO_LEGAL_PLACEMENT",
                JsonParser.parseString("{selections:[{attempts:[{hardBlocks:['D4_ARRAY_LAYOUT_FRONTAGE_ENTRANCE_AMBIGUOUS: fountain']}]}]}")));
        assertFalse(CityBlueprintFailureRouting.isProgramFailure("CITY_BLUEPRINT_REQUIRED_STRUCTURE_NO_LEGAL_PLACEMENT",
                JsonParser.parseString("{selections:[{attempts:[{hardBlocks:['TERRAIN_UNFIT']}]}]}")));
        JsonObject response = object("{ok:false,failureCount:2}");
        CityBlueprintFailureRouting.blockOnProgram(response);
        assertEquals(2, response.get("failureCount").getAsInt());
        assertEquals("city_post_d4_auto_compile_retry", response.get("nextAction").getAsString());
        assertEquals("program", response.get("failureOwner").getAsString());
    }
}
