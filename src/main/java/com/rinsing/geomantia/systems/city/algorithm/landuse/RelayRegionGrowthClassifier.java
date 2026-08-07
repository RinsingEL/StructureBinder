package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Fills an exact mask through sequential, locally relayed frontier growth regions.
 */
public final class RelayRegionGrowthClassifier {
    private static final double SHARE_EPSILON = 1.0e-6;
    private static final int BACKTRACK_HISTORY_LIMIT = 128;
    private static final int BACKTRACK_BUDGET = 2_048;
    private static final int[][] DIRECTIONS_4 = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}};

    public Result classify(Request request) {
        Objects.requireNonNull(request, "request");
        Set<Long> allowed = allowedCells(request.memberSpans(), request.exclusionSpans());
        if (allowed.isEmpty()) throw fail("RELAY_GROWTH_ALLOWED_MASK_REQUIRED");
        long sourceKey = key(request.source());
        if (!allowed.contains(sourceKey)) throw fail("RELAY_GROWTH_SOURCE_NOT_ALLOWED");
        if (!isConnected(allowed)) throw fail("RELAY_GROWTH_MASK_DISCONNECTED");
        if (allowed.size() < request.stages().size()) throw fail("RELAY_GROWTH_MASK_TOO_SMALL_FOR_STAGES");

        int[] targets = targetAreas(allowed.size(), request.stages());
        IllegalArgumentException lastFailure = null;
        for (int attempt = 0; attempt < 32; attempt++) {
            long attemptSeed = attempt == 0 ? request.stableSeed()
                    : request.stableSeed() ^ mix64(0x72657472794cL * attempt);
            try {
                return classifyAttempt(request, allowed, targets, attemptSeed);
            } catch (IllegalArgumentException failure) {
                if (!retryable(failure)) throw failure;
                lastFailure = failure;
            }
        }
        throw fail("RELAY_GROWTH_CANDIDATE_RETRIES_EXHAUSTED:"
                + Objects.requireNonNull(lastFailure).getMessage());
    }

    private static Result classifyAttempt(Request request,
                                          Set<Long> allowed,
                                          int[] targets,
                                          long attemptSeed) {
        Set<Long> unclaimed = new HashSet<>(allowed);
        Map<Long, CellClaim> claims = new HashMap<>(allowed.size() * 2);
        Map<String, RegionState> regions = new LinkedHashMap<>();
        List<RegionTrace> traces = new ArrayList<>();

        for (int stageIndex = 0; stageIndex < request.stages().size(); stageIndex++) {
            GrowthStage stage = request.stages().get(stageIndex);
            String parentRegionId = parentRegionId(request.stages(), stageIndex);
            boolean finalStage = stageIndex == request.stages().size() - 1;
            StartEdge start = stageIndex == 0
                    ? new StartEdge(key(request.source()), 0L, ProvenanceKind.ROOT_SOURCE)
                    : relayStart(stageIndex, stage, regions.get(parentRegionId), unclaimed,
                    attemptSeed, finalStage, targets[stageIndex]);
            RegionState region = growRegion(stageIndex, stage, parentRegionId, start, targets[stageIndex],
                    unclaimed, claims, attemptSeed, finalStage);
            regions.put(stage.regionId(), region);
            traces.add(region.trace());
        }

        if (!unclaimed.isEmpty() || claims.size() != allowed.size()) {
            throw fail("RELAY_GROWTH_INCOMPLETE_COVERAGE");
        }
        List<RoleSpan> roleSpans = compress(claims, CellClaim::roleRef).stream()
                .map(span -> new RoleSpan(span.z(), span.minX(), span.maxX(), span.value())).toList();
        List<RegionSpan> regionSpans = compress(claims, java.util.function.Function.identity()).stream()
                .map(span -> new RegionSpan(span.z(), span.minX(), span.maxX(),
                        span.value().regionId(), span.value().roleRef())).toList();
        return new Result(roleSpans, regionSpans, traces, allowed.size());
    }

    private static boolean retryable(IllegalArgumentException failure) {
        String message = failure.getMessage();
        return message != null && (message.startsWith("RELAY_GROWTH_FRONTIER_EXHAUSTED:")
                || message.startsWith("RELAY_GROWTH_PARENT_INTERFACE_EXHAUSTED:")
                || message.startsWith("RELAY_GROWTH_NO_RELAY_INTERFACE:")
                || message.startsWith("RELAY_GROWTH_REMAINDER_DISCONNECTED:")
                || message.startsWith("RELAY_GROWTH_BACKTRACK_BUDGET_EXHAUSTED:"));
    }

    private static RegionState growRegion(int stageIndex,
                                          GrowthStage stage,
                                          String parentRegionId,
                                          StartEdge start,
                                          int targetArea,
                                          Set<Long> unclaimed,
                                          Map<Long, CellClaim> claims,
                                          long stableSeed,
                                          boolean finalStage) {
        if (!unclaimed.contains(start.point())) throw fail("RELAY_GROWTH_START_ALREADY_CLAIMED:" + stage.regionId());
        if (!finalStage && articulationPoints(unclaimed).contains(start.point())) {
            throw fail("RELAY_GROWTH_START_DISCONNECTS_REMAINDER:" + stage.regionId());
        }
        RegionState state = new RegionState(stage, parentRegionId, targetArea);
        claim(state, start.point(), start.from(), start.kind(), unclaimed, claims);
        ArrayDeque<SearchFrame> history = new ArrayDeque<>();
        int backtracks = 0;
        while (true) {
            if (state.cells().size() == targetArea && stageCanRelay(
                    state, unclaimed, stableSeed, stageIndex, finalStage)) return state;

            if (state.cells().size() < targetArea) {
                SearchFrame frame = new SearchFrame(safeCandidates(
                        frontierEdges(state, unclaimed, stableSeed, stageIndex), unclaimed, finalStage));
                FrontierEdge selected = frame.next();
                if (selected != null) {
                    history.addLast(frame);
                    if (history.size() > BACKTRACK_HISTORY_LIMIT) history.removeFirst();
                    claim(state, selected.point(), selected.from(), ProvenanceKind.REGION_FRONTIER,
                            unclaimed, claims);
                    continue;
                }
            }

            FrontierEdge alternative = null;
            while (alternative == null && !history.isEmpty()) {
                if (++backtracks > BACKTRACK_BUDGET) {
                    throw fail("RELAY_GROWTH_BACKTRACK_BUDGET_EXHAUSTED:" + stage.regionId());
                }
                undoLastClaim(state, unclaimed, claims);
                SearchFrame frame = history.removeLast();
                alternative = frame.next();
                if (alternative != null) {
                    history.addLast(frame);
                }
            }
            if (alternative == null) {
                throw fail("RELAY_GROWTH_FRONTIER_EXHAUSTED:" + stage.regionId()
                        + ":actual=" + state.cells().size() + ":target=" + targetArea
                        + ":unclaimed=" + unclaimed.size());
            }
            claim(state, alternative.point(), alternative.from(), ProvenanceKind.REGION_FRONTIER,
                    unclaimed, claims);
        }
    }

    private static List<FrontierEdge> safeCandidates(List<FrontierEdge> candidates,
                                                     Set<Long> unclaimed,
                                                     boolean finalStage) {
        List<FrontierEdge> ordered = candidates.stream().sorted(FrontierEdge.ORDER).toList();
        if (finalStage) return ordered;
        Set<Long> articulationPoints = null;
        List<FrontierEdge> safe = new ArrayList<>();
        for (FrontierEdge candidate : ordered) {
            if (locallySafeRemoval(unclaimed, candidate.point())) {
                safe.add(candidate);
                continue;
            }
            if (articulationPoints == null) articulationPoints = articulationPoints(unclaimed);
            if (!articulationPoints.contains(candidate.point())) safe.add(candidate);
        }
        return List.copyOf(safe);
    }

    private static boolean stageCanRelay(RegionState state,
                                         Set<Long> unclaimed,
                                         long stableSeed,
                                         int stageIndex,
                                         boolean finalStage) {
        if (finalStage) return unclaimed.isEmpty();
        if (!isConnected(unclaimed)) return false;
        Set<Long> articulationPoints = articulationPoints(unclaimed);
        return frontierEdges(state, unclaimed, stableSeed, stageIndex).stream()
                .anyMatch(edge -> !articulationPoints.contains(edge.point())
                        && hasNeighborAfterRemoval(unclaimed, edge.point()));
    }

    private static void undoLastClaim(RegionState state,
                                      Set<Long> unclaimed,
                                      Map<Long, CellClaim> claims) {
        ExpansionStep removed = state.steps().remove(state.steps().size() - 1);
        long point = key(removed.point());
        state.cells().remove(point);
        state.ordinals().remove(point);
        claims.remove(point);
        unclaimed.add(point);
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
    }

    private static StartEdge relayStart(int stageIndex,
                                        GrowthStage stage,
                                        RegionState parent,
                                        Set<Long> unclaimed,
                                        long stableSeed,
                                        boolean finalStage,
                                        int targetArea) {
        if (parent == null) throw fail("RELAY_GROWTH_PARENT_REGION_UNKNOWN:" + stage.regionId());
        Set<Long> articulationPoints = finalStage ? Set.of() : articulationPoints(unclaimed);
        return parent.cells().stream().flatMap(from -> neighbors4(from).stream()
                        .filter(unclaimed::contains)
                        .filter(candidate -> finalStage || !articulationPoints.contains(candidate))
                        .filter(candidate -> targetArea <= 1 || hasNeighborAfterRemoval(unclaimed, candidate))
                        .filter(candidate -> targetArea <= 1 || canStartRegion(candidate, unclaimed))
                        .map(candidate -> new StartEdge(candidate, from, ProvenanceKind.RELAY_INTERFACE)))
                .min(Comparator.comparingDouble((StartEdge edge) -> stableUnit(stableSeed, stageIndex,
                                edge.point(), edge.from(), 0x72656c61794cL))
                        .thenComparingInt(edge -> z(edge.point()))
                        .thenComparingInt(edge -> x(edge.point()))
                        .thenComparingInt(edge -> z(edge.from()))
                        .thenComparingInt(edge -> x(edge.from())))
                .orElseThrow(() -> fail("RELAY_GROWTH_PARENT_INTERFACE_EXHAUSTED:" + stage.regionId()));
    }

    private static boolean canStartRegion(long start, Set<Long> unclaimed) {
        Set<Long> remaining = new HashSet<>(unclaimed);
        remaining.remove(start);
        if (remaining.isEmpty()) return false;
        Set<Long> articulationPoints = articulationPoints(remaining);
        return neighbors4(start).stream().anyMatch(candidate ->
                remaining.contains(candidate) && !articulationPoints.contains(candidate));
    }

    private static List<FrontierEdge> frontierEdges(RegionState state,
                                                    Set<Long> unclaimed,
                                                    long stableSeed,
                                                    int stageIndex) {
        Map<Long, FrontierEdge> bestByPoint = new HashMap<>();
        int newestOrdinal = state.steps().size() - 1;
        for (long from : state.cells()) {
            for (long candidate : neighbors4(from)) {
                if (!unclaimed.contains(candidate)) continue;
                int sameRegionNeighbors = sameRegionNeighborCount(candidate, state.cells());
                int remainingNeighbors = sameRegionNeighborCount(candidate, unclaimed);
                double score = frontierScore(state, candidate, from, sameRegionNeighbors,
                        remainingNeighbors, newestOrdinal, stableSeed, stageIndex);
                FrontierEdge edge = new FrontierEdge(candidate, from, score);
                bestByPoint.merge(candidate, edge,
                        (first, second) -> FrontierEdge.ORDER.compare(first, second) <= 0 ? first : second);
            }
        }
        return List.copyOf(bestByPoint.values());
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
        double lengthVariation = 0.85 + stableUnit(stableSeed, stageIndex, 0L, 0L,
                0x7370696e654cL) * 0.3;
        int spineTarget = Math.min(state.targetArea(), Math.max(2,
                (int) Math.round(Math.sqrt(state.targetArea()) * 3.2 * lengthVariation)));
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

    private static int[] targetAreas(int blockCount, List<GrowthStage> stages) {
        int[] targets = new int[stages.size()];
        double[] exact = new double[stages.size()];
        int assigned = 0;
        for (int index = 0; index < stages.size(); index++) {
            exact[index] = stages.get(index).targetShare() * blockCount;
            targets[index] = Math.max(1, (int) Math.floor(exact[index]));
            assigned += targets[index];
        }
        while (assigned < blockCount) {
            int selected = 0;
            for (int index = 1; index < targets.length; index++) {
                double deficit = exact[index] - targets[index];
                double selectedDeficit = exact[selected] - targets[selected];
                if (deficit > selectedDeficit + 1.0e-12) selected = index;
            }
            targets[selected]++;
            assigned++;
        }
        while (assigned > blockCount) {
            int selected = -1;
            for (int index = 0; index < targets.length; index++) {
                if (targets[index] <= 1) continue;
                if (selected < 0 || targets[index] - exact[index] > targets[selected] - exact[selected] + 1.0e-12) {
                    selected = index;
                }
            }
            if (selected < 0) throw fail("RELAY_GROWTH_TARGET_ALLOCATION_FAILED");
            targets[selected]--;
            assigned--;
        }
        return targets;
    }

    private static String parentRegionId(List<GrowthStage> stages, int stageIndex) {
        GrowthStage stage = stages.get(stageIndex);
        if (stageIndex == 0) return "";
        return stage.parentRegionId().isBlank() ? stages.get(stageIndex - 1).regionId() : stage.parentRegionId();
    }

    private static Set<Long> allowedCells(List<LandUseAreaPlan.ScanlineSpan> members,
                                          List<LandUseAreaPlan.ScanlineSpan> exclusions) {
        Set<Long> allowed = new HashSet<>();
        addSpans(allowed, members);
        Set<Long> excluded = new HashSet<>();
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
        Set<Long> visited = new HashSet<>();
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

    private static Set<Long> articulationPoints(Set<Long> cells) {
        if (cells.size() <= 2) return Set.of();
        Map<Long, Integer> discovered = new HashMap<>();
        Map<Long, Integer> low = new HashMap<>();
        Set<Long> result = new HashSet<>();
        int[] time = {0};
        for (long cell : cells) {
            if (!discovered.containsKey(cell)) {
                articulationDfs(cell, null, cells, discovered, low, result, time);
            }
        }
        return result;
    }

    private static boolean locallySafeRemoval(Set<Long> cells, long removed) {
        List<Long> attachments = neighbors4(removed).stream().filter(cells::contains).toList();
        if (attachments.size() <= 1) return true;
        int centerX = x(removed);
        int centerZ = z(removed);
        Set<Long> local = new HashSet<>();
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
        Set<Long> visited = new HashSet<>();
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

    private static void articulationDfs(long cell,
                                        Long parent,
                                        Set<Long> cells,
                                        Map<Long, Integer> discovered,
                                        Map<Long, Integer> low,
                                        Set<Long> result,
                                        int[] time) {
        int discovery = ++time[0];
        discovered.put(cell, discovery);
        low.put(cell, discovery);
        int children = 0;
        for (long neighbor : neighbors4(cell)) {
            if (!cells.contains(neighbor)) continue;
            if (!discovered.containsKey(neighbor)) {
                children++;
                articulationDfs(neighbor, cell, cells, discovered, low, result, time);
                low.put(cell, Math.min(low.get(cell), low.get(neighbor)));
                if (parent == null && children > 1
                        || parent != null && low.get(neighbor) >= discovery) {
                    result.add(cell);
                }
            } else if (parent == null || neighbor != parent) {
                low.put(cell, Math.min(low.get(cell), discovered.get(neighbor)));
            }
        }
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
            if (roleSpans.isEmpty() || regionSpans.isEmpty() || regions.isEmpty() || coveredBlockCount <= 0) {
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
            if (targetAreaBlocks <= 0 || actualAreaBlocks != targetAreaBlocks
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

    private static final class SearchFrame {
        private final List<FrontierEdge> candidates;
        private int nextIndex;

        private SearchFrame(List<FrontierEdge> candidates) {
            this.candidates = candidates;
        }

        private FrontierEdge next() {
            return nextIndex < candidates.size() ? candidates.get(nextIndex++) : null;
        }
    }

    private static final class RegionState {
        private final GrowthStage stage;
        private final String parentRegionId;
        private final int targetArea;
        private final Set<Long> cells = new HashSet<>();
        private final Map<Long, Integer> ordinals = new HashMap<>();
        private final List<ExpansionStep> steps = new ArrayList<>();

        private RegionState(GrowthStage stage, String parentRegionId, int targetArea) {
            this.stage = stage;
            this.parentRegionId = parentRegionId;
            this.targetArea = targetArea;
        }

        private GrowthStage stage() { return stage; }

        private Set<Long> cells() { return cells; }

        private Map<Long, Integer> ordinals() { return ordinals; }

        private List<ExpansionStep> steps() { return steps; }

        private int targetArea() { return targetArea; }

        private RegionTrace trace() {
            return new RegionTrace(stage.regionId(), parentRegionId, stage.roleRef(), stage.growthForm(),
                    steps.get(0).point(), targetArea, cells.size(), steps);
        }
    }
}
