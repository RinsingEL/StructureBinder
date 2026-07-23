package com.rinsing.geomantia.systems.city.application.dressing;

import java.util.Objects;

/** AI-facing intent. It contains references and relative policies, never resolved world coordinates. */
public record DecorationProgramIntent(
        String programId,
        TargetArea targetArea,
        CoordinateFrameIntent coordinateFrame,
        CompiledDecorationProgram.ShapeSpec shape,
        CompiledDecorationProgram.PatternSpec pattern,
        CompiledDecorationProgram.ContentPalette contentPalette,
        CompiledDecorationProgram.TerrainPolicy terrainPolicy,
        CompiledDecorationProgram.ConflictPolicy conflictPolicy,
        int priority,
        long seed) {

    public DecorationProgramIntent {
        if (programId == null || programId.isBlank()) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_ID_REQUIRED");
        }
        Objects.requireNonNull(targetArea, "targetArea");
        Objects.requireNonNull(coordinateFrame, "coordinateFrame");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(contentPalette, "contentPalette");
        Objects.requireNonNull(terrainPolicy, "terrainPolicy");
        Objects.requireNonNull(conflictPolicy, "conflictPolicy");
    }

    public record TargetArea(String sourceType, String ref, int insetBlocks) {
        public TargetArea {
            requirePolicyName(sourceType, "CITY_DECORATION_TARGET_SOURCE_TYPE_REQUIRED");
            if (ref == null || ref.isBlank()) {
                throw new IllegalArgumentException("CITY_DECORATION_TARGET_REF_REQUIRED");
            }
            if (insetBlocks < 0) {
                throw new IllegalArgumentException("CITY_DECORATION_TARGET_INSET_INVALID");
            }
        }
    }

    public record CoordinateFrameIntent(String originMode, String orientationMode, int quarterTurns,
                                        int offsetUBlocks, int offsetVBlocks) {
        public CoordinateFrameIntent {
            requirePolicyName(originMode, "CITY_DECORATION_ORIGIN_MODE_REQUIRED");
            requirePolicyName(orientationMode, "CITY_DECORATION_ORIENTATION_MODE_REQUIRED");
            if (quarterTurns < 0 || quarterTurns > 3) {
                throw new IllegalArgumentException("CITY_DECORATION_QUARTER_TURNS_INVALID");
            }
        }
    }

    private static void requirePolicyName(String value, String reasonCode) {
        if (value == null || !value.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException(reasonCode);
        }
    }
}
