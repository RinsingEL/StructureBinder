package com.rinsing.geomantia.systems.city.application.dressing;

import java.util.Objects;

/** D3/D4/D5 integration resolves an intent reference into this internal geometry context. */
public record ResolvedDecorationProgramContext(
        CompiledDecorationProgram.TargetMask targetMask,
        CompiledDecorationProgram.CoordinateFrame coordinateFrame) {

    public ResolvedDecorationProgramContext {
        Objects.requireNonNull(targetMask, "targetMask");
        Objects.requireNonNull(coordinateFrame, "coordinateFrame");
    }

    @FunctionalInterface
    public interface Resolver {
        ResolvedDecorationProgramContext resolve(DecorationProgramIntent intent);
    }
}
