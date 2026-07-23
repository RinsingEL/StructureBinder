package com.rinsing.geomantia.systems.city.domain.landuse;

import java.util.Locale;

public enum CardinalDirection {
    NORTH(0, -1),
    EAST(1, 0),
    SOUTH(0, 1),
    WEST(-1, 0);

    private final int dx;
    private final int dz;

    CardinalDirection(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    public int dx() {
        return dx;
    }

    public int dz() {
        return dz;
    }

    public static CardinalDirection from(String value, CardinalDirection fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
