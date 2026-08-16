package com.rinsing.geomantia.systems.city.domain.landuse;

public final class LandscapeTerrainContinuity {
    private LandscapeTerrainContinuity() {
    }

    public static boolean allows(String terrainPolicy, LandUseTerrainField.Cell from, LandUseTerrainField.Cell to) {
        if (from == null || to == null) {
            return false;
        }
        return Math.abs(from.elevation() - to.elevation()) <= maximumAdjacentElevationDelta(terrainPolicy);
    }

    public static int maximumAdjacentElevationDelta(String terrainPolicy) {
        return switch (terrainPolicy == null ? "BALANCED" : terrainPolicy) {
            case "CONFORM" -> 4;
            case "ASSERTIVE" -> 10;
            default -> 6;
        };
    }
}
