package com.rinsing.geomantia.systems.city.application.dressing;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.List;

final class DeterministicScatterPatternGenerator implements DecorationPatternGenerator {
    @Override
    public boolean supports(CompiledDecorationProgram.PatternSpec pattern) {
        return pattern instanceof CompiledDecorationProgram.DeterministicScatterPattern;
    }

    @Override
    public List<DecorationSlot> generate(CompiledDecorationProgram program, BlockBounds queryBounds,
                                         DecorationShapeEvaluator shapeEvaluator) {
        CompiledDecorationProgram.DeterministicScatterPattern pattern =
                (CompiledDecorationProgram.DeterministicScatterPattern) program.pattern();
        int size = pattern.cellSizeBlocks();
        int[] localRange = localRange(program.coordinateFrame(), queryBounds);
        int minTileU = Math.floorDiv(localRange[0], size);
        int maxTileU = Math.floorDiv(localRange[1], size);
        int minTileV = Math.floorDiv(localRange[2], size);
        int maxTileV = Math.floorDiv(localRange[3], size);
        List<DecorationSlot> slots = new ArrayList<>();
        for (int tileV = minTileV; tileV <= maxTileV; tileV++) {
            for (int tileU = minTileU; tileU <= maxTileU; tileU++) {
                long densityHash = DecorationDeterminism.worldHash(program.seed(), program.programId(), tileU, tileV, 0);
                if (DecorationDeterminism.boundedInt(densityHash, 1000) >= pattern.densityPermille()) {
                    continue;
                }
                int u = tileU * size + DecorationDeterminism.boundedInt(
                        DecorationDeterminism.worldHash(program.seed(), program.programId(), tileU, tileV, 1), size);
                int v = tileV * size + DecorationDeterminism.boundedInt(
                        DecorationDeterminism.worldHash(program.seed(), program.programId(), tileU, tileV, 2), size);
                BlockPoint world = program.coordinateFrame().toWorld(u, v);
                if (queryBounds.contains(world.x(), world.z()) && shapeEvaluator.contains(program, world.x(), world.z())) {
                    slots.add(DecorationPatternSupport.slot(program, pattern.paletteSlotId(), world.x(), world.z(), 0,
                            tileU, tileV));
                }
            }
        }
        return slots;
    }

    private int[] localRange(CompiledDecorationProgram.CoordinateFrame frame, BlockBounds bounds) {
        CompiledDecorationProgram.LocalPoint[] corners = {
                frame.toLocal(bounds.minX(), bounds.minZ()), frame.toLocal(bounds.minX(), bounds.maxZ()),
                frame.toLocal(bounds.maxX(), bounds.minZ()), frame.toLocal(bounds.maxX(), bounds.maxZ())
        };
        int minU = Integer.MAX_VALUE;
        int maxU = Integer.MIN_VALUE;
        int minV = Integer.MAX_VALUE;
        int maxV = Integer.MIN_VALUE;
        for (CompiledDecorationProgram.LocalPoint corner : corners) {
            minU = Math.min(minU, corner.u());
            maxU = Math.max(maxU, corner.u());
            minV = Math.min(minV, corner.v());
            maxV = Math.max(maxV, corner.v());
        }
        return new int[]{minU, maxU, minV, maxV};
    }
}
