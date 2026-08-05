package com.rinsing.geomantia.systems.realm_planning.application.terrain;

import com.google.gson.JsonObject;

public record TerrainSamplingProvenance(
        boolean generatorNativeRequested,
        String providerId,
        String sourceKind,
        boolean fastPath,
        String fallbackReason,
        String sourceFingerprint,
        String samplingSemantics
) {
    public TerrainSamplingProvenance {
        providerId = requireText(providerId, "providerId");
        sourceKind = requireText(sourceKind, "sourceKind");
        fallbackReason = fallbackReason == null ? "" : fallbackReason.trim();
        sourceFingerprint = requireText(sourceFingerprint, "sourceFingerprint");
        samplingSemantics = requireText(samplingSemantics, "samplingSemantics");
    }

    public static TerrainSamplingProvenance fromSelection(boolean generatorNativeRequested,
            TerrainPreviewProviderSelection selection) {
        return new TerrainSamplingProvenance(
                generatorNativeRequested,
                selection.providerId(),
                selection.sourceKind(),
                selection.fastPath(),
                selection.fallbackReason(),
                selection.sourceFingerprint(),
                selection.samplingSemantics()
        );
    }

    public static TerrainSamplingProvenance currentAtlasSampler() {
        return new TerrainSamplingProvenance(false, "current_atlas_sampler", "gis_atlas_sampler", false,
                "", "unspecified", "atlas_sampler");
    }

    public static TerrainSamplingProvenance fromJson(JsonObject json) {
        if (json == null) {
            return currentAtlasSampler();
        }
        return new TerrainSamplingProvenance(
                booleanValue(json, "generatorNativeRequested", false),
                stringValue(json, "providerId", "current_atlas_sampler"),
                stringValue(json, "sourceKind", "gis_atlas_sampler"),
                booleanValue(json, "fastPath", false),
                stringValue(json, "fallbackReason", ""),
                stringValue(json, "sourceFingerprint", "unspecified"),
                stringValue(json, "samplingSemantics", "atlas_sampler")
        );
    }

    public JsonObject asJson() {
        JsonObject json = new JsonObject();
        json.addProperty("generatorNativeRequested", generatorNativeRequested);
        json.addProperty("providerId", providerId);
        json.addProperty("sourceKind", sourceKind);
        json.addProperty("fastPath", fastPath);
        json.addProperty("fallbackReason", fallbackReason);
        json.addProperty("sourceFingerprint", sourceFingerprint);
        json.addProperty("samplingSemantics", samplingSemantics);
        return json;
    }

    public String cacheIdentity() {
        return String.join("|", Boolean.toString(generatorNativeRequested), providerId, sourceKind,
                Boolean.toString(fastPath), fallbackReason, sourceFingerprint, samplingSemantics);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value.trim();
    }

    private static String stringValue(JsonObject json, String field, String fallback) {
        return json.has(field) && !json.get(field).isJsonNull() ? json.get(field).getAsString() : fallback;
    }

    private static boolean booleanValue(JsonObject json, String field, boolean fallback) {
        return json.has(field) && !json.get(field).isJsonNull() ? json.get(field).getAsBoolean() : fallback;
    }
}
