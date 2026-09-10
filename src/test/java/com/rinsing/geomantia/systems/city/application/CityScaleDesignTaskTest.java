package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.model.CityScale;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CityScaleDesignTaskTest {
    private JsonObject blueprint() throws Exception {
        try (var in=getClass().getResourceAsStream("/fixtures/city_blueprint/valid_city_blueprint.json")) {
            var b=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            for (String id:List.of("a","b","c")) {var group=b.getAsJsonArray("groups").get(0).getAsJsonObject().deepCopy();
                group.addProperty("groupId",id);group.addProperty("priority","STANDARD");b.getAsJsonArray("groups").add(group);}
            return b;
        }
    }
    @Test void everyScaleHasSeparateAdviceAndCapitalDoesNotSilentlyBecomeLarge() {
        assertEquals(5,CityScale.values().length);
        assertEquals(CityScale.LARGE_CITY,CityScale.fromContractName("large_city"));
        assertEquals(CityScale.CITY,CityScale.fromContractName("capital"));
        for(var s:CityScale.values()) assertTrue(CityScaleDesignTask.describe(s).get("suggestedInitialLeafArraysMin").getAsInt()>0);
    }
    @Test void gateRequiresCoreCompositionAndMultipleLargeClustersWithoutEnforcingCountAdvice() throws Exception {
        var json=blueprint();var codec=new CityBlueprintCodec();
        assertEquals("",CityScaleDesignTask.violation(codec.read(json),CityScale.TOWN));
        assertFalse(CityScaleDesignTask.violation(codec.read(json),CityScale.CITY).isBlank());
        json.add("arrayCompositions",JsonParser.parseString("""
            [{"compositionId":"main","algorithmProfileRef":"algorithm:grid","centerGroupId":"civic_core","memberGroupIds":["a"]}]
            """));
        assertEquals("",CityScaleDesignTask.violation(codec.read(json),CityScale.CITY));
        assertFalse(CityScaleDesignTask.violation(codec.read(json),CityScale.LARGE_CITY).isBlank());
        json.getAsJsonArray("arrayCompositions").add(JsonParser.parseString("""
            {"compositionId":"inner","algorithmProfileRef":"algorithm:courtyard","centerGroupId":"a","memberGroupIds":["b","c"]}
            """));
        assertEquals("",CityScaleDesignTask.violation(codec.read(json),CityScale.LARGE_CITY));
        var groups=CityCompositionHierarchy.ordered(codec.read(json).arrayCompositions());
        assertEquals("main",groups.get(0).compositionId());assertEquals("inner",groups.get(1).compositionId());
    }
    @Test void recursiveRelationshipsRejectCyclesAndDuplicateParentsWithoutRemovingGroups() throws Exception {
        var json=blueprint();json.add("arrayCompositions",JsonParser.parseString("""
            [{"compositionId":"x","algorithmProfileRef":"algorithm:grid","centerGroupId":"a","memberGroupIds":["b"]},
             {"compositionId":"y","algorithmProfileRef":"algorithm:grid","centerGroupId":"b","memberGroupIds":["a"]}]
            """));
        assertThrows(IllegalArgumentException.class,()->CityCompositionHierarchy.ordered(new CityBlueprintCodec().read(json).arrayCompositions()));
    }
}
