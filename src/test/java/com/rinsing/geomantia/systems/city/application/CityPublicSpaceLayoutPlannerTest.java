package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.rinsing.geomantia.systems.city.application.CityTemplatePlacementGeometry.*;
import static com.rinsing.geomantia.systems.city.application.CityPublicSpaceLayoutPlanner.*;

class CityPublicSpaceLayoutPlannerTest {
    private final CityPublicSpaceLayoutPlanner planner = new CityPublicSpaceLayoutPlanner();
    private final BlockBounds area = new BlockBounds(-40,-40,39,39);
    @Test void publicSpaceCanBeEmptyAndRejectsAccidentalFill() {
        var intent = new Intent(Kind.PUBLIC_PLAZA, area, 2, 5, 3);
        assertTrue(planner.plan(intent, null, List.of()).ok());
        assertFalse(planner.plan(intent, null, List.of(member())).ok());
        assertEquals(area, planner.plan(intent, member(), List.of()).publicSpace());
    }
    @Test void edgeMarketUsesRealWidthsAndDoesNotDistributeAroundAllEdges() {
        var result = planner.plan(new Intent(Kind.MARKET_EDGE, area, 2, 5, 4), null, Collections.nCopies(4, member()));
        assertTrue(result.ok(), result.failures().toString());
        assertEquals(4, result.placements().size());
        for (int i=1;i<4;i++) assertEquals(3,result.placements().get(i).footprint().minX()-result.placements().get(i-1).footprint().maxX());
        assertTrue(result.placements().stream().allMatch(p->p.footprint().maxZ()==-41));
    }
    @Test void interiorMarketPreservesCentralWalkwayAndCore() {
        var result = planner.plan(new Intent(Kind.MARKET_INTERIOR, area, 2, 5, 3), member(), Collections.nCopies(20, member()));
        assertTrue(result.ok(), result.failures().toString());
        var aisle = new BlockBounds(-3,-40,1,39);
        assertTrue(result.placements().stream().filter(p->!p.core()).noneMatch(p->p.footprint().overlaps(aisle)));
        assertEquals(result,planner.plan(new Intent(Kind.MARKET_INTERIOR, area, 2, 5, 3),member(),Collections.nCopies(20,member())));
    }
    @Test void insufficientSpaceReportsExactRemainderWithoutResizing() {
        var result=planner.plan(new Intent(Kind.MARKET_EDGE,new BlockBounds(0,0,15,15),2,3,1),null,Collections.nCopies(4,member()));
        assertFalse(result.ok());assertEquals(3,result.unplacedCount());assertEquals(16,result.publicSpace().widthBlocks());
    }
    private static Member member() {
        var template = new CityTemplateCatalog.Template("test:stall","test","geomantia:city/stall","geomantia:city/stall","hash","v1",
                new Size(8,6,6),List.of(Rotation.values()),List.of(Mirror.NONE),
                List.of(new RoadEntrance("front",new BlockPoint(4,0),Direction.NORTH)),"structure_start_beard_thin","full_footprint_support",12);
        return new Member("test:stall",template,"front");
    }
}
