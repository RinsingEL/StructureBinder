package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * Classifies an irregular LandUse mask into contour-following field and channel bands.
 */
public final class ContourBandSurfaceClassifier {
    private static final double FLAT_GRADIENT_THRESHOLD = 0.02;
    private static final double TANGENT_COST_MULTIPLIER = 2.0;
    private static final double EPSILON = 1.0e-9;
    private static final double DIAGONAL_COST = Math.sqrt(2.0);
    private static final int GRID_MARGIN = 2;
    private static final int[][] NEIGHBORS_8 = {
            {-1, -1}, {0, -1}, {1, -1},
            {-1, 0}, {1, 0},
            {-1, 1}, {0, 1}, {1, 1}
    };
    private static final int[][] NEIGHBORS_4 = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}};

    public Result classify(Request request) {
        Objects.requireNonNull(request, "request");
        MaskGrid grid = MaskGrid.from(request.memberSpans(), request.exclusionSpans());
        SmoothedElevation elevation = SmoothedElevation.from(request.terrainField(), grid);
        double anchorElevation = elevation.at(request.anchor().x(), request.anchor().z());

        double gradientTotal = 0;
        double maxGradient = 0;
        for (int z = grid.minZ; z <= grid.maxZ; z++) {
            for (int x = grid.minX; x <= grid.maxX; x++) {
                if (!grid.member(x, z)) continue;
                double gradient = elevation.gradientMagnitude(x, z);
                gradientTotal += gradient;
                maxGradient = Math.max(maxGradient, gradient);
            }
        }
        double meanGradient = gradientTotal / grid.memberCount;

        Mode mode = meanGradient < FLAT_GRADIENT_THRESHOLD
                ? Mode.RADIAL_FALLBACK
                : Mode.CONTOUR_NORMAL;
        double[] contourDistance = null;
        if (mode == Mode.CONTOUR_NORMAL) {
            List<Integer> contour = anchorContour(elevation, grid, request.anchor(), anchorElevation);
            if (contour.isEmpty()) {
                mode = Mode.RADIAL_FALLBACK;
            } else {
                contourDistance = distanceFrom(contour, grid, elevation);
            }
        }

        BandRole[] roles = new BandRole[grid.size()];
        for (int z = grid.minZ; z <= grid.maxZ; z++) {
            for (int x = grid.minX; x <= grid.maxX; x++) {
                if (!grid.allowed(x, z)) continue;
                double distance = mode == Mode.RADIAL_FALLBACK
                        ? Math.hypot(x - request.anchor().x(), z - request.anchor().z())
                        : signedDistance(contourDistance[grid.index(x, z)],
                        elevation.at(x, z) - anchorElevation,
                        elevation, request.anchor(), x, z);
                int phase = Math.floorMod((int) Math.floor(distance + EPSILON), request.repeatPeriodBlocks());
                roles[grid.index(x, z)] = roleFor(
                        phase, request.fieldBeforeBlocks(), request.channelWidthBlocks());
            }
        }
        repairDiagonalWaterLinks(roles, grid);

        List<BandSpan> spans = new ArrayList<>();
        int channelBlocks = 0;
        int fieldBlocks = 0;
        for (int z = grid.minZ; z <= grid.maxZ; z++) {
            BandRole activeRole = null;
            int spanStart = grid.minX;
            for (int x = grid.minX; x <= grid.maxX; x++) {
                BandRole role = roles[grid.index(x, z)];
                if (role != null) {
                    if (role.isChannel()) channelBlocks++;
                    else fieldBlocks++;
                }
                if (role != activeRole) {
                    if (activeRole != null) {
                        spans.add(new BandSpan(z, spanStart, x - 1, activeRole));
                    }
                    activeRole = role;
                    spanStart = x;
                }
            }
            if (activeRole != null) spans.add(new BandSpan(z, spanStart, grid.maxX, activeRole));
        }
        return new Result(List.copyOf(spans), mode, request.anchor(), request.repeatPeriodBlocks(),
                fieldBlocks, channelBlocks, meanGradient, maxGradient);
    }

    private static BandRole roleFor(int phase, int fieldBeforeBlocks, int channelWidthBlocks) {
        int channelOffset = phase - fieldBeforeBlocks;
        if (channelOffset < 0 || channelOffset >= channelWidthBlocks) return BandRole.FIELD;
        if (channelWidthBlocks == 1) return BandRole.CHANNEL_WATER;
        if (channelOffset == 0) return BandRole.CHANNEL_BEFORE_BANK;
        if (channelOffset == channelWidthBlocks - 1) return BandRole.CHANNEL_AFTER_BANK;
        return BandRole.CHANNEL_WATER;
    }

    private static void repairDiagonalWaterLinks(BandRole[] roles, MaskGrid grid) {
        boolean[] originalWater = new boolean[roles.length];
        for (int index = 0; index < roles.length; index++) {
            originalWater[index] = roles[index] == BandRole.CHANNEL_WATER;
        }
        boolean[] visited = new boolean[roles.length];
        for (int start = 0; start < originalWater.length; start++) {
            if (!originalWater[start] || visited[start]) continue;
            List<Integer> component = waterComponent(start, originalWater, visited, grid);
            repairWaterComponent(roles, originalWater, component, grid);
        }
        if (waterComponentCount(roles, grid, NEIGHBORS_8)
                != waterComponentCount(roles, grid, NEIGHBORS_4)) {
            throw new IllegalArgumentException("CONTOUR_BAND_WATER_NOT_FOUR_CONNECTED");
        }
    }

    private static int waterComponentCount(BandRole[] roles, MaskGrid grid, int[][] directions) {
        boolean[] visited = new boolean[roles.length];
        int componentCount = 0;
        for (int start = 0; start < roles.length; start++) {
            if (roles[start] != BandRole.CHANNEL_WATER || visited[start]) continue;
            componentCount++;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            visited[start] = true;
            while (!queue.isEmpty()) {
                int current = queue.removeFirst();
                int x = current % grid.width;
                int z = current / grid.width;
                for (int[] direction : directions) {
                    int nextX = x + direction[0];
                    int nextZ = z + direction[1];
                    if (nextX < 0 || nextX >= grid.width || nextZ < 0 || nextZ >= grid.height) continue;
                    int next = nextZ * grid.width + nextX;
                    if (roles[next] == BandRole.CHANNEL_WATER && !visited[next]) {
                        visited[next] = true;
                        queue.addLast(next);
                    }
                }
            }
        }
        return componentCount;
    }

    private static List<Integer> waterComponent(int start,
                                                boolean[] originalWater,
                                                boolean[] visited,
                                                MaskGrid grid) {
        List<Integer> component = new ArrayList<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        visited[start] = true;
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            component.add(current);
            int x = current % grid.width;
            int z = current / grid.width;
            for (int[] direction : NEIGHBORS_8) {
                int nextX = x + direction[0];
                int nextZ = z + direction[1];
                if (nextX < 0 || nextX >= grid.width || nextZ < 0 || nextZ >= grid.height) continue;
                int next = nextZ * grid.width + nextX;
                if (originalWater[next] && !visited[next]) {
                    visited[next] = true;
                    queue.addLast(next);
                }
            }
        }
        component.sort(Integer::compareTo);
        return component;
    }

    private static void repairWaterComponent(BandRole[] roles,
                                             boolean[] originalWater,
                                             List<Integer> component,
                                             MaskGrid grid) {
        List<DiagonalLink> diagonalLinks = diagonalLinks(component, originalWater, grid).stream()
                .filter(link -> !originalWater[link.firstConnector] && !originalWater[link.secondConnector])
                .toList();
        List<List<Integer>> candidates = diagonalLinks.stream().map(link ->
                        List.of(link.firstConnector, link.secondConnector).stream()
                                .filter(index -> grid.allowed[index])
                                .sorted(Comparator.comparingInt(
                                                (Integer index) -> connectorRank(roles, originalWater, index, grid))
                                        .thenComparingInt(Integer::intValue))
                                .toList())
                .toList();
        int[] assignments = new int[diagonalLinks.size()];
        Arrays.fill(assignments, -1);
        Map<Integer, Integer> connectorOwners = new HashMap<>();
        java.util.Set<Integer> demotions = new java.util.TreeSet<>();
        for (int linkIndex = 0; linkIndex < diagonalLinks.size(); linkIndex++) {
            if (!assignUniqueConnector(linkIndex, candidates, assignments, connectorOwners,
                    new java.util.HashSet<>())) {
                DiagonalLink link = diagonalLinks.get(linkIndex);
                demotions.add(Math.max(link.source, link.target));
            }
        }
        Arrays.stream(assignments).filter(index -> index >= 0).forEach(index ->
                roles[index] = BandRole.CHANNEL_WATER);
        demotions.forEach(index -> roles[index] = BandRole.CHANNEL_BEFORE_BANK);
    }

    private static boolean assignUniqueConnector(int linkIndex,
                                                 List<List<Integer>> candidates,
                                                 int[] assignments,
                                                 Map<Integer, Integer> connectorOwners,
                                                 java.util.Set<Integer> visitedConnectors) {
        for (int connector : candidates.get(linkIndex)) {
            if (!visitedConnectors.add(connector)) continue;
            Integer previousOwner = connectorOwners.get(connector);
            if (previousOwner == null || assignUniqueConnector(previousOwner, candidates, assignments,
                    connectorOwners, visitedConnectors)) {
                assignments[linkIndex] = connector;
                connectorOwners.put(connector, linkIndex);
                return true;
            }
        }
        return false;
    }

    private static List<DiagonalLink> diagonalLinks(List<Integer> component,
                                                    boolean[] originalWater,
                                                    MaskGrid grid) {
        int[][] forwardDiagonals = {{-1, 1}, {1, 1}};
        List<DiagonalLink> result = new ArrayList<>();
        for (int source : component) {
            int x = source % grid.width;
            int z = source / grid.width;
            for (int[] diagonal : forwardDiagonals) {
                int targetX = x + diagonal[0];
                int targetZ = z + diagonal[1];
                if (targetX < 0 || targetX >= grid.width || targetZ < 0 || targetZ >= grid.height) continue;
                int target = targetZ * grid.width + targetX;
                if (!originalWater[target]) continue;
                int horizontal = z * grid.width + targetX;
                int vertical = targetZ * grid.width + x;
                result.add(new DiagonalLink(source, target, horizontal, vertical));
            }
        }
        result.sort(Comparator.comparingInt(DiagonalLink::source).thenComparingInt(DiagonalLink::target));
        return result;
    }

    private static int connectorRank(BandRole[] roles,
                                     boolean[] originalWater,
                                     int connector,
                                     MaskGrid grid) {
        int localX = connector % grid.width;
        int localZ = connector / grid.width;
        int originalNeighbors = 0;
        for (int[] direction : NEIGHBORS_4) {
            int nextX = localX + direction[0];
            int nextZ = localZ + direction[1];
            if (nextX >= 0 && nextX < grid.width && nextZ >= 0 && nextZ < grid.height
                    && originalWater[nextZ * grid.width + nextX]) {
                originalNeighbors++;
            }
        }
        int neighborPenalty = Math.abs(originalNeighbors - 2) * 4;
        int surfacePenalty = roles[connector] != null && roles[connector].isChannel() ? 0 : 1;
        return neighborPenalty + surfacePenalty;
    }

    private static double signedDistance(double distance,
                                         double elevationDelta,
                                         SmoothedElevation elevation,
                                         BlockPoint anchor,
                                         int x,
                                         int z) {
        if (elevationDelta > EPSILON) return distance;
        if (elevationDelta < -EPSILON) return -distance;
        double anchorGradientX = elevation.gradientX(anchor.x(), anchor.z());
        double anchorGradientZ = elevation.gradientZ(anchor.x(), anchor.z());
        double projection = (x - anchor.x()) * anchorGradientX + (z - anchor.z()) * anchorGradientZ;
        return projection < 0 ? -distance : distance;
    }

    private static List<Integer> anchorContour(SmoothedElevation elevation,
                                               MaskGrid grid,
                                               BlockPoint anchor,
                                               double anchorElevation) {
        boolean[] candidates = new boolean[grid.size()];
        int anchorIndex = grid.index(anchor.x(), anchor.z());
        candidates[anchorIndex] = true;
        for (int z = grid.minZ; z <= grid.maxZ; z++) {
            for (int x = grid.minX; x <= grid.maxX; x++) {
                int index = grid.index(x, z);
                double delta = elevation.at(x, z) - anchorElevation;
                if (x < grid.maxX) {
                    addCrossingCandidate(candidates, index, index + 1, delta,
                            elevation.at(x + 1, z) - anchorElevation);
                }
                if (z < grid.maxZ) {
                    addCrossingCandidate(candidates, index, index + grid.width, delta,
                            elevation.at(x, z + 1) - anchorElevation);
                }
            }
        }

        boolean[] visited = new boolean[grid.size()];
        List<List<Integer>> components = new ArrayList<>();
        for (int index = 0; index < candidates.length; index++) {
            if (!candidates[index] || visited[index]) continue;
            List<Integer> component = new ArrayList<>();
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(index);
            visited[index] = true;
            while (!queue.isEmpty()) {
                int current = queue.removeFirst();
                component.add(current);
                int localX = current % grid.width;
                int localZ = current / grid.width;
                for (int[] direction : NEIGHBORS_8) {
                    int nextX = localX + direction[0];
                    int nextZ = localZ + direction[1];
                    if (nextX < 0 || nextX >= grid.width || nextZ < 0 || nextZ >= grid.height) continue;
                    int next = nextZ * grid.width + nextX;
                    if (candidates[next] && !visited[next]) {
                        visited[next] = true;
                        queue.addLast(next);
                    }
                }
            }
            components.add(component);
        }
        return components.stream().min(Comparator
                        .comparingLong((List<Integer> component) -> component.stream()
                                .mapToLong(index -> squaredDistance(index, anchor, grid)).min().orElse(Long.MAX_VALUE))
                        .thenComparingInt(component -> component.stream().mapToInt(Integer::intValue).min().orElse(0)))
                .orElse(List.of());
    }

    private static void addCrossingCandidate(boolean[] candidates,
                                             int leftIndex,
                                             int rightIndex,
                                             double leftDelta,
                                             double rightDelta) {
        if (Math.abs(leftDelta) <= EPSILON) {
            candidates[leftIndex] = true;
            return;
        }
        if (Math.abs(rightDelta) <= EPSILON) {
            candidates[rightIndex] = true;
            return;
        }
        if (Math.signum(leftDelta) == Math.signum(rightDelta)) return;
        if (Math.abs(leftDelta) <= Math.abs(rightDelta)) candidates[leftIndex] = true;
        else candidates[rightIndex] = true;
    }

    private static long squaredDistance(int index, BlockPoint anchor, MaskGrid grid) {
        int x = grid.minX + index % grid.width;
        int z = grid.minZ + index / grid.width;
        long dx = (long) x - anchor.x();
        long dz = (long) z - anchor.z();
        return dx * dx + dz * dz;
    }

    private static double[] distanceFrom(List<Integer> sources,
                                         MaskGrid grid,
                                         SmoothedElevation elevation) {
        int width = grid.width;
        int height = grid.height;
        int size = grid.size();
        double[] distances = new double[size];
        Arrays.fill(distances, Double.POSITIVE_INFINITY);
        PriorityQueue<DistanceNode> open = new PriorityQueue<>(DistanceNode.ORDER);
        for (int source : sources.stream().distinct().sorted().toList()) {
            distances[source] = 0;
            open.add(new DistanceNode(source, 0));
        }
        while (!open.isEmpty()) {
            DistanceNode current = open.remove();
            if (current.distance > distances[current.index] + EPSILON) continue;
            int x = current.index % width;
            int z = current.index / width;
            for (int[] direction : NEIGHBORS_8) {
                int nextX = x + direction[0];
                int nextZ = z + direction[1];
                if (nextX < 0 || nextX >= width || nextZ < 0 || nextZ >= height) continue;
                int next = nextZ * width + nextX;
                int worldX = grid.minX + x;
                int worldZ = grid.minZ + z;
                int nextWorldX = grid.minX + nextX;
                int nextWorldZ = grid.minZ + nextZ;
                double step = anisotropicStepCost(elevation, worldX, worldZ,
                        nextWorldX, nextWorldZ);
                double candidate = current.distance + step;
                if (candidate + EPSILON < distances[next]) {
                    distances[next] = candidate;
                    open.add(new DistanceNode(next, candidate));
                }
            }
        }
        return distances;
    }

    private static double anisotropicStepCost(SmoothedElevation elevation,
                                              int x,
                                              int z,
                                              int nextX,
                                              int nextZ) {
        int dx = nextX - x;
        int dz = nextZ - z;
        double geometricCost = dx == 0 || dz == 0 ? 1.0 : DIAGONAL_COST;
        double firstAlignment = normalAlignment(elevation, x, z, dx, dz, geometricCost);
        double secondAlignment = normalAlignment(elevation, nextX, nextZ, dx, dz, geometricCost);
        if (firstAlignment < 0 && secondAlignment < 0) return geometricCost;
        double alignment = firstAlignment < 0 ? secondAlignment
                : secondAlignment < 0 ? firstAlignment : (firstAlignment + secondAlignment) / 2.0;
        double tangentFraction = 1.0 - alignment;
        return geometricCost * (1.0 + TANGENT_COST_MULTIPLIER * tangentFraction * tangentFraction);
    }

    private static double normalAlignment(SmoothedElevation elevation,
                                          int x,
                                          int z,
                                          int dx,
                                          int dz,
                                          double stepLength) {
        double gradientX = elevation.gradientX(x, z);
        double gradientZ = elevation.gradientZ(x, z);
        double magnitude = Math.hypot(gradientX, gradientZ);
        if (magnitude < FLAT_GRADIENT_THRESHOLD) return -1;
        return Math.min(1.0, Math.abs(dx * gradientX + dz * gradientZ) / (stepLength * magnitude));
    }

    public record Request(List<LandUseAreaPlan.ScanlineSpan> memberSpans,
                          List<LandUseAreaPlan.ScanlineSpan> exclusionSpans,
                          LandUseTerrainField terrainField,
                          BlockPoint anchor,
                          int fieldBeforeBlocks,
                          int channelWidthBlocks,
                          int fieldAfterBlocks) {
        public Request {
            memberSpans = List.copyOf(Objects.requireNonNull(memberSpans, "memberSpans"));
            exclusionSpans = List.copyOf(Objects.requireNonNull(exclusionSpans, "exclusionSpans"));
            Objects.requireNonNull(terrainField, "terrainField");
            Objects.requireNonNull(anchor, "anchor");
            if (memberSpans.isEmpty()) {
                throw new IllegalArgumentException("CONTOUR_BAND_MEMBER_SPANS_REQUIRED");
            }
            if (fieldBeforeBlocks <= 0 || channelWidthBlocks <= 0 || fieldAfterBlocks <= 0) {
                throw new IllegalArgumentException("CONTOUR_BAND_WIDTHS_MUST_BE_POSITIVE");
            }
            int minX = memberSpans.stream().mapToInt(LandUseAreaPlan.ScanlineSpan::minX).min().orElseThrow();
            int maxX = memberSpans.stream().mapToInt(LandUseAreaPlan.ScanlineSpan::maxX).max().orElseThrow();
            int minZ = memberSpans.stream().mapToInt(LandUseAreaPlan.ScanlineSpan::z).min().orElseThrow();
            int maxZ = memberSpans.stream().mapToInt(LandUseAreaPlan.ScanlineSpan::z).max().orElseThrow();
            if (anchor.x() < minX || anchor.x() > maxX || anchor.z() < minZ || anchor.z() > maxZ) {
                throw new IllegalArgumentException("CONTOUR_BAND_ANCHOR_OUTSIDE_MEMBER_BOUNDS");
            }
        }

        public int repeatPeriodBlocks() {
            return Math.addExact(Math.addExact(fieldBeforeBlocks, channelWidthBlocks), fieldAfterBlocks);
        }
    }

    public record Result(List<BandSpan> spans,
                         Mode mode,
                         BlockPoint anchor,
                         int repeatPeriodBlocks,
                         int fieldBlockCount,
                         int channelBlockCount,
                         double meanGradient,
                         double maxGradient) {
        public Result {
            spans = List.copyOf(Objects.requireNonNull(spans, "spans"));
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(anchor, "anchor");
            if (repeatPeriodBlocks <= 0) throw new IllegalArgumentException("repeatPeriodBlocks must be positive");
            if (fieldBlockCount < 0 || channelBlockCount < 0) {
                throw new IllegalArgumentException("block counts must not be negative");
            }
        }

        public Optional<BandRole> roleAt(int x, int z) {
            return spans.stream().filter(span -> span.z == z && x >= span.minX && x <= span.maxX)
                    .map(BandSpan::role).findFirst();
        }

        public List<LandUseAreaPlan.ScanlineSpan> fieldSpans() {
            return spansFor(BandRole.FIELD);
        }

        public List<LandUseAreaPlan.ScanlineSpan> channelSpans() {
            return spans.stream().filter(span -> span.role.isChannel())
                    .map(span -> new LandUseAreaPlan.ScanlineSpan(span.z, span.minX, span.maxX)).toList();
        }

        private List<LandUseAreaPlan.ScanlineSpan> spansFor(BandRole role) {
            return spans.stream().filter(span -> span.role == role)
                    .map(span -> new LandUseAreaPlan.ScanlineSpan(span.z, span.minX, span.maxX)).toList();
        }
    }

    public record BandSpan(int z, int minX, int maxX, BandRole role) {
        public BandSpan {
            if (minX > maxX) throw new IllegalArgumentException("minX must be <= maxX");
            Objects.requireNonNull(role, "role");
        }
    }

    public enum BandRole {
        FIELD,
        CHANNEL_BEFORE_BANK,
        CHANNEL_WATER,
        CHANNEL_AFTER_BANK;

        public boolean isChannel() {
            return this != FIELD;
        }
    }

    public enum Mode {
        CONTOUR_NORMAL,
        RADIAL_FALLBACK
    }

    private record DistanceNode(int index, double distance) {
        private static final Comparator<DistanceNode> ORDER = Comparator.comparingDouble(DistanceNode::distance)
                .thenComparingInt(DistanceNode::index);
    }

    private record DiagonalLink(int source, int target, int firstConnector, int secondConnector) {
    }

    private static final class MaskGrid {
        private final int minX;
        private final int minZ;
        private final int maxX;
        private final int maxZ;
        private final int width;
        private final int height;
        private final boolean[] members;
        private final boolean[] allowed;
        private final int memberCount;

        private MaskGrid(int minX,
                         int minZ,
                         int maxX,
                         int maxZ,
                         boolean[] members,
                         boolean[] allowed,
                         int memberCount) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.width = maxX - minX + 1;
            this.height = maxZ - minZ + 1;
            this.members = members;
            this.allowed = allowed;
            this.memberCount = memberCount;
        }

        private static MaskGrid from(List<LandUseAreaPlan.ScanlineSpan> members,
                                     List<LandUseAreaPlan.ScanlineSpan> exclusions) {
            int minX = members.stream().mapToInt(LandUseAreaPlan.ScanlineSpan::minX).min().orElseThrow();
            int maxX = members.stream().mapToInt(LandUseAreaPlan.ScanlineSpan::maxX).max().orElseThrow();
            int minZ = members.stream().mapToInt(LandUseAreaPlan.ScanlineSpan::z).min().orElseThrow();
            int maxZ = members.stream().mapToInt(LandUseAreaPlan.ScanlineSpan::z).max().orElseThrow();
            int width = Math.addExact(Math.subtractExact(maxX, minX), 1);
            int height = Math.addExact(Math.subtractExact(maxZ, minZ), 1);
            boolean[] membersMask = new boolean[Math.multiplyExact(width, height)];
            for (LandUseAreaPlan.ScanlineSpan span : members) {
                if (span.z() < minZ || span.z() > maxZ) continue;
                int from = Math.max(span.minX(), minX);
                int to = Math.min(span.maxX(), maxX);
                for (int x = from; x <= to; x++) {
                    membersMask[(span.z() - minZ) * width + x - minX] = true;
                }
            }
            boolean[] allowed = membersMask.clone();
            for (LandUseAreaPlan.ScanlineSpan span : exclusions) {
                if (span.z() < minZ || span.z() > maxZ) continue;
                int from = Math.max(span.minX(), minX);
                int to = Math.min(span.maxX(), maxX);
                for (int x = from; x <= to; x++) allowed[(span.z() - minZ) * width + x - minX] = false;
            }
            int memberCount = 0;
            int count = 0;
            for (boolean cell : membersMask) if (cell) memberCount++;
            for (boolean cell : allowed) if (cell) count++;
            if (count == 0) throw new IllegalArgumentException("CONTOUR_BAND_MASK_EMPTY_AFTER_EXCLUSION");
            return new MaskGrid(minX, minZ, maxX, maxZ, membersMask, allowed, memberCount);
        }

        private int size() {
            return allowed.length;
        }

        private int index(int x, int z) {
            return (z - minZ) * width + x - minX;
        }

        private boolean allowed(int x, int z) {
            return allowed[index(x, z)];
        }

        private boolean member(int x, int z) {
            return members[index(x, z)];
        }

        private boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private static final class SmoothedElevation {
        private final int minX;
        private final int minZ;
        private final int width;
        private final double[] values;

        private SmoothedElevation(int minX, int minZ, int width, double[] values) {
            this.minX = minX;
            this.minZ = minZ;
            this.width = width;
            this.values = values;
        }

        private static SmoothedElevation from(LandUseTerrainField terrain, MaskGrid mask) {
            ElevationSampler sampler = new ElevationSampler(terrain);
            int minX = Math.subtractExact(mask.minX, GRID_MARGIN);
            int minZ = Math.subtractExact(mask.minZ, GRID_MARGIN);
            int width = Math.addExact(mask.width, GRID_MARGIN * 2);
            int height = Math.addExact(mask.height, GRID_MARGIN * 2);
            double[] raw = new double[Math.multiplyExact(width, height)];
            for (int localZ = 0; localZ < height; localZ++) {
                for (int localX = 0; localX < width; localX++) {
                    raw[localZ * width + localX] = sampler.at(minX + localX, minZ + localZ);
                }
            }
            double[] horizontal = raw.clone();
            for (int z = 0; z < height; z++) {
                for (int x = 1; x < width - 1; x++) {
                    horizontal[z * width + x] = (raw[z * width + x - 1]
                            + 2 * raw[z * width + x] + raw[z * width + x + 1]) / 4.0;
                }
            }
            double[] smooth = horizontal.clone();
            for (int z = 1; z < height - 1; z++) {
                for (int x = 0; x < width; x++) {
                    smooth[z * width + x] = (horizontal[(z - 1) * width + x]
                            + 2 * horizontal[z * width + x] + horizontal[(z + 1) * width + x]) / 4.0;
                }
            }
            return new SmoothedElevation(minX, minZ, width, smooth);
        }

        private double at(int x, int z) {
            return values[(z - minZ) * width + x - minX];
        }

        private double gradientX(int x, int z) {
            return (at(x + 1, z) - at(x - 1, z)) / 2.0;
        }

        private double gradientZ(int x, int z) {
            return (at(x, z + 1) - at(x, z - 1)) / 2.0;
        }

        private double gradientMagnitude(int x, int z) {
            return Math.hypot(gradientX(x, z), gradientZ(x, z));
        }
    }

    private static final class ElevationSampler {
        private final Map<CellKey, Double> samples = new HashMap<>();
        private final Map<CellKey, Double> nearestCache = new HashMap<>();
        private final int[] sampleXs;
        private final int[] sampleZs;

        private ElevationSampler(LandUseTerrainField terrain) {
            terrain.cells().stream().filter(LandUseTerrainField.Cell::sampled).forEach(cell ->
                    samples.putIfAbsent(new CellKey(cell.blockMinX(), cell.blockMinZ()), cell.elevation()));
            if (samples.isEmpty()) throw new IllegalArgumentException("CONTOUR_BAND_SAMPLED_TERRAIN_REQUIRED");
            sampleXs = samples.keySet().stream().mapToInt(CellKey::x).distinct().sorted().toArray();
            sampleZs = samples.keySet().stream().mapToInt(CellKey::z).distinct().sorted().toArray();
        }

        private double at(int x, int z) {
            CoordinateBracket xBracket = bracket(sampleXs, x);
            CoordinateBracket zBracket = bracket(sampleZs, z);
            double top = lerp(sample(xBracket.lower, zBracket.lower),
                    sample(xBracket.upper, zBracket.lower), xBracket.fraction);
            double bottom = lerp(sample(xBracket.lower, zBracket.upper),
                    sample(xBracket.upper, zBracket.upper), xBracket.fraction);
            double fractionZ = zBracket.fraction;
            return lerp(top, bottom, fractionZ);
        }

        private static CoordinateBracket bracket(int[] coordinates, int value) {
            int index = Arrays.binarySearch(coordinates, value);
            if (index >= 0) return new CoordinateBracket(value, value, 0);
            int insertion = -index - 1;
            if (insertion == 0) return new CoordinateBracket(coordinates[0], coordinates[0], 0);
            if (insertion == coordinates.length) {
                int last = coordinates[coordinates.length - 1];
                return new CoordinateBracket(last, last, 0);
            }
            int lower = coordinates[insertion - 1];
            int upper = coordinates[insertion];
            return new CoordinateBracket(lower, upper, (double) (value - lower) / (upper - lower));
        }

        private double sample(int x, int z) {
            CellKey key = new CellKey(x, z);
            Double exact = samples.get(key);
            if (exact != null) return exact;
            return nearestCache.computeIfAbsent(key, ignored -> samples.entrySet().stream()
                    .min(Comparator.<Map.Entry<CellKey, Double>>comparingLong(
                                    entry -> entry.getKey().squaredDistanceTo(x, z))
                            .thenComparingInt(entry -> entry.getKey().z)
                            .thenComparingInt(entry -> entry.getKey().x))
                    .orElseThrow().getValue());
        }

        private static double lerp(double left, double right, double alpha) {
            return left + (right - left) * alpha;
        }
    }

    private record CellKey(int x, int z) {
        private long squaredDistanceTo(int otherX, int otherZ) {
            long dx = (long) x - otherX;
            long dz = (long) z - otherZ;
            return dx * dx + dz * dz;
        }
    }

    private record CoordinateBracket(int lower, int upper, double fraction) {
    }
}
