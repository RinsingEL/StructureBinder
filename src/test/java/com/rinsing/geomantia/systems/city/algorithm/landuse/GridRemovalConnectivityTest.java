package com.rinsing.geomantia.systems.city.algorithm.landuse;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GridRemovalConnectivityTest {
    @Test void nestedRemovalsAndBoxedSetRestoresMatchFreshConnectivityAtIntegerEdges() {
        for (int offset : new int[]{Integer.MIN_VALUE, -3472, Integer.MAX_VALUE - 9}) {
            Set<Long> original = new HashSet<>();
            for (int z = 0; z < 10; z++) for (int x = 0; x < 10; x++) {
                if (x != 4 || z == 5) original.add(key(offset + x, offset + z));
            }
            Set<Long> indexed = new RelayRegionGrowthClassifier.RemainingCells(original);
            var removed = new ArrayList<>(original);
            Collections.shuffle(removed, new Random(23));
            for (Long point : removed) {
                compareAll(indexed);
                assertTrue(indexed.remove(point));
            }
            Collections.reverse(removed);
            for (Long point : removed) {
                assertTrue(indexed.add(point));
                compareAll(indexed);
            }
            assertEquals(original, indexed);
        }
    }

    @Test void hugeSparseBoundsUseExactSearchWithoutAllocatingDenseGrid() {
        Set<Long> mask = Set.of(key(Integer.MIN_VALUE, 0), key(Integer.MIN_VALUE + 1, 0),
                key(Integer.MAX_VALUE, 0));
        assertNull(GridRemovalConnectivity.create(mask));
        compareAll(new RelayRegionGrowthClassifier.RemainingCells(mask));
    }

    private static void compareAll(Set<Long> indexed) {
        Set<Long> plain = new HashSet<>(indexed);
        for (long point : indexed) assertEquals(
                RelayRegionGrowthClassifier.removalKeepsComponents(plain, point),
                RelayRegionGrowthClassifier.removalKeepsComponents(indexed, point));
    }
    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffff_ffffL); }
}
