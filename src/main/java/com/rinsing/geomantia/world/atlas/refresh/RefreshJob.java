package com.rinsing.geomantia.world.atlas.refresh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class RefreshJob {
    private final String jobId;
    private final String dimensionId;
    private final int centerBlockX;
    private final int centerBlockZ;
    private final int radiusChunks;
    private final int dependencyMarginCells;
    private final int cellStepBlocks;
    private final SampleMode sampleMode;
    private final RefreshPriority priority;
    private final int budgetCellsPerBatch;
    private final long startedAt;
    private final List<String> dirtyRegions = new ArrayList<>();
    private RefreshStatus status = RefreshStatus.QUEUED;
    private int completedCells;
    private int totalCells;
    private int currentRing;
    private long finishedAt;
    private String errorMessage = "";

    public RefreshJob(String jobId, String dimensionId, int centerBlockX, int centerBlockZ, int radiusChunks,
            int dependencyMarginCells, int cellStepBlocks, SampleMode sampleMode, RefreshPriority priority,
            int budgetCellsPerBatch) {
        this.jobId = Objects.requireNonNull(jobId, "jobId");
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        this.centerBlockX = centerBlockX;
        this.centerBlockZ = centerBlockZ;
        this.radiusChunks = radiusChunks;
        this.dependencyMarginCells = dependencyMarginCells;
        this.cellStepBlocks = cellStepBlocks;
        this.sampleMode = Objects.requireNonNull(sampleMode, "sampleMode");
        this.priority = Objects.requireNonNull(priority, "priority");
        this.budgetCellsPerBatch = budgetCellsPerBatch;
        this.startedAt = System.currentTimeMillis();
    }

    public String jobId() {
        return jobId;
    }

    public String dimensionId() {
        return dimensionId;
    }

    public int centerBlockX() {
        return centerBlockX;
    }

    public int centerBlockZ() {
        return centerBlockZ;
    }

    public int radiusChunks() {
        return radiusChunks;
    }

    public int dependencyMarginCells() {
        return dependencyMarginCells;
    }

    public int cellStepBlocks() {
        return cellStepBlocks;
    }

    public SampleMode sampleMode() {
        return sampleMode;
    }

    public RefreshPriority priority() {
        return priority;
    }

    public int budgetCellsPerBatch() {
        return budgetCellsPerBatch;
    }

    public RefreshStatus status() {
        return status;
    }

    public int completedCells() {
        return completedCells;
    }

    public int totalCells() {
        return totalCells;
    }

    public int currentRing() {
        return currentRing;
    }

    public List<String> dirtyRegions() {
        return Collections.unmodifiableList(dirtyRegions);
    }

    public long startedAt() {
        return startedAt;
    }

    public long finishedAt() {
        return finishedAt;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public void setStatus(RefreshStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    public void setTotalCells(int totalCells) {
        this.totalCells = totalCells;
    }

    public void setCompletedCells(int completedCells) {
        this.completedCells = completedCells;
    }

    public void setCurrentRing(int currentRing) {
        this.currentRing = currentRing;
    }

    public void addDirtyRegion(String regionId) {
        if (!dirtyRegions.contains(regionId)) {
            dirtyRegions.add(regionId);
        }
    }

    public void complete() {
        status = RefreshStatus.COMPLETED;
        finishedAt = System.currentTimeMillis();
    }

    public void fail(String errorMessage) {
        status = RefreshStatus.FAILED;
        this.errorMessage = errorMessage == null ? "" : errorMessage;
        finishedAt = System.currentTimeMillis();
    }
}
