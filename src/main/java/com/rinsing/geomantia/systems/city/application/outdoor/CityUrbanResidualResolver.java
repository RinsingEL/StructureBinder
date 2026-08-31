package com.rinsing.geomantia.systems.city.application.outdoor;

import com.rinsing.geomantia.systems.city.algorithm.landuse.LandUseExpansionResult;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves only the compact urban envelope; landscape claims never enlarge it. */
public final class CityUrbanResidualResolver {
    private static final int[][] DIRECTIONS = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}};

    public Result resolve(String cityId,
                          BlockBounds planningBounds,
                          LandUseTerrainField terrain,
                          List<LandUseSeedGroup> groups,
                          List<LandUseAreaPlan.CorridorExclusion> corridors,
                          LandUseExpansionResult expansion,
                          Config config) {
        if (config == null || !config.enabled()) {
            return new Result(expansion, CityUrbanSpacePlan.disabled(cityId), List.of());
        }
        if (config.closeRadiusBlocks() <= 0) {
            throw new IllegalArgumentException("CITY_URBAN_ENVELOPE_CLOSE_RADIUS_REQUIRED");
        }
        Set<BlockPoint> urbanClaims = new HashSet<>();
        for (Map.Entry<BlockPoint, LandUseExpansionResult.Claim> entry : expansion.claims().entrySet()) {
            if (config.urbanGroupIds().contains(entry.getValue().groupId())) urbanClaims.add(entry.getKey());
        }
        Set<BlockPoint> urbanStructures = rasterize(config.urbanStructureFootprints(), planningBounds);
        Set<BlockPoint> initialSupport = new HashSet<>(urbanClaims);
        initialSupport.addAll(urbanStructures);
        if (initialSupport.isEmpty()) {
            throw new IllegalArgumentException("CITY_URBAN_ENVELOPE_SUPPORT_REQUIRED");
        }
        Set<BlockPoint> nearbyCorridors = nearbyCorridors(corridors, initialSupport,
                config.closeRadiusBlocks(), planningBounds);
        Set<BlockPoint> support = new HashSet<>(initialSupport);
        support.addAll(nearbyCorridors);
        Set<BlockPoint> envelope = close(support, config.closeRadiusBlocks(), planningBounds);
        envelope.addAll(support);
        envelope = componentsContaining(envelope, urbanStructures.isEmpty() ? urbanClaims : urbanStructures);
        if (envelope.isEmpty()) throw new IllegalArgumentException("CITY_URBAN_ENVELOPE_EMPTY");

        List<String> warnings = new ArrayList<>();
        if (touchesPlanningBoundary(envelope, planningBounds)) {
            warnings.add("CITY_URBAN_ENVELOPE_TOUCHES_PLANNING_BOUNDARY");
        }

        Set<BlockPoint> allStructures = rasterize(groups.stream()
                .flatMap(group -> group.structureFootprints().stream()).distinct().toList(), planningBounds);
        Set<BlockPoint> allCorridors = rasterize(corridors.stream()
                .map(LandUseAreaPlan.CorridorExclusion::blockBounds).toList(), planningBounds);
        Set<BlockPoint> residual = new HashSet<>(envelope);
        residual.removeAll(expansion.claims().keySet());
        residual.removeAll(allStructures);
        residual.removeAll(allCorridors);

        Map<BlockPoint, LandUseExpansionResult.Claim> resolvedClaims = new HashMap<>(expansion.claims());
        Map<String, Integer> resolvedGroupCounts = new HashMap<>(expansion.claimedBlocksByGroup());
        Map<String, Integer> groupMaxAreas = new HashMap<>();
        groups.forEach(group -> groupMaxAreas.put(group.groupId(), group.maxAreaBlocks()));
        TerrainIndex terrainIndex = new TerrainIndex(terrain);
        List<CityUrbanSpacePlan.ResidualRegion> regions = new ArrayList<>();
        int absorbed = 0;
        int explicit = 0;
        int ordinal = 0;
        for (Set<BlockPoint> component : components(residual)) {
            BlockPoint first = component.stream().min(POINT_ORDER).orElseThrow();
            boolean terrainDominated = component.stream().filter(point -> terrainBlocked(terrainIndex.cellAt(point)))
                    .count() * 2 >= component.size();
            boolean touchesEdge = touchesEnvelopeEdge(component, envelope);
            CityUrbanSpacePlan.ResidualClass residualClass = classify(component, terrainDominated, touchesEdge,
                    config.closeRadiusBlocks());
            CityUrbanSpacePlan.ResidualDisposition disposition = config.policy().forClass(residualClass);
            Map<String, Integer> adjacency = adjacentUrbanGroups(component, resolvedClaims, config.urbanGroupIds());
            List<String> adjacentGroups = adjacency.keySet().stream().sorted().toList();
            String absorbedGroupId = "";
            String residualId = "residual_" + (++ordinal) + '_' + first.x() + '_' + first.z();
            if (disposition == CityUrbanSpacePlan.ResidualDisposition.ABSORB_NEIGHBOR) {
                absorbedGroupId = adjacency.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue()
                                .reversed().thenComparing(Map.Entry::getKey))
                        .map(Map.Entry::getKey).findFirst()
                        .orElseGet(() -> nearestUrbanGroup(component, resolvedClaims, config.urbanGroupIds()));
                if (absorbedGroupId.isBlank()) {
                    throw new IllegalStateException("CITY_URBAN_RESIDUAL_HAS_NO_SPATIAL_OWNER:" + residualId);
                }
                double baseCost = minimumAdjacentCost(component, resolvedClaims, absorbedGroupId);
                for (BlockPoint point : component) {
                    resolvedClaims.put(point,
                            new LandUseExpansionResult.Claim(absorbedGroupId, baseCost + 1.0));
                }
                resolvedGroupCounts.merge(absorbedGroupId, component.size(), Integer::sum);
                absorbed += component.size();
            }
            if (disposition != CityUrbanSpacePlan.ResidualDisposition.ABSORB_NEIGHBOR) {
                explicit += component.size();
            }
            regions.add(new CityUrbanSpacePlan.ResidualRegion(residualId, residualClass, disposition,
                    scanlines(component), adjacentGroups, absorbedGroupId, component.size(), terrainDominated,
                    touchesEdge));
        }
        regions.sort(Comparator.comparing(CityUrbanSpacePlan.ResidualRegion::residualId));
        LandUseExpansionResult resolvedExpansion = new LandUseExpansionResult(resolvedClaims, resolvedGroupCounts,
                expansion.claimedBlocksByGrowthRegion(), expansion.effectiveSeedPointsByGroup(),
                expansion.expansionOriginsByGroup(),
                expansion.contestedClaimCount(),
                expansion.blockedCandidateCount());
        BlockBounds workingBounds = bounds(envelope);
        Set<BlockPoint> landUseOwned = inside(expansion.claims().keySet(), envelope);
        Set<BlockPoint> structureOwned = inside(allStructures, envelope);
        structureOwned.removeAll(landUseOwned);
        Set<BlockPoint> corridorOwned = inside(allCorridors, envelope);
        corridorOwned.removeAll(landUseOwned);
        corridorOwned.removeAll(structureOwned);
        int accounted = landUseOwned.size() + structureOwned.size() + corridorOwned.size() + absorbed + explicit;
        if (accounted != envelope.size()) {
            throw new IllegalStateException("CITY_URBAN_COVERAGE_INVARIANT_VIOLATED:"
                    + accounted + "!=" + envelope.size());
        }
        CityUrbanSpacePlan plan = new CityUrbanSpacePlan(CityUrbanSpacePlan.SCHEMA, cityId, "", true,
                config.closeRadiusBlocks(), workingBounds, scanlines(envelope), regions,
                new CityUrbanSpacePlan.CoverageSummary(envelope.size(), landUseOwned.size(),
                        structureOwned.size(), corridorOwned.size(), absorbed, explicit, 0))
                .withComputedHash();
        return new Result(resolvedExpansion, plan, List.copyOf(warnings));
    }

    private static Set<BlockPoint> close(Set<BlockPoint> source, int radius, BlockBounds planningBounds) {
        Set<BlockPoint> dilated = new HashSet<>(source);
        for (int step = 0; step < radius; step++) {
            Set<BlockPoint> next = new HashSet<>(dilated);
            for (BlockPoint point : dilated) {
                for (int[] direction : DIRECTIONS) {
                    BlockPoint neighbor = new BlockPoint(point.x() + direction[0], point.z() + direction[1]);
                    if (planningBounds.contains(neighbor.x(), neighbor.z())) next.add(neighbor);
                }
            }
            dilated = next;
        }
        Set<BlockPoint> eroded = dilated;
        for (int step = 0; step < radius; step++) {
            Set<BlockPoint> next = new HashSet<>();
            for (BlockPoint point : eroded) {
                boolean interior = true;
                for (int[] direction : DIRECTIONS) {
                    if (!eroded.contains(new BlockPoint(point.x() + direction[0], point.z() + direction[1]))) {
                        interior = false;
                        break;
                    }
                }
                if (interior) next.add(point);
            }
            eroded = next;
            if (eroded.isEmpty()) break;
        }
        return eroded;
    }

    private static Set<BlockPoint> nearbyCorridors(List<LandUseAreaPlan.CorridorExclusion> corridors,
                                                    Set<BlockPoint> support,
                                                    int radius,
                                                    BlockBounds planningBounds) {
        Set<BlockPoint> expandedSupport = new HashSet<>(support);
        for (int step = 0; step < radius; step++) {
            Set<BlockPoint> next = new HashSet<>(expandedSupport);
            for (BlockPoint point : expandedSupport) {
                for (int[] direction : DIRECTIONS) {
                    BlockPoint neighbor = new BlockPoint(point.x() + direction[0], point.z() + direction[1]);
                    if (planningBounds.contains(neighbor.x(), neighbor.z())) next.add(neighbor);
                }
            }
            expandedSupport = next;
        }
        Set<BlockPoint> result = new HashSet<>();
        for (LandUseAreaPlan.CorridorExclusion corridor : corridors) {
            Set<BlockPoint> cells = rasterize(List.of(corridor.blockBounds()), planningBounds);
            if (cells.stream().anyMatch(expandedSupport::contains)) result.addAll(cells);
        }
        return result;
    }

    private static Set<BlockPoint> componentsContaining(Set<BlockPoint> points, Set<BlockPoint> required) {
        Set<BlockPoint> result = new HashSet<>();
        for (Set<BlockPoint> component : components(points)) {
            if (component.stream().anyMatch(required::contains)) result.addAll(component);
        }
        return result;
    }

    private static List<Set<BlockPoint>> components(Set<BlockPoint> points) {
        Set<BlockPoint> remaining = new HashSet<>(points);
        List<Set<BlockPoint>> result = new ArrayList<>();
        while (!remaining.isEmpty()) {
            BlockPoint first = remaining.stream().min(POINT_ORDER).orElseThrow();
            Set<BlockPoint> component = new LinkedHashSet<>();
            ArrayDeque<BlockPoint> queue = new ArrayDeque<>();
            remaining.remove(first);
            queue.add(first);
            while (!queue.isEmpty()) {
                BlockPoint point = queue.removeFirst();
                component.add(point);
                for (int[] direction : DIRECTIONS) {
                    BlockPoint next = new BlockPoint(point.x() + direction[0], point.z() + direction[1]);
                    if (remaining.remove(next)) queue.addLast(next);
                }
            }
            result.add(component);
        }
        result.sort(Comparator.comparing(component -> component.stream().min(POINT_ORDER).orElseThrow(), POINT_ORDER));
        return result;
    }

    private static CityUrbanSpacePlan.ResidualClass classify(Set<BlockPoint> component,
                                                              boolean terrainDominated,
                                                              boolean touchesEdge,
                                                              int radius) {
        if (terrainDominated) return CityUrbanSpacePlan.ResidualClass.NATURAL_FEATURE;
        if (touchesEdge) return CityUrbanSpacePlan.ResidualClass.EXTERIOR_CONNECTED;
        BlockBounds bounds = bounds(component);
        int minorSpan = Math.min(bounds.widthBlocks(), bounds.heightBlocks());
        int smallLimit = Math.max(32, radius * radius / 2);
        if (component.size() <= smallLimit) return CityUrbanSpacePlan.ResidualClass.SMALL_ENCLOSED;
        if (minorSpan <= 3) return CityUrbanSpacePlan.ResidualClass.NARROW_GAP;
        if (component.size() <= radius * radius * 4) {
            return CityUrbanSpacePlan.ResidualClass.MEDIUM_ENCLOSED;
        }
        return CityUrbanSpacePlan.ResidualClass.LARGE_ENCLOSED;
    }

    private static Map<String, Integer> adjacentUrbanGroups(Set<BlockPoint> component,
                                                            Map<BlockPoint, LandUseExpansionResult.Claim> claims,
                                                            Set<String> urbanGroupIds) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (BlockPoint point : component) {
            for (int[] direction : DIRECTIONS) {
                LandUseExpansionResult.Claim claim = claims.get(
                        new BlockPoint(point.x() + direction[0], point.z() + direction[1]));
                if (claim != null && urbanGroupIds.contains(claim.groupId())) {
                    result.merge(claim.groupId(), 1, Integer::sum);
                }
            }
        }
        return result;
    }

    private static double minimumAdjacentCost(Set<BlockPoint> component,
                                              Map<BlockPoint, LandUseExpansionResult.Claim> claims,
                                              String groupId) {
        double result = Double.POSITIVE_INFINITY;
        for (BlockPoint point : component) {
            for (int[] direction : DIRECTIONS) {
                LandUseExpansionResult.Claim claim = claims.get(
                        new BlockPoint(point.x() + direction[0], point.z() + direction[1]));
                if (claim != null && groupId.equals(claim.groupId())) {
                    result = Math.min(result, claim.cumulativeCost());
                }
            }
        }
        return Double.isFinite(result) ? result : 0.0;
    }

    private static String nearestUrbanGroup(Set<BlockPoint> component,
                                            Map<BlockPoint, LandUseExpansionResult.Claim> claims,
                                            Set<String> urbanGroupIds) {
        BlockPoint origin = component.stream().min(POINT_ORDER).orElseThrow();
        return claims.entrySet().stream()
                .filter(entry -> urbanGroupIds.contains(entry.getValue().groupId()))
                .min(Comparator.comparingInt((Map.Entry<BlockPoint, LandUseExpansionResult.Claim> entry) ->
                                Math.abs(entry.getKey().x() - origin.x()) + Math.abs(entry.getKey().z() - origin.z()))
                        .thenComparing(entry -> entry.getValue().groupId()))
                .map(entry -> entry.getValue().groupId()).orElse("");
    }

    private static boolean terrainBlocked(LandUseTerrainField.Cell cell) {
        return cell == null || !cell.sampled() || cell.water() || cell.slope() >= 45.0 || cell.localRelief() >= 48.0;
    }

    private static boolean touchesEnvelopeEdge(Set<BlockPoint> component, Set<BlockPoint> envelope) {
        for (BlockPoint point : component) {
            for (int[] direction : DIRECTIONS) {
                if (!envelope.contains(new BlockPoint(point.x() + direction[0], point.z() + direction[1]))) return true;
            }
        }
        return false;
    }

    private static boolean touchesPlanningBoundary(Set<BlockPoint> points, BlockBounds bounds) {
        return points.stream().anyMatch(point -> point.x() == bounds.minX() || point.x() == bounds.maxX()
                || point.z() == bounds.minZ() || point.z() == bounds.maxZ());
    }

    private static int countInside(Iterable<BlockPoint> points, Set<BlockPoint> envelope) {
        int count = 0;
        for (BlockPoint point : points) if (envelope.contains(point)) count++;
        return count;
    }

    private static Set<BlockPoint> inside(Iterable<BlockPoint> points, Set<BlockPoint> envelope) {
        Set<BlockPoint> result = new HashSet<>();
        for (BlockPoint point : points) if (envelope.contains(point)) result.add(point);
        return result;
    }

    private static Set<BlockPoint> rasterize(List<BlockBounds> bounds, BlockBounds planningBounds) {
        Set<BlockPoint> result = new HashSet<>();
        for (BlockBounds value : bounds) {
            int minX = Math.max(value.minX(), planningBounds.minX());
            int maxX = Math.min(value.maxX(), planningBounds.maxX());
            int minZ = Math.max(value.minZ(), planningBounds.minZ());
            int maxZ = Math.min(value.maxZ(), planningBounds.maxZ());
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) result.add(new BlockPoint(x, z));
            }
        }
        return result;
    }

    private static List<LandUseAreaPlan.ScanlineSpan> scanlines(Set<BlockPoint> points) {
        Map<Integer, List<Integer>> byZ = new java.util.TreeMap<>();
        points.forEach(point -> byZ.computeIfAbsent(point.z(), ignored -> new ArrayList<>()).add(point.x()));
        List<LandUseAreaPlan.ScanlineSpan> result = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> entry : byZ.entrySet()) {
            List<Integer> xs = entry.getValue().stream().distinct().sorted().toList();
            if (xs.isEmpty()) continue;
            int start = xs.get(0);
            int previous = start;
            for (int index = 1; index < xs.size(); index++) {
                int x = xs.get(index);
                if (x != previous + 1) {
                    result.add(new LandUseAreaPlan.ScanlineSpan(entry.getKey(), start, previous));
                    start = x;
                }
                previous = x;
            }
            result.add(new LandUseAreaPlan.ScanlineSpan(entry.getKey(), start, previous));
        }
        return List.copyOf(result);
    }

    private static BlockBounds bounds(Set<BlockPoint> points) {
        return new BlockBounds(points.stream().mapToInt(BlockPoint::x).min().orElseThrow(),
                points.stream().mapToInt(BlockPoint::z).min().orElseThrow(),
                points.stream().mapToInt(BlockPoint::x).max().orElseThrow(),
                points.stream().mapToInt(BlockPoint::z).max().orElseThrow());
    }

    public record Result(LandUseExpansionResult expansion,
                         CityUrbanSpacePlan urbanSpacePlan,
                         List<String> warnings) {
        public Result {
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }
    }

    public record Config(boolean enabled,
                         int closeRadiusBlocks,
                         Set<String> urbanGroupIds,
                         List<BlockBounds> urbanStructureFootprints,
                         ResidualPolicy policy) {
        public Config {
            if (closeRadiusBlocks < 0) throw new IllegalArgumentException("closeRadiusBlocks must not be negative");
            urbanGroupIds = Set.copyOf(urbanGroupIds == null ? Set.of() : urbanGroupIds);
            urbanStructureFootprints = List.copyOf(
                    urbanStructureFootprints == null ? List.of() : urbanStructureFootprints);
            policy = policy == null ? ResidualPolicy.natural() : policy;
        }

        public static Config disabled() {
            return new Config(false, 0, Set.of(), List.of(), ResidualPolicy.natural());
        }
    }

    public record ResidualPolicy(CityUrbanSpacePlan.ResidualDisposition smallEnclosed,
                                 CityUrbanSpacePlan.ResidualDisposition narrowGap,
                                 CityUrbanSpacePlan.ResidualDisposition mediumEnclosed,
                                 CityUrbanSpacePlan.ResidualDisposition largeEnclosed,
                                 CityUrbanSpacePlan.ResidualDisposition exteriorConnected) {
        public ResidualPolicy {
            if (smallEnclosed == null || narrowGap == null || mediumEnclosed == null
                    || largeEnclosed == null || exteriorConnected == null) {
                throw new IllegalArgumentException("Every residual disposition is required");
            }
        }

        public static ResidualPolicy natural() {
            return new ResidualPolicy(CityUrbanSpacePlan.ResidualDisposition.NATURAL_RESERVE,
                    CityUrbanSpacePlan.ResidualDisposition.NATURAL_RESERVE,
                    CityUrbanSpacePlan.ResidualDisposition.NATURAL_RESERVE,
                    CityUrbanSpacePlan.ResidualDisposition.NATURAL_RESERVE,
                    CityUrbanSpacePlan.ResidualDisposition.NATURAL_RESERVE);
        }

        public static ResidualPolicy absorb() {
            return new ResidualPolicy(CityUrbanSpacePlan.ResidualDisposition.ABSORB_NEIGHBOR,
                    CityUrbanSpacePlan.ResidualDisposition.ABSORB_NEIGHBOR,
                    CityUrbanSpacePlan.ResidualDisposition.ABSORB_NEIGHBOR,
                    CityUrbanSpacePlan.ResidualDisposition.ABSORB_NEIGHBOR,
                    CityUrbanSpacePlan.ResidualDisposition.ABSORB_NEIGHBOR);
        }

        private CityUrbanSpacePlan.ResidualDisposition forClass(CityUrbanSpacePlan.ResidualClass value) {
            return switch (value) {
                case SMALL_ENCLOSED -> smallEnclosed;
                case NARROW_GAP -> narrowGap;
                case MEDIUM_ENCLOSED -> mediumEnclosed;
                case LARGE_ENCLOSED -> largeEnclosed;
                case EXTERIOR_CONNECTED -> exteriorConnected;
                case NATURAL_FEATURE -> smallEnclosed;
            };
        }
    }

    private static final Comparator<BlockPoint> POINT_ORDER = Comparator.comparingInt(BlockPoint::z)
            .thenComparingInt(BlockPoint::x);

    private static final class TerrainIndex {
        private final int step;
        private final Map<CellKey, LandUseTerrainField.Cell> cells = new HashMap<>();

        private TerrainIndex(LandUseTerrainField field) {
            step = field.cellStepBlocks();
            field.cells().forEach(cell -> cells.put(new CellKey(cell.cellX(), cell.cellZ()), cell));
        }

        private LandUseTerrainField.Cell cellAt(BlockPoint point) {
            return cells.get(new CellKey(Math.floorDiv(point.x(), step), Math.floorDiv(point.z(), step)));
        }
    }

    private record CellKey(int x, int z) {
    }
}
