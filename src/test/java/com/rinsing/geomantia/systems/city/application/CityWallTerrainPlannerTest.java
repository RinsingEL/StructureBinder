package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.IntUnaryOperator;
import static org.junit.jupiter.api.Assertions.*;

class CityWallTerrainPlannerTest {
    private JsonObject wall() {
        JsonObject p=new JsonObject();JsonArray units=new JsonArray();
        for(int x=0;x<128;x+=8) units.add(module(x,x+7,"wall_unit"));
        p.add("wallUnits",units);p.add("wallNodes",new JsonArray());return p;
    }
    private JsonObject module(int min,int max,String type) {
        JsonObject u=JsonParser.parseString("{\"wallAxis\":\"X\",\"blockBounds\":{\"minZ\":0,\"maxZ\":4}}").getAsJsonObject();
        u.addProperty("unitType",type);u.addProperty("gateSlotId","gate");
        u.getAsJsonObject("blockBounds").addProperty("minX",min);
        u.getAsJsonObject("blockBounds").addProperty("maxX",max);return u;
    }
    private JsonArray samples(IntUnaryOperator surface) {
        JsonArray out=new JsonArray();for(int x=0;x<128;x++)for(int z=0;z<5;z++) {
            JsonObject c=new JsonObject();c.addProperty("x",x);c.addProperty("z",z);
            c.addProperty("surfaceY",surface.applyAsInt(x));c.addProperty("fluid",false);out.add(c);
        }return out;
    }
    private Map<Integer,Integer> checkConnections(JsonObject p) {
        Map<Integer,Integer> heights=new TreeMap<>();
        for(var e:p.getAsJsonArray("surfaceColumns")) {
            var c=e.getAsJsonObject();int x=c.get("x").getAsInt(),h=c.get("baseY").getAsInt();
            if(heights.containsKey(x))assertEquals(heights.get(x),h,"flat cross section");
            heights.put(x,h);
            assertNotEquals("natural_barrier",c.get("terrainMode").getAsString(),"surface height alone is no barrier proof");
        }
        for(int x=1;x<128;x++)assertTrue(Math.abs(heights.get(x)-heights.get(x-1))<=1,"stair at "+x);
        return heights;
    }
    @Test void largeOverallReliefFollowsTerrainWithoutLiftingWholeRing() {
        var p=new CityWallTerrainPlanner().plan(wall(),samples(x->x<32?63:x<96?63+(x-32)*36/64:99));
        assertTrue(p.get("ok").getAsBoolean());var heights=checkConnections(p);
        assertEquals(63,heights.get(0));assertEquals(99,heights.get(127));
        assertTrue(p.get("transitionCount").getAsInt()>0);
    }
    @Test void localPitUsesRetainingFoundationAndAbruptMountainEmbeds() {
        var p=new CityWallTerrainPlanner().plan(wall(),samples(x->x==5?20:x<64?64:110));
        var heights=checkConnections(p);assertEquals(64,heights.get(5));
        assertTrue(heights.get(64)<100);
        assertTrue(p.get("embeddedColumnCount").getAsInt()>0);
        assertTrue(p.get("foundationColumnCount").getAsInt()>0);
    }
    @Test void towerAndGateAreLevelWhileNeighbouringSectionsStayConnected() {
        var wall=wall();wall.getAsJsonArray("wallNodes").add(module(30,39,"tower"));
        wall.getAsJsonArray("wallUnits").set(8,module(64,71,"gate_gap"));
        wall.getAsJsonArray("wallUnits").set(9,module(72,79,"gate_gap"));
        var terrain=samples(x->x>=64&&x<80?110:64+x/8);
        var profile=new CityWallTerrainPlanner().plan(wall,terrain);var heights=checkConnections(profile);
        for(int x=30;x<=39;x++)assertEquals(heights.get(30),heights.get(x));
        for(int x=64;x<80;x++) {
            assertEquals(heights.get(64),heights.get(x));
            assertTrue(heights.get(x)+9-110>=4,"gate clearance");
        }
        assertEquals(64,heights.get(0),"gate only raises nearby wall");
    }
    @Test void missingSurfaceFailsBeforePlacementAndRepeatedPlanningIsDeterministic() {
        var planner=new CityWallTerrainPlanner();var terrain=samples(x->63+x%37);
        assertEquals(planner.plan(wall(),terrain),planner.plan(wall(),terrain));
        terrain.remove(10);
        assertThrows(IllegalArgumentException.class,()->planner.plan(wall(),terrain));
        assertThrows(IllegalArgumentException.class,()->planner.plan(wall(),new JsonArray()));
    }
}
