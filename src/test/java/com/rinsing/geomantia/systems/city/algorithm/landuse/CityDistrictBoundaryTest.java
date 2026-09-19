package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityDistrictBoundaryTest {
    @Test void lShapedDistrictKeepsLargeBayAndProducesClosedLongSegments() {
        var edges = new CityDistrictBoundary().outline(List.of(new BlockBounds(0,0,95,31),
                new BlockBounds(0,32,31,95)),0,32);
        assertEquals(6,edges.size());
        Set<BlockPoint> starts = new HashSet<>(), ends = new HashSet<>();
        for(var edge:edges) {
            starts.add(edge.from()); ends.add(edge.to());
            assertTrue(edge.from().x()==edge.to().x() || edge.from().z()==edge.to().z());
        }
        assertEquals(starts,ends);
        assertTrue(starts.contains(new BlockPoint(32,32)));
    }

    @Test void courtyardHasNoInnerWallAndNegativeCoordinatesAreStable() {
        var bounds=List.of(new BlockBounds(-96,-96,-1,-65),new BlockBounds(-96,-32,-1,-1),
                new BlockBounds(-96,-64,-65,-33),new BlockBounds(-32,-64,-1,-33));
        var first=new CityDistrictBoundary().outline(bounds,0,32);
        List<BlockBounds> reversed=new ArrayList<>(bounds); Collections.reverse(reversed);
        assertEquals(first,new CityDistrictBoundary().outline(reversed,0,32));
        assertEquals(4,first.size());
    }
}
