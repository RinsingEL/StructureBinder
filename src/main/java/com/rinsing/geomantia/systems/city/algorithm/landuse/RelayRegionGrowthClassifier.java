package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Grows approximate landscape roles inside a fixed mask; unsuitable or unreachable cells may remain empty.
 */
public final class RelayRegionGrowthClassifier {
    private static final double SHARE_EPSILON = 1.0e-6;
    private static final int[][] DIRECTIONS_4 = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}};

    /** Bounded, one-way growth: proportions guide appearance, never trigger backtracking. */
    public Result classify(Request request) {
        Objects.requireNonNull(request, "request");
        Set<Long> unclaimed = new LongOpenHashSet(allowedCells(request.memberSpans(), request.exclusionSpans()));
        if (!unclaimed.contains(key(request.source()))) return new Result(List.of(),List.of(),List.of(),0);
        int requestedArea = unclaimed.size();
        Map<Long, CellClaim> claims = new Long2ObjectOpenHashMap<>(requestedArea);
        Map<String, RegionState> regions = new LinkedHashMap<>();
        List<RegionTrace> traces = new ArrayList<>();
        for (int index = 0; index < request.stages().size() && !unclaimed.isEmpty(); index++) {
            GrowthStage stage = request.stages().get(index);
            int target = Math.max(1, (int) Math.round(requestedArea * stage.targetShare()));
            String parentId = parentRegionId(request.stages(), index);
            RegionState parent = regions.get(parentId);
            StartEdge start = null;
            if (index == 0 && unclaimed.contains(key(request.source()))) {
                start = new StartEdge(key(request.source()), 0L, ProvenanceKind.ROOT_SOURCE);
            } else if (parent != null) {
                // Prefer a real contact, but ordinary vegetation need not form a mandatory chain.
                start = parent.cells().stream().sorted().flatMap(from -> neighbors4(from).stream()
                        .filter(unclaimed::contains)
                        .map(next -> new StartEdge(next, from, ProvenanceKind.RELAY_INTERFACE)))
                        .findFirst().orElse(null);
            }
            if (start == null) {
                long closest = unclaimed.stream().min(Comparator
                        .comparingLong((Long cell) -> Math.abs((long)x(cell) - request.source().x())
                                + Math.abs((long)z(cell) - request.source().z()))
                        .thenComparingLong(Long::longValue)).orElseThrow();
                start = new StartEdge(closest, 0L, ProvenanceKind.ROOT_SOURCE);
                parentId = "";
            }
            RegionState state = new RegionState(stage, parentId, target, request.stableSeed(), index);
            claim(state, start.point(), start.from(), start.kind(), unclaimed, claims);
            while (state.cells().size() < target && !state.rankedFrontier.isEmpty()) {
                FrontierEdge next = state.rankedFrontier.first();
                claim(state, next.point(), next.from(), ProvenanceKind.REGION_FRONTIER, unclaimed, claims);
            }
            regions.put(stage.regionId(), state);
            traces.add(state.trace(target));
        }
        List<RoleSpan> roles = compress(claims, CellClaim::roleRef).stream()
                .map(span -> new RoleSpan(span.z(), span.minX(), span.maxX(), span.value())).toList();
        List<RegionSpan> spans = compress(claims, java.util.function.Function.identity()).stream()
                .map(span -> new RegionSpan(span.z(), span.minX(), span.maxX(),
                        span.value().regionId(), span.value().roleRef())).toList();
        return new Result(roles, spans, traces, claims.size());
    }

    private static void claim(RegionState state,
                              long point,
                              long from,
                              ProvenanceKind kind,
                              Set<Long> unclaimed,
                              Map<Long, CellClaim> claims) {
        if (!unclaimed.remove(point)) throw fail("RELAY_GROWTH_DUPLICATE_CLAIM");
        if (kind != ProvenanceKind.ROOT_SOURCE && !adjacent4(point, from)) {
            throw fail("RELAY_GROWTH_NON_ADJACENT_PROVENANCE");
        }
        if (kind != ProvenanceKind.ROOT_SOURCE && !claims.containsKey(from)) {
            throw fail("RELAY_GROWTH_PROVENANCE_NOT_GROWN");
        }
        int ordinal = state.steps().size();
        claims.put(point, new CellClaim(state.stage().roleRef(), state.stage().regionId()));
        state.cells().add(point);
        state.ordinals().put(point, ordinal);
        state.steps().add(new ExpansionStep(ordinal, point(point),
                kind == ProvenanceKind.ROOT_SOURCE ? null : point(from), kind));
        refreshFrontierSources(state, point, unclaimed);
    }

    private static void refreshFrontierSources(RegionState state, long changed, Set<Long> unclaimed) {
        refreshFrontierSource(state, changed, unclaimed);
        for (long neighbor : neighbors4(changed)) refreshFrontierSource(state, neighbor, unclaimed);
        if (state.steps().isEmpty()) {
            state.frontierByPoint.clear();
            state.rankedFrontier.clear();
            return;
        }
        Set<Long> affected = new LongOpenHashSet();
        affected.add(changed);
        affected.addAll(neighbors4(changed));
        // Under deletion, incident faces can only merge. A cut vertex cannot become
        // safe unless its degree changes, i.e. an adjacent cell was removed.
        for (long candidate : affected) state.unsafeFrontier.remove(candidate);
        // Corridor spine scores depend on the newest ordinal. Rebuild while in (or
        // crossing back into) that short phase; afterwards all score changes are local.
        if (state.stage().growthForm() == GrowthForm.CORRIDOR
                && state.cells().size() <= spineTarget(state, state.stableSeed, state.stageIndex) + 1) {
            affected.addAll(state.frontierByPoint.keySet());
            for (long from : state.frontierSources) affected.addAll(neighbors4(from));
        }
        for (long candidate : affected) refreshFrontierCandidate(state, candidate, unclaimed);
    }

    private static void refreshFrontierCandidate(RegionState state, long candidate, Set<Long> unclaimed) {
        FrontierEdge previous = state.frontierByPoint.remove(candidate);
        if (previous != null) state.rankedFrontier.remove(previous);
        if (!unclaimed.contains(candidate)) return;
        int sameRegionNeighbors = sameRegionNeighborCount(candidate, state.cells());
        if (sameRegionNeighbors == 0) return;
        int remainingNeighbors = sameRegionNeighborCount(candidate, unclaimed);
        FrontierEdge best = null;
        for (long from : neighbors4(candidate)) {
            if (!state.cells().contains(from)) continue;
            FrontierEdge edge = new FrontierEdge(candidate, from, frontierScore(state, candidate, from,
                    sameRegionNeighbors, remainingNeighbors, state.steps().size() - 1,
                    state.stableSeed, state.stageIndex));
            if (best == null || FrontierEdge.ORDER.compare(edge, best) < 0) best = edge;
        }
        if (best != null) {
            state.frontierByPoint.put(candidate, best);
            if (!state.unsafeFrontier.contains(candidate)) state.rankedFrontier.add(best);
        }
    }

    private static void refreshFrontierSource(RegionState state, long cell, Set<Long> unclaimed) {
        if (state.cells().contains(cell) && hasNeighborAfterRemoval(unclaimed, cell)) {
            state.frontierSources.add(cell);
        } else {
            state.frontierSources.remove(cell);
        }
    }

    private static boolean canStartRegion(long start, Set<Long> unclaimed) {
        if (unclaimed instanceof RemainingCells) {
            unclaimed.remove(start);
            try {
                return neighbors4(start).stream().anyMatch(candidate -> unclaimed.contains(candidate)
                        && removalKeepsComponents(unclaimed, candidate));
            } finally {
                unclaimed.add(start);
            }
        }
        Set<Long> remaining = new LongOpenHashSet(unclaimed);
        remaining.remove(start);
        if (remaining.isEmpty()) return false;
        return neighbors4(start).stream().anyMatch(candidate ->
                remaining.contains(candidate) && removalKeepsComponents(remaining, candidate));
    }

    private static double frontierScore(RegionState state,
                                        long candidate,
                                        long from,
                                        int sameRegionNeighbors,
                                        int remainingNeighbors,
                                        int newestOrdinal,
                                        long stableSeed,
                                        int stageIndex) {
        double noise = stableUnit(stableSeed, stageIndex, candidate, from, 0x66726f6e74696572L);
        int directionIndex = (int) (stableUnit(stableSeed, stageIndex, 0L, 0L,
                0x64726966744cL) * 8);
        int[][] driftDirections = {{1, 0}, {1, 1}, {0, 1}, {-1, 1},
                {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
        int[] drift = driftDirections[Math.min(directionIndex, driftDirections.length - 1)];
        BlockPoint origin = state.steps().get(0).point();
        double projection = ((long) x(candidate) - origin.x()) * drift[0]
                + ((long) z(candidate) - origin.z()) * drift[1];
        if (state.stage().growthForm() == GrowthForm.PATCH) {
            return noise - Math.min(3, sameRegionNeighbors) * 0.24 + remainingNeighbors * 0.4
                    - projection * 0.12;
        }

        int sourceOrdinal = state.ordinals().get(from);
        ExpansionStep sourceStep = state.steps().get(sourceOrdinal);
        double turnPenalty = 0;
        if (sourceStep.from() != null) {
            int previousDx = x(from) - sourceStep.from().x();
            int previousDz = z(from) - sourceStep.from().z();
            int nextDx = x(candidate) - x(from);
            int nextDz = z(candidate) - z(from);
            int alignment = previousDx * nextDx + previousDz * nextDz;
            turnPenalty = alignment > 0 ? -0.58 : alignment < 0 ? 0.9 : 0.08;
        }
        int spineTarget = spineTarget(state, stableSeed, stageIndex);
        if (state.cells().size() < spineTarget) {
            double agePenalty = (newestOrdinal - sourceOrdinal) * 0.28;
            return noise * 0.22 + agePenalty + turnPenalty
                    + Math.max(0, sameRegionNeighbors - 1) * 2.8 - projection * 0.07;
        }
        double sourceLayerPenalty = sourceOrdinal / (double) spineTarget;
        return noise - Math.min(3, sameRegionNeighbors) * 0.32
                + Math.max(0, sameRegionNeighbors - 3) * 1.4
                + sourceLayerPenalty * 0.75 + remainingNeighbors * 0.08 - projection * 0.01;
    }

    private static int spineTarget(RegionState state, long stableSeed, int stageIndex) {
        double lengthVariation = 0.85 + stableUnit(stableSeed, stageIndex, 0L, 0L,
                0x7370696e654cL) * 0.3;
        return Math.min(state.targetArea(), Math.max(2,
                (int) Math.round(Math.sqrt(state.targetArea()) * 3.2 * lengthVariation)));
    }

    private static String parentRegionId(List<GrowthStage> stages, int stageIndex) {
        GrowthStage stage = stages.get(stageIndex);
        if (stageIndex == 0) return "";
        return stage.parentRegionId().isBlank() ? stages.get(stageIndex - 1).regionId() : stage.parentRegionId();
    }

    private static Set<Long> allowedCells(List<LandUseAreaPlan.ScanlineSpan> members,
                                          List<LandUseAreaPlan.ScanlineSpan> exclusions) {
        Set<Long> allowed = new LongOpenHashSet();
        addSpans(allowed, members);
        Set<Long> excluded = new LongOpenHashSet();
        addSpans(excluded, exclusions);
        allowed.removeAll(excluded);
        return allowed;
    }

    private static void addSpans(Set<Long> target, List<LandUseAreaPlan.ScanlineSpan> spans) {
        for (LandUseAreaPlan.ScanlineSpan span : spans) {
            for (int x = span.minX(); ; x++) {
                target.add(key(x, span.z()));
                if (x == span.maxX()) break;
            }
        }
    }

    private static boolean isConnected(Set<Long> cells) {
        Set<Long> visited = new LongOpenHashSet();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        long start = cells.iterator().next();
        visited.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            for (long neighbor : neighbors4(queue.removeFirst())) {
                if (cells.contains(neighbor) && visited.add(neighbor)) queue.addLast(neighbor);
            }
        }
        return visited.size() == cells.size();
    }

    /** Removing a vertex is safe exactly when all its remaining neighbors can still
     * reach each other. Grow those (at most four) searches together: stop as soon as
     * they join, or one component exhausts without joining the others. Unlike a full
     * graph cut-vertex pass this does not visit unrelated/large branches needlessly.
     * Package visibility permits exhaustive comparison with a brute-force oracle. */
    static boolean removalKeepsComponents(Set<Long> cells, long removed) {
        if (cells instanceof RemainingCells remaining && remaining.connectivity != null) {
            return remaining.connectivity.canRemove(cells, removed);
        }
        if (locallySafeRemoval(cells, removed)) return true;
        List<Long> attachments = neighbors4(removed).stream().filter(cells::contains).toList();
        int count = attachments.size();
        int[] parent = new int[count + 1];
        int[] pending = new int[count + 1];
        Long2IntOpenHashMap owner = new Long2IntOpenHashMap();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        for (int index = 1; index <= count; index++) {
            long point = attachments.get(index - 1);
            parent[index] = index;
            pending[index] = 1;
            owner.put(point, index);
            queue.addLast(point);
        }
        int components = count;
        while (!queue.isEmpty()) {
            long point = queue.removeFirst();
            int root = componentRoot(parent, owner.get(point));
            pending[root]--;
            for (long neighbor : neighbors4(point)) {
                if (neighbor == removed || !cells.contains(neighbor)) continue;
                int previous = owner.get(neighbor);
                if (previous == 0) {
                    owner.put(neighbor, root);
                    queue.addLast(neighbor);
                    pending[root]++;
                } else {
                    int other = componentRoot(parent, previous);
                    if (other != root) {
                        parent[other] = root;
                        pending[root] += pending[other];
                        if (--components == 1) return true;
                    }
                }
            }
            if (pending[root] == 0) return false;
        }
        return components <= 1;
    }

    private static int componentRoot(int[] parent, int node) {
        while (parent[node] != node) node = parent[node];
        return node;
    }

    private static boolean locallySafeRemoval(Set<Long> cells, long removed) {
        List<Long> attachments = neighbors4(removed).stream().filter(cells::contains).toList();
        if (attachments.size() <= 1) return true;
        int centerX = x(removed);
        int centerZ = z(removed);
        Set<Long> local = new LongOpenHashSet();
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                long candidateX = (long) centerX + dx;
                long candidateZ = (long) centerZ + dz;
                if (candidateX < Integer.MIN_VALUE || candidateX > Integer.MAX_VALUE
                        || candidateZ < Integer.MIN_VALUE || candidateZ > Integer.MAX_VALUE) continue;
                long candidate = key((int) candidateX, (int) candidateZ);
                if (candidate != removed && cells.contains(candidate)) local.add(candidate);
            }
        }
        Set<Long> visited = new LongOpenHashSet();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        visited.add(attachments.get(0));
        queue.add(attachments.get(0));
        while (!queue.isEmpty()) {
            for (long neighbor : neighbors4(queue.removeFirst())) {
                if (local.contains(neighbor) && visited.add(neighbor)) queue.addLast(neighbor);
            }
        }
        return visited.containsAll(attachments);
    }

    private static boolean hasNeighborAfterRemoval(Set<Long> cells, long removed) {
        return neighbors4(removed).stream().anyMatch(neighbor -> neighbor != removed && cells.contains(neighbor));
    }

    private static int sameRegionNeighborCount(long point, Set<Long> region) {
        int count = 0;
        for (long neighbor : neighbors4(point)) if (region.contains(neighbor)) count++;
        return count;
    }

    private static boolean adjacent4(long first, long second) {
        return Math.abs((long) x(first) - x(second)) + Math.abs((long) z(first) - z(second)) == 1;
    }

    private static List<Long> neighbors4(long point) {
        int pointX = x(point);
        int pointZ = z(point);
        List<Long> neighbors = new ArrayList<>(4);
        for (int[] direction : DIRECTIONS_4) {
            long nextX = (long) pointX + direction[0];
            long nextZ = (long) pointZ + direction[1];
            if (nextX >= Integer.MIN_VALUE && nextX <= Integer.MAX_VALUE
                    && nextZ >= Integer.MIN_VALUE && nextZ <= Integer.MAX_VALUE) {
                neighbors.add(key((int) nextX, (int) nextZ));
            }
        }
        return neighbors;
    }

    private static <T> List<ValueSpan<T>> compress(Map<Long, CellClaim> claims,
                                                    java.util.function.Function<CellClaim, T> value) {
        List<Long> cells = claims.keySet().stream().sorted(Comparator
                .comparingInt(RelayRegionGrowthClassifier::z)
                .thenComparingInt(RelayRegionGrowthClassifier::x)).toList();
        List<ValueSpan<T>> spans = new ArrayList<>();
        long first = cells.get(0);
        int activeZ = z(first);
        int minX = x(first);
        int maxX = minX;
        T activeValue = value.apply(claims.get(first));
        for (int index = 1; index < cells.size(); index++) {
            long cell = cells.get(index);
            T cellValue = value.apply(claims.get(cell));
            if (z(cell) == activeZ && (long) x(cell) == (long) maxX + 1L
                    && Objects.equals(activeValue, cellValue)) {
                maxX = x(cell);
                continue;
            }
            spans.add(new ValueSpan<>(activeZ, minX, maxX, activeValue));
            activeZ = z(cell);
            minX = x(cell);
            maxX = minX;
            activeValue = cellValue;
        }
        spans.add(new ValueSpan<>(activeZ, minX, maxX, activeValue));
        return List.copyOf(spans);
    }

    private static double stableUnit(long seed, int stageIndex, long point, long from, long salt) {
        long hash = mix64(seed ^ salt ^ mix64(stageIndex));
        hash = mix64(hash ^ mix64(point));
        hash = mix64(hash ^ Long.rotateLeft(mix64(from), 23));
        return (hash >>> 11) * 0x1.0p-53;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static IllegalArgumentException fail(String reason) {
        return new IllegalArgumentException(reason);
    }

    private static long key(BlockPoint point) {
        return key(point.x(), point.z());
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffff_ffffL);
    }

    private static int x(long key) {
        return (int) (key >> 32);
    }

    private static int z(long key) {
        return (int) key;
    }

    private static BlockPoint point(long key) {
        return new BlockPoint(x(key), z(key));
    }

    public record Request(List<LandUseAreaPlan.ScanlineSpan> memberSpans,
                          List<LandUseAreaPlan.ScanlineSpan> exclusionSpans,
                          BlockPoint source,
                          long stableSeed,
                          List<GrowthStage> stages) {
        public Request {
            memberSpans = List.copyOf(Objects.requireNonNull(memberSpans, "memberSpans"));
            exclusionSpans = List.copyOf(Objects.requireNonNull(exclusionSpans, "exclusionSpans"));
            Objects.requireNonNull(source, "source");
            stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
            if (memberSpans.isEmpty()) throw fail("RELAY_GROWTH_MEMBER_SPANS_REQUIRED");
            if (stages.isEmpty()) throw fail("RELAY_GROWTH_STAGES_REQUIRED");
            Set<String> regionIds = new HashSet<>();
            double shareSum = 0;
            for (int index = 0; index < stages.size(); index++) {
                GrowthStage stage = stages.get(index);
                if (!regionIds.add(stage.regionId())) {
                    throw fail("RELAY_GROWTH_REGION_DUPLICATE:" + stage.regionId());
                }
                if (index == 0 && !stage.parentRegionId().isBlank()) {
                    throw fail("RELAY_GROWTH_ROOT_PARENT_FORBIDDEN");
                }
                if (index > 0 && !stage.parentRegionId().isBlank()
                        && stages.subList(0, index).stream()
                        .noneMatch(candidate -> candidate.regionId().equals(stage.parentRegionId()))) {
                    throw fail("RELAY_GROWTH_PARENT_MUST_PRECEDE_CHILD:" + stage.regionId());
                }
                shareSum += stage.targetShare();
            }
            if (Math.abs(shareSum - 1.0) > SHARE_EPSILON) {
                throw fail("RELAY_GROWTH_TARGET_SHARES_MUST_SUM_TO_ONE:" + shareSum);
            }
        }
    }

    public record GrowthStage(String regionId,
                              String parentRegionId,
                              String roleRef,
                              double targetShare,
                              GrowthForm growthForm) {
        public GrowthStage {
            requireText(regionId, "RELAY_GROWTH_REGION_ID_REQUIRED");
            parentRegionId = parentRegionId == null ? "" : parentRegionId;
            requireText(roleRef, "RELAY_GROWTH_ROLE_REF_REQUIRED");
            if (!Double.isFinite(targetShare) || targetShare <= 0 || targetShare > 1) {
                throw fail("RELAY_GROWTH_TARGET_SHARE_INVALID:" + regionId);
            }
            Objects.requireNonNull(growthForm, "growthForm");
        }
    }

    public enum GrowthForm {
        PATCH,
        CORRIDOR
    }

    public enum ProvenanceKind {
        ROOT_SOURCE,
        RELAY_INTERFACE,
        REGION_FRONTIER
    }

    public record Result(List<RoleSpan> roleSpans,
                         List<RegionSpan> regionSpans,
                         List<RegionTrace> regions,
                         int coveredBlockCount) {
        public Result {
            roleSpans = List.copyOf(Objects.requireNonNull(roleSpans, "roleSpans"));
            regionSpans = List.copyOf(Objects.requireNonNull(regionSpans, "regionSpans"));
            regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
            if (coveredBlockCount < 0) {
                throw fail("RELAY_GROWTH_RESULT_EMPTY");
            }
        }

        public Optional<String> roleAt(int x, int z) {
            return roleSpans.stream().filter(span -> span.z() == z && x >= span.minX() && x <= span.maxX())
                    .map(RoleSpan::roleRef).findFirst();
        }

        public Optional<String> regionAt(int x, int z) {
            return regionSpans.stream().filter(span -> span.z() == z && x >= span.minX() && x <= span.maxX())
                    .map(RegionSpan::regionId).findFirst();
        }
    }

    public record RoleSpan(int z, int minX, int maxX, String roleRef) {
        public RoleSpan {
            if (minX > maxX) throw fail("RELAY_GROWTH_ROLE_SPAN_INVALID");
            requireText(roleRef, "RELAY_GROWTH_ROLE_REF_REQUIRED");
        }

        public int blockCount() {
            return maxX - minX + 1;
        }
    }

    public record RegionSpan(int z, int minX, int maxX, String regionId, String roleRef) {
        public RegionSpan {
            if (minX > maxX) throw fail("RELAY_GROWTH_REGION_SPAN_INVALID");
            requireText(regionId, "RELAY_GROWTH_REGION_ID_REQUIRED");
            requireText(roleRef, "RELAY_GROWTH_ROLE_REF_REQUIRED");
        }
    }

    public record RegionTrace(String regionId,
                              String parentRegionId,
                              String roleRef,
                              GrowthForm growthForm,
                              BlockPoint start,
                              int targetAreaBlocks,
                              int actualAreaBlocks,
                              List<ExpansionStep> expansionTrace) {
        public RegionTrace {
            requireText(regionId, "RELAY_GROWTH_REGION_ID_REQUIRED");
            parentRegionId = parentRegionId == null ? "" : parentRegionId;
            requireText(roleRef, "RELAY_GROWTH_ROLE_REF_REQUIRED");
            Objects.requireNonNull(growthForm, "growthForm");
            Objects.requireNonNull(start, "start");
            expansionTrace = List.copyOf(Objects.requireNonNull(expansionTrace, "expansionTrace"));
            if (targetAreaBlocks <= 0 || actualAreaBlocks <= 0
                    || expansionTrace.size() != actualAreaBlocks) {
                throw fail("RELAY_GROWTH_REGION_TRACE_AREA_INVALID:" + regionId);
            }
        }
    }

    public record ExpansionStep(int ordinal,
                                BlockPoint point,
                                BlockPoint from,
                                ProvenanceKind provenanceKind) {
        public ExpansionStep {
            if (ordinal < 0) throw fail("RELAY_GROWTH_STEP_ORDINAL_INVALID");
            Objects.requireNonNull(point, "point");
            Objects.requireNonNull(provenanceKind, "provenanceKind");
            if ((provenanceKind == ProvenanceKind.ROOT_SOURCE) != (from == null)) {
                throw fail("RELAY_GROWTH_STEP_PROVENANCE_INVALID");
            }
        }
    }

    private static void requireText(String value, String reason) {
        if (value == null || value.isBlank()) throw fail(reason);
    }

    private record CellClaim(String roleRef, String regionId) {
    }

    private record StartEdge(long point, long from, ProvenanceKind kind) {
    }

    private record FrontierEdge(long point, long from, double score) {
        private static final Comparator<FrontierEdge> ORDER = Comparator.comparingDouble(FrontierEdge::score)
                .thenComparingInt(edge -> z(edge.point()))
                .thenComparingInt(edge -> x(edge.point()))
                .thenComparingInt(edge -> z(edge.from()))
                .thenComparingInt(edge -> x(edge.from()));
    }

    private record ValueSpan<T>(int z, int minX, int maxX, T value) {
    }

    /** The growth algorithm removes cells and restores them strictly in LIFO order. */
    static final class RemainingCells extends LongOpenHashSet {
        private final GridRemovalConnectivity connectivity;
        private final LongArrayList removed = new LongArrayList();
        private final IntArrayList checkpoints = new IntArrayList();

        RemainingCells(Set<Long> source) {
            super(source);
            connectivity = GridRemovalConnectivity.create(this);
        }

        @Override public boolean remove(long point) {
            if (!super.remove(point)) return false;
            if (connectivity != null) {
                removed.add(point);
                checkpoints.add(connectivity.checkpoint());
                connectivity.remove(point);
            }
            return true;
        }

        @Override public boolean add(long point) {
            // The superclass also invokes add during construction, before initialization.
            if (connectivity == null) return super.add(point);
            if (contains(point)) return false;
            if (removed.isEmpty() || removed.getLong(removed.size() - 1) != point) {
                throw new IllegalStateException("RELAY_GROWTH_NON_LIFO_RESTORE");
            }
            connectivity.rollback(checkpoints.removeInt(checkpoints.size() - 1));
            removed.removeLong(removed.size() - 1);
            return super.add(point);
        }
    }

    private static final class RegionState {
        private final GrowthStage stage;
        private final String parentRegionId;
        private final int targetArea;
        private final long stableSeed;
        private final int stageIndex;
        private final Set<Long> cells = new LongOpenHashSet();
        private final Set<Long> frontierSources = new LongOpenHashSet();
        private final Map<Long, FrontierEdge> frontierByPoint = new Long2ObjectOpenHashMap<>();
        private final TreeSet<FrontierEdge> rankedFrontier = new TreeSet<>(FrontierEdge.ORDER);
        private final Set<Long> unsafeFrontier = new LongOpenHashSet();
        private final Map<Long, Integer> ordinals = new Long2IntOpenHashMap();
        private final List<ExpansionStep> steps = new ArrayList<>();

        private RegionState(GrowthStage stage, String parentRegionId, int targetArea, long stableSeed, int stageIndex) {
            this.stage = stage;
            this.parentRegionId = parentRegionId;
            this.targetArea = targetArea;
            this.stableSeed = stableSeed;
            this.stageIndex = stageIndex;
        }

        private GrowthStage stage() { return stage; }

        private Set<Long> cells() { return cells; }

        private Map<Long, Integer> ordinals() { return ordinals; }

        private List<ExpansionStep> steps() { return steps; }

        private int targetArea() { return targetArea; }

        private RegionTrace trace(int requestedArea) {
            return new RegionTrace(stage.regionId(), parentRegionId, stage.roleRef(), stage.growthForm(),
                    steps.get(0).point(), requestedArea, cells.size(), steps);
        }
    }
}
