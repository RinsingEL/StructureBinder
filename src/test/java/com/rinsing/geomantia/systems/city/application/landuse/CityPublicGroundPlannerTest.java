package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.algorithm.landuse.CityDistrictPlanner;
import com.rinsing.geomantia.systems.city.application.outdoor.CityPublicRealmPlanner;
import com.rinsing.geomantia.systems.city.domain.landuse.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan.*;

class CityPublicGroundPlannerTest {
    @Test void courtyardGreenGroundInheritsItsFrozenSharedPlatformInsteadOfNaturalLevel() {
        var floor=new HashMap<BlockPoint,Integer>();
        for(int z=0;z<16;z++)for(int x=0;x<32;x++)floor.put(new BlockPoint(x,z),68);
        var members=java.util.stream.IntStream.range(0,16).mapToObj(z->new LandUseAreaPlan.ScanlineSpan(z,0,31)).toList();
        var area=new AreaPrint("court","court",List.of("city::foundation"),LandUseSurfaceSettings.defaults(SurfacePolicy.PAVE),
                members,List.of(),LandUseSurfaceSettings.SurfaceAlgorithm.UNIFORM,null,
                new UniformRecipe("minecraft:stone_bricks","",CitySharedTerracePlanner.spans(floor)));
        var result=new CityPublicGroundPlanner().plan(source(List.of(area),green()),
                district(List.of(new CityDistrictPlanner.Result.PublicSpace("court",bounds,"courtyard"))),terrain,
                (x,z)->new CityPublicRealmPlanner.Ground(65,true));
        assertTrue(result.surface().featureCells().stream().filter(f->f.surfaceOffset()==0)
                .allMatch(f->f.targetSurfaceY()==68));
    }
    @Test void unequalBanksUseABridgeStairRatherThanAFullBlockLip(){
        var output=new CityPublicGroundPlanner().plan(source(List.of(),green()),district(List.of()),terrain,
                (x,z)->new CityPublicRealmPlanner.Ground(x>=8&&x<=15?34:x>=16?63:62,true));
        var stairs=output.surface().featureCells().stream().filter(f->f.kind()==FeatureKind.ROAD_STAIR).toList();assertFalse(stairs.isEmpty());
        assertTrue(stairs.stream().allMatch(f->f.sourceId().startsWith(CityEntranceAccessPlanner.BRIDGE_SOURCE)&&f.facing()==HorizontalFacing.EAST&&f.targetSurfaceY()==63));
    }
    private final BlockBounds bounds=new BlockBounds(0,0,31,15);
    private final LandUseTerrainField terrain=new LandUseTerrainField(LandUseTerrainField.SCHEMA,"city",bounds,16,List.of());
    private CityDistrictPlanner.Result district(List<CityDistrictPlanner.Result.PublicSpace> spaces){
        return new CityDistrictPlanner.Result(Set.of(),Set.of(),Set.of(),Set.of(),Map.of(),2,0).withPublicSpaces(spaces);
    }
    private CityLandUseSurfacePrintPlan source(List<AreaPrint> areas,List<FeatureCell> features){
        return new CityLandUseSurfacePrintPlan(SCHEMA,"city","landuse","",areas,List.of(),features);
    }
    private List<FeatureCell> green(){
        List<FeatureCell> features=new ArrayList<>();
        for(int z=0;z<16;z++)for(int x=0;x<32;x++)features.add(new FeatureCell(CityPublicGreeneryPlanner.SOURCE+"city",x,z,
                z==7?"minecraft:polished_andesite":"minecraft:grass_block",0,z==7?FeatureKind.GREEN_PATH:FeatureKind.GREEN_GROUND,HorizontalFacing.NONE));
        return features;
    }
    @Test void spansTheRecordedDeepDryCavityWithoutFillingTwentyEightBlocks(){
        var output=new CityPublicGroundPlanner().plan(source(List.of(),green()),district(List.of()),terrain,
                (x,z)->new CityPublicRealmPlanner.Ground(x>=8&&x<=15?34:62,true));
        var deck=output.surface().featureCells().stream().filter(f->f.kind()==FeatureKind.BRIDGE_DECK&&f.z()==7).toList();
        assertEquals(8,deck.size());assertTrue(deck.stream().allMatch(f->f.targetSurfaceY()==62&&f.z()==7));
        assertEquals(8,output.report().get("publicPathBridgeColumns").getAsInt());
        assertTrue(output.surface().featureCells().stream().filter(f->f.kind()==FeatureKind.GREEN_PATH).allMatch(f->f.targetSurfaceY()==62));
        for(var rail:output.surface().featureCells().stream().filter(f->f.kind()==FeatureKind.BRIDGE_RAIL).toList())assertTrue(output.surface().featureCells().stream().anyMatch(f->f.x()==rail.x()&&f.z()==rail.z()&&f.kind()==FeatureKind.BRIDGE_DECK&&f.targetSurfaceY().equals(rail.targetSurfaceY())),"every post stands directly on its bridge deck");
    }
    @Test void clipsIndividualWetColumnsInsideACoarseDryCellAndFlattensAuthoredCourt(){
        Map<BlockPoint,Integer> floor=new HashMap<>();for(int z=0;z<16;z++)for(int x=0;x<32;x++)floor.put(new BlockPoint(x,z),62);
        var members=java.util.stream.IntStream.range(0,16).mapToObj(z->new LandUseAreaPlan.ScanlineSpan(z,0,31)).toList();
        var area=new AreaPrint("court","court",List.of("city::foundation"),LandUseSurfaceSettings.defaults(SurfacePolicy.PAVE),
                members,List.of(),LandUseSurfaceSettings.SurfaceAlgorithm.UNIFORM,null,
                new UniformRecipe("minecraft:stone_bricks","",CitySharedTerracePlanner.spans(floor)));
        var output=new CityPublicGroundPlanner().plan(source(List.of(area),green()),
                district(List.of(new CityDistrictPlanner.Result.PublicSpace("court",bounds,"courtyard"))),terrain,
                (x,z)->new CityPublicRealmPlanner.Ground(62+(x%3==0?1:0),x<28));
        assertTrue(output.surface().featureCells().stream().allMatch(f->f.x()<29));
        assertTrue(output.surface().featureCells().stream().filter(f->f.surfaceOffset()==0).allMatch(f->f.targetSurfaceY()==62));
        var recipe=(UniformRecipe)output.surface().areas().get(0).recipe();assertTrue(recipe.platformSpans().stream().allMatch(s->s.maxX()<29));
        assertEquals(members,output.surface().areas().get(0).memberSpans(),"accepted Area identity must survive shoreline clipping");
        assertEquals(4*16,output.surface().areas().get(0).exclusionSpans().stream().mapToInt(s->s.maxX()-s.minX()+1).sum());
        var lot=new LandUseAreaPlan.Area("court","foundation","urban",List.of("city::foundation"),List.of(),List.of(new BlockPoint(0,0)),members,List.of(),List.of(),List.of(),1,SurfacePolicy.PAVE,VegetationPolicy.CLEAR,BoundaryPolicy.OPEN);
        var plan=new LandUseAreaPlanCodec().withComputedHash(new LandUseAreaPlan(LandUseAreaPlan.SCHEMA,"test","city","",bounds,List.of(lot),List.of(),List.of(),List.of()));
        var clipped=output.surface();clipped=new CityLandUseSurfacePrintPlanCodec().withComputedHash(new CityLandUseSurfacePrintPlan(clipped.schema(),clipped.cityId(),plan.planHash(),"",clipped.areas(),clipped.sharedBoundarySpans(),clipped.featureCells(),clipped.materialField()));
        var compiler=new com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseChunkCompiler();
        for(int chunkX=0;chunkX<2;chunkX++){var fragment=compiler.compile(plan,clipped,chunkX,0);
            assertTrue(fragment.surfaceOperations().stream().allMatch(s->s.x()<28));
            assertTrue(fragment.gradingMaskCells().stream().allMatch(s->s.x()<28),"excluded water must never become unfrozen foundation grading");}
        assertFalse(output.report().getAsJsonArray("warnings").isEmpty());
    }
}
