package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CityWallPlacementBackendTest {
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
