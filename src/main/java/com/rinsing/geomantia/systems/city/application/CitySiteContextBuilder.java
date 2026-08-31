package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.model.*;

import java.util.ArrayList;
import java.util.List;

public final class CitySiteContextBuilder {

    private final CityPlanningConfig config;

    public CitySiteContextBuilder(CityPlanningConfig config) {
        this.config = config;
    }

    public CityPlanningConfig config() {
        return config;
    }

    public CitySiteContext build(
            String cityId,
            String realmId,
            String dimensionId,
            String seedId,
            String siteCandidateId,
            int anchorBlockX,
            int anchorBlockZ,
            String cityRole,
            String scaleLabel,
            int planningRadiusCells,
            int gisCellStepBlocks,
            List<TerritoryCellRef> territoryCells) {

        CityScale scale = CityScale.fromContractName(scaleLabel);
        if (scale == null) {
            throw new IllegalArgumentException("Unknown scale label: " + scaleLabel);
        }

        CityPlanningConfig.ScaleRadius scaleRadius = config.radiusFor(scale);
        int radiusBlocks = planningRadiusCells * gisCellStepBlocks;
        radiusBlocks = scaleRadius.clampRadius(radiusBlocks);

        BlockBounds bounds = computeBounds(anchorBlockX, anchorBlockZ, radiusBlocks);
        PlanningGrid grid = buildGrid(bounds, scale, gisCellStepBlocks);
        BlockPoint anchor = new BlockPoint(anchorBlockX, anchorBlockZ);
        List<EntryCandidate> entryCandidates = buildEntryCandidates(anchor, bounds, scale);
        TerritoryCheckResult territoryCheck = checkTerritory(bounds, anchor, territoryCells, gisCellStepBlocks);

        return new CitySiteContext(
                CitySiteContext.SCHEMA,
                cityId, realmId, dimensionId,
                seedId, siteCandidateId,
                bounds, grid, anchor,
                cityRole, scale, radiusBlocks,
                entryCandidates, territoryCheck);
    }

    public CitySiteContext buildWithFixedGridStep(
            String cityId,
            String realmId,
            String dimensionId,
            String seedId,
            String siteCandidateId,
            int anchorBlockX,
            int anchorBlockZ,
            String cityRole,
            String scaleLabel,
            int planningRadiusCells,
            int sourceCellStepBlocks,
            int fixedGridStepBlocks,
            List<TerritoryCellRef> territoryCells) {
        if (sourceCellStepBlocks <= 0 || fixedGridStepBlocks <= 0) {
            throw new IllegalArgumentException("Source and fixed grid steps must be positive.");
        }
        CityScale scale = CityScale.fromContractName(scaleLabel);
        if (scale == null) {
            throw new IllegalArgumentException("Unknown scale label: " + scaleLabel);
        }
        int radiusBlocks = config.radiusFor(scale).clampRadius(planningRadiusCells * sourceCellStepBlocks);
        BlockBounds bounds = computeBounds(anchorBlockX, anchorBlockZ, radiusBlocks);
        PlanningGrid grid = buildFixedGrid(bounds, fixedGridStepBlocks);
        BlockPoint anchor = new BlockPoint(anchorBlockX, anchorBlockZ);
        return new CitySiteContext(
                CitySiteContext.SCHEMA,
                cityId, realmId, dimensionId,
                seedId, siteCandidateId,
                bounds, grid, anchor,
                cityRole, scale, radiusBlocks,
                buildEntryCandidates(anchor, bounds, scale),
                checkTerritory(bounds, anchor, territoryCells, sourceCellStepBlocks));
    }

    public BlockBounds computeBounds(int anchorBlockX, int anchorBlockZ, int radiusBlocks) {
        return new BlockBounds(
                anchorBlockX - radiusBlocks,
                anchorBlockZ - radiusBlocks,
                anchorBlockX + radiusBlocks,
                anchorBlockZ + radiusBlocks);
    }

    public PlanningGrid buildGrid(BlockBounds bounds, CityScale scale, int gisCellStepBlocks) {
        CityPlanningConfig.ScaleRadius scaleRadius = config.radiusFor(scale);
        int cellStep = Math.max(config.cityCellStepMin(), gisCellStepBlocks);
        cellStep = scaleRadius.clampCellStep(cellStep);

        int cellsX = Math.max(1, bounds.widthBlocks() / cellStep);
        int cellsZ = Math.max(1, bounds.heightBlocks() / cellStep);

        return new PlanningGrid(bounds.minX(), bounds.minZ(), cellStep, cellsX, cellsZ);
    }

    public PlanningGrid buildFixedGrid(BlockBounds bounds, int cellStepBlocks) {
        if (cellStepBlocks <= 0) {
            throw new IllegalArgumentException("cellStepBlocks must be positive.");
        }
        return new PlanningGrid(bounds.minX(), bounds.minZ(), cellStepBlocks,
                Math.max(1, bounds.widthBlocks() / cellStepBlocks),
                Math.max(1, bounds.heightBlocks() / cellStepBlocks));
    }

