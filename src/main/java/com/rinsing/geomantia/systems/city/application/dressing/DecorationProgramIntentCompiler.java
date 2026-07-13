package com.rinsing.geomantia.systems.city.application.dressing;

import java.util.ArrayList;
import java.util.List;

public final class DecorationProgramIntentCompiler {
    public CompiledDecorationProgramPlan compile(DecorationProgramIntentPlan intentPlan,
                                                 ResolvedDecorationProgramContext.Resolver resolver) {
        return compile(intentPlan, resolver, List.of());
    }

    public CompiledDecorationProgramPlan compile(DecorationProgramIntentPlan intentPlan,
                                                 ResolvedDecorationProgramContext.Resolver resolver,
                                                 List<CompiledDecorationProgramPlan.HardObstacle> hardObstacles) {
        List<CompiledDecorationProgram> compiled = new ArrayList<>();
        for (DecorationProgramIntent intent : intentPlan.programs()) {
            ResolvedDecorationProgramContext context = resolver.resolve(intent);
            if (context == null) {
                throw new IllegalArgumentException("CITY_DECORATION_TARGET_AREA_UNRESOLVED: " + intent.programId());
            }
            compiled.add(new CompiledDecorationProgram(CompiledDecorationProgram.SCHEMA, intent.programId(),
                    intent.priority(), intent.seed(), context.targetMask(), context.coordinateFrame(), intent.shape(),
                    intent.pattern(), intent.contentPalette(), intent.terrainPolicy(), intent.conflictPolicy()));
        }
        return new CompiledDecorationProgramPlan(CompiledDecorationProgramPlan.SCHEMA, intentPlan.cityId(),
                intentPlan.catalogHash(), intentPlan.styleProfileId(), intentPlan.styleProfileHash(), hardObstacles,
                compiled);
    }
}
