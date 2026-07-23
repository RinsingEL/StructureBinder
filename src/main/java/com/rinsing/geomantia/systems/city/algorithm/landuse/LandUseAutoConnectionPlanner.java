package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LandUseAutoConnectionPlanner {
    public static final int DEFAULT_MAX_GAP_BLOCKS = 64;
    private static final int[][] DIRECTIONS = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}};

    public Plan plan(List<LandUseSeedGroup> groups, LandUseExpansionResult probe) {
        return plan(groups, probe, DEFAULT_MAX_GAP_BLOCKS);
    }

    public Plan plan(List<LandUseSeedGroup> groups,
                     LandUseExpansionResult probe,
                     int maxGapBlocks) {
        if (maxGapBlocks < 0) throw new IllegalArgumentException("maxGapBlocks must not be negative");
        Map<String, String> surfaceKeys = surfaceKeys(groups);
        List<BoundaryPoint> boundaryPoints = boundaryPoints(probe, surfaceKeys);
        int bucketSize = maxGapBlocks + 1;
        Map<BucketKey, List<BoundaryPoint>> buckets = new HashMap<>();
        Map<PairKey, Connection> nearestByPair = new HashMap<>();

        for (BoundaryPoint point : boundaryPoints) {
            int bucketX = Math.floorDiv(point.point().x(), bucketSize);
            int bucketZ = Math.floorDiv(point.point().z(), bucketSize);
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                for (int offsetX = -1; offsetX <= 1; offsetX++) {
                    BucketKey key = new BucketKey(point.surfaceKey(), bucketX + offsetX, bucketZ + offsetZ);
                    for (BoundaryPoint other : buckets.getOrDefault(key, List.of())) {
                        if (point.groupId().equals(other.groupId())) continue;
                        Connection candidate = connection(point, other, maxGapBlocks);
                        if (candidate == null) continue;
                        PairKey pairKey = new PairKey(candidate.surfaceCompatibilityKey(),
                                candidate.groupA(), candidate.groupB());
                        Connection previous = nearestByPair.get(pairKey);
                        if (previous == null || CONNECTION_ORDER.compare(candidate, previous) < 0) {
                            nearestByPair.put(pairKey, candidate);
                        }
                    }
                }
            }
            BucketKey ownBucket = new BucketKey(point.surfaceKey(), bucketX, bucketZ);
            buckets.computeIfAbsent(ownBucket, ignored -> new ArrayList<>()).add(point);
        }

        List<Connection> connections = nearestByPair.values().stream().sorted(CONNECTION_ORDER).toList();
        Map<String, List<Target>> targets = new LinkedHashMap<>();
        for (Connection connection : connections) {
            targets.computeIfAbsent(connection.groupA(), ignored -> new ArrayList<>()).add(
                    new Target(connection.connectionId(), connection.groupB(), connection.boundaryB()));
            targets.computeIfAbsent(connection.groupB(), ignored -> new ArrayList<>()).add(
                    new Target(connection.connectionId(), connection.groupA(), connection.boundaryA()));
        }
        targets.replaceAll((ignored, values) -> values.stream().sorted(TARGET_ORDER).toList());
        return new Plan(connections, targets);
    }

    public List<ConnectionOutcome> evaluate(Plan plan,
                                            List<LandUseSeedGroup> groups,
                                            LandUseExpansionResult expansion) {
        Map<String, String> surfaceKeys = surfaceKeys(groups);
        DisjointSet connectedGroups = new DisjointSet(surfaceKeys.keySet());
        for (Map.Entry<BlockPoint, LandUseExpansionResult.Claim> entry : expansion.claims().entrySet()) {
            String groupId = entry.getValue().groupId();
            String surfaceKey = surfaceKeys.get(groupId);
            if (surfaceKey == null) continue;
            for (int[] direction : DIRECTIONS) {
                if (direction[0] < 0 || direction[1] < 0) continue;
                BlockPoint neighborPoint = new BlockPoint(entry.getKey().x() + direction[0],
                        entry.getKey().z() + direction[1]);
                LandUseExpansionResult.Claim neighbor = expansion.claims().get(neighborPoint);
                if (neighbor == null || neighbor.groupId().equals(groupId)) continue;
                if (surfaceKey.equals(surfaceKeys.get(neighbor.groupId()))) {
                    connectedGroups.union(groupId, neighbor.groupId());
                }
            }
        }
        List<ConnectionOutcome> outcomes = new ArrayList<>();
        for (Connection connection : plan.connections()) {
            boolean connected = connectedGroups.connected(connection.groupA(), connection.groupB());
            String status = connected
                    ? (connection.initialBoundaryGapBlocks() == 0 ? "already_connected" : "connected_by_expansion")
                    : "not_reached";
            outcomes.add(new ConnectionOutcome(connection, status));
        }
        return List.copyOf(outcomes);
    }

    public static String surfaceCompatibilityKey(LandUseSeedGroup group) {
        if (!group.surfaceSettings().surfacePrintEnabled() || !group.surfaceSettings().autoConnect()) return "";
        return group.surfaceSettings().compatibilityKey();
    }

    private static Map<String, String> surfaceKeys(List<LandUseSeedGroup> groups) {
        Map<String, String> result = new HashMap<>();
        for (LandUseSeedGroup group : groups) {
            String key = surfaceCompatibilityKey(group);
            if (!key.isBlank()) result.put(group.groupId(), key);
        }
        return result;
    }

    private static List<BoundaryPoint> boundaryPoints(LandUseExpansionResult expansion,
                                                      Map<String, String> surfaceKeys) {
        List<BoundaryPoint> result = new ArrayList<>();
        for (Map.Entry<BlockPoint, LandUseExpansionResult.Claim> entry : expansion.claims().entrySet()) {
            String groupId = entry.getValue().groupId();
            String surfaceKey = surfaceKeys.get(groupId);
            if (surfaceKey == null || !isBoundary(entry.getKey(), groupId, expansion)) continue;
            result.add(new BoundaryPoint(groupId, surfaceKey, entry.getKey()));
        }
        result.sort(Comparator.comparing(BoundaryPoint::surfaceKey)
                .thenComparing(BoundaryPoint::groupId)
                .thenComparing(value -> value.point(), POINT_ORDER));
        return result;
    }

    private static boolean isBoundary(BlockPoint point,
                                      String groupId,
                                      LandUseExpansionResult expansion) {
        for (int[] direction : DIRECTIONS) {
            LandUseExpansionResult.Claim neighbor = expansion.claims().get(
                    new BlockPoint(point.x() + direction[0], point.z() + direction[1]));
            if (neighbor == null || !groupId.equals(neighbor.groupId())) return true;
        }
        return false;
    }

    private static Connection connection(BoundaryPoint left,
                                         BoundaryPoint right,
                                         int maxGapBlocks) {
        long distance = Math.abs((long) left.point().x() - right.point().x())
                + Math.abs((long) left.point().z() - right.point().z());
        long gap = Math.max(0, distance - 1);
        if (gap > maxGapBlocks) return null;
        BoundaryPoint first = left.groupId().compareTo(right.groupId()) <= 0 ? left : right;
        BoundaryPoint second = first == left ? right : left;
        String connectionId = "auto_surface:" + first.groupId() + ':' + second.groupId();
        return new Connection(connectionId, first.surfaceKey(), first.groupId(), second.groupId(),
                first.point(), second.point(), (int) gap);
    }

    public record Plan(List<Connection> connections,
                       Map<String, List<Target>> targetsByGroup) {
        public Plan {
            connections = List.copyOf(connections == null ? List.of() : connections);
            Map<String, List<Target>> copied = new LinkedHashMap<>();
            if (targetsByGroup != null) {
                targetsByGroup.forEach((groupId, targets) -> copied.put(groupId, List.copyOf(targets)));
            }
            targetsByGroup = Map.copyOf(copied);
        }

        public static Plan empty() {
            return new Plan(List.of(), Map.of());
        }

        public List<Target> targetsFor(String groupId) {
            return targetsByGroup.getOrDefault(groupId, List.of());
        }
    }

    public record Connection(String connectionId,
                             String surfaceCompatibilityKey,
                             String groupA,
                             String groupB,
                             BlockPoint boundaryA,
                             BlockPoint boundaryB,
                             int initialBoundaryGapBlocks) {
    }

    public record Target(String connectionId, String targetGroupId, BlockPoint point) {
    }

    public record ConnectionOutcome(Connection connection, String status) {
    }

    private record BoundaryPoint(String groupId, String surfaceKey, BlockPoint point) {
    }

    private record BucketKey(String surfaceKey, int x, int z) {
    }

    private record PairKey(String surfaceKey, String groupA, String groupB) {
    }

    private static final Comparator<BlockPoint> POINT_ORDER = Comparator.comparingInt(BlockPoint::z)
            .thenComparingInt(BlockPoint::x);
    private static final Comparator<Connection> CONNECTION_ORDER = Comparator
            .comparingInt(Connection::initialBoundaryGapBlocks)
            .thenComparing(Connection::surfaceCompatibilityKey)
            .thenComparing(Connection::groupA)
            .thenComparing(Connection::groupB)
            .thenComparing(Connection::boundaryA, POINT_ORDER)
            .thenComparing(Connection::boundaryB, POINT_ORDER);
    private static final Comparator<Target> TARGET_ORDER = Comparator.comparing(Target::targetGroupId)
            .thenComparing(Target::point, POINT_ORDER)
            .thenComparing(Target::connectionId);

    private static final class DisjointSet {
        private final Map<String, String> parents = new HashMap<>();

        private DisjointSet(Iterable<String> values) {
            values.forEach(value -> parents.put(value, value));
        }

        private String find(String value) {
            String parent = parents.get(value);
            if (parent == null) return "";
            if (parent.equals(value)) return value;
            String root = find(parent);
            parents.put(value, root);
            return root;
        }

        private void union(String left, String right) {
            String leftRoot = find(left);
            String rightRoot = find(right);
            if (leftRoot.isBlank() || rightRoot.isBlank() || leftRoot.equals(rightRoot)) return;
            if (leftRoot.compareTo(rightRoot) <= 0) parents.put(rightRoot, leftRoot);
            else parents.put(leftRoot, rightRoot);
        }

        private boolean connected(String left, String right) {
            String leftRoot = find(left);
            return !leftRoot.isBlank() && leftRoot.equals(find(right));
        }
    }
}
