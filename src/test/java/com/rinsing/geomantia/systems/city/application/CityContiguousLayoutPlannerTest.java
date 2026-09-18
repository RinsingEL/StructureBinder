package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityContiguousLayoutPlannerTest {
    @Test void mixedTerraceSizesStayConnectedWithoutOverlapAtLargeScale() {
        var variants = List.of(new CityContiguousLayoutPlanner.Size(12, 13),
                new CityContiguousLayoutPlanner.Size(11, 11), new CityContiguousLayoutPlanner.Size(15, 15),
                new CityContiguousLayoutPlanner.Size(18, 17), new CityContiguousLayoutPlanner.Size(17, 17));
        var sizes = java.util.stream.IntStream.range(0, 1024).mapToObj(i -> variants.get(i % 5)).toList();
        var points = CityContiguousLayoutPlanner.plan(sizes, 42);
        List<BlockBounds> bodies = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            var p = points.get(i); var size = sizes.get(i);
            var b = new BlockBounds(p.x(), p.z(), p.x() + size.width() - 1, p.z() + size.depth() - 1);
            assertTrue(bodies.stream().noneMatch(b::overlaps));
            if (i > 0) assertTrue(bodies.stream().anyMatch(a -> CityContiguousLayoutPlanner.contact(a, b) > 0));
            bodies.add(b);
        }
        assertEquals(points.subList(0, 80), CityContiguousLayoutPlanner.plan(sizes.subList(0, 80), 42));
        assertNotEquals(points.subList(0, 80), CityContiguousLayoutPlanner.plan(sizes.subList(0, 80), 43));
    }

    @Test void cornerOnlyContactDoesNotConnectFields() {
        var a = new BlockBounds(0, 0, 3, 3);
        assertEquals(0, CityContiguousLayoutPlanner.contact(a, new BlockBounds(4, 4, 7, 7)));
        assertEquals(4, CityContiguousLayoutPlanner.contact(a, new BlockBounds(4, 0, 7, 3)));
    }
}
