package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgramPlan;
import com.rinsing.geomantia.systems.city.application.dressing.DecorationDeterminism;
import com.rinsing.geomantia.systems.city.application.dressing.DecorationSlot;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Finds reviewable anchors for a single required prefab without reading or mutating world chunks. */
public final class CityDecorationAnchorCandidatePlanner {
    public static final String CANDIDATE_SET_SCHEMA = "city_decoration_anchor_candidate_set.v0.1";
    public static final String QUALITY_SCHEMA = "city_decoration_anchor_candidate_quality.v0.1";
    private static final int MAX_INTERIOR_CLEARANCE_SCORE_BLOCKS = 8;
    private static final int MAX_OBSTACLE_CLEARANCE_SCORE_BLOCKS = 16;

    public Result plan(CompiledDecorationProgramPlan plan,
                       String programId,
                       CityDecorationContentCatalog catalog,
                       int candidateCount) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(catalog, "catalog");
        if (programId == null || programId.isBlank()) {
            throw new IllegalArgumentException("CITY_DECORATION_ANCHOR_CANDIDATE_PROGRAM_ID_REQUIRED");
        }
        if (candidateCount < 1 || candidateCount > 8) {
            throw new IllegalArgumentException("CITY_DECORATION_ANCHOR_CANDIDATE_COUNT_INVALID: " + candidateCount);
        }
        if (!plan.catalogHash().equals(catalog.catalogHash())) {
            throw new IllegalArgumentException("CITY_DECORATION_CATALOG_HASH_MISMATCH: plan="
                    + plan.catalogHash() + ", catalog=" + catalog.catalogHash());
        }

        CompiledDecorationProgram program = plan.programs().stream()
                .filter(value -> value.programId().equals(programId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "CITY_DECORATION_ANCHOR_CANDIDATE_PROGRAM_UNKNOWN: " + programId));
        validateSinglePointProgram(program);
        CompiledDecorationProgram.GridRepeatPattern pattern =
                (CompiledDecorationProgram.GridRepeatPattern) program.pattern();
        CompiledDecorationProgram.PaletteSlot paletteSlot =
                program.contentPalette().requireSlot(pattern.paletteSlotId());

        TargetMaskIndex target = new TargetMaskIndex(program.targetMask());
        List<FixedDecoration> fixedDecorations = fixedDecorations(plan, program, catalog);
        Rejections rejections = new Rejections();
        List<ScoredCandidate> legal = new ArrayList<>();
        long scannedAnchorCount = 0L;
        for (Map.Entry<Integer, List<Span>> row : target.rows().entrySet()) {
            int z = row.getKey();
            for (Span span : row.getValue()) {
                for (int x = span.min(); x <= span.max(); x++) {
                    scannedAnchorCount++;
                    DecorationSlot slot = candidateSlot(program, paletteSlot.slotId(), x, z);
                    CompiledDecorationProgram.ContentEntry selected =
                            CityDecorationChunkCompiler.selectContent(program, slot, paletteSlot);
                    CityDecorationContentCatalog.Content content = catalog.requireContent(selected.contentRef());
                    if (content.plant()) {
                        rejections.contentNotPrefab++;
                        continue;
                    }
                    if (!content.allowedRotations().contains(0)) {
                        rejections.rotationUnsupported++;
                        continue;
                    }

                    BlockBounds footprint = CityDecorationChunkCompiler.rotatedFootprint(x, z,
                            content.size().widthBlocks(), content.size().depthBlocks(), 0);
                    if (!contains(chunkBounds(Math.floorDiv(x, 16), Math.floorDiv(z, 16)), footprint)) {
                        rejections.crossChunk++;
                        continue;
                    }
                    int margin = Math.max(content.comfortMarginBlocks(),
                            program.conflictPolicy().clearanceBlocks());
                    BlockBounds clearance = expand(footprint, margin);
                    if (overlapsAny(clearance, plan.hardObstacles())) {
                        rejections.hardObstacle++;
                        continue;
                    }
                    if (fixedDecorations.stream().anyMatch(fixed -> clearance.overlaps(fixed.clearance()))) {
                        rejections.decorationConflict++;
                        continue;
                    }
                    if (!target.contains(clearance)) {
                        rejections.outsideTarget++;
                        continue;
                    }

                    Score score = score(target, footprint, clearance, plan.hardObstacles(), fixedDecorations);
                    CompiledDecorationProgram.LocalPoint delta = program.coordinateFrame().toLocal(x, z);
                    legal.add(new ScoredCandidate(
                            DecorationDeterminism.slotId(program.programId(), "anchor_candidate", x, z, 0),
                            content.contentId(), paletteSlot.slotId(), x, z, delta.u(), delta.v(),
                            footprint, clearance, margin, score));
                }
            }
        }

