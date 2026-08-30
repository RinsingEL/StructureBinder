package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.landuse.LandscapeTerrainContinuity;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Deterministic D4 terrain-fit reservation for required Landscape instances. */
public final class CityLandscapeCapacityReservationPlanner {
    public static final String SCHEMA_VERSION = "city_landscape_capacity_reservation_plan.v0.2";
    public static final int CANDIDATES_PER_INSTANCE = 32;
    public static final int SEARCH_NODE_LIMIT = 100_000;
    private static final int[][] DIRECTIONS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
    private static final double[] FAN_HALF_ANGLES = {0.0, Math.toRadians(34.0),
            Math.toRadians(54.0), Math.toRadians(66.0)};

    public Result plan(CityBlueprint blueprint, CityBlueprintReferenceCatalog catalog,
                       LandUseTerrainField terrain, JsonArray requiredAnchors) {
        return plan(blueprint, catalog, terrain, requiredAnchors, SEARCH_NODE_LIMIT);
    }

    public Result plan(CityBlueprint blueprint, CityBlueprintReferenceCatalog catalog,
                       LandUseTerrainField terrain, JsonArray requiredAnchors, int nodeLimit) {
        return plan(blueprint, catalog, terrain, requiredAnchors, nodeLimit, Map.of());
    }

    /**
     * Replans required landscape parcels after D4 freezes each group's percentage target.
     * The map is per landscape and expresses the desired area of one parcel.
     */
    public Result plan(CityBlueprint blueprint, CityBlueprintReferenceCatalog catalog,
                       LandUseTerrainField terrain, JsonArray requiredAnchors, int nodeLimit,
                       Map<String, Integer> desiredParcelAreas) {
        if (nodeLimit <= 0) {
            return new Result(false, "CITY_BLUEPRINT_LANDSCAPE_SEARCH_LIMIT_EXHAUSTED",
                    withRequiredAnchorHash(failurePlan(blueprint,
                            "CITY_BLUEPRINT_LANDSCAPE_SEARCH_LIMIT_EXHAUSTED", 0), requiredAnchors));
        }
        List<Subject> subjects = requiredSubjects(blueprint, catalog, requiredAnchors, desiredParcelAreas);
        if (subjects.isEmpty()) {
            return new Result(true, "", withRequiredAnchorHash(
                    successPlan(blueprint, 0, List.of(), List.of()), requiredAnchors));
        }
        Set<BlockPoint> structureCells = structureCells(requiredAnchors);
        Search search = new Search(subjects, terrain, structureCells, nodeLimit, blueprint.generationSeed());
        search.solve(0, new LinkedHashSet<>(), new ArrayList<>());
        if (search.limitExhausted) {
            String reason = "CITY_BLUEPRINT_LANDSCAPE_SEARCH_LIMIT_EXHAUSTED";
            return new Result(false, reason,
                    withRequiredAnchorHash(failurePlan(blueprint, reason, search.nodes), requiredAnchors));
        }
        return new Result(true, "",
                withRequiredAnchorHash(successPlan(blueprint, search.nodes, search.solution, subjects),
                        requiredAnchors));
    }

    private static List<Subject> requiredSubjects(CityBlueprint blueprint,
                                                  CityBlueprintReferenceCatalog catalog,
                                                  JsonArray anchors,
                                                  Map<String, Integer> desiredParcelAreas) {
        Map<String, CityBlueprint.ExtentClass> extentByGroup = new LinkedHashMap<>();
        for (CityBlueprint.Group group : blueprint.groups()) {
            extentByGroup.put(group.groupId(), group.extentClass());
        }
        Map<String, JsonObject> owners = new LinkedHashMap<>();
        Map<String, JsonObject> groupOwners = new LinkedHashMap<>();
        for (JsonElement element : anchors) {
            JsonObject anchor = element.getAsJsonObject();
            if (!"required".equalsIgnoreCase(text(anchor, "blueprintPlacementPhase"))) continue;
            owners.put(text(anchor, "placementGroupId") + '\u0000' + text(anchor, "blueprintStructureRef"), anchor);
            groupOwners.putIfAbsent(text(anchor, "placementGroupId"), anchor);
        }
        List<Subject> result = new ArrayList<>();
        for (CityBlueprint.Landscape landscape : blueprint.outdoorPlan().landscapes()) {
            if (!landscape.required()) continue;
            if (landscape.originMode() != CityBlueprint.LandscapeOriginMode.ATTACHED || landscape.owner() == null) {
                throw new IllegalArgumentException("CITY_BLUEPRINT_REQUIRED_LANDSCAPE_OWNER_INVALID:"
                        + landscape.landscapeId());
            }
            CityBlueprintReferenceCatalog.LandscapeProfile profile = catalog.landscapeProfiles()
                    .get(landscape.landscapeProfileRef());
            if (profile == null) throw new IllegalArgumentException("CITY_BLUEPRINT_LANDSCAPE_PROFILE_UNKNOWN");
            JsonObject owner = landscape.owner().groupOwned()
                    ? groupOwners.get(landscape.owner().groupId())
                    : owners.get(landscape.owner().groupId() + '\u0000'
                    + landscape.owner().requiredStructureRef());
            // The owning building may have been skipped as an unfit terrain member;
            // its attached landscape is skipped with it, without failing the city.
            if (owner == null) continue;
            CityBlueprint.ExtentClass ownerExtent = extentByGroup.get(landscape.owner().groupId());
            if (ownerExtent == null) {
                throw new IllegalArgumentException("CITY_BLUEPRINT_REQUIRED_LANDSCAPE_OWNER_INVALID:"
                        + landscape.landscapeId());
            }
            int defaultArea = Math.max(1, profile.baseArea(ownerExtent) / landscape.parcelCount());
            int requestedArea = desiredParcelAreas.getOrDefault(landscape.landscapeId(), defaultArea);
            // The profile maximum shapes the initial parcel. Once the city percentage baseline is
            // frozen, the requested area is the actual missing landscape share and may legitimately
            // exceed that initial-size hint; the GIS planning boundary remains the hard limit.
            int area = desiredParcelAreas.containsKey(landscape.landscapeId())
                    ? Math.max(profile.parcelStyle().parcelAreaMinBlocks(), requestedArea)
                    : Math.max(profile.parcelStyle().parcelAreaMinBlocks(),
                    Math.min(profile.parcelStyle().parcelAreaMaxBlocks(), requestedArea));
            for (int instance = 0; instance < landscape.instanceCount(); instance++) {
                result.add(new Subject(landscape, profile, owner, area, instance,
                        desiredParcelAreas.containsKey(landscape.landscapeId())));
            }
        }
        result.sort(Comparator.comparing((Subject subject) -> subject.landscape().landscapeId())
                .thenComparingInt(Subject::instanceOrdinal));
        return List.copyOf(result);
    }

