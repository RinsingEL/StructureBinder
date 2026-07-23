package com.rinsing.geomantia.systems.city.application.terrain;

import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure planner for complete terrain-aware runs. It never partitions work by owner chunk. */
public final class CityContinuousTerrainRunPlanner {

    public Plan compile(Request request, TerrainView terrain, FallbackResolver fallbacks) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(terrain, "terrain");
        Objects.requireNonNull(fallbacks, "fallbacks");

        Map<LineKey, List<Point>> lines = new LinkedHashMap<>();
        Set<String> pointIds = new HashSet<>();
        for (Point point : request.points()) {
            if (!pointIds.add(point.pointId())) {
                throw new IllegalArgumentException("CITY_TERRAIN_RUN_POINT_ID_DUPLICATE: " + point.pointId());
            }
            int cross = request.axis().cross(point.localPoint());
            lines.computeIfAbsent(new LineKey(point.trackId(), cross), ignored -> new ArrayList<>()).add(point);
        }

        List<Run> runs = new ArrayList<>();
        for (Map.Entry<LineKey, List<Point>> entry : lines.entrySet()) {
            List<Point> line = entry.getValue();
            line.sort(Comparator.comparingInt((Point point) -> request.axis().along(point.localPoint()))
                    .thenComparing(Point::pointId));
            int start = 0;
            for (int index = 1; index <= line.size(); index++) {
                boolean split = index == line.size()
                        || request.axis().along(line.get(index).localPoint())
                        != request.axis().along(line.get(index - 1).localPoint()) + 1;
                if (split) {
                    runs.add(compileRun(request, entry.getKey(), line.subList(start, index), terrain, fallbacks));
                    start = index;
                }
            }
        }
        runs.sort(Comparator.comparing(Run::runId));
        List<FoundationSegment> segments = runs.stream()
                .flatMap(run -> run.foundationSegments().stream()).toList();
        return new Plan(List.copyOf(runs), segments);
    }

    private Run compileRun(Request request,
                           LineKey line,
                           List<Point> points,
                           TerrainView terrain,
                           FallbackResolver fallbacks) {
        String runId = runId(request.sourceId(), line, request.axis(), points);
        List<MutableOutcome> outcomes = new ArrayList<>();
        for (int ordinal = 0; ordinal < points.size(); ordinal++) {
            Point point = points.get(ordinal);
            TerrainSample sample = Objects.requireNonNull(
                    terrain.sample(point.worldAnchor().x(), point.worldAnchor().z()),
                    request.reasonCodes().terrainSampleMissing());
            outcomes.add(new MutableOutcome(point, ordinal, sample, request.reasonCodes().safe()));
        }

        int terminationOrdinal = -1;
        String terminationReason = "";
        Decision terminationDecision = Decision.TERMINATE;
        for (int index = 0; index < outcomes.size(); index++) {
            MutableOutcome current = outcomes.get(index);
            if (!current.sample.available()) {
                terminationOrdinal = index;
                terminationReason = request.reasonCodes().terrainUnavailable();
                terminationDecision = Decision.DEFER;
                break;
            }
            if (current.sample.water() && !request.policy().allowWater()) {
                terminationOrdinal = index;
                terminationReason = request.reasonCodes().waterTerminated();
                break;
            }
            if (index > 0 && Math.abs(current.sample.surfaceY() - outcomes.get(index - 1).sample.surfaceY())
                    > request.policy().maxSlopeDelta()) {
                terminationOrdinal = index;
                terminationReason = request.reasonCodes().localCliffTerminated();
                break;
            }
            int windowStart = Math.max(0, index - request.policy().continuousDropWindowBlocks() + 1);
            int high = current.sample.surfaceY();
            for (int cursor = windowStart; cursor < index; cursor++) {
                high = Math.max(high, outcomes.get(cursor).sample.surfaceY());
            }
            if (high - current.sample.surfaceY() > request.policy().maxContinuousDropBlocks()) {
                terminationOrdinal = index;
                terminationReason = request.reasonCodes().continuousDropTerminated();
                break;
            }
            if (request.policy().foundationMode() == FoundationMode.FILL_ONLY && !current.sample.water()) {
                List<Integer> local = availableLocalHeights(outcomes, index, false);
                int smoothed = median(local);
                if (smoothed - current.sample.surfaceY() > request.policy().maxFoundationDepthBlocks()) {
                    terminationOrdinal = index;
                    terminationReason = request.reasonCodes().foundationDepthTerminated();
                    break;
                }
            }
        }

        if (terminationOrdinal >= 0) {
            for (int index = terminationOrdinal; index < outcomes.size(); index++) {
                MutableOutcome value = outcomes.get(index);
                value.decision = terminationDecision;
                value.reasonCode = terminationReason;
            }
            if (terminationOrdinal > 0) {
                MutableOutcome lastSafe = outcomes.get(terminationOrdinal - 1);
                String fallback = fallbacks.fallbackFor(lastSafe.contentRef);
                if (fallback != null && !fallback.isBlank()) {
                    lastSafe.decision = Decision.END_CAP;
                    lastSafe.appliedContentRef = fallback;
                    lastSafe.reasonCode = terminationReason;
                }
            }
        }

        applyFoundationTargets(request.policy(), outcomes);
        List<PointOutcome> frozenPoints = outcomes.stream().map(value -> value.freeze(runId)).toList();
        List<FoundationSegment> segments = foundationSegments(
                request.policy(), request.reasonCodes(), runId, outcomes, terrain);
        return new Run(runId, request.sourceId(), line.trackId(), request.axis(), line.crossCoordinate(),
                frozenPoints, terminationOrdinal < 0 ? null : terminationOrdinal, terminationReason, segments);
    }

    private static void applyFoundationTargets(RunPolicy policy, List<MutableOutcome> outcomes) {
        for (int index = 0; index < outcomes.size(); index++) {
            MutableOutcome value = outcomes.get(index);
            value.targetY = value.sample.surfaceY();
            if (policy.foundationMode() != FoundationMode.FILL_ONLY || value.sample.water()
                    || value.decision == Decision.TERMINATE || value.decision == Decision.DEFER) {
                continue;
            }
            List<Integer> local = availableLocalHeights(outcomes, index, true);
            int smoothed = median(local);
            int fillDepth = Math.max(0, smoothed - value.sample.surfaceY());
            if (fillDepth <= policy.maxFoundationDepthBlocks()) {
                value.targetY = Math.max(value.sample.surfaceY(), smoothed);
            }
        }
    }

    private static List<Integer> availableLocalHeights(List<MutableOutcome> outcomes,
                                                        int index,
                                                        boolean excludeTerminated) {
        List<Integer> local = new ArrayList<>();
        for (int cursor = Math.max(0, index - 1); cursor <= Math.min(outcomes.size() - 1, index + 1); cursor++) {
            MutableOutcome neighbour = outcomes.get(cursor);
            if (!neighbour.sample.available() || neighbour.sample.water()) {
                continue;
            }
            if (excludeTerminated
                    && (neighbour.decision == Decision.TERMINATE || neighbour.decision == Decision.DEFER)) {
                continue;
            }
            local.add(neighbour.sample.surfaceY());
        }
        return local;
    }

    private static int median(List<Integer> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("CITY_TERRAIN_RUN_LOCAL_HEIGHTS_REQUIRED");
        }
        values.sort(Integer::compareTo);
        return values.get(values.size() / 2);
    }

    private static List<FoundationSegment> foundationSegments(RunPolicy policy,
                                                               ReasonCodes reasonCodes,
                                                               String runId,
                                                               List<MutableOutcome> outcomes,
                                                               TerrainView terrain) {
        if (policy.foundationMode() != FoundationMode.FILL_ONLY) {
            return List.of();
        }
        List<MutableOutcome> eligible = outcomes.stream().filter(value -> !value.sample.water()
                && value.decision != Decision.TERMINATE && value.decision != Decision.DEFER).toList();
        if (eligible.size() <= 1) {
            return List.of();
        }
        List<FoundationSegment> result = new ArrayList<>();
        for (int index = 0; index + 1 < eligible.size(); index++) {
            MutableOutcome first = eligible.get(index);
            MutableOutcome second = eligible.get(index + 1);
            if (second.ordinal == first.ordinal + 1) {
                int shoulder = safeShoulderBlocks(policy.foundationShoulderBlocks(),
                        first.point.worldAnchor(), second.point.worldAnchor(), terrain,
                        reasonCodes.terrainSampleMissing());
                result.add(new FoundationSegment(runId,
                        first.point.worldAnchor().x(), first.point.worldAnchor().z(), first.targetY,
                        second.point.worldAnchor().x(), second.point.worldAnchor().z(), second.targetY,
                        0, policy.maxFoundationDepthBlocks(), shoulder));
            }
        }
        return List.copyOf(result);
    }

    private static int safeShoulderBlocks(int configuredShoulder,
                                          BlockPoint first,
                                          BlockPoint second,
                                          TerrainView terrain,
                                          String missingSampleReason) {
        if (configuredShoulder <= 0) {
            return 0;
        }
        double dx = second.x() - first.x();
        double dz = second.z() - first.z();
        double lengthSquared = dx * dx + dz * dz;
        for (int z = Math.min(first.z(), second.z()) - configuredShoulder;
             z <= Math.max(first.z(), second.z()) + configuredShoulder; z++) {
            for (int x = Math.min(first.x(), second.x()) - configuredShoulder;
                 x <= Math.max(first.x(), second.x()) + configuredShoulder; x++) {
                double rawT = ((x - first.x()) * dx + (z - first.z()) * dz) / lengthSquared;
                if (rawT < 0.0D || rawT > 1.0D) {
                    continue;
                }
                double projectedX = first.x() + rawT * dx;
                double projectedZ = first.z() + rawT * dz;
                double lateralDistance = Math.sqrt((x - projectedX) * (x - projectedX)
                        + (z - projectedZ) * (z - projectedZ));
                if (lateralDistance > configuredShoulder) {
                    continue;
                }
                TerrainSample sample = Objects.requireNonNull(terrain.sample(x, z), missingSampleReason);
                if (!sample.available() || sample.water()) {
                    return 0;
                }
            }
        }
        return configuredShoulder;
    }

    private static String runId(String sourceId, LineKey line, Axis axis, List<Point> points) {
        String identity = sourceId + "|" + line.trackId() + "|" + axis + "|" + line.crossCoordinate()
                + "|" + axis.along(points.get(0).localPoint())
                + "|" + axis.along(points.get(points.size() - 1).localPoint());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            return "run:" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }

    public enum Axis {
        U,
        V;

        public int along(GridPoint point) {
            return this == U ? point.u() : point.v();
        }

        public int cross(GridPoint point) {
            return this == U ? point.v() : point.u();
        }
    }

    public enum FoundationMode {
        NONE,
        FILL_ONLY
    }

    public enum TerrainClass {
        SAFE,
        WATER,
        UNAVAILABLE
    }

    public enum Decision {
        PLACE,
        END_CAP,
        TERMINATE,
        DEFER
    }

    @FunctionalInterface
    public interface TerrainView {
        TerrainSample sample(int worldX, int worldZ);
    }

    @FunctionalInterface
    public interface FallbackResolver {
        String fallbackFor(String contentRef);
    }

    public record Request(String sourceId,
                          Axis axis,
                          RunPolicy policy,
                          ReasonCodes reasonCodes,
                          List<Point> points) {
        public Request {
            if (sourceId == null || sourceId.isBlank()) {
                throw new IllegalArgumentException("CITY_TERRAIN_RUN_SOURCE_ID_REQUIRED");
            }
            Objects.requireNonNull(axis, "axis");
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(reasonCodes, "reasonCodes");
            points = List.copyOf(Objects.requireNonNull(points, "points"));
        }
    }

    public record RunPolicy(int maxSlopeDelta,
                            boolean allowWater,
                            int maxContinuousDropBlocks,
                            int continuousDropWindowBlocks,
                            FoundationMode foundationMode,
                            int maxFoundationDepthBlocks,
                            int foundationShoulderBlocks) {
        public RunPolicy {
            if (maxSlopeDelta < 0 || maxContinuousDropBlocks < 0 || continuousDropWindowBlocks <= 0
                    || maxFoundationDepthBlocks < 0 || foundationShoulderBlocks < 0) {
                throw new IllegalArgumentException("CITY_TERRAIN_RUN_POLICY_INVALID");
            }
            Objects.requireNonNull(foundationMode, "foundationMode");
            if (foundationMode == FoundationMode.FILL_ONLY && maxFoundationDepthBlocks <= 0) {
                throw new IllegalArgumentException("CITY_TERRAIN_RUN_FOUNDATION_DEPTH_REQUIRED");
            }
        }
    }

    public record ReasonCodes(String safe,
                              String terrainUnavailable,
                              String waterTerminated,
                              String localCliffTerminated,
                              String continuousDropTerminated,
                              String foundationDepthTerminated,
                              String terrainSampleMissing) {
        public ReasonCodes {
            for (String value : List.of(safe, terrainUnavailable, waterTerminated, localCliffTerminated,
                    continuousDropTerminated, foundationDepthTerminated, terrainSampleMissing)) {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("CITY_TERRAIN_RUN_REASON_CODE_REQUIRED");
                }
            }
        }
    }

    public record GridPoint(int u, int v) {
    }

    public record Point(String trackId,
                        String pointId,
                        GridPoint localPoint,
                        BlockPoint worldAnchor,
                        String contentRef) {
        public Point {
            if (trackId == null || trackId.isBlank() || pointId == null || pointId.isBlank()
                    || contentRef == null || contentRef.isBlank()) {
                throw new IllegalArgumentException("CITY_TERRAIN_RUN_POINT_INVALID");
            }
            Objects.requireNonNull(localPoint, "localPoint");
            Objects.requireNonNull(worldAnchor, "worldAnchor");
        }
    }

    public record TerrainSample(int surfaceY, boolean water, boolean available) {
    }

    public record Plan(List<Run> runs, List<FoundationSegment> foundationSegments) {
        public Plan {
            runs = List.copyOf(runs);
            foundationSegments = List.copyOf(foundationSegments);
            Set<String> runIds = new HashSet<>();
            Set<String> pointIds = new HashSet<>();
            for (Run run : runs) {
                if (!runIds.add(run.runId())) {
                    throw new IllegalArgumentException("CITY_TERRAIN_RUN_ID_DUPLICATE: " + run.runId());
                }
                for (PointOutcome point : run.points()) {
                    if (!pointIds.add(point.pointId())) {
                        throw new IllegalArgumentException("CITY_TERRAIN_RUN_POINT_ID_DUPLICATE: "
                                + point.pointId());
                    }
                }
            }
            List<FoundationSegment> derived = runs.stream()
                    .flatMap(run -> run.foundationSegments().stream()).toList();
            if (!foundationSegments.equals(derived)) {
                throw new IllegalArgumentException("CITY_TERRAIN_RUN_FOUNDATION_SEGMENTS_MISMATCH");
            }
        }
    }

    public record Run(String runId,
                      String sourceId,
                      String trackId,
                      Axis axis,
                      int crossCoordinate,
                      List<PointOutcome> points,
                      Integer terminationOrdinal,
                      String terminationReasonCode,
                      List<FoundationSegment> foundationSegments) {
        public Run {
            if (runId == null || runId.isBlank() || sourceId == null || sourceId.isBlank()
                    || trackId == null || trackId.isBlank() || axis == null
                    || terminationReasonCode == null) {
                throw new IllegalArgumentException("CITY_TERRAIN_RUN_INVALID");
            }
            points = List.copyOf(points);
            foundationSegments = List.copyOf(foundationSegments);
            if (points.isEmpty()) {
                throw new IllegalArgumentException("CITY_TERRAIN_RUN_POINTS_REQUIRED");
            }
            for (int index = 0; index < points.size(); index++) {
                PointOutcome point = points.get(index);
                if (!runId.equals(point.runId()) || point.runOrdinal() != index) {
                    throw new IllegalArgumentException("CITY_TERRAIN_RUN_POINT_MEMBERSHIP_INVALID");
                }
            }
            for (FoundationSegment segment : foundationSegments) {
                if (!runId.equals(segment.runId())) {
                    throw new IllegalArgumentException("CITY_TERRAIN_RUN_SEGMENT_MEMBERSHIP_INVALID");
                }
            }
            if (terminationOrdinal == null && !terminationReasonCode.isEmpty()
                    || terminationOrdinal != null && (terminationOrdinal < 0
                    || terminationOrdinal >= points.size() || terminationReasonCode.isEmpty())) {
                throw new IllegalArgumentException("CITY_TERRAIN_RUN_TERMINATION_INVALID");
            }
        }
    }

    public record PointOutcome(String runId,
                               String pointId,
                               BlockPoint worldAnchor,
                               int runOrdinal,
                               int surfaceY,
                               int targetY,
                               boolean water,
                               TerrainClass terrainClass,
                               Decision decision,
                               String contentRef,
                               String appliedContentRef,
                               String reasonCode) {
        public PointOutcome {
            if (runId == null || runId.isBlank() || pointId == null || pointId.isBlank()
                    || worldAnchor == null || runOrdinal < 0 || terrainClass == null || decision == null
                    || contentRef == null || contentRef.isBlank()
                    || appliedContentRef == null || appliedContentRef.isBlank()
                    || reasonCode == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("CITY_TERRAIN_RUN_OUTCOME_INVALID");
            }
        }
    }

    public record FoundationSegment(String runId,
                                    int x0,
                                    int z0,
                                    int y0,
                                    int x1,
                                    int z1,
                                    int y1,
                                     int halfWidth,
                                     int maxDepthBlocks,
                                     int shoulderBlocks)
            implements CityTerrainFoundationDensityComputer.FoundationSegmentView {
        public FoundationSegment {
            if (runId == null || runId.isBlank() || x0 == x1 && z0 == z1 || halfWidth < 0
                    || maxDepthBlocks <= 0 || shoulderBlocks < 0) {
                throw new IllegalArgumentException("CITY_TERRAIN_RUN_FOUNDATION_SEGMENT_INVALID");
            }
        }
    }

    private record LineKey(String trackId, int crossCoordinate) {
    }

    private static final class MutableOutcome {
        private final Point point;
        private final int ordinal;
        private final TerrainSample sample;
        private final String contentRef;
        private Decision decision = Decision.PLACE;
        private String appliedContentRef;
        private String reasonCode;
        private int targetY;

        private MutableOutcome(Point point, int ordinal, TerrainSample sample, String safeReason) {
            this.point = point;
            this.ordinal = ordinal;
            this.sample = sample;
            this.contentRef = point.contentRef();
            this.appliedContentRef = point.contentRef();
            this.reasonCode = safeReason;
            this.targetY = sample.surfaceY();
        }

        private PointOutcome freeze(String runId) {
            TerrainClass terrainClass = !sample.available() ? TerrainClass.UNAVAILABLE
                    : sample.water() ? TerrainClass.WATER : TerrainClass.SAFE;
            return new PointOutcome(runId, point.pointId(), point.worldAnchor(), ordinal,
                    sample.surfaceY(), targetY, sample.water(), terrainClass, decision,
                    contentRef, appliedContentRef, reasonCode);
        }
    }
}
