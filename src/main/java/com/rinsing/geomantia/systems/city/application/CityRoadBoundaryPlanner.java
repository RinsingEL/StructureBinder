package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CityRoadBoundaryPlanner {
    private static final int MAIN_ROAD_WIDTH = 5;
    private static final int SECONDARY_ROAD_WIDTH = 3;
    private static final int BUFFER_WIDTH = 4;

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
        for (FunctionZoneTerrainStats stats : terrainStats) {
            statsByZone.put(stats.zonePatchId(), stats);
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
            CityQualityReport quality = quality(false, hardBlocks, warnings, needsReview, 0, 0, 0);
            return new Result(
                    new RoadIntent(RoadIntent.CURRENT_SCHEMA_VERSION, functionZoneMap.cityId(), List.of(), List.of(), quality),
                    new BoundaryIntent(BoundaryIntent.CURRENT_SCHEMA_VERSION, functionZoneMap.cityId(), List.of()),
                    new BuildOperationPlan(BuildOperationPlan.CURRENT_SCHEMA_VERSION, functionZoneMap.cityId(),
                            "geomantia_templates/d5", List.of()),
                    quality);
        }

        Map<String, BlockPoint> centers = new LinkedHashMap<>();
        for (FunctionZonePatch zone : functionZoneMap.zones()) {
            centers.put(zone.zonePatchId(), zoneCenter(zone));
        }
        BlockPoint coreCenter = centers.get(coreZone.zonePatchId());
        BlockPoint entryPoint = entry.block();

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
        List<String> mainOps = addRoadOperations(operations, "road_main_01", "main_road_01",
                lPolyline(entryPoint, coreCenter, entry.direction()), MAIN_ROAD_WIDTH);
        roadEdges.add(new RoadIntent.Edge("main_road_01", "main",
                "entry_" + safeId(entry.id()),
                "core_" + safeId(coreZone.zonePatchId()),
                lPolyline(entryPoint, coreCenter, entry.direction()),
                MAIN_ROAD_WIDTH,
                mainOps,
                "Connects selected city entry to the core zone."));

        int secondaryIndex = 1;
        for (FunctionZonePatch zone : functionZoneMap.zones()) {
            if (zone.zonePatchId().equals(coreZone.zonePatchId())) {
                continue;
            }
            BlockPoint zoneCenter = centers.get(zone.zonePatchId());
            String edgeId = "secondary_road_" + String.format("%02d", secondaryIndex);
            List<BlockPoint> polyline = lPolyline(zoneCenter, coreCenter, "");
            List<String> opRefs = addRoadOperations(operations, "road_secondary_" + String.format("%02d", secondaryIndex),
                    edgeId, polyline, SECONDARY_ROAD_WIDTH);
            roadEdges.add(new RoadIntent.Edge(edgeId, "secondary",
                    "zone_" + safeId(zone.zonePatchId()),
                    "core_" + safeId(coreZone.zonePatchId()),
                    polyline,
                    SECONDARY_ROAD_WIDTH,
                    opRefs,
                    "Connects " + zone.zoneName() + " to the core road network."));
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
                nodes.size(), roadEdges.size(), boundaryEdges.size());
        return new Result(
                new RoadIntent(RoadIntent.CURRENT_SCHEMA_VERSION, functionZoneMap.cityId(), nodes, roadEdges, quality),
                new BoundaryIntent(BoundaryIntent.CURRENT_SCHEMA_VERSION, functionZoneMap.cityId(), boundaryEdges),
                new BuildOperationPlan(BuildOperationPlan.CURRENT_SCHEMA_VERSION, functionZoneMap.cityId(),
                        "geomantia_templates/d5", operations),
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
        String material = treatment.equals("green_buffer") ? "minecraft:grass_block" : "minecraft:coarse_dirt";
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
        if (zone.functionType() == CityFunctionType.HARBOR_OR_WATERFRONT
                || (stats != null && stats.waterContactRatio() > 0.2)) {
            return "waterfront";
        }
        if (zone.functionType() == CityFunctionType.DEFENSE) {
            return "wall_hint";
        }
        if (zone.functionType() == CityFunctionType.FARM_OR_PASTURE) {
            return "green_buffer";
        }
        return "soft_transition";
    }

    private CityQualityReport quality(boolean passed, List<String> hardBlocks, List<String> warnings,
                                      List<String> needsReview, int nodeCount, int roadCount, int boundaryCount) {
        JsonObject metrics = new JsonObject();
        metrics.addProperty("nodeCount", nodeCount);
        metrics.addProperty("roadEdgeCount", roadCount);
        metrics.addProperty("boundaryEdgeCount", boundaryCount);
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

    public record Result(RoadIntent roadIntent,
                         BoundaryIntent boundaryIntent,
                         BuildOperationPlan buildOperationPlan,
                         CityQualityReport qualityReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", qualityReport.passed());
            obj.add("qualityReport", qualityReport.asJson());
            obj.add("roadIntent", roadIntent.asJson());
            obj.add("boundaryIntent", boundaryIntent.asJson());
            obj.add("buildOperationPlan", buildOperationPlan.asJson());
            return obj;
        }
    }
}
