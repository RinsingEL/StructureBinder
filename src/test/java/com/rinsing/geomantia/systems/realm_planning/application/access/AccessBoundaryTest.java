package com.rinsing.geomantia.systems.realm_planning.application.access;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccessBoundaryTest {
    @Test void followsActualOffsetMovementBoundaryInsteadOfGeographicEdge() {
        var lines=AccessBoundary.sample(-480,0,(x,z)->x<-512);
        assertFalse(lines.isEmpty());
        for(var line:lines) {
            assertEquals(-512,line.x1(),.04);
            assertEquals(-512,line.x2(),.04);
        }
        assertEquals(32,lines.stream().mapToDouble(s->AccessBoundary.distance(s,-480,0)).min().orElseThrow(),.04);
    }
    @Test void includesClosedCityHoleInsideOpenArea() {
        var lines=AccessBoundary.sample(0,0,(x,z)->!(x>=16 && x<=48 && z>=-32 && z<=32));
        assertFalse(lines.isEmpty());
        assertEquals(16,lines.stream().mapToDouble(s->AccessBoundary.distance(s,0,0)).min().orElseThrow(),.04);
        assertTrue(lines.stream().anyMatch(s->Math.abs(s.x1()-48)<.04));
    }
    @Test void noArtificialViewportEdgeInUniformArea() {
        assertTrue(AccessBoundary.sample(0,0,(x,z)->true).isEmpty());
        assertTrue(AccessBoundary.sample(0,0,(x,z)->false).isEmpty());
    }
    @Test void boundedWorkAndCurvedBoundary() {
        int[] calls={0};
        var lines=AccessBoundary.sample(0,0,(x,z)->{calls[0]++;return Math.hypot(x,z)<50;});
        assertTrue(calls[0]<3000);
        for(var line:lines) assertEquals(50,Math.hypot(line.x1(),line.z1()),.05);
        assertTrue(lines.size()<100);
    }
}
