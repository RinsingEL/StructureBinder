package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
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
    public static final int DEFAULT_WALL_UNIT_LENGTH_BLOCKS = 16;
    public static final int DEFAULT_WATER_RUN_MIN_UNITS = 3;
    public static final int DEFAULT_WATER_RETREAT_MAX_CELLS = 4;
    public static final int DEFAULT_STRUCTURE_WALL_BREATHING_ROOM_BLOCKS = 32;
    public static final int DEFAULT_HEIGHT_DATUM_CLAMP_BLOCKS = 6;
    public static final int DEFAULT_LOCAL_MEDIAN_WINDOW_UNITS = 3;
    public static final int DEFAULT_V5_WALL_UNIT_LENGTH_BLOCKS = 8;
    public static final int DEFAULT_V5_NOMINAL_WALL_HEIGHT_BLOCKS = 9;
    public static final int DEFAULT_V5_WATER_RUN_MIN_BLOCKS = 32;
    public static final double DEFAULT_V5_WATER_FLUID_RATIO_MIN = 0.8D;
    public static final int DEFAULT_V5_SEGMENT_MAX_DELTA_BLOCKS = DEFAULT_FLAT_MAX_DELTA_BLOCKS;
    public static final int DEFAULT_V5_STEPPED_TRANSITION_MAX_DELTA_BLOCKS = DEFAULT_STEPPED_MAX_DELTA_BLOCKS;
    public static final int DEFAULT_V5_NATURAL_BOUNDARY_MIN_DELTA_BLOCKS = DEFAULT_NATURAL_BOUNDARY_MIN_DELTA_BLOCKS;
    private static final int V4_NODE_INTERVAL_UNITS = 4;
    private static final int V4_TERRAIN_CONTOUR_MAX_SHIFT_UNITS = 2;
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
        plan.add("templateLibrary", CityWallTemplateCatalog.libraryJson());
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
        plan.add("templateLibrary", CityWallTemplateCatalog.libraryJson());
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
        plan.add("templateLibrary", CityWallTemplateCatalog.libraryJson());
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

    public JsonObject planV4(JsonObject placedLedger,
                             JsonObject wallReservationPlan,
                             JsonObject actualRoadMask,
                             int gateWidthBlocks,
                             int roadProtectionMarginBlocks,
                             int maxFoundationDepthBlocks,
                             int maxSegmentHeightDeltaBlocks,
                             V3Options terrainOptions,
                             V4Options options) {
        V4Options opts = options == null ? V4Options.defaults() : options;
        V3Options terrainOpts = terrainOptions == null ? V3Options.defaults() : terrainOptions;
        int unitLength = opts.normalizedWallUnitLengthBlocks();
        int roadMargin = roadProtectionMarginBlocks <= 0
                ? DEFAULT_ROAD_PROTECTION_MARGIN_BLOCKS : roadProtectionMarginBlocks;
        int gateWidth = gateWidthBlocks <= 0 ? 9 : gateWidthBlocks;
        JsonObject roadMask = actualRoadMask == null
                ? emptyRoadMask(stringValue(placedLedger, "cityId", "unknown_city"))
                : actualRoadMask.deepCopy();
        BlockBounds footprintUnion = unionPlaced(placedLedger);
        BlockBounds wallBounds = snap(expand(snap(footprintUnion, unitLength),
                opts.normalizedStructureWallBreathingRoomBlocks()), unitLength);

        java.util.List<BlockBounds> roads = v4RoadBounds(roadMask);
        java.util.List<BlockBounds> footprints = footprintBounds(placedLedger);
        java.util.List<BlockBounds> waterPatches = v4WaterPatches(wallReservationPlan);
        java.util.List<V4UnitDraft> drafts = v4RingUnits(wallBounds, unitLength);
        BlockBounds knownPatchBounds = v4KnownPatchBounds(wallReservationPlan, wallBounds);
        JsonArray terrainContourEvents = adaptV4TerrainContour(drafts, wallReservationPlan, footprintUnion,
                footprints, knownPatchBounds, waterPatches, opts);
        JsonArray waterRetreatEvents = retreatWaterRuns(drafts, waterPatches, opts, knownPatchBounds);

        java.util.List<Integer> surfaces = new java.util.ArrayList<>();
        for (V4UnitDraft draft : drafts) {
            draft.surfaceMedianY = estimatedSurfaceY(draft.bounds);
            surfaces.add(draft.surfaceMedianY);
        }
        int datumY = trimmedMedian(surfaces, 64);
        for (int i = 0; i < drafts.size(); i++) {
            V4UnitDraft draft = drafts.get(i);
            draft.localMedianY = localMedianY(drafts, i, opts.normalizedLocalMedianWindowUnits(), datumY);
            draft.targetY = clamp(draft.localMedianY, datumY - opts.normalizedHeightDatumClampBlocks(),
                    datumY + opts.normalizedHeightDatumClampBlocks());
        }

        JsonArray nodes = new JsonArray();
        JsonArray wallUnits = new JsonArray();
        JsonArray connectorUnits = new JsonArray();
        JsonArray generatedGates = new JsonArray();
        JsonArray validationBreaks = new JsonArray();
        int ordinaryWaterHits = 0;
        int heightBreaks = 0;
        int naturalBoundaryGaps = 0;
        int skippedMaskBreaks = 0;
        int terraceCount = 0;

        for (int i = 0; i < drafts.size(); i++) {
            V4UnitDraft draft = drafts.get(i);
            String nodeType = v4NodeType(drafts, i);
            if (overlapsAny(expand(draft.bounds, roadMargin), roads)) {
                nodeType = "gatehouse";
            }
            BlockPoint nodePoint = v4NodePoint(drafts, i);
            BlockBounds nodeBounds = v4NodeBounds(nodeType, draft, nodePoint);
            nodes.add(v4Node("wall_node_" + i, nodeType, nodePoint.x(), nodePoint.z(), nodeBounds,
                    draft.surfaceMedianY, draft.targetY, draft.nodeHeightMode()));
        }

        for (int i = 0; i < drafts.size(); i++) {
            V4UnitDraft draft = drafts.get(i);
            V4UnitDraft next = drafts.get((i + 1) % drafts.size());
            boolean roadOverlap = overlapsAny(expand(draft.bounds, roadMargin), roads);
            boolean structureOverlap = overlapsAny(draft.bounds, footprints);
            boolean waterOverlap = overlapsAny(draft.bounds, waterPatches);
            JsonObject unit = new JsonObject();
            unit.addProperty("unitId", draft.unitId);
            unit.addProperty("unitType", "wall_unit");
            unit.addProperty("fromNodeId", "wall_node_" + i);
            unit.addProperty("toNodeId", "wall_node_" + ((i + 1) % drafts.size()));
            unit.addProperty("side", draft.side);
            unit.addProperty("wallAxis", draft.axis);
            unit.addProperty("wallUnitLengthBlocks", unitLength);
            unit.add("blockBounds", boundsJson(draft.bounds));
            unit.addProperty("surfaceMedianY", draft.surfaceMedianY);
            unit.addProperty("localMedianY", draft.localMedianY);
            unit.addProperty("targetY", draft.targetY);
            unit.addProperty("toTargetY", next.targetY);
            unit.addProperty("heightDeltaToNextNode", Math.abs(next.targetY - draft.targetY));
            unit.addProperty("heightMode", "flat_wall");
            unit.addProperty("placementAllowed", true);
            unit.addProperty("waterOverlapAfterRetreat", waterOverlap);
            unit.addProperty("waterRetreated", draft.waterRetreated);
            unit.addProperty("waterRetreatSteps", draft.waterRetreatSteps);
            unit.addProperty("terrainContourAdjusted", draft.terrainContoured);
            unit.addProperty("terrainContourLink", draft.terrainContourLink);
            unit.addProperty("terrainContourShiftBlocks", draft.terrainContourShiftBlocks);
            unit.addProperty("terrainContourReason", draft.terrainContourReason);
            unit.addProperty("templateId", "wall_straight_15");
            unit.addProperty("reasonCode", "V4_LAND_RING_WALL_UNIT");

            if (draft.naturalBoundary) {
                unit.addProperty("unitType", "natural_boundary_gap");
                unit.addProperty("placementAllowed", false);
                unit.addProperty("heightMode", "natural_boundary");
                unit.addProperty("reasonCode", "NATURAL_WATER_BOUNDARY_GAP_AFTER_RETREAT_FAIL");
                addValidationBreak(validationBreaks, draft.unitId, "NATURAL_WATER_BOUNDARY_GAP",
                        "Continuous water run could not retreat to land side.", draft.bounds);
                naturalBoundaryGaps++;
            } else if (roadOverlap) {
                unit.addProperty("unitType", "skipped_wall_unit");
                unit.addProperty("placementAllowed", false);
                unit.addProperty("heightMode", "gatehouse_opening");
                unit.addProperty("reasonCode", "ROAD_MASK_GATEHOUSE_OPENING");
                addValidationBreak(validationBreaks, draft.unitId, "ROAD_MASK_GATEHOUSE_OPENING",
                        "Wall unit intersects protected actual road mask; gatehouse node owns the opening.",
                        draft.bounds);
                addGeneratedGate(generatedGates, draft, gateWidth);
                skippedMaskBreaks++;
            } else if (structureOverlap) {
                unit.addProperty("unitType", "skipped_wall_unit");
                unit.addProperty("placementAllowed", false);
                unit.addProperty("heightMode", "blocked_by_structure_mask");
                unit.addProperty("reasonCode", "STRUCTURE_MASK_WALL_UNIT_SKIP");
                addValidationBreak(validationBreaks, draft.unitId, "STRUCTURE_MASK_WALL_UNIT_SKIP",
                        "Wall unit intersects placed structure actualFootprint.", draft.bounds);
                skippedMaskBreaks++;
            } else {
                int delta = Math.abs(next.targetY - draft.targetY);
                if (delta > 6) {
                    String terraceId = "terrace_node_" + terraceCount++;
                    unit.addProperty("unitType", "terraced_wall_unit");
                    unit.addProperty("heightMode", "terrace_node_inserted");
                    unit.addProperty("reasonCode", "HEIGHT_BREAK_TERRACE_INSERTED");
                    unit.addProperty("terraceNodeId", terraceId);
                    int terraceY = (draft.targetY + next.targetY) / 2;
                    nodes.add(v4Node(terraceId, "terrace_node", draft.bounds.center().x(), draft.bounds.center().z(),
                            towerBounds(draft.bounds.center().x(), draft.bounds.center().z(), 5),
                            draft.surfaceMedianY, terraceY, "elevation_band"));
                    connectorUnits.add(v4Connector("connector_" + connectorUnits.size(), terraceId,
                            "terrace_node", draft.bounds.center(), towerBounds(draft.bounds.center().x(),
                                    draft.bounds.center().z(), 3),
                            draft.surfaceMedianY, terraceY, "stepped", "stair_link"));
                    addValidationBreak(validationBreaks, draft.unitId, "HEIGHT_BREAK_TERRACE_INSERTED",
                            "Adjacent node targetY delta exceeded 6 blocks; inserted terrace_node.", draft.bounds);
                    heightBreaks++;
                } else if (delta >= 3) {
                    unit.addProperty("unitType", "stepped_wall_unit");
                    unit.addProperty("heightMode", "stepped_wall_unit");
                    unit.addProperty("reasonCode", "HEIGHT_DELTA_STEPPED_UNIT");
                    heightBreaks++;
                }
                if (waterOverlap) {
                    ordinaryWaterHits++;
                }
            }
            wallUnits.add(unit);
        }

        for (int i = 0; i < drafts.size(); i++) {
            V4UnitDraft draft = drafts.get(i);
            String nodeType = v4NodeType(drafts, i);
            if (draft.naturalBoundary) {
                connectorUnits.add(v4Connector("connector_" + connectorUnits.size(), "wall_node_" + i,
                        nodeType, v4NodePoint(drafts, i), v4ConnectorBounds(draft),
                        draft.surfaceMedianY, draft.targetY,
                        "skipped", "natural_boundary_endpoint"));
                continue;
            }
            V4UnitDraft prev = drafts.get((i - 1 + drafts.size()) % drafts.size());
            int connectorDelta = Math.max(Math.abs(draft.targetY - prev.targetY),
                    Math.abs(draft.targetY - drafts.get((i + 1) % drafts.size()).targetY));
            String status = connectorDelta >= 3 ? "stepped" : "connected";
            connectorUnits.add(v4Connector("connector_" + connectorUnits.size(), "wall_node_" + i,
                    nodeType, v4NodePoint(drafts, i), v4ConnectorBounds(draft),
                    draft.surfaceMedianY, draft.targetY,
                    status, connectorDelta >= 3 ? "stair_link" : "short_wall_link"));
        }

        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", "city_wall_plan.v0.4");
        plan.addProperty("cityId", stringValue(placedLedger, "cityId", "unknown_city"));
        plan.addProperty("wallVersion", "v4");
        plan.addProperty("boundaryMode", "actual_footprint_land_ring");
        plan.addProperty("wallBoundaryMode", "actual_footprint_land_ring");
        plan.addProperty("wallPlanningMode", "actual_footprint_land_ring_wall_graph");
        plan.addProperty("wallContourMode", "terrain_adaptive_domain_guided_land_ring");
        plan.addProperty("terrainContourMaxShiftBlocks",
                opts.normalizedWallUnitLengthBlocks() * V4_TERRAIN_CONTOUR_MAX_SHIFT_UNITS);
        plan.addProperty("roadMaskSource", "actual_world_blocks");
        plan.addProperty("wallUnitLengthBlocks", unitLength);
        plan.addProperty("waterRunMinUnits", opts.normalizedWaterRunMinUnits());
        plan.addProperty("waterRetreatMaxCells", opts.normalizedWaterRetreatMaxCells());
        plan.addProperty("structureWallBreathingRoomBlocks", opts.normalizedStructureWallBreathingRoomBlocks());
        plan.addProperty("cityWallDatumY", datumY);
        plan.addProperty("heightDatumClampBlocks", opts.normalizedHeightDatumClampBlocks());
        plan.addProperty("localMedianWindowUnits", opts.normalizedLocalMedianWindowUnits());
        plan.addProperty("gateWidthBlocks", gateWidth);
        plan.addProperty("roadProtectionMarginBlocks", roadMargin);
        plan.addProperty("maxFoundationDepthBlocks", maxFoundationDepthBlocks <= 0 ? 8 : maxFoundationDepthBlocks);
        plan.addProperty("maxSegmentHeightDeltaBlocks", maxSegmentHeightDeltaBlocks <= 0 ? 7 : maxSegmentHeightDeltaBlocks);
        plan.addProperty("wallTerrainPolicy", terrainOpts.normalizedWallTerrainPolicy());
        plan.add("wallReservationSource", wallReservationPlan == null ? new JsonObject() : wallReservationPlan.deepCopy());
        plan.add("actualRoadMask", roadMask);
        plan.add("sourcePlacedStructureLedger", placedLedger == null ? new JsonObject() : placedLedger.deepCopy());
        plan.add("sourceActualFootprintUnion", boundsJson(footprintUnion));
        if (wallReservationPlan != null && wallReservationPlan.has("wallBounds")) {
            plan.add("sourcePatchContextBounds", wallReservationPlan.getAsJsonObject("wallBounds").deepCopy());
        }
        plan.add("knownPatchBounds", boundsJson(knownPatchBounds));
        plan.add("wallBounds", boundsJson(wallBounds));
        plan.add("terrainContourEvents", terrainContourEvents);
        plan.add("waterRetreatEvents", waterRetreatEvents);
        plan.add("generatedGates", generatedGates);
        plan.add("wallNodes", nodes);
        plan.add("wallUnits", wallUnits);
        plan.add("nodeConnectorUnits", connectorUnits);
        plan.add("wallSegments", new JsonArray());
        plan.add("templateLibrary", CityWallTemplateCatalog.libraryJson());

        JsonObject terrain = new JsonObject();
        terrain.addProperty("policyVersion", terrainOpts.normalizedWallTerrainPolicy());
        terrain.addProperty("heightStrategy", "global_datum_plus_local_median");
        terrain.addProperty("cityWallDatumY", datumY);
        terrain.addProperty("heightDatumClampBlocks", opts.normalizedHeightDatumClampBlocks());
        terrain.addProperty("localMedianWindowUnits", opts.normalizedLocalMedianWindowUnits());
        terrain.addProperty("connectorMode", "node_connector_units");
        terrain.addProperty("foundationMode", "per_graph_unit_column_foundation");
        terrain.addProperty("slopeMode", "flat_stepped_terrace_or_natural_boundary");
        terrain.addProperty("roadProtection", true);
        terrain.addProperty("debugScanSupported", true);
        plan.add("terrainFitPolicy", terrain);

        JsonObject validation = new JsonObject();
        JsonArray outsideKnownPatchUnits = outsideKnownPatchUnits(wallUnits, knownPatchBounds);
        validation.addProperty("status", validationBreaks.isEmpty() && ordinaryWaterHits == 0 ? "valid" : "valid_with_breaks");
        validation.addProperty("closedLoopCandidate", naturalBoundaryGaps == 0);
        validation.addProperty("wallNodeCount", nodes.size());
        validation.addProperty("wallUnitCount", wallUnits.size());
        validation.addProperty("nodeConnectorUnitCount", connectorUnits.size());
        validation.addProperty("actualFootprintInsideKnownPatch", containsBounds(knownPatchBounds, footprintUnion));
        validation.addProperty("wallBoundsInsideKnownPatch", containsBounds(knownPatchBounds, wallBounds));
        validation.addProperty("outsideKnownPatchUnitCount", outsideKnownPatchUnits.size());
        validation.addProperty("terrainContourAdjustedUnitCount", countTerrainContoured(drafts));
        validation.addProperty("terrainContourLinkUnitCount", countTerrainContourLinks(drafts));
        validation.addProperty("ordinaryWallUnitsInWater", ordinaryWaterHits);
        validation.addProperty("naturalBoundaryGapCount", naturalBoundaryGaps);
        validation.addProperty("skippedMaskBreakCount", skippedMaskBreaks);
        validation.addProperty("heightBreakCount", heightBreaks);
        validation.add("outsideKnownPatchUnits", outsideKnownPatchUnits);
        validation.add("breaks", validationBreaks);
        plan.add("wallGraphValidation", validation);
        return plan;
    }

    public JsonObject planV5(JsonObject placedLedger,
                             JsonObject wallReservationPlan,
                             JsonObject actualRoadMask,
                             V5Options options) {
        V5Options opts = options == null ? V5Options.defaults() : options;
        if (wallReservationPlan == null
                || !"v5".equalsIgnoreCase(stringValue(wallReservationPlan, "wallVersion", ""))) {
            throw new IllegalArgumentException("WALL_V5_REQUIRES_D5_V5_RESERVATION: Run city_plan_d5 wallVersion=v5 first.");
        }
        JsonArray line = array(wallReservationPlan, "wallLine");
        if (line.isEmpty()) {
            line = array(wallReservationPlan, "wallCenterline");
        }
        if (line.isEmpty()) {
            throw new IllegalArgumentException("WALL_V5_WALL_LINE_UNAVAILABLE: D5 v5 reservation has no wallLine.");
        }

        BlockBounds coverage = wallReservationPlan.has("wallCoverageBounds")
                && wallReservationPlan.get("wallCoverageBounds").isJsonObject()
                ? bounds(wallReservationPlan.getAsJsonObject("wallCoverageBounds"))
                : wallReservationPlan.has("wallBounds") && wallReservationPlan.get("wallBounds").isJsonObject()
                ? expand(bounds(wallReservationPlan.getAsJsonObject("wallBounds")), DEFAULT_V5_WALL_UNIT_LENGTH_BLOCKS)
                : expand(unionPlaced(placedLedger), DEFAULT_WALL_MARGIN_BLOCKS);
        JsonArray footprintViolations = v5LockedFootprintViolations(placedLedger, coverage);
        if (!footprintViolations.isEmpty()) {
            throw new IllegalArgumentException("D5_V5_LOCKED_FOOTPRINT_OUTSIDE_RESERVATION: D7 actual/locked footprint exceeds D5 v5 coverage.");
        }

        int unitLength = opts.normalizedWallUnitLengthBlocks();
        JsonArray gateSlots = array(wallReservationPlan, "gateSlots");
        JsonArray generatedGates = new JsonArray();
        JsonArray wallUnits = new JsonArray();
        int unitIndex = 0;
        for (JsonElement elem : line) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("blockBounds")) {
                continue;
            }
            JsonObject sourceLine = elem.getAsJsonObject();
            BlockBounds sourceBounds = bounds(sourceLine.getAsJsonObject("blockBounds"));
            String axis = wallAxis(sourceBounds);
            BlockBounds placementBounds = v5PlacementBounds(sourceBounds, axis);
            for (BlockBounds unitBounds : splitBounds(placementBounds, unitLength, axis)) {
                JsonObject unit = new JsonObject();
                unit.addProperty("unitId", "v5_wall_unit_" + unitIndex++);
                unit.addProperty("sourceLineId", stringValue(sourceLine, "lineId",
                        stringValue(sourceLine, "segmentId", "")));
                unit.addProperty("sideHint", stringValue(sourceLine, "sideHint", ""));
                unit.addProperty("wallAxis", axis);
                unit.addProperty("heightMode", "surface_cache_1_block_median_at_execute");
                unit.addProperty("targetY", 0);
                unit.add("blockBounds", boundsJson(unitBounds));
                JsonObject gate = overlappingGate(unitBounds, gateSlots);
                if (gate != null) {
                    unit.addProperty("unitType", "gate_gap");
                    unit.addProperty("templateId", "wall_gap_gate_9");
                    unit.addProperty("placementAllowed", false);
                    unit.addProperty("reasonCode", "D5_V5_GATE_SLOT_OPENING");
                    unit.addProperty("gateSlotId", stringValue(gate, "gateSlotId", ""));
                    addGeneratedGateV5(generatedGates, gate, unitBounds);
                } else {
                    unit.addProperty("unitType", "wall_unit_v5");
                    unit.addProperty("templateId", "wall_straight_8");
                    unit.addProperty("placementAllowed", true);
                    unit.addProperty("reasonCode", "D5_V5_WALL_LINE_UNIT");
                }
                wallUnits.add(unit);
            }
        }

        JsonArray wallNodes = new JsonArray();
        int nodeIndex = 0;
        for (JsonElement elem : array(wallReservationPlan, "wallNodeSlots")) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("blockBounds")) {
                continue;
            }
            JsonObject slot = elem.getAsJsonObject();
            BlockBounds bounds = bounds(slot.getAsJsonObject("blockBounds"));
            JsonObject node = v4Node("v5_wall_node_" + nodeIndex++,
                    stringValue(slot, "nodeType", "beacon_tower"),
                    bounds.center().x(), bounds.center().z(), bounds, 0, 0,
                    "surface_cache_1_block_median_at_execute");
            node.addProperty("sourceNodeSlotId", stringValue(slot, "nodeSlotId", ""));
            node.addProperty("reasonCode", stringValue(slot, "reasonCode", "D5_V5_WALL_NODE_SLOT"));
            node.addProperty("wallAxis", wallAxisForNode(bounds, line));
            wallNodes.add(node);
        }

        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", "city_wall_plan.v0.5");
        plan.addProperty("cityId", stringValue(placedLedger, "cityId", "unknown_city"));
        plan.addProperty("wallVersion", "v5");
        plan.addProperty("boundaryMode", "d5_final_wall_line");
        plan.addProperty("wallBoundaryMode", "d5_final_wall_line");
        plan.addProperty("wallPlanningMode", "d5_plane_then_surface_cache_height_fit");
        plan.addProperty("wallContourMode", "disabled_v5_no_reline_after_d5");
        plan.addProperty("roadMaskSource", "actual_world_blocks_for_gate_conflict_diagnostics_only");
        plan.addProperty("wallUnitLengthBlocks", unitLength);
        plan.addProperty("nominalWallHeightBlocks", opts.normalizedNominalWallHeightBlocks());
        plan.addProperty("waterRunMinBlocks", opts.normalizedWaterRunMinBlocks());
        plan.addProperty("waterFluidRatioMin", opts.normalizedWaterFluidRatioMin());
        plan.addProperty("heightSegmentMaxDeltaBlocks", opts.normalizedHeightSegmentMaxDeltaBlocks());
        plan.addProperty("heightSteppedTransitionMaxDeltaBlocks",
                opts.normalizedHeightSteppedTransitionMaxDeltaBlocks());
        plan.addProperty("naturalBoundaryMinDeltaBlocks", opts.normalizedNaturalBoundaryMinDeltaBlocks());
        plan.addProperty("maxFoundationDepthBlocks", 64);
        plan.addProperty("maxSegmentHeightDeltaBlocks",
                Math.max(0, opts.normalizedNaturalBoundaryMinDeltaBlocks() - 1));
        plan.addProperty("gateFailurePolicy", "keep_gate_opening_or_downgrade_without_reline");
        plan.addProperty("beaconFailurePolicy", "downgrade_to_wall_or_skip_without_reline");
        plan.add("wallReservationSource", wallReservationPlan.deepCopy());
        plan.add("actualRoadMask", actualRoadMask == null
                ? emptyRoadMask(plan.get("cityId").getAsString()) : actualRoadMask.deepCopy());
        plan.add("sourcePlacedStructureLedger", placedLedger == null ? new JsonObject() : placedLedger.deepCopy());
        plan.add("sourceActualFootprintUnion", boundsJson(unionPlaced(placedLedger)));
        plan.add("wallCoverageBounds", boundsJson(coverage));
        if (wallReservationPlan.has("wallBounds") && wallReservationPlan.get("wallBounds").isJsonObject()) {
            plan.add("wallBounds", wallReservationPlan.getAsJsonObject("wallBounds").deepCopy());
        }
        plan.add("wallLine", line.deepCopy());
        plan.add("generatedGates", generatedGates);
        plan.add("wallNodes", wallNodes);
        plan.add("wallUnits", wallUnits);
        plan.add("nodeConnectorUnits", new JsonArray());
        plan.add("wallSegments", new JsonArray());
        plan.add("templateLibrary", CityWallTemplateCatalog.libraryJson());

        JsonObject surface = new JsonObject();
        surface.addProperty("cacheSchema", "city_surface_cache.v0.1");
        surface.addProperty("storageFormat", ".dat");
        surface.addProperty("sampleGranularityBlocks", 1);
        surface.addProperty("requiredFields", "surfaceY/topBlock/fluid/biome/temperature/flags");
        surface.addProperty("backfillStage", "d7_or_city_plan_city_walls_before_v5_execute");
        plan.add("surfaceCachePolicy", surface);

        JsonObject terrain = new JsonObject();
        terrain.addProperty("policyVersion", "v5");
        terrain.addProperty("heightStrategy", "segmented_surface_datum");
        terrain.addProperty("heightSegmentMaxDeltaBlocks", opts.normalizedHeightSegmentMaxDeltaBlocks());
        terrain.addProperty("heightSteppedTransitionMaxDeltaBlocks",
                opts.normalizedHeightSteppedTransitionMaxDeltaBlocks());
        terrain.addProperty("naturalBoundaryMinDeltaBlocks", opts.normalizedNaturalBoundaryMinDeltaBlocks());
        terrain.addProperty("transitionPolicy", "segmented_step_transition_until_natural_boundary_threshold");
        terrain.addProperty("pitPolicy", "fill_horizontal_floor_inside_corridor");
        terrain.addProperty("raisedGroundPolicy", "connect_wall_into_existing_ground");
        terrain.addProperty("waterPolicy", "continuous_water_boundary_no_wall");
        terrain.addProperty("cliffPolicy", "natural_cliff_boundary_no_wall");
        terrain.addProperty("debugScanSupported", true);
        plan.add("terrainFitPolicy", terrain);

        JsonObject validation = new JsonObject();
        validation.addProperty("status", "valid");
        validation.addProperty("d5PlaneAuthority", true);
        validation.addProperty("noRelineAfterD5", true);
        validation.addProperty("patchBoundaryUsedAsFinalWallLine", false);
        validation.addProperty("wallNodeCount", wallNodes.size());
        validation.addProperty("wallUnitCount", wallUnits.size());
        validation.addProperty("generatedGateCount", generatedGates.size());
        validation.addProperty("lockedFootprintViolationCount", footprintViolations.size());
        validation.add("lockedFootprintViolations", footprintViolations);
        plan.add("wallGraphValidation", validation);
        return plan;
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

    private static JsonArray v5LockedFootprintViolations(JsonObject ledger, BlockBounds coverage) {
        JsonArray out = new JsonArray();
        for (JsonElement elem : array(ledger, "placedStructures")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject structure = elem.getAsJsonObject();
            JsonObject source = structure.has("lockedActualFootprint")
                    && structure.get("lockedActualFootprint").isJsonObject()
                    ? structure.getAsJsonObject("lockedActualFootprint")
                    : structure.has("actualFootprint") && structure.get("actualFootprint").isJsonObject()
                    ? structure.getAsJsonObject("actualFootprint")
                    : null;
            if (source == null) {
                continue;
            }
            BlockBounds footprint = bounds(source);
            if (containsBounds(coverage, footprint)) {
                continue;
            }
            JsonObject violation = new JsonObject();
            violation.addProperty("anchorId", stringValue(structure, "anchorId", ""));
            violation.addProperty("templateId", stringValue(structure, "templateId", ""));
            violation.addProperty("reasonCode", "D5_V5_LOCKED_FOOTPRINT_OUTSIDE_RESERVATION");
            violation.add("footprint", boundsJson(footprint));
            violation.add("coverageBounds", boundsJson(coverage));
            out.add(violation);
        }
        return out;
    }

    private static BlockBounds v5PlacementBounds(BlockBounds source, String axis) {
        if ("X".equals(axis)) {
            int centerZ = source.center().z();
            return new BlockBounds(source.minX(), centerZ - WALL_HALF_THICKNESS_BLOCKS,
                    source.maxX(), centerZ + WALL_HALF_THICKNESS_BLOCKS);
        }
        int centerX = source.center().x();
        return new BlockBounds(centerX - WALL_HALF_THICKNESS_BLOCKS, source.minZ(),
                centerX + WALL_HALF_THICKNESS_BLOCKS, source.maxZ());
    }

    private static java.util.List<BlockBounds> splitBounds(BlockBounds bounds, int unitLength, String axis) {
        int length = Math.max(1, unitLength);
        java.util.List<BlockBounds> out = new java.util.ArrayList<>();
        if ("X".equals(axis)) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x += length) {
                out.add(new BlockBounds(x, bounds.minZ(), Math.min(bounds.maxX(), x + length - 1), bounds.maxZ()));
            }
        } else {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z += length) {
                out.add(new BlockBounds(bounds.minX(), z, bounds.maxX(), Math.min(bounds.maxZ(), z + length - 1)));
            }
        }
        return out;
    }

    private static JsonObject overlappingGate(BlockBounds unitBounds, JsonArray gateSlots) {
        for (JsonElement elem : gateSlots) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("blockBounds")) {
                continue;
            }
            JsonObject gate = elem.getAsJsonObject();
            if (unitBounds.overlaps(bounds(gate.getAsJsonObject("blockBounds")))) {
                return gate;
            }
        }
        return null;
    }

    private static void addGeneratedGateV5(JsonArray generatedGates, JsonObject gate, BlockBounds unitBounds) {
        String gateSlotId = stringValue(gate, "gateSlotId", "d5_v5_gate_slot");
        for (JsonElement elem : generatedGates) {
            if (elem.isJsonObject()
                    && gateSlotId.equals(stringValue(elem.getAsJsonObject(), "gateSlotId", ""))) {
                return;
            }
        }
        JsonObject generated = new JsonObject();
        generated.addProperty("gateId", "v5_gate_" + generatedGates.size());
        generated.addProperty("gateSlotId", gateSlotId);
        generated.addProperty("nodeType", "gate_opening");
        generated.addProperty("reasonCode", "D5_V5_GATE_SLOT_OPENING");
        generated.addProperty("failurePolicy", "keep_gate_opening_or_downgrade_without_reline");
        generated.addProperty("gateWidthBlocks", Math.max(unitBounds.widthBlocks(), unitBounds.heightBlocks()));
        generated.add("blockBounds", gate.getAsJsonObject("blockBounds").deepCopy());
        generatedGates.add(generated);
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

    private static String wallAxisForNode(BlockBounds nodeBounds, JsonArray wallLine) {
        String fallback = wallAxis(nodeBounds);
        int bestDistance = Integer.MAX_VALUE;
        String bestAxis = fallback;
        for (JsonElement elem : wallLine) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("blockBounds")) {
                continue;
            }
            BlockBounds lineBounds = bounds(elem.getAsJsonObject().getAsJsonObject("blockBounds"));
            int distance = distanceBetweenBounds(nodeBounds, lineBounds);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestAxis = wallAxis(lineBounds);
            }
        }
        return bestAxis;
    }

    private static int distanceBetweenBounds(BlockBounds a, BlockBounds b) {
        int dx = a.maxX() < b.minX()
                ? b.minX() - a.maxX()
                : b.maxX() < a.minX() ? a.minX() - b.maxX() : 0;
        int dz = a.maxZ() < b.minZ()
                ? b.minZ() - a.maxZ()
                : b.maxZ() < a.minZ() ? a.minZ() - b.maxZ() : 0;
        return dx + dz;
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

    private static java.util.List<BlockBounds> boundsList(JsonObject obj, String key) {
        java.util.List<BlockBounds> out = new java.util.ArrayList<>();
        for (JsonElement elem : array(obj, key)) {
            if (elem.isJsonObject() && elem.getAsJsonObject().has("blockBounds")) {
                out.add(bounds(elem.getAsJsonObject().getAsJsonObject("blockBounds")));
            }
        }
        return out;
    }

    private static java.util.List<BlockBounds> v4RoadBounds(JsonObject roadMask) {
        java.util.List<BlockBounds> out = new java.util.ArrayList<>();
        for (JsonElement elem : array(roadMask, "roadMask")) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("blockBounds")) {
                continue;
            }
            JsonObject road = elem.getAsJsonObject();
            if (v4WallMaterialRoadFalsePositive(stringValue(road, "blockId", ""))) {
                continue;
            }
            out.add(bounds(road.getAsJsonObject("blockBounds")));
        }
        return out;
    }

    private static boolean v4WallMaterialRoadFalsePositive(String blockId) {
        return "minecraft:stone_bricks".equals(blockId)
                || "minecraft:cobblestone".equals(blockId)
                || "minecraft:mossy_cobblestone".equals(blockId)
                || "minecraft:andesite".equals(blockId)
                || "minecraft:polished_andesite".equals(blockId);
    }

    private static java.util.List<BlockBounds> footprintBounds(JsonObject ledger) {
        java.util.List<BlockBounds> out = new java.util.ArrayList<>();
        for (JsonElement elem : array(ledger, "placedStructures")) {
            if (elem.isJsonObject() && elem.getAsJsonObject().has("actualFootprint")) {
                out.add(bounds(elem.getAsJsonObject().getAsJsonObject("actualFootprint")));
            }
        }
        if (out.isEmpty()) {
            out.add(unionPlaced(ledger));
        }
        return out;
    }

    private static java.util.List<BlockBounds> v4WaterPatches(JsonObject reservation) {
        java.util.List<BlockBounds> out = new java.util.ArrayList<>();
        for (JsonElement elem : array(reservation, "seedPatches")) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("blockBounds")) {
                continue;
            }
            JsonObject patch = elem.getAsJsonObject();
            String type = stringValue(patch, "landformType", "");
            if ("water".equalsIgnoreCase(type)) {
                int before = out.size();
                int cellStep = Math.max(1, intValue(patch, "cellStepBlocks", DEFAULT_WALL_UNIT_LENGTH_BLOCKS));
                for (JsonElement cellElem : array(patch, "memberCells")) {
                    if (!cellElem.isJsonObject()) {
                        continue;
                    }
                    JsonObject cell = cellElem.getAsJsonObject();
                    int minX = intValue(cell, "blockMinX", Integer.MIN_VALUE);
                    int minZ = intValue(cell, "blockMinZ", Integer.MIN_VALUE);
                    if (minX == Integer.MIN_VALUE || minZ == Integer.MIN_VALUE) {
                        continue;
                    }
                    out.add(new BlockBounds(minX, minZ, minX + cellStep - 1, minZ + cellStep - 1));
                }
                if (out.size() == before) {
                    out.add(bounds(patch.getAsJsonObject("blockBounds")));
                }
            }
        }
        return out;
    }

    private static BlockBounds v4KnownPatchBounds(JsonObject reservation, BlockBounds fallback) {
        BlockBounds union = null;
        for (JsonElement elem : array(reservation, "seedPatches")) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("blockBounds")) {
                continue;
            }
            BlockBounds bounds = bounds(elem.getAsJsonObject().getAsJsonObject("blockBounds"));
            union = union == null ? bounds : union(union, bounds);
        }
        return union == null ? expand(fallback, WALL_HALF_THICKNESS_BLOCKS)
                : expand(union, WALL_HALF_THICKNESS_BLOCKS * 2);
    }

    private static JsonArray adaptV4TerrainContour(java.util.List<V4UnitDraft> drafts,
                                                   JsonObject reservation,
                                                   BlockBounds footprintUnion,
                                                   java.util.List<BlockBounds> footprints,
                                                   BlockBounds knownPatchBounds,
                                                   java.util.List<BlockBounds> waterPatches,
                                                   V4Options options) {
        JsonArray events = new JsonArray();
        java.util.List<BlockBounds> domainCells = v4DomainGuideCells(reservation);
        if (drafts.isEmpty() || domainCells.isEmpty()) {
            return events;
        }
        int unitLength = options.normalizedWallUnitLengthBlocks();
        int maxShift = unitLength * V4_TERRAIN_CONTOUR_MAX_SHIFT_UNITS;
        int safeMargin = Math.max(WALL_HALF_THICKNESS_BLOCKS + 2,
                Math.min(unitLength, options.normalizedStructureWallBreathingRoomBlocks() / 2));
        int originalCount = drafts.size();
        for (int i = 0; i < originalCount; i++) {
            V4UnitDraft draft = drafts.get(i);
            Integer guideCoordinate = v4ContourGuideCoordinate(draft, domainCells);
            if (guideCoordinate == null) {
                continue;
            }
            int currentCoordinate = v4ContourCoordinate(draft);
            int targetCoordinate = v4ConstrainedContourCoordinate(draft.side, guideCoordinate,
                    currentCoordinate, maxShift, footprintUnion, safeMargin);
            int delta = targetCoordinate - currentCoordinate;
            if (Math.abs(delta) < Math.max(1, unitLength / 2)) {
                continue;
            }
            BlockBounds before = draft.bounds;
            BlockBounds shifted = v4ShiftToContourCoordinate(draft, targetCoordinate);
            if (!isValidTerrainContourCandidate(shifted, knownPatchBounds, waterPatches, footprints)) {
                continue;
            }
            draft.bounds = shifted;
            draft.terrainContoured = true;
            draft.terrainContourShiftBlocks = delta;
            draft.terrainContourReason = "TERRAIN_CONTOUR_DOMAIN_GUIDE";

            JsonObject event = new JsonObject();
            event.addProperty("unitId", draft.unitId);
            event.addProperty("reasonCode", draft.terrainContourReason);
            event.addProperty("side", draft.side);
            event.addProperty("guideCoordinate", guideCoordinate);
            event.addProperty("shiftBlocks", delta);
            event.add("beforeBounds", boundsJson(before));
            event.add("afterBounds", boundsJson(draft.bounds));
            events.add(event);
        }

        java.util.List<V4UnitDraft> linked = v4InsertTerrainContourLinks(drafts,
                unitLength, knownPatchBounds, events);
        drafts.clear();
        drafts.addAll(linked);
        return events;
    }

    private static java.util.List<BlockBounds> v4DomainGuideCells(JsonObject reservation) {
        java.util.List<BlockBounds> out = new java.util.ArrayList<>();
        for (JsonElement elem : array(reservation, "cityDomainMask")) {
            if (!elem.isJsonObject() || !elem.getAsJsonObject().has("blockBounds")) {
                continue;
            }
            JsonObject mask = elem.getAsJsonObject();
            if (!"city_domain_cell".equals(stringValue(mask, "maskType", ""))) {
                continue;
            }
            out.add(bounds(mask.getAsJsonObject("blockBounds")));
        }
        if (!out.isEmpty()) {
            return out;
        }
        for (JsonElement elem : array(reservation, "seedPatches")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject patch = elem.getAsJsonObject();
            if ("water".equalsIgnoreCase(stringValue(patch, "landformType", ""))) {
                continue;
            }
            int cellStep = Math.max(1, intValue(patch, "cellStepBlocks", DEFAULT_WALL_UNIT_LENGTH_BLOCKS));
            for (JsonElement cellElem : array(patch, "memberCells")) {
                if (!cellElem.isJsonObject()) {
                    continue;
                }
                JsonObject cell = cellElem.getAsJsonObject();
                int minX = intValue(cell, "blockMinX", Integer.MIN_VALUE);
                int minZ = intValue(cell, "blockMinZ", Integer.MIN_VALUE);
                if (minX == Integer.MIN_VALUE || minZ == Integer.MIN_VALUE) {
                    continue;
                }
                out.add(new BlockBounds(minX, minZ, minX + cellStep - 1, minZ + cellStep - 1));
            }
        }
        return out;
    }

    private static Integer v4ContourGuideCoordinate(V4UnitDraft draft, java.util.List<BlockBounds> domainCells) {
        Integer guide = null;
        for (BlockBounds cell : domainCells) {
            switch (draft.side) {
                case "north" -> {
                    if (rangesOverlap(cell.minX(), cell.maxX(), draft.bounds.minX(), draft.bounds.maxX())) {
                        guide = guide == null ? cell.minZ() : Math.min(guide, cell.minZ());
                    }
                }
                case "south" -> {
                    if (rangesOverlap(cell.minX(), cell.maxX(), draft.bounds.minX(), draft.bounds.maxX())) {
                        guide = guide == null ? cell.maxZ() : Math.max(guide, cell.maxZ());
                    }
                }
                case "east" -> {
                    if (rangesOverlap(cell.minZ(), cell.maxZ(), draft.bounds.minZ(), draft.bounds.maxZ())) {
                        guide = guide == null ? cell.maxX() : Math.max(guide, cell.maxX());
                    }
                }
                case "west" -> {
                    if (rangesOverlap(cell.minZ(), cell.maxZ(), draft.bounds.minZ(), draft.bounds.maxZ())) {
                        guide = guide == null ? cell.minX() : Math.min(guide, cell.minX());
                    }
                }
                default -> {
                }
            }
        }
        return guide;
    }

    private static int v4ContourCoordinate(V4UnitDraft draft) {
        return switch (draft.side) {
            case "north", "south" -> draft.bounds.center().z();
            case "east", "west" -> draft.bounds.center().x();
            default -> 0;
        };
    }

    private static int v4ConstrainedContourCoordinate(String side,
                                                      int guideCoordinate,
                                                      int currentCoordinate,
                                                      int maxShift,
                                                      BlockBounds footprintUnion,
                                                      int safeMargin) {
        int target = clamp(guideCoordinate, currentCoordinate - maxShift, currentCoordinate + maxShift);
        return switch (side) {
            case "north" -> Math.min(target, footprintUnion.minZ() - safeMargin);
            case "south" -> Math.max(target, footprintUnion.maxZ() + safeMargin);
            case "east" -> Math.max(target, footprintUnion.maxX() + safeMargin);
            case "west" -> Math.min(target, footprintUnion.minX() - safeMargin);
            default -> currentCoordinate;
        };
    }

    private static BlockBounds v4ShiftToContourCoordinate(V4UnitDraft draft, int targetCoordinate) {
        int currentCoordinate = v4ContourCoordinate(draft);
        int delta = targetCoordinate - currentCoordinate;
        return switch (draft.side) {
            case "north", "south" -> shift(draft.bounds, 0, delta);
            case "east", "west" -> shift(draft.bounds, delta, 0);
            default -> draft.bounds;
        };
    }

    private static boolean isValidTerrainContourCandidate(BlockBounds candidate,
                                                          BlockBounds knownPatchBounds,
                                                          java.util.List<BlockBounds> waterPatches,
                                                          java.util.List<BlockBounds> footprints) {
        return containsBounds(knownPatchBounds, candidate)
                && !overlapsAny(candidate, waterPatches)
                && !overlapsAny(candidate, footprints);
    }

    private static java.util.List<V4UnitDraft> v4InsertTerrainContourLinks(java.util.List<V4UnitDraft> drafts,
                                                                           int unitLength,
                                                                           BlockBounds knownPatchBounds,
                                                                           JsonArray events) {
        java.util.List<V4UnitDraft> linked = new java.util.ArrayList<>();
        int linkIndex = 0;
        for (int i = 0; i < drafts.size(); i++) {
            V4UnitDraft current = drafts.get(i);
            linked.add(current);
            if (i == drafts.size() - 1) {
                continue;
            }
            V4UnitDraft next = drafts.get(i + 1);
            if (!current.side.equals(next.side)) {
                continue;
            }
            V4UnitDraft link = v4TerrainContourLink(current, next, "terrain_contour_link_" + linkIndex, unitLength);
            if (link == null || !containsBounds(knownPatchBounds, link.bounds)) {
                continue;
            }
            linkIndex++;
            linked.add(link);
            JsonObject event = new JsonObject();
            event.addProperty("unitId", link.unitId);
            event.addProperty("reasonCode", "TERRAIN_CONTOUR_STEP_LINK_INSERTED");
            event.addProperty("side", current.side);
            event.add("blockBounds", boundsJson(link.bounds));
            events.add(event);
        }
        return linked;
    }

    private static V4UnitDraft v4TerrainContourLink(V4UnitDraft current,
                                                    V4UnitDraft next,
                                                    String unitId,
                                                    int unitLength) {
        int minStep = Math.max(WALL_HALF_THICKNESS_BLOCKS + 1, unitLength / 2);
        if ("X".equals(current.axis) && "X".equals(next.axis)) {
            int z1 = current.bounds.center().z();
            int z2 = next.bounds.center().z();
            if (Math.abs(z1 - z2) < minStep) {
                return null;
            }
            int x = "south".equals(current.side) ? current.bounds.minX() : current.bounds.maxX();
            BlockBounds bounds = new BlockBounds(x - WALL_HALF_THICKNESS_BLOCKS, Math.min(z1, z2),
                    x + WALL_HALF_THICKNESS_BLOCKS, Math.max(z1, z2));
            V4UnitDraft link = new V4UnitDraft(unitId, current.side, "Z", bounds,
                    x, z1, x, z2, current.inwardDx, current.inwardDz);
            link.terrainContoured = true;
            link.terrainContourLink = true;
            link.terrainContourShiftBlocks = Math.abs(z1 - z2);
            link.terrainContourReason = "TERRAIN_CONTOUR_STEP_LINK";
            return link;
        }
        if ("Z".equals(current.axis) && "Z".equals(next.axis)) {
            int x1 = current.bounds.center().x();
            int x2 = next.bounds.center().x();
            if (Math.abs(x1 - x2) < minStep) {
                return null;
            }
            int z = "west".equals(current.side) ? current.bounds.minZ() : current.bounds.maxZ();
            BlockBounds bounds = new BlockBounds(Math.min(x1, x2), z - WALL_HALF_THICKNESS_BLOCKS,
                    Math.max(x1, x2), z + WALL_HALF_THICKNESS_BLOCKS);
            V4UnitDraft link = new V4UnitDraft(unitId, current.side, "X", bounds,
                    x1, z, x2, z, current.inwardDx, current.inwardDz);
            link.terrainContoured = true;
            link.terrainContourLink = true;
            link.terrainContourShiftBlocks = Math.abs(x1 - x2);
            link.terrainContourReason = "TERRAIN_CONTOUR_STEP_LINK";
            return link;
        }
        return null;
    }

    private static java.util.List<V4UnitDraft> v4RingUnits(BlockBounds bounds, int unitLength) {
        java.util.List<V4UnitDraft> out = new java.util.ArrayList<>();
        int index = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x += unitLength) {
            int x2 = Math.min(bounds.maxX(), x + unitLength - 1);
            out.add(new V4UnitDraft("wall_unit_" + index++, "north", "X",
                    new BlockBounds(x, bounds.minZ() - WALL_HALF_THICKNESS_BLOCKS,
                            x2, bounds.minZ() + WALL_HALF_THICKNESS_BLOCKS),
                    x, bounds.minZ(), x2, bounds.minZ(), 0, 1));
        }
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z += unitLength) {
            int z2 = Math.min(bounds.maxZ(), z + unitLength - 1);
            out.add(new V4UnitDraft("wall_unit_" + index++, "east", "Z",
                    new BlockBounds(bounds.maxX() - WALL_HALF_THICKNESS_BLOCKS, z,
                            bounds.maxX() + WALL_HALF_THICKNESS_BLOCKS, z2),
                    bounds.maxX(), z, bounds.maxX(), z2, -1, 0));
        }
        for (int x = bounds.maxX(); x >= bounds.minX(); x -= unitLength) {
            int x2 = Math.max(bounds.minX(), x - unitLength + 1);
            out.add(new V4UnitDraft("wall_unit_" + index++, "south", "X",
                    new BlockBounds(x2, bounds.maxZ() - WALL_HALF_THICKNESS_BLOCKS,
                            x, bounds.maxZ() + WALL_HALF_THICKNESS_BLOCKS),
                    x, bounds.maxZ(), x2, bounds.maxZ(), 0, -1));
        }
        for (int z = bounds.maxZ(); z >= bounds.minZ(); z -= unitLength) {
            int z2 = Math.max(bounds.minZ(), z - unitLength + 1);
            out.add(new V4UnitDraft("wall_unit_" + index++, "west", "Z",
                    new BlockBounds(bounds.minX() - WALL_HALF_THICKNESS_BLOCKS, z2,
                            bounds.minX() + WALL_HALF_THICKNESS_BLOCKS, z),
                    bounds.minX(), z, bounds.minX(), z2, 1, 0));
        }
        return out;
    }

    private static JsonArray retreatWaterRuns(java.util.List<V4UnitDraft> drafts,
                                              java.util.List<BlockBounds> waterPatches,
                                              V4Options options,
                                              BlockBounds knownPatchBounds) {
        JsonArray events = new JsonArray();
        if (drafts.isEmpty() || waterPatches.isEmpty()) {
            return events;
        }
        int runStart = -1;
        for (int i = 0; i <= drafts.size(); i++) {
            boolean water = i < drafts.size() && overlapsAny(drafts.get(i).bounds, waterPatches);
            if (water && runStart < 0) {
                runStart = i;
            }
            if ((!water || i == drafts.size()) && runStart >= 0) {
                int runEnd = i - 1;
                int runLength = runEnd - runStart + 1;
                for (int j = runStart; j <= runEnd; j++) {
                    drafts.get(j).waterRunLength = runLength;
                }
                if (runLength >= options.normalizedWaterRunMinUnits()) {
                    for (int j = runStart; j <= runEnd; j++) {
                        V4UnitDraft draft = drafts.get(j);
                        BlockBounds before = draft.bounds;
                        boolean retreated = false;
                        for (int step = 1; step <= options.normalizedWaterRetreatMaxCells(); step++) {
                            int distance = options.normalizedWallUnitLengthBlocks() * step;
                            int[][] candidates = {
                                    {draft.inwardDx * distance, draft.inwardDz * distance},
                                    {0, distance},
                                    {0, -distance},
                                    {distance, 0},
                                    {-distance, 0}
                            };
                            for (int[] candidate : candidates) {
                                BlockBounds shifted = shift(before, candidate[0], candidate[1]);
                                if (isValidWaterRetreatCandidate(shifted, waterPatches,
                                        knownPatchBounds, drafts, j)) {
                                    draft.bounds = shifted;
                                    draft.waterRetreated = true;
                                    draft.waterRetreatSteps = step;
                                    retreated = true;
                                    break;
                                }
                            }
                            if (retreated) {
                                break;
                            }
                        }
                        JsonObject event = new JsonObject();
                        event.addProperty("unitId", draft.unitId);
                        event.addProperty("runStartIndex", runStart);
                        event.addProperty("runEndIndex", runEnd);
                        event.addProperty("runLengthUnits", runLength);
                        event.addProperty("retreated", retreated);
                        event.addProperty("retreatSteps", draft.waterRetreatSteps);
                        event.add("beforeBounds", boundsJson(before));
                        event.add("afterBounds", boundsJson(draft.bounds));
                        if (!retreated) {
                            draft.naturalBoundary = true;
                            event.addProperty("reasonCode", "NATURAL_WATER_BOUNDARY_GAP_AFTER_RETREAT_FAIL");
                        } else {
                            event.addProperty("reasonCode", "WATER_RUN_RETIRED_TO_LAND_SIDE");
                        }
                        events.add(event);
                    }
                }
                runStart = -1;
            }
        }
        for (V4UnitDraft draft : drafts) {
            if (draft.naturalBoundary || !overlapsAny(draft.bounds, waterPatches)) {
                continue;
            }
            BlockBounds before = draft.bounds;
            boolean retreated = false;
            for (int step = 1; step <= options.normalizedWaterRetreatMaxCells(); step++) {
                int distance = options.normalizedWallUnitLengthBlocks() * step;
                int[][] candidates = {
                        {draft.inwardDx * distance, draft.inwardDz * distance},
                        {0, distance},
                        {0, -distance},
                        {distance, 0},
                        {-distance, 0}
                };
                for (int[] candidate : candidates) {
                    BlockBounds shifted = shift(before, candidate[0], candidate[1]);
                    if (isValidWaterRetreatCandidate(shifted, waterPatches,
                            knownPatchBounds, drafts, drafts.indexOf(draft))) {
                        draft.bounds = shifted;
                        draft.waterRetreated = true;
                        draft.waterRetreatSteps = step;
                        retreated = true;
                        break;
                    }
                }
                if (retreated) {
                    break;
                }
            }
            JsonObject event = new JsonObject();
            event.addProperty("unitId", draft.unitId);
            event.addProperty("runStartIndex", -1);
            event.addProperty("runEndIndex", -1);
            event.addProperty("runLengthUnits", draft.waterRunLength);
            event.addProperty("retreated", retreated);
            event.addProperty("retreatSteps", draft.waterRetreatSteps);
            event.add("beforeBounds", boundsJson(before));
            event.add("afterBounds", boundsJson(draft.bounds));
            if (!retreated) {
                draft.naturalBoundary = true;
                event.addProperty("reasonCode", "NATURAL_WATER_BOUNDARY_GAP_AFTER_CORNER_RETREAT_FAIL");
            } else {
                event.addProperty("reasonCode", "WATER_CORNER_RETIRED_TO_LAND_SIDE");
            }
            events.add(event);
        }
        return events;
    }

    private static int estimatedSurfaceY(BlockBounds bounds) {
        int cx = Math.floorDiv(bounds.center().x(), DEFAULT_WALL_UNIT_LENGTH_BLOCKS);
        int cz = Math.floorDiv(bounds.center().z(), DEFAULT_WALL_UNIT_LENGTH_BLOCKS);
        return 64 + Math.floorMod(cx * 5 + cz * 7, 17);
    }

    private static int trimmedMedian(java.util.List<Integer> values, int fallback) {
        if (values.isEmpty()) {
            return fallback;
        }
        java.util.List<Integer> sorted = new java.util.ArrayList<>(values);
        sorted.sort(Integer::compareTo);
        int trim = sorted.size() >= 10 ? Math.max(1, sorted.size() / 10) : 0;
        return median(sorted.subList(trim, sorted.size() - trim), fallback);
    }

    private static int median(java.util.List<Integer> values, int fallback) {
        if (values.isEmpty()) {
            return fallback;
        }
        java.util.List<Integer> sorted = new java.util.ArrayList<>(values);
        sorted.sort(Integer::compareTo);
        return sorted.get(sorted.size() / 2);
    }

    private static int localMedianY(java.util.List<V4UnitDraft> drafts, int index, int window, int fallback) {
        if (drafts.isEmpty()) {
            return fallback;
        }
        int radius = Math.max(0, window / 2);
        java.util.List<Integer> samples = new java.util.ArrayList<>();
        for (int offset = -radius; offset <= radius; offset++) {
            V4UnitDraft sample = drafts.get(Math.floorMod(index + offset, drafts.size()));
            samples.add(sample.surfaceMedianY);
        }
        return median(samples, fallback);
    }

    private static String v4NodeType(java.util.List<V4UnitDraft> drafts, int index) {
        V4UnitDraft draft = drafts.get(index);
        V4UnitDraft previous = drafts.get(Math.floorMod(index - 1, drafts.size()));
        if (draft.naturalBoundary || previous.naturalBoundary) {
            return "natural_boundary_endpoint";
        }
        if (!draft.side.equals(previous.side)) {
            return "corner_tower";
        }
        return index % V4_NODE_INTERVAL_UNITS == 0 ? "beacon_tower" : "junction";
    }

    private static JsonObject v4Node(String nodeId, String nodeType, int x, int z, BlockBounds blockBounds,
                                     int surfaceMedianY, int targetY, String heightMode) {
        JsonObject node = new JsonObject();
        node.addProperty("nodeId", nodeId);
        node.addProperty("nodeType", nodeType);
        node.addProperty("x", x);
        node.addProperty("z", z);
        node.add("blockBounds", boundsJson(blockBounds));
        node.addProperty("surfaceMedianY", surfaceMedianY);
        node.addProperty("targetY", targetY);
        node.addProperty("heightMode", heightMode);
        node.addProperty("templateId", switch (nodeType) {
            case "gatehouse" -> "gatehouse_9";
            case "beacon_tower" -> "beacon_5x5";
            case "corner_tower" -> "watchtower_5x5";
            case "junction" -> "wall_node_connector";
            default -> "wall_tower_small";
        });
        node.addProperty("placementRole", switch (nodeType) {
            case "corner_tower", "gatehouse", "terrace_node" -> "structural_node";
            case "beacon_tower" -> "visual_marker";
            default -> "graph_only";
        });
        return node;
    }

    private static JsonObject v4Connector(String connectorId, String nodeId, String nodeType,
                                          BlockPoint point, BlockBounds blockBounds,
                                          int surfaceMedianY, int targetY,
                                          String connectorStatus, String connectorMode) {
        JsonObject connector = new JsonObject();
        connector.addProperty("connectorId", connectorId);
        connector.addProperty("nodeId", nodeId);
        connector.addProperty("nodeType", nodeType);
        connector.addProperty("x", point.x());
        connector.addProperty("z", point.z());
        connector.addProperty("unitType", "node_connector");
        connector.addProperty("connectorStatus", connectorStatus);
        connector.addProperty("connectorMode", connectorMode);
        connector.addProperty("heightMode", "stepped".equals(connectorStatus) ? "stair_link" : connectorMode);
        connector.addProperty("surfaceMedianY", surfaceMedianY);
        connector.addProperty("targetY", targetY);
        connector.addProperty("templateId", "stair_link".equals(connectorMode)
                ? "wall_stair_connector" : "wall_node_connector");
        connector.add("blockBounds", boundsJson(blockBounds));
        return connector;
    }

    private static BlockPoint v4NodePoint(java.util.List<V4UnitDraft> drafts, int index) {
        V4UnitDraft draft = drafts.get(index);
        return switch (draft.side) {
            case "north" -> new BlockPoint(draft.bounds.minX(), draft.bounds.center().z());
            case "east" -> new BlockPoint(draft.bounds.center().x(), draft.bounds.minZ());
            case "south" -> new BlockPoint(draft.bounds.maxX(), draft.bounds.center().z());
            case "west" -> new BlockPoint(draft.bounds.center().x(), draft.bounds.maxZ());
            default -> draft.bounds.center();
        };
    }

    private static BlockPoint v4ConnectorPoint(V4UnitDraft draft) {
        return switch (draft.side) {
            case "north" -> new BlockPoint(draft.bounds.minX(), draft.bounds.center().z());
            case "east" -> new BlockPoint(draft.bounds.center().x(), draft.bounds.minZ());
            case "south" -> new BlockPoint(draft.bounds.maxX(), draft.bounds.center().z());
            case "west" -> new BlockPoint(draft.bounds.center().x(), draft.bounds.maxZ());
            default -> draft.bounds.center();
        };
    }

    private static BlockBounds v4PointBounds(BlockPoint point) {
        return new BlockBounds(point.x(), point.z(), point.x(), point.z());
    }

    private static BlockBounds v4NodeBounds(String nodeType, V4UnitDraft draft, BlockPoint point) {
        if ("gatehouse".equals(nodeType)) {
            return expand(draft.bounds, 2);
        }
        if ("junction".equals(nodeType) || "natural_boundary_endpoint".equals(nodeType)) {
            return v4PointBounds(point);
        }
        return towerBounds(point.x(), point.z(), 5);
    }

    private static BlockBounds v4ConnectorBounds(V4UnitDraft draft) {
        return v4PointBounds(v4ConnectorPoint(draft));
    }

    private static void addValidationBreak(JsonArray validationBreaks, String unitId,
                                           String reasonCode, String message, BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("breakId", "wall_graph_break_" + validationBreaks.size());
        obj.addProperty("unitId", unitId);
        obj.addProperty("reasonCode", reasonCode);
        obj.addProperty("message", message);
        obj.add("blockBounds", boundsJson(bounds));
        validationBreaks.add(obj);
    }

    private static JsonArray outsideKnownPatchUnits(JsonArray wallUnits, BlockBounds knownPatchBounds) {
        JsonArray out = new JsonArray();
        for (JsonElement elem : wallUnits) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject unit = elem.getAsJsonObject();
            if (!unit.has("blockBounds") || !unit.get("blockBounds").isJsonObject()) {
                continue;
            }
            BlockBounds unitBounds = bounds(unit.getAsJsonObject("blockBounds"));
            if (containsBounds(knownPatchBounds, unitBounds)) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("unitId", stringValue(unit, "unitId", ""));
            item.addProperty("unitType", stringValue(unit, "unitType", ""));
            item.addProperty("reasonCode", stringValue(unit, "reasonCode", ""));
            item.add("blockBounds", boundsJson(unitBounds));
            out.add(item);
        }
        return out;
    }

    private static int countTerrainContoured(java.util.List<V4UnitDraft> drafts) {
        int count = 0;
        for (V4UnitDraft draft : drafts) {
            if (draft.terrainContoured) {
                count++;
            }
        }
        return count;
    }

    private static int countTerrainContourLinks(java.util.List<V4UnitDraft> drafts) {
        int count = 0;
        for (V4UnitDraft draft : drafts) {
            if (draft.terrainContourLink) {
                count++;
            }
        }
        return count;
    }

    private static void addGeneratedGate(JsonArray generatedGates, V4UnitDraft draft, int gateWidth) {
        JsonObject gate = new JsonObject();
        gate.addProperty("gateId", "v4_gatehouse_" + generatedGates.size());
        gate.addProperty("nodeType", "gatehouse");
        gate.addProperty("reasonCode", "ROAD_MASK_GATEHOUSE_OPENING");
        gate.addProperty("gateWidthBlocks", gateWidth);
        gate.add("blockBounds", boundsJson(draft.bounds));
        generatedGates.add(gate);
    }

    private static boolean overlapsAny(BlockBounds bounds, java.util.List<BlockBounds> candidates) {
        for (BlockBounds candidate : candidates) {
            if (bounds.overlaps(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static BlockBounds shift(BlockBounds bounds, int dx, int dz) {
        return new BlockBounds(bounds.minX() + dx, bounds.minZ() + dz,
                bounds.maxX() + dx, bounds.maxZ() + dz);
    }

    private static boolean isValidWaterRetreatCandidate(BlockBounds shifted,
                                                        java.util.List<BlockBounds> waterPatches,
                                                        BlockBounds knownPatchBounds,
                                                        java.util.List<V4UnitDraft> drafts,
                                                        int selfIndex) {
        return !overlapsAny(shifted, waterPatches)
                && containsBounds(knownPatchBounds, shifted)
                && !overlapsOtherDraft(drafts, selfIndex, shifted);
    }

    private static boolean containsBounds(BlockBounds outer, BlockBounds inner) {
        return outer.contains(inner.minX(), inner.minZ())
                && outer.contains(inner.maxX(), inner.maxZ());
    }

    private static boolean overlapsOtherDraft(java.util.List<V4UnitDraft> drafts, int selfIndex, BlockBounds bounds) {
        for (int i = 0; i < drafts.size(); i++) {
            if (i == selfIndex) {
                continue;
            }
            if (bounds.overlaps(drafts.get(i).bounds)) {
                return true;
            }
        }
        return false;
    }

    private static BlockBounds towerBounds(int centerX, int centerZ, int size) {
        int radius = Math.max(1, size / 2);
        return new BlockBounds(centerX - radius, centerZ - radius, centerX + radius, centerZ + radius);
    }

    public record V4Options(int wallUnitLengthBlocks,
                            int waterRunMinUnits,
                            int waterRetreatMaxCells,
                            int structureWallBreathingRoomBlocks,
                            int heightDatumClampBlocks,
                            int localMedianWindowUnits) {
        public static V4Options defaults() {
            return new V4Options(
                    DEFAULT_WALL_UNIT_LENGTH_BLOCKS,
                    DEFAULT_WATER_RUN_MIN_UNITS,
                    DEFAULT_WATER_RETREAT_MAX_CELLS,
                    DEFAULT_STRUCTURE_WALL_BREATHING_ROOM_BLOCKS,
                    DEFAULT_HEIGHT_DATUM_CLAMP_BLOCKS,
                    DEFAULT_LOCAL_MEDIAN_WINDOW_UNITS);
        }

        public int normalizedWallUnitLengthBlocks() {
            return wallUnitLengthBlocks <= 0 ? DEFAULT_WALL_UNIT_LENGTH_BLOCKS : wallUnitLengthBlocks;
        }

        public int normalizedWaterRunMinUnits() {
            return waterRunMinUnits <= 0 ? DEFAULT_WATER_RUN_MIN_UNITS : waterRunMinUnits;
        }

        public int normalizedWaterRetreatMaxCells() {
            return waterRetreatMaxCells <= 0 ? DEFAULT_WATER_RETREAT_MAX_CELLS : waterRetreatMaxCells;
        }

        public int normalizedStructureWallBreathingRoomBlocks() {
            return structureWallBreathingRoomBlocks <= 0
                    ? DEFAULT_STRUCTURE_WALL_BREATHING_ROOM_BLOCKS
                    : structureWallBreathingRoomBlocks;
        }

        public int normalizedHeightDatumClampBlocks() {
            return heightDatumClampBlocks <= 0 ? DEFAULT_HEIGHT_DATUM_CLAMP_BLOCKS : heightDatumClampBlocks;
        }

        public int normalizedLocalMedianWindowUnits() {
            int value = localMedianWindowUnits <= 0 ? DEFAULT_LOCAL_MEDIAN_WINDOW_UNITS : localMedianWindowUnits;
            return value % 2 == 0 ? value + 1 : value;
        }
    }

    public record V5Options(int wallUnitLengthBlocks,
                            int nominalWallHeightBlocks,
                            int waterRunMinBlocks,
                            double waterFluidRatioMin,
                            int heightSegmentMaxDeltaBlocks,
                            int heightSteppedTransitionMaxDeltaBlocks,
                            int naturalBoundaryMinDeltaBlocks) {
        public static V5Options defaults() {
            return new V5Options(DEFAULT_V5_WALL_UNIT_LENGTH_BLOCKS,
                    DEFAULT_V5_NOMINAL_WALL_HEIGHT_BLOCKS,
                    DEFAULT_V5_WATER_RUN_MIN_BLOCKS,
                    DEFAULT_V5_WATER_FLUID_RATIO_MIN,
                    DEFAULT_V5_SEGMENT_MAX_DELTA_BLOCKS,
                    DEFAULT_V5_STEPPED_TRANSITION_MAX_DELTA_BLOCKS,
                    DEFAULT_V5_NATURAL_BOUNDARY_MIN_DELTA_BLOCKS);
        }

        public int normalizedWallUnitLengthBlocks() {
            return wallUnitLengthBlocks <= 0 ? DEFAULT_V5_WALL_UNIT_LENGTH_BLOCKS : wallUnitLengthBlocks;
        }

        public int normalizedNominalWallHeightBlocks() {
            return nominalWallHeightBlocks <= 0 ? DEFAULT_V5_NOMINAL_WALL_HEIGHT_BLOCKS : nominalWallHeightBlocks;
        }

        public int normalizedWaterRunMinBlocks() {
            return waterRunMinBlocks <= 0 ? DEFAULT_V5_WATER_RUN_MIN_BLOCKS : waterRunMinBlocks;
        }

        public double normalizedWaterFluidRatioMin() {
            return waterFluidRatioMin <= 0.0D ? DEFAULT_V5_WATER_FLUID_RATIO_MIN
                    : Math.min(1.0D, waterFluidRatioMin);
        }

        public int normalizedHeightSegmentMaxDeltaBlocks() {
            return heightSegmentMaxDeltaBlocks <= 0
                    ? DEFAULT_V5_SEGMENT_MAX_DELTA_BLOCKS : heightSegmentMaxDeltaBlocks;
        }

        public int normalizedHeightSteppedTransitionMaxDeltaBlocks() {
            int segmentMax = normalizedHeightSegmentMaxDeltaBlocks();
            int steppedMax = heightSteppedTransitionMaxDeltaBlocks <= 0
                    ? DEFAULT_V5_STEPPED_TRANSITION_MAX_DELTA_BLOCKS : heightSteppedTransitionMaxDeltaBlocks;
            return Math.max(segmentMax, steppedMax);
        }

        public int normalizedNaturalBoundaryMinDeltaBlocks() {
            return Math.max(normalizedHeightSteppedTransitionMaxDeltaBlocks() + 1,
                    naturalBoundaryMinDeltaBlocks <= 0
                            ? DEFAULT_V5_NATURAL_BOUNDARY_MIN_DELTA_BLOCKS : naturalBoundaryMinDeltaBlocks);
        }
    }

    private static final class V4UnitDraft {
        private final String unitId;
        private final String side;
        private final String axis;
        private final int startX;
        private final int startZ;
        private final int inwardDx;
        private final int inwardDz;
        private BlockBounds bounds;
        private boolean naturalBoundary;
        private boolean waterRetreated;
        private int waterRetreatSteps;
        private int waterRunLength;
        private boolean terrainContoured;
        private boolean terrainContourLink;
        private int terrainContourShiftBlocks;
        private String terrainContourReason = "none";
        private int surfaceMedianY;
        private int localMedianY;
        private int targetY;

        private V4UnitDraft(String unitId, String side, String axis, BlockBounds bounds,
                            int startX, int startZ, int endX, int endZ, int inwardDx, int inwardDz) {
            this.unitId = unitId;
            this.side = side;
            this.axis = axis;
            this.bounds = bounds;
            this.startX = startX;
            this.startZ = startZ;
            this.inwardDx = inwardDx;
            this.inwardDz = inwardDz;
        }

        private String nodeHeightMode() {
            if (naturalBoundary) {
                return "natural_boundary";
            }
            return targetY == localMedianY ? "local_median" : "datum_clamped";
        }
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