    private static final class Search {
        private final List<Subject> subjects;
        private final LandUseTerrainField terrain;
        private final Set<BlockPoint> structureCells;
        private final int nodeLimit;
        private final long generationSeed;
        private final List<List<InstanceCandidate>> candidatesBySubject;
        private int nodes;
        private boolean limitExhausted;
        private List<InstanceCandidate> solution = List.of();
        private SolutionScore bestScore;

        private Search(List<Subject> subjects, LandUseTerrainField terrain, Set<BlockPoint> structureCells,
                       int nodeLimit, long generationSeed) {
            this.subjects = subjects;
            this.terrain = terrain;
            this.structureCells = structureCells;
            this.nodeLimit = nodeLimit;
            this.generationSeed = generationSeed;
            this.candidatesBySubject = new ArrayList<>(
                    java.util.Collections.nCopies(subjects.size(), null));
        }

        private void solve(int index, Set<BlockPoint> claimed, List<InstanceCandidate> selected) {
            if (nodes >= nodeLimit) {
                limitExhausted = true;
                return;
            }
            nodes++;
            if (index == subjects.size()) {
                SolutionScore score = solutionScore(selected, generationSeed);
                if (bestScore == null || score.compareTo(bestScore) < 0) {
                    bestScore = score;
                    solution = List.copyOf(selected);
                }
                return;
            }
            Subject subject = subjects.get(index);
            if (subject.owner() == null) return;
            List<InstanceCandidate> subjectCandidates = candidatesBySubject.get(index);
            if (subjectCandidates == null) {
                subjectCandidates = candidates(subject, subject.instanceOrdinal(), terrain, structureCells,
                        generationSeed);
                candidatesBySubject.set(index, subjectCandidates);
            }
            for (InstanceCandidate candidate : subjectCandidates) {
                if (!java.util.Collections.disjoint(claimed, candidate.cells())) continue;
                claimed.addAll(candidate.cells());
                selected.add(candidate);
                solve(index + 1, claimed, selected);
                selected.remove(selected.size() - 1);
                claimed.removeAll(candidate.cells());
                if (limitExhausted) return;
            }
            // A required Landscape expresses desired function, not city-wide atomic placement.
            // Terrain may reduce an instance to zero; preserve that outcome as a warning.
            solve(index + 1, claimed, selected);
        }
    }

    private static SolutionScore solutionScore(List<InstanceCandidate> instances, long generationSeed) {
        int claimedArea = instances.stream().mapToInt(instance -> instance.cells().size()).sum();
        int sourceDistance = instances.stream().mapToInt(InstanceCandidate::ownerSeedDistanceBlocks).sum();
        return new SolutionScore(-instances.size(), -claimedArea, sourceDistance,
                score(instances, generationSeed));
    }

    private static LayoutScore score(List<InstanceCandidate> instances, long generationSeed) {
        long aspectRatioPenalty = 0;
        int maximumTreeDepth = 0;
        int directionCoverage = 0;
        int branchPointCount = 0;
        long boundingArea = 0;
        StringBuilder identity = new StringBuilder(Long.toString(generationSeed));
        for (InstanceCandidate instance : instances) {
            BlockBounds bounds = bounds(instance.cells());
            int width = bounds.maxX() - bounds.minX() + 1;
            int depth = bounds.maxZ() - bounds.minZ() + 1;
            aspectRatioPenalty += 1_000L * Math.max(width, depth) / Math.max(1, Math.min(width, depth));
            boundingArea += (long) width * depth;

            Map<String, Integer> depths = new LinkedHashMap<>();
            Map<String, Integer> childCounts = new LinkedHashMap<>();
            Set<Integer> directions = new HashSet<>();
            Map<String, ParcelCapacity> parcelsById = new LinkedHashMap<>();
            for (ParcelCapacity parcel : instance.parcels()) {
                parcelsById.put(parcel.parcelId(), parcel);
                int parcelDepth = 0;
                BlockBounds parentBounds = bounds(instance.subject().owner());
                if (!parcel.parentParcelId().isBlank()) {
                    parcelDepth = depths.get(parcel.parentParcelId()) + 1;
                    childCounts.merge(parcel.parentParcelId(), 1, Integer::sum);
                    parentBounds = bounds(parcelsById.get(parcel.parentParcelId()).cells());
                }
                depths.put(parcel.parcelId(), parcelDepth);
                maximumTreeDepth = Math.max(maximumTreeDepth, parcelDepth);
                directions.add(relativeDirection(parentBounds, bounds(parcel.cells())));
            }
            directionCoverage += directions.size();
            branchPointCount += (int) childCounts.values().stream().filter(count -> count > 1).count();
            identity.append('|').append(instance.subject().landscape().landscapeId())
                    .append(':').append(instance.instanceOrdinal())
                    .append(':').append(instance.direction()).append(':').append(instance.topology());
        }
        String tieBreak = hash(new JsonPrimitive(identity.toString()));
        return new LayoutScore(aspectRatioPenalty, maximumTreeDepth, -directionCoverage,
                -branchPointCount, boundingArea, tieBreak);
    }

    private static int relativeDirection(BlockBounds parent, BlockBounds child) {
        double dx = centerX(child) - centerX(parent);
        double dz = centerZ(child) - centerZ(parent);
        double angle = Math.atan2(dz, dx);
        return Math.floorMod((int) Math.round(angle / (Math.PI / 4.0)), 8);
    }

