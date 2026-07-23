package com.rinsing.geomantia.systems.city.domain.model;

import com.google.gson.JsonObject;

public record PlanningGrid(
        int originBlockX,
        int originBlockZ,
        int cellStepBlocks,
        int cellsX,
        int cellsZ) {

    public PlanningGrid {
        if (cellStepBlocks <= 0) throw new IllegalArgumentException("cellStepBlocks must be positive");
        if (cellsX <= 0) throw new IllegalArgumentException("cellsX must be positive");
        if (cellsZ <= 0) throw new IllegalArgumentException("cellsZ must be positive");
    }

    public int blockMinX() {
        return originBlockX;
    }

    public int blockMinZ() {
        return originBlockZ;
    }

    public int blockMaxX() {
        return originBlockX + cellsX * cellStepBlocks;
    }

    public int blockMaxZ() {
        return originBlockZ + cellsZ * cellStepBlocks;
    }

    public int widthBlocks() {
        return cellsX * cellStepBlocks;
    }

    public int heightBlocks() {
        return cellsZ * cellStepBlocks;
    }

    public boolean containsBlock(int blockX, int blockZ) {
        return blockX >= originBlockX && blockX < blockMaxX()
                && blockZ >= originBlockZ && blockZ < blockMaxZ();
    }

    public int cellToBlockX(int cellX) {
        return originBlockX + cellX * cellStepBlocks;
    }

    public int cellToBlockZ(int cellZ) {
        return originBlockZ + cellZ * cellStepBlocks;
    }

    public int blockToCellX(int blockX) {
        return (blockX - originBlockX) / cellStepBlocks;
    }

    public int blockToCellZ(int blockZ) {
        return (blockZ - originBlockZ) / cellStepBlocks;
    }

    public JsonObject asJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("originBlockX", originBlockX);
        obj.addProperty("originBlockZ", originBlockZ);
        obj.addProperty("cellStepBlocks", cellStepBlocks);
        obj.addProperty("cellsX", cellsX);
        obj.addProperty("cellsZ", cellsZ);
        JsonObject bounds = new JsonObject();
        bounds.addProperty("minX", blockMinX());
        bounds.addProperty("minZ", blockMinZ());
        bounds.addProperty("maxX", blockMaxX());
        bounds.addProperty("maxZ", blockMaxZ());
        obj.add("blockBounds", bounds);
        return obj;
    }
}
