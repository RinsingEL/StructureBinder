package com.rinsing.geomantia.systems.city.application.terrain;

import java.util.List;

/** Positive-only density contribution shared by continuous terrain foundation consumers. */
public final class CityTerrainFoundationDensityComputer {
    private static final double MAX_FILL_CONTRIBUTION = 0.5D;

    private CityTerrainFoundationDensityComputer() {
    }

    public static double compute(int x, int y, int z,
                                 List<CityContinuousTerrainRunPlanner.FoundationSegment> segments) {
        return computeViews(x, y, z, segments);
    }

    public static double computeViews(int x, int y, int z,
                                      List<? extends FoundationSegmentView> segments) {
        if (segments == null || segments.isEmpty()) return 0.0D;
        double best = 0.0D;
        for (FoundationSegmentView segment : segments) {
            best = Math.max(best, computeForSegment(x, y, z, segment));
        }
        return best;
    }

    public static double computePlatformViews(int x, int y, int z,
                                              List<? extends FoundationPlatformView> platforms) {
        if (platforms == null || platforms.isEmpty()) return 0.0D;
        double best = 0.0D;
        for (FoundationPlatformView platform : platforms) {
            best = Math.max(best, computeForPlatform(x, y, z, platform));
        }
        return best;
    }

    private static double computeForSegment(int x, int y, int z, FoundationSegmentView segment) {
        double dx = segment.x1() - segment.x0();
        double dz = segment.z1() - segment.z0();
        double lengthSquared = dx * dx + dz * dz;
        if (lengthSquared < 1.0E-9D) return 0.0D;
        double t = ((x - segment.x0()) * dx + (z - segment.z0()) * dz) / lengthSquared;
        if (t < 0.0D || t > 1.0D) return 0.0D;
        double projectedX = segment.x0() + t * dx;
        double projectedZ = segment.z0() + t * dz;
        double lateralDistance = Math.sqrt((x - projectedX) * (x - projectedX)
                + (z - projectedZ) * (z - projectedZ));
        double zeroRadius = segment.halfWidth() + segment.shoulderBlocks();
        if (lateralDistance > zeroRadius || zeroRadius <= 0.0D && lateralDistance > 0.0D) return 0.0D;
        int targetY = (int) Math.round(segment.y0() + t * (segment.y1() - segment.y0()));
        int depth = targetY - y;
        if (depth <= 0 || depth > segment.maxDepthBlocks()) return 0.0D;
        double lateral = lateralDistance <= segment.halfWidth() ? 1.0D
                : 1.0D - smootherStep((lateralDistance - segment.halfWidth())
                / Math.max(1.0D, zeroRadius - segment.halfWidth()));
        double verticalT = clamp((depth - 0.5D) / Math.max(1.0D, segment.maxDepthBlocks()), 0.0D, 1.0D);
        return lateral * lateral * MAX_FILL_CONTRIBUTION * Math.pow(1.0D - verticalT, 2.2D);
    }

    private static double computeForPlatform(int x, int y, int z, FoundationPlatformView platform) {
        int horizontalX = x < platform.minX() ? platform.minX() - x
                : x > platform.maxX() ? x - platform.maxX() : 0;
        int horizontalZ = z < platform.minZ() ? platform.minZ() - z
                : z > platform.maxZ() ? z - platform.maxZ() : 0;
        double horizontalDistance = Math.hypot(horizontalX, horizontalZ);
        if (horizontalDistance > platform.shoulderBlocks()) return 0.0D;
        int depth = platform.targetY() - y;
        if (depth <= 0 || depth > platform.maxDepthBlocks()) return 0.0D;
        double lateral = horizontalDistance == 0.0D ? 1.0D
                : 1.0D - smootherStep(horizontalDistance / Math.max(1.0D, platform.shoulderBlocks()));
        double verticalT = clamp((depth - 0.5D) / Math.max(1.0D, platform.maxDepthBlocks()), 0.0D, 1.0D);
        return lateral * lateral * MAX_FILL_CONTRIBUTION * Math.pow(1.0D - verticalT, 2.2D);
    }

    private static double smootherStep(double value) {
        double x = clamp(value, 0.0D, 1.0D);
        return x * x * x * (x * (x * 6.0D - 15.0D) + 10.0D);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public interface FoundationSegmentView {
        int x0();

        int z0();

        int y0();

        int x1();

        int z1();

        int y1();

        int halfWidth();

        int maxDepthBlocks();

        int shoulderBlocks();
    }

    public interface FoundationPlatformView {
        int minX();

        int minZ();

        int maxX();

        int maxZ();

        int targetY();

        int maxDepthBlocks();

        int shoulderBlocks();
    }
}
