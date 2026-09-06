package com.rinsing.geomantia.systems.city.algorithm.landuse;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Set;

/** Exact cut-vertex queries for the planar four-neighbor grid, with rollback.
 * Faces join when an edge disappears. For degree d > 0, deleting a vertex
 * increases connected components by d - r, where r is its distinct incident
 * faces (Euler's formula). This replaces repeated traversals of the remaining
 * cells with four union-find lookups. Coordinates and growth order are unchanged.
 */
final class GridRemovalConnectivity {
    // Memory threshold only: sparse/huge bounding boxes use the exact graph-search
    // implementation instead. No mask is truncated and no design is rejected.
    private static final long MAX_FACES = 4_000_000;
    private final long minX, minZ;
    private final int width;
    private final int[] parent, size;
    private final IntArrayList history = new IntArrayList();

    static GridRemovalConnectivity create(Set<Long> cells) {
        if (cells.isEmpty()) return null;
        long minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        long minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (long cell : cells) {
            int x = (int) (cell >> 32), z = (int) cell;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        long width = maxX - minX + 2, height = maxZ - minZ + 2;
        if (width > MAX_FACES || height > MAX_FACES || width * height > MAX_FACES) return null;
        return new GridRemovalConnectivity(cells, minX - 1, minZ - 1, (int) width, (int) height);
    }

    private GridRemovalConnectivity(Set<Long> cells, long minX, long minZ, int width, int height) {
        this.minX = minX; this.minZ = minZ; this.width = width;
        int length = width * height + 1; // zero is the exterior face
        parent = new int[length]; size = new int[length];
        for (int index = 0; index < length; index++) { parent[index] = index; size[index] = 1; }
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int face = 1 + row * width + column;
                long x = minX + column, z = minZ + row;
                if (row == 0 || column == 0 || row == height - 1 || column == width - 1) union(face, 0, false);
                if (column + 1 < width && !(contains(cells, x + 1, z) && contains(cells, x + 1, z + 1))) {
                    union(face, face + 1, false);
                }
                if (row + 1 < height && !(contains(cells, x, z + 1) && contains(cells, x + 1, z + 1))) {
                    union(face, face + width, false);
                }
            }
        }
    }

    boolean canRemove(Set<Long> cells, long point) {
        long x = (int) (point >> 32), z = (int) point;
        int degree = (contains(cells, x - 1, z) ? 1 : 0) + (contains(cells, x + 1, z) ? 1 : 0)
                + (contains(cells, x, z - 1) ? 1 : 0) + (contains(cells, x, z + 1) ? 1 : 0);
        if (degree <= 1) return true;
        int first = face(x - 1, z - 1);
        int a = root(first), b = root(first + 1), c = root(first + width), d = root(first + width + 1);
        int distinct = 1 + (b != a ? 1 : 0) + (c != a && c != b ? 1 : 0)
                + (d != a && d != b && d != c ? 1 : 0);
        return distinct == degree;
    }

    int checkpoint() { return history.size(); }

    void remove(long point) {
        int first = face((long) (int) (point >> 32) - 1, (long) (int) point - 1);
        union(first, first + 1, true);
        union(first, first + width, true);
        union(first, first + width + 1, true);
    }

    void rollback(int checkpoint) {
        while (history.size() > checkpoint) {
            int child = history.removeInt(history.size() - 1);
            size[parent[child]] -= size[child];
            parent[child] = child;
        }
    }

    private int face(long x, long z) { return 1 + (int) (z - minZ) * width + (int) (x - minX); }
    private int root(int node) {
        // No path compression: union-by-size gives logarithmic lookup and exact undo.
        while (parent[node] != node) node = parent[node];
        return node;
    }
    private void union(int first, int second, boolean record) {
        int a = root(first), b = root(second);
        if (a == b) return;
        if (size[a] < size[b]) { int swap = a; a = b; b = swap; }
        if (record) history.add(b);
        parent[b] = a; size[a] += size[b];
    }
    private static boolean contains(Set<Long> cells, long x, long z) {
        return x >= Integer.MIN_VALUE && x <= Integer.MAX_VALUE && z >= Integer.MIN_VALUE && z <= Integer.MAX_VALUE
                && cells.contains((x << 32) ^ (z & 0xffff_ffffL));
    }
}
