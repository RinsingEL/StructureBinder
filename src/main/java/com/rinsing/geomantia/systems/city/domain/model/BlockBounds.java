package com.rinsing.geomantia.systems.city.domain.model;

public record BlockBounds(int minX, int minZ, int maxX, int maxZ) {
    public BlockBounds {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException(
                    "min must be <= max: (" + minX + "," + minZ + ") to (" + maxX + "," + maxZ + ")");
        }
    }

    public int widthBlocks() {
        return maxX - minX + 1;
    }

    public int heightBlocks() {
        return maxZ - minZ + 1;
    }

    public boolean contains(int blockX, int blockZ) {
        return blockX >= minX && blockX <= maxX && blockZ >= minZ && blockZ <= maxZ;
    }

    public boolean overlaps(BlockBounds other) {
        return minX <= other.maxX && maxX >= other.minX
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    public BlockPoint center() {
        return new BlockPoint((minX + maxX) / 2, (minZ + maxZ) / 2);
    }
}
