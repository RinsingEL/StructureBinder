package com.rinsing.geomantia.systems.realm_planning.application.access;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class StarterLandSelectorTest {
    @TempDir Path world;
    @Test void oceanOriginChoosesInteriorLandAndRejectsTinyIsland() {
        var site=StarterLandSelector.select(0,0,12288,2048,(x,z)->
                new StarterLandSelector.Ground(70, x>=3072 || Math.abs(x)<32 && Math.abs(z)<32));
        assertTrue(site.x()>=4096);
        assertTrue(Math.abs(site.x())+3072<=12288);
    }
    @Test void dryButSteepPreferredPointDoesNotBecomeSpawn() {
        var site=StarterLandSelector.select(16,16,8192,2048,(x,z)->
                new StarterLandSelector.Ground(x==16&&z==16?150:70,true));
        assertNotEquals(new StarterLandSelector.Site(16,150,16),site);
    }
    @Test void waterWorldFailsExplicitlyAndSearchIsBounded() {
        var count=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IllegalStateException.class,()->StarterLandSelector.select(0,0,12288,2048,(x,z)->{
            count.incrementAndGet(); return new StarterLandSelector.Ground(63,false);
        }));
        assertTrue(count.get()<=4096);
    }
    @Test void frozenWorldCentreControlsMovementFogGenerationAndMapBeforeW() throws Exception {
        Files.writeString(world.resolve("geomantia_starter_realm.json"),"{\"centerBlockX\":6144,\"centerBlockZ\":-1024}");
        Path root=world.resolve("realm_debug");
        var config=PlanningAreaAccessConfig.defaults();
        var policy=new PlanningAreaAccessPolicy(root,config);
        assertTrue(policy.evaluate("minecraft:overworld",6144,-1024).allowed());
        assertFalse(policy.evaluate("minecraft:overworld",0,0).allowed());
        assertTrue(policy.revealed("minecraft:overworld",6144,-1024));
        assertTrue(policy.permitsChunk("minecraft:overworld",384,-64));
        assertFalse(policy.permitsChunk("minecraft:overworld",0,0));
        var area=InitialExplorationArea.fromDebugRoot(root,2048);
        assertTrue(area.overlapsGenerationArea(new CityPlanningReservation.Bounds(6144,-1024,6200,-900)));
        assertFalse(area.overlapsGenerationArea(new CityPlanningReservation.Bounds(0,0,100,100)));
        var snapshot=com.rinsing.geomantia.systems.realm_planning.application.map.AdventurerMapStatusReader.read(root,"",config).forViewer(false);
        assertEquals(6144,snapshot.initialCenterX());
        assertEquals(-1024,snapshot.initialCenterZ());
        assertFalse(snapshot.coarseMap().available());
        assertEquals(6144,InitialExplorationArea.fromDebugRoot(root,2048).centerX());
    }
}
