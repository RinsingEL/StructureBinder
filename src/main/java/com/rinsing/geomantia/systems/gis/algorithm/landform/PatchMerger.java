package com.rinsing.geomantia.systems.gis.algorithm.landform;

import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.domain.cell.AtlasCell;
import com.rinsing.geomantia.systems.gis.domain.cell.CellStateFlag;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.landform.PatchFlag;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegion;
import com.rinsing.geomantia.systems.gis.domain.region.RegionStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;

public final class PatchMerger {
    private final GisClassifierConfig config;

    public PatchMerger(GisClassifierConfig config) {
        this.config = config;
    }

    public List<LandformPatch> merge(AtlasRegion region) {
        int side = region.cellsPerSide();
        boolean[][] seen = new boolean[side][side];
        List<LandformPatch> patches = new ArrayList<>();
        int patchSeq = 1;
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                if (seen[x][z]) {
                    continue;
                }
                AtlasCell start = region.cell(x, z);
                if (!start.hasFlag(CellStateFlag.LANDFORM_READY)) {
                    seen[x][z] = true;
                    continue;
                }
                LandformType type = start.landformType();
                List<AtlasCell> cells = flood(region, x, z, type, seen);
                LandformPatch patch = summarize(region, patchSeq++, type, cells);
                patches.add(patch);
                cells.forEach(cell -> cell.setPatchId(patch.patchId()));
            }
        }
        region.replacePatches(patches);
        region.setStatus(RegionStatus.READY);
        return patches;
    }

    public List<LandformPatch> mergeAcrossRegions(List<AtlasRegion> regions, String patchNamespace) {
        return mergeAcrossRegions(regions, cell -> true, patchNamespace);
    }

    public List<LandformPatch> mergeAcrossRegions(List<AtlasRegion> regions, Predicate<AtlasCell> included,
            String patchNamespace) {
        if (regions == null || regions.isEmpty()) {
            return List.of();
        }
        if (patchNamespace == null || patchNamespace.isBlank()) {
            throw new IllegalArgumentException("patchNamespace is required.");
        }
        List<AtlasRegion> present = regions.stream().filter(java.util.Objects::nonNull).toList();
        if (present.isEmpty()) {
            return List.of();
        }
        int step = present.get(0).cellStepBlocks();
        if (present.stream().anyMatch(region -> region.cellStepBlocks() != step)) {
            throw new IllegalArgumentException("All GIS regions must use the same cell step.");
        }
        Map<CellKey, AtlasCell> eligible = new LinkedHashMap<>();
        present.stream()
                .sorted(Comparator.comparingInt(AtlasRegion::regionZ).thenComparingInt(AtlasRegion::regionX))
                .flatMap(region -> region.cells().stream())
                .filter(cell -> cell.hasFlag(CellStateFlag.LANDFORM_READY) && included.test(cell))
                .forEach(cell -> eligible.put(new CellKey(cell.globalCellX(), cell.globalCellZ()), cell));
        Set<CellKey> seen = new HashSet<>();
        List<LandformPatch> patches = new ArrayList<>();
        int patchSeq = 1;
        for (Map.Entry<CellKey, AtlasCell> entry : eligible.entrySet()) {
            if (!seen.add(entry.getKey())) {
                continue;
            }
            List<AtlasCell> cells = flood(eligible, entry.getKey(), entry.getValue().landformType(), seen);
            LandformPatch patch = summarize(patchNamespace, step, patchSeq++, entry.getValue().landformType(),
                    cells, eligible);
            patches.add(patch);
            cells.forEach(cell -> cell.setPatchId(patch.patchId()));
        }
        return List.copyOf(patches);
    }

    private static List<AtlasCell> flood(AtlasRegion region, int startX, int startZ, LandformType type,
            boolean[][] seen) {
        List<AtlasCell> cells = new ArrayList<>();
        Queue<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] { startX, startZ });
        seen[startX][startZ] = true;
        int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        while (!queue.isEmpty()) {
            int[] current = queue.remove();
            AtlasCell cell = region.cell(current[0], current[1]);
            cells.add(cell);
            for (int[] dir : dirs) {
                int nx = current[0] + dir[0];
                int nz = current[1] + dir[1];
                if (region.containsLocal(nx, nz) && !seen[nx][nz]
                        && region.cell(nx, nz).hasFlag(CellStateFlag.LANDFORM_READY)
                        && region.cell(nx, nz).landformType() == type) {
                    seen[nx][nz] = true;
                    queue.add(new int[] { nx, nz });
                }
            }
        }
        return cells;
    }

    private static List<AtlasCell> flood(Map<CellKey, AtlasCell> eligible, CellKey start, LandformType type,
            Set<CellKey> seen) {
        List<AtlasCell> cells = new ArrayList<>();
        Queue<CellKey> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            CellKey current = queue.remove();
            cells.add(eligible.get(current));
            for (int[] dir : DIRECTIONS) {
                CellKey neighbor = new CellKey(current.x() + dir[0], current.z() + dir[1]);
                AtlasCell neighborCell = eligible.get(neighbor);
                if (neighborCell != null && neighborCell.landformType() == type && seen.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        return cells;
    }

    private LandformPatch summarize(String namespace, int step, int patchSeq, LandformType type,
            List<AtlasCell> cells, Map<CellKey, AtlasCell> eligible) {
        int blockMinX = Integer.MAX_VALUE;
        int blockMinZ = Integer.MAX_VALUE;
        int blockMaxX = Integer.MIN_VALUE;
        int blockMaxZ = Integer.MIN_VALUE;
        double sumElevation = 0.0;
        double minElevation = Double.POSITIVE_INFINITY;
        double maxElevation = Double.NEGATIVE_INFINITY;
        double sumSlope = 0.0;
        double sumWaterDistance = 0.0;
        boolean touchesWater = false;
        boolean touchesAnalysisEdge = false;
        int dirty = 0;
        for (AtlasCell cell : cells) {
            blockMinX = Math.min(blockMinX, cell.blockMinX());
            blockMinZ = Math.min(blockMinZ, cell.blockMinZ());
            blockMaxX = Math.max(blockMaxX, cell.blockMinX() + step - 1);
            blockMaxZ = Math.max(blockMaxZ, cell.blockMinZ() + step - 1);
            sumElevation += cell.elevation();
            minElevation = Math.min(minElevation, cell.elevation());
            maxElevation = Math.max(maxElevation, cell.elevation());
            sumSlope += cell.slope();
            sumWaterDistance += Double.isFinite(cell.waterDistance()) ? cell.waterDistance() : 9999.0;
            touchesWater = touchesWater || cell.isWater() || cell.waterDistance() <= 1.0;
            CellKey key = new CellKey(cell.globalCellX(), cell.globalCellZ());
            for (int[] dir : DIRECTIONS) {
                if (!eligible.containsKey(new CellKey(key.x() + dir[0], key.z() + dir[1]))) {
                    touchesAnalysisEdge = true;
                    break;
                }
            }
            if (cell.hasFlag(CellStateFlag.EDGE_DIRTY)) {
                dirty++;
            }
        }
        Set<PatchFlag> flags = EnumSet.noneOf(PatchFlag.class);
        if (cells.size() <= config.fragmentMaxPatchCells()) {
            flags.add(PatchFlag.FRAGMENT);
        }
        if (dirty > 0) {
            flags.add(PatchFlag.EDGE_DIRTY);
        }
        if (touchesAnalysisEdge) {
            flags.add(PatchFlag.CROSS_REGION_CANDIDATE);
        }
        double confidence = Math.max(0.0, 1.0 - dirty / (double) Math.max(1, cells.size()));
        String patchId = namespace + ":p." + patchSeq;
        return new LandformPatch(patchId, namespace, type, cells.size(), blockMinX, blockMinZ, blockMaxX,
                blockMaxZ, sumElevation / cells.size(), minElevation, maxElevation, sumSlope / cells.size(),
                sumWaterDistance / cells.size(), touchesWater, touchesAnalysisEdge, confidence, flags);
    }

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private record CellKey(int x, int z) {
    }

    private LandformPatch summarize(AtlasRegion region, int patchSeq, LandformType type, List<AtlasCell> cells) {
        int blockMinX = Integer.MAX_VALUE;
        int blockMinZ = Integer.MAX_VALUE;
        int blockMaxX = Integer.MIN_VALUE;
        int blockMaxZ = Integer.MIN_VALUE;
        double sumElevation = 0.0;
        double minElevation = Double.POSITIVE_INFINITY;
        double maxElevation = Double.NEGATIVE_INFINITY;
        double sumSlope = 0.0;
        double sumWaterDistance = 0.0;
        boolean touchesWater = false;
        boolean touchesRegionEdge = false;
        int dirty = 0;
        for (AtlasCell cell : cells) {
            blockMinX = Math.min(blockMinX, cell.blockMinX());
            blockMinZ = Math.min(blockMinZ, cell.blockMinZ());
            blockMaxX = Math.max(blockMaxX, cell.blockMinX() + region.cellStepBlocks() - 1);
            blockMaxZ = Math.max(blockMaxZ, cell.blockMinZ() + region.cellStepBlocks() - 1);
            sumElevation += cell.elevation();
            minElevation = Math.min(minElevation, cell.elevation());
            maxElevation = Math.max(maxElevation, cell.elevation());
            sumSlope += cell.slope();
            sumWaterDistance += Double.isFinite(cell.waterDistance()) ? cell.waterDistance() : 9999.0;
            touchesWater = touchesWater || cell.isWater() || cell.waterDistance() <= 1.0;
            touchesRegionEdge = touchesRegionEdge || cell.localCellX() == 0 || cell.localCellZ() == 0
                    || cell.localCellX() == region.cellsPerSide() - 1
                    || cell.localCellZ() == region.cellsPerSide() - 1;
            if (cell.hasFlag(CellStateFlag.EDGE_DIRTY)) {
                dirty++;
            }
        }
        Set<PatchFlag> flags = EnumSet.noneOf(PatchFlag.class);
        if (cells.size() <= config.fragmentMaxPatchCells()) {
            flags.add(PatchFlag.FRAGMENT);
        }
        if (dirty > 0) {
            flags.add(PatchFlag.EDGE_DIRTY);
        }
        if (touchesRegionEdge) {
            flags.add(PatchFlag.CROSS_REGION_CANDIDATE);
        }
        double confidence = Math.max(0.0, 1.0 - dirty / (double) Math.max(1, cells.size()));
        return new LandformPatch(region.regionId() + ":p." + patchSeq, region.regionId(), type, cells.size(),
                blockMinX, blockMinZ, blockMaxX, blockMaxZ, sumElevation / cells.size(), minElevation,
                maxElevation, sumSlope / cells.size(), sumWaterDistance / cells.size(), touchesWater,
                touchesRegionEdge, confidence, flags);
    }
}
