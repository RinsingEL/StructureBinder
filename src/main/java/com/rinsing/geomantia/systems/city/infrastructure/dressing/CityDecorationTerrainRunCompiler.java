package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import com.rinsing.geomantia.systems.city.application.dressing.CityDecorationProgramPlanner;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgramPlan;
import com.rinsing.geomantia.systems.city.application.dressing.DecorationSlot;
import com.rinsing.geomantia.systems.city.application.terrain.CityContinuousTerrainRunPlanner;
import com.rinsing.geomantia.systems.city.application.terrain.CityTerrainFoundationDensityComputer;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Compiles complete continuous-pattern runs before activation; it never groups by owner chunk. */
public final class CityDecorationTerrainRunCompiler {
    public static final String SCHEMA = "city_decoration_frozen_terrain_runs";

    private final CityDecorationProgramPlanner planner = new CityDecorationProgramPlanner();
    private final CityContinuousTerrainRunPlanner runPlanner = new CityContinuousTerrainRunPlanner();

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
            CityContinuousTerrainRunPlanner.Axis axis = continuationAxis(program);
            if (axis == null) {
                if (program.terrainPolicy().foundationMode() == CompiledDecorationProgram.FoundationMode.FILL_ONLY) {
                    throw new IllegalArgumentException("CITY_DECORATION_FOUNDATION_PATTERN_UNSUPPORTED");
                }
                continue;
            }
            List<DecorationSlot> slots = planner.project(program, program.targetMask().bounds());
            runs.addAll(compileRuns(program, catalog, terrain, axis, slots));
        }
        runs.sort(Comparator.comparing(Run::runId));
        List<FoundationSegment> segments = runs.stream().flatMap(run -> run.foundationSegments().stream()).toList();
        return new FrozenPlan(SCHEMA, plan.cityId(), plan.catalogHash(), List.copyOf(runs), segments);
    }

    private List<Run> compileRuns(CompiledDecorationProgram program,
                                  CityDecorationContentCatalog catalog,
                                  TerrainView terrain,
                                  CityContinuousTerrainRunPlanner.Axis axis,
                                  List<DecorationSlot> slots) {
        List<CityContinuousTerrainRunPlanner.Point> points = new ArrayList<>();
        Map<String, List<LayerSelection>> layersBySlot = new HashMap<>();
        for (DecorationSlot slot : slots) {
            CompiledDecorationProgram.PaletteSlot paletteSlot =
                    program.contentPalette().requireSlot(slot.paletteSlotId());
            List<LayerSelection> layers = paletteSlot.layers().stream().map(layer -> {
                String contentRef = CityDecorationChunkCompiler.selectContent(program, slot, paletteSlot, layer)
                        .contentRef();
                return new LayerSelection(layer.layerId(), contentRef, contentRef, layer.required());
            }).toList();
            layersBySlot.put(slot.slotId(), layers);
            points.add(new CityContinuousTerrainRunPlanner.Point(slot.paletteSlotId(), slot.slotId(),
                    new CityContinuousTerrainRunPlanner.GridPoint(
                            slot.localAnchor().u(), slot.localAnchor().v()),
                    slot.worldAnchor(), layers.get(0).contentRef()));
        }

        CityContinuousTerrainRunPlanner.Plan common = runPlanner.compile(
                new CityContinuousTerrainRunPlanner.Request(program.programId(), axis,
                        runPolicy(program.terrainPolicy()), decorationReasonCodes(), points),
                (x, z) -> {
                    TerrainSample sample = terrain.sample(x, z);
                    return sample == null ? null : new CityContinuousTerrainRunPlanner.TerrainSample(
                            sample.surfaceY(), sample.water(), sample.available());
                }, contentRef -> catalog.requireContent(contentRef).terrainDropFallbackContentRef());

        List<Run> result = new ArrayList<>();
        for (CityContinuousTerrainRunPlanner.Run run : common.runs()) {
            List<SlotOutcome> outcomes = run.points().stream()
                    .map(point -> slotOutcome(point, layersBySlot.get(point.pointId())))
                    .toList();
            List<FoundationSegment> segments = run.foundationSegments().stream()
                    .map(CityDecorationTerrainRunCompiler::foundationSegment).toList();
            result.add(new Run(run.runId(), run.sourceId(), run.trackId(),
                    decorationAxis(run.axis()), run.crossCoordinate(), outcomes,
                    run.terminationOrdinal(), run.terminationReasonCode(), segments));
        }
        return List.copyOf(result);
    }

    private static SlotOutcome slotOutcome(CityContinuousTerrainRunPlanner.PointOutcome point,
                                           List<LayerSelection> sourceLayers) {
        if (sourceLayers == null || sourceLayers.isEmpty()) {
            throw new IllegalArgumentException("CITY_DECORATION_FROZEN_SLOT_LAYERS_INVALID");
        }
        List<LayerSelection> layers = new ArrayList<>(sourceLayers);
        LayerSelection primary = layers.get(0);
        if (!primary.contentRef().equals(point.contentRef())) {
            throw new IllegalArgumentException("CITY_DECORATION_FROZEN_SLOT_LAYERS_INVALID");
        }
        layers.set(0, new LayerSelection(primary.layerId(), primary.contentRef(),
                point.appliedContentRef(), primary.required()));
        return new SlotOutcome(point.runId(), point.pointId(), point.worldAnchor(), point.runOrdinal(),
                point.surfaceY(), point.targetY(), point.water(), TerrainClass.valueOf(point.terrainClass().name()),
                Decision.valueOf(point.decision().name()), point.contentRef(), point.appliedContentRef(),
                point.reasonCode(), layers);
    }

    private static FoundationSegment foundationSegment(
            CityContinuousTerrainRunPlanner.FoundationSegment segment) {
        return new FoundationSegment(segment.runId(), segment.x0(), segment.z0(), segment.y0(),
                segment.x1(), segment.z1(), segment.y1(), segment.halfWidth(),
                segment.maxDepthBlocks(), segment.shoulderBlocks());
    }

    private static CityContinuousTerrainRunPlanner.Axis continuationAxis(CompiledDecorationProgram program) {
        if (program.pattern() instanceof CompiledDecorationProgram.CrossSectionRepeatPattern value) {
            CompiledDecorationProgram.Axis continuation = value.axis() == CompiledDecorationProgram.Axis.U
                    ? CompiledDecorationProgram.Axis.V : CompiledDecorationProgram.Axis.U;
            return commonAxis(continuation);
        }
        if (program.pattern() instanceof CompiledDecorationProgram.ParallelRowsPattern value) {
            return commonAxis(value.axis());
        }
        return null;
    }

    private static CityContinuousTerrainRunPlanner.Axis commonAxis(CompiledDecorationProgram.Axis axis) {
        return CityContinuousTerrainRunPlanner.Axis.valueOf(axis.name());
    }

    private static CompiledDecorationProgram.Axis decorationAxis(CityContinuousTerrainRunPlanner.Axis axis) {
        return CompiledDecorationProgram.Axis.valueOf(axis.name());
    }

    private static CityContinuousTerrainRunPlanner.RunPolicy runPolicy(
            CompiledDecorationProgram.TerrainPolicy policy) {
        return new CityContinuousTerrainRunPlanner.RunPolicy(policy.maxSlopeDelta(), policy.allowWater(),
                policy.maxContinuousDropBlocks(), policy.continuousDropWindowBlocks(),
                CityContinuousTerrainRunPlanner.FoundationMode.valueOf(policy.foundationMode().name()),
                policy.maxFoundationDepthBlocks(), policy.foundationShoulderBlocks());
    }

    private static CityContinuousTerrainRunPlanner.ReasonCodes decorationReasonCodes() {
        return new CityContinuousTerrainRunPlanner.ReasonCodes(
                "CITY_DECORATION_RUN_SLOT_SAFE",
                "CITY_DECORATION_RUN_TERRAIN_UNAVAILABLE",
                "CITY_DECORATION_RUN_WATER_TERMINATED",
                "CITY_DECORATION_RUN_LOCAL_CLIFF_TERMINATED",
                "CITY_DECORATION_RUN_CONTINUOUS_DROP_TERMINATED",
                "CITY_DECORATION_RUN_FOUNDATION_DEPTH_TERMINATED",
                "CITY_DECORATION_TERRAIN_SAMPLE_MISSING");
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

    public record FrozenPlan(String schema, String cityId, String catalogHash,
                             List<Run> runs, List<FoundationSegment> foundationSegments) {
        public FrozenPlan {
            if (!SCHEMA.equals(schema) || cityId == null || cityId.isBlank()
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
                                    int halfWidth, int maxDepthBlocks, int shoulderBlocks)
            implements CityTerrainFoundationDensityComputer.FoundationSegmentView {
        public FoundationSegment {
            if (runId == null || runId.isBlank() || x0 == x1 && z0 == z1
                    || halfWidth < 0 || maxDepthBlocks <= 0 || shoulderBlocks < 0) {
                throw new IllegalArgumentException("CITY_DECORATION_FROZEN_FOUNDATION_SEGMENT_INVALID");
            }
        }
    }

}
