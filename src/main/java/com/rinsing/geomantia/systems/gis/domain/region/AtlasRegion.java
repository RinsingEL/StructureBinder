package com.rinsing.geomantia.systems.gis.domain.region;

import com.rinsing.geomantia.systems.gis.GisAtlasConstants;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.domain.cell.AtlasCell;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class AtlasRegion {
    private final String regionId;
    private final String dimensionId;
    private final int regionX;
    private final int regionZ;
    private final int blockMinX;
    private final int blockMinZ;
    private final int sizeChunks;
    private final int cellStepBlocks;
    private final int cellsPerSide;
    private final int atlasVersion;
    private final String configVersion;
    private final AtlasCell[][] cells;
    private final List<LandformPatch> patches = new ArrayList<>();
    private RegionStatus status = RegionStatus.EMPTY;
    private long updatedAt;

    public AtlasRegion(String dimensionId, int regionX, int regionZ, GisSampleConfig config) {
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.sizeChunks = config.regionSizeChunks();
        this.cellStepBlocks = config.cellStepBlocks();
        this.cellsPerSide = config.cellsPerRegionSide();
        this.blockMinX = regionX * config.regionSizeBlocks();
        this.blockMinZ = regionZ * config.regionSizeBlocks();
        this.regionId = RegionKey.regionId(dimensionId, regionX, regionZ);
        this.atlasVersion = GisAtlasConstants.ATLAS_VERSION;
        this.configVersion = GisAtlasConstants.CONFIG_VERSION;
        this.cells = new AtlasCell[cellsPerSide][cellsPerSide];
        for (int x = 0; x < cellsPerSide; x++) {
            for (int z = 0; z < cellsPerSide; z++) {
                int globalCellX = Math.floorDiv(blockMinX + x * cellStepBlocks, cellStepBlocks);
                int globalCellZ = Math.floorDiv(blockMinZ + z * cellStepBlocks, cellStepBlocks);
                cells[x][z] = new AtlasCell(regionId, globalCellX, globalCellZ, x, z,
                        blockMinX + x * cellStepBlocks, blockMinZ + z * cellStepBlocks);
            }
        }
        touch();
    }

    public String regionId() {
        return regionId;
    }

    public String dimensionId() {
        return dimensionId;
    }

    public int regionX() {
        return regionX;
    }

    public int regionZ() {
        return regionZ;
    }

    public int blockMinX() {
        return blockMinX;
    }

    public int blockMinZ() {
        return blockMinZ;
    }

    public int sizeChunks() {
        return sizeChunks;
    }

    public int cellStepBlocks() {
        return cellStepBlocks;
    }

    public int cellsPerSide() {
        return cellsPerSide;
    }

    public int atlasVersion() {
        return atlasVersion;
    }

    public String configVersion() {
        return configVersion;
    }

    public RegionStatus status() {
        return status;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public void setStatus(RegionStatus status) {
        this.status = Objects.requireNonNull(status, "status");
        touch();
    }

    public AtlasCell cell(int localCellX, int localCellZ) {
        if (!containsLocal(localCellX, localCellZ)) {
            return null;
        }
        return cells[localCellX][localCellZ];
    }

    public boolean containsLocal(int localCellX, int localCellZ) {
        return localCellX >= 0 && localCellZ >= 0 && localCellX < cellsPerSide && localCellZ < cellsPerSide;
    }

    public List<AtlasCell> cells() {
        List<AtlasCell> result = new ArrayList<>(cellsPerSide * cellsPerSide);
        for (int z = 0; z < cellsPerSide; z++) {
            for (int x = 0; x < cellsPerSide; x++) {
                result.add(cells[x][z]);
            }
        }
        return result;
    }

    public List<LandformPatch> patches() {
        return Collections.unmodifiableList(patches);
    }

    public void replacePatches(List<LandformPatch> patches) {
        this.patches.clear();
        this.patches.addAll(patches);
        touch();
    }

    public void touch() {
        this.updatedAt = System.currentTimeMillis();
    }
}
