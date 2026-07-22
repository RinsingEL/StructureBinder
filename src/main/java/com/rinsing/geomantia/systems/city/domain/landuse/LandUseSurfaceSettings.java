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
        DirectionMode directionMode,
        BlockPoint directionCenter) {
    private static final Pattern BLOCK_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    public LandUseSurfaceSettings {
        surfaceBlockId = normalizeBlockId(surfaceBlockId, "surfaceBlockId");
        cropBlockId = normalizeBlockId(cropBlockId, "cropBlockId");
        compatibilityCategory = compatibilityCategory == null ? "" : compatibilityCategory;
        directionMode = directionMode == null ? DirectionMode.GLOBAL_AXIS : directionMode;
        if (directionMode == DirectionMode.GLOBAL_AXIS && directionCenter != null) {
            throw new IllegalArgumentException("LAND_USE_SURFACE_DIRECTION_CENTER_REQUIRES_RADIAL");
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
    }

    public LandUseSurfaceSettings(boolean surfacePrintEnabled,
                                  boolean autoConnect,
                                  String surfaceBlockId,
                                  String cropBlockId,
                                  String compatibilityCategory) {
        this(surfacePrintEnabled, autoConnect, surfaceBlockId, cropBlockId, compatibilityCategory,
                DirectionMode.GLOBAL_AXIS, null);
    }

    public static LandUseSurfaceSettings defaults(SurfacePolicy policy) {
        Objects.requireNonNull(policy, "policy");
        return switch (policy) {
            case PAVE -> new LandUseSurfaceSettings(true, true, "minecraft:stone_bricks", "", "PAVE");
            case CULTIVATE -> new LandUseSurfaceSettings(
                    true, true, "minecraft:farmland", "minecraft:wheat", "CULTIVATE");
            case PRESERVE, WATER_ADAPTIVE -> new LandUseSurfaceSettings(false, false, "", "", "");
        };
    }

    public LandUseSurfaceSettings withOverrides(Boolean enabled,
                                                Boolean connect,
                                                String surfaceBlock,
                                                String cropBlock) {
        return withOverrides(enabled, connect, surfaceBlock, cropBlock, null, null);
    }

    public LandUseSurfaceSettings withOverrides(Boolean enabled,
                                                 Boolean connect,
                                                 String surfaceBlock,
                                                 String cropBlock,
                                                 DirectionMode requestedDirectionMode,
                                                 BlockPoint requestedDirectionCenter) {
        boolean resolvedEnabled = enabled == null ? surfacePrintEnabled : enabled;
        String resolvedSurfaceBlock = surfaceBlock == null ? surfaceBlockId : surfaceBlock;
        String resolvedCategory = compatibilityCategory;
        boolean promotedToPave = resolvedCategory.isBlank() && resolvedEnabled && !resolvedSurfaceBlock.isBlank();
        if (promotedToPave) {
            resolvedCategory = SurfacePolicy.PAVE.name();
        }
        DirectionMode resolvedDirectionMode = requestedDirectionMode == null
                ? directionMode : requestedDirectionMode;
        BlockPoint resolvedDirectionCenter = resolvedDirectionMode == DirectionMode.RADIAL
                ? requestedDirectionCenter == null ? directionCenter : requestedDirectionCenter
                : null;
        return new LandUseSurfaceSettings(
                resolvedEnabled,
                connect == null ? (promotedToPave || autoConnect) : connect,
                resolvedSurfaceBlock,
                cropBlock == null ? cropBlockId : cropBlock,
                resolvedCategory,
                resolvedDirectionMode,
                resolvedDirectionCenter);
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
                + directionMode + '|'
                + (directionCenter == null ? "" : directionCenter.x() + "," + directionCenter.z());
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

    public enum DirectionMode {
        GLOBAL_AXIS,
        RADIAL
    }
}
