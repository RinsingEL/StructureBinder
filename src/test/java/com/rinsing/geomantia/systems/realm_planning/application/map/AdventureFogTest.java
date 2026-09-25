package com.rinsing.geomantia.systems.realm_planning.application.map;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdventureFogTest {
    @Test void ordinarySnapshotRemovesHiddenTerritoryCitiesNamesAndDebugState() {
        var map=new AdventurerMapSnapshot.CoarseMap("minecraft:overworld",0,0,128,2,1,
                new byte[]{3,7},new byte[]{1,2},new byte[]{1,0},List.of("home","secret"),List.of("Home","Hidden Kingdom"));
        var snapshot=new AdventurerMapSnapshot("run","running","scan",25,"T4","running","secret","Hidden Kingdom",
                "hiddenCity","pending",1,4,2048,map,List.of(
                new AdventurerMapSnapshot.CityNode("homeCity","home","city",0,0,"waiting_for_generation",false),
                new AdventurerMapSnapshot.CityNode("hiddenCity","secret","city",128,0,"pending",true)));
        var normal=snapshot.forViewer(false);
        assertEquals(List.of("home"),normal.coarseMap().realmIds());
        assertEquals(List.of("Home"),normal.coarseMap().realmNames());
        assertEquals(0,normal.coarseMap().terrainCodes()[1]);
        assertEquals(0,normal.coarseMap().realmCodes()[1]);
        assertEquals(1,normal.cityNodes().size());
        assertTrue(normal.currentRealmName().isEmpty());
        assertEquals("running",normal.wStatus());
        assertEquals(25,normal.wProgressPercent());
        assertSame(snapshot,snapshot.forViewer(true));
        assertEquals(7,snapshot.coarseMap().terrainCodes()[1]);
    }
}
