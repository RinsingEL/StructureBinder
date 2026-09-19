package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CityWallPlacementBackendTest {
    @Test void commonDatumAbsorbsSmallPitsWithoutChangingWalkwayHeight() {
        JsonObject result=CityWallPlacementBackend.heightProfile(List.of(61,65,65,66,67),17,64);
        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(65,result.get("baseY").getAsInt());
        assertEquals(74,result.get("walkwayFloorY").getAsInt());
    }
    @Test void incompatibleCliffRequiresRedesignInsteadOfDisconnectedTops() {
        JsonObject result=CityWallPlacementBackend.heightProfile(List.of(64,64,65,89),17,64);
        assertFalse(result.get("ok").getAsBoolean());
        assertEquals("WALL_TERRAIN_REQUIRES_REDESIGN",result.get("reasonCode").getAsString());
    }
    @Test void excessiveFootingAndEmptySurfaceAreRejected() {
        assertFalse(CityWallPlacementBackend.heightProfile(List.of(60,70,70),17,3).get("ok").getAsBoolean());
        assertFalse(CityWallPlacementBackend.heightProfile(List.of(),17,64).get("ok").getAsBoolean());
    }
    @Test void gateMayCrossRoadButNeverClipBuilding() {
        JsonObject plan=com.google.gson.JsonParser.parseString("""
                {"wallUnits":[{"unitType":"gate_gap","blockBounds":{"minX":0,"minZ":0,"maxX":8,"maxZ":4}}],
                 "actualRoadMask":{"roadMask":[{"blockBounds":{"minX":3,"minZ":-8,"maxX":5,"maxZ":12}}]},
                 "sourcePlacedStructureLedger":{"placedStructures":[]}}
                """).getAsJsonObject();
        assertEquals("",CityWallPlacementBackend.geometryConflict(plan));
        plan.getAsJsonArray("wallUnits").get(0).getAsJsonObject().addProperty("unitType","wall_segment");
        assertEquals("WALL_UNRESERVED_ROAD_CONFLICT",CityWallPlacementBackend.geometryConflict(plan));
        plan.getAsJsonArray("wallUnits").get(0).getAsJsonObject().addProperty("unitType","gate_gap");
        plan.getAsJsonObject("sourcePlacedStructureLedger").getAsJsonArray("placedStructures").add(
                com.google.gson.JsonParser.parseString("""
                {"lockedActualFootprint":{"minX":4,"minZ":2,"maxX":10,"maxZ":10}}
                """));
        assertEquals("WALL_STRUCTURE_CONFLICT",CityWallPlacementBackend.geometryConflict(plan));
    }
    @Test void unavailableWorldDoesNotReportSuccessfulPlacement() {
        var report=new CityWallPlacementBackend().execute(null,new JsonObject());
        assertFalse(report.get("ok").getAsBoolean());
        assertEquals(0,report.get("changedBlocks").getAsInt());
    }
}
