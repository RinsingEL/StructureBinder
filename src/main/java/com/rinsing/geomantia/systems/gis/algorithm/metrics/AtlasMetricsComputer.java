package com.rinsing.geomantia.systems.gis.algorithm.metrics;

import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.domain.cell.AtlasCell;
import com.rinsing.geomantia.systems.gis.domain.cell.CellStateFlag;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegion;
import com.rinsing.geomantia.systems.gis.domain.region.RegionStatus;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public void computeAcrossRegions(List<AtlasRegion> regions) {
        if (regions == null || regions.isEmpty()) {
            return;
        }
        Map<CellKey, AtlasCell> cells = new LinkedHashMap<>();
        regions.stream().filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(AtlasRegion::regionZ).thenComparingInt(AtlasRegion::regionX))
                .flatMap(region -> region.cells().stream())
                .filter(cell -> cell.hasFlag(CellStateFlag.SAMPLED))
                .forEach(cell -> cells.put(new CellKey(cell.globalCellX(), cell.globalCellZ()), cell));
        Map<CellKey, Double> waterDistance = computeWaterDistance(cells);
        for (Map.Entry<CellKey, AtlasCell> entry : cells.entrySet()) {
            CellKey key = entry.getKey();
            AtlasCell cell = entry.getValue();
            cell.removeFlag(CellStateFlag.EDGE_DIRTY);
            boolean smallDirty = !hasNeighborhood(cells, key, config.tpiSmallRadiusCells());
            boolean largeDirty = !hasNeighborhood(cells, key, config.tpiLargeRadiusCells());
            cell.setSmallMetrics(
                    maxElevationDelta(cells, key, config.slopeRadiusCells()),
                    localRelief(cells, key, config.localReliefRadiusCells()),
                    roughness(cells, key, config.roughnessRadiusCells()),
                    tpi(cells, key, config.tpiSmallRadiusCells()));
            cell.setLargeMetrics(tpi(cells, key, config.tpiLargeRadiusCells()),
                    waterDistance.getOrDefault(key, Double.POSITIVE_INFINITY));
            if (smallDirty || largeDirty) {
                cell.addFlag(CellStateFlag.EDGE_DIRTY);
            }
        }
        regions.stream().filter(java.util.Objects::nonNull)
                .forEach(region -> region.setStatus(RegionStatus.METRICS_PARTIAL));
    }

    private static boolean hasNeighborhood(Map<CellKey, AtlasCell> cells, CellKey center, int radius) {
        for (int x = center.x() - radius; x <= center.x() + radius; x++) {
            for (int z = center.z() - radius; z <= center.z() + radius; z++) {
                if (!cells.containsKey(new CellKey(x, z))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double maxElevationDelta(Map<CellKey, AtlasCell> cells, CellKey center, int radius) {
        AtlasCell centerCell = cells.get(center);
        double max = 0.0;
        for (int x = center.x() - radius; x <= center.x() + radius; x++) {
            for (int z = center.z() - radius; z <= center.z() + radius; z++) {
                AtlasCell neighbor = cells.get(new CellKey(x, z));
                if (neighbor != null && neighbor != centerCell) {
                    max = Math.max(max, Math.abs(neighbor.elevation() - centerCell.elevation()));
                }
            }
        }
        return max;
    }

    private static double localRelief(Map<CellKey, AtlasCell> cells, CellKey center, int radius) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int x = center.x() - radius; x <= center.x() + radius; x++) {
            for (int z = center.z() - radius; z <= center.z() + radius; z++) {
                AtlasCell neighbor = cells.get(new CellKey(x, z));
                if (neighbor != null) {
                    min = Math.min(min, neighbor.elevation());
                    max = Math.max(max, neighbor.elevation());
                }
            }
        }
        return Double.isFinite(min) && Double.isFinite(max) ? max - min : 0.0;
    }

    private static double roughness(Map<CellKey, AtlasCell> cells, CellKey center, int radius) {
        double sum = 0.0;
        double sumSq = 0.0;
        int count = 0;
        for (int x = center.x() - radius; x <= center.x() + radius; x++) {
            for (int z = center.z() - radius; z <= center.z() + radius; z++) {
                AtlasCell neighbor = cells.get(new CellKey(x, z));
                if (neighbor == null) {
                    continue;
                }
                sum += neighbor.elevation();
                sumSq += neighbor.elevation() * neighbor.elevation();
                count++;
            }
        }
        double mean = sum / Math.max(1, count);
        return Math.sqrt(Math.max(0.0, sumSq / Math.max(1, count) - mean * mean));
    }

    private static double tpi(Map<CellKey, AtlasCell> cells, CellKey center, int radius) {
        double sum = 0.0;
        int count = 0;
        for (int x = center.x() - radius; x <= center.x() + radius; x++) {
            for (int z = center.z() - radius; z <= center.z() + radius; z++) {
                if (x == center.x() && z == center.z()) {
                    continue;
                }
                double dx = x - center.x();
                double dz = z - center.z();
                if (Math.sqrt(dx * dx + dz * dz) > radius) {
                    continue;
                }
                AtlasCell neighbor = cells.get(new CellKey(x, z));
                if (neighbor != null) {
                    sum += neighbor.elevation();
                    count++;
                }
            }
        }
        return count == 0 ? 0.0 : cells.get(center).elevation() - sum / count;
    }

    private Map<CellKey, Double> computeWaterDistance(Map<CellKey, AtlasCell> cells) {
        Map<CellKey, Double> distance = new LinkedHashMap<>();
        Queue<CellKey> queue = new ArrayDeque<>();
        cells.forEach((key, cell) -> {
            if (cell.isWater()) {
                distance.put(key, 0.0);
                queue.add(key);
            } else {
                distance.put(key, Double.POSITIVE_INFINITY);
            }
        });
        while (!queue.isEmpty()) {
            CellKey current = queue.remove();
            double next = distance.get(current) + 1.0;
            if (next > config.maxWaterDistanceCells()) {
                continue;
            }
            for (int[] dir : DIRECTIONS) {
                CellKey neighbor = new CellKey(current.x() + dir[0], current.z() + dir[1]);
                if (distance.containsKey(neighbor) && next < distance.get(neighbor)) {
                    distance.put(neighbor, next);
                    queue.add(neighbor);
                }
            }
        }
        return distance;
    }

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private record CellKey(int x, int z) {
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
