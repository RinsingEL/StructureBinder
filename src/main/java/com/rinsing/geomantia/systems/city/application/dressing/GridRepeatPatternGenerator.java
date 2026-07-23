package com.rinsing.geomantia.systems.city.application.dressing;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.util.ArrayList;
import java.util.List;

final class GridRepeatPatternGenerator implements DecorationPatternGenerator {
    @Override
    public boolean supports(CompiledDecorationProgram.PatternSpec pattern) {
        return pattern instanceof CompiledDecorationProgram.GridRepeatPattern;
    }

    @Override
    public List<DecorationSlot> generate(CompiledDecorationProgram program, BlockBounds queryBounds,
                                         DecorationShapeEvaluator shapeEvaluator) {
        CompiledDecorationProgram.GridRepeatPattern pattern = (CompiledDecorationProgram.GridRepeatPattern) program.pattern();
        int phaseStartU = DecorationPatternSupport.phaseStart(program, CompiledDecorationProgram.Axis.U);
        int phaseStartV = DecorationPatternSupport.phaseStart(program, CompiledDecorationProgram.Axis.V);
        List<DecorationSlot> slots = new ArrayList<>();
        DecorationPatternSupport.forEachAreaCell(program, queryBounds, shapeEvaluator, (x, z) -> {
            CompiledDecorationProgram.LocalPoint local = program.coordinateFrame().toLocal(x, z);
            if (Math.floorMod(local.u() - phaseStartU - pattern.offsetUBlocks(), pattern.spacingUBlocks()) == 0
                    && Math.floorMod(local.v() - phaseStartV - pattern.offsetVBlocks(), pattern.spacingVBlocks()) == 0) {
                slots.add(DecorationPatternSupport.slot(program, pattern.paletteSlotId(), x, z, 0,
                        local.u(), local.v()));
            }
        });
        return slots;
    }
}
