package com.rinsing.geomantia.systems.city.application.dressing;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.util.ArrayList;
import java.util.List;

final class ParallelRowsPatternGenerator implements DecorationPatternGenerator {
    @Override
    public boolean supports(CompiledDecorationProgram.PatternSpec pattern) {
        return pattern instanceof CompiledDecorationProgram.ParallelRowsPattern;
    }

    @Override
    public List<DecorationSlot> generate(CompiledDecorationProgram program, BlockBounds queryBounds,
                                         DecorationShapeEvaluator shapeEvaluator) {
        CompiledDecorationProgram.ParallelRowsPattern pattern = (CompiledDecorationProgram.ParallelRowsPattern) program.pattern();
        CompiledDecorationProgram.Axis crossAxis = pattern.axis() == CompiledDecorationProgram.Axis.U
                ? CompiledDecorationProgram.Axis.V : CompiledDecorationProgram.Axis.U;
        int phaseStart = DecorationPatternSupport.phaseStart(program, crossAxis);
        List<DecorationSlot> slots = new ArrayList<>();
        DecorationPatternSupport.forEachAreaCell(program, queryBounds, shapeEvaluator, (x, z) -> {
            CompiledDecorationProgram.LocalPoint local = program.coordinateFrame().toLocal(x, z);
            int cross = pattern.axis() == CompiledDecorationProgram.Axis.U ? local.v() : local.u();
            if (Math.floorMod(cross - phaseStart - pattern.offsetBlocks(), pattern.spacingBlocks())
                    < pattern.rowWidthBlocks()) {
                slots.add(DecorationPatternSupport.slot(program, pattern.paletteSlotId(), x, z,
                        pattern.axis() == CompiledDecorationProgram.Axis.U ? 0 : 1, local.u(), local.v()));
            }
        });
        return slots;
    }
}
