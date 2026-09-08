package com.rinsing.geomantia.systems.city.application;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CityExpansionLayoutPolicyTest {
    @Test void gridFirstAndGridUnitKeepsAConnectedInternalCross() {
        assertEquals(CityExpansionLayoutPolicy.Algorithm.GRID,CityExpansionLayoutPolicy.Algorithm.values()[0]);
        List<JsonObject> anchors=new ArrayList<>();
        for(int x:List.of(0,16)) for(int z:List.of(0,16)) {
            JsonObject a=new JsonObject(), b=new JsonObject();
            b.addProperty("minX",x); b.addProperty("minZ",z);
            b.addProperty("maxX",x+8); b.addProperty("maxZ",z+8);
            a.add("actualFootprint",b);anchors.add(a);
        }
        var roads=CityExpansionLayoutPolicy.streets("unit","district","GRID",anchors,new JsonArray());
        assertEquals(2,roads.size());
        assertTrue(CityStreetObstacleRouter.crossSection(roads.get(0)).overlaps(CityStreetObstacleRouter.crossSection(roads.get(1))));
        JsonArray occupied=new JsonArray();
        occupied.add(JsonParser.parseString("""
                {"ownerGroupId":"city_main_road","streetBand":{"bounds":{"minX":-10,"maxX":-10,"minZ":0,"maxZ":24}}}
                """));
        var attached=CityExpansionLayoutPolicy.streets("unit","district","GRID",anchors,occupied);
        assertTrue(attached.size()>roads.size());
        assertTrue(attached.stream().anyMatch(road -> road.getAsJsonObject("bounds").get("minX").getAsInt()==-10));
        for(var a:anchors) for(var road:roads) assertFalse(CityStreetObstacleRouter.crossSection(road).overlaps(
                CityStructureCandidateEnvelope.bounds(a.getAsJsonObject("actualFootprint"))));
    }
    @Test void blockedDoorRejectsTheWholeUnitWithoutMovingAnyBuilding() {
        JsonObject first=JsonParser.parseString("""
                {"actualFootprint":{"minX":0,"maxX":8,"minZ":0,"maxZ":8},
                "templatePlacementPlan":{"transformed":{"roadEntrances":[
                {"direction":"EAST","worldPosition":{"x":8,"z":4}}]}}}
                """).getAsJsonObject();
        JsonObject second=JsonParser.parseString("{\"actualFootprint\":{\"minX\":9,\"maxX\":17,\"minZ\":0,\"maxZ\":8}}").getAsJsonObject();
        String original=first.toString()+second.toString();
        assertTrue(CityExpansionLayoutPolicy.streets("unit","district","GRID",List.of(first,second),new JsonArray()).isEmpty());
        assertEquals(original,first.toString()+second.toString());
    }

}
