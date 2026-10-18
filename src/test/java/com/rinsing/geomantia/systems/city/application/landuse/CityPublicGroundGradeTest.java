package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.application.outdoor.CityPublicRealmPlanner;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityPublicGroundGradeTest {
    @Test void greenMeetsBothHigherPavementAndNaturalEdgeAcrossTileAndChunkSeams() {
        for(int origin:List.of(-19,-16,-8,0,7,15)){
            Map<BlockPoint,Integer> green=new HashMap<>(),fixed=new HashMap<>();
            for(int z=0;z<12;z++)for(int x=0;x<12;x++)green.put(new BlockPoint(origin+x,z),65);
            for(int z=-1;z<=12;z++)fixed.put(new BlockPoint(origin-1,z),69);
            var result=CityPublicGroundGrade.solve(green,fixed,p->new CityPublicRealmPlanner.Ground(65,true));
            // The side edges cannot both meet Y65 and the Y69 pavement at their corner: record
            // that impossible boundary, and verify the unconstrained middle cross-section.
            for(int x=0;x<11;x++)assertTrue(Math.abs(result.heights().get(new BlockPoint(origin+x,6))
                    -result.heights().get(new BlockPoint(origin+x+1,6)))<=1,result.toString());
            assertEquals(69,result.heights().get(new BlockPoint(origin,6)));
            assertEquals(65,result.heights().get(new BlockPoint(origin+11,6)));
        }
    }
    @Test void incompatibleFixedLandingsAreReportedAndNeverAveraged() {
        Map<BlockPoint,Integer> preferred=new HashMap<>(),fixed=new HashMap<>();
        for(int x=0;x<3;x++)preferred.put(new BlockPoint(x,0),65);
        fixed.put(new BlockPoint(0,0),65);fixed.put(new BlockPoint(2,0),70);
        var result=CityPublicGroundGrade.solve(preferred,fixed,p->new CityPublicRealmPlanner.Ground(65,true));
        assertFalse(result.conflicts().isEmpty());
        assertEquals(65,result.heights().get(new BlockPoint(0,0)));
        assertEquals(70,result.heights().get(new BlockPoint(2,0)));
    }
}
