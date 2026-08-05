package com.rinsing.geomantia.systems.realm_planning.application.terrain;

public record TerrainPreviewSample(
        int blockX,
        int blockZ,
        double elevation,
        boolean water,
        String biomeId,
        String terrainId,
        String sourceBiomeId
) {
    public TerrainPreviewSample(int blockX, int blockZ, double elevation, boolean water, String biomeId) {
        this(blockX, blockZ, elevation, water, biomeId, "unknown", "unknown");
    }

    public TerrainPreviewSample {
        if (!Double.isFinite(elevation)) {
            throw new IllegalArgumentException("elevation must be finite.");
        }
        biomeId = biomeId == null || biomeId.isBlank() ? "unknown" : biomeId.trim();
        terrainId = terrainId == null || terrainId.isBlank() ? "unknown" : terrainId.trim();
        sourceBiomeId = sourceBiomeId == null || sourceBiomeId.isBlank() ? "unknown" : sourceBiomeId.trim();
    }
}
