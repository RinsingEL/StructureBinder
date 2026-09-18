package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.*;

/** Packs authored rectangles edge-to-edge; the frozen plan is independent of terrain survival. */
final class CityContiguousLayoutPlanner {
    record Size(int width, int depth) {
        Size { if (width < 1 || depth < 1) throw new IllegalArgumentException("Positive footprint required"); }
    }

    static List<BlockPoint> plan(List<Size> sizes, long seed) {
        List<BlockBounds> placed = new ArrayList<>();
        List<BlockPoint> origins = new ArrayList<>();
        Map<Long, List<BlockBounds>> buckets = new HashMap<>();
        double aspect = 1.15 + Math.floorMod(seed, 7) * 0.05;
        for (Size size : sizes) {
            Set<BlockPoint> candidates = new LinkedHashSet<>();
            if (placed.isEmpty()) candidates.add(new BlockPoint(0, 0));
            for (BlockBounds b : placed) {
                for (int z : new int[]{b.minZ(), b.maxZ() - size.depth() + 1}) {
                    candidates.add(new BlockPoint(b.maxX() + 1, z));
                    candidates.add(new BlockPoint(b.minX() - size.width(), z));
                }
                for (int x : new int[]{b.minX(), b.maxX() - size.width() + 1}) {
                    candidates.add(new BlockPoint(x, b.maxZ() + 1));
                    candidates.add(new BlockPoint(x, b.minZ() - size.depth()));
                }
            }
            BlockBounds best = null;
            double bestScore = Double.POSITIVE_INFINITY;
            for (BlockPoint p : candidates) {
                BlockBounds b = new BlockBounds(p.x(), p.z(), p.x() + size.width() - 1, p.z() + size.depth() - 1);
                Set<BlockBounds> nearby = nearby(buckets, b);
                if (nearby.stream().anyMatch(b::overlaps)) continue;
                int contact = nearby.stream().mapToInt(other -> contact(b, other)).sum();
                if (!placed.isEmpty() && contact == 0) continue;
                double x = (b.minX() + b.maxX()) / 2.0;
                double z = (b.minZ() + b.maxZ()) / 2.0;
                double angle = Math.atan2(z, x);
                // Broad lobes change the outer contour without jittering individual tiles or opening gaps.
                double contour = 1.0 + 0.10 * Math.sin(3 * angle + Math.floorMod(seed, 19));
                double score = (x * x / aspect + z * z * aspect) / contour - contact * 2.0;
                if (score < bestScore) { best = b; bestScore = score; }
            }
            if (best == null) throw new IllegalStateException("CONTIGUOUS_FRONTIER_EXHAUSTED");
            placed.add(best);
            origins.add(new BlockPoint(best.minX(), best.minZ()));
            for (int x = Math.floorDiv(best.minX(), 32); x <= Math.floorDiv(best.maxX(), 32); x++)
                for (int z = Math.floorDiv(best.minZ(), 32); z <= Math.floorDiv(best.maxZ(), 32); z++)
                    buckets.computeIfAbsent(key(x, z), ignored -> new ArrayList<>()).add(best);
        }
        return List.copyOf(origins);
    }

    static int contact(BlockBounds a, BlockBounds b) {
        if (a.maxX() + 1 == b.minX() || b.maxX() + 1 == a.minX())
            return Math.max(0, Math.min(a.maxZ(), b.maxZ()) - Math.max(a.minZ(), b.minZ()) + 1);
        if (a.maxZ() + 1 == b.minZ() || b.maxZ() + 1 == a.minZ())
            return Math.max(0, Math.min(a.maxX(), b.maxX()) - Math.max(a.minX(), b.minX()) + 1);
        return 0;
    }

    private static Set<BlockBounds> nearby(Map<Long, List<BlockBounds>> buckets, BlockBounds b) {
        Set<BlockBounds> result = new HashSet<>();
        for (int x = Math.floorDiv(b.minX() - 1, 32); x <= Math.floorDiv(b.maxX() + 1, 32); x++)
            for (int z = Math.floorDiv(b.minZ() - 1, 32); z <= Math.floorDiv(b.maxZ() + 1, 32); z++)
                result.addAll(buckets.getOrDefault(key(x, z), List.of()));
        return result;
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
