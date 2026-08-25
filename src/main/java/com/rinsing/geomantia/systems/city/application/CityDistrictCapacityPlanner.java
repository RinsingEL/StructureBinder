package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary;
import com.rinsing.geomantia.systems.city.domain.model.PatchMemberCell;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reserves a connected, terrain-aware district domain before structure placement. */
final class CityDistrictCapacityPlanner {
    static final String SCHEMA_VERSION = "city_district_capacity_plan.v0.1";

    Result plan(List<CityBlueprint.Group> groups,
                Map<String, LandformPatchSummary> patches,
                Map<String, BlockBounds> formationBounds,
                Map<String, Set<String>> bufferExemptions,
                Map<String, String> algorithmsByGroup,
                Map<String, SpatialDemand> spatialDemands,
                LandUseTerrainField terrain,
                int cellStepBlocks,
                BlockBounds planningBounds,
                long generationSeed) {
        Map<String, Reservation> reservations = new LinkedHashMap<>();
        Set<CellKey> globallyReserved = new LinkedHashSet<>();
        JsonArray groupPlans = new JsonArray();
        for (CityBlueprint.Group group : groups) {
            Reservation reservation = reserve(group, patches, formationBounds.get(group.groupId()),
                    bufferExemptions.getOrDefault(group.groupId(), Set.of()), reservations,
                    globallyReserved, algorithmsByGroup.getOrDefault(group.groupId(), "COMPACT"), terrain,
                    spatialDemands.get(group.groupId()), cellStepBlocks, planningBounds, generationSeed);
            reservations.put(group.groupId(), reservation);
            reservation.cells().forEach(cell -> globallyReserved.add(key(cell)));
            groupPlans.add(reservation.asJson(cellStepBlocks));
            if (!reservation.ok()) {
                JsonObject plan = basePlan(groups, cellStepBlocks, planningBounds, groupPlans);
                return new Result(false, reservation.reasonCode(), reservation.message(), plan, reservations);
            }
        }
        JsonObject plan = basePlan(groups, cellStepBlocks, planningBounds, groupPlans);
        plan.addProperty("status", "reserved");
        return new Result(true, "", "", plan, reservations);
    }

    private Reservation reserve(CityBlueprint.Group group,
                                Map<String, LandformPatchSummary> patches,
                                BlockBounds formationBounds,
                                Set<String> bufferExemptions,
                                Map<String, Reservation> reservations,
                                Set<CellKey> globallyReserved,
                                String algorithm,
                                LandUseTerrainField terrain,
                                SpatialDemand spatialDemand,
                                int step,
                                BlockBounds planningBounds,
                                long generationSeed) {
        CityBlueprintGroupLayoutPlanner.PlacementMode placementMode =
                CityBlueprintGroupLayoutPlanner.PlacementMode.fromAlgorithm(algorithm);
        if (spatialDemand == null) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_GROUP_SPATIAL_DEMAND_MISSING:" + group.groupId());
        }
        CityBlueprint.PlacementRelation placement = group.placementRelation();
        BlockBounds capacitySeedBounds = formationBounds != null && !formationBounds.equals(planningBounds)
                ? formationBounds : null;
        if (placement != null && placement.kind() == CityBlueprint.PlacementRelationKind.BETWEEN_GROUPS) {
            return Reservation.deferred(group, placementMode, "DEFERRED_BETWEEN_GROUPS");
        }

