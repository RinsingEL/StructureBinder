package com.rinsing.geomantia.systems.city.application.dressing;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.util.ArrayList;
import java.util.List;

final class CrossSectionRepeatPatternGenerator implements DecorationPatternGenerator {
    @Override
    public boolean supports(CompiledDecorationProgram.PatternSpec pattern) {
        return pattern instanceof CompiledDecorationProgram.CrossSectionRepeatPattern;
    }

    @Override
    public List<DecorationSlot> generate(CompiledDecorationProgram program, BlockBounds queryBounds,
                                         DecorationShapeEvaluator shapeEvaluator) {
        CompiledDecorationProgram.CrossSectionRepeatPattern pattern =
                (CompiledDecorationProgram.CrossSectionRepeatPattern) program.pattern();
        int cycle = pattern.bands().stream().mapToInt(CompiledDecorationProgram.CrossSectionBand::widthBlocks).sum();
        int phaseStart = DecorationPatternSupport.phaseStart(program, pattern.axis());
        List<DecorationSlot> slots = new ArrayList<>();
        DecorationPatternSupport.forEachAreaCell(program, queryBounds, shapeEvaluator, (x, z) -> {
            CompiledDecorationProgram.LocalPoint local = program.coordinateFrame().toLocal(x, z);
            int cross = pattern.axis() == CompiledDecorationProgram.Axis.U ? local.u() : local.v();
            int cursor = Math.floorMod(cross - phaseStart - pattern.offsetBlocks(), cycle);
            CompiledDecorationProgram.CrossSectionBand selected = pattern.bands().get(0);
            int bandStart = 0;
            for (CompiledDecorationProgram.CrossSectionBand band : pattern.bands()) {
                if (cursor < bandStart + band.widthBlocks()) {
                    selected = band;
                    break;
                }
                bandStart += band.widthBlocks();
            }
            slots.add(DecorationPatternSupport.slot(program, selected.paletteSlotId(), x, z,
                    pattern.axis() == CompiledDecorationProgram.Axis.U ? 1 : 0, local.u(), local.v()));
        });
        return slots;
    }
}
