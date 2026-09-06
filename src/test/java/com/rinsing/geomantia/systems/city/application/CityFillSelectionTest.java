package com.rinsing.geomantia.systems.city.application;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityFillSelectionTest {
    @Test void requiredAndConnectionUsageCountTowardFillLimit() {
        assertEquals("house", CityFillSelection.choose(List.of("armorer", "house"),
                Map.of("armorer", 3, "house", 1), Set.of(), 3, 1, 0));
        assertNull(CityFillSelection.choose(List.of("armorer"), Map.of("armorer", 3), Set.of(), 3, 1, 0));
    }
    @Test void pairsRespectRemainingQuotaAndNoImplicitLimitIsInvented() {
        assertNull(CityFillSelection.choose(List.of("house"), Map.of("house", 2), Set.of(), 3, 2, 0));
        assertEquals("house", CityFillSelection.choose(List.of("house"), Map.of("house", 100), Set.of(), 0, 2, 0));
    }
    @Test void phaseCursorResetDoesNotPreferAlreadyRepeatedTemplate() {
        assertEquals("b", CityFillSelection.choose(List.of("a", "b"), Map.of("a", 5), Set.of(), 0, 1, 0));
        assertEquals("a", CityFillSelection.choose(List.of("a", "b"), Map.of("a", 5), Set.of("b"), 0, 1, 0));
    }
}
