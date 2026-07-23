package com.rinsing.geomantia.systems.city.application.dressing;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.util.List;

/** AI intent -> resolved compiled plan -> deterministic geometric slots. */
public final class CityDecorationProgramPlanner {
    private final DecorationProgramIntentCodec intentCodec;
    private final DecorationProgramIntentCompiler intentCompiler;
    private final DecorationPatternEngine patternEngine;

    public CityDecorationProgramPlanner() {
        this(new DecorationProgramIntentCodec(), new DecorationProgramIntentCompiler(), new DecorationPatternEngine());
    }

    CityDecorationProgramPlanner(DecorationProgramIntentCodec intentCodec,
                                 DecorationProgramIntentCompiler intentCompiler,
                                 DecorationPatternEngine patternEngine) {
        this.intentCodec = intentCodec;
        this.intentCompiler = intentCompiler;
        this.patternEngine = patternEngine;
    }

    public DecorationProgramIntentPlan parse(JsonObject aiRequest) {
        return intentCodec.parsePlan(aiRequest);
    }

    public CompiledDecorationProgramPlan compile(DecorationProgramIntentPlan intentPlan,
                                                 ResolvedDecorationProgramContext.Resolver resolver) {
        return intentCompiler.compile(intentPlan, resolver);
    }

    public CompiledDecorationProgramPlan compile(DecorationProgramIntentPlan intentPlan,
                                                 ResolvedDecorationProgramContext.Resolver resolver,
                                                 List<CompiledDecorationProgramPlan.HardObstacle> hardObstacles) {
        return intentCompiler.compile(intentPlan, resolver, hardObstacles);
    }

    public List<DecorationSlot> project(CompiledDecorationProgramPlan plan, BlockBounds queryBounds) {
        return patternEngine.generate(plan.programsInExecutionOrder(), queryBounds);
    }

    public List<DecorationSlot> project(CompiledDecorationProgram program, BlockBounds queryBounds) {
        return patternEngine.generate(program, queryBounds);
    }
}
