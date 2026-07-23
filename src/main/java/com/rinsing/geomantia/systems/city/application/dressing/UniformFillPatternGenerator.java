package com.rinsing.geomantia.systems.city.application.dressing;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.util.ArrayList;
import java.util.List;

final class UniformFillPatternGenerator implements DecorationPatternGenerator {
    @Override
    public boolean supports(CompiledDecorationProgram.PatternSpec pattern) {
        return pattern instanceof CompiledDecorationProgram.UniformFillPattern;
    }

    @Override
    public List<DecorationSlot> generate(CompiledDecorationProgram program, BlockBounds queryBounds,
                                         DecorationShapeEvaluator shapeEvaluator) {
        CompiledDecorationProgram.UniformFillPattern pattern = (CompiledDecorationProgram.UniformFillPattern) program.pattern();
        List<DecorationSlot> slots = new ArrayList<>();
        DecorationPatternSupport.forEachAreaCell(program, queryBounds, shapeEvaluator, (x, z) -> {
            CompiledDecorationProgram.LocalPoint local = program.coordinateFrame().toLocal(x, z);
            slots.add(DecorationPatternSupport.slot(program, pattern.paletteSlotId(), x, z, 0,
                    local.u(), local.v()));
        });
        return slots;
    }
}
