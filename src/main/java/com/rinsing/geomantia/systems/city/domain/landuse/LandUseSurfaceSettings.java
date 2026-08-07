package com.rinsing.geomantia.systems.city.domain.landuse;

import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.Objects;
import java.util.regex.Pattern;

public record LandUseSurfaceSettings(
        boolean surfacePrintEnabled,
        boolean autoConnect,
        String surfaceBlockId,
        String cropBlockId,
        String compatibilityCategory,
        SurfaceAlgorithm surfaceAlgorithm,
        BlockPoint algorithmAnchor,
        String channelBankBlockId,
        String channelWaterBlockId,
        String channelBankOverlayBlockId,
        String boundaryBlockId,
        int fieldBeforeBlocks,
        int channelWidthBlocks,
        int fieldAfterBlocks) {
    private static final Pattern BLOCK_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    public LandUseSurfaceSettings {
        surfaceBlockId = normalizeBlockId(surfaceBlockId, "surfaceBlockId");
        cropBlockId = normalizeBlockId(cropBlockId, "cropBlockId");
        channelBankBlockId = normalizeBlockId(channelBankBlockId, "channelBankBlockId");
        channelWaterBlockId = normalizeBlockId(channelWaterBlockId, "channelWaterBlockId");
        channelBankOverlayBlockId = normalizeBlockId(channelBankOverlayBlockId, "channelBankOverlayBlockId");
        boundaryBlockId = normalizeBlockId(boundaryBlockId, "boundaryBlockId");
        compatibilityCategory = compatibilityCategory == null ? "" : compatibilityCategory;
        surfaceAlgorithm = surfaceAlgorithm == null ? SurfaceAlgorithm.UNIFORM : surfaceAlgorithm;
        if (algorithmAnchor != null && surfaceAlgorithm != SurfaceAlgorithm.CONTOUR_BANDS) {
            throw new IllegalArgumentException("LAND_USE_SURFACE_ALGORITHM_ANCHOR_REQUIRES_CONTOUR_BANDS");
        }
        if (!compatibilityCategory.isEmpty()
                && !compatibilityCategory.equals(SurfacePolicy.PAVE.name())
                && !compatibilityCategory.equals(SurfacePolicy.CULTIVATE.name())) {
            throw new IllegalArgumentException("LAND_USE_SURFACE_COMPATIBILITY_CATEGORY_INVALID:"
                    + compatibilityCategory);
        }
        if (surfacePrintEnabled && surfaceBlockId.isBlank()) {
            throw new IllegalArgumentException("LAND_USE_SURFACE_BLOCK_ID_REQUIRED_WHEN_ENABLED");
        }
        if (surfacePrintEnabled && surfaceAlgorithm == SurfaceAlgorithm.CONTOUR_BANDS
                && (cropBlockId.isBlank() || channelBankBlockId.isBlank() || channelWaterBlockId.isBlank()
                || channelBankOverlayBlockId.isBlank())) {
            throw new IllegalArgumentException("LAND_USE_CONTOUR_BAND_MATERIALS_REQUIRED_WHEN_ENABLED");
        }
        if (surfacePrintEnabled && surfaceAlgorithm == SurfaceAlgorithm.CONTOUR_BANDS
                && (fieldBeforeBlocks <= 0 || channelWidthBlocks <= 0 || fieldAfterBlocks <= 0)) {
            throw new IllegalArgumentException("LAND_USE_CONTOUR_BAND_WIDTHS_REQUIRED");
        }
        if (surfaceAlgorithm != SurfaceAlgorithm.CONTOUR_BANDS
                && (fieldBeforeBlocks != 0 || channelWidthBlocks != 0 || fieldAfterBlocks != 0)) {
            throw new IllegalArgumentException("LAND_USE_CONTOUR_BAND_WIDTHS_REQUIRE_CONTOUR_BANDS");
        }
    }

    public LandUseSurfaceSettings(boolean surfacePrintEnabled,
                                  boolean autoConnect,
                                  String surfaceBlockId,
                                  String cropBlockId,
                                  String compatibilityCategory,
                                  SurfaceAlgorithm surfaceAlgorithm,
                                  BlockPoint algorithmAnchor,
                                  String channelBankBlockId,
                                  String channelWaterBlockId,
                                  String channelBankOverlayBlockId) {
        this(surfacePrintEnabled, autoConnect, surfaceBlockId, cropBlockId, compatibilityCategory,
                surfaceAlgorithm, algorithmAnchor, channelBankBlockId, channelWaterBlockId,
                channelBankOverlayBlockId, "",
                surfaceAlgorithm == SurfaceAlgorithm.CONTOUR_BANDS ? 5 : 0,
                surfaceAlgorithm == SurfaceAlgorithm.CONTOUR_BANDS ? 3 : 0,
                surfaceAlgorithm == SurfaceAlgorithm.CONTOUR_BANDS ? 5 : 0);
    }

    public LandUseSurfaceSettings(boolean surfacePrintEnabled,
                                  boolean autoConnect,
                                  String surfaceBlockId,
                                  String cropBlockId,
                                  String compatibilityCategory) {
        this(surfacePrintEnabled, autoConnect, surfaceBlockId, cropBlockId, compatibilityCategory,
                SurfacePolicy.CULTIVATE.name().equals(compatibilityCategory)
                        ? SurfaceAlgorithm.CONTOUR_BANDS : SurfaceAlgorithm.UNIFORM,
                null,
                SurfacePolicy.CULTIVATE.name().equals(compatibilityCategory) ? "minecraft:dirt" : "",
                SurfacePolicy.CULTIVATE.name().equals(compatibilityCategory) ? "minecraft:water" : "",
                SurfacePolicy.CULTIVATE.name().equals(compatibilityCategory) ? "minecraft:oak_slab" : "");
    }

    public static LandUseSurfaceSettings defaults(SurfacePolicy policy) {
        Objects.requireNonNull(policy, "policy");
        return switch (policy) {
            case PAVE -> new LandUseSurfaceSettings(true, true, "minecraft:stone_bricks", "", "PAVE",
                    SurfaceAlgorithm.UNIFORM, null, "", "", "", "", 0, 0, 0);
            case CULTIVATE -> new LandUseSurfaceSettings(
                    true, true, "minecraft:farmland", "minecraft:wheat", "CULTIVATE",
                    SurfaceAlgorithm.CONTOUR_BANDS, null, "minecraft:dirt", "minecraft:water",
                    "minecraft:oak_slab", "", 5, 3, 5);
            case PRESERVE, WATER_ADAPTIVE -> new LandUseSurfaceSettings(false, false, "", "", "",
                    SurfaceAlgorithm.UNIFORM, null, "", "", "", "", 0, 0, 0);
        };
    }

    public LandUseSurfaceSettings withOverrides(Boolean enabled,
                                                Boolean connect,
                                                String surfaceBlock,
                                                String cropBlock) {
        return withOverrides(enabled, connect, null, surfaceBlock, cropBlock,
                null, null, null, null, null);
    }

    public LandUseSurfaceSettings withOverrides(
            Boolean enabled,
            Boolean connect,
            SurfaceAlgorithm requestedAlgorithm,
            String surfaceBlock,
            String cropBlock,
            String channelBankBlock,
            String channelWaterBlock,
            String channelBankOverlayBlock,
            BlockPoint requestedAlgorithmAnchor,
            SurfaceMaterials algorithmDefault) {
        return withOverrides(enabled, connect, requestedAlgorithm, surfaceBlock, cropBlock,
                channelBankBlock, channelWaterBlock, channelBankOverlayBlock, null,
                null, null, null, requestedAlgorithmAnchor, algorithmDefault);
    }

    public LandUseSurfaceSettings withOverrides(
            Boolean enabled,
            Boolean connect,
            SurfaceAlgorithm requestedAlgorithm,
            String surfaceBlock,
            String cropBlock,
            String channelBankBlock,
            String channelWaterBlock,
            String channelBankOverlayBlock,
            String boundaryBlock,
            Integer requestedFieldBeforeBlocks,
            Integer requestedChannelWidthBlocks,
            Integer requestedFieldAfterBlocks,
            BlockPoint requestedAlgorithmAnchor,
            SurfaceMaterials algorithmDefault) {
        SurfaceAlgorithm resolvedAlgorithm = requestedAlgorithm == null ? surfaceAlgorithm : requestedAlgorithm;
        LandUseSurfaceSettings algorithmFallback = resolvedAlgorithm == SurfaceAlgorithm.CONTOUR_BANDS
                ? defaults(SurfacePolicy.CULTIVATE) : defaults(SurfacePolicy.PAVE);
        boolean algorithmChanged = resolvedAlgorithm != surfaceAlgorithm;
        String fallbackSurface = algorithmChanged ? algorithmFallback.surfaceBlockId : surfaceBlockId;
        String fallbackCrop = algorithmChanged ? algorithmFallback.cropBlockId : cropBlockId;
        String fallbackBank = algorithmChanged ? algorithmFallback.channelBankBlockId : channelBankBlockId;
        String fallbackWater = algorithmChanged ? algorithmFallback.channelWaterBlockId : channelWaterBlockId;
        String fallbackOverlay = algorithmChanged
                ? algorithmFallback.channelBankOverlayBlockId : channelBankOverlayBlockId;
        String fallbackBoundary = algorithmChanged ? algorithmFallback.boundaryBlockId : boundaryBlockId;
        int fallbackFieldBefore = algorithmChanged
                ? algorithmFallback.fieldBeforeBlocks : fieldBeforeBlocks;
        int fallbackChannelWidth = algorithmChanged
                ? algorithmFallback.channelWidthBlocks : channelWidthBlocks;
        int fallbackFieldAfter = algorithmChanged
                ? algorithmFallback.fieldAfterBlocks : fieldAfterBlocks;
        if (algorithmDefault != null) {
            fallbackSurface = algorithmDefault.surfaceBlockId();
            if (!algorithmDefault.cropBlockId().isBlank()) fallbackCrop = algorithmDefault.cropBlockId();
            if (!algorithmDefault.channelBankBlockId().isBlank()) {
                fallbackBank = algorithmDefault.channelBankBlockId();
            }
            if (!algorithmDefault.channelWaterBlockId().isBlank()) {
                fallbackWater = algorithmDefault.channelWaterBlockId();
            }
            if (!algorithmDefault.channelBankOverlayBlockId().isBlank()) {
                fallbackOverlay = algorithmDefault.channelBankOverlayBlockId();
            }
        }
        boolean resolvedEnabled = enabled == null ? surfacePrintEnabled : enabled;
        String resolvedSurfaceBlock = surfaceBlock == null ? fallbackSurface : surfaceBlock;
        String resolvedCategory = compatibilityCategory;
        boolean promotedToPave = resolvedCategory.isBlank() && resolvedEnabled && !resolvedSurfaceBlock.isBlank();
        if (promotedToPave) resolvedCategory = SurfacePolicy.PAVE.name();
        BlockPoint resolvedAnchor = resolvedAlgorithm == SurfaceAlgorithm.CONTOUR_BANDS
                ? requestedAlgorithmAnchor == null ? algorithmAnchor : requestedAlgorithmAnchor : null;
        return new LandUseSurfaceSettings(
                resolvedEnabled,
                connect == null ? (promotedToPave || autoConnect) : connect,
                resolvedSurfaceBlock,
                cropBlock == null ? fallbackCrop : cropBlock,
                resolvedCategory,
                resolvedAlgorithm,
                resolvedAnchor,
                channelBankBlock == null ? fallbackBank : channelBankBlock,
                channelWaterBlock == null ? fallbackWater : channelWaterBlock,
                channelBankOverlayBlock == null ? fallbackOverlay : channelBankOverlayBlock,
                boundaryBlock == null ? fallbackBoundary : boundaryBlock,
                resolvedAlgorithm == SurfaceAlgorithm.CONTOUR_BANDS
                        ? requestedFieldBeforeBlocks == null ? fallbackFieldBefore : requestedFieldBeforeBlocks : 0,
                resolvedAlgorithm == SurfaceAlgorithm.CONTOUR_BANDS
                        ? requestedChannelWidthBlocks == null ? fallbackChannelWidth : requestedChannelWidthBlocks : 0,
                resolvedAlgorithm == SurfaceAlgorithm.CONTOUR_BANDS
                        ? requestedFieldAfterBlocks == null ? fallbackFieldAfter : requestedFieldAfterBlocks : 0);
    }

    public String compatibilityKey() {
        if (!surfacePrintEnabled) return "";
        return compatibilityCategory;
    }

    /** Stable exact-match key used when deciding whether claimed geometry may be fused. */
    public String exactSignature() {
        return Boolean.toString(surfacePrintEnabled) + '|'
                + autoConnect + '|'
                + surfaceBlockId + '|'
                + cropBlockId + '|'
                + compatibilityCategory + '|'
                + surfaceAlgorithm + '|'
                + (algorithmAnchor == null ? "" : algorithmAnchor.x() + "," + algorithmAnchor.z()) + '|'
                + channelBankBlockId + '|'
                + channelWaterBlockId + '|'
                + channelBankOverlayBlockId + '|'
                + boundaryBlockId + '|'
                + fieldBeforeBlocks + '|'
                + channelWidthBlocks + '|'
                + fieldAfterBlocks;
    }

    public static boolean isValidBlockId(String value) {
        return value != null && BLOCK_ID.matcher(value).matches();
    }

    private static String normalizeBlockId(String value, String field) {
        String normalized = value == null ? "" : value;
        if (!normalized.isEmpty() && !isValidBlockId(normalized)) {
            throw new IllegalArgumentException("LAND_USE_SURFACE_BLOCK_ID_INVALID:" + field + ':' + normalized);
        }
        return normalized;
    }

    public enum SurfaceAlgorithm {
        UNIFORM,
        CONTOUR_BANDS
    }

    public record SurfaceMaterials(String surfaceBlockId,
                                   String cropBlockId,
                                   String channelBankBlockId,
                                   String channelWaterBlockId,
                                   String channelBankOverlayBlockId) {
        public SurfaceMaterials {
            surfaceBlockId = normalizeBlockId(surfaceBlockId, "surfaceBlockId");
            cropBlockId = normalizeBlockId(cropBlockId, "cropBlockId");
            channelBankBlockId = normalizeBlockId(channelBankBlockId, "channelBankBlockId");
            channelWaterBlockId = normalizeBlockId(channelWaterBlockId, "channelWaterBlockId");
            channelBankOverlayBlockId = normalizeBlockId(channelBankOverlayBlockId,
                    "channelBankOverlayBlockId");
            if (surfaceBlockId.isBlank()) {
                throw new IllegalArgumentException("LAND_USE_SURFACE_DEFAULT_BLOCK_REQUIRED");
            }
        }
    }
}
