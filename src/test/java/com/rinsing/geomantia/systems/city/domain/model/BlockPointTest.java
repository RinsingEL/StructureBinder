package com.rinsing.geomantia.systems.city.domain.model;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockPointTest {
    @Test
    void cityScaleGridCoordinatesDoNotCollapseIntoDiagonalHashBands() {
        Set<Integer> hashes = new HashSet<>();
        int pointCount = 0;
        for (int z = 138_700; z < 138_934; z++) {
            for (int x = 138_700; x < 138_936; x++) {
                hashes.add(new BlockPoint(x, z).hashCode());
                pointCount++;
            }
        }

        assertTrue(hashes.size() >= pointCount * 0.99,
                "a regular city grid must retain at least 99% distinct hash buckets");
        assertEquals(new BlockPoint(12, -4), new BlockPoint(12, -4));
        assertEquals(new BlockPoint(12, -4).hashCode(), new BlockPoint(12, -4).hashCode());
    }
}
