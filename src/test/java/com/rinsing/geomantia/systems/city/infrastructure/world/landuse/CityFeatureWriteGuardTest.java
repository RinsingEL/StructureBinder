package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CityFeatureWriteGuardTest {
    @Test void rejectedCanopyRestoresEveryEarlierTrunkWriteIncludingNestedFeatures() {
        var world=new java.util.HashMap<String,Integer>();world.put("trunk",0);world.put("leaf",0);
        assertFalse(CityFeatureWriteGuard.atomicFeature(()->{
            int before=world.get("trunk");CityFeatureWriteGuard.record("trunk",()->()->world.put("trunk",before));world.put("trunk",1);
            CityFeatureWriteGuard.atomicFeature(()->{
                int leaf=world.get("leaf");CityFeatureWriteGuard.record("leaf",()->()->world.put("leaf",leaf));world.put("leaf",1);
                CityFeatureWriteGuard.rejectFeature();return true;
            });return true;
        }));
        assertEquals(java.util.Map.of("trunk",0,"leaf",0),world);
        assertTrue(CityFeatureWriteGuard.atomicFeature(()->true));assertFalse(CityFeatureWriteGuard.active());
    }
    @Test void cityOwnershipRestoresExternalGuardEvenAfterNestedFeatureThrows() {
        CityFeatureWriteGuard.run(() -> {
            assertTrue(CityFeatureWriteGuard.active());
            assertThrows(IllegalStateException.class, () -> CityFeatureWriteGuard.city(() -> {
                assertTrue(CityFeatureWriteGuard.cityOwned());
                assertFalse(CityFeatureWriteGuard.active());
                CityFeatureWriteGuard.run(() -> { assertFalse(CityFeatureWriteGuard.active()); return true; });
                throw new IllegalStateException();
            }));
            assertTrue(CityFeatureWriteGuard.active()); return true;
        });
        assertFalse(CityFeatureWriteGuard.cityOwned()); assertFalse(CityFeatureWriteGuard.active());
    }
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
