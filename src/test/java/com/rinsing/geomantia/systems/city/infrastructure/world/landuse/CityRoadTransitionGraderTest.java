package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityRoadTransitionGraderTest {
    @Test void fullBlockVillagePaletteUsesFrozenConfiguredStairsAtThePlatformTransition() {
        var original=fragment(0,true);
        var all=original.gradingFeatureOperations().stream().map(f->new CityLandUseChunkCompiler.FeatureOperation(
                f.sourceId(),f.x(),f.z(),"minecraft:coarse_dirt",f.surfaceOffset(),f.kind(),f.facing(),f.targetSurfaceY())).toList();
        var field=new com.rinsing.geomantia.systems.city.domain.landuse.CityMaterialField(
                new com.rinsing.geomantia.systems.city.domain.blueprint.CitySurfaceMaterials(Map.of(),Map.of(),
                        Map.of("COMPACT_ALLEY",Map.of("roadStair","minecraft:oak_stairs")),Map.of()),List.of(),
                List.of(new com.rinsing.geomantia.systems.city.domain.landuse.CityMaterialField.Road("lane","COMPACT_ALLEY",
                        new com.rinsing.geomantia.systems.city.domain.model.BlockBounds(-16,8,31,8))));
        var fragment=new CityLandUseChunkCompiler.ChunkFragment(original.schema(),original.cityId(),original.planHash(),original.paletteHash(),
                0,0,16,0,0,0,null,List.of(),List.of(),List.of(),all.stream().filter(f->f.x()>=0&&f.x()<16).toList(),all,List.of(),List.of(),field);
        var result=CityRoadTransitionGrader.grade(fragment,terrain());
        assertTrue(result.stream().anyMatch(f->f.x()==14 && f.targetSurfaceY()==89 && f.blockId().equals("minecraft:oak_stairs")));
        assertTrue(result.stream().anyMatch(f->f.x()==0 && f.blockId().equals("minecraft:coarse_dirt")));
    }
    @Test void sevenBlockDropAtNaturalRoadPlatformBoundaryBecomesAContinuousStairRun() {
        var fragment=fragment(0,true);
        var roads=CityRoadTransitionGrader.grade(fragment,terrain());
        var heights=new TreeMap<Integer,Integer>();
        for(var road:roads) heights.put(road.x(),road.targetSurfaceY()==null?95:road.targetSurfaceY());
        assertEquals(88,heights.get(15));
        assertTrue(roads.stream().anyMatch(f->f.kind()==CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR));
        for(int x=1;x<16;x++) assertTrue(Math.abs(heights.get(x)-heights.get(x-1))<=1,heights.toString());
    }

    @Test void adjoiningOwnersUseTheSameTransitionRegardlessOfCompilationOrder() {
        var left=CityRoadTransitionGrader.grade(fragment(0,true),terrain());
        var right=CityRoadTransitionGrader.grade(fragment(1,true),terrain());
        assertEquals(left,CityRoadTransitionGrader.grade(fragment(0,true),terrain()));
        var a=left.get(left.size()-1);var b=right.get(0);
        assertTrue(Math.abs(a.targetSurfaceY()-b.targetSurfaceY())<=1);
    }

    @Test void villageRoadWithoutAnyPlatformKeepsActualTerrainFollowing() {
        var original=fragment(0,false);
        assertEquals(original.featureOperations(),CityRoadTransitionGrader.grade(original,terrain()));
    }

    private static CityLandUseMicroGrader.TerrainView terrain() {
        return (x,z)->new CityLandUseChunkExecutor.ColumnSample(95,"minecraft:grass_block",true);
    }
    private static CityLandUseChunkCompiler.ChunkFragment fragment(int owner,boolean platform) {
        List<CityLandUseChunkCompiler.FeatureOperation> all=new ArrayList<>();
        for(int x=owner*16-16;x<=owner*16+31;x++)all.add(new CityLandUseChunkCompiler.FeatureOperation(
                "lane",x,8,"minecraft:mud_brick_slab",0,CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB,
                CityLandUseSurfacePrintPlan.HorizontalFacing.NONE,platform&&x>=15?88:null));
        return new CityLandUseChunkCompiler.ChunkFragment(CityLandUseChunkCompiler.RESULT_SCHEMA,"city","hash","palette",
                owner,0,16,0,0,0,null,List.of(),List.of(),List.of(),
                all.stream().filter(f->Math.floorDiv(f.x(),16)==owner).toList(),all,List.of(),List.of());
    }
}
