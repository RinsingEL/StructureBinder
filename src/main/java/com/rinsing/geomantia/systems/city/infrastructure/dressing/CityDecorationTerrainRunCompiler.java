package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import com.rinsing.geomantia.systems.city.application.dressing.CityDecorationProgramPlanner;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgramPlan;
import com.rinsing.geomantia.systems.city.application.dressing.DecorationSlot;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Compiles complete continuous-pattern runs before activation; it never groups by owner chunk. */
public final class CityDecorationTerrainRunCompiler {
    public static final String SCHEMA = "city_decoration_frozen_terrain_runs.v0.2";

    private final CityDecorationProgramPlanner planner = new CityDecorationProgramPlanner();

    public FrozenPlan compile(CompiledDecorationProgramPlan plan,
                              CityDecorationContentCatalog catalog,
                              TerrainView terrain) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(terrain, "terrain");
        if (!plan.catalogHash().equals(catalog.catalogHash())) {
            throw new IllegalArgumentException("CITY_DECORATION_CATALOG_HASH_MISMATCH");
        }
        List<Run> runs = new ArrayList<>();
        for (CompiledDecorationProgram program : plan.programsInExecutionOrder()) {
            Continuation continuation = continuation(program);
            if (continuation == null) {
                if (program.terrainPolicy().foundationMode() == CompiledDecorationProgram.FoundationMode.FILL_ONLY) {
                    throw new IllegalArgumentException("CITY_DECORATION_FOUNDATION_PATTERN_UNSUPPORTED");
                }
                continue;
            }
            List<DecorationSlot> slots = planner.project(program, program.targetMask().bounds());
            runs.addAll(compileRuns(program, catalog, terrain, continuation, slots));
        }
        runs.sort(Comparator.comparing(Run::runId));
        List<FoundationSegment> segments = runs.stream().flatMap(run -> run.foundationSegments().stream()).toList();
        return new FrozenPlan(SCHEMA, plan.cityId(), plan.catalogHash(), List.copyOf(runs), segments);
    }

    private List<Run> compileRuns(CompiledDecorationProgram program,
                                  CityDecorationContentCatalog catalog,
                                  TerrainView terrain,
                                  Continuation continuation,
                                  List<DecorationSlot> slots) {
        Map<LineKey, List<DecorationSlot>> lines = new LinkedHashMap<>();
        for (DecorationSlot slot : slots) {
            int cross = continuation.cross(slot.localAnchor());
            lines.computeIfAbsent(new LineKey(slot.paletteSlotId(), cross), ignored -> new ArrayList<>()).add(slot);
        }
        List<Run> result = new ArrayList<>();
        for (Map.Entry<LineKey, List<DecorationSlot>> entry : lines.entrySet()) {
            List<DecorationSlot> line = entry.getValue();
            line.sort(Comparator.comparingInt(slot -> continuation.along(slot.localAnchor())));
            int start = 0;
            for (int index = 1; index <= line.size(); index++) {
                boolean split = index == line.size()
                        || continuation.along(line.get(index).localAnchor())
                        != continuation.along(line.get(index - 1).localAnchor()) + 1;
                if (split) {
                    result.add(compileRun(program, catalog, terrain, continuation, entry.getKey(),
                            line.subList(start, index)));
                    start = index;
                }
            }
        }
        return result;
    }

    private Run compileRun(CompiledDecorationProgram program,
                           CityDecorationContentCatalog catalog,
                           TerrainView terrain,
                           Continuation continuation,
                           LineKey line,
                           List<DecorationSlot> slots) {
        String runId = runId(program, continuation, line, slots);
        List<MutableOutcome> outcomes = new ArrayList<>();
        for (int ordinal = 0; ordinal < slots.size(); ordinal++) {
            DecorationSlot slot = slots.get(ordinal);
            TerrainSample sample = Objects.requireNonNull(terrain.sample(slot.worldAnchor().x(), slot.worldAnchor().z()),
                    "CITY_DECORATION_TERRAIN_SAMPLE_MISSING");
            CompiledDecorationProgram.PaletteSlot paletteSlot = program.contentPalette().requireSlot(slot.paletteSlotId());
            List<LayerSelection> layers = paletteSlot.layers().stream().map(layer -> new LayerSelection(
                    layer.layerId(), CityDecorationChunkCompiler.selectContent(program, slot, paletteSlot, layer)
                    .contentRef(), CityDecorationChunkCompiler.selectContent(program, slot, paletteSlot, layer)
                    .contentRef(), layer.required())).toList();
            outcomes.add(new MutableOutcome(slot, ordinal, sample, layers));
        }

        int terminationOrdinal = -1;
        String terminationReason = "";
        for (int index = 0; index < outcomes.size(); index++) {
            MutableOutcome current = outcomes.get(index);
            if (!current.sample.available()) {
                terminationOrdinal = index;
                terminationReason = "CITY_DECORATION_RUN_TERRAIN_UNAVAILABLE";
                break;
            }
            if (current.sample.water() && !program.terrainPolicy().allowWater()) {
                terminationOrdinal = index;
                terminationReason = "CITY_DECORATION_RUN_WATER_TERMINATED";
                break;
            }
            if (index > 0 && Math.abs(current.sample.surfaceY() - outcomes.get(index - 1).sample.surfaceY())
                    > program.terrainPolicy().maxSlopeDelta()) {
                terminationOrdinal = index;
                terminationReason = "CITY_DECORATION_RUN_LOCAL_CLIFF_TERMINATED";
                break;
            }
            int windowStart = Math.max(0, index - program.terrainPolicy().continuousDropWindowBlocks() + 1);
            int high = current.sample.surfaceY();
            for (int cursor = windowStart; cursor < index; cursor++) {
                high = Math.max(high, outcomes.get(cursor).sample.surfaceY());
            }
            if (high - current.sample.surfaceY() > program.terrainPolicy().maxContinuousDropBlocks()) {
                terminationOrdinal = index;
                terminationReason = "CITY_DECORATION_RUN_CONTINUOUS_DROP_TERMINATED";
                break;
            }
            if (program.terrainPolicy().foundationMode() == CompiledDecorationProgram.FoundationMode.FILL_ONLY
                    && !current.sample.water()) {
                List<Integer> local = new ArrayList<>();
                for (int cursor = Math.max(0, index - 1); cursor <= Math.min(outcomes.size() - 1, index + 1); cursor++) {
                    MutableOutcome neighbour = outcomes.get(cursor);
                    if (neighbour.sample.available() && !neighbour.sample.water()) {
                        local.add(neighbour.sample.surfaceY());
                    }
                }
                local.sort(Integer::compareTo);
                int smoothed = local.get(local.size() / 2);
                if (smoothed - current.sample.surfaceY()
                        > program.terrainPolicy().maxFoundationDepthBlocks()) {
                    terminationOrdinal = index;
                    terminationReason = "CITY_DECORATION_RUN_FOUNDATION_DEPTH_TERMINATED";
                    break;
                }
            }
        }

        if (terminationOrdinal >= 0) {
            for (int index = terminationOrdinal; index < outcomes.size(); index++) {
                MutableOutcome value = outcomes.get(index);
                value.decision = terminationReason.endsWith("UNAVAILABLE") ? Decision.DEFER : Decision.TERMINATE;
                value.reasonCode = terminationReason;
            }
            if (terminationOrdinal > 0) {
                MutableOutcome lastSafe = outcomes.get(terminationOrdinal - 1);
                String fallback = catalog.requireContent(lastSafe.contentRef).terrainDropFallbackContentRef();
                if (fallback != null) {
                    lastSafe.decision = Decision.END_CAP;
                    lastSafe.appliedContentRef = fallback;
                    LayerSelection primary = lastSafe.layers.get(0);
                    lastSafe.layers.set(0, new LayerSelection(primary.layerId(), primary.contentRef(),
                            fallback, primary.required()));
                    lastSafe.reasonCode = terminationReason;
                }
            }
        }

        applyFoundationTargets(program, outcomes);
        outcomes.forEach(outcome -> outcome.runId = runId);
        List<SlotOutcome> frozenSlots = outcomes.stream().map(MutableOutcome::freeze).toList();
        List<FoundationSegment> segments = foundationSegments(program, runId, outcomes, terrain);
        return new Run(runId, program.programId(), line.paletteSlotId(), continuation.axis(), line.crossCoordinate(),
                frozenSlots, terminationOrdinal < 0 ? null : terminationOrdinal, terminationReason, segments);
    }

    private static void applyFoundationTargets(CompiledDecorationProgram program, List<MutableOutcome> outcomes) {
        for (int index = 0; index < outcomes.size(); index++) {
            MutableOutcome value = outcomes.get(index);
            value.targetY = value.sample.surfaceY();
            if (program.terrainPolicy().foundationMode() != CompiledDecorationProgram.FoundationMode.FILL_ONLY
                    || value.sample.water() || value.decision == Decision.TERMINATE || value.decision == Decision.DEFER) {
                continue;
            }
            List<Integer> local = new ArrayList<>();
            for (int cursor = Math.max(0, index - 1); cursor <= Math.min(outcomes.size() - 1, index + 1); cursor++) {
                MutableOutcome neighbour = outcomes.get(cursor);
                if (!neighbour.sample.water() && neighbour.sample.available()
                        && neighbour.decision != Decision.TERMINATE && neighbour.decision != Decision.DEFER) {
                    local.add(neighbour.sample.surfaceY());
                }
            }
            local.sort(Integer::compareTo);
            int smoothed = local.get(local.size() / 2);
            int fillDepth = Math.max(0, smoothed - value.sample.surfaceY());
            if (fillDepth <= program.terrainPolicy().maxFoundationDepthBlocks()) {
                value.targetY = Math.max(value.sample.surfaceY(), smoothed);
            }
        }
    }

    private static List<FoundationSegment> foundationSegments(CompiledDecorationProgram program,
                                                               String runId,
                                                               List<MutableOutcome> outcomes,
                                                               TerrainView terrain) {
        if (program.terrainPolicy().foundationMode() != CompiledDecorationProgram.FoundationMode.FILL_ONLY) {
            return List.of();
        }
        List<MutableOutcome> eligible = outcomes.stream().filter(value -> !value.sample.water()
                && value.decision != Decision.TERMINATE && value.decision != Decision.DEFER).toList();
        if (eligible.isEmpty()) {
            return List.of();
        }
        List<FoundationSegment> result = new ArrayList<>();
        if (eligible.size() == 1) {
            return List.of();
        }
        for (int index = 0; index + 1 < eligible.size(); index++) {
            MutableOutcome first = eligible.get(index);
            MutableOutcome second = eligible.get(index + 1);
            if (second.ordinal == first.ordinal + 1) {
                result.add(segment(program, runId, first, second, terrain));
            }
        }
        return List.copyOf(result);
    }

    private static FoundationSegment segment(CompiledDecorationProgram program, String runId,
                                             MutableOutcome first, MutableOutcome second,
                                             TerrainView terrain) {
        int shoulderBlocks = safeShoulderBlocks(program.terrainPolicy().foundationShoulderBlocks(),
                first.slot.worldAnchor(), second.slot.worldAnchor(), terrain);
        return new FoundationSegment(runId, first.slot.worldAnchor().x(), first.slot.worldAnchor().z(), first.targetY,
                second.slot.worldAnchor().x(), second.slot.worldAnchor().z(), second.targetY, 0,
                program.terrainPolicy().maxFoundationDepthBlocks(),
                shoulderBlocks);
    }

    private static int safeShoulderBlocks(int configuredShoulder, BlockPoint first, BlockPoint second,
                                          TerrainView terrain) {
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
                TerrainSample sample = Objects.requireNonNull(terrain.sample(x, z),
                        "CITY_DECORATION_TERRAIN_SAMPLE_MISSING");
                if (!sample.available() || sample.water()) {
                    return 0;
                }
            }
        }
        return configuredShoulder;
    }

    private static Continuation continuation(CompiledDecorationProgram program) {
        if (program.pattern() instanceof CompiledDecorationProgram.CrossSectionRepeatPattern value) {
            CompiledDecorationProgram.Axis axis = value.axis() == CompiledDecorationProgram.Axis.U
                    ? CompiledDecorationProgram.Axis.V : CompiledDecorationProgram.Axis.U;
            return new Continuation(axis);
        }
        if (program.pattern() instanceof CompiledDecorationProgram.ParallelRowsPattern value) {
            return new Continuation(value.axis());
        }
        return null;
    }

    private static String runId(CompiledDecorationProgram program, Continuation continuation, LineKey line,
                                List<DecorationSlot> slots) {
        String identity = program.programId() + "|" + line.paletteSlotId() + "|" + continuation.axis()
                + "|" + line.crossCoordinate() + "|" + continuation.along(slots.get(0).localAnchor())
                + "|" + continuation.along(slots.get(slots.size() - 1).localAnchor());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return "run:" + java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }

    public interface TerrainView {
        TerrainSample sample(int worldX, int worldZ);
    }

    public record TerrainSample(int surfaceY, boolean water, boolean available) {
    }

    public enum TerrainClass {
        SAFE, WATER, UNAVAILABLE
    }

    public enum Decision {
        PLACE, END_CAP, TERMINATE, DEFER
    }

    public record FrozenPlan(String schemaVersion, String cityId, String catalogHash,
                             List<Run> runs, List<FoundationSegment> foundationSegments) {
        public FrozenPlan {
            if (!SCHEMA.equals(schemaVersion) || cityId == null || cityId.isBlank()
                    || catalogHash == null || catalogHash.isBlank()) {
                throw new IllegalArgumentException("CITY_DECORATION_FROZEN_TERRAIN_PLAN_INVALID");
            }
            runs = List.copyOf(runs);
            foundationSegments = List.copyOf(foundationSegments);
            Set<String> runIds = new HashSet<>();
            Set<String> slotIds = new HashSet<>();
            for (Run run : runs) {
                if (!runIds.add(run.runId())) {
                    throw new IllegalArgumentException("CITY_DECORATION_FROZEN_RUN_ID_DUPLICATE: " + run.runId());
                }
                for (SlotOutcome slot : run.slots()) {
                    if (!slotIds.add(slot.slotId())) {
                        throw new IllegalArgumentException("CITY_DECORATION_FROZEN_SLOT_ID_DUPLICATE: "
                                + slot.slotId());
                    }
                }
            }
            List<FoundationSegment> derivedSegments = runs.stream()
                    .flatMap(run -> run.foundationSegments().stream()).toList();
            if (!foundationSegments.equals(derivedSegments)) {
                throw new IllegalArgumentException("CITY_DECORATION_FROZEN_FOUNDATION_SEGMENTS_MISMATCH");
            }
        }

        public Map<String, SlotOutcome> outcomesBySlotId() {
            Map<String, SlotOutcome> result = new HashMap<>();
            runs.forEach(run -> run.slots().forEach(slot -> {
                if (result.putIfAbsent(slot.slotId(), slot) != null) {
                    throw new IllegalStateException("CITY_DECORATION_FROZEN_SLOT_ID_DUPLICATE: " + slot.slotId());
                }
            }));
            return Map.copyOf(result);
        }
    }

    public record Run(String runId, String programId, String paletteSlotId,
                      CompiledDecorationProgram.Axis continuationAxis, int crossCoordinate,
                      List<SlotOutcome> slots, Integer terminationOrdinal, String terminationReasonCode,
                      List<FoundationSegment> foundationSegments) {
        public Run {
            if (runId == null || runId.isBlank() || programId == null || programId.isBlank()
                    || paletteSlotId == null || paletteSlotId.isBlank() || continuationAxis == null) {
                throw new IllegalArgumentException("CITY_DECORATION_FROZEN_RUN_INVALID");
            }
            slots = List.copyOf(slots);
            foundationSegments = List.copyOf(foundationSegments);
            Set<String> slotIds = new HashSet<>();
            for (SlotOutcome slot : slots) {
                if (!runId.equals(slot.runId())) {
                    throw new IllegalArgumentException("CITY_DECORATION_FROZEN_SLOT_RUN_ID_MISMATCH");
                }
                if (!slotIds.add(slot.slotId())) {
                    throw new IllegalArgumentException("CITY_DECORATION_FROZEN_SLOT_ID_DUPLICATE: "
                            + slot.slotId());
                }
            }
            for (FoundationSegment segment : foundationSegments) {
                if (!runId.equals(segment.runId())) {
                    throw new IllegalArgumentException("CITY_DECORATION_FROZEN_SEGMENT_RUN_ID_MISMATCH");
                }
            }
        }
    }

    public record SlotOutcome(String runId, String slotId, BlockPoint worldAnchor, int runOrdinal,
                              int surfaceY, int targetY, boolean water, TerrainClass terrainClass,
                              Decision decision, String contentRef, String appliedContentRef, String reasonCode,
                              List<LayerSelection> layers) {
        public SlotOutcome {
            if (runId == null || runId.isBlank() || slotId == null || slotId.isBlank()
                    || worldAnchor == null || runOrdinal < 0 || terrainClass == null || decision == null
                    || contentRef == null || contentRef.isBlank()
                    || appliedContentRef == null || appliedContentRef.isBlank()
                    || reasonCode == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("CITY_DECORATION_FROZEN_SLOT_INVALID");
            }
            layers = List.copyOf(layers);
            if (layers.isEmpty() || !contentRef.equals(layers.get(0).contentRef())
                    || !appliedContentRef.equals(layers.get(0).appliedContentRef())) {
                throw new IllegalArgumentException("CITY_DECORATION_FROZEN_SLOT_LAYERS_INVALID");
            }
        }

        public SlotOutcome(String runId, String slotId, BlockPoint worldAnchor, int runOrdinal,
                           int surfaceY, int targetY, boolean water, TerrainClass terrainClass,
                           Decision decision, String contentRef, String appliedContentRef, String reasonCode) {
            this(runId, slotId, worldAnchor, runOrdinal, surfaceY, targetY, water, terrainClass,
                    decision, contentRef, appliedContentRef, reasonCode,
                    List.of(new LayerSelection("primary", contentRef, appliedContentRef, true)));
        }
    }

    public record LayerSelection(String layerId, String contentRef, String appliedContentRef, boolean required) {
        public LayerSelection {
            if (layerId == null || layerId.isBlank() || contentRef == null || contentRef.isBlank()
                    || appliedContentRef == null || appliedContentRef.isBlank()) {
                throw new IllegalArgumentException("CITY_DECORATION_FROZEN_LAYER_SELECTION_INVALID");
            }
        }
    }

    public record FoundationSegment(String runId, int x0, int z0, int y0, int x1, int z1, int y1,
                                    int halfWidth, int maxDepthBlocks, int shoulderBlocks) {
        public FoundationSegment {
            if (runId == null || runId.isBlank() || x0 == x1 && z0 == z1
                    || halfWidth < 0 || maxDepthBlocks <= 0 || shoulderBlocks < 0) {
                throw new IllegalArgumentException("CITY_DECORATION_FROZEN_FOUNDATION_SEGMENT_INVALID");
            }
        }
    }

    private record Continuation(CompiledDecorationProgram.Axis axis) {
        int along(CompiledDecorationProgram.LocalPoint point) {
            return axis == CompiledDecorationProgram.Axis.U ? point.u() : point.v();
        }

        int cross(CompiledDecorationProgram.LocalPoint point) {
            return axis == CompiledDecorationProgram.Axis.U ? point.v() : point.u();
        }
    }

    private record LineKey(String paletteSlotId, int crossCoordinate) {
    }

    private static final class MutableOutcome {
        private final DecorationSlot slot;
        private final int ordinal;
        private final TerrainSample sample;
        private final List<LayerSelection> layers;
        private final String contentRef;
        private Decision decision = Decision.PLACE;
        private String appliedContentRef;
        private String reasonCode = "CITY_DECORATION_RUN_SLOT_SAFE";
        private int targetY;
        private String runId;

        private MutableOutcome(DecorationSlot slot, int ordinal, TerrainSample sample,
                               List<LayerSelection> layers) {
            this.slot = slot;
            this.ordinal = ordinal;
            this.sample = sample;
            this.layers = new ArrayList<>(layers);
            this.contentRef = layers.get(0).contentRef();
            this.appliedContentRef = layers.get(0).appliedContentRef();
            this.targetY = sample.surfaceY();
        }

        private SlotOutcome freeze() {
            TerrainClass terrainClass = !sample.available() ? TerrainClass.UNAVAILABLE
                    : sample.water() ? TerrainClass.WATER : TerrainClass.SAFE;
            return new SlotOutcome(runId, slot.slotId(), slot.worldAnchor(), ordinal, sample.surfaceY(), targetY,
                    sample.water(), terrainClass, decision, contentRef, appliedContentRef, reasonCode, layers);
        }
    }
}
