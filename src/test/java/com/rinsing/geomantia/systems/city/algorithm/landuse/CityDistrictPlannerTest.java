package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityDistrictPlannerTest {
    @Test void roadCrossingsDoNotTurnWaterOrMissingSamplesIntoFoundations() {
        var groups = Map.of("town", List.of(new BlockBounds(0,0,8,12),new BlockBounds(18,0,26,12)));
        var road = List.of(new BlockBounds(8,4,18,6));
        var river = terrain(true);
        var missing = new LandUseTerrainField(river.schema(),river.cityId(),BOUNDS,1,
                river.cells().stream().filter(c -> c.cellX()!=13).toList());
        for (var terrain : List.of(river,missing)) {
            var result = new CityDistrictPlanner().plan(BOUNDS,terrain,groups,road,Set.of(),SETTINGS);
            assertFalse(result.construction().stream().anyMatch(p -> p.x()==13),
                    "crossing and shoulders must not claim protected terrain as dry-land foundation");
            assertTrue(result.construction().contains(new BlockPoint(12,5)),"land approach remains paved");
            assertTrue(result.construction().contains(new BlockPoint(14,5)),"far bank remains paved");
        }
    }

    @Test void acceptedUrbanBuildingsAndTheirRoadShouldersDoNotLeaveCliffLabelHoles() {
        var plain=terrain(false);
        var cliff=new LandUseTerrainField(plain.schema(),plain.cityId(),plain.planningBounds(),1,
                plain.cells().stream().map(c->new LandUseTerrainField.Cell(c.cellX(),c.cellZ(),c.blockMinX(),
                        c.blockMinZ(),1,80,7,21,5,false,0,0,c.biomeId(),"cliff",c.landformPatchId(),true)).toList());
        var buildings=Map.of("urban",List.of(new BlockBounds(0,0,8,12),new BlockBounds(18,0,26,12)));
        var result=new CityDistrictPlanner().plan(BOUNDS,cliff,buildings,
                List.of(new BlockBounds(0,18,40,20)),Set.of(),SETTINGS);
        assertTrue(result.construction().contains(new BlockPoint(12,6)),"interior cliff cell is construction land");
        assertTrue(result.construction().contains(new BlockPoint(12,21)),"roadside shoulder must share the platform");
        assertFalse(result.construction().contains(new BlockPoint(90,20)),"countryside is not urbanized");
        assertFalse(result.natural().contains(new BlockPoint(40,25)),"exterior cliffs are not planted as public lawns");
    }
    @Test void aVillageHasPublicGapsWithoutAutomaticallyAcquiringPlatforms() {
        var village = Map.of("village", List.of(new BlockBounds(0,0,8,12), new BlockBounds(18,0,26,12)));
        var result = new CityDistrictPlanner().plan(BOUNDS,terrain(true),village,Map.of(),
                List.of(new BlockBounds(0,14,26,16)),Set.of(new BlockPoint(12,6)),SETTINGS);
        assertTrue(result.construction().isEmpty());
        assertFalse(result.envelope().isEmpty());
        assertTrue(result.natural().contains(new BlockPoint(12,5)));
        assertFalse(result.natural().contains(new BlockPoint(13,5)),"water remains protected");
        assertFalse(result.natural().contains(new BlockPoint(12,6)),"authored landscape remains protected");
        assertTrue(Collections.disjoint(result.structures(),result.natural()));
    }

    private static final BlockBounds BOUNDS = new BlockBounds(-64,-64,255,127);
    private static final LandUseSeedGroup.FoundationSettings SETTINGS =
            new LandUseSeedGroup.FoundationSettings(2,4,16);

    @Test void groupGapIsPavedButWaterAndExplicitLandscapeArePreserved() {
        var groups = Map.of("market", List.of(new BlockBounds(0,0,8,12),new BlockBounds(18,0,26,12)));
        Set<BlockPoint> garden = Set.of(new BlockPoint(13,6));
        var plan = new CityDistrictPlanner().plan(BOUNDS, terrain(false), groups, List.of(), garden, SETTINGS);
        assertTrue(plan.construction().contains(new BlockPoint(12,4)));
        assertFalse(plan.construction().contains(new BlockPoint(13,6)));
        assertTrue(plan.envelope().contains(new BlockPoint(13,6)));
        var river = new CityDistrictPlanner().plan(BOUNDS, terrain(true), groups,List.of(),Set.of(),SETTINGS);
        assertFalse(river.construction().contains(new BlockPoint(13,4)));
    }

    @Test void largeCourtyardIsNotPavedAndLongRoadDoesNotUrbanizeCountryside() {
        var groups = Map.of("west", List.of(new BlockBounds(0,0,8,8)),
                "east",List.of(new BlockBounds(180,0,188,8)));
        var plan = new CityDistrictPlanner().plan(BOUNDS,terrain(false),groups,
                List.of(new BlockBounds(8,3,180,5)),Set.of(),SETTINGS);
        assertFalse(plan.construction().contains(new BlockPoint(90,4)));
        assertFalse(plan.envelope().contains(new BlockPoint(90,4)));
        assertTrue(plan.construction().contains(new BlockPoint(12,4)));
        assertEquals(2,plan.foundation().componentCount());
    }

    @Test void negativeCoordinatesAndInputOrderHaveIdenticalGeometry() {
        var a = new BlockBounds(-30,-20,-22,-12);
        var b = new BlockBounds(-12,-20,-4,-12);
        var first = new CityDistrictPlanner().plan(BOUNDS,terrain(false),Map.of("town",List.of(a,b)),List.of(),Set.of(),SETTINGS);
        var second = new CityDistrictPlanner().plan(BOUNDS,terrain(false),Map.of("town",List.of(b,a)),List.of(),Set.of(),SETTINGS);
        assertEquals(first,second);
        assertTrue(first.envelope().containsAll(first.construction()));
        assertTrue(Collections.disjoint(first.natural(),first.construction()));
    }

    private static LandUseTerrainField terrain(boolean river) {
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        for(int z=BOUNDS.minZ();z<=BOUNDS.maxZ();z++) for(int x=BOUNDS.minX();x<=BOUNDS.maxX();x++) {
            boolean water=river && x==13;
            cells.add(new LandUseTerrainField.Cell(x,z,x,z,1,water?62:70,0,0,0,water,water?8:0,0,
                    "minecraft:plains",water?"river":"plain","patch",true));
        }
        return new LandUseTerrainField(LandUseTerrainField.SCHEMA,"city",BOUNDS,1,cells);
    }
}
