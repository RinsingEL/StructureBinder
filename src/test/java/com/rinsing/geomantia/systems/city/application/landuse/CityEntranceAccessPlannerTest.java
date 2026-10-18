package com.rinsing.geomantia.systems.city.application.landuse;
import com.rinsing.geomantia.systems.city.domain.landuse.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan.*;
class CityEntranceAccessPlannerTest {
    @Test void ascendingExistingRoadMustMeetTheFarLandingAfterEntranceRewrite() {
        var initial=source(66);
        var features=initial.featureCells().stream().map(f->new FeatureCell(f.sourceId(),f.x(),f.z(),f.blockId(),0,
                f.kind(),f.facing(),Math.min(66,62+(-2699-f.x())))).toList();
        var input=new CityLandUseSurfacePrintPlan(initial.schema(),initial.cityId(),initial.sourceLandUsePlanHash(),"",initial.areas(),List.of(),features);
        var result=new CityEntranceAccessPlanner().plan(input,List.of(new LandUseAreaPlan.GateSlot("dorm::front",
                new BlockPoint(-2698,5185),CardinalDirection.WEST,"dorm")),Set.of());
        assertEquals(1,result.report().get("connectedEntrances").getAsInt());
        for(int z=5184;z<=5186;z++){
            final int zz=z;var line=result.surface().featureCells().stream().filter(f->f.z()==zz).sorted(Comparator.comparingInt(FeatureCell::x).reversed()).toList();
            int previous=66;
            for(var f:line){int low=f.targetSurfaceY()-(f.kind()==FeatureKind.ROAD_STAIR?1:0);
                assertTrue(Math.abs(previous-low)<=1,"broken landing at "+f);previous=low;}
        }
    }
    private CityLandUseSurfacePrintPlan source(int floor){
        var area=new AreaPrint("foundation","foundation",List.of("city::foundation"),LandUseSurfaceSettings.defaults(SurfacePolicy.PAVE),
                List.of(new LandUseAreaPlan.ScanlineSpan(5185,-2698,-2693)),List.of(),LandUseSurfaceSettings.SurfaceAlgorithm.UNIFORM,null,
                new UniformRecipe("minecraft:stone_bricks","",List.of(new PlatformSpan(5185,-2698,-2693,floor))));
        var features=new ArrayList<FeatureCell>();for(int x=-2705;x<=-2699;x++)for(int z=5184;z<=5186;z++)features.add(new FeatureCell("bridge",x,z,"minecraft:stone_brick_slab",0,FeatureKind.BRIDGE_DECK,HorizontalFacing.NONE,62));
        return new CityLandUseSurfacePrintPlan(SCHEMA,"city","hash","",List.of(area),List.of(),features);
    }
    private CityEntranceAccessPlanner.Result plan(int floor){return new CityEntranceAccessPlanner().plan(source(floor),
            List.of(new LandUseAreaPlan.GateSlot("dorm::front",new BlockPoint(-2698,5185),CardinalDirection.WEST,"dorm")),Set.of(new BlockPoint(-2698,5185)));}
    @Test void recordedTwoBlockBridgeToDormLipGetsAThreeWideContinuousStair(){
        var result=plan(64);assertEquals(6,result.report().get("gradedApproachColumns").getAsInt());assertTrue(result.report().getAsJsonArray("warnings").isEmpty());
        for(int z=5184;z<=5186;z++)for(int x=-2700;x<=-2699;x++){
            int xx=x,zz=z;var f=result.surface().featureCells().stream().filter(c->c.x()==xx&&c.z()==zz).findFirst().orElseThrow();
            assertEquals(FeatureKind.ROAD_STAIR,f.kind());assertEquals(HorizontalFacing.EAST,f.facing());assertEquals(x==-2699?64:63,f.targetSurfaceY());assertTrue(f.sourceId().startsWith(CityEntranceAccessPlanner.BRIDGE_SOURCE));
        }
        assertTrue(result.surface().featureCells().stream().filter(f->f.x()<=-2701).allMatch(f->f.kind()==FeatureKind.BRIDGE_DECK&&f.targetSurfaceY()==62));
        assertTrue(result.surface().featureCells().stream().noneMatch(f->f.x()>=-2698),"authored building blocks are never rewritten");
    }
    @Test void anExistingSingleFileAlleyIsConnectedWithoutWideningIntoItsRails(){
        var full=source(64);var narrow=new CityLandUseSurfacePrintPlan(full.schema(),full.cityId(),full.sourceLandUsePlanHash(),"",full.areas(),List.of(),full.featureCells().stream().filter(f->f.z()==5185).toList());
        var result=new CityEntranceAccessPlanner().plan(narrow,List.of(new LandUseAreaPlan.GateSlot("dorm::front",new BlockPoint(-2698,5185),CardinalDirection.WEST,"dorm")),Set.of());
        assertEquals(2,result.report().get("gradedApproachColumns").getAsInt());assertTrue(result.report().getAsJsonArray("warnings").toString().contains("CITY_ENTRANCE_NARROW_STAIR"));
        assertTrue(result.surface().featureCells().stream().allMatch(f->f.z()==5185));
    }
    @Test void anUnreasonablyHighDoorReportsTheGapWithoutWritingPartialSteps(){var result=plan(70);assertEquals(new HashSet<>(source(70).featureCells()),new HashSet<>(result.surface().featureCells()));assertEquals(0,result.report().get("gradedApproachColumns").getAsInt());assertFalse(result.report().getAsJsonArray("warnings").isEmpty());}
}
