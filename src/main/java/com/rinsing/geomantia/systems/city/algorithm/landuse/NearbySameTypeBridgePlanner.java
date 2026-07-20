package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRule;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Adds bounded, deterministic natural-land bridges between nearby compatible LandUse components. */
public final class NearbySameTypeBridgePlanner {
    private static final int[][] DIRECTIONS = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}};

    public Result bridge(BlockBounds planningBounds,
                         LandUseTerrainField terrain,
                         List<LandUseSeedGroup> groups,
                         List<LandUseAreaPlan.CorridorExclusion> corridors,
                         LandUseExpansionResult expansion) {
        Map<String, LandUseSeedGroup> groupsById = new HashMap<>();
        for (LandUseSeedGroup group : groups) groupsById.put(group.groupId(), group);
        Map<BlockPoint, LandUseExpansionResult.Claim> claims = new HashMap<>(expansion.claims());
        Set<BlockPoint> obstacles = obstacles(groups, corridors);
        TerrainIndex terrainIndex = new TerrainIndex(terrain);
        List<Component> components = components(claims, groupsById);
        List<Candidate> candidates = candidates(planningBounds, terrainIndex, obstacles, claims, components);

        UnionFind unions = new UnionFind(components.stream().map(Component::id).toList());
        List<Bridge> accepted = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (unions.connected(candidate.leftId(), candidate.rightId())
                    || candidate.path().stream().anyMatch(claims::containsKey)) {
                continue;
            }
            for (BlockPoint point : candidate.path()) {
                claims.put(point, new LandUseExpansionResult.Claim(candidate.ownerGroupId(), candidate.path().size()));
            }
            unions.union(candidate.leftId(), candidate.rightId());
            accepted.add(new Bridge(candidate.ruleRef(), candidate.sourceGroupIds(), candidate.path().size()));
        }
        LandUseExpansionResult bridged = new LandUseExpansionResult(claims, expansion.claimedBlocksByGroup(),
                expansion.contestedClaimCount(), expansion.blockedCandidateCount());
        return new Result(bridged, List.copyOf(accepted));
    }

    private static List<Component> components(Map<BlockPoint, LandUseExpansionResult.Claim> claims,
                                              Map<String, LandUseSeedGroup> groupsById) {
        Map<String, Set<BlockPoint>> pointsByGroup = new LinkedHashMap<>();
        for (Map.Entry<BlockPoint, LandUseExpansionResult.Claim> entry : claims.entrySet()) {
            LandUseSeedGroup group = groupsById.get(entry.getValue().groupId());
            if (group != null && group.rule().mergeSameType() && group.rule().nearbyMergeMaxBridgeBlocks() > 0) {
                pointsByGroup.computeIfAbsent(group.groupId(), ignored -> new HashSet<>()).add(entry.getKey());
            }
        }
        UnionFind adjacentGroups = new UnionFind(pointsByGroup.keySet().stream().sorted().toList());
        for (Map.Entry<BlockPoint, LandUseExpansionResult.Claim> entry : claims.entrySet()) {
            LandUseSeedGroup group = groupsById.get(entry.getValue().groupId());
            if (group == null || !pointsByGroup.containsKey(group.groupId())) continue;
            for (int[] direction : DIRECTIONS) {
                LandUseExpansionResult.Claim neighbor = claims.get(new BlockPoint(
                        entry.getKey().x() + direction[0], entry.getKey().z() + direction[1]));
                if (neighbor == null || neighbor.groupId().equals(group.groupId())) continue;
                LandUseSeedGroup neighborGroup = groupsById.get(neighbor.groupId());
                if (neighborGroup != null && group.rule().ruleRef().equals(neighborGroup.rule().ruleRef())
                        && pointsByGroup.containsKey(neighborGroup.groupId())) {
                    adjacentGroups.union(group.groupId(), neighborGroup.groupId());
                }
            }
        }
        Map<String, ComponentBuilder> merged = new LinkedHashMap<>();
        for (String groupId : pointsByGroup.keySet().stream().sorted().toList()) {
            String root = adjacentGroups.find(groupId);
            ComponentBuilder builder = merged.computeIfAbsent(root, ignored -> new ComponentBuilder());
            builder.groupIds.add(groupId);
            builder.points.addAll(pointsByGroup.get(groupId));
        }
        List<Component> result = new ArrayList<>();
        for (ComponentBuilder builder : merged.values()) {
            List<String> groupIds = builder.groupIds.stream().sorted().toList();
            LandUseRule rule = groupsById.get(groupIds.get(0)).rule();
            String id = rule.ruleRef() + ':' + String.join("+", groupIds);
            result.add(new Component(id, rule.ruleRef(), rule, Set.copyOf(builder.points), groupIds,
                    bounds(builder.points)));
        }
        result.sort(Comparator.comparing(Component::ruleRef).thenComparing(Component::id));
        return result;
    }

    private static List<Candidate> candidates(BlockBounds bounds,
                                              TerrainIndex terrain,
                                              Set<BlockPoint> obstacles,
                                              Map<BlockPoint, LandUseExpansionResult.Claim> claims,
                                              List<Component> components) {
        List<Candidate> result = new ArrayList<>();
        for (int leftIndex = 0; leftIndex < components.size(); leftIndex++) {
            Component left = components.get(leftIndex);
            for (int rightIndex = leftIndex + 1; rightIndex < components.size(); rightIndex++) {
                Component right = components.get(rightIndex);
                if (!left.ruleRef().equals(right.ruleRef())) continue;
                int maxBlocks = left.rule().nearbyMergeMaxBridgeBlocks();
                List<BlockPoint> path = shortestPath(bounds, terrain, obstacles, claims, left, right, maxBlocks);
                if (path.isEmpty()) continue;
                List<String> sourceGroups = new ArrayList<>(left.sourceGroupIds());
                sourceGroups.addAll(right.sourceGroupIds());
                sourceGroups = sourceGroups.stream().distinct().sorted().toList();
                result.add(new Candidate(left.id(), right.id(), left.ruleRef(), sourceGroups, sourceGroups.get(0), path));
            }
        }
        result.sort(Comparator.comparingInt((Candidate value) -> value.path().size())
                .thenComparing(Candidate::ruleRef).thenComparing(Candidate::leftId).thenComparing(Candidate::rightId));
        return result;
    }

    private static List<BlockPoint> shortestPath(BlockBounds bounds,
                                                 TerrainIndex terrain,
                                                 Set<BlockPoint> obstacles,
                                                 Map<BlockPoint, LandUseExpansionResult.Claim> claims,
                                                 Component left,
                                                 Component right,
                                                 int maxBlocks) {
        if (maxBlocks <= 0 || lowerBound(left.bounds(), right.bounds()) - 1 > maxBlocks) return List.of();
        ArrayDeque<BlockPoint> queue = new ArrayDeque<>();
        Map<BlockPoint, BlockPoint> predecessor = new HashMap<>();
        Map<BlockPoint, Integer> distance = new HashMap<>();
        for (BlockPoint start : boundary(left.points())) {
            queue.addLast(start);
            distance.put(start, 0);
        }
        while (!queue.isEmpty()) {
            BlockPoint current = queue.removeFirst();
            int currentDistance = distance.get(current);
            for (int[] direction : DIRECTIONS) {
                BlockPoint next = new BlockPoint(current.x() + direction[0], current.z() + direction[1]);
                if (right.points().contains(next)) return reconstruct(current, predecessor);
                if (currentDistance >= maxBlocks || distance.containsKey(next)
                        || !isNaturalBridgeCell(bounds, terrain, obstacles, claims, next)) {
                    continue;
                }
                predecessor.put(next, current);
                distance.put(next, currentDistance + 1);
                queue.addLast(next);
            }
        }
        return List.of();
    }

    private static List<BlockPoint> reconstruct(BlockPoint current, Map<BlockPoint, BlockPoint> predecessor) {
        List<BlockPoint> result = new ArrayList<>();
        BlockPoint cursor = current;
        while (predecessor.containsKey(cursor)) {
            result.add(cursor);
            cursor = predecessor.get(cursor);
        }
        return result;
    }

    private static boolean isNaturalBridgeCell(BlockBounds bounds,
                                               TerrainIndex terrain,
                                               Set<BlockPoint> obstacles,
                                               Map<BlockPoint, LandUseExpansionResult.Claim> claims,
                                               BlockPoint point) {
        if (!bounds.contains(point.x(), point.z()) || obstacles.contains(point) || claims.containsKey(point)) return false;
        LandUseTerrainField.Cell cell = terrain.cellAt(point.x(), point.z());
        return cell != null && cell.sampled() && !cell.water() && cell.slope() < 45.0 && cell.localRelief() < 48.0;
    }

    private static List<BlockPoint> boundary(Set<BlockPoint> points) {
        return points.stream().filter(point -> {
            for (int[] direction : DIRECTIONS) {
                if (!points.contains(new BlockPoint(point.x() + direction[0], point.z() + direction[1]))) return true;
            }
            return false;
        }).sorted(POINT_ORDER).toList();
    }

    private static int lowerBound(BlockBounds left, BlockBounds right) {
        int x = left.maxX() < right.minX() ? right.minX() - left.maxX()
                : right.maxX() < left.minX() ? left.minX() - right.maxX() : 0;
        int z = left.maxZ() < right.minZ() ? right.minZ() - left.maxZ()
                : right.maxZ() < left.minZ() ? left.minZ() - right.maxZ() : 0;
        return x + z;
    }

    private static BlockBounds bounds(Set<BlockPoint> points) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPoint point : points) {
            minX = Math.min(minX, point.x());
            minZ = Math.min(minZ, point.z());
            maxX = Math.max(maxX, point.x());
            maxZ = Math.max(maxZ, point.z());
        }
        return new BlockBounds(minX, minZ, maxX, maxZ);
    }

    private static Set<BlockPoint> obstacles(List<LandUseSeedGroup> groups,
                                             List<LandUseAreaPlan.CorridorExclusion> corridors) {
        Set<BlockPoint> result = new HashSet<>();
        for (LandUseSeedGroup group : groups) {
            for (BlockBounds footprint : group.structureFootprints()) addBounds(result, footprint);
        }
        for (LandUseAreaPlan.CorridorExclusion corridor : corridors) addBounds(result, corridor.blockBounds());
        return result;
    }

    private static void addBounds(Set<BlockPoint> target, BlockBounds bounds) {
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) target.add(new BlockPoint(x, z));
        }
    }

    private static final Comparator<BlockPoint> POINT_ORDER = Comparator.comparingInt(BlockPoint::z)
            .thenComparingInt(BlockPoint::x);

    public record Result(LandUseExpansionResult expansion, List<Bridge> bridges) {
    }

    public record Bridge(String ruleRef, List<String> sourceGroupIds, int bridgeBlockCount) {
        public Bridge {
            sourceGroupIds = List.copyOf(sourceGroupIds);
        }
    }

    private record Component(String id, String ruleRef, LandUseRule rule, Set<BlockPoint> points,
                             List<String> sourceGroupIds, BlockBounds bounds) {
    }

    private record Candidate(String leftId, String rightId, String ruleRef, List<String> sourceGroupIds,
                             String ownerGroupId, List<BlockPoint> path) {
    }

    private static final class ComponentBuilder {
        private final Set<String> groupIds = new HashSet<>();
        private final Set<BlockPoint> points = new HashSet<>();
    }

    private static final class TerrainIndex {
        private final int step;
        private final Map<CellKey, LandUseTerrainField.Cell> cells = new HashMap<>();

        private TerrainIndex(LandUseTerrainField terrain) {
            step = terrain.cellStepBlocks();
            terrain.cells().forEach(cell -> cells.put(new CellKey(cell.cellX(), cell.cellZ()), cell));
        }

        private LandUseTerrainField.Cell cellAt(int x, int z) {
            return cells.get(new CellKey(Math.floorDiv(x, step), Math.floorDiv(z, step)));
        }
    }

    private record CellKey(int x, int z) {
    }

    private static final class UnionFind {
        private final Map<String, String> parent = new HashMap<>();

        private UnionFind(List<String> ids) {
            ids.forEach(id -> parent.put(id, id));
        }

        private boolean connected(String left, String right) {
            return find(left).equals(find(right));
        }

        private void union(String left, String right) {
            String leftRoot = find(left);
            String rightRoot = find(right);
            if (leftRoot.equals(rightRoot)) return;
            if (leftRoot.compareTo(rightRoot) <= 0) {
                parent.put(rightRoot, leftRoot);
            } else {
                parent.put(leftRoot, rightRoot);
            }
        }

        private String find(String id) {
            String value = parent.get(id);
            if (value == null || value.equals(id)) return id;
            String root = find(value);
            parent.put(id, root);
            return root;
        }
    }
}
