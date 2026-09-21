package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CityMeanderingClusterLayoutTest {
    @Test
    void mixedSizesKeepBodiesAndFullRoadCrossSectionsDisjointAcrossSeeds() {
        for (long seed = 0; seed < 20; seed++) {
            List<Integer> spans = new ArrayList<>();
            for (int i = 0; i < 25; i++) spans.add(i % 7 == 0 ? 36 : 9 + i % 3 * 5);
            var layout = new CityMeanderingClusterLayout(spans, 5, 3, seed);
            var center = new BlockPoint(0, 0);
            List<BlockBounds> bodies = new ArrayList<>();
            for (int i = 0; i < spans.size(); i++) {
                var point = layout.origin(center, i);
                bodies.add(new BlockBounds(point.x(), point.z(), point.x() + spans.get(i) - 1,
                        point.z() + spans.get(i) - 1));
            }
            for (int i = 0; i < bodies.size(); i++)
                for (int j = i + 1; j < bodies.size(); j++) assertFalse(bodies.get(i).overlaps(bodies.get(j)));
            var road = layout.roadJson(center);
            for (int i = 1; i < road.size(); i++) {
                var a = road.get(i - 1).getAsJsonObject(); var b = road.get(i).getAsJsonObject();
                int ax = a.get("x").getAsInt(), az = a.get("z").getAsInt();
                int bx = b.get("x").getAsInt(), bz = b.get("z").getAsInt();
                assertTrue(ax == bx || az == bz);
                var band = new BlockBounds(Math.min(ax, bx) - 2, Math.min(az, bz) - 2,
                        Math.max(ax, bx) + 2, Math.max(az, bz) + 2);
                for (var body : bodies) assertFalse(band.overlaps(body), "seed=" + seed + ", segment=" + i);
            }
            assertTrue(layout.cluster(24) >= 3);
            assertEquals(road, new CityMeanderingClusterLayout(spans, 5, 3, seed).roadJson(center));
        }
    }
}