        legal.sort(Comparator.comparingDouble((ScoredCandidate candidate) -> candidate.score().total()).reversed()
                .thenComparingInt(ScoredCandidate::worldX)
                .thenComparingInt(ScoredCandidate::worldZ)
                .thenComparing(ScoredCandidate::contentRef));
        List<ScoredCandidate> selected = diversify(legal, candidateCount);
        JsonObject rejectionJson = rejections.toJson();
        JsonObject candidateSet = candidateSet(plan, program, candidateCount, scannedAnchorCount,
                legal.size(), selected, fixedDecorations, rejectionJson);
        JsonObject qualityReport = qualityReport(plan, program, candidateCount, scannedAnchorCount,
                legal.size(), selected.size(), rejectionJson);
        return new Result(candidateSet, qualityReport);
    }

    private static void validateSinglePointProgram(CompiledDecorationProgram program) {
        if (!(program.shape() instanceof CompiledDecorationProgram.RectangleShape rectangle)
                || rectangle.minU() != 0 || rectangle.minV() != 0
                || rectangle.maxU() != 0 || rectangle.maxV() != 0) {
            throw new IllegalArgumentException("CITY_DECORATION_ANCHOR_CANDIDATE_POINT_SHAPE_REQUIRED: "
                    + program.programId());
        }
        if (!(program.pattern() instanceof CompiledDecorationProgram.GridRepeatPattern pattern)) {
            throw new IllegalArgumentException("CITY_DECORATION_ANCHOR_CANDIDATE_GRID_PATTERN_REQUIRED: "
                    + program.programId());
        }
        if (Math.floorMod(pattern.offsetUBlocks(), pattern.spacingUBlocks()) != 0
                || Math.floorMod(pattern.offsetVBlocks(), pattern.spacingVBlocks()) != 0) {
            throw new IllegalArgumentException("CITY_DECORATION_ANCHOR_CANDIDATE_GRID_POINT_INACTIVE: "
                    + program.programId());
        }
    }

    private static DecorationSlot candidateSlot(CompiledDecorationProgram program, String paletteSlotId,
                                                int worldX, int worldZ) {
        return new DecorationSlot(DecorationDeterminism.slotId(program.programId(), program.pattern().type(), 0, 0),
                program.programId(), paletteSlotId, new BlockPoint(worldX, worldZ),
                new CompiledDecorationProgram.LocalPoint(0, 0), 0);
    }

    private static List<FixedDecoration> fixedDecorations(CompiledDecorationProgramPlan plan,
                                                           CompiledDecorationProgram requestedProgram,
                                                           CityDecorationContentCatalog catalog) {
        List<FixedDecoration> fixed = new ArrayList<>();
        for (CompiledDecorationProgram program : plan.programs()) {
            if (program.programId().equals(requestedProgram.programId())
                    || !isActiveSinglePointGrid(program)) {
                continue;
            }
            CompiledDecorationProgram.GridRepeatPattern pattern =
                    (CompiledDecorationProgram.GridRepeatPattern) program.pattern();
            CompiledDecorationProgram.PaletteSlot slot = program.contentPalette().requireSlot(pattern.paletteSlotId());
            int x = program.coordinateFrame().origin().x();
            int z = program.coordinateFrame().origin().z();
            DecorationSlot decorationSlot = candidateSlot(program, slot.slotId(), x, z);
            CityDecorationContentCatalog.Content content = catalog.requireContent(
                    CityDecorationChunkCompiler.selectContent(program, decorationSlot, slot).contentRef());
            if (content.plant() || !content.allowedRotations().contains(0)) {
                continue;
            }
            BlockBounds footprint = CityDecorationChunkCompiler.rotatedFootprint(x, z,
                    content.size().widthBlocks(), content.size().depthBlocks(), 0);
            if (!contains(chunkBounds(Math.floorDiv(x, 16), Math.floorDiv(z, 16)), footprint)) {
                continue;
            }
            int margin = Math.max(content.comfortMarginBlocks(),
                    program.conflictPolicy().clearanceBlocks());
            BlockBounds clearance = expand(footprint, margin);
            TargetMaskIndex target = new TargetMaskIndex(program.targetMask());
            if (!target.contains(clearance) || overlapsAny(clearance, plan.hardObstacles())) {
                continue;
            }
            fixed.add(new FixedDecoration(program.programId(), content.contentId(), footprint, clearance));
        }
        fixed.sort(Comparator.comparing(FixedDecoration::programId));
        return List.copyOf(fixed);
    }

    private static boolean isActiveSinglePointGrid(CompiledDecorationProgram program) {
        if (!(program.shape() instanceof CompiledDecorationProgram.RectangleShape rectangle)
                || rectangle.minU() != 0 || rectangle.minV() != 0
                || rectangle.maxU() != 0 || rectangle.maxV() != 0
                || !(program.pattern() instanceof CompiledDecorationProgram.GridRepeatPattern pattern)) {
            return false;
        }
        return Math.floorMod(pattern.offsetUBlocks(), pattern.spacingUBlocks()) == 0
                && Math.floorMod(pattern.offsetVBlocks(), pattern.spacingVBlocks()) == 0;
    }

    private static Score score(TargetMaskIndex target,
                               BlockBounds footprint,
                               BlockBounds clearance,
                               List<CompiledDecorationProgramPlan.HardObstacle> obstacles,
                               List<FixedDecoration> fixedDecorations) {
        double centerX = (footprint.minX() + footprint.maxX()) / 2.0D;
        double centerZ = (footprint.minZ() + footprint.maxZ()) / 2.0D;
        double distance = Math.hypot(centerX - target.centroidX(), centerZ - target.centroidZ());
        double centrality = clamp01(1.0D - distance / target.maxCentroidDistance());

        int interiorBlocks = 0;
        for (int amount = 1; amount <= MAX_INTERIOR_CLEARANCE_SCORE_BLOCKS; amount++) {
            if (!target.contains(expand(clearance, amount))) {
                break;
            }
            interiorBlocks = amount;
        }
        double interior = interiorBlocks / (double) MAX_INTERIOR_CLEARANCE_SCORE_BLOCKS;

        double obstacleDistance = obstacles.stream()
                .mapToDouble(obstacle -> distance(clearance, obstacle.blockBounds()))
                .min().orElse(MAX_OBSTACLE_CLEARANCE_SCORE_BLOCKS);
        obstacleDistance = Math.min(obstacleDistance, fixedDecorations.stream()
                .mapToDouble(fixed -> distance(clearance, fixed.clearance()))
                .min().orElse(MAX_OBSTACLE_CLEARANCE_SCORE_BLOCKS));
        double obstacle = Math.min(obstacleDistance, MAX_OBSTACLE_CLEARANCE_SCORE_BLOCKS)
                / MAX_OBSTACLE_CLEARANCE_SCORE_BLOCKS;
        double total = centrality * 0.50D + interior * 0.30D + obstacle * 0.20D;
        return new Score(centrality, interior, obstacle, total, distance, interiorBlocks, obstacleDistance);
    }

    private static List<ScoredCandidate> diversify(List<ScoredCandidate> ranked, int candidateCount) {
        List<ScoredCandidate> selected = new ArrayList<>();
        for (ScoredCandidate candidate : ranked) {
            if (selected.size() >= candidateCount) {
                break;
            }
            boolean separated = selected.stream().allMatch(existing -> sufficientlySeparated(candidate, existing));
            if (separated) {
                selected.add(candidate);
            }
        }
        if (selected.size() < candidateCount) {
            for (ScoredCandidate candidate : ranked) {
                if (selected.size() >= candidateCount) {
                    break;
                }
                if (!selected.contains(candidate)) {
                    selected.add(candidate);
                }
            }
        }
        return List.copyOf(selected);
    }

    private static boolean sufficientlySeparated(ScoredCandidate first, ScoredCandidate second) {
        int firstDiameter = Math.max(first.clearance().widthBlocks(), first.clearance().heightBlocks());
        int secondDiameter = Math.max(second.clearance().widthBlocks(), second.clearance().heightBlocks());
        int minimum = Math.max(4, Math.max(firstDiameter, secondDiameter) + 1);
        long dx = (long) first.worldX() - second.worldX();
        long dz = (long) first.worldZ() - second.worldZ();
        return dx * dx + dz * dz >= (long) minimum * minimum;
    }

    private static JsonObject candidateSet(CompiledDecorationProgramPlan plan,
                                           CompiledDecorationProgram program,
                                           int requestedCount,
                                           long scannedCount,
                                           int legalCount,
                                           List<ScoredCandidate> candidates,
                                           List<FixedDecoration> fixedDecorations,
                                           JsonObject rejectionCounts) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", CANDIDATE_SET_SCHEMA);
        root.addProperty("cityId", plan.cityId());
        root.addProperty("catalogHash", plan.catalogHash());
        root.addProperty("programId", program.programId());
        root.addProperty("targetMaskId", program.targetMask().maskId());
        root.addProperty("requestedCandidateCount", requestedCount);
        root.addProperty("candidateCount", candidates.size());
        root.addProperty("scannedAnchorCount", scannedCount);
        root.addProperty("legalAnchorCount", legalCount);
        root.addProperty("terrainSampling", "not_performed");
        root.addProperty("runtimeTerrainPreflightRequired", true);
        root.addProperty("rotationQuarterTurns", 0);
        root.add("rejectionCounts", rejectionCounts.deepCopy());
        JsonArray fixedValues = new JsonArray();
        for (FixedDecoration fixed : fixedDecorations) {
            JsonObject value = new JsonObject();
            value.addProperty("programId", fixed.programId());
            value.addProperty("contentRef", fixed.contentRef());
            value.add("footprintBounds", boundsJson(fixed.footprint()));
            value.add("clearanceBounds", boundsJson(fixed.clearance()));
            fixedValues.add(value);
        }
        root.add("fixedDecorationObstacles", fixedValues);
        JsonArray values = new JsonArray();
        for (int index = 0; index < candidates.size(); index++) {
            values.add(candidateJson(candidates.get(index), index + 1));
        }
        root.add("candidates", values);
        return root;
    }

    private static JsonObject candidateJson(ScoredCandidate candidate, int rank) {
        JsonObject value = new JsonObject();
        value.addProperty("rank", rank);
        value.addProperty("candidateId", candidate.candidateId());
        value.addProperty("contentRef", candidate.contentRef());
        value.addProperty("paletteSlotId", candidate.paletteSlotId());
        value.add("worldAnchor", pointJson(candidate.worldX(), candidate.worldZ()));
        JsonObject delta = new JsonObject();
        delta.addProperty("u", candidate.localU());
        delta.addProperty("v", candidate.localV());
        value.add("localOffsetDelta", delta);
        JsonObject patch = new JsonObject();
        patch.addProperty("offsetUDeltaBlocks", candidate.localU());
        patch.addProperty("offsetVDeltaBlocks", candidate.localV());
        value.add("coordinateFramePatch", patch);
        value.addProperty("rotationQuarterTurns", 0);
        value.addProperty("marginBlocks", candidate.margin());
        value.add("footprintBounds", boundsJson(candidate.footprint()));
        value.add("clearanceBounds", boundsJson(candidate.clearance()));
        JsonObject chunk = new JsonObject();
        chunk.addProperty("x", Math.floorDiv(candidate.worldX(), 16));
        chunk.addProperty("z", Math.floorDiv(candidate.worldZ(), 16));
        value.add("anchorChunk", chunk);
        value.add("scoreBreakdown", candidate.score().toJson());
        return value;
    }

    private static JsonObject qualityReport(CompiledDecorationProgramPlan plan,
                                            CompiledDecorationProgram program,
                                            int requestedCount,
                                            long scannedCount,
                                            int legalCount,
                                            int selectedCount,
                                            JsonObject rejectionCounts) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", QUALITY_SCHEMA);
        root.addProperty("cityId", plan.cityId());
        root.addProperty("programId", program.programId());
        root.addProperty("passed", selectedCount > 0);
        root.addProperty("terrainSampling", "not_performed");
        root.addProperty("runtimeTerrainPreflightRequired", true);
        JsonObject metrics = new JsonObject();
        metrics.addProperty("requestedCandidateCount", requestedCount);
        metrics.addProperty("selectedCandidateCount", selectedCount);
        metrics.addProperty("legalAnchorCount", legalCount);
        metrics.addProperty("scannedAnchorCount", scannedCount);
        root.add("metrics", metrics);
        root.add("rejectionCounts", rejectionCounts.deepCopy());
        JsonArray warnings = new JsonArray();
        if (selectedCount < requestedCount) {
            warnings.add("CITY_DECORATION_ANCHOR_CANDIDATE_SHORTFALL");
        }
        warnings.add("CITY_DECORATION_ANCHOR_CANDIDATE_TERRAIN_NOT_SAMPLED");
        root.add("warnings", warnings);
        return root;
    }

    private static JsonObject pointJson(int x, int z) {
        JsonObject value = new JsonObject();
        value.addProperty("x", x);
        value.addProperty("z", z);
        return value;
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject value = new JsonObject();
        value.addProperty("minX", bounds.minX());
        value.addProperty("minZ", bounds.minZ());
        value.addProperty("maxX", bounds.maxX());
        value.addProperty("maxZ", bounds.maxZ());
        return value;
    }

    private static boolean overlapsAny(BlockBounds bounds,
                                       List<CompiledDecorationProgramPlan.HardObstacle> obstacles) {
        return obstacles.stream().anyMatch(obstacle -> bounds.overlaps(obstacle.blockBounds()));
    }

    private static double distance(BlockBounds first, BlockBounds second) {
        int dx = first.maxX() < second.minX() ? second.minX() - first.maxX() - 1
                : second.maxX() < first.minX() ? first.minX() - second.maxX() - 1 : 0;
        int dz = first.maxZ() < second.minZ() ? second.minZ() - first.maxZ() - 1
                : second.maxZ() < first.minZ() ? first.minZ() - second.maxZ() - 1 : 0;
        return Math.hypot(dx, dz);
    }

    private static BlockBounds chunkBounds(int chunkX, int chunkZ) {
        return new BlockBounds(chunkX * 16, chunkZ * 16, chunkX * 16 + 15, chunkZ * 16 + 15);
    }

    private static BlockBounds expand(BlockBounds bounds, int amount) {
        return new BlockBounds(bounds.minX() - amount, bounds.minZ() - amount,
                bounds.maxX() + amount, bounds.maxZ() + amount);
    }

    private static boolean contains(BlockBounds outer, BlockBounds inner) {
        return outer.contains(inner.minX(), inner.minZ()) && outer.contains(inner.maxX(), inner.maxZ());
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public record Result(JsonObject candidateSet, JsonObject qualityReport) {
        public Result {
            Objects.requireNonNull(candidateSet, "candidateSet");
            Objects.requireNonNull(qualityReport, "qualityReport");
        }
    }

    private record ScoredCandidate(String candidateId, String contentRef, String paletteSlotId,
                                   int worldX, int worldZ, int localU, int localV,
                                   BlockBounds footprint, BlockBounds clearance, int margin, Score score) {
    }

    private record Score(double centrality, double interiorClearance, double obstacleClearance, double total,
                         double distanceFromMaskCentroidBlocks, int extraInteriorClearanceBlocks,
                         double nearestObstacleDistanceBlocks) {
        JsonObject toJson() {
            JsonObject value = new JsonObject();
            value.addProperty("centrality", centrality);
            value.addProperty("interiorClearance", interiorClearance);
            value.addProperty("obstacleClearance", obstacleClearance);
            value.addProperty("total", total);
            value.addProperty("distanceFromMaskCentroidBlocks", distanceFromMaskCentroidBlocks);
            value.addProperty("extraInteriorClearanceBlocks", extraInteriorClearanceBlocks);
            value.addProperty("nearestObstacleDistanceBlocks", nearestObstacleDistanceBlocks);
            return value;
        }
    }

    private static final class Rejections {
        private long outsideTarget;
        private long hardObstacle;
        private long crossChunk;
        private long rotationUnsupported;
        private long contentNotPrefab;
        private long decorationConflict;

        JsonObject toJson() {
            JsonObject value = new JsonObject();
            value.addProperty("outsideTarget", outsideTarget);
            value.addProperty("hardObstacle", hardObstacle);
            value.addProperty("crossChunk", crossChunk);
            value.addProperty("rotationUnsupported", rotationUnsupported);
            value.addProperty("contentNotPrefab", contentNotPrefab);
            value.addProperty("decorationConflict", decorationConflict);
            return value;
        }
    }

    private record FixedDecoration(String programId, String contentRef,
                                   BlockBounds footprint, BlockBounds clearance) {
    }

    private record Span(int min, int max) {
    }

    private static final class TargetMaskIndex {
        private final Map<Integer, List<Span>> rows;
        private final double centroidX;
        private final double centroidZ;
        private final double maxCentroidDistance;

        TargetMaskIndex(CompiledDecorationProgram.TargetMask mask) {
            Map<Integer, List<Span>> raw = new TreeMap<>();
            for (BlockBounds member : mask.memberBounds()) {
                for (int z = member.minZ(); z <= member.maxZ(); z++) {
                    raw.computeIfAbsent(z, ignored -> new ArrayList<>())
                            .add(new Span(member.minX(), member.maxX()));
                }
            }
            Map<Integer, List<Span>> mergedRows = new TreeMap<>();
            double sumX = 0.0D;
            double sumZ = 0.0D;
            long count = 0L;
            for (Map.Entry<Integer, List<Span>> entry : raw.entrySet()) {
                List<Span> sorted = new ArrayList<>(entry.getValue());
                sorted.sort(Comparator.comparingInt(Span::min).thenComparingInt(Span::max));
                List<Span> merged = new ArrayList<>();
                for (Span span : sorted) {
                    if (merged.isEmpty() || (long) span.min() > (long) merged.get(merged.size() - 1).max() + 1L) {
                        merged.add(span);
                    } else {
                        Span previous = merged.remove(merged.size() - 1);
                        merged.add(new Span(previous.min(), Math.max(previous.max(), span.max())));
                    }
                }
                mergedRows.put(entry.getKey(), List.copyOf(merged));
                for (Span span : merged) {
                    long width = (long) span.max() - span.min() + 1L;
                    sumX += ((double) span.min() + span.max()) * width / 2.0D;
                    sumZ += (double) entry.getKey() * width;
                    count += width;
                }
            }
            this.rows = Map.copyOf(mergedRows);
            this.centroidX = sumX / count;
            this.centroidZ = sumZ / count;
            BlockBounds bounds = mask.bounds();
            this.maxCentroidDistance = Math.max(1.0D, Math.max(
                    Math.hypot(bounds.minX() - centroidX, bounds.minZ() - centroidZ),
                    Math.max(Math.hypot(bounds.minX() - centroidX, bounds.maxZ() - centroidZ),
                            Math.max(Math.hypot(bounds.maxX() - centroidX, bounds.minZ() - centroidZ),
                                    Math.hypot(bounds.maxX() - centroidX, bounds.maxZ() - centroidZ)))));
        }

        Map<Integer, List<Span>> rows() {
            return rows;
        }

        double centroidX() {
            return centroidX;
        }

        double centroidZ() {
            return centroidZ;
        }

        double maxCentroidDistance() {
            return maxCentroidDistance;
        }

        boolean contains(BlockBounds bounds) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                List<Span> spans = rows.get(z);
                if (spans == null || spans.stream().noneMatch(span ->
                        span.min() <= bounds.minX() && span.max() >= bounds.maxX())) {
                    return false;
                }
            }
            return true;
        }
    }
}
