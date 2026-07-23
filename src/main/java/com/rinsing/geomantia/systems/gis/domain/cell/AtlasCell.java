package com.rinsing.geomantia.systems.gis.domain.cell;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public final class AtlasCell {
    private final String regionId;
    private final int globalCellX;
    private final int globalCellZ;
    private final int localCellX;
    private final int localCellZ;
    private final int blockMinX;
    private final int blockMinZ;
    private SampleSource sampleSource = SampleSource.PRIOR;
    private double elevation;
    private SurfaceType surfaceType = SurfaceType.UNKNOWN;
    private String biomeId = "unknown";
    private boolean water;
    private double waterDepth;
    private double slope;
    private double localRelief;
    private double roughness;
    private double tpiSmall;
    private double tpiLarge;
    private double waterDistance = Double.POSITIVE_INFINITY;
    private LandformType landformType = LandformType.UNKNOWN;
    private String patchId = "";
    private final EnumSet<CellStateFlag> stateFlags = EnumSet.noneOf(CellStateFlag.class);

    public AtlasCell(String regionId, int globalCellX, int globalCellZ, int localCellX, int localCellZ,
            int blockMinX, int blockMinZ) {
        this.regionId = Objects.requireNonNull(regionId, "regionId");
        this.globalCellX = globalCellX;
        this.globalCellZ = globalCellZ;
        this.localCellX = localCellX;
        this.localCellZ = localCellZ;
        this.blockMinX = blockMinX;
        this.blockMinZ = blockMinZ;
    }

    public String regionId() {
        return regionId;
    }

    public int globalCellX() {
        return globalCellX;
    }

    public int globalCellZ() {
        return globalCellZ;
    }

    public int localCellX() {
        return localCellX;
    }

    public int localCellZ() {
        return localCellZ;
    }

    public int blockMinX() {
        return blockMinX;
    }

    public int blockMinZ() {
        return blockMinZ;
    }

    public SampleSource sampleSource() {
        return sampleSource;
    }

    public double elevation() {
        return elevation;
    }

    public SurfaceType surfaceType() {
        return surfaceType;
    }

    public String biomeId() {
        return biomeId;
    }

    public boolean isWater() {
        return water;
    }

    public double waterDepth() {
        return waterDepth;
    }

    public double slope() {
        return slope;
    }

    public double localRelief() {
        return localRelief;
    }

    public double roughness() {
        return roughness;
    }

    public double tpiSmall() {
        return tpiSmall;
    }

    public double tpiLarge() {
        return tpiLarge;
    }

    public double waterDistance() {
        return waterDistance;
    }

    public LandformType landformType() {
        return landformType;
    }

    public String patchId() {
        return patchId;
    }

    public Set<CellStateFlag> stateFlags() {
        return EnumSet.copyOf(stateFlags);
    }

    public boolean hasFlag(CellStateFlag flag) {
        return stateFlags.contains(flag);
    }

    public void addFlag(CellStateFlag flag) {
        stateFlags.add(flag);
    }

    public void removeFlag(CellStateFlag flag) {
        stateFlags.remove(flag);
    }

    public void setSample(SampleSource sampleSource, double elevation, SurfaceType surfaceType, String biomeId,
            boolean water, double waterDepth) {
        this.sampleSource = Objects.requireNonNull(sampleSource, "sampleSource");
        this.elevation = elevation;
        this.surfaceType = Objects.requireNonNull(surfaceType, "surfaceType");
        this.biomeId = biomeId == null || biomeId.isBlank() ? "unknown" : biomeId;
        this.water = water;
        this.waterDepth = Math.max(0.0, waterDepth);
        addFlag(CellStateFlag.SAMPLED);
    }

    public void setSmallMetrics(double slope, double localRelief, double roughness, double tpiSmall) {
        this.slope = slope;
        this.localRelief = localRelief;
        this.roughness = roughness;
        this.tpiSmall = tpiSmall;
        addFlag(CellStateFlag.METRICS_READY_SMALL);
    }

    public void setLargeMetrics(double tpiLarge, double waterDistance) {
        this.tpiLarge = tpiLarge;
        this.waterDistance = waterDistance;
        addFlag(CellStateFlag.METRICS_READY_LARGE);
    }

    public void setLandformType(LandformType landformType) {
        this.landformType = Objects.requireNonNull(landformType, "landformType");
        this.patchId = "";
        removeFlag(CellStateFlag.PATCH_READY);
        addFlag(CellStateFlag.LANDFORM_READY);
    }

    public void setPatchId(String patchId) {
        this.patchId = patchId == null ? "" : patchId;
        if (this.patchId.isBlank()) {
            removeFlag(CellStateFlag.PATCH_READY);
        } else {
            addFlag(CellStateFlag.PATCH_READY);
        }
    }
}
