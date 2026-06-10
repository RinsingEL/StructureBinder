package com.rinsing.geomantia.systems.gis.algorithm.metrics;

import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.domain.cell.AtlasCell;
import com.rinsing.geomantia.systems.gis.domain.cell.CellStateFlag;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegion;
import com.rinsing.geomantia.systems.gis.domain.region.RegionStatus;

import java.util.ArrayDeque;
import java.util.Queue;

public final class AtlasMetricsComputer {
    private final GisSampleConfig config;

    public AtlasMetricsComputer(GisSampleConfig config) {
        this.config = config;
    }

    public void compute(AtlasRegion region) {
        int side = region.cellsPerSide();
        double[][] waterDistance = computeWaterDistance(region);
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                AtlasCell cell = region.cell(x, z);
                if (!cell.hasFlag(CellStateFlag.SAMPLED)) {
                    continue;
                }
                boolean smallDirty = !hasNeighborhood(region, x, z, config.tpiSmallRadiusCells());
                boolean largeDirty = !hasNeighborhood(region, x, z, config.tpiLargeRadiusCells());
                double slope = maxElevationDelta(region, x, z, config.slopeRadiusCells());
                double localRelief = localRelief(region, x, z, config.localReliefRadiusCells());
                double roughness = roughness(region, x, z, config.roughnessRadiusCells());
                double tpiSmall = tpi(region, x, z, config.tpiSmallRadiusCells());
                double tpiLarge = tpi(region, x, z, config.tpiLargeRadiusCells());
                cell.setSmallMetrics(slope, localRelief, roughness, tpiSmall);
                cell.setLargeMetrics(tpiLarge, waterDistance[x][z]);
                if (smallDirty || largeDirty) {
                    cell.addFlag(CellStateFlag.EDGE_DIRTY);
                }
            }
        }
        region.setStatus(RegionStatus.METRICS_PARTIAL);
    }

    private static boolean hasNeighborhood(AtlasRegion region, int cx, int cz, int radius) {
        if (cx - radius < 0 || cz - radius < 0
                || cx + radius >= region.cellsPerSide() || cz + radius >= region.cellsPerSide()) {
            return false;
        }
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                if (!region.cell(x, z).hasFlag(CellStateFlag.SAMPLED)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double maxElevationDelta(AtlasRegion region, int cx, int cz, int radius) {
        AtlasCell center = region.cell(cx, cz);
        double max = 0.0;
        for (int x = Math.max(0, cx - radius); x <= Math.min(region.cellsPerSide() - 1, cx + radius); x++) {
            for (int z = Math.max(0, cz - radius); z <= Math.min(region.cellsPerSide() - 1, cz + radius); z++) {
                if (x == cx && z == cz) {
                    continue;
                }
                if (!region.cell(x, z).hasFlag(CellStateFlag.SAMPLED)) {
                    continue;
                }
                max = Math.max(max, Math.abs(region.cell(x, z).elevation() - center.elevation()));
            }
        }
        return max;
    }

    private static double localRelief(AtlasRegion region, int cx, int cz, int radius) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int x = Math.max(0, cx - radius); x <= Math.min(region.cellsPerSide() - 1, cx + radius); x++) {
            for (int z = Math.max(0, cz - radius); z <= Math.min(region.cellsPerSide() - 1, cz + radius); z++) {
                if (!region.cell(x, z).hasFlag(CellStateFlag.SAMPLED)) {
                    continue;
                }
                double elevation = region.cell(x, z).elevation();
                min = Math.min(min, elevation);
                max = Math.max(max, elevation);
            }
        }
        return Double.isFinite(min) && Double.isFinite(max) ? max - min : 0.0;
    }

    private static double roughness(AtlasRegion region, int cx, int cz, int radius) {
        double sum = 0.0;
        double sumSq = 0.0;
        int count = 0;
        for (int x = Math.max(0, cx - radius); x <= Math.min(region.cellsPerSide() - 1, cx + radius); x++) {
            for (int z = Math.max(0, cz - radius); z <= Math.min(region.cellsPerSide() - 1, cz + radius); z++) {
                if (!region.cell(x, z).hasFlag(CellStateFlag.SAMPLED)) {
                    continue;
                }
                double elevation = region.cell(x, z).elevation();
                sum += elevation;
                sumSq += elevation * elevation;
                count++;
            }
        }
        double mean = sum / Math.max(1, count);
        return Math.sqrt(Math.max(0.0, sumSq / Math.max(1, count) - mean * mean));
    }

    private static double tpi(AtlasRegion region, int cx, int cz, int radius) {
        double sum = 0.0;
        int count = 0;
        for (int x = Math.max(0, cx - radius); x <= Math.min(region.cellsPerSide() - 1, cx + radius); x++) {
            for (int z = Math.max(0, cz - radius); z <= Math.min(region.cellsPerSide() - 1, cz + radius); z++) {
                if (x == cx && z == cz) {
                    continue;
                }
                double dx = x - cx;
                double dz = z - cz;
                if (Math.sqrt(dx * dx + dz * dz) <= radius) {
                    if (!region.cell(x, z).hasFlag(CellStateFlag.SAMPLED)) {
                        continue;
                    }
                    sum += region.cell(x, z).elevation();
                    count++;
                }
            }
        }
        if (count == 0) {
            return 0.0;
        }
        return region.cell(cx, cz).elevation() - sum / count;
    }

    private double[][] computeWaterDistance(AtlasRegion region) {
        int side = region.cellsPerSide();
        double[][] distance = new double[side][side];
        Queue<int[]> queue = new ArrayDeque<>();
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                if (region.cell(x, z).isWater()) {
                    distance[x][z] = 0.0;
                    queue.add(new int[] { x, z });
                } else if (!region.cell(x, z).hasFlag(CellStateFlag.SAMPLED)) {
                    distance[x][z] = Double.NaN;
                } else {
                    distance[x][z] = Double.POSITIVE_INFINITY;
                }
            }
        }
        int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        while (!queue.isEmpty()) {
            int[] current = queue.remove();
            double next = distance[current[0]][current[1]] + 1.0;
            if (next > config.maxWaterDistanceCells()) {
                continue;
            }
            for (int[] dir : dirs) {
                int nx = current[0] + dir[0];
                int nz = current[1] + dir[1];
                if (region.containsLocal(nx, nz) && !Double.isNaN(distance[nx][nz]) && next < distance[nx][nz]) {
                    distance[nx][nz] = next;
                    queue.add(new int[] { nx, nz });
                }
            }
        }
        return distance;
    }
}
