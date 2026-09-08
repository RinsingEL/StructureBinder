package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CityFeatureWriteGuardTest {
    @Test void nestedFeatureAndExceptionRestoreOrdinaryWrites() {
        assertFalse(CityFeatureWriteGuard.active());
        CityFeatureWriteGuard.run(() -> {
            assertTrue(CityFeatureWriteGuard.active());
            assertThrows(IllegalStateException.class, () -> CityFeatureWriteGuard.run(() -> { throw new IllegalStateException(); }));
            assertTrue(CityFeatureWriteGuard.active());
            return true;
        });
        assertFalse(CityFeatureWriteGuard.active());
    }
}
