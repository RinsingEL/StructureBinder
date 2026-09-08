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

    private static LandUseTerrainField terrain(List<LandUseTerrainField.Cell> cells) {
        return new LandUseTerrainField(LandUseTerrainField.SCHEMA,"city",new BlockBounds(-32,0,31,0),1,cells);
    }
    private static LandUseTerrainField.Cell cell(int x,int y) {
        return new LandUseTerrainField.Cell(x,0,x,0,1,y,0,0,0,false,0,0,
                "minecraft:plains","plain","patch",true);
    }
}
