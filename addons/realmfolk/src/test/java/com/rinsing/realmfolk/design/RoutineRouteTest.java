package com.rinsing.realmfolk.design;

import com.rinsing.realmfolk.life.RoutineRoute;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.rinsing.realmfolk.model.Society.*;
import static org.junit.jupiter.api.Assertions.*;

class RoutineRouteTest {
    private final Property dorm = building("dorm", new Bounds(-2752,63,5116,-2708,105,5154), new Point(-2730,65,5152));
    private final Property lab = building("lab", new Bounds(-2865,67,5408,-2843,90,5432), new Point(-2854,69,5410));
    private final Point upstairs = new Point(-2746,75,5146);
    private final Point work = new Point(-2848,69,5414);

    @Test void upperDormResidentLeavesByGroundEntranceBeforeDistantLaboratory() {
        assertEquals(List.of(dorm.home,lab.home,work),
                RoutineRoute.waypoints(List.of(dorm,lab),"city",upstairs,work,p->true));
    }
    @Test void returnTripEntersTheDormBeforeClimbingToTheBed() {
        assertEquals(List.of(lab.home,dorm.home,upstairs),
                RoutineRoute.waypoints(List.of(dorm,lab),"city",work,upstairs,p->true));
    }
    @Test void sameBuildingTripDoesNotSendResidentsOutside() {
        var downstairs=new Point(-2715,65,5137);
        assertEquals(List.of(downstairs),RoutineRoute.waypoints(List.of(dorm,lab),"city",upstairs,downstairs,p->true));
    }
    @Test void blockedEntranceIsNotInventedAndOtherTownsCannotSupplyWaypoints() {
        assertEquals(List.of(lab.home,work),RoutineRoute.waypoints(List.of(dorm,lab),"city",upstairs,work,p->!p.equals(dorm.home)));
        assertEquals(List.of(work),RoutineRoute.waypoints(List.of(dorm,lab),"another_city",upstairs,work,p->true));
    }
    private static Property building(String id,Bounds bounds,Point entrance) {
        var p=new Property(id,"city",id,bounds,entrance);p.entrances=List.of(entrance);return p;
    }
}
