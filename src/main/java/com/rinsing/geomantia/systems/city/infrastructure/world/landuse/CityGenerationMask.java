package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntBinaryOperator;

/** Immutable chunk-indexed construction columns. Ground is sampled only for authored non-graded areas. */
public final class CityGenerationMask {
    private static final int NONE = Integer.MAX_VALUE;
    private static final int TERRAIN = Integer.MIN_VALUE;
    private final Map<String, Map<Long, int[]>> dimensions;
    private final Map<String, Map<Long, Integer>> ground = new ConcurrentHashMap<>();

    private CityGenerationMask(Map<String, Map<Long, int[]>> dimensions) { this.dimensions = dimensions; }
    public static CityGenerationMask empty() { return new Builder().build(); }
    private static long key(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }

    public boolean protects(String dimension, int x, int y, int z, IntBinaryOperator terrainFloor) {
        int[] chunk = dimensions.getOrDefault(dimension, Map.of()).get(key(x >> 4, z >> 4));
        if (chunk == null) return false;
        int floor = chunk[((z & 15) << 4) | (x & 15)];
        if (floor == NONE) return false;
        if (floor == TERRAIN) floor = ground.computeIfAbsent(dimension, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(key(x, z), ignored -> terrainFloor.applyAsInt(x, z) - 1);
        return y >= floor;
    }

    public boolean intersects(String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                              IntBinaryOperator terrainFloor) {
        // Visit only existing protection chunks; a giant external bbox must not allocate or scan empty world space.
        for (var entry : dimensions.getOrDefault(dimension, Map.of()).entrySet()) {
            int cx = (int)(entry.getKey() >> 32), cz = (int)(long)entry.getKey();
            if (cx < (minX >> 4) || cx > (maxX >> 4) || cz < (minZ >> 4) || cz > (maxZ >> 4)) continue;
            for (int z = Math.max(minZ, cz * 16); z <= Math.min(maxZ, cz * 16 + 15); z++)
                for (int x = Math.max(minX, cx * 16); x <= Math.min(maxX, cx * 16 + 15); x++)
                    if (protects(dimension, x, maxY, z, terrainFloor)) return true;
        }
        return false;
    }

    public static final class Builder {
        private final Map<String, Map<Long, int[]>> dimensions = new HashMap<>();
        public void terrain(String dimension, int x, int z) { add(dimension, x, z, TERRAIN); }
        public void surface(String dimension, int x, int z, int y) { add(dimension, x, z, y - 1); }
        private void add(String dimension, int x, int z, int floor) {
            int[] cells = dimensions.computeIfAbsent(dimension, ignored -> new HashMap<>())
                    .computeIfAbsent(key(x >> 4, z >> 4), ignored -> { int[] a = new int[256]; Arrays.fill(a, NONE); return a; });
            int index = ((z & 15) << 4) | (x & 15), old = cells[index];
            // Exact engineering geometry takes precedence over inferred terrain. Overlapping exact areas use the lower floor.
            if (old == NONE || old == TERRAIN || floor != TERRAIN && floor < old) cells[index] = floor;
        }
        public CityGenerationMask build() {
            Map<String, Map<Long, int[]>> copy = new HashMap<>();
            dimensions.forEach((dimension, chunks) -> {
                Map<Long, int[]> copied = new HashMap<>(); chunks.forEach((key, cells) -> copied.put(key, cells.clone()));
                copy.put(dimension, Map.copyOf(copied));
            });
            return new CityGenerationMask(Map.copyOf(copy));
        }
    }
}
