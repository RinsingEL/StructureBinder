package com.rinsing.geomantia.systems.city.application.dressing;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.function.BiConsumer;

final class DecorationPatternSupport {
    private DecorationPatternSupport() {
    }

    static void forEachAreaCell(CompiledDecorationProgram program, BlockBounds queryBounds,
                                DecorationShapeEvaluator evaluator, BiConsumer<Integer, Integer> consumer) {
        BlockBounds candidate = evaluator.candidateBounds(program);
        int minX = Math.max(candidate.minX(), queryBounds.minX());
        int minZ = Math.max(candidate.minZ(), queryBounds.minZ());
        int maxX = Math.min(candidate.maxX(), queryBounds.maxX());
        int maxZ = Math.min(candidate.maxZ(), queryBounds.maxZ());
        if (minX > maxX || minZ > maxZ) {
            return;
        }
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                if (evaluator.contains(program, x, z)) {
                    consumer.accept(x, z);
                }
            }
        }
    }

    static DecorationSlot slot(CompiledDecorationProgram program, String paletteSlotId,
                               int worldX, int worldZ, int rotationQuarterTurns, long... semanticCoordinates) {
        CompiledDecorationProgram.LocalPoint local = program.coordinateFrame().toLocal(worldX, worldZ);
        return new DecorationSlot(DecorationDeterminism.slotId(program.programId(), program.pattern().type(),
                semanticCoordinates), program.programId(), paletteSlotId, new BlockPoint(worldX, worldZ),
                local, rotationQuarterTurns);
    }

    /**
     * Offset-based patterns start at the shape's local lower edge, rather than at the enclosing patch origin.
     * This keeps a rectangle that starts at U=120 aligned exactly like the same rectangle at U=0.
     */
    static int phaseStart(CompiledDecorationProgram program, CompiledDecorationProgram.Axis axis) {
        CompiledDecorationProgram.ShapeSpec shape = program.shape();
        if (shape instanceof CompiledDecorationProgram.RectangleShape rectangle) {
            return axis == CompiledDecorationProgram.Axis.U ? rectangle.minU() : rectangle.minV();
        }
        if (shape instanceof CompiledDecorationProgram.EllipseShape ellipse) {
            return axis == CompiledDecorationProgram.Axis.U
                    ? ellipse.centerU() - ellipse.radiusU()
                    : ellipse.centerV() - ellipse.radiusV();
        }
        if (shape instanceof CompiledDecorationProgram.RingShape ring) {
            return axis == CompiledDecorationProgram.Axis.U
                    ? ring.centerU() - ring.outerRadiusU()
                    : ring.centerV() - ring.outerRadiusV();
        }
        if (shape instanceof CompiledDecorationProgram.PolygonShape polygon) {
            return polygon.vertices().stream()
                    .mapToInt(point -> axis == CompiledDecorationProgram.Axis.U ? point.u() : point.v())
                    .min()
                    .orElseThrow(() -> new IllegalArgumentException("CITY_DECORATION_POLYGON_VERTICES_REQUIRED"));
        }
        return minLocal(program.coordinateFrame(), program.targetMask().bounds(), axis);
    }

    private static int minLocal(CompiledDecorationProgram.CoordinateFrame frame,
                                BlockBounds bounds,
                                CompiledDecorationProgram.Axis axis) {
        CompiledDecorationProgram.LocalPoint[] corners = {
                frame.toLocal(bounds.minX(), bounds.minZ()),
                frame.toLocal(bounds.minX(), bounds.maxZ()),
                frame.toLocal(bounds.maxX(), bounds.minZ()),
                frame.toLocal(bounds.maxX(), bounds.maxZ())
        };
        int minimum = Integer.MAX_VALUE;
        for (CompiledDecorationProgram.LocalPoint corner : corners) {
            minimum = Math.min(minimum, axis == CompiledDecorationProgram.Axis.U ? corner.u() : corner.v());
        }
        return minimum;
    }
}
