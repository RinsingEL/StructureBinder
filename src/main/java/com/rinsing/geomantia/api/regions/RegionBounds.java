package com.rinsing.geomantia.api.regions;

/** Inclusive block coordinates, independent of Minecraft chunks and planning grid resolution. */
public record RegionBounds(int minX, int minZ, int maxX, int maxZ) {
    public RegionBounds {
        if (minX > maxX || minZ > maxZ || minX < -30_000_000 || minZ < -30_000_000
                || maxX > 30_000_000 || maxZ > 30_000_000)
            throw new IllegalArgumentException("REGION_BOUNDS_INVALID");
    }

    public boolean overlaps(RegionBounds other) {
        return minX <= other.maxX && maxX >= other.minX && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
}
