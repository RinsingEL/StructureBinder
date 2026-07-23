package com.rinsing.geomantia.systems.city.application.dressing;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.util.List;

public interface DecorationPatternGenerator {
    boolean supports(CompiledDecorationProgram.PatternSpec pattern);

    List<DecorationSlot> generate(CompiledDecorationProgram program, BlockBounds queryBounds,
                                  DecorationShapeEvaluator shapeEvaluator);
}
