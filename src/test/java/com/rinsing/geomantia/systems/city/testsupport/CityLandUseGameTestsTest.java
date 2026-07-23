package com.rinsing.geomantia.systems.city.testsupport;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityLandUseGameTestsTest {
    @Test
    void nonceSelectsExactlyTwentyBlocksAcrossOneDynamicChunkSeam() {
        CityLandUseGameTests.WorldgenSmokeTarget target = CityLandUseGameTests.worldgenSmokeTarget(
                UUID.fromString("12345678-1234-4678-9234-567812345678"));

        assertEquals(20, target.maxX() - target.minX() + 1);
        assertEquals(0, Math.floorMod(target.seamX(), 16));
        assertEquals(Math.floorDiv(target.minX(), 16) + 1, Math.floorDiv(target.maxX(), 16));
        assertTrue(target.minX() < target.seamX());
        assertTrue(target.maxX() >= target.seamX());
        assertEquals(Math.floorDiv(target.cropZ(), 16), Math.floorDiv(target.fenceZ(), 16));
    }

    @Test
    void differentNoncesSelectDifferentTargets() {
        CityLandUseGameTests.WorldgenSmokeTarget first = CityLandUseGameTests.worldgenSmokeTarget(
                UUID.fromString("12345678-1234-4678-9234-567812345678"));
        CityLandUseGameTests.WorldgenSmokeTarget second = CityLandUseGameTests.worldgenSmokeTarget(
                UUID.fromString("87654321-4321-4765-8765-432187654321"));

        assertNotEquals(first, second);
    }
}
