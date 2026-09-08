package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import java.util.function.BooleanSupplier;

/** Only natural configured-feature writes are guarded; City placement and player edits remain ordinary writes. */
public final class CityFeatureWriteGuard {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private CityFeatureWriteGuard() {}
    public static boolean active() { return DEPTH.get() > 0; }
    public static boolean run(BooleanSupplier placement) {
        int previous = DEPTH.get();
        DEPTH.set(previous + 1);
        try { return placement.getAsBoolean(); }
        finally { if (previous == 0) DEPTH.remove(); else DEPTH.set(previous); }
    }
}
