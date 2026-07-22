package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.application.terrain.CityContinuousTerrainRunPlanner;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure global-mask compiler for lined LandUse surface runs. It does not create Decoration slots. */
public final class CityLandUseSurfaceRunCompiler {
    public static final String STRAIGHT_CONTENT_REF =
            "geomantia:decoration/water_channel_lined_straight_01";
    public static final String END_CAP_CONTENT_REF =
            "geomantia:decoration/water_channel_lined_endcap_01";
    private static final String TRACK_ID = "lined_channel";

    private final CityContinuousTerrainRunPlanner runPlanner = new CityContinuousTerrainRunPlanner();

    public Plan compile(Request request, CityContinuousTerrainRunPlanner.TerrainView terrain) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(terrain, "terrain");
        Set<Cell> allowed = cells(request.memberSpans());
        allowed.removeAll(cells(request.exclusionSpans()));

        List<Cell> stableCells = allowed.stream().sorted(Cell.STABLE_ORDER).toList();
        List<CityContinuousTerrainRunPlanner.Point> runPoints = new ArrayList<>();
        Map<String, PlacementSeed> placementsByPointId = new HashMap<>();
        for (Cell cell : stableCells) {
            if (!isChannelBandStart(request, cell)) {
                continue;
            }
            BlockBounds footprint = footprintAt(request.continuationAxis(), request.directionSign(), cell,
                    request.straight().widthBlocks(), request.straight().depthBlocks());
            if (!containsEveryCell(allowed, footprint)) {
                continue;
            }
            String pointId = request.areaId() + "/lined_channel/" + cell.x() + "," + cell.z();
            BlockPoint samplePoint = samplePoint(request.continuationAxis(), footprint);
            runPoints.add(new CityContinuousTerrainRunPlanner.Point(TRACK_ID, pointId,
                    localPoint(request, cell), samplePoint, request.straight().contentRef()));
            placementsByPointId.put(pointId, new PlacementSeed(cell, samplePoint));
        }

        CityContinuousTerrainRunPlanner.Plan common = runPlanner.compile(
                new CityContinuousTerrainRunPlanner.Request(request.areaId(),
                        commonAxis(request.continuationAxis()), runPolicy(request.terrainPolicy()),
                        landUseReasonCodes(), runPoints), terrain,
                contentRef -> STRAIGHT_CONTENT_REF.equals(contentRef)
                        ? request.endCap().contentRef() : null);

