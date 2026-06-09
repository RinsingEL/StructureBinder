package com.rinsing.geomantia.world.atlas.landform;

import com.rinsing.geomantia.world.atlas.GisClassifierConfig;
import com.rinsing.geomantia.world.atlas.cell.AtlasCell;
import com.rinsing.geomantia.world.atlas.cell.CellStateFlag;
import com.rinsing.geomantia.world.atlas.cell.LandformType;
import com.rinsing.geomantia.world.atlas.region.AtlasRegion;
import com.rinsing.geomantia.world.atlas.region.RegionStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

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
                cells.forEach(cell -> cell.addFlag(CellStateFlag.PATCH_READY));
            }
        }
        region.replacePatches(patches);
        region.setStatus(RegionStatus.READY);
        return patches;
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
