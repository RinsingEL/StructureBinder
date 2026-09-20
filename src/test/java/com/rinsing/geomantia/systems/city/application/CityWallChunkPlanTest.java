package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityWallChunkPlanTest {
    static JsonObject plan() {
        JsonObject plan=JsonParser.parseString("""
                {"wallUnits":[{"wallAxis":"X","unitType":"wall","blockBounds":{"minX":-8,"minZ":0,"maxX":24,"maxZ":4}}],
                 "wallNodes":[{"baseY":70,"blockBounds":{"minX":14,"minZ":1,"maxX":20,"maxZ":10}}],
                 "wallPlacementProfile":{"minBaseY":64,"maxBaseY":70,"surfaceColumns":[]}}
                """).getAsJsonObject();
        JsonArray columns=plan.getAsJsonObject("wallPlacementProfile").getAsJsonArray("surfaceColumns");
        for(int z=0;z<=10;z++)for(int x=-8;x<=24;x++) {
            JsonObject c=new JsonObject();c.addProperty("x",x);c.addProperty("z",z);c.addProperty("baseY",64+Math.floorDiv(x+8,8));columns.add(c);
        }
        return plan;
    }
    @Test void towerAndStraightWallKeepOriginsAndSeamHeightsAcrossPositiveAndNegativeChunks() {
        JsonObject plan=plan();String original=plan.toString();
        assertEquals(Set.of(new CityWallChunkPlan.Owner(-1,0),new CityWallChunkPlan.Owner(0,0),new CityWallChunkPlan.Owner(1,0)),CityWallChunkPlan.owners(plan));
        JsonObject west=CityWallChunkPlan.fragment(plan,new CityWallChunkPlan.Owner(0,0));
        JsonObject east=CityWallChunkPlan.fragment(plan,new CityWallChunkPlan.Owner(1,0));
        assertEquals(west.getAsJsonArray("wallNodes").get(0),east.getAsJsonArray("wallNodes").get(0));
        assertEquals(14,east.getAsJsonArray("wallNodes").get(0).getAsJsonObject().getAsJsonObject("blockBounds").get("minX").getAsInt());
        for(int x:List.of(15,16)) assertEquals(column(west,x),column(east,x));
        assertEquals(original,plan.toString());
    }
    @Test void fragmentDoesNotCarryRemoteTerrainAndOnlyHasOneCellSeamMargin() {
        var owner=new CityWallChunkPlan.Owner(0,0);var fragment=CityWallChunkPlan.fragment(plan(),owner);
        for(var e:fragment.getAsJsonObject("wallPlacementProfile").getAsJsonArray("surfaceColumns")) {
            int x=e.getAsJsonObject().get("x").getAsInt();assertTrue(x>=-1&&x<=16);
        }
        assertFalse(owner.bounds().contains(16,0));assertTrue(owner.bounds().contains(15,0));
    }
    private static JsonObject column(JsonObject fragment,int x) {
        for(var e:fragment.getAsJsonObject("wallPlacementProfile").getAsJsonArray("surfaceColumns"))
            if(e.getAsJsonObject().get("x").getAsInt()==x&&e.getAsJsonObject().get("z").getAsInt()==0)return e.getAsJsonObject();
        throw new AssertionError("Missing seam");
    }
}
