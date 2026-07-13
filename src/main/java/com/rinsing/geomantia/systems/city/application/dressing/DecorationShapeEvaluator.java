package com.rinsing.geomantia.systems.city.application.dressing;

import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram.LocalPoint;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram.ShapeSpec;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.util.List;

public final class DecorationShapeEvaluator {
    public BlockBounds candidateBounds(CompiledDecorationProgram program) {
        return program.targetMask().bounds();
    }

    public boolean contains(CompiledDecorationProgram program, int worldX, int worldZ) {
        if (!program.targetMask().contains(worldX, worldZ)) {
            return false;
        }
        LocalPoint local = program.coordinateFrame().toLocal(worldX, worldZ);
        return containsLocal(program.shape(), local.u(), local.v());
    }

    public boolean isBoundary(CompiledDecorationProgram program, int worldX, int worldZ) {
        if (!contains(program, worldX, worldZ)) {
            return false;
        }
        return !contains(program, worldX - 1, worldZ)
                || !contains(program, worldX + 1, worldZ)
                || !contains(program, worldX, worldZ - 1)
                || !contains(program, worldX, worldZ + 1);
    }

    private boolean containsLocal(ShapeSpec shape, int u, int v) {
        if (shape instanceof CompiledDecorationProgram.TargetMaskShape) {
            return true;
        }
        if (shape instanceof CompiledDecorationProgram.RectangleShape rectangle) {
            return u >= rectangle.minU() && u <= rectangle.maxU()
                    && v >= rectangle.minV() && v <= rectangle.maxV();
        }
        if (shape instanceof CompiledDecorationProgram.EllipseShape ellipse) {
            return normalizedDistance(u, v, ellipse.centerU(), ellipse.centerV(),
                    ellipse.radiusU(), ellipse.radiusV()) <= 1.0;
        }
        if (shape instanceof CompiledDecorationProgram.RingShape ring) {
            double outer = normalizedDistance(u, v, ring.centerU(), ring.centerV(),
                    ring.outerRadiusU(), ring.outerRadiusV());
            double inner = normalizedDistance(u, v, ring.centerU(), ring.centerV(),
                    ring.innerRadiusU(), ring.innerRadiusV());
            return outer <= 1.0 && inner >= 1.0;
        }
        if (shape instanceof CompiledDecorationProgram.PolygonShape polygon) {
            return polygonContains(polygon.vertices(), u, v);
        }
        throw new IllegalArgumentException("CITY_DECORATION_SHAPE_UNSUPPORTED: " + shape.type());
    }

    private double normalizedDistance(int u, int v, int centerU, int centerV, int radiusU, int radiusV) {
        double du = (u - centerU) / (double) radiusU;
        double dv = (v - centerV) / (double) radiusV;
        return du * du + dv * dv;
    }

    private boolean polygonContains(List<LocalPoint> vertices, int u, int v) {
        boolean inside = false;
        for (int i = 0, previous = vertices.size() - 1; i < vertices.size(); previous = i++) {
            LocalPoint a = vertices.get(previous);
            LocalPoint b = vertices.get(i);
            if (pointOnSegment(a, b, u, v)) {
                return true;
            }
            boolean crosses = (a.v() > v) != (b.v() > v)
                    && u < (b.u() - a.u()) * (v - a.v()) / (double) (b.v() - a.v()) + a.u();
            if (crosses) {
                inside = !inside;
            }
        }
        return inside;
    }

    private boolean pointOnSegment(LocalPoint a, LocalPoint b, int u, int v) {
        long cross = (long) (u - a.u()) * (b.v() - a.v())
                - (long) (v - a.v()) * (b.u() - a.u());
        if (cross != 0L) {
            return false;
        }
        return u >= Math.min(a.u(), b.u()) && u <= Math.max(a.u(), b.u())
                && v >= Math.min(a.v(), b.v()) && v <= Math.max(a.v(), b.v());
    }
}
