package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public final class CityRoadBoundaryPlanner {
    private static final int MAIN_ROAD_WIDTH = 5;
    private static final int SECONDARY_ROAD_WIDTH = 3;
    private static final int BUFFER_WIDTH = 4;
    private static final int MAX_ASTAR_EXPANSIONS = 32768;

    public Result plan(CitySiteContext siteContext,
                       FunctionZoneMap functionZoneMap,
                       List<FunctionZoneTerrainStats> terrainStats) {
        if (siteContext == null) {
            throw new IllegalArgumentException("siteContext is required");
        }
        if (functionZoneMap == null) {
            throw new IllegalArgumentException("functionZoneMap is required");
        }

        List<String> hardBlocks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> needsReview = new ArrayList<>();
        if (!functionZoneMap.quality().passed()) {
            hardBlocks.add("D4 FunctionZoneMap quality did not pass.");
        }
        if (functionZoneMap.zones().isEmpty()) {
            hardBlocks.add("FunctionZoneMap has no zones.");
        }

        Map<String, FunctionZoneTerrainStats> statsByZone = new HashMap<>();
        if (terrainStats != null) {
            for (FunctionZoneTerrainStats stats : terrainStats) {
                statsByZone.put(stats.zonePatchId(), stats);
            }
        }

        FunctionZonePatch coreZone = selectCoreZone(functionZoneMap.zones());
        if (coreZone == null) {
            hardBlocks.add("No core zone could be selected.");
        }
        EntryCandidate entry = selectEntry(siteContext, coreZone);
        if (entry == null) {
            hardBlocks.add("No entry candidate could be selected.");
        }

        if (!hardBlocks.isEmpty()) {
            CityQualityReport quality = quality(false, hardBlocks, warnings, needsReview, 0, 0, 0, 0);
            BuildOperationPlan operationPlan = new BuildOperationPlan(BuildOperationPlan.CURRENT_SCHEMA_VERSION,
                    functionZoneMap.cityId(), "geomantia_templates/d5", List.of());
            BuildableAreaMap buildableAreaMap = new BuildableAreaMapBuilder().build(functionZoneMap, operationPlan);
            return new Result(
                    new RoadIntent(RoadIntent.CURRENT_SCHEMA_VERSION, functionZoneMap.cityId(), List.of(), List.of(), quality),
                    new BoundaryIntent(BoundaryIntent.CURRENT_SCHEMA_VERSION, functionZoneMap.cityId(), List.of()),
                    operationPlan,
                    buildableAreaMap,
                    quality);
        }

        Map<String, BlockPoint> centers = new LinkedHashMap<>();
        for (FunctionZonePatch zone : functionZoneMap.zones()) {
            centers.put(zone.zonePatchId(), zoneCenter(zone));
        }
        BlockPoint coreCenter = centers.get(coreZone.zonePatchId());
        BlockPoint entryPoint = entry.block();
        PathContext pathContext = new PathContext(siteContext.grid(), functionZoneMap.zones(), statsByZone);
        int pathFallbackCount = 0;

        List<RoadIntent.Node> nodes = new ArrayList<>();
        nodes.add(new RoadIntent.Node("entry_" + safeId(entry.id()), "entry", "", entryPoint, entry.description()));
        nodes.add(new RoadIntent.Node("core_" + safeId(coreZone.zonePatchId()), "core", coreZone.zonePatchId(),
                coreCenter, coreZone.zoneName()));
        for (FunctionZonePatch zone : functionZoneMap.zones()) {
            if (zone.zonePatchId().equals(coreZone.zonePatchId())) {
                continue;
            }
            nodes.add(new RoadIntent.Node("zone_" + safeId(zone.zonePatchId()), "zone_center",
                    zone.zonePatchId(), centers.get(zone.zonePatchId()), zone.zoneName()));
        }

        List<RoadIntent.Edge> roadEdges = new ArrayList<>();
        List<BuildOperationPlan.Operation> operations = new ArrayList<>();
        PathResult mainPath = pathContext.findPath(
                entryPoint,
                coreCenter,
                Set.of(coreZone.zonePatchId()),
                entry.direction(),
                warnings,
                "main_road_01");
        if (mainPath.fallback()) {
            pathFallbackCount++;
        }
        pathContext.addRoadCells(mainPath.cells());
        List<String> mainOps = addRoadOperations(operations, "road_main_01", "main_road_01",
                mainPath.polyline(), MAIN_ROAD_WIDTH);
        roadEdges.add(new RoadIntent.Edge("main_road_01", "main",
                "entry_" + safeId(entry.id()),
                "core_" + safeId(coreZone.zonePatchId()),
                mainPath.polyline(),
                MAIN_ROAD_WIDTH,
                mainOps,
                "A* grid path connects selected city entry to the core zone."));

        int secondaryIndex = 1;
        for (FunctionZonePatch zone : functionZoneMap.zones()) {
            if (zone.zonePatchId().equals(coreZone.zonePatchId())) {
                continue;
            }
            BlockPoint zoneCenter = centers.get(zone.zonePatchId());
            String edgeId = "secondary_road_" + String.format("%02d", secondaryIndex);
            PathResult secondaryPath = pathContext.findPath(
                    zoneCenter,
                    coreCenter,
                    Set.of(zone.zonePatchId(), coreZone.zonePatchId()),
                    "",
                    warnings,
                    edgeId);
            if (secondaryPath.fallback()) {
                pathFallbackCount++;
            }
            pathContext.addRoadCells(secondaryPath.cells());
            List<BlockPoint> polyline = secondaryPath.polyline();
            List<String> opRefs = addRoadOperations(operations, "road_secondary_" + String.format("%02d", secondaryIndex),
                    edgeId, polyline, SECONDARY_ROAD_WIDTH);
            roadEdges.add(new RoadIntent.Edge(edgeId, "secondary",
                    "zone_" + safeId(zone.zonePatchId()),
                    "core_" + safeId(coreZone.zonePatchId()),
                    polyline,
                    SECONDARY_ROAD_WIDTH,
                    opRefs,
                    "A* grid path connects " + zone.zoneName() + " to the core road network."));
            secondaryIndex++;
        }

        List<BoundaryIntent.Edge> boundaryEdges = new ArrayList<>();
        int boundaryIndex = 1;
        for (FunctionZonePatch zone : functionZoneMap.zones()) {
            BoundaryShape shape = boundaryShape(zone);
            String treatment = boundaryTreatment(zone, statsByZone.get(zone.zonePatchId()));
            String edgeId = "boundary_" + String.format("%02d", boundaryIndex);
            List<String> opRefs = addBoundaryOperations(operations, edgeId, shape.polyline(), treatment);
            boundaryEdges.add(new BoundaryIntent.Edge(edgeId, treatment,
                    zone.zonePatchId(), "outside",
                    shape.polyline(), BUFFER_WIDTH, 50, opRefs,
                    shape.usedEnvelope()
                            ? "Envelope boundary fallback for " + zone.zoneName() + "."
                            : "Member-cell boundary envelope for " + zone.zoneName() + "."));
            if (shape.usedEnvelope()) {
                warnings.add("Boundary for " + zone.zonePatchId() + " used envelope fallback.");
            }
            boundaryIndex++;
        }

        addTemplateOperations(operations, functionZoneMap.zones(), entryPoint, coreCenter, warnings);

        CityQualityReport quality = quality(true, hardBlocks, warnings, needsReview,
                nodes.size(), roadEdges.size(), boundaryEdges.size(), pathFallbackCount);
        BuildOperationPlan operationPlan = new BuildOperationPlan(BuildOperationPlan.CURRENT_SCHEMA_VERSION,
                functionZoneMap.cityId(), "geomantia_templates/d5", operations);
        BuildableAreaMap buildableAreaMap = new BuildableAreaMapBuilder().build(functionZoneMap, operationPlan);
        return new Result(
                new RoadIntent(RoadIntent.CURRENT_SCHEMA_VERSION, functionZoneMap.cityId(), nodes, roadEdges, quality),
                new BoundaryIntent(BoundaryIntent.CURRENT_SCHEMA_VERSION, functionZoneMap.cityId(), boundaryEdges),
                operationPlan,
                buildableAreaMap,
                quality);
    }

    private FunctionZonePatch selectCoreZone(List<FunctionZonePatch> zones) {
        for (CityFunctionType type : List.of(CityFunctionType.CIVIC_CORE, CityFunctionType.MARKET)) {
            for (FunctionZonePatch zone : zones) {
                if (zone.functionType() == type) {
                    return zone;
                }
            }
        }
        return zones.stream()
                .filter(zone -> zone.functionType() != CityFunctionType.HARBOR_OR_WATERFRONT)
                .max(Comparator.comparingInt(FunctionZonePatch::areaBlocks))
                .orElse(zones.stream().max(Comparator.comparingInt(FunctionZonePatch::areaBlocks)).orElse(null));
    }

    private EntryCandidate selectEntry(CitySiteContext siteContext, FunctionZonePatch coreZone) {
        if (siteContext.entryCandidates().isEmpty()) {
            return null;
        }
        for (EntryCandidate entry : siteContext.entryCandidates()) {
            if ("main_gate".equals(entry.id())) {
                return entry;
            }
        }
        if (coreZone == null) {
            return siteContext.entryCandidates().get(0);
        }
        BlockPoint coreCenter = zoneCenter(coreZone);
        return siteContext.entryCandidates().stream()
                .min(Comparator.comparingDouble(entry -> distanceSq(entry.block(), coreCenter)))
                .orElse(siteContext.entryCandidates().get(0));
    }

    private BlockPoint zoneCenter(FunctionZonePatch zone) {
        if (!zone.memberCells().isEmpty()) {
            double x = 0;
            double z = 0;
            for (PatchMemberCell cell : zone.memberCells()) {
                x += cell.blockMinX();
                z += cell.blockMinZ();
            }
            int count = zone.memberCells().size();
            return new BlockPoint((int) Math.round(x / count), (int) Math.round(z / count));
        }
        return new BlockPoint(
                (zone.cellShape().minX() + zone.cellShape().maxX()) / 2,
                (zone.cellShape().minZ() + zone.cellShape().maxZ()) / 2);
    }

    private List<BlockPoint> lPolyline(BlockPoint from, BlockPoint to, String direction) {
        String dir = direction == null ? "" : direction.toLowerCase(Locale.ROOT);
        BlockPoint corner;
        if (dir.contains("east") || dir.contains("west")) {
            corner = new BlockPoint(to.x(), from.z());
        } else {
            corner = new BlockPoint(from.x(), to.z());
        }
        if ((corner.x() == from.x() && corner.z() == from.z())
                || (corner.x() == to.x() && corner.z() == to.z())) {
            return List.of(from, to);
        }
        return List.of(from, corner, to);
    }

    private List<String> addRoadOperations(List<BuildOperationPlan.Operation> operations,
                                           String opBase,
                                           String sourceIntentId,
                                           List<BlockPoint> polyline,
                                           int width) {
        List<String> refs = new ArrayList<>();
        refs.add(opBase + "_clear");
        operations.add(new BuildOperationPlan.Operation(opBase + "_clear", "clearVegetation",
                sourceIntentId, polyline, width + 2, "", "", "", new BlockPoint(0, 0),
                "Clear soft vegetation for road corridor."));
        refs.add(opBase + "_fill");
        operations.add(new BuildOperationPlan.Operation(opBase + "_fill", "surfaceFill",
                sourceIntentId, polyline, width, "minecraft:gravel", "", "", new BlockPoint(0, 0),
                "Place road surface."));
        refs.add(opBase + "_edge");
        operations.add(new BuildOperationPlan.Operation(opBase + "_edge", "surfaceReplace",
                sourceIntentId, polyline, width + 2, "minecraft:coarse_dirt", "minecraft:gravel", "",
                new BlockPoint(0, 0), "Place road shoulder."));
        return refs;
    }

    private List<String> addBoundaryOperations(List<BuildOperationPlan.Operation> operations,
                                               String edgeId,
                                               List<BlockPoint> polyline,
                                               String treatment) {
        List<String> refs = new ArrayList<>();
        String base = "boundary_" + safeId(edgeId);
        refs.add(base + "_clear");
        operations.add(new BuildOperationPlan.Operation(base + "_clear", "clearVegetation",
                edgeId, polyline, BUFFER_WIDTH + 2, "", "", "", new BlockPoint(0, 0),
                "Clear soft vegetation for boundary treatment."));
        refs.add(base + "_buffer");
        String material = boundaryMaterial(treatment);
        operations.add(new BuildOperationPlan.Operation(base + "_buffer", "carveBuffer",
                edgeId, polyline, BUFFER_WIDTH, material, "", "", new BlockPoint(0, 0),
                "Apply boundary or buffer surface."));
        return refs;
    }

    private void addTemplateOperations(List<BuildOperationPlan.Operation> operations,
                                       List<FunctionZonePatch> zones,
                                       BlockPoint entryPoint,
                                       BlockPoint coreCenter,
                                       List<String> warnings) {
        boolean defense = zones.stream().anyMatch(zone -> zone.functionType() == CityFunctionType.DEFENSE);
        boolean waterfront = zones.stream().anyMatch(zone -> zone.functionType() == CityFunctionType.HARBOR_OR_WATERFRONT);
        if (defense) {
            operations.add(new BuildOperationPlan.Operation("template_gate_small", "pasteTemplate",
                    "main_road_01", List.of(), 1, "", "", "gate_small", entryPoint,
                    "Paste optional small gate template at main entry."));
        }
        if (waterfront) {
            operations.add(new BuildOperationPlan.Operation("template_dock_marker", "pasteTemplate",
                    "waterfront_boundary", List.of(), 1, "", "", "dock_marker", coreCenter,
                    "Paste optional dock marker near core/waterfront connection."));
        }
    }

    private BoundaryShape boundaryShape(FunctionZonePatch zone) {
        if (zone.memberCells().isEmpty()) {
            return new BoundaryShape(rectangle(zone.cellShape()), true);
        }
        int minX = zone.memberCells().stream().mapToInt(PatchMemberCell::blockMinX).min().orElse(zone.cellShape().minX());
        int minZ = zone.memberCells().stream().mapToInt(PatchMemberCell::blockMinZ).min().orElse(zone.cellShape().minZ());
        int maxX = zone.memberCells().stream().mapToInt(PatchMemberCell::blockMinX).max().orElse(zone.cellShape().maxX());
        int maxZ = zone.memberCells().stream().mapToInt(PatchMemberCell::blockMinZ).max().orElse(zone.cellShape().maxZ());
        return new BoundaryShape(rectangle(new BlockBounds(minX, minZ, maxX, maxZ)), false);
    }

    private List<BlockPoint> rectangle(BlockBounds bounds) {
        return List.of(
                new BlockPoint(bounds.minX(), bounds.minZ()),
                new BlockPoint(bounds.maxX(), bounds.minZ()),
                new BlockPoint(bounds.maxX(), bounds.maxZ()),
                new BlockPoint(bounds.minX(), bounds.maxZ()),
                new BlockPoint(bounds.minX(), bounds.minZ()));
    }

    private String boundaryTreatment(FunctionZonePatch zone, FunctionZoneTerrainStats stats) {
        if (zone.functionType() == CityFunctionType.HARBOR_OR_WATERFRONT) {
            return "waterfront";
        }
        if (zone.functionType() == CityFunctionType.DEFENSE) {
            return "wall_hint";
        }
        if (zone.functionType() == CityFunctionType.FARM_OR_PASTURE) {
            return "green_buffer";
        }
        if (zone.functionType() == CityFunctionType.RESIDENTIAL
                || zone.functionType() == CityFunctionType.PRODUCTION) {
            return "green_buffer";
        }
        return "soft_transition";
    }

    private String boundaryMaterial(String treatment) {
        return switch (treatment) {
            case "waterfront" -> "minecraft:mud";
            case "wall_hint" -> "minecraft:stone_bricks";
            case "green_buffer", "soft_transition" -> "minecraft:grass_block";
            default -> "minecraft:grass_block";
        };
    }

    private CityQualityReport quality(boolean passed, List<String> hardBlocks, List<String> warnings,
                                      List<String> needsReview, int nodeCount, int roadCount, int boundaryCount,
                                      int pathFallbackCount) {
        JsonObject metrics = new JsonObject();
        metrics.addProperty("nodeCount", nodeCount);
        metrics.addProperty("roadEdgeCount", roadCount);
        metrics.addProperty("boundaryEdgeCount", boundaryCount);
        metrics.addProperty("pathAlgorithm", "a_star_grid_v0.1");
        metrics.addProperty("pathFallbackCount", pathFallbackCount);
        int score = Math.max(0, 100 - hardBlocks.size() * 50 - warnings.size() * 5 - needsReview.size() * 3);
        return new CityQualityReport(passed && hardBlocks.isEmpty(), score, hardBlocks, warnings, needsReview, metrics);
    }

    private double distanceSq(BlockPoint a, BlockPoint b) {
        double dx = a.x() - b.x();
        double dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    private String safeId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
    }

    private record BoundaryShape(List<BlockPoint> polyline, boolean usedEnvelope) {
    }

    private final class PathContext {
        private final PlanningGrid grid;
        private final List<FunctionZonePatch> zones;
        private final Map<String, FunctionZoneTerrainStats> statsByZone;
        private final Map<Long, List<String>> zonesByCell = new HashMap<>();
        private final Map<String, FunctionZonePatch> zoneById = new HashMap<>();
        private final Set<Long> roadCells = new HashSet<>();

        private PathContext(PlanningGrid grid,
                            List<FunctionZonePatch> zones,
                            Map<String, FunctionZoneTerrainStats> statsByZone) {
            this.grid = grid;
            this.zones = zones;
            this.statsByZone = statsByZone;
            indexZones();
        }

        private PathResult findPath(BlockPoint from,
                                    BlockPoint to,
                                    Set<String> endpointZoneIds,
                                    String fallbackDirection,
                                    List<String> warnings,
                                    String edgeId) {
            Cell start = cellForBlock(from);
            Cell target = cellForBlock(to);
            List<Cell> cells = aStar(start, target, endpointZoneIds);
            boolean fallback = cells.isEmpty();
            if (fallback) {
                warnings.add("A* road path fallback used for " + edgeId + ".");
                List<BlockPoint> fallbackPolyline = lPolyline(from, to, fallbackDirection);
                return new PathResult(fallbackPolyline, cellsAlongPolyline(fallbackPolyline), true);
            }
            return new PathResult(polylineFromCells(from, to, cells), cells, false);
        }

        private void addRoadCells(List<Cell> cells) {
            for (Cell cell : cells) {
                roadCells.add(cell.key());
            }
        }

        private List<Cell> aStar(Cell start, Cell target, Set<String> endpointZoneIds) {
            if (!contains(start) || !contains(target)) {
                return List.of();
            }
            PriorityQueue<PathNode> open = new PriorityQueue<>(
                    Comparator.comparingDouble(PathNode::fScore)
                            .thenComparingDouble(PathNode::gScore));
            Map<Long, Double> best = new HashMap<>();
            Set<Long> closed = new HashSet<>();
            PathNode first = new PathNode(start, 0, heuristic(start, target), null);
            open.add(first);
            best.put(start.key(), 0.0);

            int expansions = 0;
            while (!open.isEmpty() && expansions < MAX_ASTAR_EXPANSIONS) {
                PathNode current = open.poll();
                if (!closed.add(current.cell().key())) {
                    continue;
                }
                if (current.cell().equals(target)) {
                    return reconstruct(current);
                }
                expansions++;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        Cell next = new Cell(current.cell().x() + dx, current.cell().z() + dz);
                        if (!contains(next) || closed.contains(next.key())) {
                            continue;
                        }
                        double step = dx != 0 && dz != 0 ? 1.41421356237 : 1.0;
                        double nextG = current.gScore() + step * movementCost(next, endpointZoneIds);
                        if (nextG >= best.getOrDefault(next.key(), Double.POSITIVE_INFINITY)) {
                            continue;
                        }
                        best.put(next.key(), nextG);
                        open.add(new PathNode(next, nextG, nextG + heuristic(next, target), current));
                    }
                }
            }
            return List.of();
        }

        private double movementCost(Cell cell, Set<String> endpointZoneIds) {
            if (roadCells.contains(cell.key())) {
                return 0.25;
            }
            double cost = 1.0;
            for (String zoneId : zonesByCell.getOrDefault(cell.key(), List.of())) {
                if (endpointZoneIds.contains(zoneId)) {
                    cost += 0.15;
                    continue;
                }
                FunctionZonePatch zone = zoneById.get(zoneId);
                FunctionZoneTerrainStats stats = statsByZone.get(zoneId);
                cost += zonePenalty(zone, stats);
            }
            return cost;
        }

        private double zonePenalty(FunctionZonePatch zone, FunctionZoneTerrainStats stats) {
            double penalty = switch (zone.functionType()) {
                case HARBOR_OR_WATERFRONT -> 12.0;
                case DEFENSE -> 8.0;
                case FARM_OR_PASTURE -> 3.0;
                case CIVIC_CORE, MARKET -> 6.0;
                default -> 5.0;
            };
            if (stats != null) {
                if (stats.waterContactRatio() > 0.2) {
                    penalty += 6.0;
                }
                if (stats.slopeMean() > 0.35) {
                    penalty += 2.0;
                }
            }
            return penalty;
        }

        private double heuristic(Cell a, Cell b) {
            int dx = a.x() - b.x();
            int dz = a.z() - b.z();
            return Math.sqrt(dx * dx + dz * dz);
        }

        private List<Cell> reconstruct(PathNode end) {
            List<Cell> cells = new ArrayList<>();
            PathNode current = end;
            while (current != null) {
                cells.add(current.cell());
                current = current.previous();
            }
            Collections.reverse(cells);
            return cells;
        }

        private List<BlockPoint> polylineFromCells(BlockPoint from, BlockPoint to, List<Cell> cells) {
            if (cells.size() < 2) {
                return List.of(from, to);
            }
            List<BlockPoint> points = new ArrayList<>();
            points.add(from);
            int previousDx = 0;
            int previousDz = 0;
            for (int i = 1; i < cells.size(); i++) {
                Cell previous = cells.get(i - 1);
                Cell current = cells.get(i);
                int dx = Integer.compare(current.x(), previous.x());
                int dz = Integer.compare(current.z(), previous.z());
                if (i > 1 && (dx != previousDx || dz != previousDz)) {
                    addIfDifferent(points, blockForCell(previous));
                }
                previousDx = dx;
                previousDz = dz;
            }
            addIfDifferent(points, to);
            return points;
        }

        private List<Cell> cellsAlongPolyline(List<BlockPoint> polyline) {
            Set<Long> keys = new HashSet<>();
            List<Cell> cells = new ArrayList<>();
            for (int i = 1; i < polyline.size(); i++) {
                BlockPoint a = polyline.get(i - 1);
                BlockPoint b = polyline.get(i);
                int steps = Math.max(Math.abs(b.x() - a.x()), Math.abs(b.z() - a.z()));
                steps = Math.max(1, steps / Math.max(1, grid.cellStepBlocks()));
                for (int s = 0; s <= steps; s++) {
                    int x = a.x() + Math.round((b.x() - a.x()) * (s / (float) steps));
                    int z = a.z() + Math.round((b.z() - a.z()) * (s / (float) steps));
                    Cell cell = cellForBlock(new BlockPoint(x, z));
                    if (keys.add(cell.key())) {
                        cells.add(cell);
                    }
                }
            }
            return cells;
        }

        private void addIfDifferent(List<BlockPoint> points, BlockPoint point) {
            BlockPoint last = points.get(points.size() - 1);
            if (last.x() != point.x() || last.z() != point.z()) {
                points.add(point);
            }
        }

        private boolean contains(Cell cell) {
            return cell.x() >= 0 && cell.x() < grid.cellsX()
                    && cell.z() >= 0 && cell.z() < grid.cellsZ();
        }

        private Cell cellForBlock(BlockPoint block) {
            int x = Math.floorDiv(block.x() - grid.originBlockX(), grid.cellStepBlocks());
            int z = Math.floorDiv(block.z() - grid.originBlockZ(), grid.cellStepBlocks());
            x = Math.max(0, Math.min(grid.cellsX() - 1, x));
            z = Math.max(0, Math.min(grid.cellsZ() - 1, z));
            return new Cell(x, z);
        }

        private BlockPoint blockForCell(Cell cell) {
            int halfStep = Math.max(1, grid.cellStepBlocks() / 2);
            return new BlockPoint(grid.cellToBlockX(cell.x()) + halfStep,
                    grid.cellToBlockZ(cell.z()) + halfStep);
        }

        private void indexZones() {
            for (FunctionZonePatch zone : zones) {
                zoneById.put(zone.zonePatchId(), zone);
                Set<Cell> cells = zoneCells(zone);
                for (Cell cell : cells) {
                    zonesByCell.computeIfAbsent(cell.key(), key -> new ArrayList<>()).add(zone.zonePatchId());
                }
            }
        }

        private Set<Cell> zoneCells(FunctionZonePatch zone) {
            Set<Cell> cells = new HashSet<>();
            if (!zone.memberCells().isEmpty()) {
                for (PatchMemberCell member : zone.memberCells()) {
                    cells.add(cellForBlock(new BlockPoint(member.blockMinX(), member.blockMinZ())));
                }
                return cells;
            }
            Cell min = cellForBlock(new BlockPoint(zone.cellShape().minX(), zone.cellShape().minZ()));
            Cell max = cellForBlock(new BlockPoint(zone.cellShape().maxX(), zone.cellShape().maxZ()));
            for (int x = Math.min(min.x(), max.x()); x <= Math.max(min.x(), max.x()); x++) {
                for (int z = Math.min(min.z(), max.z()); z <= Math.max(min.z(), max.z()); z++) {
                    cells.add(new Cell(x, z));
                }
            }
            return cells;
        }
    }

    private record PathResult(List<BlockPoint> polyline, List<Cell> cells, boolean fallback) {
    }

    private record PathNode(Cell cell, double gScore, double fScore, PathNode previous) {
    }

    private record Cell(int x, int z) {
        long key() {
            return (((long) x) << 32) ^ (z & 0xffffffffL);
        }
    }

    public record Result(RoadIntent roadIntent,
                         BoundaryIntent boundaryIntent,
                         BuildOperationPlan buildOperationPlan,
                         BuildableAreaMap buildableAreaMap,
                         CityQualityReport qualityReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", qualityReport.passed());
            obj.add("qualityReport", qualityReport.asJson());
            obj.add("roadIntent", roadIntent.asJson());
            obj.add("boundaryIntent", boundaryIntent.asJson());
            obj.add("buildOperationPlan", buildOperationPlan.asJson());
            obj.add("buildableAreaMap", buildableAreaMap.asJson());
            return obj;
        }
    }
}
