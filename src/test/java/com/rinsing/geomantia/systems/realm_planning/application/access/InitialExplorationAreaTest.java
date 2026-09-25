package com.rinsing.geomantia.systems.realm_planning.application.access;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class InitialExplorationAreaTest {
    @TempDir Path world;
    @Test void narrowedWaitingAreaHaloRetainsDiagonalChunkDependencies() {
        var area=new InitialExplorationArea(0,0,2048);
        double edge=2048/Math.sqrt(2)-1;
        assertTrue(area.contains(edge,edge));
        for(int x=-224;x<=224;x+=16) for(int z=-224;z<=224;z+=16)
            assertTrue(area.generationContains(edge+x,edge+z),"Diagonal generation dependency must remain available");
        assertFalse(area.generationContains(2048+272,0));
    }
    @Test void entireStarterContinentAndNearSeaSurviveReloadWithIrregularBoundary() throws Exception {
        var geo=GeographicRegions.build(GeographicRegionsTest.grid(-32,120,-32,32,
                (x,z)->(x<48 && (x<20 || z<8))||x>88),256,1024);
        var area=InitialExplorationArea.continent(geo,0,0);
        assertTrue(area.contains(5000,0),"Land beyond the old circular radius is open");
        assertTrue(area.contains(49*128,0),"Near sea belongs to its continent");
        assertFalse(area.contains(60*128,0),"Distant ocean remains closed");
        assertFalse(area.contains(92*128,0),"Another continent is not the starter");
        assertFalse(area.contains(40*128,20*128),"Coastline indentation is not a bounding rectangle");
        assertTrue(area.generationContains(51*128,0));
        assertFalse(area.generationContains(55*128,0),"Old 1024-block halo is no longer open for generation");
        assertTrue(area.overlapsGenerationArea(new CityPlanningReservation.Bounds(5000,0,5100,128)));
        assertFalse(area.overlapsGenerationArea(new CityPlanningReservation.Bounds(12000,0,12100,128)));
        WorldEntrySurvey.writeAtomic(world.resolve("geomantia_starter_realm.json"),area.description());
        var loaded=InitialExplorationArea.fromDebugRoot(world.resolve("realm_debug"),2048);
        assertEquals(area.description(),loaded.description());
        assertTrue(loaded.contains(5000,0));
        var view=loaded.mapView();
        for(var entry:loaded.description().getAsJsonArray("cells")) {
            var cell=entry.getAsJsonObject();
            assertTrue(Math.abs((cell.get("gridX").getAsInt()+0.5)*geo.step()-view.centerX())<view.radius());
            assertTrue(Math.abs((cell.get("gridZ").getAsInt()+0.5)*geo.step()-view.centerZ())<view.radius());
        }
    }
    @Test void geographicSelectionRequiresARealContinent() {
        var geo=GeographicRegions.build(GeographicRegionsTest.grid(-16,16,-16,16,(x,z)->false),256,1024);
        assertThrows(IllegalArgumentException.class,()->InitialExplorationArea.continent(geo,0,0));
    }
    @Test void waitingAreaIsExplicitlyPreparingAndCanUseAnOffsetSafeSpawn() {
        var area=new InitialExplorationArea(-1000,2000,256);
        assertTrue(area.contains(-1000,2000));
        assertFalse(area.contains(0,0));
        assertEquals("preparing",area.description().get("shape").getAsString());
    }
}
