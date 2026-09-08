package com.rinsing.geomantia.systems.realm_planning.application.access;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class GeographicRegionsTest {
    static JsonObject grid(int minX,int maxX,int minZ,int maxZ,java.util.function.BiPredicate<Integer,Integer> land) {
        JsonObject grid=new JsonObject(); grid.addProperty("cellStepBlocks",128); JsonArray cells=new JsonArray();
        for(int z=minZ;z<=maxZ;z++) for(int x=minX;x<=maxX;x++) { JsonObject c=new JsonObject(); c.addProperty("gridX",x); c.addProperty("gridZ",z); c.addProperty("waterFrac",land.test(x,z)?0:1); cells.add(c); }
        grid.add("cells",cells); return grid;
    }
    @Test void continentsOwnTheirNearestCoastAndRemoteOceanIsSplit() {
        var map=GeographicRegions.build(grid(-20,20,-10,10,(x,z)->(x==-16||x==16)&&Math.abs(z)<3),256,1024);
        String left=map.at(-16*128,0),right=map.at(16*128,0);
        assertNotEquals(left,right); assertEquals(left,map.at(-14*128,0)); assertEquals(right,map.at(14*128,0));
        assertTrue(map.regions().get(map.at(0,0)).ocean());
        assertTrue(map.regions().values().stream().filter(GeographicRegions.Region::ocean).count()>1);
        assertEquals(GeographicRegions.build(grid(-20,20,-10,10,(x,z)->(x==-16||x==16)&&Math.abs(z)<3),256,1024).asJson(),map.asJson());
        for(var r:map.regions().values()) { Set<GeographicRegions.Cell> seen=new HashSet<>(); var queue=new ArrayDeque<GeographicRegions.Cell>(); queue.add(r.cells().iterator().next());
            while(!queue.isEmpty()) { var c=queue.remove(); if(!seen.add(c)) continue; for(var n:c.neighbors()) if(r.cells().contains(n)) queue.add(n); }
            assertEquals(r.cells(),seen,"Every geographic region must be connected");
        }
    }
    @Test void changingNearSeaDistanceChangesAssignmentAndMissingCellsDoNotConnectLand() {
        JsonObject grid=grid(0,4,0,0,(x,z)->x==0||x==4);
        var shortCoast=GeographicRegions.build(grid,0,128); var longCoast=GeographicRegions.build(grid,256,128);
        assertTrue(shortCoast.regions().get(shortCoast.at(128,0)).ocean());
        assertEquals(longCoast.at(0,0),longCoast.at(128,0));
        assertNotEquals(longCoast.at(0,0),longCoast.at(512,0));
    }
    @Test void diagonalAndUnequalCityReservationsUseActualRectangles() {
        var a=CityPlanningReservation.centered("a",0,0,512);
        assertTrue(a.protection().maxX()>=768);
        assertThrows(IllegalArgumentException.class,()->a.requireSeparate(CityPlanningReservation.centered("b",1500,100,512)));
        assertDoesNotThrow(()->a.requireSeparate(CityPlanningReservation.centered("b",1540,0,512)));
        assertThrows(IllegalArgumentException.class,()->a.requireSeparate(CityPlanningReservation.centered("c",900,900,160)));
        assertDoesNotThrow(()->a.requireSeparate(CityPlanningReservation.centered("c",1100,100,160)));
        assertFalse(a.design().contains(700,0)); assertTrue(a.protection().contains(700,0));
    }
}