    private static List<InstanceCandidate> candidates(Subject subject, int instanceOrdinal,
                                                       LandUseTerrainField terrain,
                                                       Set<BlockPoint> structureCells,
                                                       long generationSeed) {
        List<InstanceCandidate> result = new ArrayList<>();
        Set<String> fingerprints = new HashSet<>();
        BlockBounds owner = bounds(subject.owner());
        Set<BlockPoint> ownerCells = cells(owner);
        TerrainIndex terrainIndex = new TerrainIndex(terrain);
        int count = subject.landscape().parcelCount();
        boolean oneBlockSeparator = usesOneBlockSeparator(subject.landscape());
        int topologyStart = subject.percentageFill() ? Math.min(3, Math.max(0, count - 1)) : 0;
        int topologyEnd = subject.percentageFill() ? topologyStart + 1 : 4;
        int rootVariantCount = subject.percentageFill() ? 1 : 2;
        for (int directionIndex = 0; directionIndex < 4; directionIndex++) {
            for (int topology = topologyStart; topology < topologyEnd; topology++) {
              for (int rootVariant = 0; rootVariant < rootVariantCount; rootVariant++) {
                List<ParcelCapacity> parcels = new ArrayList<>();
                Set<BlockPoint> cells = new LinkedHashSet<>();
                boolean valid = true;
                for (int ordinal = 0; ordinal < count; ordinal++) {
                    int parentIndex = parentIndexFor(topology, ordinal);
                    ParcelCapacity parentParcel = parentIndex < 0 ? null : parcels.get(parentIndex);
                    String parentId = parentParcel == null ? "" : parentParcel.parcelId();
                    Set<BlockPoint> parentCells = parentParcel == null ? ownerCells : parentParcel.cells();
                    Set<BlockPoint> nonParentCells = new HashSet<>(cells);
                    if (parentParcel != null) nonParentCells.removeAll(parentParcel.cells());
                    Set<BlockPoint> forbiddenAdjacency = oneBlockSeparator && parentParcel != null
                            ? new HashSet<>(cells) : nonParentCells;
                    int separatorWidthBlocks = oneBlockSeparator && parentParcel != null ? 1 : 0;
                    double bearing = bearing(directionIndex, topology, ordinal);
                    long shapeSeed = stableHash(generationSeed + ":" + subject.landscape().landscapeId()
                            + ':' + instanceOrdinal + ':' + directionIndex + ':' + topology + ':' + ordinal);
                    GrowthMask grown = forceGrowAdjacent(subject, terrainIndex, parentCells, structureCells, cells,
                            forbiddenAdjacency, bearing, shapeSeed, subject.parcelArea(),
                            parentParcel == null, rootVariant == 1,
                            parentParcel == null && rootVariant == 0 ? 1 : parentParcel == null ? 0 : 1,
                            separatorWidthBlocks);
                    if (grown == null) {
                        if (parentParcel == null) valid = false;
                        break;
                    }
                    Set<BlockPoint> mask = grown.cells();
                    String parcelId = subject.landscape().landscapeId() + "::instance_"
                            + String.format(java.util.Locale.ROOT, "%02d", instanceOrdinal + 1)
                            + "::parcel_" + String.format(java.util.Locale.ROOT, "%02d", ordinal + 1);
                    int sharedActual = ordinal == 0 ? 0 : sharedBoundary(mask, parentParcel.cells());
                    int separatedActual = ordinal == 0 ? 0
                            : separatedBoundary(mask, parentParcel.cells(), separatorWidthBlocks);
                    if (ordinal > 0 && (separatorWidthBlocks == 0 ? sharedActual < 1 : separatedActual < 1)) {
                        valid = false;
                        break;
                    }
                    for (int prior = 0; prior < parcels.size(); prior++) {
                        if (prior != parentIndex && sharedBoundary(mask, parcels.get(prior).cells()) > 0) {
                            valid = false;
                            break;
                        }
                    }
                    if (!valid) break;
                    parcels.add(new ParcelCapacity(parcelId, parentId, mask, grown.seed(), sharedActual,
                            separatorWidthBlocks, separatedActual));
                    cells.addAll(mask);
                }
                String fingerprint = parcels.stream()
                        .map(parcel -> parcel.parentParcelId() + '=' + maskFingerprint(parcel.cells()))
                        .collect(java.util.stream.Collectors.joining("|"));
                if (valid && fingerprints.add(fingerprint)) {
                    int ownerSeedDistance = distanceToBounds(parcels.get(0).seed(), owner);
                    result.add(new InstanceCandidate(subject, instanceOrdinal, directionIndex, topology,
                            List.copyOf(parcels), Set.copyOf(cells), ownerSeedDistance));
                }
              }
            }
        }
        return List.copyOf(result);
    }

    private static double bearing(int main, int topology, int ordinal) {
        int[] direction = DIRECTIONS[main];
        double base = Math.atan2(direction[1], direction[0]);
        if (ordinal == 0 || topology == 0) return base;
        int branches = branchCount(topology);
        int branch = (ordinal - 1) % branches;
        double half = FAN_HALF_ANGLES[topology];
        double offset = branches == 1 ? 0.0 : -half + (2.0 * half * branch) / (branches - 1);
        return base + offset;
    }

    private static int parentIndexFor(int topology, int ordinal) {
        if (ordinal == 0) return -1;
        int branches = branchCount(topology);
        return ordinal <= branches ? 0 : ordinal - branches;
    }

    private static int branchCount(int topology) {
        return Math.min(3, topology + 1);
    }

