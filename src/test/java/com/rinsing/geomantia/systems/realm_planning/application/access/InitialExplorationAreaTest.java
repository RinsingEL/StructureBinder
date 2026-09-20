package com.rinsing.geomantia.systems.realm_planning.application.access;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InitialExplorationAreaTest {
    @Test void excludesTheWholeOriginContinentNearSeaAndLoadBufferFromFutureCityReservations() {
        var area = new InitialExplorationArea(GeographicRegions.build(
                GeographicRegionsTest.grid(-16,120,-16,16,(x,z)->x<48||x>88),256,1024));
        assertTrue(area.contains(5000,0));
        assertTrue(area.contains(49*128,0));
        assertFalse(area.contains(55*128,0));
        assertTrue(area.generationContains(55*128,0));
        assertTrue(area.overlapsGenerationArea(new CityPlanningReservation.Bounds(54*128,0,65*128,128)));
        assertFalse(area.overlapsGenerationArea(new CityPlanningReservation.Bounds(95*128,0,100*128,128)));
        assertFalse(area.contains(100*128,0));
    }
}
