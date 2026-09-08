package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import java.util.function.BooleanSupplier;

/** External feature/structure writes are guarded; explicit City scopes and ordinary player edits are allowed. */
public final class CityFeatureWriteGuard {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> CITY_DEPTH = ThreadLocal.withInitial(() -> 0);
    private CityFeatureWriteGuard() {}
    public static boolean active() { return DEPTH.get() > 0 && CITY_DEPTH.get() == 0; }
    public static boolean cityOwned() { return CITY_DEPTH.get() > 0; }
    public static <T> T city(java.util.function.Supplier<T> placement) {
        int previous = CITY_DEPTH.get(); CITY_DEPTH.set(previous + 1);
        try { return placement.get(); }
        finally { if (previous == 0) CITY_DEPTH.remove(); else CITY_DEPTH.set(previous); }
    }
    public static boolean run(BooleanSupplier placement) {
        int previous = DEPTH.get();
        DEPTH.set(previous + 1);
        try { return placement.getAsBoolean(); }
        finally { if (previous == 0) DEPTH.remove(); else DEPTH.set(previous); }
    }
}
