package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CityLandscapeSurfaceGateTest {
    private static CityLandUseChunkCompiler.GradingMaskCell cell(int x,int z) {
        return new CityLandUseChunkCompiler.GradingMaskCell("field",x,z,false,64,true,6);
    }
    private static CityLandUseChunkExecutor.ColumnSample ground(int y) {
        return new CityLandUseChunkExecutor.ColumnSample(y,"minecraft:dirt",true);
    }
    @Test void canyonBottomIsRejectedEvenWhenLocallyFlat() {
        assertNull(CityLandscapeSurfaceGate.target(cell(0,0),(x,z)->ground(35)));
        assertNull(CityLandscapeSurfaceGate.target(cell(0,0),(x,z)->ground(x>0?40:64)));
    }
    @Test void enclosedShallowPitIsFilledButWideDepressionIsNotFlattened() {
        assertEquals(64,CityLandscapeSurfaceGate.target(cell(0,0),(x,z)->ground(x==0&&z==0?62:64)));
        assertEquals(62,CityLandscapeSurfaceGate.target(cell(0,0),(x,z)->ground(62)));
        assertNull(CityLandscapeSurfaceGate.target(cell(0,0),(x,z)->ground(x==0&&z==0?58:64)));
    }
    @Test void waterAndUnavailableNeighborsAreLocallySkipped() {
        assertNull(CityLandscapeSurfaceGate.target(cell(0,0),(x,z)->new CityLandUseChunkExecutor.ColumnSample(64,"minecraft:water",true)));
        assertNull(CityLandscapeSurfaceGate.target(cell(15,0),(x,z)->x==16?null:ground(64)));
    }
    @Test void negativeChunkSeamUsesTheSameTerrainDecisionAndBoundedReads() {
        for(int x:new int[]{-17,-16,-1,0,15,16}) {
            int[] calls={0};
            Integer y=CityLandscapeSurfaceGate.target(cell(x,0),(px,pz)->{calls[0]++;return ground(64);});
            assertEquals(64,y); assertTrue(calls[0]<=18);
        }
    }
    @Test void neighboringFinishedFarmlandDoesNotChangePitRepair() {
        var before=CityLandscapeSurfaceGate.target(cell(15,0),(x,z)->ground(x==15&&z==0?62:64));
        var after=CityLandscapeSurfaceGate.target(cell(15,0),(x,z)-> x>=16
                ?new CityLandUseChunkExecutor.ColumnSample(64,"minecraft:farmland",false):ground(x==15&&z==0?62:64));
        assertEquals(before,after);
    }
}
