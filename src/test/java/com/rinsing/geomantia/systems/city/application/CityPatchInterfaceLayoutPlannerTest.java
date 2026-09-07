package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.rinsing.geomantia.systems.city.application.CityPatchInterfaceLayoutPlanner.*;
import static com.rinsing.geomantia.systems.city.application.CityTemplatePlacementGeometry.*;

class CityPatchInterfaceLayoutPlannerTest {
    private final CityPatchInterfaceLayoutPlanner planner = new CityPatchInterfaceLayoutPlanner();
    @Test void usesActualSharedEdgeNotBoundingBoxesAndRejectsUnrelatedPatches() {
        var result = planner.plan(intent(Set.of(new BlockPoint(0,0)), Set.of(new BlockPoint(2,0)), Side.A, 0, 1), List.of(member()));
        assertFalse(result.ok());
        assertEquals(List.of("CITY_INTERFACE_NOT_ADJACENT"), result.failures());
    }
    @Test void keepsFullBodiesOnChosenSideOfNegativeCoordinateShore() {
        Set<BlockPoint> a = new HashSet<>(), b = new HashSet<>();
        for(int z=-3;z<=3;z++){a.add(new BlockPoint(-1,z));b.add(new BlockPoint(0,z));}
        var first = planner.plan(intent(a,b,Side.A,0,1), Collections.nCopies(8,member()));
        var second = planner.plan(intent(a,b,Side.B,0,1), Collections.nCopies(8,member()));
        assertTrue(first.ok(), first.failures().toString()); assertTrue(second.ok(),second.failures().toString());
        assertEquals(1, first.contour().size());
        assertTrue(first.placements().stream().allMatch(p->p.footprint().maxX()==-1));
        assertTrue(second.placements().stream().allMatch(p->p.footprint().minX()==0));
        assertEquals(first,planner.plan(intent(a,b,Side.A,0,1), Collections.nCopies(8,member())));
    }
    @Test void percentageClipsContourAndNeverBridgesDisconnectedIslands() {
        Set<BlockPoint> a=Set.of(new BlockPoint(0,0),new BlockPoint(0,5));
        Set<BlockPoint> b=Set.of(new BlockPoint(1,0),new BlockPoint(1,5));
        var result = planner.plan(intent(a,b,Side.A,.5,1), List.of(member(),member()));
        assertTrue(result.ok(), result.failures().toString());
        assertEquals(2,result.contour().size());
        assertNotEquals(result.contour().get(0).component(), result.contour().get(1).component());
        assertTrue(result.placements().stream().allMatch(p->p.footprint().minZ()>=80));
    }
    @Test void overlapAndOverflowAreExplicitNotSilentlyDropped() {
        var a=Set.of(new BlockPoint(0,0));
        assertThrows(IllegalArgumentException.class,()->intent(a,a,Side.A,0,1));
        var result=planner.plan(intent(a,Set.of(new BlockPoint(1,0)),Side.A,0,1),Collections.nCopies(10,member()));
        assertFalse(result.ok()); assertEquals(2,result.placements().size()); assertEquals(8,result.unplacedCount());
    }
    private static Intent intent(Set<BlockPoint>a,Set<BlockPoint>b,Side side,double start,double end){
        return new Intent(16,a,b,side,Facing.TOWARD_OTHER,0,0,start,end);
    }
    private static CityPublicSpaceLayoutPlanner.Member member(){
        var template=new CityTemplateCatalog.Template("test:geometry","test","test:block","test:block","hash","v1",
                new Size(8,6,6),List.of(Rotation.values()),List.of(Mirror.NONE),
                List.of(new RoadEntrance("front",new BlockPoint(4,0),Direction.NORTH)),"structure_start_beard_thin","full_footprint_support",0);
        return new CityPublicSpaceLayoutPlanner.Member("test:geometry",template,"front");
    }
}
