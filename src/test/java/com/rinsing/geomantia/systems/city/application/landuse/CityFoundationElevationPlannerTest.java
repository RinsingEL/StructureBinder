package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CityFoundationElevationPlannerTest {
    @Test void shoreBuildingUsesItsExistingDryDatumWindowBeforeRejectingWetComponent() {
        var footprint = new BlockBounds(-2865,5505,-2855,5517);
        var spans = new ArrayList<LandUseAreaPlan.ScanlineSpan>();
        for (int z=5505;z<=5517;z++) spans.add(new LandUseAreaPlan.ScanlineSpan(z,-2865,-2855));
        var cells = new ArrayList<LandUseTerrainField.Cell>();
        for (int z=343;z<=345;z++) for (int x=-180;x<=-178;x++)
            cells.add(coarseCell(x,z,z!=343 || x==-179, true, z==343 && x!=-179?62:61));
        var terrain = new LandUseTerrainField(LandUseTerrainField.SCHEMA,"city",
                new BlockBounds(-2880,5488,-2833,5535),16,cells);
        var result = CityFoundationElevationPlanner.plan(spans,terrain,List.of(footprint));
        assertEquals(143,result.stream().mapToInt(s->s.maxX()-s.minX()+1).sum());
        assertTrue(result.stream().allMatch(s->s.targetY()==62));
        Collections.reverse(cells);
        assertEquals(result,CityFoundationElevationPlanner.plan(spans,new LandUseTerrainField(
                LandUseTerrainField.SCHEMA,"city",terrain.planningBounds(),16,cells),List.of(footprint)));
        assertThrows(IllegalArgumentException.class,()->CityFoundationElevationPlanner.plan(spans,terrain));
    }

    @Test void wetCoreCenterCanUseTheSameLocalDryEvidence() {
        var terrain = coarseTerrain(List.of(coarseCell(0,0,true,true,61),coarseCell(1,0,false,true,65)));
        var result = CityFoundationElevationPlanner.planForCore(
                List.of(new LandUseAreaPlan.ScanlineSpan(0,0,0)),terrain,new BlockBounds(0,0,10,10));
        assertEquals(65,result.get(0).targetY());
    }

    @Test void unsupportedWaterDoesNotBorrowDistantOrUnrelatedBuildingHeights() {
        var spans = List.of(new LandUseAreaPlan.ScanlineSpan(0,0,10));
        var terrain = coarseTerrain(List.of(coarseCell(0,0,true,true,61),coarseCell(4,0,false,true,80)));
        var ex=assertThrows(IllegalArgumentException.class,()->CityFoundationElevationPlanner.plan(spans,terrain,
                List.of(new BlockBounds(0,0,10,10),new BlockBounds(64,0,74,10))));
        assertTrue(ex.getMessage().startsWith("CITY_FOUNDATION_ELEVATION_UNAVAILABLE"));
    }

    @Test void unsampledOrNonfiniteNeighborCannotSupplyFoundationDatum() {
        for (var neighbor:List.of(coarseCell(1,0,false,false,65),coarseCell(1,0,false,true,Double.NaN),
                coarseCell(1,0,false,true,Double.POSITIVE_INFINITY))) {
            var terrain=coarseTerrain(List.of(coarseCell(0,0,true,true,61),neighbor));
            assertThrows(IllegalArgumentException.class,()->CityFoundationElevationPlanner.planForCore(
                    List.of(new LandUseAreaPlan.ScanlineSpan(0,0,0)),terrain,new BlockBounds(0,0,10,10)));
        }
    }

    private static LandUseTerrainField coarseTerrain(List<LandUseTerrainField.Cell> cells) {
        return new LandUseTerrainField(LandUseTerrainField.SCHEMA,"city",new BlockBounds(0,0,95,31),16,cells);
    }
    private static LandUseTerrainField.Cell coarseCell(int x,int z,boolean water,boolean sampled,double y) {
        return new LandUseTerrainField.Cell(x,z,x*16,z*16,16,y,0,0,0,water,0,0,
                "minecraft:plains",water?"water":"plain","patch",sampled);
    }

    @Test void coreDatumDoesNotInventFourBlockTerracesOnLevelTerrain() {
        for(int natural:List.of(65,66,67,69)){
            var cells=new ArrayList<LandUseTerrainField.Cell>();
            for(int x=-32;x<=31;x++)cells.add(cell(x,natural));
            var result=CityFoundationElevationPlanner.planForCore(List.of(new LandUseAreaPlan.ScanlineSpan(0,-16,16)),
                    terrain(cells),new BlockBounds(-2,0,2,0));
            assertTrue(result.stream().allMatch(s->s.targetY()==natural));
        }
    }
    @Test
    void completePlatformClosesDeepPitAndKeepsSameHeightAcrossNegativeChunkBoundary() {
        var spans = List.of(new LandUseAreaPlan.ScanlineSpan(0, -32, 31));
        var cells = new ArrayList<LandUseTerrainField.Cell>();
        for (int x=-32; x<=31; x++) cells.add(cell(x, x>=-8 && x<8 ? 44 : 68));
        var result = CityFoundationElevationPlanner.plan(spans, terrain(cells));
        assertEquals(List.of(new CityLandUseSurfacePrintPlan.PlatformSpan(0,-32,31,68)), result);
        Collections.reverse(cells);
        assertEquals(result, CityFoundationElevationPlanner.plan(spans, terrain(cells)));
    }

    @Test
    void genuinelySeparatedPlatformsCanRetainDifferentHeights() {
        var spans = List.of(new LandUseAreaPlan.ScanlineSpan(0,-4,-1),
                new LandUseAreaPlan.ScanlineSpan(0,1,4));
        var cells = new ArrayList<LandUseTerrainField.Cell>();
        for(int x=-4;x<=4;x++) cells.add(cell(x,x<0?68:76));
        assertEquals(List.of(new CityLandUseSurfacePrintPlan.PlatformSpan(0,-4,-1,68),
                new CityLandUseSurfacePrintPlan.PlatformSpan(0,1,4,76)),
                CityFoundationElevationPlanner.plan(spans,terrain(cells)));
    }

    @Test void connectedSlopeRetainsBuildingLedTerraces() {
        var cells = new ArrayList<LandUseTerrainField.Cell>();
        for (int x=-32; x<=31; x++) cells.add(cell(x, x<0 ? 68 : 80));
        var result = CityFoundationElevationPlanner.plan(
                List.of(new LandUseAreaPlan.ScanlineSpan(0,-32,31)), terrain(cells),
                List.of(new BlockBounds(-28,0,-20,0), new BlockBounds(20,0,28,0)));
        assertTrue(result.stream().anyMatch(span -> span.targetY() == 68));
        assertTrue(result.stream().anyMatch(span -> span.targetY() == 80));
        assertEquals(64, result.stream().mapToInt(span -> span.maxX()-span.minX()+1).sum());
    }

    @Test void declaredCoreKeepsNestedSupportBuildingsOnItsDatumAcrossDifferentTerrain() {
        var cells = new ArrayList<LandUseTerrainField.Cell>();
        for (int x=-32; x<=31; x++) cells.add(cell(x, x<0 ? 68 : 80));
        var spans = List.of(new LandUseAreaPlan.ScanlineSpan(0,-32,31));
        var lowCore = new BlockBounds(-28,0,-20,0);
        assertEquals(List.of(new CityLandUseSurfacePrintPlan.PlatformSpan(0,-32,31,68)),
                CityFoundationElevationPlanner.planForCore(spans,terrain(cells),lowCore));
        assertEquals(List.of(new CityLandUseSurfacePrintPlan.PlatformSpan(0,-32,31,80)),
                CityFoundationElevationPlanner.planForCore(spans,terrain(cells),new BlockBounds(20,0,28,0)));
        Collections.reverse(cells);
        assertEquals(List.of(new CityLandUseSurfacePrintPlan.PlatformSpan(0,-32,31,68)),
                CityFoundationElevationPlanner.planForCore(spans,terrain(cells),lowCore));
    }

    @Test void explicitOffsetAppliesToTheWholeCoreGroupAndRejectsOverflow() {
        var cells=new ArrayList<LandUseTerrainField.Cell>();for(int x=-32;x<=31;x++)cells.add(cell(x,68));
        var spans=List.of(new LandUseAreaPlan.ScanlineSpan(0,-10,10));var core=new BlockBounds(-4,0,4,0);
        var result=CityFoundationElevationPlanner.planForCore(spans,terrain(cells),core,4);
        assertEquals(List.of(new CityLandUseSurfacePrintPlan.PlatformSpan(0,-10,10,72)),result);
        assertThrows(IllegalArgumentException.class,()->CityFoundationElevationPlanner.planForCore(spans,terrain(cells),core,Integer.MIN_VALUE));
    }

    private static LandUseTerrainField terrain(List<LandUseTerrainField.Cell> cells) {
        return new LandUseTerrainField(LandUseTerrainField.SCHEMA,"city",new BlockBounds(-32,0,31,0),1,cells);
    }
    private static LandUseTerrainField.Cell cell(int x,int y) {
        return new LandUseTerrainField.Cell(x,0,x,0,1,y,0,0,0,false,0,0,
                "minecraft:plains","plain","patch",true);
    }
}
