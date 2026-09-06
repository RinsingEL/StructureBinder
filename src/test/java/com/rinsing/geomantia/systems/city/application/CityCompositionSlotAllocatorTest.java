package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CityCompositionSlotAllocatorTest {
    @Test
    void earlierFlexibleMemberDoesNotStrandLaterMember() {
        BlockBounds a = new BlockBounds(0, 0, 9, 9);
        BlockBounds b = new BlockBounds(20, 0, 29, 9);
        var result = CityCompositionSlotAllocator.allocate(List.of(List.of(a, b), List.of(a)), List.of(), 100);
        assertTrue(result.allocated());
        assertEquals(List.of(1, 0), result.choices());
        assertFalse(result.searchLimitReached());
    }

    @Test
    void backtracksWhenFirstChoiceBlocksAllRemainingOptions() {
        BlockBounds a = new BlockBounds(0, 0, 9, 9);
        BlockBounds b = new BlockBounds(20, 0, 29, 9);
        BlockBounds c = new BlockBounds(1, 0, 8, 9);
        var result = CityCompositionSlotAllocator.allocate(List.of(List.of(a, b), List.of(a, c)), List.of(), 100);
        assertTrue(result.allocated());
        assertEquals(List.of(1, 0), result.choices());
    }

    @Test
    void reportsInfeasibleSeparatelyFromExhaustedSearchAndKeepsReservations() {
        BlockBounds a = new BlockBounds(0, 0, 9, 9);
        assertFalse(CityCompositionSlotAllocator.allocate(List.of(List.of(a)), List.of(a), 100).allocated());
        var impossible = CityCompositionSlotAllocator.allocate(List.of(List.of(a), List.of(a)), List.of(), 100);
        assertFalse(impossible.allocated());
        assertFalse(impossible.searchLimitReached());
        var limited = CityCompositionSlotAllocator.allocate(List.of(List.of(a)), List.of(), 0);
        assertFalse(limited.allocated());
        assertTrue(limited.searchLimitReached());
    }
}
