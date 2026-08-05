package com.rinsing.geomantia.systems.realm_planning.application.terrain;

public record TerrainPreviewProviderAvailability(boolean available, String reason) {
    public TerrainPreviewProviderAvailability {
        reason = reason == null ? "" : reason.trim();
        if (available && !reason.isEmpty()) {
            throw new IllegalArgumentException("An available provider cannot declare an unavailable reason.");
        }
        if (!available && reason.isEmpty()) {
            throw new IllegalArgumentException("An unavailable provider must declare a reason.");
        }
    }

    public static TerrainPreviewProviderAvailability ready() {
        return new TerrainPreviewProviderAvailability(true, "");
    }

    public static TerrainPreviewProviderAvailability unavailable(String reason) {
        return new TerrainPreviewProviderAvailability(false, reason);
    }
}
