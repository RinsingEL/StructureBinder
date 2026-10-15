package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.algorithm.landuse.CityDistrictPlanner;
import com.rinsing.geomantia.systems.city.domain.landuse.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CitySharedTerracePlannerTest {
    private static final BlockBounds BOUNDS=new BlockBounds(-4,-8,40,28);
    private static Set<BlockPoint> mask(int minX,int maxX,int minZ,int maxZ){Set<BlockPoint>s=new HashSet<>();for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++)s.add(new BlockPoint(x,z));return s;}
    private static LandUseTerrainField terrain(boolean water,boolean valley){
        List<LandUseTerrainField.Cell> cells=new ArrayList<>();for(int z=-8;z<=28;z++)for(int x=-4;x<=40;x++)
            cells.add(new LandUseTerrainField.Cell(x,z,x,z,1,valley&&x==17?40:64,0,0,0,water&&x==17,0,10,"minecraft:plains","plain","p",true));
        return new LandUseTerrainField(LandUseTerrainField.SCHEMA,"city",BOUNDS,1,cells);
    }
    private static CityDistrictPlanner.Result district(){
        var west=mask(0,8,0,12);var east=mask(25,33,0,12);Set<BlockPoint> construction=new HashSet<>(west);construction.addAll(east);
        var envelope=mask(-2,38,-6,24);var natural=new HashSet<>(envelope);natural.removeAll(construction);
        return new CityDistrictPlanner.Result(construction,envelope,natural,Set.of(),Map.of("west",west,"east",east),4,2,
                Map.of("west",new BlockBounds(1,1,5,5),"east",new BlockBounds(27,1,31,5)));
    }
    private static CityLandUseSurfacePrintPlan source(){
        Map<BlockPoint,Integer> floor=new HashMap<>();district().construction().forEach(p->floor.put(p,72));
        var spans=floor.keySet().stream().sorted(Comparator.comparingInt(BlockPoint::z).thenComparingInt(BlockPoint::x))
                .map(p->new LandUseAreaPlan.ScanlineSpan(p.z(),p.x(),p.x())).toList();
        var area=new CityLandUseSurfacePrintPlan.AreaPrint("foundation","foundation",List.of("city::foundation"),
                LandUseSurfaceSettings.defaults(SurfacePolicy.PAVE),spans,List.of(),LandUseSurfaceSettings.SurfaceAlgorithm.UNIFORM,null,
                new CityLandUseSurfacePrintPlan.UniformRecipe("minecraft:stone_bricks","",CitySharedTerracePlanner.spans(floor)));
        List<CityLandUseSurfacePrintPlan.FeatureCell> roads=new ArrayList<>();
        for(int x=0;x<=33;x++)for(int z=13;z<=15;z++)roads.add(new CityLandUseSurfacePrintPlan.FeatureCell("street",x,z,"minecraft:stone_slab",0,
                CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB,CityLandUseSurfacePrintPlan.HorizontalFacing.NONE,64));
        return new CityLandUseSurfacePrintPlan(CityLandUseSurfacePrintPlan.SCHEMA,"city","hash","",List.of(area),List.of(),roads);
    }
    private static LandUseAreaPlan empty(){return new LandUseAreaPlan(LandUseAreaPlan.SCHEMA,"1","city","hash",BOUNDS,List.of(),List.of(),List.of(),List.of(),List.of());}
    private static CityLandUseSurfacePrintPlan plan(boolean water,boolean valley){return new CitySharedTerracePlanner().apply(source(),district(),terrain(water,valley),empty(),List.of(),CitySharedTerracePlanner.Settings.defaults());}
    @Test void freezesCoreDatumAndBuildsMatchingDryShouldersOnBothSides(){
        var result=plan(false,false);var recipe=(CityLandUseSurfacePrintPlan.UniformRecipe)result.areas().get(0).recipe();
        assertTrue(recipe.platformSpans().stream().allMatch(s->s.targetY()==64),"core determines the whole support array datum");
        var terraces=result.featureCells().stream().filter(f->f.kind()==CityLandUseSurfacePrintPlan.FeatureKind.TERRACE_GROUND).toList();
        assertFalse(terraces.isEmpty());assertTrue(terraces.stream().allMatch(f->f.targetSurfaceY()==64));
        assertTrue(terraces.stream().anyMatch(f->f.z()<13));assertTrue(terraces.stream().anyMatch(f->f.z()>15));
        assertEquals(result,plan(false,false),"whole-city planning must be deterministic");
        assertEquals(result,new CityLandUseSurfacePrintPlanCodec().fromJson(new CityLandUseSurfacePrintPlanCodec().toJson(result)));
    }
    @Test void preservesRiverValleyAndExistingPlatformFootprints(){
        for(var result:List.of(plan(true,false),plan(false,true))) {
            var terraces=result.featureCells().stream().filter(f->f.kind()==CityLandUseSurfacePrintPlan.FeatureKind.TERRACE_GROUND).toList();
            assertTrue(terraces.stream().noneMatch(f->f.x()==17));
            assertTrue(terraces.stream().noneMatch(f->district().construction().contains(new BlockPoint(f.x(),f.z()))));
        }
    }
    @Test void regradesInternalStreetWhenEqualHeightCoresReplaceOldDatum() {
        var original = source();
        List<CityLandUseSurfacePrintPlan.FeatureCell> roads = new ArrayList<>();
        for (int x = 0; x <= 8; x++) roads.add(new CityLandUseSurfacePrintPlan.FeatureCell("internal", x, 6,
                "minecraft:stone_slab", 0, CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB,
                CityLandUseSurfacePrintPlan.HorizontalFacing.NONE, 72));
        var input = new CityLandUseSurfacePrintPlan(original.schema(), original.cityId(), original.sourceLandUsePlanHash(), "",
                original.areas(), original.sharedBoundarySpans(), roads);
        var result = new CitySharedTerracePlanner().apply(input, district(), terrain(false, false), empty(),
                List.of(), CitySharedTerracePlanner.Settings.defaults());
        assertEquals(9, result.featureCells().stream().filter(f -> f.sourceId().equals("internal")
                && f.targetSurfaceY() == 64 && f.kind() == CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB).count());
    }
    @Test void bridgeDeckKeepsTerracePostsOutOfItsRailAndApproach() {
        var original=source();
        var features=new ArrayList<>(original.featureCells());
        var deck=new CityLandUseSurfacePrintPlan.FeatureCell("bridge",0,0,"minecraft:stone_slab",0,
                CityLandUseSurfacePrintPlan.FeatureKind.BRIDGE_DECK,CityLandUseSurfacePrintPlan.HorizontalFacing.NONE,62);
        var rail=new CityLandUseSurfacePrintPlan.FeatureCell("bridge",0,0,"minecraft:spruce_fence",1,
                CityLandUseSurfacePrintPlan.FeatureKind.BRIDGE_RAIL,CityLandUseSurfacePrintPlan.HorizontalFacing.NONE,62);
        features.add(deck);features.add(rail);
        var input=new CityLandUseSurfacePrintPlan(original.schema(),original.cityId(),original.sourceLandUsePlanHash(),"",
                original.areas(),original.sharedBoundarySpans(),features);
        List<LandUseTerrainField.Cell> samples=new ArrayList<>();
        for(int z=-8;z<=28;z++)for(int x=-4;x<=40;x++)
            samples.add(new LandUseTerrainField.Cell(x,z,x,z,1,x<0?60:64,0,0,0,false,0,10,"minecraft:plains","plain","p",true));
        var terrain=new LandUseTerrainField(LandUseTerrainField.SCHEMA,"city",BOUNDS,1,samples);
        var result=new CitySharedTerracePlanner().apply(input,district(),terrain,empty(),List.of(),CitySharedTerracePlanner.Settings.defaults());
        assertTrue(result.featureCells().contains(deck));assertTrue(result.featureCells().contains(rail));
        assertTrue(result.featureCells().stream().noneMatch(f->f.kind()==CityLandUseSurfacePrintPlan.FeatureKind.TERRACE_RAIL
                &&Math.abs(f.x())<=2&&Math.abs(f.z())<=2));
        assertTrue(result.featureCells().stream().anyMatch(f->f.kind()==CityLandUseSurfacePrintPlan.FeatureKind.TERRACE_RAIL),
                "Other exposed platform edges still need protection");
    }
}