        List<String> patchRefs = placement != null && !placement.patchRefs().isEmpty()
                ? placement.patchRefs() : group.preferredPatchRefs();
        Map<CellKey, PatchMemberCell> candidateCells = new LinkedHashMap<>();
        List<String> capacityPatchRefs = new ArrayList<>(patchRefs);
        for (String patchRef : patchRefs) {
            LandformPatchSummary patch = patches.get(patchRef);
            if (patch == null) continue;
            for (PatchMemberCell cell : patch.memberCells()) {
                BlockBounds cellBounds = cellBounds(cell, step);
                if (within(cellBounds, planningBounds) && usable(cell, terrain, group.terrainPolicy())) {
                    candidateCells.putIfAbsent(key(cell), cell);
                }
            }
        }
        int minimumArea = spatialDemand.minimumAreaBlocks();
        int targetArea = spatialDemand.targetAreaBlocks();
        int roadReserve = spatialDemand.roadReserveAreaBlocks();
        int targetWithRoad = targetArea + roadReserve;
        int minimumCells = ceilDiv(minimumArea, step * step);
        int targetCells = ceilDiv(targetWithRoad, step * step);
        int maximumCells = ceilDiv(spatialDemand.maximumAreaBlocks(), step * step);
        if (candidateCells.size() < minimumCells
                && (placement == null || placement.kind() != CityBlueprint.PlacementRelationKind.BETWEEN_PATCHES)) {
            for (Map.Entry<String, LandformPatchSummary> entry : patches.entrySet()) {
                if (capacityPatchRefs.contains(entry.getKey())) continue;
                for (PatchMemberCell cell : entry.getValue().memberCells()) {
                    BlockBounds cellBounds = cellBounds(cell, step);
                    if (within(cellBounds, planningBounds) && usable(cell, terrain, group.terrainPolicy())) {
                        candidateCells.putIfAbsent(key(cell), cell);
                    }
                }
                if (candidateCells.size() >= minimumCells) {
                    capacityPatchRefs.add(entry.getKey());
                    break;
                }
                capacityPatchRefs.add(entry.getKey());
            }
        }
        // Leave the final terrain-gate diagnostic to structure placement when the
        // entire domain is water or unsampled; no such cell enters the reservation.
        if (candidateCells.isEmpty()) {
            return Reservation.deferred(group, placementMode, "NO_USABLE_TERRAIN");
        }
        PatchMemberCell seedCell = chooseSeed(group, candidateCells.values(), step, generationSeed,
                capacitySeedBounds);
        if (seedCell == null) {
            return Reservation.failed(group, placementMode, capacityPatchRefs, minimumArea, targetArea,
                    spatialDemand.maximumAreaBlocks(), roadReserve, targetWithRoad,
                    "CITY_BLUEPRINT_GROUP_DISTRICT_CAPACITY_UNREACHABLE",
                    group.groupId() + " has no usable member cell for district capacity.");
        }

        Set<CellKey> blockedByExisting = new LinkedHashSet<>();
        for (PatchMemberCell cell : candidateCells.values()) {
            if (globallyReserved.contains(key(cell))) {
                blockedByExisting.add(key(cell));
                continue;
            }
            if (!separationAllowed(cell, reservations, bufferExemptions, step)) {
                blockedByExisting.add(key(cell));
            }
        }
        if (blockedByExisting.contains(key(seedCell))) {
            BlockPointLike seedOrigin = choosePoint(group, candidateCells.values(), step, capacitySeedBounds);
            seedCell = candidateCells.values().stream()
                    .filter(cell -> !blockedByExisting.contains(key(cell)))
                    .min(Comparator.comparingLong(cell -> distanceSquared(cell, seedOrigin)))
                    .orElse(null);
        }
        if (seedCell == null) {
            return Reservation.deferred(group, placementMode, "CAPACITY_GAP_RECORDED");
        }

