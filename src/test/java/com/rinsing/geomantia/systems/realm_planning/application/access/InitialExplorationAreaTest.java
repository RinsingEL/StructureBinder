package com.rinsing.geomantia.systems.realm_planning.application.access;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class InitialExplorationAreaTest {
    @Test void starterRealmDoesNotReleaseItsWholeContinentAndProtectsLoadingHalo() {
        var area = new InitialExplorationArea(GeographicRegions.build(
                GeographicRegionsTest.grid(-16,120,-16,16,(x,z)->x<48||x>88),256,1024));
        assertTrue(area.contains(0,0));
        assertTrue(area.contains(2048,0));
        assertFalse(area.contains(2049,0));
        assertFalse(area.contains(5000,0));
        assertTrue(area.generationContains(3000,0));
        assertFalse(area.generationContains(3073,0));
        assertTrue(area.overlapsGenerationArea(new CityPlanningReservation.Bounds(3000,0,4000,128)));
        assertFalse(area.overlapsGenerationArea(new CityPlanningReservation.Bounds(4000,0,5000,128)));
    }
    @Test void configuredRadiusAlsoWorksWhenOriginIsOcean() {
        var area=new InitialExplorationArea(GeographicRegions.build(
                GeographicRegionsTest.grid(-16,16,-16,16,(x,z)->false),256,1024),256);
        assertTrue(area.contains(256,0));
        assertFalse(area.contains(257,0));
        assertFalse(area.contains(200,200));
    }
}
