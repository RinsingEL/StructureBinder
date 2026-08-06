package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRule;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public final class StableLandUseExpander {
    private static final int[][] DIRECTIONS = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}};

    public LandUseExpansionResult expand(String cityId,
                                         BlockBounds planningBounds,
                                         LandUseTerrainField terrain,
                                         List<LandUseSeedGroup> groups,
                                         List<LandUseAreaPlan.CorridorExclusion> corridors,
                                         String seedSalt) {
        return expand(cityId, planningBounds, terrain, groups, corridors, seedSalt,
                LandUseAutoConnectionPlanner.Plan.empty());
    }

    public LandUseExpansionResult expand(String cityId,
                                         BlockBounds planningBounds,
                                         LandUseTerrainField terrain,
                                         List<LandUseSeedGroup> groups,
                                         List<LandUseAreaPlan.CorridorExclusion> corridors,
                                         String seedSalt,
                                         LandUseAutoConnectionPlanner.Plan guidance) {
        if (cityId == null || cityId.isBlank()) throw new IllegalArgumentException("cityId is required");
        if (!cityId.equals(terrain.cityId())) throw new IllegalArgumentException("LAND_USE_TERRAIN_CITY_ID_MISMATCH");
        guidance = guidance == null ? LandUseAutoConnectionPlanner.Plan.empty() : guidance;
        Map<String, LandUseSeedGroup> byId = new HashMap<>();
        Map<String, GrowthContext> growthRegions = new HashMap<>();
        for (LandUseSeedGroup group : groups) {
            if (byId.put(group.groupId(), group) != null) {
                throw new IllegalArgumentException("Duplicate LandUse groupId: " + group.groupId());
            }
            for (LandUseSeedGroup.GrowthRegion region : group.growthRegions()) {
                if (growthRegions.put(region.regionId(), new GrowthContext(group, region)) != null) {
                    throw new IllegalArgumentException("Duplicate LandUse growth regionId: " + region.regionId());
                }
            }
        }
        Set<BlockPoint> obstacles = obstacles(groups, corridors);
        TerrainIndex terrainIndex = new TerrainIndex(terrain);
        PriorityQueue<Node> queue = new PriorityQueue<>(Comparator
                .comparingDouble(Node::priorityCost)
                .thenComparingDouble(Node::cumulativeCost)
                .thenComparing(Node::groupId)
                .thenComparing(Node::regionId)
                .thenComparingInt(Node::z)
                .thenComparingInt(Node::x));
        Map<RegionPoint, Double> best = new HashMap<>();
        Set<RegionPoint> expanded = new HashSet<>();
        Map<String, Integer> groupCounts = new HashMap<>();
        Map<String, Integer> regionCounts = new HashMap<>();
        Map<BlockPoint, LandUseExpansionResult.Claim> claims = new HashMap<>();
        int blocked = 0;
        int contested = 0;

        for (GrowthContext context : growthRegions.values().stream()
                .sorted(Comparator.comparing(value -> value.region().regionId())).toList()) {
            LandUseSeedGroup group = context.group();
            LandUseSeedGroup.GrowthRegion region = context.region();
            for (BlockPoint seed : region.seedPoints()) {
                if (!planningBounds.contains(seed.x(), seed.z()) || obstacles.contains(seed)
                        || !passable(terrainIndex.cellAt(seed.x(), seed.z()))) {
                    blocked++;
                    continue;
                }
                Node node = new Node(group.groupId(), region.regionId(), seed.x(), seed.z(), 0,
                        priority(0, 0, region, group), seed.x(), seed.z());
                RegionPoint key = new RegionPoint(region.regionId(), seed.x(), seed.z());
                if (best.putIfAbsent(key, 0.0) == null) queue.add(node);
            }
        }

        while (!queue.isEmpty()) {
            Node node = queue.remove();
            LandUseSeedGroup group = byId.get(node.groupId());
            GrowthContext context = growthRegions.get(node.regionId());
            if (group == null || context == null
                    || regionCounts.getOrDefault(node.regionId(), 0) >= context.region().maxAreaBlocks()) continue;
            RegionPoint candidateKey = new RegionPoint(node.regionId(), node.x(), node.z());
            if (node.cumulativeCost() > best.getOrDefault(candidateKey, Double.POSITIVE_INFINITY) + 1.0e-9) continue;
            if (!expanded.add(candidateKey)) continue;
            BlockPoint point = new BlockPoint(node.x(), node.z());
            LandUseExpansionResult.Claim existing = claims.get(point);
            if (existing != null) {
                if (!existing.groupId().equals(group.groupId())) {
                    contested++;
                    // Attached grounds and landscapes may intentionally share perimeter seeds.
                    // Let a losing seed launch its frontier, but never cross an occupied claim later.
                    if (node.cumulativeCost() > 1.0e-9) continue;
                }
            } else {
                claims.put(point, new LandUseExpansionResult.Claim(group.groupId(), node.cumulativeCost()));
                groupCounts.merge(group.groupId(), 1, Integer::sum);
                int claimed = regionCounts.merge(node.regionId(), 1, Integer::sum);
                if (claimed >= context.region().maxAreaBlocks()) continue;
            }

            for (int[] direction : DIRECTIONS) {
                int nextX = node.x() + direction[0];
                int nextZ = node.z() + direction[1];
                BlockPoint nextPoint = new BlockPoint(nextX, nextZ);
                if (!planningBounds.contains(nextX, nextZ) || obstacles.contains(nextPoint)) {
                    blocked++;
                    continue;
                }
                LandUseTerrainField.Cell cell = terrainIndex.cellAt(nextX, nextZ);
                if (!passable(cell)) {
                    blocked++;
                    continue;
                }
                double stepCost = stepCost(group.rule(), group.growthBias(), group.terrainBias(),
                        group.preferredPatchRefs(), cell,
                        cityId, group.groupId(), seedSalt, nextX, nextZ,
                        node.seedX(), node.seedZ(), node.x(), node.z(), guidance.targetsFor(group.groupId()));
                double total = node.cumulativeCost() + stepCost;
                if (total > group.actionBudget()) continue;
                RegionPoint key = new RegionPoint(node.regionId(), nextX, nextZ);
                if (total + 1.0e-9 >= best.getOrDefault(key, Double.POSITIVE_INFINITY)) continue;
                best.put(key, total);
                int regionClaimed = regionCounts.getOrDefault(node.regionId(), 0);
                queue.add(new Node(group.groupId(), node.regionId(), nextX, nextZ, total,
                        priority(total, regionClaimed, context.region(), group), node.seedX(), node.seedZ()));
            }
        }
        return new LandUseExpansionResult(claims, groupCounts, regionCounts, contested, blocked);
    }

    private static Set<BlockPoint> obstacles(List<LandUseSeedGroup> groups,
                                             List<LandUseAreaPlan.CorridorExclusion> corridors) {
        Set<BlockPoint> points = new HashSet<>();
        for (LandUseSeedGroup group : groups) {
            for (BlockBounds bounds : group.structureFootprints()) addBounds(points, bounds);
        }
        for (LandUseAreaPlan.CorridorExclusion corridor : corridors) addBounds(points, corridor.blockBounds());
        return points;
    }

    private static void addBounds(Set<BlockPoint> points, BlockBounds bounds) {
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) points.add(new BlockPoint(x, z));
        }
    }

    private static boolean passable(LandUseTerrainField.Cell cell) {
        return cell != null && cell.sampled() && cell.slope() < 45.0 && cell.localRelief() < 48.0;
    }

    private static double stepCost(LandUseRule rule,
                                   LandUseSeedGroup.GrowthBias growthBias,
                                   LandUseSeedGroup.TerrainBias terrainBias,
                                   List<String> preferredPatchRefs,
                                   LandUseTerrainField.Cell cell,
                                   String cityId,
                                   String groupId,
                                   String seedSalt,
                                   int x,
                                   int z,
                                   int seedX,
                                   int seedZ,
                                   int currentX,
                                   int currentZ,
                                   List<LandUseAutoConnectionPlanner.Target> targets) {
        double value = rule.baseStepCost()
                * directionalBaseMultiplier(currentX, currentZ, x, z, targets)
                * growthBiasMultiplier(growthBias, currentX, currentZ, x, z);
        value += rule.slopeCost() * terrainBias.slopeMultiplier() * Math.max(0, cell.slope()) / 10.0;
        value += rule.reliefCost() * terrainBias.reliefMultiplier()
                * Math.max(Math.max(0, cell.localRelief()), Math.max(0, cell.roughness())) / 10.0;
        if (cell.water()) value += rule.waterCost();
        String biome = cell.biomeId().toLowerCase(java.util.Locale.ROOT);
        if (biome.contains("forest") || biome.contains("taiga") || biome.contains("jungle")) {
            value += rule.forestAffinity();
        }
        if (!preferredPatchRefs.isEmpty()) {
            value *= preferredPatchRefs.contains(cell.landformPatchId()) ? 0.8 : 1.15;
        }
        value += (Math.abs(x - seedX) + Math.abs(z - seedZ)) * 0.002;
        value += deterministicJitter(cityId + ':' + groupId + ':' + rule.ruleRef() + ':' + seedSalt, x, z);
        return Math.max(0.1, value);
    }

    private static double growthBiasMultiplier(LandUseSeedGroup.GrowthBias bias,
                                               int currentX,
                                               int currentZ,
                                               int nextX,
                                               int nextZ) {
        if (bias == null || bias.mode() == LandUseSeedGroup.GrowthBiasMode.NEUTRAL) return 1.0;
        if (bias.mode() == LandUseSeedGroup.GrowthBiasMode.ALONG_WATER) {
            int stepX = nextX - currentX;
            int stepZ = nextZ - currentZ;
            return Math.abs(stepX * bias.axisX() + stepZ * bias.axisZ()) > 0 ? 0.65 : 1.8;
        }
        BlockPoint reference = bias.referencePoint();
        long currentDistance = Math.abs((long) currentX - reference.x())
                + Math.abs((long) currentZ - reference.z());
        long nextDistance = Math.abs((long) nextX - reference.x())
                + Math.abs((long) nextZ - reference.z());
        boolean aligned = bias.mode() == LandUseSeedGroup.GrowthBiasMode.AWAY_FROM_REFERENCE
                ? nextDistance > currentDistance : nextDistance < currentDistance;
        boolean opposed = bias.mode() == LandUseSeedGroup.GrowthBiasMode.AWAY_FROM_REFERENCE
                ? nextDistance < currentDistance : nextDistance > currentDistance;
        if (aligned) return 0.65;
        if (opposed) return 1.8;
        return 1.0;
    }

    private static double directionalBaseMultiplier(int currentX,
                                                    int currentZ,
                                                    int nextX,
                                                    int nextZ,
                                                    List<LandUseAutoConnectionPlanner.Target> targets) {
        if (targets.isEmpty()) return 1.0;
        boolean lateral = false;
        int stepX = nextX - currentX;
        int stepZ = nextZ - currentZ;
        for (LandUseAutoConnectionPlanner.Target target : targets) {
            long targetX = (long) target.point().x() - currentX;
            long targetZ = (long) target.point().z() - currentZ;
            long alignment = stepX * targetX + stepZ * targetZ;
            if (alignment > 0) return 0.45;
            if (alignment == 0) lateral = true;
        }
        return lateral ? 1.15 : 1.85;
    }

    private static double priority(double cumulativeCost,
                                   int claimed,
                                   LandUseSeedGroup.GrowthRegion region,
                                   LandUseSeedGroup group) {
        double completion = claimed / (double) Math.max(1, region.preferredAreaBlocks());
        return cumulativeCost * (1.0 + Math.min(1.0, completion) * 0.35)
                / Math.max(0.1, group.competitionWeight());
    }

    private static double deterministicJitter(String seed, int x, int z) {
        long value = 0xcbf29ce484222325L;
        String key = seed + ':' + x + ':' + z;
        for (int index = 0; index < key.length(); index++) {
            value ^= key.charAt(index);
            value *= 0x100000001b3L;
        }
        return ((value >>> 11) & 0xffffL) / 65535.0 * 0.15;
    }

    private record Node(String groupId, String regionId, int x, int z,
                        double cumulativeCost, double priorityCost,
                        int seedX, int seedZ) {
    }

    private record GrowthContext(LandUseSeedGroup group, LandUseSeedGroup.GrowthRegion region) {
    }

    private record RegionPoint(String regionId, int x, int z) {
    }

    private static final class TerrainIndex {
        private final int step;
        private final Map<CellKey, LandUseTerrainField.Cell> cells = new HashMap<>();

        private TerrainIndex(LandUseTerrainField field) {
            this.step = field.cellStepBlocks();
            field.cells().forEach(cell -> cells.put(new CellKey(cell.cellX(), cell.cellZ()), cell));
        }

        private LandUseTerrainField.Cell cellAt(int x, int z) {
            return cells.get(new CellKey(Math.floorDiv(x, step), Math.floorDiv(z, step)));
        }
    }

    private record CellKey(int x, int z) {
    }
}
