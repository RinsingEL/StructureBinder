package com.rinsing.geomantia.systems.city.application.dressing;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class EdgeRepeatPatternGenerator implements DecorationPatternGenerator {
    @Override
    public boolean supports(CompiledDecorationProgram.PatternSpec pattern) {
        return pattern instanceof CompiledDecorationProgram.EdgeRepeatPattern;
    }

    @Override
    public List<DecorationSlot> generate(CompiledDecorationProgram program, BlockBounds queryBounds,
                                         DecorationShapeEvaluator shapeEvaluator) {
        CompiledDecorationProgram.EdgeRepeatPattern pattern = (CompiledDecorationProgram.EdgeRepeatPattern) program.pattern();
        List<BlockPoint> boundary = new ArrayList<>();
        DecorationPatternSupport.forEachAreaCell(program, shapeEvaluator.candidateBounds(program), shapeEvaluator,
                (x, z) -> {
                    if (shapeEvaluator.isBoundary(program, x, z)) {
                        boundary.add(new BlockPoint(x, z));
                    }
                });
        boundary.sort(Comparator.comparingInt(BlockPoint::x).thenComparingInt(BlockPoint::z));
        List<DecorationSlot> slots = new ArrayList<>();
        int normalizedOffset = Math.floorMod(pattern.offsetBlocks(), pattern.spacingBlocks());
        for (int i = normalizedOffset; i < boundary.size(); i += pattern.spacingBlocks()) {
            BlockPoint point = boundary.get(i);
            if (!queryBounds.contains(point.x(), point.z())) {
                continue;
            }
            CompiledDecorationProgram.LocalPoint local = program.coordinateFrame().toLocal(point.x(), point.z());
            slots.add(DecorationPatternSupport.slot(program, pattern.paletteSlotId(), point.x(), point.z(), 0,
                    local.u(), local.v()));
        }
        return slots;
    }
}
