package com.rinsing.geomantia.systems.city.application;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CityPerimeterSlotsTest {
    @Test void compactFillsEveryPositionInThreeLayersWithoutDuplicates() {
        Set<String> occupied = new HashSet<>();
        for (int i = 0; i < 48; i++) {
            var slot = CityPerimeterSlots.compact(i);
            assertTrue(occupied.add(slot.x() + ":" + slot.z()));
            assertTrue(slot.ring() <= 3);
        }
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            assertEquals(x != 0 || z != 0, occupied.contains(x + ":" + z));
        }
    }

    @Test void courtyardLeavesAnUninterruptedGateThroughEveryLayer() {
        Set<String> occupied = new HashSet<>();
        for (int i = 0; i < 45; i++) {
            var slot = CityPerimeterSlots.courtyard(i);
            assertFalse(slot.x() == 0 && slot.z() > 0);
            assertTrue(occupied.add(slot.x() + ":" + slot.z()));
            assertTrue(slot.ring() <= 3);
        }
        assertEquals(45, occupied.size());
    }
}
