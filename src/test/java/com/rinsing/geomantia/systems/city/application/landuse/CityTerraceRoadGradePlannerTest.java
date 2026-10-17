package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityTerraceRoadGradePlannerTest {
    private List<CityLandUseSurfacePrintPlan.FeatureCell> lane(int length) {
        List<CityLandUseSurfacePrintPlan.FeatureCell> cells=new ArrayList<>();
        for(int z=0;z<length;z++)cells.add(new CityLandUseSurfacePrintPlan.FeatureCell(z<3?"approach":"extension",0,z,
                "minecraft:polished_andesite_slab",0,CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB,CityLandUseSurfacePrintPlan.HorizontalFacing.NONE));
        return cells;
    }
    private Map<BlockPoint,Integer> platforms(int length) {
        Map<BlockPoint,Integer> cells=new HashMap<>();
        for(int z=0;z<length;z++)cells.put(new BlockPoint(0,z),z<length/2?76:72);
        return cells;
    }
    @Test void wideRoadKeepsCrossSectionLevelInsteadOfStairsAcrossTheCarriageway() {
        var features = new ArrayList<CityLandUseSurfacePrintPlan.FeatureCell>();
        for (int x = 0; x < 8; x++) for (int z = -2; z <= 2; z++)
            features.add(new CityLandUseSurfacePrintPlan.FeatureCell("street", x, z, "minecraft:stone_brick_slab", 0,
                    CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB, CityLandUseSurfacePrintPlan.HorizontalFacing.NONE));
        Map<BlockPoint, Integer> platforms = new HashMap<>();
        for (int x = 0; x < 8; x++) for (int z = -2; z <= 2; z++)
            platforms.put(new BlockPoint(x, z), z <= 0 ? 72 : 68);
        var result = new CityTerraceRoadGradePlanner().plan(features, platforms, Map.of(), (x, z) -> 70);
        for (int x = 0; x < 8; x++) {
            int section = x;
            var heights = result.stream().filter(f -> f.x() == section).map(f -> f.targetSurfaceY()).distinct().toList();
            assertEquals(1, heights.size(), "cross section x=" + section + " -> " + heights);
        }
        assertTrue(result.stream().noneMatch(f -> f.kind() == CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR
                && (f.facing() == CityLandUseSurfacePrintPlan.HorizontalFacing.NORTH
                || f.facing() == CityLandUseSurfacePrintPlan.HorizontalFacing.SOUTH)));
    }

    @Test void stairsOnAnEastWestRoadOnlyFaceAlongTheRoad() {
        var features = new ArrayList<CityLandUseSurfacePrintPlan.FeatureCell>();
        for (int x = 0; x < 8; x++) for (int z = -1; z <= 1; z++)
            features.add(new CityLandUseSurfacePrintPlan.FeatureCell("street", x, z, "minecraft:stone_brick_slab", 0,
                    CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB, CityLandUseSurfacePrintPlan.HorizontalFacing.NONE));
        Map<BlockPoint, Integer> platforms = new HashMap<>();
        for (int x = 0; x < 8; x++) for (int z = -1; z <= 1; z++)
            platforms.put(new BlockPoint(x, z), x < 4 ? 76 : 72);
        var result = new CityTerraceRoadGradePlanner().plan(features, platforms,
                Map.of(new BlockPoint(0, 0), 76, new BlockPoint(7, 0), 72), (x, z) -> 70);
        var stairs = result.stream().filter(f -> f.kind() == CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR).toList();
        assertFalse(stairs.isEmpty());
        assertTrue(stairs.stream().allMatch(f -> f.facing() == CityLandUseSurfacePrintPlan.HorizontalFacing.EAST
                || f.facing() == CityLandUseSurfacePrintPlan.HorizontalFacing.WEST), stairs.toString());
    }

    @Test void joinsSplitApproachesWithoutMovingEntranceHeights() {
        var features=lane(8);var pins=Map.of(new BlockPoint(0,0),76,new BlockPoint(0,7),72);
        var planner=new CityTerraceRoadGradePlanner();var result=planner.plan(features,platforms(8),pins,(x,z)->70);
        assertEquals(76,result.get(0).targetSurfaceY());assertEquals(72,result.get(7).targetSurfaceY());
        assertTrue(result.stream().filter(f->f.kind()==CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR).count()>=4);
        for(var f:result)if(f.kind()==CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR)
            assertEquals(CityLandUseSurfacePrintPlan.HorizontalFacing.NORTH,f.facing());
        var reversed=new ArrayList<>(features);Collections.reverse(reversed);
        var again=new ArrayList<>(planner.plan(reversed,platforms(8),pins,(x,z)->70));Collections.reverse(again);
        assertEquals(result,again);
    }
    @Test void rejectsTooShortTransitionInsteadOfMovingDoors() {
        assertThrows(IllegalArgumentException.class,()->new CityTerraceRoadGradePlanner().plan(lane(3),platforms(3),
                Map.of(new BlockPoint(0,0),76,new BlockPoint(0,2),72),(x,z)->70));
    }
    @Test void equalHeightPlatformsKeepExistingFrozenRoads() {
        var features=lane(8);var platforms=platforms(8);platforms.replaceAll((p,y)->72);
        assertSame(features,new CityTerraceRoadGradePlanner().plan(features,platforms,Map.of(),(x,z)->70));
    }
}
