package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CityWallPlacementBackendTest {
    @Test void towerFrontAndEveryVoxelRotateInsideTheReservedFootprint() {
        var anchor = new com.rinsing.geomantia.systems.city.domain.model.BlockPoint(100,200);
        for (String facing : java.util.List.of("NORTH","EAST","SOUTH","WEST")) {
            var area = com.rinsing.geomantia.systems.city.application.CityWallTowerGeometry.bounds(anchor,facing);
            JsonObject node = new JsonObject(); node.addProperty("x",100); node.addProperty("z",200);
            node.addProperty("baseY",64); node.addProperty("facing",facing);
            JsonObject box = new JsonObject();box.addProperty("minX",area.minX());box.addProperty("minZ",area.minZ());
            box.addProperty("maxX",area.maxX());box.addProperty("maxZ",area.maxZ());node.add("blockBounds",box);
            var positions = new java.util.HashSet<net.minecraft.core.BlockPos>();
            for(int x=0;x<7;x++)for(int z=0;z<10;z++) {
                var at = CityWallPlacementBackend.towerBlockPosition(node,x,3,z);
                assertTrue(area.contains(at.getX(),at.getZ()));assertEquals(67,at.getY());positions.add(at);
            }
            assertEquals(70,positions.size());
            var front = CityWallPlacementBackend.towerBlockPosition(node,3,3,9);
            var direction = net.minecraft.core.Direction.valueOf(facing);
            assertEquals(100+3*direction.getStepX(),front.getX());
            assertEquals(200+3*direction.getStepZ(),front.getZ());
            assertEquals(direction,CityWallPlacementBackend.towerRotation(node).rotate(net.minecraft.core.Direction.SOUTH));
        }
    }

    @Test void connectedWallCornerClearsInternalRailsButKeepsOutsideParapets() {
        var horizontal=java.util.List.of(new com.rinsing.geomantia.systems.city.domain.model.BlockBounds(0,-2,32,2));
        var vertical=java.util.List.of(new com.rinsing.geomantia.systems.city.domain.model.BlockBounds(-2,0,2,32));
        assertTrue(CityWallPlacementBackend.cornerWalkway(0,0,horizontal,vertical));
        assertTrue(CityWallPlacementBackend.cornerWalkway(2,0,horizontal,vertical));
        assertTrue(CityWallPlacementBackend.cornerWalkway(0,2,horizontal,vertical));
        assertFalse(CityWallPlacementBackend.cornerWalkway(0,-2,horizontal,vertical));
        assertFalse(CityWallPlacementBackend.cornerWalkway(-2,0,horizontal,vertical));
    }

    @Test void mountainRequiresUnbrokenSolidSurveyInsteadOfOnlyHighSurface() {
        assertTrue(CityWallPlacementBackend.verifiedBarrier(110,64,61,y->true));
        assertFalse(CityWallPlacementBackend.verifiedBarrier(110,64,61,y->y!=65),"cave at the wall base");
        assertFalse(CityWallPlacementBackend.verifiedBarrier(110,64,61,y->y!=61),"hole at survey bottom");
        assertFalse(CityWallPlacementBackend.verifiedBarrier(110,64,61,y->y!=76),"hole at barrier top");
        assertFalse(CityWallPlacementBackend.verifiedBarrier(75,64,61,y->true),"terrain is too low to replace wall");
    }
    @Test void underwaterAndVoidFootingsReachSupportInsideTheWorld() {
        assertEquals(41,CityWallPlacementBackend.foundationStart(63,63,-64,y->y<=40));
        assertEquals(63,CityWallPlacementBackend.foundationStart(63,70,-64,y->y<=62));
        assertEquals(64,CityWallPlacementBackend.foundationStart(99,64,-64,y->true));
        assertEquals(-64,CityWallPlacementBackend.foundationStart(63,63,-64,y->false));
    }
    @Test void modulesMustStayInsideTheirOwnReservation() {
        JsonObject plan=com.google.gson.JsonParser.parseString("""
                {"wallUnits":[{"unitType":"gate_gap","blockBounds":{"minX":0,"minZ":0,"maxX":8,"maxZ":4}}],
                 "wallReservationSource":{"wallCorridorMask":[{"blockBounds":{"minX":0,"minZ":0,"maxX":8,"maxZ":4}}]}}
                """).getAsJsonObject();
        assertEquals("",CityWallPlacementBackend.geometryConflict(plan));
        plan.getAsJsonArray("wallUnits").get(0).getAsJsonObject().getAsJsonObject("blockBounds").addProperty("maxX",9);
        assertEquals("WALL_OUTSIDE_OWNED_RESERVATION",CityWallPlacementBackend.geometryConflict(plan));
    }
    @Test void unavailableWorldDoesNotReportSuccessfulPlacement() {
        var report=new CityWallPlacementBackend().execute(null,new JsonObject());
        assertFalse(report.get("ok").getAsBoolean());
        assertEquals(0,report.get("changedBlocks").getAsInt());
    }
}
