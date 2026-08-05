package com.rinsing.geomantia.systems.city.application;

import java.util.Locale;

/** City-owned fixed placement topology; unlike TerraSense terms these values have executable semantics. */
public enum CityStructureTerrainMode {
    SURFACE,
    EMBEDDED,
    FLOATING;

    static CityStructureTerrainMode parse(String value, String sourceRef) {
        if (value == null || value.isBlank()) {
            throw invalid(sourceRef + ": terrainModes entries must be non-empty strings.");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid(sourceRef + ": unknown terrainMode " + value);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("CITY_STRUCTURE_TERRAIN_MODE_INVALID: " + message);
    }
}