    private static GrowthMask forceGrowAdjacent(Subject subject,
                                                TerrainIndex terrain,
                                                Set<BlockPoint> sourceCells,
                                                Set<BlockPoint> structureCells,
                                                Set<BlockPoint> occupiedCells,
                                                Set<BlockPoint> forbiddenAdjacency,
                                                double bearing,
                                                long shapeSeed,
                                                int targetArea,
                                                boolean ownerSeededRoot,
                                                boolean allowDetachedRoot,
                                                int minimumSourceBoundary,
                                                int separatorWidthBlocks) {
        BlockBounds sourceBounds = bounds(sourceCells);
        double sourceCenterX = centerX(sourceBounds);
        double sourceCenterZ = centerZ(sourceBounds);
        BlockPoint seed = ownerSeededRoot
                ? (allowDetachedRoot
                        ? bestTerrainSeed(subject, terrain, structureCells, occupiedCells, forbiddenAdjacency,
                                sourceCenterX, sourceCenterZ, bearing, shapeSeed)
                        : bestBoundarySeed(subject, terrain, sourceCells, structureCells, occupiedCells,
                                forbiddenAdjacency, sourceCenterX, sourceCenterZ, bearing, shapeSeed))
                : (separatorWidthBlocks > 0
                        ? separatedBoundaryCandidates(sourceCells, separatorWidthBlocks)
                        : boundaryCandidates(sourceCells)).stream()
                        .filter(point -> eligible(point, terrain, structureCells, occupiedCells,
                                forbiddenAdjacency))
                        .filter(point -> separatorWidthBlocks > 0
                                ? touchesContinuousTerrainAcrossSeparator(subject, terrain, sourceCells,
                                point, separatorWidthBlocks)
                                : touchesContinuousTerrain(subject, terrain, sourceCells, point))
                        .min(Comparator.comparingDouble((BlockPoint point) -> seedScore(subject, terrain, point,
                                        sourceCenterX, sourceCenterZ, bearing, shapeSeed))
                                .thenComparingInt(BlockPoint::z).thenComparingInt(BlockPoint::x))
                        .orElse(null);
        if (seed == null) return null;

        Set<BlockPoint> result = new LinkedHashSet<>();
        Map<BlockPoint, Double> bestPath = new HashMap<>();
        PriorityQueue<GrowthNode> frontier = new PriorityQueue<>(Comparator
                .comparingDouble(GrowthNode::priority)
                .thenComparingDouble(GrowthNode::pathCost)
                .thenComparingInt(node -> node.point().z())
                .thenComparingInt(node -> node.point().x()));
        double firstCost = terrainStepCost(subject, terrain.cellAt(seed.x(), seed.z()));
        frontier.add(new GrowthNode(seed, firstCost, firstCost));
        bestPath.put(seed, firstCost);
        while (!frontier.isEmpty() && result.size() < targetArea) {
            GrowthNode node = frontier.poll();
            if (result.contains(node.point()) || !eligible(node.point(), terrain, structureCells,
                    occupiedCells, forbiddenAdjacency)) continue;
            double known = bestPath.getOrDefault(node.point(), Double.POSITIVE_INFINITY);
            if (node.pathCost() > known + 1.0e-9) continue;
            result.add(node.point());
            for (int[] direction : DIRECTIONS) {
                BlockPoint next = new BlockPoint(node.point().x() + direction[0], node.point().z() + direction[1]);
                if (result.contains(next) || !eligible(next, terrain, structureCells,
                        occupiedCells, forbiddenAdjacency)
                        || !continuous(subject, terrain, node.point(), next)) continue;
                double pathCost = node.pathCost() + terrainStepCost(subject, terrain.cellAt(next.x(), next.z()));
                if (pathCost + 1.0e-9 >= bestPath.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                bestPath.put(next, pathCost);
                double priority = growthPriority(subject, terrain, next, seed, sourceCells, result,
                        sourceCenterX, sourceCenterZ, bearing, shapeSeed, targetArea, pathCost);
                frontier.add(new GrowthNode(next, pathCost, priority));
            }
        }
        int sourceBoundary = separatorWidthBlocks > 0
                ? separatedBoundary(result, sourceCells, separatorWidthBlocks)
                : sharedBoundary(result, sourceCells);
        if (result.isEmpty() || sourceBoundary < minimumSourceBoundary) return null;
        return new GrowthMask(Set.copyOf(result), seed);
    }

    private static boolean usesOneBlockSeparator(CityBlueprint.Landscape landscape) {
        return landscape.purpose() == CityBlueprint.LandscapePurpose.FUNCTIONAL
                && landscape.fillSelection().variants().stream()
                .flatMap(variant -> variant.roleShares().stream())
                .anyMatch(role -> role.growthForm() == CityBlueprint.RegionGrowthForm.CORRIDOR
                        && "GROUND_PATH".equalsIgnoreCase(role.roleRef()));
    }

    private static BlockPoint bestBoundarySeed(Subject subject,
                                               TerrainIndex terrain,
                                               Set<BlockPoint> sourceCells,
                                               Set<BlockPoint> structureCells,
                                               Set<BlockPoint> occupiedCells,
                                               Set<BlockPoint> forbiddenAdjacency,
                                               double sourceCenterX,
                                               double sourceCenterZ,
                                               double bearing,
                                               long shapeSeed) {
        return boundaryCandidates(sourceCells).stream()
                .filter(point -> eligible(point, terrain, structureCells, occupiedCells, forbiddenAdjacency))
                .filter(point -> touchesContinuousTerrain(subject, terrain, sourceCells, point))
                .min(Comparator.comparingDouble((BlockPoint point) -> seedScore(subject, terrain, point,
                                sourceCenterX, sourceCenterZ, bearing, shapeSeed))
                        .thenComparingInt(BlockPoint::z).thenComparingInt(BlockPoint::x))
                .orElse(null);
    }

    private static BlockPoint bestTerrainSeed(Subject subject,
                                              TerrainIndex terrain,
                                              Set<BlockPoint> structureCells,
                                              Set<BlockPoint> occupiedCells,
                                              Set<BlockPoint> forbiddenAdjacency,
                                              double sourceCenterX,
                                              double sourceCenterZ,
                                              double bearing,
                                              long shapeSeed) {
        BlockPoint best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        BlockBounds bounds = terrain.bounds();
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                BlockPoint point = new BlockPoint(x, z);
                if (!eligible(point, terrain, structureCells, occupiedCells, forbiddenAdjacency)) continue;
                double distance = Math.hypot(x - sourceCenterX, z - sourceCenterZ);
                double score = seedScore(subject, terrain, point, sourceCenterX, sourceCenterZ,
                        bearing, shapeSeed) + distance * 0.0125;
                if (score < bestScore - 1.0e-9
                        || Math.abs(score - bestScore) <= 1.0e-9
                        && (best == null || z < best.z() || z == best.z() && x < best.x())) {
                    best = point;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private static double seedScore(Subject subject, TerrainIndex terrain, BlockPoint point,
                                    double sourceCenterX, double sourceCenterZ,
                                    double bearing, long shapeSeed) {
        double anglePenalty = anglePenalty(point.x() - sourceCenterX, point.z() - sourceCenterZ, bearing);
        double noise = valueNoise(shapeSeed ^ 0x9e3779b97f4a7c15L, point.x(), point.z(), 7);
        return anglePenalty * 5.0 + terrainStepCost(subject, terrain.cellAt(point.x(), point.z())) + noise * 0.35;
    }

    private static double growthPriority(Subject subject, TerrainIndex terrain, BlockPoint point,
                                         BlockPoint seed, Set<BlockPoint> sourceCells, Set<BlockPoint> result,
                                         double sourceCenterX, double sourceCenterZ, double bearing,
                                         long shapeSeed, int targetArea, double pathCost) {
        double radius = Math.max(2.0, Math.sqrt(targetArea / Math.PI));
        double dx = point.x() - seed.x();
        double dz = point.z() - seed.z();
        double projection = (dx * Math.cos(bearing) + dz * Math.sin(bearing)) / radius;
        double lateral = Math.abs(dx * Math.sin(bearing) - dz * Math.cos(bearing)) / radius;
        int sameNeighbors = adjacentCount(result, point);
        int sourceNeighbors = adjacentCount(sourceCells, point);
        // The seed is selected from the parent's legal boundary (or the detached-root case has
        // no contact requirement), so the minimum contact invariant is already established once.
        // Re-scanning the entire growing mask for every frontier node made large percentage-fill
        // parcels quadratic in their area.
        double contactReward = sourceNeighbors * 0.28;
        int broadScale = Math.max(6, (int) Math.round(radius));
        int localScale = Math.max(3, (int) Math.round(radius * 0.45));
        double broadNoise = valueNoise(shapeSeed ^ 0x6a09e667f3bcc909L,
                point.x(), point.z(), broadScale);
        double localNoise = valueNoise(shapeSeed ^ 0xbb67ae8584caa73bL,
                point.x(), point.z(), localScale);
        double sourceAnglePenalty = anglePenalty(point.x() - sourceCenterX, point.z() - sourceCenterZ, bearing);
        return pathCost * 0.44
                + Math.hypot(dx, dz) / radius * 0.74
                + lateral * 0.18
                + sourceAnglePenalty * 0.20
                - projection * 0.32
                - sameNeighbors * 0.72
                - contactReward
                + broadNoise * 0.82
                + localNoise * 0.34;
    }

    private static boolean eligible(BlockPoint point, TerrainIndex terrain,
                                    Set<BlockPoint> structureCells, Set<BlockPoint> occupiedCells,
                                    Set<BlockPoint> forbiddenAdjacency) {
        return terrain.bounds().contains(point.x(), point.z())
                && passable(terrain.cellAt(point.x(), point.z()))
                && !structureCells.contains(point)
                && !occupiedCells.contains(point)
                && !touches(forbiddenAdjacency, point);
    }

    private static boolean touches(Set<BlockPoint> cells, BlockPoint point) {
        if (cells.isEmpty()) return false;
        for (int[] direction : DIRECTIONS) {
            if (cells.contains(new BlockPoint(point.x() + direction[0], point.z() + direction[1]))) return true;
        }
        return false;
    }

    private static Set<BlockPoint> boundaryCandidates(Set<BlockPoint> sourceCells) {
        Set<BlockPoint> result = new LinkedHashSet<>();
        for (BlockPoint source : sourceCells) {
            for (int[] direction : DIRECTIONS) {
                BlockPoint point = new BlockPoint(source.x() + direction[0], source.z() + direction[1]);
                if (!sourceCells.contains(point)) result.add(point);
            }
        }
        return result;
    }

    private static Set<BlockPoint> separatedBoundaryCandidates(Set<BlockPoint> sourceCells,
                                                                int separatorWidthBlocks) {
        Set<BlockPoint> result = new LinkedHashSet<>();
        int distance = separatorWidthBlocks + 1;
        for (BlockPoint source : sourceCells) {
            for (int[] direction : DIRECTIONS) {
                BlockPoint point = new BlockPoint(source.x() + direction[0] * distance,
                        source.z() + direction[1] * distance);
                if (!sourceCells.contains(point)) result.add(point);
            }
        }
        return result;
    }

    private static double anglePenalty(double dx, double dz, double bearing) {
        double length = Math.max(1.0e-9, Math.hypot(dx, dz));
        double cosine = (dx * Math.cos(bearing) + dz * Math.sin(bearing)) / length;
        return 1.0 - Math.max(-1.0, Math.min(1.0, cosine));
    }

    private static int adjacentCount(Set<BlockPoint> cells, BlockPoint point) {
        int count = 0;
        for (int[] direction : DIRECTIONS) {
            if (cells.contains(new BlockPoint(point.x() + direction[0], point.z() + direction[1]))) count++;
        }
        return count;
    }

    private static boolean passable(LandUseTerrainField.Cell cell) {
        return cell != null && cell.sampled() && !cell.water()
                && cell.slope() < 45.0 && cell.localRelief() < 48.0;
    }

    private static boolean touchesContinuousTerrain(Subject subject, TerrainIndex terrain,
                                                    Set<BlockPoint> sourceCells, BlockPoint target) {
        for (int[] direction : DIRECTIONS) {
            BlockPoint source = new BlockPoint(target.x() + direction[0], target.z() + direction[1]);
            if (sourceCells.contains(source) && continuous(subject, terrain, source, target)) return true;
        }
        return false;
    }

    private static boolean touchesContinuousTerrainAcrossSeparator(Subject subject, TerrainIndex terrain,
                                                                    Set<BlockPoint> sourceCells,
                                                                    BlockPoint target,
                                                                    int separatorWidthBlocks) {
        int distance = separatorWidthBlocks + 1;
        for (int[] direction : DIRECTIONS) {
            BlockPoint source = new BlockPoint(target.x() + direction[0] * distance,
                    target.z() + direction[1] * distance);
            if (!sourceCells.contains(source)) continue;
            BlockPoint previous = source;
            boolean continuous = true;
            for (int step = 1; step <= distance; step++) {
                BlockPoint next = new BlockPoint(source.x() - direction[0] * step,
                        source.z() - direction[1] * step);
                if (!continuous(subject, terrain, previous, next)) {
                    continuous = false;
                    break;
                }
                previous = next;
            }
            if (continuous) return true;
        }
        return false;
    }

    private static boolean continuous(Subject subject, TerrainIndex terrain, BlockPoint from, BlockPoint to) {
        return LandscapeTerrainContinuity.allows(
                subject.landscape().terrainPolicy().name(),
                terrain.cellAt(from.x(), from.z()),
                terrain.cellAt(to.x(), to.z())
        );
    }

    private static double terrainStepCost(Subject subject, LandUseTerrainField.Cell cell) {
        if (!passable(cell)) return Double.POSITIVE_INFINITY;
        double terrainMultiplier = switch (subject.landscape().terrainPolicy()) {
            case CONFORM -> 1.35;
            case ASSERTIVE -> 0.65;
            default -> 1.0;
        };
        double relief = Math.max(Math.max(0.0, cell.localRelief()), Math.max(0.0, cell.roughness()));
        double cost = 1.0 + Math.max(0.0, cell.slope()) * 0.075 * terrainMultiplier
                + relief * 0.042 * terrainMultiplier;
        if (cell.water()) cost += 6.0;
        if (!subject.landscape().preferredPatchRefs().isEmpty()) {
            cost *= subject.landscape().preferredPatchRefs().contains(cell.landformPatchId()) ? 0.80 : 1.15;
        }
        return Math.max(0.1, cost);
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

    private static String maskFingerprint(Set<BlockPoint> cells) {
        return cells.stream().sorted(Comparator.comparingInt(BlockPoint::z).thenComparingInt(BlockPoint::x))
                .map(point -> point.x() + "," + point.z())
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static int sharedBoundary(Set<BlockPoint> left, Set<BlockPoint> right) {
        int count = 0;
        for (BlockPoint point : left) {
            if (right.contains(new BlockPoint(point.x() + 1, point.z()))
                    || right.contains(new BlockPoint(point.x() - 1, point.z()))
                    || right.contains(new BlockPoint(point.x(), point.z() + 1))
                    || right.contains(new BlockPoint(point.x(), point.z() - 1))) count++;
        }
        return count;
    }

    private static int separatedBoundary(Set<BlockPoint> left, Set<BlockPoint> right,
                                         int separatorWidthBlocks) {
        if (separatorWidthBlocks <= 0) return sharedBoundary(left, right);
        int distance = separatorWidthBlocks + 1;
        int count = 0;
        for (BlockPoint point : left) {
            if (right.contains(new BlockPoint(point.x() + distance, point.z()))
                    || right.contains(new BlockPoint(point.x() - distance, point.z()))
                    || right.contains(new BlockPoint(point.x(), point.z() + distance))
                    || right.contains(new BlockPoint(point.x(), point.z() - distance))) count++;
        }
        return count;
    }

    private static Set<BlockPoint> structureCells(JsonArray anchors) {
        Set<BlockPoint> result = new HashSet<>();
        for (JsonElement element : anchors) {
            BlockBounds bounds = bounds(element.getAsJsonObject());
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x <= bounds.maxX(); x++) result.add(new BlockPoint(x, z));
            }
        }
        return result;
    }

    private static Set<BlockPoint> cells(BlockBounds bounds) {
        Set<BlockPoint> result = new LinkedHashSet<>();
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) result.add(new BlockPoint(x, z));
        }
        return result;
    }

    private static BlockBounds bounds(JsonObject anchor) {
        JsonObject value = anchor.has("actualFootprint") ? anchor.getAsJsonObject("actualFootprint")
                : anchor.has("lockedActualFootprint") ? anchor.getAsJsonObject("lockedActualFootprint")
                : anchor.has("plannedFootprint") ? anchor.getAsJsonObject("plannedFootprint")
                : anchor.has("collisionEnvelope") ? anchor.getAsJsonObject("collisionEnvelope")
                : anchor.has("groupCollisionEnvelope") ? anchor.getAsJsonObject("groupCollisionEnvelope")
                : anchor;
        return new BlockBounds(value.get("minX").getAsInt(), value.get("minZ").getAsInt(),
                value.get("maxX").getAsInt(), value.get("maxZ").getAsInt());
    }

    private static BlockBounds bounds(Set<BlockPoint> cells) {
        return new BlockBounds(cells.stream().mapToInt(BlockPoint::x).min().orElseThrow(),
                cells.stream().mapToInt(BlockPoint::z).min().orElseThrow(),
                cells.stream().mapToInt(BlockPoint::x).max().orElseThrow(),
                cells.stream().mapToInt(BlockPoint::z).max().orElseThrow());
    }

    private static double centerX(BlockBounds bounds) {
        return (bounds.minX() + bounds.maxX()) / 2.0;
    }

    private static double centerZ(BlockBounds bounds) {
        return (bounds.minZ() + bounds.maxZ()) / 2.0;
    }

    private static int distanceToBounds(BlockPoint point, BlockBounds bounds) {
        int dx = point.x() < bounds.minX() ? bounds.minX() - point.x()
                : point.x() > bounds.maxX() ? point.x() - bounds.maxX() : 0;
        int dz = point.z() < bounds.minZ() ? bounds.minZ() - point.z()
                : point.z() > bounds.maxZ() ? point.z() - bounds.maxZ() : 0;
        return dx + dz;
    }

    private static JsonObject successPlan(CityBlueprint blueprint, int nodes,
                                          List<InstanceCandidate> instances,
                                          List<Subject> subjects) {
        JsonObject root = basePlan(blueprint, "reserved", nodes);
        LayoutScore layoutScore = score(instances, blueprint.generationSeed());
        root.addProperty("selectionPolicy", "MAXIMIZE_TERRAIN_FIT_THEN_BEST_LAYOUT");
        root.addProperty("patchBoundaryPolicy", "SOFT_PREFERENCE_ALLOW_OUTSIDE");
        root.addProperty("attachedOriginPolicy", "OWNER_SEEDED_TERRAIN_FIT");
        root.add("layoutScore", layoutScore.asJson());
        JsonArray values = new JsonArray();
        for (InstanceCandidate instance : instances) {
            JsonObject value = new JsonObject();
            String instanceId = instance.parcels().get(0).parcelId().replaceAll("::parcel_[0-9]+$", "");
            value.addProperty("landscapeId", instance.subject().landscape().landscapeId());
            value.addProperty("landscapeInstanceId", instanceId);
            value.addProperty("profileRef", instance.subject().landscape().landscapeProfileRef());
            value.addProperty("ownerGroupId", instance.subject().landscape().owner().groupId());
            value.addProperty("ownerRequiredStructureRef",
                    instance.subject().landscape().owner().requiredStructureRef());
            value.addProperty("ownershipScope", instance.subject().landscape().owner().groupOwned()
                    ? "FUNCTION_AREA" : "STRUCTURE_SEEDED");
            value.addProperty("ownerAnchorId", text(instance.subject().owner(), "anchorId"));
            value.add("ownerFootprint", footprintJson(bounds(instance.subject().owner())));
            value.addProperty("capacityCandidateId", instanceId + "::d" + instance.direction()
                    + "::t" + instance.topology());
            value.addProperty("directionVariant", instance.direction());
            value.addProperty("topologyVariant", instance.topology());
            value.addProperty("parcelCount", instance.parcels().size());
            value.addProperty("requestedParcelCount", instance.subject().landscape().parcelCount());
            value.addProperty("parcelAreaBlocks", instance.subject().parcelArea());
            value.addProperty("actualAreaBlocks", instance.cells().size());
            value.addProperty("ownerSeedDistanceBlocks", instance.ownerSeedDistanceBlocks());
            value.addProperty("capacityStatus", instance.parcels().size()
                    == instance.subject().landscape().parcelCount()
                    && instance.parcels().stream().allMatch(parcel ->
                    parcel.cells().size() == instance.subject().parcelArea()) ? "reserved" : "terrain_reduced");
            JsonArray parcels = new JsonArray();
            for (ParcelCapacity parcel : instance.parcels()) {
                JsonObject item = new JsonObject();
                item.addProperty("parcelId", parcel.parcelId());
                item.addProperty("parentParcelId", parcel.parentParcelId());
                item.addProperty("rootSource", parcel.parentParcelId().isBlank()
                        ? "owner_seeded_terrain_candidate" : "parent_parcel_boundary");
                item.addProperty("sharedBoundaryBlocks", parcel.sharedBoundaryBlocks());
                item.addProperty("separatorWidthBlocks", parcel.separatorWidthBlocks());
                item.addProperty("separatedBoundaryBlocks", parcel.separatedBoundaryBlocks());
                item.addProperty("targetAreaBlocks", instance.subject().parcelArea());
                item.addProperty("actualAreaBlocks", parcel.cells().size());
                JsonObject seed = new JsonObject();
                seed.addProperty("x", parcel.seed().x());
                seed.addProperty("z", parcel.seed().z());
                item.add("seed", seed);
                JsonObject proof = new JsonObject();
                proof.addProperty("source", parcel.parentParcelId().isBlank()
                        ? "owner_seed" : parcel.parentParcelId());
                proof.addProperty("minimumBlocks", parcel.parentParcelId().isBlank()
                        ? 0 : 1);
                proof.addProperty("actualBlocks", parcel.parentParcelId().isBlank()
                        ? 0 : parcel.separatorWidthBlocks() > 0
                        ? parcel.separatedBoundaryBlocks() : parcel.sharedBoundaryBlocks());
                proof.addProperty("relation", parcel.separatorWidthBlocks() > 0
                        ? "ONE_BLOCK_SEPARATOR" : "SHARED_BOUNDARY");
                item.add("sharedBoundaryProof", proof);
                item.add("reservationSpans", spans(parcel.cells()));
                parcels.add(item);
            }
            value.add("parcelReservations", parcels);
            value.add("reservationSpans", spans(instance.cells()));
            values.add(value);
        }
        root.add("instances", values);
        root.add("failures", new JsonArray());
        JsonArray warnings = new JsonArray();
        Set<String> selected = instances.stream().map(instance -> subjectKey(instance.subject()))
                .collect(java.util.stream.Collectors.toSet());
        for (Subject subject : subjects) {
            if (!selected.contains(subjectKey(subject))) {
                warnings.add(warning(subject, "REQUIRED_LANDSCAPE_NO_TERRAIN_FIT_WARNING",
                        "No terrain-gated cell was available; D4 continues without this Landscape instance."));
                continue;
            }
            InstanceCandidate instance = instances.stream()
                    .filter(candidate -> subjectKey(candidate.subject()).equals(subjectKey(subject)))
                    .findFirst().orElseThrow();
            if (instance.parcels().size() < subject.landscape().parcelCount()
                    || instance.parcels().stream().anyMatch(parcel -> parcel.cells().size() < subject.parcelArea())) {
                warnings.add(warning(subject, "REQUIRED_LANDSCAPE_TERRAIN_REDUCED_WARNING",
                        "Terrain reduced requested Parcel count or area; reserved cells remain valid."));
            }
        }
        root.add("warnings", warnings);
        refreshPlanHash(root);
        return root;
    }

    private static String subjectKey(Subject subject) {
        return subject.landscape().landscapeId() + '\u0000' + subject.instanceOrdinal();
    }

    private static JsonObject warning(Subject subject, String reasonCode, String message) {
        JsonObject warning = new JsonObject();
        warning.addProperty("reasonCode", reasonCode);
        warning.addProperty("landscapeId", subject.landscape().landscapeId());
        warning.addProperty("instanceOrdinal", subject.instanceOrdinal());
        warning.addProperty("message", message);
        return warning;
    }

    private static JsonObject failurePlan(CityBlueprint blueprint, String reason, int nodes) {
        JsonObject root = basePlan(blueprint, "failed", nodes);
        root.add("instances", new JsonArray());
        JsonArray failures = new JsonArray();
        failures.add(reason);
        root.add("failures", failures);
        refreshPlanHash(root);
        return root;
    }

    private static JsonObject basePlan(CityBlueprint blueprint, String status, int nodes) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("cityId", blueprint.cityId());
        root.addProperty("sourceBlueprintHash", hash(new CityBlueprintCodec().write(blueprint)));
        root.addProperty("status", status);
        root.addProperty("searchNodeCount", nodes);
        root.add("warnings", new JsonArray());
        return root;
    }

