package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationTerrainRunCompiler;
import net.minecraft.util.Mth;

import java.util.List;

/** Positive-only density contribution for frozen Decoration fill-only foundation segments. */
public final class CityDecorationFoundationDensityComputer {
    private static final double MAX_FILL_CONTRIBUTION = 0.5D;

    private CityDecorationFoundationDensityComputer() {
    }

    public static double compute(int x, int y, int z,
                                 List<CityDecorationTerrainRunCompiler.FoundationSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return 0.0D;
        }
        double best = 0.0D;
        for (CityDecorationTerrainRunCompiler.FoundationSegment segment : segments) {
            best = Math.max(best, computeForSegment(x, y, z, segment));
        }
        return best;
    }

    private static double computeForSegment(int x, int y, int z,
                                            CityDecorationTerrainRunCompiler.FoundationSegment segment) {
        double dx = segment.x1() - segment.x0();
        double dz = segment.z1() - segment.z0();
        double lengthSquared = dx * dx + dz * dz;
        if (lengthSquared < 1.0E-9D) {
            return 0.0D;
        }
        double t = ((x - segment.x0()) * dx + (z - segment.z0()) * dz) / lengthSquared;
        if (t < 0.0D || t > 1.0D) {
            return 0.0D;
        }
        double projectedX = segment.x0() + t * dx;
        double projectedZ = segment.z0() + t * dz;
        double lateralDistance = Math.sqrt((x - projectedX) * (x - projectedX)
                + (z - projectedZ) * (z - projectedZ));
        double zeroRadius = segment.halfWidth() + segment.shoulderBlocks();
        if (lateralDistance > zeroRadius || zeroRadius <= 0.0D && lateralDistance > 0.0D) {
            return 0.0D;
        }
        int targetY = (int) Math.round(segment.y0() + t * (segment.y1() - segment.y0()));
        int depth = targetY - y;
        if (depth <= 0 || depth > segment.maxDepthBlocks()) {
            return 0.0D;
        }
        double lateral = lateralDistance <= segment.halfWidth() ? 1.0D
                : 1.0D - smootherStep((lateralDistance - segment.halfWidth())
                / Math.max(1.0D, zeroRadius - segment.halfWidth()));
        double verticalT = Mth.clamp((depth - 0.5D) / Math.max(1.0D, segment.maxDepthBlocks()), 0.0D, 1.0D);
        return lateral * lateral * MAX_FILL_CONTRIBUTION * Math.pow(1.0D - verticalT, 2.2D);
    }

    private static double smootherStep(double value) {
        double x = Mth.clamp(value, 0.0D, 1.0D);
        return x * x * x * (x * (x * 6.0D - 15.0D) + 10.0D);
    }
}
