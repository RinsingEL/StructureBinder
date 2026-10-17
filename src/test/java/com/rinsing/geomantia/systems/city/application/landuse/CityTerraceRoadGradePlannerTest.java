package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityTerraceRoadGradePlannerTest {
    @Test void overlappingBandMetadataKeepsTheWholeIntersectionAtOneGrade(){
        var features=new HashMap<BlockPoint,CityLandUseSurfacePrintPlan.FeatureCell>();var levels=new HashMap<BlockPoint,Integer>();
        for(int z=-10;z<=10;z++)for(int x=-1;x<=1;x++)features.put(new BlockPoint(x,z),new CityLandUseSurfacePrintPlan.FeatureCell("north",x,z,"minecraft:stone_brick_slab",0,CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB,CityLandUseSurfacePrintPlan.HorizontalFacing.NONE,66));
        for(int x=-10;x<=10;x++)for(int z=-1;z<=1;z++)features.put(new BlockPoint(x,z),new CityLandUseSurfacePrintPlan.FeatureCell("east",x,z,"minecraft:stone_brick_slab",0,CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB,CityLandUseSurfacePrintPlan.HorizontalFacing.NONE,66));
        features.keySet().forEach(p->levels.put(p,p.z()<0?72:68));
        var bands=List.of(new LandUseSourceResolver.RoadBand("north","n","CITY_MAIN_ROAD",new BlockPoint(0,-10),new BlockPoint(0,10),new com.rinsing.geomantia.systems.city.domain.model.BlockBounds(-1,-10,1,10),3,""),
                new LandUseSourceResolver.RoadBand("east","n","CITY_MAIN_ROAD",new BlockPoint(-10,0),new BlockPoint(10,0),new com.rinsing.geomantia.systems.city.domain.model.BlockBounds(-10,-1,10,1),3,""));
        var result=new CityTerraceRoadGradePlanner().plan(List.copyOf(features.values()),levels,Map.of(),(x,z)->66,bands);
        assertEquals(1,result.stream().filter(f->Math.abs(f.x())<=1&&Math.abs(f.z())<=1).map(f->f.targetSurfaceY()-(f.kind()==CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR?1:0)).distinct().count());
    }
    @Test void aTerraceBoundaryAlongTheRoadCannotProduceTransverseStairs() {
        var features=new ArrayList<CityLandUseSurfacePrintPlan.FeatureCell>();var platform=new HashMap<BlockPoint,Integer>();
        for(int z=0;z<50;z++)for(int x=0;x<7;x++) {
            features.add(new CityLandUseSurfacePrintPlan.FeatureCell("main",x,z,"minecraft:stone_brick_slab",0,
                    CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB,CityLandUseSurfacePrintPlan.HorizontalFacing.NONE,66));
            platform.put(new BlockPoint(x,z),x<2?68:72);
        }
        var result=new CityTerraceRoadGradePlanner().plan(features,platform,Map.of(),(x,z)->66);
        for(int z=0;z<50;z++){int row=z;assertEquals(1,result.stream().filter(f->f.z()==row).map(f->f.targetSurfaceY()).distinct().count());}
        assertTrue(result.stream().noneMatch(f->f.facing()==CityLandUseSurfacePrintPlan.HorizontalFacing.EAST||f.facing()==CityLandUseSurfacePrintPlan.HorizontalFacing.WEST));
    }
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
