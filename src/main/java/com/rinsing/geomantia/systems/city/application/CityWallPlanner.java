package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class CityWallPlanner {
    public static final int DEFAULT_WALL_MARGIN_BLOCKS = 24;
    public static final int DEFAULT_SEGMENT_LENGTH_BLOCKS = 15;
    public static final int DEFAULT_GATE_WIDTH_BLOCKS = 7;
    public static final int DEFAULT_ROAD_PROTECTION_MARGIN_BLOCKS = 2;
    public static final int DEFAULT_GATE_CLUSTER_RADIUS_BLOCKS = 24;
    public static final int DEFAULT_TERRAIN_FIT_UNIT_LENGTH_BLOCKS = 5;
    public static final String DEFAULT_WALL_TERRAIN_POLICY = "v3";
    public static final int DEFAULT_FLAT_MAX_DELTA_BLOCKS = 7;
    public static final int DEFAULT_STEPPED_MAX_DELTA_BLOCKS = 16;
    public static final int DEFAULT_MOUNTAIN_PROBE_DISTANCE_BLOCKS = 6;
    public static final int DEFAULT_NATURAL_BOUNDARY_MIN_DELTA_BLOCKS = 17;
    public static final String DEFAULT_WALL_DESIGN_POLICY = "v3";
    public static final int DEFAULT_MIN_GATE_SPACING_BLOCKS = 48;
    public static final int DEFAULT_MIN_GATE_ROAD_LENGTH_BLOCKS = 24;
    public static final int DEFAULT_NATURAL_WATER_BOUNDARY_MIN_AREA_BLOCKS = 4096;
    public static final int DEFAULT_ROAD_PROJECTION_MAX_DISTANCE_BLOCKS = 32;
    private static final int WALL_HALF_THICKNESS_BLOCKS = 2;

    public JsonObject plan(JsonObject placedLedger, int wallMarginBlocks, int segmentLengthBlocks,
                           int gateWidthBlocks) {
        int margin = wallMarginBlocks <= 0 ? DEFAULT_WALL_MARGIN_BLOCKS : wallMarginBlocks;
        int segmentLength = segmentLengthBlocks <= 0 ? DEFAULT_SEGMENT_LENGTH_BLOCKS : segmentLengthBlocks;
        int gateWidth = gateWidthBlocks <= 0 ? DEFAULT_GATE_WIDTH_BLOCKS : gateWidthBlocks;
        BlockBounds cityBounds = unionPlaced(placedLedger);
        BlockBounds wallBounds = expand(snap(cityBounds, segmentLength), margin);

        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", "city_wall_plan.v0.1");
        plan.addProperty("cityId", stringValue(placedLedger, "cityId", "unknown_city"));
        plan.addProperty("boundaryMode", "temporary_rectilinear_actual_footprint_union");
        plan.addProperty("wallMarginBlocks", margin);
        plan.addProperty("segmentLengthBlocks", segmentLength);
        plan.addProperty("gateWidthBlocks", gateWidth);
        plan.add("sourceActualFootprintUnion", boundsJson(cityBounds));
        plan.add("wallBounds", boundsJson(wallBounds));
        plan.add("wallSegments", segments(wallBounds, segmentLength, gateWidth));
        plan.add("templateLibrary", CityWallTemplateLibrary.libraryJson());
        return plan;
    }

    public JsonObject planV2(JsonObject placedLedger,
                             JsonObject wallReservationPlan,
                             JsonObject actualRoadMask,
                             int gateWidthBlocks,
                             int roadProtectionMarginBlocks,
                             int maxFoundationDepthBlocks,
                             int maxSegmentHeightDeltaBlocks) {
        int gateWidth = gateWidthBlocks <= 0 ? 9 : gateWidthBlocks;
        int roadMargin = roadProtectionMarginBlocks <= 0
                ? DEFAULT_ROAD_PROTECTION_MARGIN_BLOCKS : roadProtectionMarginBlocks;
        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", "city_wall_plan.v0.2");
        plan.addProperty("cityId", stringValue(placedLedger, "cityId", "unknown_city"));
        plan.addProperty("boundaryMode", "d3_patch_boundary_wall_corridor");
        plan.addProperty("wallPlanningMode", "reservation_then_road_gate_cut");
        plan.addProperty("roadMaskSource", "actual_world_blocks");
        plan.addProperty("gateWidthBlocks", gateWidth);
        plan.addProperty("roadProtectionMarginBlocks", roadMargin);
        plan.addProperty("maxFoundationDepthBlocks", maxFoundationDepthBlocks <= 0 ? 8 : maxFoundationDepthBlocks);
        plan.addProperty("maxSegmentHeightDeltaBlocks", maxSegmentHeightDeltaBlocks <= 0 ? 7 : maxSegmentHeightDeltaBlocks);
        plan.add("wallReservationSource", wallReservationPlan == null ? new JsonObject() : wallReservationPlan.deepCopy());
        plan.add("actualRoadMask", actualRoadMask == null ? emptyRoadMask(plan.get("cityId").getAsString()) : actualRoadMask.deepCopy());
        plan.add("sourcePlacedStructureLedger", placedLedger == null ? new JsonObject() : placedLedger.deepCopy());
        plan.add("sourceActualFootprintUnion", boundsJson(unionPlaced(placedLedger)));
        BlockBounds wallBounds = wallReservationPlan != null && wallReservationPlan.has("wallBounds")
                ? bounds(wallReservationPlan.getAsJsonObject("wallBounds"))
                : expand(unionPlaced(placedLedger), DEFAULT_WALL_MARGIN_BLOCKS);
        plan.add("wallBounds", boundsJson(wallBounds));

        JsonArray roadIntersections = new JsonArray();
        JsonArray gates = new JsonArray();
        JsonArray segments = new JsonArray();
        JsonArray roadMasks = array(plan.getAsJsonObject("actualRoadMask"), "roadMask");
        int index = 0;
        for (JsonElement elem : array(wallReservationPlan, "wallCenterline")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject centerline = elem.getAsJsonObject();
            BlockBounds bounds = bounds(centerline.getAsJsonObject("blockBounds"));
            List<BlockBounds> hits = roadHits(bounds, roadMasks, roadMargin);
            if (!hits.isEmpty()) {
                BlockBounds gateBounds = gateBounds(bounds, hits, gateWidth, roadMargin);
                String gateId = "road_gate_" + gates.size();
                JsonObject intersection = new JsonObject();
                intersection.addProperty("intersectionId", "road_wall_intersection_" + roadIntersections.size());
                intersection.addProperty("wallSegmentId", stringValue(centerline, "segmentId", "wall_centerline_" + index));
                intersection.addProperty("reasonCode", "ROAD_WALL_INTERSECTION");
                intersection.add("blockBounds", boundsJson(gateBounds));
                roadIntersections.add(intersection);
                JsonObject gate = segment(gateId, "gate_gap", "wall_gap_gate_7",
                        gateBounds.minX(), gateBounds.minZ(), gateBounds.maxX(), gateBounds.maxZ());
                gate.addProperty("reasonCode", "WALL_GATE_FROM_ROAD");
                gate.addProperty("gateWidthBlocks", Math.max(gateBounds.widthBlocks(), gateBounds.heightBlocks()));
                gates.add(gate.deepCopy());
                segments.add(gate);
                addFlankingTowers(segments, gateId, gateBounds);
            } else if (nearRoad(bounds, roadMasks, roadMargin + 2)) {
                JsonObject skipped = segment("wall_road_too_close_" + index, "skipped_wall_segment", "wall_straight_15",
                        bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ());
                skipped.addProperty("reasonCode", "WALL_ROAD_TOO_CLOSE_SKIP");
                segments.add(skipped);
            } else {
                JsonObject wall = segment("wall_segment_" + index, "wall_segment", "wall_straight_15",
                        bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ());
                wall.addProperty("reasonCode", "PATCH_BOUNDARY_WALL_SEGMENT");
                segments.add(wall);
            }
            index++;
        }
        if (gates.isEmpty()) {
            addFallbackGate(plan, wallReservationPlan, segments, gates);
        }
        plan.add("wallRoadIntersections", roadIntersections);
        plan.add("generatedGates", gates);
        plan.add("wallSegments", segments);
        plan.add("templateLibrary", CityWallTemplateLibrary.libraryJson());
        JsonObject terrain = new JsonObject();
        terrain.addProperty("foundationMode", "step_to_surface");
        terrain.addProperty("slopeMode", "skip_or_embed");
        terrain.addProperty("roadProtection", true);
        plan.add("terrainFitPolicy", terrain);
        return plan;
    }

    public JsonObject planV3(JsonObject placedLedger,
                             JsonObject wallReservationPlan,
                             JsonObject actualRoadMask,
                             int gateWidthBlocks,
                             int roadProtectionMarginBlocks,
                             int maxFoundationDepthBlocks,
                             int maxSegmentHeightDeltaBlocks,
                             V3Options options) {
        int gateWidth = gateWidthBlocks <= 0 ? 9 : gateWidthBlocks;
        int roadMargin = roadProtectionMarginBlocks <= 0
                ? DEFAULT_ROAD_PROTECTION_MARGIN_BLOCKS : roadProtectionMarginBlocks;
        V3Options opts = options == null ? V3Options.defaults() : options;
        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", "city_wall_plan.v0.3");
        plan.addProperty("cityId", stringValue(placedLedger, "cityId", "unknown_city"));
        plan.addProperty("wallVersion", "v3");
        plan.addProperty("boundaryMode", "structure_seeded_patch_region_hull");
        plan.addProperty("wallBoundaryMode", "structure_seeded_patch_region_hull");
        plan.addProperty("wallPlanningMode", opts.isV32DesignPolicy()
                ? "domain_hull_then_natural_boundary_and_gatehouse_nodes"
                : "domain_hull_then_road_gate_cluster");
        plan.addProperty("wallDesignPolicy", opts.normalizedWallDesignPolicy());
        plan.addProperty("roadMaskSource", "actual_world_blocks");
        plan.addProperty("gateWidthBlocks", gateWidth);
        plan.addProperty("roadProtectionMarginBlocks", roadMargin);
        plan.addProperty("gateClusterRadiusBlocks", opts.gateClusterRadiusBlocks());
        plan.addProperty("minGateSpacingBlocks", opts.normalizedMinGateSpacingBlocks());
        plan.addProperty("minGateRoadLengthBlocks", opts.normalizedMinGateRoadLengthBlocks());
        plan.addProperty("roadProjectionMaxDistanceBlocks", opts.normalizedRoadProjectionMaxDistanceBlocks());
        plan.addProperty("naturalWaterBoundaryMinAreaBlocks", opts.normalizedNaturalWaterBoundaryMinAreaBlocks());
        plan.addProperty("terrainFitUnitLengthBlocks", opts.terrainFitUnitLengthBlocks());
        plan.addProperty("wallTerrainPolicy", opts.normalizedWallTerrainPolicy());
        plan.addProperty("maxFoundationDepthBlocks", maxFoundationDepthBlocks <= 0 ? 8 : maxFoundationDepthBlocks);
        plan.addProperty("maxSegmentHeightDeltaBlocks", maxSegmentHeightDeltaBlocks <= 0 ? 7 : maxSegmentHeightDeltaBlocks);
        plan.add("wallReservationSource", wallReservationPlan == null ? new JsonObject() : wallReservationPlan.deepCopy());
        plan.add("actualRoadMask", actualRoadMask == null ? emptyRoadMask(plan.get("cityId").getAsString()) : actualRoadMask.deepCopy());
        plan.add("sourcePlacedStructureLedger", placedLedger == null ? new JsonObject() : placedLedger.deepCopy());
        plan.add("sourceActualFootprintUnion", boundsJson(unionPlaced(placedLedger)));
        BlockBounds wallBounds = wallReservationPlan != null && wallReservationPlan.has("wallBounds")
                ? bounds(wallReservationPlan.getAsJsonObject("wallBounds"))
                : expand(unionPlaced(placedLedger), DEFAULT_WALL_MARGIN_BLOCKS);
        plan.add("wallBounds", boundsJson(wallBounds));

        JsonArray roadMasks = array(plan.getAsJsonObject("actualRoadMask"), "roadMask");
        List<RoadComponent> roadComponents = roadComponents(roadMasks, opts.gateClusterRadiusBlocks());
        java.util.Map<String, RoadClass> roadClasses = new java.util.LinkedHashMap<>();
        JsonArray classifiedRoads = new JsonArray();
        JsonArray ignoredInside = new JsonArray();
        JsonArray roadTrendSkipped = new JsonArray();
        JsonArray projectedRoadGateCandidates = new JsonArray();
        JsonArray roadProjectionSkipped = new JsonArray();
        JsonArray rawIntersections = new JsonArray();
        List<GateCluster> gateClusters = new java.util.ArrayList<>();
        for (RoadComponent component : roadComponents) {
            RoadClass roadClass = classifyRoad(component, wallReservationPlan, wallBounds);
            roadClasses.put(component.id(), roadClass);
            JsonObject roadObj = new JsonObject();
            roadObj.addProperty("roadComponentId", component.id());
            roadObj.addProperty("roadClass", roadClass.contractName);
            roadObj.addProperty("roadBlockCount", component.blockCount());
            roadObj.add("blockBounds", boundsJson(component.bounds()));
            classifiedRoads.add(roadObj);
            if (roadClass == RoadClass.INSIDE) {
                ignoredInside.add(roadObj.deepCopy());
                continue;
            }
            RoadTrend trend = roadTrend(component, wallBounds, wallReservationPlan);
            roadObj.addProperty("trendClass", trend.trendClass());
            roadObj.addProperty("estimatedCrossingLengthBlocks", trend.crossingLengthBlocks());
            roadObj.addProperty("touchOnly", trend.touchOnly());
            boolean trendOpensGate = trend.opensGate(opts.normalizedMinGateRoadLengthBlocks());
            boolean directGateCreated = false;
            if (opts.isV32DesignPolicy() && !trendOpensGate && opts.isV33DesignPolicy()) {
                ProjectedGateCandidate projected = projectedGateCandidate(component, trend, wallReservationPlan,
                        opts.normalizedRoadProjectionMaxDistanceBlocks(), gateWidth, roadMargin);
                if (projected.accepted()) {
                    JsonObject candidate = projected.report();
                    candidate.addProperty("candidateId", "projected_road_gate_candidate_"
                            + projectedRoadGateCandidates.size());
                    projectedRoadGateCandidates.add(candidate);
                    mergeGateCluster(gateClusters, component.id(), projected.gateBounds(),
                            opts.gateClusterRadiusBlocks(), opts.normalizedMinGateSpacingBlocks(),
                            "WALL_GATE_FROM_ROAD_PROJECTION");
                    continue;
                }
                roadProjectionSkipped.add(projected.report());
            }
            if (opts.isV32DesignPolicy() && !trendOpensGate) {
                JsonObject skipped = roadObj.deepCopy();
                skipped.addProperty("reasonCode", trend.touchOnly()
                        ? "WALL_ROAD_TOUCH_ONLY_SKIP" : "WALL_ROAD_TREND_TOO_SHORT_SKIP");
                roadTrendSkipped.add(skipped);
                continue;
            }
            if (roadClass == RoadClass.EXTERNAL || roadClass == RoadClass.AMBIGUOUS) {
                for (JsonElement elem : array(wallReservationPlan, "wallCenterline")) {
                    if (!elem.isJsonObject()) {
                        continue;
                    }
                    JsonObject centerline = elem.getAsJsonObject();
                    BlockBounds wall = bounds(centerline.getAsJsonObject("blockBounds"));
                    if (wall.overlaps(expand(component.bounds(), roadMargin))) {
                        BlockBounds gate = gateBounds(wall, List.of(component.bounds()), gateWidth, roadMargin);
                        JsonObject raw = new JsonObject();
                        raw.addProperty("intersectionId", "raw_road_wall_intersection_" + rawIntersections.size());
                        raw.addProperty("roadComponentId", component.id());
                        raw.addProperty("wallSegmentId", stringValue(centerline, "segmentId", ""));
                        raw.addProperty("reasonCode", "ROAD_WALL_INTERSECTION");
                        raw.add("blockBounds", boundsJson(gate));
                        rawIntersections.add(raw);
                        mergeGateCluster(gateClusters, component.id(), gate, opts.gateClusterRadiusBlocks(),
                                opts.isV32DesignPolicy() ? opts.normalizedMinGateSpacingBlocks() : 0,
                                opts.isV32DesignPolicy() ? "GATEHOUSE_FROM_ROAD_TREND" : "GATE_CLUSTER_FROM_EXTERNAL_ROAD");
                        directGateCreated = true;
                    }
                }
                if (!directGateCreated && opts.isV33DesignPolicy()) {
                    ProjectedGateCandidate projected = projectedGateCandidate(component, trend, wallReservationPlan,
                            opts.normalizedRoadProjectionMaxDistanceBlocks(), gateWidth, roadMargin);
                    if (projected.accepted()) {
                        JsonObject candidate = projected.report();
                        candidate.addProperty("candidateId", "projected_road_gate_candidate_"
                                + projectedRoadGateCandidates.size());
                        projectedRoadGateCandidates.add(candidate);
                        mergeGateCluster(gateClusters, component.id(), projected.gateBounds(),
                                opts.gateClusterRadiusBlocks(), opts.normalizedMinGateSpacingBlocks(),
                                "WALL_GATE_FROM_ROAD_PROJECTION");
                    } else {
                        roadProjectionSkipped.add(projected.report());
                    }
                }
            }
        }

        JsonArray gates = new JsonArray();
        JsonArray segments = new JsonArray();
        JsonArray gateClusterJson = new JsonArray();
        for (GateCluster cluster : gateClusters) {
            String gateId = "road_gate_cluster_" + gateClusterJson.size();
            BlockBounds gateBounds = expand(cluster.bounds(), Math.max(0, gateWidth / 2 - 1));
            JsonObject gateCluster = new JsonObject();
            gateCluster.addProperty("gateClusterId", gateId);
            gateCluster.addProperty("sourceRoadComponentId", cluster.roadComponentId());
            gateCluster.addProperty("reasonCode", cluster.reasonCode());
            gateCluster.add("blockBounds", boundsJson(gateBounds));
            gateClusterJson.add(gateCluster);
            boolean gatehouse = opts.isV32DesignPolicy();
            JsonObject gate = segment(gateId, gatehouse ? "gatehouse" : "gate_gap",
                    gatehouse ? gatehouseTemplate(gateBounds) : "wall_gap_gate_7",
                    gateBounds.minX(), gateBounds.minZ(), gateBounds.maxX(), gateBounds.maxZ());
            gate.addProperty("reasonCode", gatehouse ? cluster.reasonCode() : "GATE_CLUSTER_FROM_EXTERNAL_ROAD");
            gate.addProperty("gateWidthBlocks", Math.max(gateBounds.widthBlocks(), gateBounds.heightBlocks()));
            if (gatehouse) {
                gate.addProperty("gatehouse", true);
                gate.addProperty("gateStructurePolicy", "independent_gate_template");
            }
            gates.add(gate.deepCopy());
            segments.add(gate);
            addFlankingTowers(segments, gateId, gateBounds);
        }

        int index = 0;
        JsonArray naturalBoundaries = new JsonArray();
        JsonArray naturalBoundarySkippedWalls = new JsonArray();
        for (JsonElement elem : array(wallReservationPlan, "wallCenterline")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject centerline = elem.getAsJsonObject();
            BlockBounds bounds = bounds(centerline.getAsJsonObject("blockBounds"));
            if (overlapsAnyGate(bounds, gates)) {
                index++;
                continue;
            }
            NaturalBoundary boundary = opts.isV32DesignPolicy()
                    ? naturalBoundaryFor(bounds, wallReservationPlan, opts)
                    : NaturalBoundary.none();
            if (boundary.applies()) {
                JsonObject natural = segment("natural_boundary_" + index, "natural_boundary",
                        boundary.templateId(), bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ());
                natural.addProperty("boundaryType", boundary.boundaryType());
                natural.addProperty("reasonCode", boundary.reasonCode());
                natural.addProperty("sourcePatchRef", boundary.sourcePatchRef());
                natural.addProperty("continuousWallSkipped", true);
                naturalBoundaries.add(natural.deepCopy());
                naturalBoundarySkippedWalls.add(natural.deepCopy());
                segments.add(natural);
                index++;
                continue;
            }
            if (nearRoad(bounds, roadMasks, roadMargin + 2)
                    && !touchesOnlyInsideRoad(bounds, roadComponents, roadClasses)) {
                JsonObject skipped = segment("wall_road_too_close_" + index, "skipped_wall_segment",
                        "wall_straight_15", bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ());
                skipped.addProperty("reasonCode", "WALL_ROAD_TOO_CLOSE_SKIP");
                segments.add(skipped);
            } else {
                JsonObject wall = segment("wall_segment_" + index, "wall_segment", "wall_straight_15",
                        bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ());
                wall.addProperty("reasonCode", "DOMAIN_HULL_WALL_SEGMENT");
                wall.addProperty("terrainFitUnitLengthBlocks", opts.terrainFitUnitLengthBlocks());
                segments.add(wall);
            }
            index++;
        }
        if (gates.isEmpty()) {
            addFallbackGate(plan, wallReservationPlan, segments, gates);
        }
        plan.add("classifiedRoadComponents", classifiedRoads);
        plan.add("roadTrendSkippedIntersections", roadTrendSkipped);
        plan.add("projectedRoadGateCandidates", projectedRoadGateCandidates);
        plan.add("roadProjectionSkippedIntersections", roadProjectionSkipped);
        plan.add("rawRoadWallIntersections", rawIntersections);
        plan.add("insideRoadIgnoredIntersections", ignoredInside);
        plan.add("gateClusters", gateClusterJson);
        plan.add("naturalBoundaries", naturalBoundaries);
        plan.add("naturalBoundarySkippedWalls", naturalBoundarySkippedWalls);
        plan.add("wallRoadIntersections", rawIntersections);
        plan.add("generatedGates", gates);
        plan.add("wallSegments", segments);
        plan.add("templateLibrary", CityWallTemplateLibrary.libraryJson());
        JsonObject terrain = new JsonObject();
        terrain.addProperty("policyVersion", opts.normalizedWallTerrainPolicy());
        terrain.addProperty("flatMaxDeltaBlocks", opts.normalizedFlatMaxDeltaBlocks());
        terrain.addProperty("steppedMaxDeltaBlocks", opts.normalizedSteppedMaxDeltaBlocks());
        terrain.addProperty("mountainProbeDistanceBlocks", opts.normalizedMountainProbeDistanceBlocks());
        terrain.addProperty("naturalBoundaryMinDeltaBlocks", opts.normalizedNaturalBoundaryMinDeltaBlocks());
        terrain.addProperty("embeddedSlopeTower", opts.embeddedSlopeTower());
        terrain.addProperty("foundationMode", "per_unit_column_foundation");
        terrain.addProperty("slopeMode", "v3.1".equals(opts.normalizedWallTerrainPolicy())
                ? "low_flat_mid_stepped_high_embedded_or_cliff"
                : "terrain_units_step_or_skip");
        terrain.addProperty("roadProtection", true);
        terrain.addProperty("debugScanSupported", true);
        plan.add("terrainFitPolicy", terrain);
        return plan;
    }

    public Path writeArtifacts(JsonObject plan, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path planPath = outputDirectory.resolve("city_wall_plan.json");
        Files.writeString(planPath, CityJson.GSON.toJson(plan));
        CityWallTemplateLibrary.writeTemplates(outputDirectory.resolve("city_wall_templates"));
        return planPath;
    }

    private static JsonArray segments(BlockBounds bounds, int segmentLength, int gateWidth) {
        JsonArray out = new JsonArray();
        int gateCenterX = bounds.center().x();
        addHorizontal(out, "north", bounds.minZ(), bounds.minX(), bounds.maxX(), gateCenterX, gateWidth,
                segmentLength);
        addVertical(out, "east", bounds.maxX(), bounds.minZ(), bounds.maxZ(), Integer.MIN_VALUE, gateWidth,
                segmentLength);
        addHorizontal(out, "south", bounds.maxZ(), bounds.maxX(), bounds.minX(), Integer.MIN_VALUE, gateWidth,
                segmentLength);
        addVertical(out, "west", bounds.minX(), bounds.maxZ(), bounds.minZ(), Integer.MIN_VALUE, gateWidth,
                segmentLength);
        addTower(out, "corner_north_west", bounds.minX(), bounds.minZ());
        addTower(out, "corner_north_east", bounds.maxX(), bounds.minZ());
        addTower(out, "corner_south_east", bounds.maxX(), bounds.maxZ());
        addTower(out, "corner_south_west", bounds.minX(), bounds.maxZ());
        addTower(out, "gate_west_tower", gateCenterX - gateWidth / 2 - 3, bounds.minZ());
        addTower(out, "gate_east_tower", gateCenterX + gateWidth / 2 + 3, bounds.minZ());
        return out;
    }

    private static void addFlankingTowers(JsonArray segments, String gateId, BlockBounds gateBounds) {
        boolean horizontal = gateBounds.widthBlocks() >= gateBounds.heightBlocks();
        if (horizontal) {
            addTower(segments, gateId + "_tower_w", gateBounds.minX() - 3, gateBounds.center().z());
            addTower(segments, gateId + "_tower_e", gateBounds.maxX() + 3, gateBounds.center().z());
        } else {
            addTower(segments, gateId + "_tower_n", gateBounds.center().x(), gateBounds.minZ() - 3);
            addTower(segments, gateId + "_tower_s", gateBounds.center().x(), gateBounds.maxZ() + 3);
        }
    }

    private static void addFallbackGate(JsonObject plan, JsonObject reservation, JsonArray segments, JsonArray gates) {
        JsonArray candidates = array(reservation, "gateCandidateZones");
        if (candidates.isEmpty()) {
            plan.addProperty("gateFallbackReasonCode", "NO_VALID_GATE_CANDIDATE_AFTER_FILTER");
            return;
        }
        JsonObject candidate = candidates.get(0).getAsJsonObject();
        BlockBounds source = bounds(candidate.getAsJsonObject("blockBounds"));
        BlockBounds gateBounds = new BlockBounds(
                source.center().x() - 4, source.center().z() - 4,
                source.center().x() + 4, source.center().z() + 4);
        JsonObject gate = segment("fallback_gate_0", "gate_gap", "wall_gap_gate_7",
                gateBounds.minX(), gateBounds.minZ(), gateBounds.maxX(), gateBounds.maxZ());
        gate.addProperty("reasonCode", "NO_VALID_GATE_CANDIDATE_AFTER_FILTER");
        gates.add(gate.deepCopy());
        segments.add(gate);
        addFlankingTowers(segments, "fallback_gate_0", gateBounds);
        plan.addProperty("gateFallbackReasonCode", "NO_VALID_GATE_CANDIDATE_AFTER_FILTER");
    }

    private static List<RoadComponent> roadComponents(JsonArray roadMasks, int clusterRadius) {
        List<BlockBounds> blocks = new java.util.ArrayList<>();
        for (JsonElement elem : roadMasks) {
            if (elem.isJsonObject() && elem.getAsJsonObject().has("blockBounds")) {
                blocks.add(bounds(elem.getAsJsonObject().getAsJsonObject("blockBounds")));
            }
        }
        List<RoadComponent> components = new java.util.ArrayList<>();
        boolean[] assigned = new boolean[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            if (assigned[i]) {
                continue;
            }
            java.util.List<Integer> componentIndexes = new java.util.ArrayList<>();
            componentIndexes.add(i);
            assigned[i] = true;
            BlockBounds union = blocks.get(i);
            boolean changed;
            do {
                changed = false;
                BlockBounds expanded = expand(union, Math.max(2, clusterRadius / 3));
                for (int j = 0; j < blocks.size(); j++) {
                    if (!assigned[j] && expanded.overlaps(blocks.get(j))) {
                        assigned[j] = true;
                        componentIndexes.add(j);
                        union = union(union, blocks.get(j));
                        changed = true;
                    }
                }
            } while (changed);
            components.add(new RoadComponent("road_component_" + components.size(), union, componentIndexes.size()));
        }
        return components;
    }

    private static RoadClass classifyRoad(RoadComponent component, JsonObject reservation, BlockBounds wallBounds) {
        JsonArray domain = array(reservation, "cityDomainMask");
        int domainHits = 0;
        for (JsonElement elem : domain) {
            if (elem.isJsonObject() && elem.getAsJsonObject().has("blockBounds")
                    && component.bounds().overlaps(bounds(elem.getAsJsonObject().getAsJsonObject("blockBounds")))) {
                domainHits++;
                if (domainHits >= 2) {
                    return RoadClass.INSIDE;
                }
            }
        }
        if (!wallBounds.contains(component.bounds().center().x(), component.bounds().center().z())
                || component.bounds().minX() <= wallBounds.minX()
                || component.bounds().maxX() >= wallBounds.maxX()
                || component.bounds().minZ() <= wallBounds.minZ()
                || component.bounds().maxZ() >= wallBounds.maxZ()) {
            return RoadClass.EXTERNAL;
        }
        return domainHits > 0 ? RoadClass.INSIDE : RoadClass.AMBIGUOUS;
    }

    private static void mergeGateCluster(List<GateCluster> clusters, String roadComponentId,
                                         BlockBounds gate, int radius, int minGateSpacingBlocks,
                                         String reasonCode) {
        BlockBounds expanded = expand(gate, Math.max(radius, Math.max(0, minGateSpacingBlocks / 2)));
        for (int i = 0; i < clusters.size(); i++) {
            GateCluster cluster = clusters.get(i);
            if ((minGateSpacingBlocks > 0 || cluster.roadComponentId().equals(roadComponentId))
                    && expanded.overlaps(cluster.bounds())) {
                String mergedRoadId = cluster.roadComponentId().equals(roadComponentId)
                        ? roadComponentId
                        : cluster.roadComponentId() + "+" + roadComponentId;
                clusters.set(i, new GateCluster(mergedRoadId, union(cluster.bounds(), gate),
                        mergeGateReason(cluster.reasonCode(), reasonCode)));
                return;
            }
        }
        clusters.add(new GateCluster(roadComponentId, gate, reasonCode));
    }

    private static String mergeGateReason(String existing, String incoming) {
        if ("GATEHOUSE_FROM_ROAD_TREND".equals(existing) || "GATEHOUSE_FROM_ROAD_TREND".equals(incoming)) {
            return "GATEHOUSE_FROM_ROAD_TREND";
        }
        if ("WALL_GATE_FROM_ROAD_PROJECTION".equals(existing)
                || "WALL_GATE_FROM_ROAD_PROJECTION".equals(incoming)) {
            return "WALL_GATE_FROM_ROAD_PROJECTION";
        }
        return incoming == null || incoming.isBlank() ? existing : incoming;
    }

    private static RoadTrend roadTrend(RoadComponent component, BlockBounds wallBounds, JsonObject reservation) {
        BlockBounds bounds = component.bounds();
        int major = Math.max(bounds.widthBlocks(), bounds.heightBlocks());
        int minor = Math.min(bounds.widthBlocks(), bounds.heightBlocks());
        boolean touchesOuterEdge = bounds.minX() <= wallBounds.minX()
                || bounds.maxX() >= wallBounds.maxX()
                || bounds.minZ() <= wallBounds.minZ()
                || bounds.maxZ() >= wallBounds.maxZ();
        boolean touchOnly = (major <= 2 && minor <= 2)
                || (minor <= 2 && major < DEFAULT_MIN_GATE_ROAD_LENGTH_BLOCKS);
        boolean crossesDomain = false;
        boolean outsideDomain = false;
        for (JsonElement elem : array(reservation, "cityDomainMask")) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("blockBounds")) {
                continue;
            }
            BlockBounds domainCell = bounds(elem.getAsJsonObject().getAsJsonObject("blockBounds"));
            if (bounds.overlaps(domainCell)) {
                crossesDomain = true;
                break;
            }
        }
        if (!wallBounds.contains(bounds.center().x(), bounds.center().z()) || touchesOuterEdge) {
            outsideDomain = true;
        }
        String trendClass = crossesDomain && outsideDomain ? "external_to_internal"
                : touchesOuterEdge ? "touches_outer_boundary"
                : "ambiguous";
        return new RoadTrend(trendClass, major, touchOnly || !crossesDomain && !outsideDomain);
    }

    private static ProjectedGateCandidate projectedGateCandidate(RoadComponent component,
                                                                 RoadTrend trend,
                                                                 JsonObject reservation,
                                                                 int projectionMaxDistanceBlocks,
                                                                 int gateWidth,
                                                                 int roadMargin) {
        int projectionMax = projectionMaxDistanceBlocks <= 0
                ? DEFAULT_ROAD_PROJECTION_MAX_DISTANCE_BLOCKS
                : projectionMaxDistanceBlocks;
        JsonObject report = new JsonObject();
        report.addProperty("roadComponentId", component.id());
        report.addProperty("roadClass", "projectableRoad");
        report.addProperty("roadBlockCount", component.blockCount());
        report.addProperty("trendClass", trend.trendClass());
        report.addProperty("estimatedCrossingLengthBlocks", trend.crossingLengthBlocks());
        report.addProperty("touchOnly", trend.touchOnly());
        report.add("roadBounds", boundsJson(component.bounds()));

        if (trend.touchOnly() && component.blockCount() < 8 && trend.crossingLengthBlocks() < 6) {
            report.addProperty("reasonCode", "WALL_ROAD_TOUCH_ONLY_SKIP");
            return new ProjectedGateCandidate(false, null, report);
        }
        if (component.blockCount() < 8 && trend.crossingLengthBlocks() < 6) {
            report.addProperty("reasonCode", "WALL_ROAD_PROJECTION_TOO_SMALL");
            return new ProjectedGateCandidate(false, null, report);
        }

        BlockBounds bestWall = null;
        String bestWallId = "";
        int bestDistance = Integer.MAX_VALUE;
        boolean sawNearWall = false;
        for (JsonElement elem : array(reservation, "wallCenterline")) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("blockBounds")) {
                continue;
            }
            JsonObject centerline = elem.getAsJsonObject();
            BlockBounds wall = bounds(centerline.getAsJsonObject("blockBounds"));
            int distance = rectDistanceBlocks(component.bounds(), wall);
            if (distance <= projectionMax) {
                sawNearWall = true;
            }
            if (distance <= projectionMax && roadProjectsOntoWall(component.bounds(), wall, projectionMax)
                    && distance < bestDistance) {
                bestWall = wall;
                bestWallId = stringValue(centerline, "segmentId", "");
                bestDistance = distance;
            }
        }
        if (bestWall == null) {
            report.addProperty("reasonCode", sawNearWall
                    ? "WALL_ROAD_PROJECTION_NOT_ALIGNED"
                    : "WALL_ROAD_PROJECTION_NO_NEAR_WALL");
            report.addProperty("projectionMaxDistanceBlocks", projectionMax);
            return new ProjectedGateCandidate(false, null, report);
        }

        BlockBounds gate = gateBounds(bestWall, List.of(component.bounds()), gateWidth, roadMargin);
        report.addProperty("wallSegmentId", bestWallId);
        report.addProperty("distanceToWallBlocks", bestDistance);
        report.addProperty("projectionMaxDistanceBlocks", projectionMax);
        report.addProperty("reasonCode", "WALL_GATE_FROM_ROAD_PROJECTION");
        report.add("projectedGateBounds", boundsJson(gate));
        report.add("wallBounds", boundsJson(bestWall));
        return new ProjectedGateCandidate(true, gate, report);
    }

    private static boolean roadProjectsOntoWall(BlockBounds road, BlockBounds wall, int projectionMax) {
        boolean horizontalWall = wall.widthBlocks() >= wall.heightBlocks();
        if (horizontalWall) {
            if (road.widthBlocks() > road.heightBlocks()) {
                return false;
            }
            return rangesOverlap(road.minX(), road.maxX(), wall.minX() - projectionMax, wall.maxX() + projectionMax);
        }
        if (road.heightBlocks() > road.widthBlocks()) {
            return false;
        }
        return rangesOverlap(road.minZ(), road.maxZ(), wall.minZ() - projectionMax, wall.maxZ() + projectionMax);
    }

    private static boolean rangesOverlap(int aMin, int aMax, int bMin, int bMax) {
        return aMin <= bMax && aMax >= bMin;
    }

    private static int rectDistanceBlocks(BlockBounds a, BlockBounds b) {
        int dx = Math.max(0, Math.max(b.minX() - a.maxX(), a.minX() - b.maxX()));
        int dz = Math.max(0, Math.max(b.minZ() - a.maxZ(), a.minZ() - b.maxZ()));
        return Math.max(dx, dz);
    }

    private static NaturalBoundary naturalBoundaryFor(BlockBounds wall,
                                                      JsonObject reservation,
                                                      V3Options options) {
        NaturalBoundary best = NaturalBoundary.none();
        for (JsonElement elem : array(reservation, "seedPatches")) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("blockBounds")) {
                continue;
            }
            JsonObject patch = elem.getAsJsonObject();
            String type = stringValue(patch, "landformType", "");
            BlockBounds patchBounds = bounds(patch.getAsJsonObject("blockBounds"));
            int patchArea = patchBounds.widthBlocks() * patchBounds.heightBlocks();
            if ("shore".equals(type) && expand(patchBounds, 12).overlaps(wall)) {
                return new NaturalBoundary(true, "NATURAL_WATER_BOUNDARY", "NATURAL_WATER_BOUNDARY_FROM_SHORE_PATCH",
                        "natural_water_boundary", stringValue(patch, "landformPatchId", ""));
            }
            if ("water".equals(type)
                    && patchArea >= options.normalizedNaturalWaterBoundaryMinAreaBlocks()
                    && nearPatchOuterEdge(wall, patchBounds, 16)) {
                return new NaturalBoundary(true, "NATURAL_WATER_BOUNDARY", "NATURAL_WATER_BOUNDARY_FROM_LARGE_WATER_PATCH",
                        "natural_water_boundary", stringValue(patch, "landformPatchId", ""));
            }
            if ("cliff".equals(type) && expand(patchBounds, 8).overlaps(wall)) {
                best = new NaturalBoundary(true, "NATURAL_CLIFF_BOUNDARY", "NATURAL_CLIFF_BOUNDARY_FROM_CLIFF_PATCH",
                        "natural_cliff_boundary", stringValue(patch, "landformPatchId", ""));
            }
        }
        return best;
    }

    private static boolean nearPatchOuterEdge(BlockBounds wall, BlockBounds patch, int margin) {
        BlockPoint center = wall.center();
        return Math.abs(center.x() - patch.minX()) <= margin
                || Math.abs(center.x() - patch.maxX()) <= margin
                || Math.abs(center.z() - patch.minZ()) <= margin
                || Math.abs(center.z() - patch.maxZ()) <= margin;
    }

    private static String gatehouseTemplate(BlockBounds gateBounds) {
        int width = Math.max(gateBounds.widthBlocks(), gateBounds.heightBlocks());
        return width >= 13 ? "gatehouse_13" : "gatehouse_9";
    }

    private static boolean overlapsAnyGate(BlockBounds bounds, JsonArray gates) {
        for (JsonElement elem : gates) {
            if (elem.isJsonObject() && elem.getAsJsonObject().has("blockBounds")
                    && bounds.overlaps(bounds(elem.getAsJsonObject().getAsJsonObject("blockBounds")))) {
                return true;
            }
        }
        return false;
    }

    private static boolean touchesOnlyInsideRoad(BlockBounds wall, List<RoadComponent> components,
                                                 java.util.Map<String, RoadClass> roadClasses) {
        boolean touched = false;
        BlockBounds expanded = expand(wall, DEFAULT_ROAD_PROTECTION_MARGIN_BLOCKS + 2);
        for (RoadComponent component : components) {
            if (expanded.overlaps(component.bounds())) {
                touched = true;
                if (roadClasses.getOrDefault(component.id(), RoadClass.AMBIGUOUS) != RoadClass.INSIDE) {
                    return false;
                }
            }
        }
        return touched;
    }

    private static List<BlockBounds> roadHits(BlockBounds segment, JsonArray roadMasks, int roadMargin) {
        List<BlockBounds> hits = new java.util.ArrayList<>();
        for (JsonElement elem : roadMasks) {
            if (!elem.isJsonObject()) {
                continue;
            }
            BlockBounds road = expand(bounds(elem.getAsJsonObject().getAsJsonObject("blockBounds")), roadMargin);
            if (segment.overlaps(road)) {
                hits.add(road);
            }
        }
        return hits;
    }

    private static boolean nearRoad(BlockBounds segment, JsonArray roadMasks, int margin) {
        BlockBounds expanded = expand(segment, margin);
        for (JsonElement elem : roadMasks) {
            if (elem.isJsonObject()
                    && expanded.overlaps(bounds(elem.getAsJsonObject().getAsJsonObject("blockBounds")))) {
                return true;
            }
        }
        return false;
    }

    private static BlockBounds gateBounds(BlockBounds segment, List<BlockBounds> hits, int gateWidth, int roadMargin) {
        BlockBounds union = hits.get(0);
        for (BlockBounds hit : hits) {
            union = new BlockBounds(
                    Math.min(union.minX(), hit.minX()),
                    Math.min(union.minZ(), hit.minZ()),
                    Math.max(union.maxX(), hit.maxX()),
                    Math.max(union.maxZ(), hit.maxZ()));
        }
        boolean horizontal = segment.widthBlocks() >= segment.heightBlocks();
        int half = Math.max(gateWidth / 2, roadMargin + 3);
        if (horizontal) {
            int cx = clamp(union.center().x(), segment.minX(), segment.maxX());
            return new BlockBounds(cx - half, segment.minZ(), cx + half, segment.maxZ());
        }
        int cz = clamp(union.center().z(), segment.minZ(), segment.maxZ());
        return new BlockBounds(segment.minX(), cz - half, segment.maxX(), cz + half);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void addHorizontal(JsonArray out, String side, int z, int startX, int endX,
                                      int gateCenterX, int gateWidth, int segmentLength) {
        int step = startX <= endX ? segmentLength : -segmentLength;
        for (int x = startX; step > 0 ? x <= endX : x >= endX; x += step) {
            int x2 = step > 0 ? Math.min(endX, x + segmentLength - 1) : Math.max(endX, x - segmentLength + 1);
            if (gateCenterX != Integer.MIN_VALUE && overlapsGate(Math.min(x, x2), Math.max(x, x2),
                    gateCenterX, gateWidth)) {
                addGap(out, side + "_gate_gap", Math.min(x, x2), z - WALL_HALF_THICKNESS_BLOCKS,
                        Math.max(x, x2), z + WALL_HALF_THICKNESS_BLOCKS);
            } else {
                addWall(out, side + "_" + out.size(), "wall_straight_15", Math.min(x, x2),
                        z - WALL_HALF_THICKNESS_BLOCKS, Math.max(x, x2), z + WALL_HALF_THICKNESS_BLOCKS);
            }
        }
    }

    private static void addVertical(JsonArray out, String side, int x, int startZ, int endZ,
                                    int gateCenterZ, int gateWidth, int segmentLength) {
        int step = startZ <= endZ ? segmentLength : -segmentLength;
        for (int z = startZ; step > 0 ? z <= endZ : z >= endZ; z += step) {
            int z2 = step > 0 ? Math.min(endZ, z + segmentLength - 1) : Math.max(endZ, z - segmentLength + 1);
            if (gateCenterZ != Integer.MIN_VALUE && overlapsGate(Math.min(z, z2), Math.max(z, z2),
                    gateCenterZ, gateWidth)) {
                addGap(out, side + "_gate_gap", x - WALL_HALF_THICKNESS_BLOCKS, Math.min(z, z2),
                        x + WALL_HALF_THICKNESS_BLOCKS, Math.max(z, z2));
            } else {
                addWall(out, side + "_" + out.size(), "wall_straight_15",
                        x - WALL_HALF_THICKNESS_BLOCKS, Math.min(z, z2),
                        x + WALL_HALF_THICKNESS_BLOCKS, Math.max(z, z2));
            }
        }
    }

    private static boolean overlapsGate(int min, int max, int center, int width) {
        int gateMin = center - width / 2;
        int gateMax = center + width / 2;
        return min <= gateMax && max >= gateMin;
    }

    private static void addWall(JsonArray out, String segmentId, String templateId,
                                int minX, int minZ, int maxX, int maxZ) {
        JsonObject obj = segment(segmentId, "wall_segment", templateId, minX, minZ, maxX, maxZ);
        out.add(obj);
    }

    private static void addGap(JsonArray out, String segmentId, int minX, int minZ, int maxX, int maxZ) {
        JsonObject obj = segment(segmentId, "gate_gap", "wall_gap_gate_7", minX, minZ, maxX, maxZ);
        out.add(obj);
    }

    private static void addTower(JsonArray out, String segmentId, int x, int z) {
        JsonObject obj = segment(segmentId, "tower", "wall_tower_small", x - 2, z - 2, x + 2, z + 2);
        out.add(obj);
    }

    private static JsonObject segment(String id, String type, String templateId,
                                      int minX, int minZ, int maxX, int maxZ) {
        JsonObject obj = new JsonObject();
        BlockBounds bounds = new BlockBounds(Math.min(minX, maxX), Math.min(minZ, maxZ),
                Math.max(minX, maxX), Math.max(minZ, maxZ));
        obj.addProperty("segmentId", id);
        obj.addProperty("segmentType", type);
        obj.addProperty("templateId", templateId);
        obj.addProperty("wallAxis", wallAxis(bounds));
        obj.add("blockBounds", boundsJson(bounds));
        return obj;
    }

    private static String wallAxis(BlockBounds bounds) {
        return bounds.widthBlocks() >= bounds.heightBlocks() ? "X" : "Z";
    }

    private static BlockBounds unionPlaced(JsonObject ledger) {
        BlockBounds union = null;
        JsonArray placed = ledger != null && ledger.has("placedStructures")
                && ledger.get("placedStructures").isJsonArray()
                ? ledger.getAsJsonArray("placedStructures")
                : new JsonArray();
        for (JsonElement elem : placed) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("actualFootprint")) {
                continue;
            }
            BlockBounds bounds = bounds(elem.getAsJsonObject().getAsJsonObject("actualFootprint"));
            union = union == null ? bounds : new BlockBounds(
                    Math.min(union.minX(), bounds.minX()),
                    Math.min(union.minZ(), bounds.minZ()),
                    Math.max(union.maxX(), bounds.maxX()),
                    Math.max(union.maxZ(), bounds.maxZ()));
        }
        return union == null ? new BlockBounds(-32, -32, 32, 32) : union;
    }

    private static BlockBounds expand(BlockBounds bounds, int margin) {
        return new BlockBounds(bounds.minX() - margin, bounds.minZ() - margin,
                bounds.maxX() + margin, bounds.maxZ() + margin);
    }

    private static BlockBounds union(BlockBounds a, BlockBounds b) {
        return new BlockBounds(
                Math.min(a.minX(), b.minX()),
                Math.min(a.minZ(), b.minZ()),
                Math.max(a.maxX(), b.maxX()),
                Math.max(a.maxZ(), b.maxZ()));
    }

    private static JsonObject emptyRoadMask(String cityId) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", "city_actual_road_mask.v0.2");
        obj.addProperty("cityId", cityId);
        obj.addProperty("status", "empty");
        obj.addProperty("reasonCode", "ROAD_MASK_EMPTY_GATE_FALLBACK");
        obj.add("roadMask", new JsonArray());
        return obj;
    }

    private static JsonArray array(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray()
                ? obj.getAsJsonArray(key)
                : new JsonArray();
    }

    private static BlockBounds snap(BlockBounds bounds, int step) {
        return new BlockBounds(
                Math.floorDiv(bounds.minX(), step) * step,
                Math.floorDiv(bounds.minZ(), step) * step,
                Math.floorDiv(bounds.maxX() + step - 1, step) * step - 1,
                Math.floorDiv(bounds.maxZ() + step - 1, step) * step - 1);
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(intValue(obj, "minX", 0), intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0), intValue(obj, "maxZ", 0));
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private static int intValue(JsonObject obj, String key, int fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback;
    }

    private static String stringValue(JsonObject obj, String key, String fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }

    public record V3Options(int gateClusterRadiusBlocks,
                            int terrainFitUnitLengthBlocks,
                            String wallTerrainPolicy,
                            int flatMaxDeltaBlocks,
                            int steppedMaxDeltaBlocks,
                            int mountainProbeDistanceBlocks,
                            int naturalBoundaryMinDeltaBlocks,
                            boolean embeddedSlopeTower,
                            String wallDesignPolicy,
                            int minGateSpacingBlocks,
                            int minGateRoadLengthBlocks,
                            int naturalWaterBoundaryMinAreaBlocks,
                            int roadProjectionMaxDistanceBlocks) {
        public V3Options(int gateClusterRadiusBlocks, int terrainFitUnitLengthBlocks) {
            this(gateClusterRadiusBlocks, terrainFitUnitLengthBlocks,
                    DEFAULT_WALL_TERRAIN_POLICY,
                    DEFAULT_FLAT_MAX_DELTA_BLOCKS,
                    DEFAULT_STEPPED_MAX_DELTA_BLOCKS,
                    DEFAULT_MOUNTAIN_PROBE_DISTANCE_BLOCKS,
                    DEFAULT_NATURAL_BOUNDARY_MIN_DELTA_BLOCKS,
                    true,
                    DEFAULT_WALL_DESIGN_POLICY,
                    DEFAULT_MIN_GATE_SPACING_BLOCKS,
                    DEFAULT_MIN_GATE_ROAD_LENGTH_BLOCKS,
                    DEFAULT_NATURAL_WATER_BOUNDARY_MIN_AREA_BLOCKS,
                    DEFAULT_ROAD_PROJECTION_MAX_DISTANCE_BLOCKS);
        }

        public V3Options(int gateClusterRadiusBlocks,
                         int terrainFitUnitLengthBlocks,
                         String wallTerrainPolicy,
                         int flatMaxDeltaBlocks,
                         int steppedMaxDeltaBlocks,
                         int mountainProbeDistanceBlocks,
                         int naturalBoundaryMinDeltaBlocks,
                         boolean embeddedSlopeTower) {
            this(gateClusterRadiusBlocks, terrainFitUnitLengthBlocks, wallTerrainPolicy, flatMaxDeltaBlocks,
                    steppedMaxDeltaBlocks, mountainProbeDistanceBlocks, naturalBoundaryMinDeltaBlocks,
                    embeddedSlopeTower, DEFAULT_WALL_DESIGN_POLICY, DEFAULT_MIN_GATE_SPACING_BLOCKS,
                    DEFAULT_MIN_GATE_ROAD_LENGTH_BLOCKS, DEFAULT_NATURAL_WATER_BOUNDARY_MIN_AREA_BLOCKS,
                    DEFAULT_ROAD_PROJECTION_MAX_DISTANCE_BLOCKS);
        }

        public V3Options(int gateClusterRadiusBlocks,
                         int terrainFitUnitLengthBlocks,
                         String wallTerrainPolicy,
                         int flatMaxDeltaBlocks,
                         int steppedMaxDeltaBlocks,
                         int mountainProbeDistanceBlocks,
                         int naturalBoundaryMinDeltaBlocks,
                         boolean embeddedSlopeTower,
                         String wallDesignPolicy,
                         int minGateSpacingBlocks,
                         int minGateRoadLengthBlocks,
                         int naturalWaterBoundaryMinAreaBlocks) {
            this(gateClusterRadiusBlocks, terrainFitUnitLengthBlocks, wallTerrainPolicy, flatMaxDeltaBlocks,
                    steppedMaxDeltaBlocks, mountainProbeDistanceBlocks, naturalBoundaryMinDeltaBlocks,
                    embeddedSlopeTower, wallDesignPolicy, minGateSpacingBlocks, minGateRoadLengthBlocks,
                    naturalWaterBoundaryMinAreaBlocks, DEFAULT_ROAD_PROJECTION_MAX_DISTANCE_BLOCKS);
        }

        public static V3Options defaults() {
            return new V3Options(DEFAULT_GATE_CLUSTER_RADIUS_BLOCKS, DEFAULT_TERRAIN_FIT_UNIT_LENGTH_BLOCKS);
        }

        public String normalizedWallTerrainPolicy() {
            return "v3.1".equalsIgnoreCase(wallTerrainPolicy == null ? "" : wallTerrainPolicy.trim())
                    ? "v3.1" : DEFAULT_WALL_TERRAIN_POLICY;
        }

        public String normalizedWallDesignPolicy() {
            String normalized = wallDesignPolicy == null ? "" : wallDesignPolicy.trim();
            if ("v3.3".equalsIgnoreCase(normalized)) {
                return "v3.3";
            }
            return "v3.2".equalsIgnoreCase(normalized) ? "v3.2" : DEFAULT_WALL_DESIGN_POLICY;
        }

        public boolean isV32DesignPolicy() {
            return "v3.2".equals(normalizedWallDesignPolicy()) || "v3.3".equals(normalizedWallDesignPolicy());
        }

        public boolean isV33DesignPolicy() {
            return "v3.3".equals(normalizedWallDesignPolicy());
        }

        public int normalizedFlatMaxDeltaBlocks() {
            return flatMaxDeltaBlocks <= 0 ? DEFAULT_FLAT_MAX_DELTA_BLOCKS : flatMaxDeltaBlocks;
        }

        public int normalizedSteppedMaxDeltaBlocks() {
            return steppedMaxDeltaBlocks <= 0
                    ? DEFAULT_STEPPED_MAX_DELTA_BLOCKS
                    : Math.max(normalizedFlatMaxDeltaBlocks(), steppedMaxDeltaBlocks);
        }

        public int normalizedMountainProbeDistanceBlocks() {
            return mountainProbeDistanceBlocks <= 0
                    ? DEFAULT_MOUNTAIN_PROBE_DISTANCE_BLOCKS
                    : mountainProbeDistanceBlocks;
        }

        public int normalizedNaturalBoundaryMinDeltaBlocks() {
            return naturalBoundaryMinDeltaBlocks <= 0
                    ? DEFAULT_NATURAL_BOUNDARY_MIN_DELTA_BLOCKS
                    : Math.max(normalizedSteppedMaxDeltaBlocks() + 1, naturalBoundaryMinDeltaBlocks);
        }

        public int normalizedMinGateSpacingBlocks() {
            return minGateSpacingBlocks <= 0 ? DEFAULT_MIN_GATE_SPACING_BLOCKS : minGateSpacingBlocks;
        }

        public int normalizedMinGateRoadLengthBlocks() {
            return minGateRoadLengthBlocks <= 0 ? DEFAULT_MIN_GATE_ROAD_LENGTH_BLOCKS : minGateRoadLengthBlocks;
        }

        public int normalizedNaturalWaterBoundaryMinAreaBlocks() {
            return naturalWaterBoundaryMinAreaBlocks <= 0
                    ? DEFAULT_NATURAL_WATER_BOUNDARY_MIN_AREA_BLOCKS
                    : naturalWaterBoundaryMinAreaBlocks;
        }

        public int normalizedRoadProjectionMaxDistanceBlocks() {
            return roadProjectionMaxDistanceBlocks <= 0
                    ? DEFAULT_ROAD_PROJECTION_MAX_DISTANCE_BLOCKS
                    : roadProjectionMaxDistanceBlocks;
        }
    }

    private record RoadComponent(String id, BlockBounds bounds, int blockCount) {
    }

    private record GateCluster(String roadComponentId, BlockBounds bounds, String reasonCode) {
    }

    private record ProjectedGateCandidate(boolean accepted, BlockBounds gateBounds, JsonObject report) {
    }

    private record RoadTrend(String trendClass, int crossingLengthBlocks, boolean touchOnly) {
        boolean opensGate(int minLengthBlocks) {
            return !touchOnly && crossingLengthBlocks >= Math.max(1, minLengthBlocks)
                    && ("external_to_internal".equals(trendClass) || "touches_outer_boundary".equals(trendClass));
        }
    }

    private record NaturalBoundary(boolean applies,
                                   String boundaryType,
                                   String reasonCode,
                                   String templateId,
                                   String sourcePatchRef) {
        static NaturalBoundary none() {
            return new NaturalBoundary(false, "", "", "", "");
        }
    }

    private enum RoadClass {
        INSIDE("insideRoad"),
        EXTERNAL("externalApproachRoad"),
        AMBIGUOUS("ambiguousRoad");

        private final String contractName;

        RoadClass(String contractName) {
            this.contractName = contractName;
        }
    }
}
