package com.rinsing.geomantia.systems.city.application.dressing;

import java.util.Objects;

/** Dispatches DecorationProgram target references without mixing source-specific geometry rules. */
public final class CityDecorationProgramContextResolver implements ResolvedDecorationProgramContext.Resolver {
    private final ResolvedDecorationProgramContext.Resolver patchResolver;
    private final ResolvedDecorationProgramContext.Resolver landUseResolver;

    public CityDecorationProgramContextResolver(
            ResolvedDecorationProgramContext.Resolver patchResolver,
            ResolvedDecorationProgramContext.Resolver landUseResolver) {
        this.patchResolver = Objects.requireNonNull(patchResolver, "patchResolver");
        this.landUseResolver = landUseResolver;
    }

    @Override
    public ResolvedDecorationProgramContext resolve(DecorationProgramIntent intent) {
        return switch (intent.targetArea().sourceType()) {
            case "patch" -> patchResolver.resolve(intent);
            case "land_use_area" -> {
                if (landUseResolver == null) {
                    throw new IllegalArgumentException("CITY_DECORATION_LAND_USE_PLAN_REQUIRED");
                }
                yield landUseResolver.resolve(intent);
            }
            default -> throw new IllegalArgumentException("CITY_DECORATION_TARGET_SOURCE_UNSUPPORTED: "
                    + intent.targetArea().sourceType());
        };
    }
}
