package com.rinsing.geomantia.systems.realm_planning.application.terrain;

import java.util.Objects;

public record TerrainPreviewProviderDescriptor(
        String providerId,
        TerrainPreviewSourceKind sourceKind,
        boolean fastPath,
        String sourceFingerprint,
        String samplingSemantics
) {
    public TerrainPreviewProviderDescriptor(String providerId, TerrainPreviewSourceKind sourceKind,
            boolean fastPath, String sourceFingerprint) {
        this(providerId, sourceKind, fastPath, sourceFingerprint, "unspecified");
    }

    public TerrainPreviewProviderDescriptor {
        providerId = requireText(providerId, "providerId");
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        sourceFingerprint = requireText(sourceFingerprint, "sourceFingerprint");
        samplingSemantics = requireText(samplingSemantics, "samplingSemantics");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value.trim();
    }
}
