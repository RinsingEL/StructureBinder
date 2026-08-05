package com.rinsing.geomantia.systems.realm_planning.application.terrain;

import java.util.Objects;

public record TerrainPreviewProviderSelection(
        TerrainPreviewProvider provider,
        String providerId,
        String sourceKind,
        boolean fastPath,
        String fallbackReason,
        String sourceFingerprint,
        String samplingSemantics
) {
    public TerrainPreviewProviderSelection(TerrainPreviewProvider provider, String providerId,
            String sourceKind, boolean fastPath, String fallbackReason, String sourceFingerprint) {
        this(provider, providerId, sourceKind, fastPath, fallbackReason, sourceFingerprint, "unspecified");
    }

    public TerrainPreviewProviderSelection {
        provider = Objects.requireNonNull(provider, "provider");
        providerId = requireText(providerId, "providerId");
        sourceKind = requireText(sourceKind, "sourceKind");
        fallbackReason = fallbackReason == null ? "" : fallbackReason.trim();
        sourceFingerprint = requireText(sourceFingerprint, "sourceFingerprint");
        samplingSemantics = requireText(samplingSemantics, "samplingSemantics");
    }

    public TerrainPreviewSample sample(int blockX, int blockZ) {
        return provider.sample(blockX, blockZ);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value.trim();
    }
}
