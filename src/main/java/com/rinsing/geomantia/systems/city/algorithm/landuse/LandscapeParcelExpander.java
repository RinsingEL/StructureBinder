package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.landuse.LandscapeTerrainContinuity;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRule;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Formal Blueprint-path growth for independent Landscape Parcel silhouettes.
 */
public final class LandscapeParcelExpander {
    private static final int[][] DIRECTIONS = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}};
    private static final double EPSILON = 1.0e-9;

    public LandUseExpansionResult expand(String cityId,
                                         BlockBounds planningBounds,
                                         LandUseTerrainField terrain,
                                         List<LandUseSeedGroup> groups,
                                         String seedSalt) {
        return expand(cityId, planningBounds, terrain, groups, seedSalt, Set.of());
    }

    public LandUseExpansionResult expand(String cityId,
                                         BlockBounds planningBounds,
                                         LandUseTerrainField terrain,
                                         List<LandUseSeedGroup> groups,
                                         String seedSalt,
                                         Set<BlockPoint> reservedPoints) {
        return expand(cityId, planningBounds, terrain, groups, seedSalt, reservedPoints, Map.of());
    }

    public LandUseExpansionResult expand(String cityId,
                                         BlockBounds planningBounds,
                                         LandUseTerrainField terrain,
                                         List<LandUseSeedGroup> groups,
                                         String seedSalt,
                                         Set<BlockPoint> reservedPoints,
                                         Map<String, Set<BlockPoint>> capacityDomains) {
        return expand(cityId, planningBounds, terrain, groups, seedSalt, reservedPoints,
                capacityDomains, Map.of());
    }

    public LandUseExpansionResult expand(String cityId,
                                         BlockBounds planningBounds,
                                         LandUseTerrainField terrain,
                                         List<LandUseSeedGroup> groups,
                                         String seedSalt,
                                         Set<BlockPoint> reservedPoints,
                                         Map<String, Set<BlockPoint>> capacityDomains,
                                         Map<String, String> parentParcelIds) {
        if (cityId == null || cityId.isBlank()) throw new IllegalArgumentException("cityId is required");
        Objects.requireNonNull(planningBounds, "planningBounds");
        Objects.requireNonNull(terrain, "terrain");
        if (!cityId.equals(terrain.cityId())) {
            throw new IllegalArgumentException("LAND_USE_TERRAIN_CITY_ID_MISMATCH");
        }

        List<LandUseSeedGroup> orderedGroups = (groups == null ? List.<LandUseSeedGroup>of() : groups).stream()
                .sorted(Comparator.comparing(LandUseSeedGroup::groupId)).toList();
        TerrainIndex terrainIndex = new TerrainIndex(terrain);
        Set<String> groupIds = new HashSet<>();
        Set<String> regionIds = new HashSet<>();
        List<RegionState> states = new ArrayList<>();
        for (LandUseSeedGroup group : orderedGroups) {
            if (!groupIds.add(group.groupId())) {
                throw new IllegalArgumentException("Duplicate LandUse groupId: " + group.groupId());
            }
            if (group.layerRole() != LandUseSeedGroup.LayerRole.LANDSCAPE) {
                throw new IllegalArgumentException("CITY_LANDSCAPE_EXPANDER_ROLE_INVALID:" + group.groupId());
            }
            for (LandUseSeedGroup.GrowthRegion region : group.growthRegions().stream()
                    .sorted(Comparator.comparing(LandUseSeedGroup.GrowthRegion::regionId)).toList()) {
                if (!regionIds.add(region.regionId())) {
                    throw new IllegalArgumentException("Duplicate LandUse growth regionId: " + region.regionId());
                }
                if (region.seedPoints().size() != 1) {
                    throw new IllegalArgumentException("CITY_LANDSCAPE_ROOT_SEED_COUNT_INVALID:"
                            + group.groupId() + ':' + region.regionId() + ':' + region.seedPoints().size());
                }
                states.add(new RegionState(group, region, region.seedPoints().get(0), terrainIndex,
                        stableHash(cityId + ':' + group.groupId() + ':' + region.regionId() + ':' + seedSalt),
                        capacityDomains == null ? Set.of()
                                : capacityDomains.getOrDefault(group.groupId(), Set.of())));
            }
        }
        states.sort(Comparator.comparing((RegionState state) -> state.group.groupId())
                .thenComparing(state -> state.region.regionId()));

        Set<BlockPoint> obstacles = obstacles(orderedGroups);
        obstacles.addAll(reservedPoints == null ? Set.of() : reservedPoints);
        Map<BlockPoint, LandUseExpansionResult.Claim> claims = new LinkedHashMap<>();
        Counters counters = new Counters();

        Map<String, RegionState> completedByGroup = new LinkedHashMap<>();
        Map<String, List<BlockPoint>> effectiveSeedsByGroup = new LinkedHashMap<>();
        Map<String, LandUseExpansionResult.ExpansionOrigin> expansionOriginsByGroup = new LinkedHashMap<>();
        for (RegionState state : states) {
            if (state.targetArea == 0) {
                state.exhausted = true;
                completedByGroup.put(state.group.groupId(), state);
                continue;
            }
            RelaySeed relaySeed;
            try {
                relaySeed = relaySeed(state, completedByGroup, claims, planningBounds,
                        terrainIndex, obstacles, parentParcelIds == null ? Map.of() : parentParcelIds);
            } catch (IllegalArgumentException failure) {
                if (!isRelayAdmissionFailure(failure)) throw failure;
                state.exhausted = true;
                completedByGroup.put(state.group.groupId(), state);
                continue;
            }
            BlockPoint seed = relaySeed.start();
            if (relaySeed.roadGap() != null) obstacles.add(relaySeed.roadGap());
            if (!planningBounds.contains(seed.x(), seed.z()) || obstacles.contains(seed)
                    || !state.allowed(seed) || !passable(terrainIndex.cellAt(seed.x(), seed.z()))) {
                counters.blocked++;
                state.exhausted = true;
                completedByGroup.put(state.group.groupId(), state);
                continue;
            }
            if (claims.containsKey(seed)) {
                counters.contested++;
                state.exhausted = true;
                completedByGroup.put(state.group.groupId(), state);
                continue;
            }
            state.seed = seed;
            claim(state, seed, 0.0, claims, planningBounds, terrainIndex, obstacles, counters);
            effectiveSeedsByGroup.computeIfAbsent(state.group.groupId(), ignored -> new ArrayList<>()).add(seed);
            expansionOriginsByGroup.put(state.group.groupId(), new LandUseExpansionResult.ExpansionOrigin(
                    relaySeed.kind(), relaySeed.parentGroupId(), seed, relaySeed.sourceFrontier()));
            while (!state.exhausted && state.cells.size() < state.targetArea) {
                Candidate candidate = bestCandidate(state, counters);
                if (candidate == null) {
                    state.exhausted = true;
                    break;
                }
                state.frontier.remove(candidate.point());
                claim(state, candidate.point(), candidate.pathCost(), claims,
                        planningBounds, terrainIndex, obstacles, counters);
            }
            completedByGroup.put(state.group.groupId(), state);
        }

        Map<String, Integer> groupCounts = new LinkedHashMap<>();
        Map<String, Integer> regionCounts = new LinkedHashMap<>();
        for (RegionState state : states) {
            regionCounts.put(state.region.regionId(), state.cells.size());
            groupCounts.merge(state.group.groupId(), state.cells.size(), Integer::sum);
        }
        return new LandUseExpansionResult(claims, groupCounts, regionCounts, effectiveSeedsByGroup,
                expansionOriginsByGroup,
                counters.contested, counters.blocked);
    }

    private static boolean isRelayAdmissionFailure(IllegalArgumentException failure) {
        String message = failure.getMessage();
        return message != null && (message.startsWith("CITY_LANDSCAPE_PARENT_PARCEL_UNAVAILABLE:")
                || message.startsWith("CITY_LANDSCAPE_PARENT_INTERFACE_EXHAUSTED:"));
    }

    private static RelaySeed relaySeed(RegionState state, Map<String, RegionState> completed,
                                       Map<BlockPoint, LandUseExpansionResult.Claim> claims,
                                       BlockBounds planningBounds, TerrainIndex terrain,
                                       Set<BlockPoint> obstacles,
                                       Map<String, String> parentParcelIds) {
        String groupId = state.group.groupId();
        int marker = groupId.lastIndexOf("::parcel_");
        if (marker < 0) return RelaySeed.root(state.seed);
        String frozenParentId = parentParcelIds.get(groupId);
        if (frozenParentId != null) {
            if (frozenParentId.isBlank()) return RelaySeed.root(state.seed);
            return relayFromParent(state, completed.get(frozenParentId), claims, planningBounds,
                    terrain, obstacles, frozenParentId);
        }
        int ordinal;
        try {
            ordinal = Integer.parseInt(groupId.substring(marker + "::parcel_".length()));
        } catch (NumberFormatException ignored) {
            return RelaySeed.root(state.seed);
        }
        if (ordinal <= 1) return RelaySeed.root(state.seed);
        String parentId = groupId.substring(0, marker) + "::parcel_"
                + String.format(Locale.ROOT, "%02d", ordinal - 1);
        return relayFromParent(state, completed.get(parentId), claims, planningBounds, terrain, obstacles, parentId);
    }

    private static RelaySeed relayFromParent(RegionState state, RegionState parent,
                                              Map<BlockPoint, LandUseExpansionResult.Claim> claims,
                                              BlockBounds planningBounds, TerrainIndex terrain,
                                              Set<BlockPoint> obstacles,
                                              String parentGroupId) {
        if (parent == null || parent.cells.isEmpty()) {
            throw new IllegalArgumentException("CITY_LANDSCAPE_PARENT_PARCEL_UNAVAILABLE:"
                    + state.group.groupId() + ':' + parentGroupId);
        }
        boolean roadGap = usesNaturalRoadGap(state.group);
        Map<BlockPoint, RelayInterface> interfaceByStart = new HashMap<>();
        for (BlockPoint source : parent.cells) {
            for (int[] direction : DIRECTIONS) {
                BlockPoint gap = roadGap
                        ? new BlockPoint(source.x() + direction[0], source.z() + direction[1]) : null;
                int advance = roadGap ? 2 : 1;
                BlockPoint start = new BlockPoint(source.x() + direction[0] * advance,
                        source.z() + direction[1] * advance);
                if (!planningBounds.contains(start.x(), start.z()) || claims.containsKey(start)
                        || obstacles.contains(start) || !state.allowed(start)
                        || !passable(terrain.cellAt(start.x(), start.z()))
                        || roadGap && (!planningBounds.contains(gap.x(), gap.z())
                        || claims.containsKey(gap) || obstacles.contains(gap)
                        || !passable(terrain.cellAt(gap.x(), gap.z()))
                        || !continuous(state.group, terrain, source, gap)
                        || !continuous(state.group, terrain, gap, start))
                        || !roadGap && !continuous(state.group, terrain, source, start)) {
                    continue;
                }
                RelayInterface candidate = new RelayInterface(source, gap);
                interfaceByStart.merge(start, candidate,
                        (left, right) -> POINT_ORDER.compare(left.source(), right.source()) <= 0
                                ? left : right);
            }
        }
        BlockPoint start = interfaceByStart.keySet().stream()
                .max(Comparator.comparingInt((BlockPoint point) -> adjacentCount(parent.cells, point))
                        .thenComparingInt(point -> -point.z()).thenComparingInt(point -> -point.x()))
                .orElseThrow(() -> new IllegalArgumentException("CITY_LANDSCAPE_PARENT_INTERFACE_EXHAUSTED:"
                        + state.group.groupId() + ':' + parentGroupId));
        RelayInterface relayInterface = interfaceByStart.get(start);
        return new RelaySeed(start, relayInterface.source(), parentGroupId,
                relayInterface.roadGap(),
                roadGap ? LandUseExpansionResult.OriginKind.PARENT_PARCEL_ROAD_GAP
                        : LandUseExpansionResult.OriginKind.PARENT_PARCEL_INTERFACE);
    }

    private static boolean usesNaturalRoadGap(LandUseSeedGroup group) {
        if (group.landscapeFillProgram() == null) return false;
        return group.landscapeFillProgram().roles().stream().anyMatch(role ->
                role.growthForm() == com.rinsing.geomantia.systems.city.domain.landuse.LandscapeFillProgram.GrowthForm.CORRIDOR
                        && (role.materialRole()
                        == com.rinsing.geomantia.systems.city.domain.landuse.LandscapeFillProgram.MaterialRole.GROUND));
    }

    private static int adjacentCount(Set<BlockPoint> cells, BlockPoint point) {
        int count = 0;
        for (int[] direction : DIRECTIONS) {
            if (cells.contains(new BlockPoint(point.x() + direction[0], point.z() + direction[1]))) count++;
        }
        return count;
    }

    private static Candidate bestCandidate(RegionState state, Counters counters) {
        // Regions grow sequentially. Occupied frontier entries are known when inserted;
        // consume them here to preserve the original contested-count timing (not on the
        // final claim). Scores can change only next to a newly claimed cell.
        for (BlockPoint point : state.contestedFrontier) {
            state.frontier.remove(point);
            Candidate previous = state.scoredFrontier.remove(point);
            if (previous != null) state.rankedFrontier.remove(previous);
            counters.contested++;
        }
        state.contestedFrontier.clear();
        Candidate best = state.rankedFrontier.pollFirst();
        if (best != null) state.scoredFrontier.remove(best.point());
        return best;
    }

    private static void claim(RegionState state,
                              BlockPoint point,
                              double pathCost,
                              Map<BlockPoint, LandUseExpansionResult.Claim> claims,
                              BlockBounds planningBounds,
                              TerrainIndex terrain,
                              Set<BlockPoint> obstacles,
                              Counters counters) {
        state.cells.add(point);
        claims.put(point, new LandUseExpansionResult.Claim(state.group.groupId(), pathCost));
        for (int[] direction : DIRECTIONS) {
            BlockPoint next = new BlockPoint(point.x() + direction[0], point.z() + direction[1]);
            if (state.cells.contains(next)) continue;
            if (!planningBounds.contains(next.x(), next.z()) || obstacles.contains(next)
                    || !state.allowed(next)) {
                if (state.rejected.add(next)) counters.blocked++;
                continue;
            }
            LandUseTerrainField.Cell cell = terrain.cellAt(next.x(), next.z());
            if (!passable(cell) || !continuous(state.group, terrain, point, next)) {
                if (state.rejected.add(next)) counters.blocked++;
                continue;
            }
            double nextPathCost = pathCost + terrainStepCost(state.group, cell);
            // A frozen D4 capacity domain is already the terrain-fit and size authority.
            // The generic action budget limits free search only; applying it again here can
            // truncate a large approved parcel before growth reaches its reserved boundary.
            if (state.capacityDomain.isEmpty()
                    && nextPathCost > state.group.actionBudget() + EPSILON) {
                if (state.rejected.add(next)) counters.blocked++;
                continue;
            }
            state.frontier.merge(next, new FrontierPath(nextPathCost),
                    (left, right) -> left.pathCost() <= right.pathCost() ? left : right);
            if (claims.containsKey(next)) state.contestedFrontier.add(next);
        }
        // Even an existing frontier cell whose new incoming path is rejected gets a
        // different adjacency score. Refresh all four neighbors, not just inserted paths.
        for (int[] direction : DIRECTIONS) {
            BlockPoint next = new BlockPoint(point.x() + direction[0], point.z() + direction[1]);
            FrontierPath path = state.frontier.get(next);
            if (path == null) continue;
            Candidate previous = state.scoredFrontier.remove(next);
            if (previous != null) state.rankedFrontier.remove(previous);
            Candidate updated = new Candidate(next, path.pathCost(), silhouetteScore(state, next));
            state.scoredFrontier.put(next, updated);
            state.rankedFrontier.add(updated);
        }
    }

    private static double silhouetteScore(RegionState state, BlockPoint point) {
        double dx = point.x() - state.seed.x();
        double dz = point.z() - state.seed.z();
        double radius = Math.max(2.0, Math.sqrt(state.targetArea / Math.PI));
        double radial = Math.hypot(dx, dz) / radius;
        int broadScale = Math.max(6, (int) Math.round(radius * 0.9));
        int localScale = Math.max(3, (int) Math.round(radius * 0.42));
        double broadNoise = valueNoise(state.shapeSeed ^ 0x6a09e667f3bcc909L,
                point.x(), point.z(), broadScale);
        double localNoise = valueNoise(state.shapeSeed ^ 0xbb67ae8584caa73bL,
                point.x(), point.z(), localScale);
        double angle = ((state.shapeSeed >>> 11) & 0xffffL) / 65535.0 * Math.PI * 2.0;
        double drift = (dx * Math.cos(angle) + dz * Math.sin(angle)) / radius;
        int neighbors = sameRegionNeighbors(state, point);
        LandUseTerrainField.Cell cell = state.terrain.cellAt(point.x(), point.z());
        double directional = directionalShapeBias(state.group.growthBias(), state.seed, point, radius);
        double jitter = coordinateNoise(state.shapeSeed ^ 0x3c6ef372fe94f82bL, point.x(), point.z()) * 0.08;
        return radial * 1.05
                + terrainStepCost(state.group, cell) * 0.38
                + broadNoise * 0.92
                + localNoise * 0.38
                - drift * 0.24
                - neighbors * 0.36
                + directional
                + jitter;
    }

    private static int sameRegionNeighbors(RegionState state, BlockPoint point) {
        int result = 0;
        for (int[] direction : DIRECTIONS) {
            if (state.cells.contains(new BlockPoint(point.x() + direction[0], point.z() + direction[1]))) {
                result++;
            }
        }
        return result;
    }

    private static double directionalShapeBias(LandUseSeedGroup.GrowthBias bias,
                                               BlockPoint seed,
                                               BlockPoint point,
                                               double radius) {
        if (bias == null || bias.mode() == LandUseSeedGroup.GrowthBiasMode.NEUTRAL) return 0.0;
        double dx = point.x() - seed.x();
        double dz = point.z() - seed.z();
        if (bias.mode() == LandUseSeedGroup.GrowthBiasMode.ALONG_WATER) {
            double cross = Math.abs(dx * bias.axisZ() - dz * bias.axisX());
            return cross / radius * 0.28;
        }
        double referenceX = bias.referencePoint().x() - seed.x();
        double referenceZ = bias.referencePoint().z() - seed.z();
        double length = Math.max(1.0, Math.hypot(referenceX, referenceZ));
        double projection = (dx * referenceX + dz * referenceZ) / length / radius;
        return bias.mode() == LandUseSeedGroup.GrowthBiasMode.TOWARD_REFERENCE
                ? -projection * 0.34 : projection * 0.34;
    }

    private static double terrainStepCost(LandUseSeedGroup group, LandUseTerrainField.Cell cell) {
        LandUseRule rule = group.rule();
        double value = rule.baseStepCost();
        value += rule.slopeCost() * group.terrainBias().slopeMultiplier() * Math.max(0, cell.slope()) / 10.0;
        value += rule.reliefCost() * group.terrainBias().reliefMultiplier()
                * Math.max(Math.max(0, cell.localRelief()), Math.max(0, cell.roughness())) / 10.0;
        if (cell.water()) value += rule.waterCost();
        String biome = cell.biomeId().toLowerCase(Locale.ROOT);
        if (biome.contains("forest") || biome.contains("taiga") || biome.contains("jungle")) {
            value += rule.forestAffinity();
        }
        if (!group.preferredPatchRefs().isEmpty()) {
            value *= group.preferredPatchRefs().contains(cell.landformPatchId()) ? 0.8 : 1.15;
        }
        return Math.max(0.1, value);
    }

    private static Set<BlockPoint> obstacles(List<LandUseSeedGroup> groups) {
        Set<BlockPoint> points = new HashSet<>();
        for (LandUseSeedGroup group : groups) {
            for (BlockBounds bounds : group.structureFootprints()) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                        points.add(new BlockPoint(x, z));
                    }
                }
            }
        }
        return points;
    }

    private static boolean passable(LandUseTerrainField.Cell cell) {
        return cell != null && cell.sampled() && cell.slope() < 45.0 && cell.localRelief() < 48.0;
    }

    private static boolean continuous(LandUseSeedGroup group, TerrainIndex terrain,
                                      BlockPoint from, BlockPoint to) {
        return LandscapeTerrainContinuity.allows(
                group.terrainBias().name(),
                terrain.cellAt(from.x(), from.z()),
                terrain.cellAt(to.x(), to.z())
        );
    }

    private static double valueNoise(long seed, int x, int z, int scale) {
        int gridX = Math.floorDiv(x, scale);
        int gridZ = Math.floorDiv(z, scale);
        double fractionX = Math.floorMod(x, scale) / (double) scale;
        double fractionZ = Math.floorMod(z, scale) / (double) scale;
        double smoothX = fractionX * fractionX * (3.0 - 2.0 * fractionX);
        double smoothZ = fractionZ * fractionZ * (3.0 - 2.0 * fractionZ);
        double top = lerp(coordinateNoise(seed, gridX, gridZ),
                coordinateNoise(seed, gridX + 1, gridZ), smoothX);
        double bottom = lerp(coordinateNoise(seed, gridX, gridZ + 1),
                coordinateNoise(seed, gridX + 1, gridZ + 1), smoothX);
        return lerp(top, bottom, smoothZ);
    }

    private static double coordinateNoise(long seed, int x, int z) {
        long mixed = mix64(seed ^ (long) x * 0x9e3779b97f4a7c15L ^ (long) z * 0xc2b2ae3d27d4eb4fL);
        return ((mixed >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }

    private static double lerp(double left, double right, double amount) {
        return left + (right - left) * amount;
    }

    private static long stableHash(String value) {
        long result = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            result ^= value.charAt(index);
            result *= 0x100000001b3L;
        }
        return mix64(result);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparingDouble(Candidate::score)
            .thenComparingDouble(Candidate::pathCost)
            .thenComparingInt(value -> value.point().z())
            .thenComparingInt(value -> value.point().x());
    private static final Comparator<BlockPoint> POINT_ORDER = Comparator.comparingInt(BlockPoint::z)
            .thenComparingInt(BlockPoint::x);

    private static final class RegionState {
        private final LandUseSeedGroup group;
        private final LandUseSeedGroup.GrowthRegion region;
        private BlockPoint seed;
        private final TerrainIndex terrain;
        private final long shapeSeed;
        private final int targetArea;
        private final Set<BlockPoint> capacityDomain;
        private final Set<BlockPoint> cells = new LinkedHashSet<>();
        private final Map<BlockPoint, FrontierPath> frontier = new HashMap<>();
        private final Map<BlockPoint, Candidate> scoredFrontier = new HashMap<>();
        private final TreeSet<Candidate> rankedFrontier = new TreeSet<>(CANDIDATE_ORDER);
        private final Set<BlockPoint> contestedFrontier = new HashSet<>();
        private final Set<BlockPoint> rejected = new HashSet<>();
        private boolean exhausted;

        private RegionState(LandUseSeedGroup group,
                            LandUseSeedGroup.GrowthRegion region,
                            BlockPoint seed,
                            TerrainIndex terrain,
                            long shapeSeed,
                            Set<BlockPoint> capacityDomain) {
            this.group = group;
            this.region = region;
            this.seed = seed;
            this.terrain = terrain;
            this.shapeSeed = shapeSeed;
            this.targetArea = region.preferredAreaBlocks();
            this.capacityDomain = Set.copyOf(capacityDomain == null ? Set.of() : capacityDomain);
        }

        private boolean allowed(BlockPoint point) {
            return capacityDomain.isEmpty() || capacityDomain.contains(point);
        }

        private double completionPriority() {
            return cells.size() / (double) Math.max(1, targetArea)
                    / Math.max(0.1, group.competitionWeight());
        }
    }

    private record FrontierPath(double pathCost) {
    }

    private record Candidate(BlockPoint point, double pathCost, double score) {
    }

    private record RelaySeed(BlockPoint start,
                             BlockPoint sourceFrontier,
                             String parentGroupId,
                             BlockPoint roadGap,
                             LandUseExpansionResult.OriginKind kind) {
        private static RelaySeed root(BlockPoint start) {
            return new RelaySeed(start, null, "", null, LandUseExpansionResult.OriginKind.ROOT_SOURCE);
        }
    }

    private record RelayInterface(BlockPoint source, BlockPoint roadGap) {
    }

    private static final class Counters {
        private int contested;
        private int blocked;
    }

    private static final class TerrainIndex {
        private final int step;
        private final Map<CellKey, LandUseTerrainField.Cell> cells = new HashMap<>();

        private TerrainIndex(LandUseTerrainField field) {
            this.step = field.cellStepBlocks();
            field.cells().forEach(cell -> cells.put(new CellKey(
                    Math.floorDiv(cell.blockMinX(), step), Math.floorDiv(cell.blockMinZ(), step)), cell));
        }

        private LandUseTerrainField.Cell cellAt(int x, int z) {
            return cells.get(new CellKey(Math.floorDiv(x, step), Math.floorDiv(z, step)));
        }
    }

    private record CellKey(int x, int z) {
    }
}
