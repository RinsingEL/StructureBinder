package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.algorithm.landuse.CityDistrictPlanner;
import com.rinsing.geomantia.systems.city.domain.landuse.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan.*;

class CityDesignedPublicSpacePlannerTest {
    private static CityLandUseSurfacePrintPlan source(){
        List<LandUseAreaPlan.ScanlineSpan> spans=new ArrayList<>();List<PlatformSpan> platforms=new ArrayList<>();
        for(int z=0;z<40;z++){spans.add(new LandUseAreaPlan.ScanlineSpan(z,0,39));platforms.add(new PlatformSpan(z,0,39,68));}
        var print=new AreaPrint("ground","ground",List.of("city::foundation"),LandUseSurfaceSettings.defaults(SurfacePolicy.PAVE),
                spans,List.of(),LandUseSurfaceSettings.SurfaceAlgorithm.UNIFORM,null,new UniformRecipe("minecraft:stone_bricks","",platforms));
        List<FeatureCell> features=new ArrayList<>();
        for(int z=0;z<40;z++)features.add(new FeatureCell("street",1,z,"minecraft:stone_slab",0,FeatureKind.ROAD_SLAB,HorizontalFacing.NONE,68));
        return new CityLandUseSurfacePrintPlan(SCHEMA,"city","hash","",List.of(print),List.of(),features);
    }
    private static Set<BlockPoint> available(){Set<BlockPoint> mask=new HashSet<>();for(int z=0;z<40;z++)for(int x=4;x<40;x++)mask.add(new BlockPoint(x,z));return mask;}
    @Test void bedsTreesFixturesAndRoadClearanceAreFrozenAndIndependentOfTheme(){
        var planner=new CityDesignedPublicSpacePlanner();var spaces=List.of(new CityDistrictPlanner.Result.PublicSpace("civic",new BlockBounds(0,0,39,39),"public-garden"));
        var first=planner.plan(source(),spaces,available(),true,CityDesignedPublicSpacePlanner.Palette.load());
        assertEquals(first,planner.plan(source(),spaces,available(),true,CityDesignedPublicSpacePlanner.Palette.load()));
        assertTrue(first.stream().anyMatch(f->f.blockId().equals("minecraft:oak_log")&&f.surfaceOffset()==4));
        assertTrue(first.stream().anyMatch(f->f.blockId().equals("minecraft:lantern")));
        assertTrue(first.stream().anyMatch(f->f.blockId().equals("minecraft:stone_brick_stairs")&&f.facing()!=HorizontalFacing.NONE));
        assertTrue(first.stream().filter(f->!f.sourceId().equals("street")).allMatch(f->available().contains(new BlockPoint(f.x(),f.z()))));
        assertTrue(first.stream().filter(f->f.kind()==FeatureKind.GREEN_PLANT).allMatch(f->f.surfaceOffset()<=5),"small trees respect the courtyard scale");
        var off=planner.plan(source(),spaces,available(),false,CityDesignedPublicSpacePlanner.Palette.load());
        assertTrue(off.stream().noneMatch(f->f.kind()==FeatureKind.GREEN_PLANT));
        assertTrue(off.stream().anyMatch(f->f.blockId().equals("minecraft:lantern")));
        assertEquals(40,off.stream().filter(f->f.sourceId().equals("street")).count());
    }
}