        Set<CellKey> selected = new LinkedHashSet<>();
        Deque<PatchMemberCell> frontier = new ArrayDeque<>();
        frontier.add(seedCell);
        while (!frontier.isEmpty() && selected.size() < targetCells) {
            PatchMemberCell current = frontier.removeFirst();
            CellKey currentKey = key(current);
            if (!selected.add(currentKey)) continue;
            for (PatchMemberCell neighbor : neighbors(current, candidateCells, blockedByExisting, step, seedCell, group,
                    placementMode, formationBounds, terrain, seedFor(group, seedCell, generationSeed))) {
                if (!selected.contains(key(neighbor))) frontier.addLast(neighbor);
            }
        }
        if (selected.size() < minimumCells) {
            List<PatchMemberCell> gapCells = candidateCells.values().stream()
                    .filter(cell -> selected.contains(key(cell)))
                    .sorted(Comparator.comparingInt(PatchMemberCell::cellZ)
                            .thenComparingInt(PatchMemberCell::cellX))
                    .toList();
            return Reservation.successWithGap(group, placementMode, capacityPatchRefs, minimumArea, targetArea,
                    spatialDemand.maximumAreaBlocks(), roadReserve, targetWithRoad, gapCells, maximumCells,
                    group.groupId() + " reached " + selected.size() + " district cells but requires at least "
                            + minimumCells + ".");
        }
        List<PatchMemberCell> cells = candidateCells.values().stream()
                .filter(cell -> selected.contains(key(cell)))
                .sorted(Comparator.comparingInt(PatchMemberCell::cellZ)
                        .thenComparingInt(PatchMemberCell::cellX))
                .toList();
        return Reservation.success(group, placementMode, capacityPatchRefs, minimumArea, targetArea,
                spatialDemand.maximumAreaBlocks(), roadReserve, targetWithRoad, cells, maximumCells);
    }

    private static List<PatchMemberCell> neighbors(PatchMemberCell current,
                                                    Map<CellKey, PatchMemberCell> candidates,
                                                    Set<CellKey> blocked,
                                                    int step,
                                                    PatchMemberCell seed,
                                                    CityBlueprint.Group group,
                                                    CityBlueprintGroupLayoutPlanner.PlacementMode placementMode,
                                                    BlockBounds formationBounds,
                                                    LandUseTerrainField terrain,
                                                    long noiseSeed) {
        List<PatchMemberCell> result = new ArrayList<>();
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] direction : directions) {
            CellKey candidateKey = new CellKey(current.cellX() + direction[0],
                    current.cellZ() + direction[1]);
            PatchMemberCell candidate = candidates.get(candidateKey);
            if (candidate == null || blocked.contains(candidateKey)) continue;
            result.add(candidate);
        }
        result.sort(Comparator
                .comparingDouble((PatchMemberCell cell) -> terrainOrder(cell, seed, group, placementMode,
                        formationBounds, terrain, noiseSeed))
                .thenComparingInt(PatchMemberCell::cellZ)
                .thenComparingInt(PatchMemberCell::cellX));
        return result;
    }

    private static double terrainOrder(PatchMemberCell cell, PatchMemberCell seed,
                                       CityBlueprint.Group group,
                                       CityBlueprintGroupLayoutPlanner.PlacementMode placementMode,
                                       BlockBounds formationBounds,
                                       LandUseTerrainField terrain,
                                       long noiseSeed) {
        long dx = (long) cell.cellX() - seed.cellX();
        long dz = (long) cell.cellZ() - seed.cellZ();
        double distance = Math.hypot(dx, dz);
        long mixed = mix64(noiseSeed ^ ((long) cell.cellX() * 0x9e3779b97f4a7c15L)
                ^ ((long) cell.cellZ() * 0xc2b2ae3d27d4eb4fL));
        double noise = (mixed >>> 11) * 0x1.0p-53;
        double policyBias = switch (group.terrainPolicy()) {
            case CONFORM -> distance * 0.08;
            case ASSERTIVE -> distance * 0.02;
            default -> distance * 0.05;
        };
        double terrainCost = terrain.cellAt(cell.blockMinX(), cell.blockMinZ())
                .map(value -> value.slope() * 0.20 + value.localRelief() * 0.06 + value.roughness() * 0.10)
                .orElse(Double.MAX_VALUE / 4.0);
        double modeCost = switch (placementMode) {
            case CORE_ANCHORED -> policyBias + terrainCost * 0.35;
            case AXIS_ANCHORED -> policyBias * 0.65 + terrainCost * 0.45
                    + lateralDistance(cell, seed, formationBounds) * 0.10;
            case CLUSTER_BOUNDED -> policyBias + terrainCost * 0.25;
            case TERRAIN_FOLLOWING -> policyBias * 0.35 + terrainCost * 1.20;
        };
        return modeCost + noise * 0.25;
    }

    private static double lateralDistance(PatchMemberCell cell, PatchMemberCell seed,
                                          BlockBounds formationBounds) {
        // LINEAR formation bounds are oriented by the primary building entrance.
        // The street band runs perpendicular to that entrance, so capacity must
        // prefer cells along the perpendicular axis rather than along the bounds'
        // longer entrance axis.
        boolean streetXAxis = formationBounds.widthBlocks() < formationBounds.heightBlocks();
        int delta = streetXAxis ? cell.cellZ() - seed.cellZ() : cell.cellX() - seed.cellX();
        return Math.abs(delta);
    }

    private static PatchMemberCell chooseSeed(CityBlueprint.Group group,
                                              Iterable<PatchMemberCell> cells,
                                              int step,
                                              long seed,
                                              BlockBounds formationBounds) {
        List<PatchMemberCell> values = new ArrayList<>();
        cells.forEach(values::add);
        if (values.isEmpty()) return null;
        double meanX = values.stream().mapToInt(cell -> cell.blockMinX() + step / 2).average().orElse(0.0);
        double meanZ = values.stream().mapToInt(cell -> cell.blockMinZ() + step / 2).average().orElse(0.0);
        int minX = values.stream().mapToInt(PatchMemberCell::blockMinX).min().orElse(0);
        int maxX = values.stream().mapToInt(PatchMemberCell::blockMinX).max().orElse(0);
        int minZ = values.stream().mapToInt(PatchMemberCell::blockMinZ).min().orElse(0);
        int maxZ = values.stream().mapToInt(PatchMemberCell::blockMinZ).max().orElse(0);
        double targetX = formationBounds == null ? switch (group.preferredPatchZone()) {
            case WEST -> minX + (maxX - minX) * 0.25;
            case EAST -> minX + (maxX - minX) * 0.75;
            default -> meanX;
        } : (formationBounds.minX() + formationBounds.maxX()) / 2.0;
        double targetZ = formationBounds == null ? switch (group.preferredPatchZone()) {
            case NORTH -> minZ + (maxZ - minZ) * 0.25;
            case SOUTH -> minZ + (maxZ - minZ) * 0.75;
            default -> meanZ;
        } : (formationBounds.minZ() + formationBounds.maxZ()) / 2.0;
        return values.stream().min(Comparator
                .comparingLong((PatchMemberCell cell) -> {
                    long dx = cell.blockMinX() + step / 2 - Math.round(targetX);
                    long dz = cell.blockMinZ() + step / 2 - Math.round(targetZ);
                    return dx * dx + dz * dz;
                })
                .thenComparingLong(cell -> mix64(seed ^ key(cell).hashCode()))
                .thenComparingInt(PatchMemberCell::cellZ)
                .thenComparingInt(PatchMemberCell::cellX)).orElse(null);
    }

    private static BlockPointLike choosePoint(CityBlueprint.Group group,
                                               Iterable<PatchMemberCell> cells, int step,
                                               BlockBounds formationBounds) {
        PatchMemberCell seed = chooseSeed(group, cells, step, 0L, formationBounds);
        return seed == null ? new BlockPointLike(0, 0)
                : new BlockPointLike(seed.blockMinX(), seed.blockMinZ());
    }

    private static boolean separationAllowed(PatchMemberCell candidate,
                                              Map<String, Reservation> reservations,
                                              Set<String> exempt,
                                              int step) {
        BlockBounds candidateBounds = cellBounds(candidate, step);
        for (Map.Entry<String, Reservation> entry : reservations.entrySet()) {
            if (exempt.contains(entry.getKey())) continue;
            for (PatchMemberCell existing : entry.getValue().cells()) {
                if (edgeGap(candidateBounds, cellBounds(existing, step)) < 12) return false;
            }
        }
        return true;
    }

    private static boolean usable(PatchMemberCell cell, LandUseTerrainField terrain,
                                  CityBlueprint.TerrainPolicy policy) {
        double maximumSlope = switch (policy) {
            case CONFORM -> 6.0;
            case BALANCED -> 12.0;
            case ASSERTIVE -> 18.0;
        };
        double maximumRelief = switch (policy) {
            case CONFORM -> 8.0;
            case BALANCED -> 12.0;
            case ASSERTIVE -> 18.0;
        };
        return terrain.cellAt(cell.blockMinX(), cell.blockMinZ())
                .map(value -> value.sampled() && !value.water()
                        && value.slope() <= maximumSlope && value.localRelief() <= maximumRelief)
                .orElse(false);
    }

    private static JsonObject basePlan(List<CityBlueprint.Group> groups, int step,
                                       BlockBounds planningBounds, JsonArray groupPlans) {
        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", SCHEMA_VERSION);
        plan.addProperty("status", "failed");
        plan.addProperty("policy", "PREALLOCATE_CONNECTED_CAPACITY_THEN_GROW_GROUPS");
        plan.addProperty("cellStepBlocks", step);
        plan.addProperty("minimumDistrictSeparationBlocks", 12);
        plan.add("planningBounds", boundsJson(planningBounds));
        plan.add("groups", groupPlans);
        return plan;
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject value = new JsonObject();
        value.addProperty("minX", bounds.minX());
        value.addProperty("minZ", bounds.minZ());
        value.addProperty("maxX", bounds.maxX());
        value.addProperty("maxZ", bounds.maxZ());
        return value;
    }

    private static JsonArray reservationSpans(List<PatchMemberCell> cells, int step) {
        Map<Integer, List<PatchMemberCell>> rows = new LinkedHashMap<>();
        cells.forEach(cell -> rows.computeIfAbsent(cell.blockMinZ(), ignored -> new ArrayList<>()).add(cell));
        JsonArray spans = new JsonArray();
        rows.forEach((z, row) -> {
            row.sort(Comparator.comparingInt(PatchMemberCell::blockMinX));
            int start = row.get(0).blockMinX();
            int end = start;
            for (int index = 1; index <= row.size(); index++) {
                int next = index < row.size() ? row.get(index).blockMinX() : Integer.MAX_VALUE;
                if (next == end + step) {
                    end = next;
                    continue;
                }
                JsonObject span = new JsonObject();
                span.addProperty("minX", start);
                span.addProperty("minZ", z);
                span.addProperty("maxX", end + step - 1);
                span.addProperty("maxZ", z + step - 1);
                span.addProperty("cellStepBlocks", step);
                spans.add(span);
                start = next;
                end = next;
            }
        });
        return spans;
    }

    private static BlockBounds cellBounds(PatchMemberCell cell, int step) {
        return new BlockBounds(cell.blockMinX(), cell.blockMinZ(),
                cell.blockMinX() + step - 1, cell.blockMinZ() + step - 1);
    }

    private static boolean within(BlockBounds candidate, BlockBounds container) {
        return candidate.minX() >= container.minX() && candidate.minZ() >= container.minZ()
                && candidate.maxX() <= container.maxX() && candidate.maxZ() <= container.maxZ();
    }

    private static double edgeGap(BlockBounds first, BlockBounds second) {
        int dx = axisGap(first.minX(), first.maxX(), second.minX(), second.maxX());
        int dz = axisGap(first.minZ(), first.maxZ(), second.minZ(), second.maxZ());
        return Math.hypot(dx, dz);
    }

    private static int axisGap(int firstMin, int firstMax, int secondMin, int secondMax) {
        if (firstMax < secondMin) return Math.max(0, secondMin - firstMax - 1);
        if (secondMax < firstMin) return Math.max(0, firstMin - secondMax - 1);
        return 0;
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static long distanceSquared(PatchMemberCell cell, BlockPointLike point) {
        long dx = (long) cell.blockMinX() - point.x;
        long dz = (long) cell.blockMinZ() - point.z;
        return dx * dx + dz * dz;
    }

    private static CellKey key(PatchMemberCell cell) {
        return new CellKey(cell.cellX(), cell.cellZ());
    }

    private static long seedFor(CityBlueprint.Group group, PatchMemberCell seed, long generationSeed) {
        return mix64(generationSeed ^ group.groupId().hashCode()
                ^ ((long) seed.cellX() << 32) ^ seed.cellZ());
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    record Result(boolean ok, String reasonCode, String message, JsonObject plan,
                  Map<String, Reservation> reservations) {
    }

    record SpatialDemand(int minimumAreaBlocks,
                         int targetAreaBlocks,
                         int maximumAreaBlocks,
                         int roadReserveAreaBlocks,
                         int maximumTemplateSpanBlocks,
                         int formationSpanBlocks,
                         int formationWidthBlocks,
                         int formationLengthBlocks,
                         String primaryAxisDirection,
                         int plannedStructureCount,
                         int templateFootprintAreaBlocks,
                         int internalStreetAreaBlocks) {
        SpatialDemand {
            if (minimumAreaBlocks <= 0 || targetAreaBlocks < minimumAreaBlocks
                    || maximumAreaBlocks < targetAreaBlocks || roadReserveAreaBlocks < 0
                    || maximumTemplateSpanBlocks <= 0 || formationSpanBlocks <= 0
                    || formationWidthBlocks <= 0 || formationLengthBlocks <= 0
                    || primaryAxisDirection == null || plannedStructureCount <= 0
                    || templateFootprintAreaBlocks <= 0 || internalStreetAreaBlocks < 0) {
                throw new IllegalArgumentException("CITY_BLUEPRINT_GROUP_SPATIAL_DEMAND_INVALID");
            }
        }

        JsonObject asJson() {
            JsonObject value = new JsonObject();
            value.addProperty("source", "TEMPLATE_ARRAY_DEMAND");
            value.addProperty("minimumAreaBlocks", minimumAreaBlocks);
            value.addProperty("targetAreaBlocks", targetAreaBlocks);
            value.addProperty("maximumAreaBlocks", maximumAreaBlocks);
            value.addProperty("roadReserveAreaBlocks", roadReserveAreaBlocks);
            value.addProperty("maximumTemplateSpanBlocks", maximumTemplateSpanBlocks);
            value.addProperty("formationSpanBlocks", formationSpanBlocks);
            value.addProperty("formationWidthBlocks", formationWidthBlocks);
            value.addProperty("formationLengthBlocks", formationLengthBlocks);
            if (!primaryAxisDirection.isBlank()) {
                value.addProperty("primaryAxisDirection", primaryAxisDirection);
            }
            value.addProperty("plannedStructureCount", plannedStructureCount);
            value.addProperty("templateFootprintAreaBlocks", templateFootprintAreaBlocks);
            value.addProperty("internalStreetAreaBlocks", internalStreetAreaBlocks);
            return value;
        }
    }

    record Reservation(String groupId, String placementMode, String status, String reasonCode, String message,
                       List<String> patchRefs, int minimumAreaBlocks, int targetAreaBlocks,
                       int maximumAreaBlocks, int roadReserveAreaBlocks, int targetWithRoadBlocks,
                       int maximumCellCount, List<PatchMemberCell> cells) {
        static Reservation success(CityBlueprint.Group group,
                                   CityBlueprintGroupLayoutPlanner.PlacementMode placementMode,
                                   List<String> patchRefs,
                                   int minimumArea, int targetArea, int maximumArea, int roadReserve,
                                   int targetWithRoad, List<PatchMemberCell> cells, int maximumCells) {
            return new Reservation(group.groupId(), placementMode.name(), "RESERVED", "", "", List.copyOf(patchRefs), minimumArea,
                    targetArea, maximumArea, roadReserve,
                    targetWithRoad, maximumCells, List.copyOf(cells));
        }

        static Reservation successWithGap(CityBlueprint.Group group,
                                          CityBlueprintGroupLayoutPlanner.PlacementMode placementMode,
                                          List<String> patchRefs,
                                          int minimumArea, int targetArea, int maximumArea, int roadReserve,
                                          int targetWithRoad, List<PatchMemberCell> cells, int maximumCells,
                                          String message) {
            return new Reservation(group.groupId(), placementMode.name(), "RESERVED_WITH_GAP",
                    "CITY_BLUEPRINT_GROUP_DISTRICT_CAPACITY_GAP_RECORDED", message,
                    List.copyOf(patchRefs), minimumArea, targetArea, maximumArea, roadReserve,
                    targetWithRoad, maximumCells, List.copyOf(cells));
        }

        static Reservation deferred(CityBlueprint.Group group,
                                    CityBlueprintGroupLayoutPlanner.PlacementMode placementMode,
                                    String status) {
            return new Reservation(group.groupId(), placementMode.name(), status, "", "",
                    List.of(), 0, 0, 0, 0, 0, 0, List.<PatchMemberCell>of());
        }

        static Reservation failed(CityBlueprint.Group group,
                                  CityBlueprintGroupLayoutPlanner.PlacementMode placementMode,
                                  List<String> patchRefs,
                                  int minimumArea, int targetArea, int maximumArea, int roadReserve,
                                  int targetWithRoad, String reason, String message) {
            return new Reservation(group.groupId(), placementMode.name(), "INSUFFICIENT_CAPACITY", reason, message,
                    List.copyOf(patchRefs), minimumArea, targetArea, maximumArea, roadReserve,
                    targetWithRoad, 0, List.of());
        }

        boolean ok() {
            return !"INSUFFICIENT_CAPACITY".equals(status);
        }

        JsonObject asJson(int step) {
            JsonObject value = new JsonObject();
            value.addProperty("groupId", groupId);
            value.addProperty("placementMode", placementMode);
            value.addProperty("status", status);
            if (!reasonCode.isBlank()) value.addProperty("reasonCode", reasonCode);
            if (!message.isBlank()) value.addProperty("message", message);
            JsonArray refs = new JsonArray();
            patchRefs.forEach(refs::add);
            value.add("patchRefs", refs);
            value.addProperty("minimumAreaBlocks", minimumAreaBlocks);
            value.addProperty("targetAreaBlocks", targetAreaBlocks);
            value.addProperty("maximumAreaBlocks", maximumAreaBlocks);
            value.addProperty("roadReserveAreaBlocks", roadReserveAreaBlocks);
            value.addProperty("targetWithRoadBlocks", targetWithRoadBlocks);
            value.addProperty("reservedAreaBlocks", cells.size() * step * step);
            value.addProperty("reservedCellCount", cells.size());
            value.addProperty("maximumCellCount", maximumCellCount);
            value.add("reservationSpans", reservationSpans(cells, step));
            return value;
        }
    }

    private record CellKey(int cellX, int cellZ) {
    }

    private record BlockPointLike(int x, int z) {
    }
}
