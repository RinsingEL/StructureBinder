package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.ArrayList;
import java.util.List;

/** Small staggered groups along a shared meandering spine; independent of terrain slope. */
final class CityMeanderingClusterLayout {
    private final List<BlockPoint> origins = new ArrayList<>();
    private final List<BlockPoint> frontages = new ArrayList<>();
    private final List<Integer> clusters = new ArrayList<>();
    private final List<BlockPoint> road = new ArrayList<>();
    private final int width;
    private final int depth;

    CityMeanderingClusterLayout(List<Integer> spans, int targetGap, int roadWidth, long seed) {
        int gap = Math.max(targetGap, roadWidth + 5);
        int cursor = 0, cluster = 0, previousRight = 0, previousSpan = 0;
        int mirror = (seed & 1) == 0 ? 1 : -1;
        while (cursor < spans.size()) {
            int count = Math.min(4 + Math.floorMod((int) (seed ^ (cluster * 31L)), 2), spans.size() - cursor);
            if (count < 5 && spans.size() - cursor - count == 1) count++;
            int span = spans.subList(cursor, cursor + count).stream().mapToInt(Integer::intValue).max().orElseThrow();
            int cx = cluster == 0 ? 0 : previousRight + Math.max(span, previousSpan) / 2 + 2 * gap + span;
            int cz = mirror * (int) Math.round(Math.sin(cluster * 1.25) * (span + gap) * 2);
            int left = cx - span - gap, right = cx + span + 2 * gap;
            BlockPoint start = new BlockPoint(left, cz);
            if (!road.isEmpty()) {
                BlockPoint from = road.get(road.size() - 1);
                for (int step = 1; step <= 8; step++) {
                    double t = step / 8.0, bend = t * t * (3 - 2 * t);
                    int x = (int) Math.round(from.x() + (start.x() - from.x()) * t);
                    int z = (int) Math.round(from.z() + (start.z() - from.z()) * bend);
                    appendRoad(new BlockPoint(x, road.get(road.size() - 1).z()));
                    appendRoad(new BlockPoint(x, z));
                }
            } else appendRoad(start);
            appendRoad(new BlockPoint(right, cz));
            for (int member = 0; member < count; member++) {
                int memberSpan = spans.get(cursor + member);
                int jitter = Math.floorMod((int) (seed + cluster * 17L + member * 7L), 3) - 1;
                int x = switch (member) {
                    case 0 -> -memberSpan - gap / 2;
                    case 1 -> -memberSpan - gap / 2 + 2;
                    case 2 -> gap / 2;
                    case 3 -> gap / 2 + 3;
                    default -> gap / 2;
                };
                int z = switch (member) {
                    case 0, 2 -> -memberSpan - gap;
                    case 1 -> gap;
                    case 3 -> gap + memberSpan / 3;
                    default -> -spans.get(cursor + 2) - memberSpan - 2 * gap;
                };
                origins.add(new BlockPoint(cx + x + jitter, cz + z));
                frontages.add(new BlockPoint(cx + x + jitter, cz));
                clusters.add(cluster);
            }
            cursor += count;
            previousRight = right;
            previousSpan = span;
            cluster++;
        }
        int minX = Math.min(road.get(0).x(), origins.stream().mapToInt(BlockPoint::x).min().orElseThrow());
        int maxX = road.get(road.size() - 1).x();
        int minZ = origins.stream().mapToInt(BlockPoint::z).min().orElseThrow(), maxZ = minZ;
        for (int i = 0; i < origins.size(); i++) {
            maxX = Math.max(maxX, origins.get(i).x() + spans.get(i));
            maxZ = Math.max(maxZ, origins.get(i).z() + spans.get(i));
        }
        width = maxX - minX + roadWidth + 4;
        depth = maxZ - minZ + roadWidth + 4;
        int dx = Math.floorDiv(minX + maxX, 2), dz = Math.floorDiv(minZ + maxZ, 2);
        shift(origins, dx, dz); shift(frontages, dx, dz); shift(road, dx, dz);
    }

    private void appendRoad(BlockPoint point) {
        if (road.isEmpty() || !road.get(road.size() - 1).equals(point)) road.add(point);
    }

    private static void shift(List<BlockPoint> points, int dx, int dz) {
        points.replaceAll(p -> new BlockPoint(p.x() - dx, p.z() - dz));
    }

    private static BlockPoint translate(BlockPoint point, BlockPoint center) {
        return new BlockPoint(point.x() + center.x(), point.z() + center.z());
    }

    BlockPoint origin(BlockPoint center, int slot) { return translate(origins.get(slot), center); }
    BlockPoint frontage(BlockPoint center, int slot) { return translate(frontages.get(slot), center); }
    int cluster(int slot) { return clusters.get(slot); }
    int width() { return width; }
    int depth() { return depth; }

    JsonArray roadJson(BlockPoint center) {
        JsonArray array = new JsonArray();
        road.forEach(p -> array.add(translate(p, center).asJson()));
        return array;
    }
}