    public List<EntryCandidate> buildEntryCandidates(BlockPoint anchor, BlockBounds bounds, CityScale scale) {
        List<EntryCandidate> candidates = new ArrayList<>();
        candidates.add(new EntryCandidate("main_gate", anchor, "center", "城市锚点主入口"));

        int midX = (bounds.minX() + bounds.maxX()) / 2;
        int midZ = (bounds.minZ() + bounds.maxZ()) / 2;

        addDirectionalGate(candidates, bounds, midX, bounds.minZ(), "N", "北门");
        addDirectionalGate(candidates, bounds, midX, bounds.maxZ(), "S", "南门");
        addDirectionalGate(candidates, bounds, bounds.minX(), midZ, "W", "西门");
        addDirectionalGate(candidates, bounds, bounds.maxX(), midZ, "E", "东门");

        if (scale == CityScale.TOWN || scale == CityScale.CITY) {
            addDirectionalGate(candidates, bounds, bounds.minX(), bounds.minZ(), "NW", "西北门");
            addDirectionalGate(candidates, bounds, bounds.maxX(), bounds.minZ(), "NE", "东北门");
            addDirectionalGate(candidates, bounds, bounds.minX(), bounds.maxZ(), "SW", "西南门");
            addDirectionalGate(candidates, bounds, bounds.maxX(), bounds.maxZ(), "SE", "东南门");
        }

        return candidates;
    }

    private void addDirectionalGate(List<EntryCandidate> candidates, BlockBounds bounds,
                                     int x, int z, String dir, String desc) {
        String dirName = config.entryDirectionNames().getOrDefault(dir, dir);
        candidates.add(new EntryCandidate("gate_" + dir.toLowerCase(), new BlockPoint(x, z), dir, dirName + "入口"));
    }

    public TerritoryCheckResult checkTerritory(BlockBounds bounds, BlockPoint anchor,
                                             List<TerritoryCellRef> territoryCells,
                                             int cellStepBlocks) {
        if (territoryCells == null || territoryCells.isEmpty()) {
            return TerritoryCheckResult.UNKNOWN;
        }

        boolean anchorInside = false;
        for (TerritoryCellRef tc : territoryCells) {
            int cellMinX = tc.gridX() * cellStepBlocks;
            int cellMinZ = tc.gridZ() * cellStepBlocks;
            int cellMaxX = cellMinX + cellStepBlocks - 1;
            int cellMaxZ = cellMinZ + cellStepBlocks - 1;
            if (anchor.x() >= cellMinX && anchor.x() <= cellMaxX
                    && anchor.z() >= cellMinZ && anchor.z() <= cellMaxZ) {
                anchorInside = true;
                break;
            }
        }

        if (!anchorInside) {
            return TerritoryCheckResult.OUTSIDE;
        }

        // Check how much of the perimeter falls outside territory
        int perimeterBlocks = 2 * bounds.widthBlocks() + 2 * bounds.heightBlocks();
        int outsideCount = 0;
        int step = Math.max(1, cellStepBlocks);

        // Sample perimeter blocks: top and bottom edges
        for (int x = bounds.minX(); x <= bounds.maxX(); x += step) {
            if (!isInTerritory(x, bounds.minZ(), territoryCells, cellStepBlocks)) outsideCount++;
            if (!isInTerritory(x, bounds.maxZ(), territoryCells, cellStepBlocks)) outsideCount++;
        }
        // Left and right edges
        for (int z = bounds.minZ() + step; z <= bounds.maxZ() - step; z += step) {
            if (!isInTerritory(bounds.minX(), z, territoryCells, cellStepBlocks)) outsideCount++;
            if (!isInTerritory(bounds.maxX(), z, territoryCells, cellStepBlocks)) outsideCount++;
        }

        int sampledPerimeter = 2 * (bounds.widthBlocks() / step + bounds.heightBlocks() / step);
        if (sampledPerimeter > 0 && (double) outsideCount / sampledPerimeter > config.territoryBorderRatio()) {
            return TerritoryCheckResult.BORDER;
        }

        return TerritoryCheckResult.INSIDE;
    }

    private boolean isInTerritory(int blockX, int blockZ,
                                   List<TerritoryCellRef> territoryCells,
                                   int cellStepBlocks) {
        for (TerritoryCellRef tc : territoryCells) {
            int cellMinX = tc.gridX() * cellStepBlocks;
            int cellMinZ = tc.gridZ() * cellStepBlocks;
            int cellMaxX = cellMinX + cellStepBlocks - 1;
            int cellMaxZ = cellMinZ + cellStepBlocks - 1;
            if (blockX >= cellMinX && blockX <= cellMaxX
                    && blockZ >= cellMinZ && blockZ <= cellMaxZ) {
                return true;
            }
        }
        return false;
    }

    public record TerritoryCellRef(int gridX, int gridZ) {
    }
}
