package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityDesignQuestionsTest {
    @TempDir Path dir;
    static JsonObject j(String s){return JsonParser.parseString(s).getAsJsonObject();}
    JsonObject city(){return j("{roadProfile:{profileRef:'road'},surfaceMaterials:{defaults:{ground:'minecraft:stone',roadSurface:'minecraft:stone'}},outdoorPlan:{mode:'GENERATE'}}");}
    JsonObject body(){return j("{groups:[{groupId:'clinic',requiredStructureRefs:['desert_clinic'],expansionPolicy:{allowRelationConnection:true}}],foundationGroupIds:['clinic']}");}
    JsonArray answers(){return JsonParser.parseString("[{groupId:'clinic',roadConnected:true,foundation:true,ground:'minecraft:stone',roadSurface:'minecraft:stone',styles:['forest']}]").getAsJsonArray();}
    void snapshot()throws Exception{Files.writeString(dir.resolve("city_blueprint_catalog_snapshot.json"),"{structureCatalog:{semanticProfiles:[{semanticProfileId:'desert_clinic',styleTerms:['desert']}]},referenceCatalog:{structureRefs:[],fillPools:[]}}");}
    @Test void missingAnswersNeverBecomePreserve() {
        assertThrows(IllegalArgumentException.class,()->CityDesignQuestions.overview(city(),new JsonObject()));
        JsonObject c=city();c.getAsJsonObject("outdoorPlan").addProperty("mode","PRESERVE");
        assertThrows(IllegalArgumentException.class,()->CityDesignQuestions.overview(c,j("{styles:['forest'],roadProfileRef:'road',ground:'minecraft:stone',roadSurface:'minecraft:stone',groundTreatment:'PRESERVE'}")));
    }
    @Test void rejectsFunctionallySuitableButWrongStyleAndFalseGroundClaim()throws Exception{
        snapshot();
        var error=assertThrows(IllegalArgumentException.class,()->CityDesignQuestions.district(dir,city(),body(),answers(),Set.of("forest")));
        assertTrue(error.getMessage().contains("desert_clinic"));
        var b=body();b.add("foundationGroupIds",new JsonArray());
        assertTrue(assertThrows(IllegalArgumentException.class,()->CityDesignQuestions.district(dir,city(),b,answers(),Set.of("forest"))).getMessage().contains("foundationGroupIds"));
    }
    @Test void validatesExplicitStyleExceptionButRejectsStaleMaterialAnswer()throws Exception{
        snapshot();var a=answers();a.get(0).getAsJsonObject().add("styles",JsonParser.parseString("['desert']"));a.get(0).getAsJsonObject().addProperty("exceptionReason","imported clinic in a designated foreign quarter");
        assertDoesNotThrow(()->CityDesignQuestions.district(dir,city(),body(),a,Set.of("forest")));
        var c=city();c.getAsJsonObject("surfaceMaterials").getAsJsonObject("defaults").addProperty("ground","minecraft:dirt");
        assertThrows(IllegalArgumentException.class,()->CityDesignQuestions.district(dir,c,body(),a,Set.of("forest")));
    }
}