    public static void refreshPlanHash(JsonObject plan) {
        plan.remove("planHash");
        plan.addProperty("planHash", hash(plan));
    }

    private static JsonObject withRequiredAnchorHash(JsonObject plan, JsonArray requiredAnchors) {
        plan.addProperty("sourceD4Hash", hash(requiredAnchors));
        refreshPlanHash(plan);
        return plan;
    }

    private static String hash(JsonElement value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical(value).toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static JsonElement canonical(JsonElement value) {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) return value;
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            value.getAsJsonArray().forEach(element -> result.add(canonical(element)));
            return result;
        }
        JsonObject result = new JsonObject();
        value.getAsJsonObject().keySet().stream().sorted()
                .forEach(key -> result.add(key, canonical(value.getAsJsonObject().get(key))));
        return result;
    }

    private static JsonObject footprintJson(BlockBounds bounds) {
        JsonObject value = new JsonObject();
        value.addProperty("minX", bounds.minX());
        value.addProperty("minZ", bounds.minZ());
        value.addProperty("maxX", bounds.maxX());
        value.addProperty("maxZ", bounds.maxZ());
        return value;
    }

    private static JsonArray spans(Set<BlockPoint> cells) {
        Map<Integer, List<Integer>> rows = new java.util.TreeMap<>();
        cells.forEach(point -> rows.computeIfAbsent(point.z(), ignored -> new ArrayList<>()).add(point.x()));
        JsonArray spans = new JsonArray();
        for (Map.Entry<Integer, List<Integer>> row : rows.entrySet()) {
            row.getValue().sort(Integer::compareTo);
            int start = row.getValue().get(0);
            int end = start;
            for (int index = 1; index <= row.getValue().size(); index++) {
                int next = index < row.getValue().size() ? row.getValue().get(index) : Integer.MAX_VALUE;
                if (next == end + 1) {
                    end = next;
                    continue;
                }
                JsonObject span = new JsonObject();
                span.addProperty("z", row.getKey());
                span.addProperty("minX", start);
                span.addProperty("maxX", end);
                spans.add(span);
                start = next;
                end = next;
            }
        }
        return spans;
    }

    private static String text(JsonObject object, String key) {
        return object != null && object.has(key) ? object.get(key).getAsString() : "";
    }

    public record Result(boolean ok, String reasonCode, JsonObject plan) {
    }

    private record Subject(CityBlueprint.Landscape landscape,
                           CityBlueprintReferenceCatalog.LandscapeProfile profile,
                           JsonObject owner, int parcelArea, int instanceOrdinal,
                           boolean percentageFill) {
    }

    private record ParcelCapacity(String parcelId, String parentParcelId, Set<BlockPoint> cells,
                                  BlockPoint seed,
                                  int sharedBoundaryBlocks,
                                  int separatorWidthBlocks,
                                  int separatedBoundaryBlocks) {
    }

    private record GrowthMask(Set<BlockPoint> cells, BlockPoint seed) {
    }

    private record GrowthNode(BlockPoint point, double pathCost, double priority) {
    }

    private record CellKey(int x, int z) {
    }

    private static final class TerrainIndex {
        private final BlockBounds bounds;
        private final int step;
        private final Map<CellKey, LandUseTerrainField.Cell> cells = new HashMap<>();

        private TerrainIndex(LandUseTerrainField terrain) {
            this.bounds = terrain.planningBounds();
            this.step = terrain.cellStepBlocks();
            for (LandUseTerrainField.Cell cell : terrain.cells()) {
                cells.put(new CellKey(Math.floorDiv(cell.blockMinX(), step),
                        Math.floorDiv(cell.blockMinZ(), step)), cell);
            }
        }

        private BlockBounds bounds() {
            return bounds;
        }

        private LandUseTerrainField.Cell cellAt(int x, int z) {
            return cells.get(new CellKey(Math.floorDiv(x, step), Math.floorDiv(z, step)));
        }
    }

    private record InstanceCandidate(Subject subject, int instanceOrdinal, int direction, int topology,
                                     List<ParcelCapacity> parcels, Set<BlockPoint> cells,
                                     int ownerSeedDistanceBlocks) {
    }

    private record SolutionScore(int negativeInstanceCount, int negativeClaimedArea,
                                 int ownerSeedDistanceBlocks,
                                 LayoutScore layoutScore) implements Comparable<SolutionScore> {
        @Override
        public int compareTo(SolutionScore other) {
            int comparison = Integer.compare(negativeInstanceCount, other.negativeInstanceCount);
            if (comparison != 0) return comparison;
            comparison = Integer.compare(negativeClaimedArea, other.negativeClaimedArea);
            if (comparison != 0) return comparison;
            comparison = Integer.compare(ownerSeedDistanceBlocks, other.ownerSeedDistanceBlocks);
            if (comparison != 0) return comparison;
            return layoutScore.compareTo(other.layoutScore);
        }
    }

    private record LayoutScore(long aspectRatioPenalty, int maximumTreeDepth,
                               int negativeDirectionCoverage, int negativeBranchPointCount,
                               long boundingArea, String tieBreak) implements Comparable<LayoutScore> {
        @Override
        public int compareTo(LayoutScore other) {
            int comparison = Integer.compare(maximumTreeDepth, other.maximumTreeDepth);
            if (comparison != 0) return comparison;
            comparison = Integer.compare(negativeDirectionCoverage, other.negativeDirectionCoverage);
            if (comparison != 0) return comparison;
            comparison = Long.compare(aspectRatioPenalty, other.aspectRatioPenalty);
            if (comparison != 0) return comparison;
            comparison = Integer.compare(negativeBranchPointCount, other.negativeBranchPointCount);
            if (comparison != 0) return comparison;
            comparison = Long.compare(boundingArea, other.boundingArea);
            if (comparison != 0) return comparison;
            return tieBreak.compareTo(other.tieBreak);
        }

        private JsonObject asJson() {
            JsonObject value = new JsonObject();
            value.addProperty("aspectRatioPenalty", aspectRatioPenalty);
            value.addProperty("maximumTreeDepth", maximumTreeDepth);
            value.addProperty("directionCoverage", -negativeDirectionCoverage);
            value.addProperty("branchPointCount", -negativeBranchPointCount);
            value.addProperty("boundingArea", boundingArea);
            value.addProperty("seededTieBreak", tieBreak);
            return value;
        }
    }
}