        List<Run> runs = new ArrayList<>();
        for (CityContinuousTerrainRunPlanner.Run run : common.runs()) {
            List<Placement> placements = run.points().stream()
                    .map(point -> placement(request, point, placementsByPointId.get(point.pointId()), allowed))
                    .toList();
            runs.add(new Run(run.runId(), request.continuationAxis(), run.crossCoordinate(), placements,
                    run.terminationOrdinal(), run.terminationReasonCode(), run.foundationSegments()));
        }
        runs.sort(Comparator.comparing(Run::runId));
        List<CityContinuousTerrainRunPlanner.FoundationSegment> segments = runs.stream()
                .flatMap(run -> run.foundationSegments().stream()).toList();
        return new Plan(request.areaId(), List.copyOf(runs), segments);
    }

    private static Placement placement(Request request,
                                       CityContinuousTerrainRunPlanner.PointOutcome point,
                                       PlacementSeed seed,
                                       Set<Cell> allowed) {
        if (seed == null) {
            throw new IllegalStateException("CITY_LAND_USE_SURFACE_RUN_POINT_UNKNOWN: " + point.pointId());
        }
        PrefabSpec applied = point.decision() == CityContinuousTerrainRunPlanner.Decision.END_CAP
                ? request.endCap() : request.straight();
        BlockBounds footprint = footprintAt(request.continuationAxis(), request.directionSign(), seed.bandStart(),
                applied.widthBlocks(), applied.depthBlocks());
        if (point.decision() == CityContinuousTerrainRunPlanner.Decision.END_CAP
                && !containsEveryCell(allowed, footprint)) {
            throw new IllegalStateException("CITY_LAND_USE_SURFACE_ENDCAP_FOOTPRINT_OUTSIDE_MASK: "
                    + point.pointId());
        }
        BlockPoint placementAnchor = placementAnchor(request.continuationAxis(), request.directionSign(), footprint,
                applied.widthBlocks());
        return new Placement(point.pointId(), point.runId(), point.runOrdinal(), seed.samplePoint(),
                placementAnchor, rotationDegrees(request.continuationAxis(), request.directionSign()), footprint,
                point.surfaceY(), point.targetY(), point.water(), point.terrainClass(), point.decision(),
                point.contentRef(), request.straight().contentHash(), point.appliedContentRef(),
                applied.contentHash(), point.reasonCode());
    }

    private static boolean isChannelBandStart(Request request, Cell cell) {
        int coordinate = request.continuationAxis() == WorldAxis.Z ? cell.x() : cell.z();
        int origin = request.continuationAxis() == WorldAxis.Z
                ? request.origin().x() : request.origin().z();
        long relative = (long) coordinate - origin - request.channelOffsetBlocks();
        return Math.floorMod(relative, (long) request.repeatPeriodBlocks()) == 0L;
    }

    private static CityContinuousTerrainRunPlanner.GridPoint localPoint(Request request, Cell bandStart) {
        int localX = Math.subtractExact(bandStart.x(), request.origin().x());
        int localZ = Math.subtractExact(bandStart.z(), request.origin().z());
        if (request.continuationAxis() == WorldAxis.X) {
            localX = Math.multiplyExact(localX, request.directionSign());
        } else {
            localZ = Math.multiplyExact(localZ, request.directionSign());
        }
        return new CityContinuousTerrainRunPlanner.GridPoint(localX, localZ);
    }

    private static CityContinuousTerrainRunPlanner.Axis commonAxis(WorldAxis axis) {
        return axis == WorldAxis.X
                ? CityContinuousTerrainRunPlanner.Axis.U : CityContinuousTerrainRunPlanner.Axis.V;
    }

    private static BlockPoint samplePoint(WorldAxis axis, BlockBounds footprint) {
        return axis == WorldAxis.Z
                ? new BlockPoint((footprint.minX() + footprint.maxX()) / 2, footprint.minZ())
                : new BlockPoint(footprint.minX(), (footprint.minZ() + footprint.maxZ()) / 2);
    }

    private static BlockBounds footprintAt(WorldAxis axis,
                                           int directionSign,
                                           Cell bandStart,
                                           int width,
                                           int depth) {
        if (axis == WorldAxis.Z) {
            int minZ = directionSign > 0 ? bandStart.z() : Math.subtractExact(bandStart.z(), depth - 1);
            int maxZ = directionSign > 0 ? Math.addExact(bandStart.z(), depth - 1) : bandStart.z();
            return new BlockBounds(bandStart.x(), minZ,
                    Math.addExact(bandStart.x(), width - 1), maxZ);
        }
        int minX = directionSign > 0 ? bandStart.x() : Math.subtractExact(bandStart.x(), depth - 1);
        int maxX = directionSign > 0 ? Math.addExact(bandStart.x(), depth - 1) : bandStart.x();
        return new BlockBounds(minX, bandStart.z(), maxX, Math.addExact(bandStart.z(), width - 1));
    }

    private static BlockPoint placementAnchor(WorldAxis axis,
                                               int directionSign,
                                               BlockBounds footprint,
                                               int width) {
        if (axis == WorldAxis.Z) {
            return directionSign > 0
                    ? new BlockPoint(footprint.minX(), footprint.minZ())
                    : new BlockPoint(footprint.maxX(), footprint.maxZ());
        }
        return directionSign > 0
                // Rotation 270 maps authored +Z to world +X and authored +X to world -Z.
                ? new BlockPoint(footprint.minX(), Math.addExact(footprint.minZ(), width - 1))
                // Rotation 90 maps authored +Z to world -X and authored +X to world +Z.
                : new BlockPoint(footprint.maxX(), footprint.minZ());
    }

    private static int rotationDegrees(WorldAxis axis, int directionSign) {
        if (axis == WorldAxis.Z) return directionSign > 0 ? 0 : 180;
        return directionSign > 0 ? 270 : 90;
    }

    private static boolean containsEveryCell(Set<Cell> allowed, BlockBounds bounds) {
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                if (!allowed.contains(new Cell(x, z))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Set<Cell> cells(List<LandUseAreaPlan.ScanlineSpan> spans) {
        Set<Cell> result = new HashSet<>();
        for (LandUseAreaPlan.ScanlineSpan span : spans) {
            for (int x = span.minX(); x <= span.maxX(); x++) {
                result.add(new Cell(x, span.z()));
            }
        }
        return result;
    }

    private static CityContinuousTerrainRunPlanner.RunPolicy runPolicy(TerrainPolicy policy) {
        return new CityContinuousTerrainRunPlanner.RunPolicy(policy.maxSlopeDelta(), policy.allowWater(),
                policy.maxContinuousDropBlocks(), policy.continuousDropWindowBlocks(),
                policy.foundationMode(), policy.maxFoundationDepthBlocks(), policy.foundationShoulderBlocks());
    }

    private static CityContinuousTerrainRunPlanner.ReasonCodes landUseReasonCodes() {
        return new CityContinuousTerrainRunPlanner.ReasonCodes(
                "CITY_LAND_USE_SURFACE_RUN_POINT_SAFE",
                "CITY_LAND_USE_SURFACE_RUN_TERRAIN_UNAVAILABLE",
                "CITY_LAND_USE_SURFACE_RUN_WATER_TERMINATED",
                "CITY_LAND_USE_SURFACE_RUN_LOCAL_CLIFF_TERMINATED",
                "CITY_LAND_USE_SURFACE_RUN_CONTINUOUS_DROP_TERMINATED",
                "CITY_LAND_USE_SURFACE_RUN_FOUNDATION_DEPTH_TERMINATED",
                "CITY_LAND_USE_SURFACE_RUN_TERRAIN_SAMPLE_MISSING");
    }

    public enum WorldAxis {
        X,
        Z
    }

    public record Request(String areaId,
                          List<LandUseAreaPlan.ScanlineSpan> memberSpans,
                          List<LandUseAreaPlan.ScanlineSpan> exclusionSpans,
                          WorldAxis continuationAxis,
                          BlockPoint origin,
                          int repeatPeriodBlocks,
                          int channelOffsetBlocks,
                          int directionSign,
                          PrefabSpec straight,
                          PrefabSpec endCap,
                          TerrainPolicy terrainPolicy) {
        public Request {
            if (areaId == null || areaId.isBlank()) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_RUN_AREA_ID_REQUIRED");
            }
            memberSpans = List.copyOf(Objects.requireNonNull(memberSpans, "memberSpans"));
            exclusionSpans = List.copyOf(Objects.requireNonNull(exclusionSpans, "exclusionSpans"));
            Objects.requireNonNull(continuationAxis, "continuationAxis");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(straight, "straight");
            Objects.requireNonNull(endCap, "endCap");
            Objects.requireNonNull(terrainPolicy, "terrainPolicy");
            if (repeatPeriodBlocks <= 0 || directionSign != -1 && directionSign != 1
                    || !STRAIGHT_CONTENT_REF.equals(straight.contentRef())
                    || !END_CAP_CONTENT_REF.equals(endCap.contentRef())
                    || straight.depthBlocks() != 1
                    || straight.widthBlocks() != endCap.widthBlocks()
                    || endCap.depthBlocks() != 2
                    || straight.widthBlocks() >= repeatPeriodBlocks) {
                throw new IllegalArgumentException("CITY_LAND_USE_LINED_CHANNEL_RECIPE_INVALID");
            }
        }

        public Request(String areaId,
                       List<LandUseAreaPlan.ScanlineSpan> memberSpans,
                       List<LandUseAreaPlan.ScanlineSpan> exclusionSpans,
                       WorldAxis continuationAxis,
                       BlockPoint origin,
                       int repeatPeriodBlocks,
                       int channelOffsetBlocks,
                       PrefabSpec straight,
                       PrefabSpec endCap,
                       TerrainPolicy terrainPolicy) {
            this(areaId, memberSpans, exclusionSpans, continuationAxis, origin, repeatPeriodBlocks,
                    channelOffsetBlocks, 1, straight, endCap, terrainPolicy);
        }
    }

    public record PrefabSpec(String contentRef,
                             String contentHash,
                             int widthBlocks,
                             int heightBlocks,
                             int depthBlocks) {
        public PrefabSpec {
            if (contentRef == null || contentRef.isBlank() || contentHash == null || contentHash.isBlank()
                    || widthBlocks <= 0 || heightBlocks <= 0 || depthBlocks <= 0) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PREFAB_SPEC_INVALID");
            }
        }
    }

    public record TerrainPolicy(int maxSlopeDelta,
                                boolean allowWater,
                                int maxContinuousDropBlocks,
                                int continuousDropWindowBlocks,
                                CityContinuousTerrainRunPlanner.FoundationMode foundationMode,
                                int maxFoundationDepthBlocks,
                                int foundationShoulderBlocks) {
        public TerrainPolicy {
            new CityContinuousTerrainRunPlanner.RunPolicy(maxSlopeDelta, allowWater,
                    maxContinuousDropBlocks, continuousDropWindowBlocks, foundationMode,
                    maxFoundationDepthBlocks, foundationShoulderBlocks);
        }
    }

    public record Plan(String areaId,
                       List<Run> runs,
                       List<CityContinuousTerrainRunPlanner.FoundationSegment> foundationSegments) {
        public Plan {
            if (areaId == null || areaId.isBlank()) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_RUN_AREA_ID_REQUIRED");
            }
            runs = List.copyOf(runs);
            foundationSegments = List.copyOf(foundationSegments);
            List<CityContinuousTerrainRunPlanner.FoundationSegment> derived = runs.stream()
                    .flatMap(run -> run.foundationSegments().stream()).toList();
            if (!foundationSegments.equals(derived)) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_FOUNDATION_SEGMENTS_MISMATCH");
            }
        }
    }

    public record Run(String runId,
                      WorldAxis continuationAxis,
                      int crossCoordinate,
                      List<Placement> placements,
                      Integer terminationOrdinal,
                      String terminationReasonCode,
                      List<CityContinuousTerrainRunPlanner.FoundationSegment> foundationSegments) {
        public Run {
            if (runId == null || runId.isBlank() || continuationAxis == null || terminationReasonCode == null) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_RUN_INVALID");
            }
            placements = List.copyOf(placements);
            foundationSegments = List.copyOf(foundationSegments);
            for (int index = 0; index < placements.size(); index++) {
                Placement placement = placements.get(index);
                if (!runId.equals(placement.runId()) || placement.runOrdinal() != index) {
                    throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_RUN_PLACEMENT_MEMBERSHIP_INVALID");
                }
            }
            for (CityContinuousTerrainRunPlanner.FoundationSegment segment : foundationSegments) {
                if (!runId.equals(segment.runId())) {
                    throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_RUN_SEGMENT_MEMBERSHIP_INVALID");
                }
            }
        }
    }

    public record Placement(String placementId,
                            String runId,
                            int runOrdinal,
                            BlockPoint terrainSamplePoint,
                            BlockPoint placementAnchor,
                            int rotationDegrees,
                            BlockBounds footprint,
                            int surfaceY,
                            int targetY,
                            boolean water,
                            CityContinuousTerrainRunPlanner.TerrainClass terrainClass,
                            CityContinuousTerrainRunPlanner.Decision decision,
                            String contentRef,
                            String contentHash,
                            String appliedContentRef,
                            String appliedContentHash,
                            String reasonCode) {
        public Placement {
            if (placementId == null || placementId.isBlank() || runId == null || runId.isBlank()
                    || runOrdinal < 0 || terrainSamplePoint == null || placementAnchor == null
                    || footprint == null || terrainClass == null || decision == null
                    || contentRef == null || contentRef.isBlank() || contentHash == null || contentHash.isBlank()
                    || appliedContentRef == null || appliedContentRef.isBlank()
                    || appliedContentHash == null || appliedContentHash.isBlank()
                    || reasonCode == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_RUN_PLACEMENT_INVALID");
            }
        }
    }

    private record PlacementSeed(Cell bandStart, BlockPoint samplePoint) {
    }

    private record Cell(int x, int z) {
        private static final Comparator<Cell> STABLE_ORDER = Comparator.comparingInt(Cell::z)
                .thenComparingInt(Cell::x);
    }
}
