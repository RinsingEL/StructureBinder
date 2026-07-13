package com.rinsing.geomantia.systems.city.application.dressing;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Read-only terrain assessment over the already projected decoration slots. */
public final class CityDecorationTerrainProbe {
    public static final String SCHEMA = "city_decoration_terrain_probe.v0.1";

    public JsonObject probe(CompiledDecorationProgramPlan plan,
                            List<DecorationSlot> slots,
                            TerrainView terrain) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(slots, "slots");
        Objects.requireNonNull(terrain, "terrain");

        Map<String, CompiledDecorationProgram> programs = new LinkedHashMap<>();
        for (CompiledDecorationProgram program : plan.programsInExecutionOrder()) {
            programs.put(program.programId(), program);
        }
        Map<String, List<Observation>> observationsByProgram = new LinkedHashMap<>();
        for (DecorationSlot slot : slots) {
            CompiledDecorationProgram program = programs.get(slot.programId());
            if (program == null) {
                throw new IllegalArgumentException("CITY_DECORATION_TERRAIN_PROBE_SLOT_PROGRAM_UNKNOWN: "
                        + slot.programId());
            }
            program.contentPalette().requireSlot(slot.paletteSlotId());
            Sample sample = terrain.sample(slot.worldAnchor().x(), slot.worldAnchor().z());
            if (sample == null) {
                throw new IllegalArgumentException("CITY_DECORATION_TERRAIN_PROBE_SAMPLE_MISSING: "
                        + slot.worldAnchor().x() + "," + slot.worldAnchor().z());
            }
            observationsByProgram.computeIfAbsent(program.programId(), ignored -> new ArrayList<>())
                    .add(new Observation(slot, sample));
        }

        JsonArray programArray = new JsonArray();
        int projected = 0;
        int loaded = 0;
        int unavailable = 0;
        LinkedHashSet<String> overallReasons = new LinkedHashSet<>();
        for (CompiledDecorationProgram program : plan.programsInExecutionOrder()) {
            List<Observation> observations = observationsByProgram.getOrDefault(program.programId(), List.of());
            ProgramSummary summary = summarizeProgram(program, observations);
            programArray.add(summary.json());
            projected += summary.projectedSlotCount();
            loaded += summary.loadedSlotCount();
            unavailable += summary.unavailableSlotCount();
            overallReasons.addAll(summary.reasonCodes());
        }

        JsonObject response = new JsonObject();
        response.addProperty("schemaVersion", SCHEMA);
        response.addProperty("cityId", plan.cityId());
        response.addProperty("catalogHash", plan.catalogHash());
        response.addProperty("styleProfileId", plan.styleProfileId());
        response.addProperty("styleProfileHash", plan.styleProfileHash());
        response.addProperty("loadedChunksOnly", true);
        response.addProperty("mutatesWorld", false);
        JsonObject overall = new JsonObject();
        overall.addProperty("projectedSlotCount", projected);
        overall.addProperty("sampledSlotCount", loaded);
        overall.addProperty("loadedSlotCount", loaded);
        overall.addProperty("unavailableSlotCount", unavailable);
        overall.addProperty("coverageRatio", coverageRatio(projected, loaded));
        String recommendation = overallRecommendation(projected, unavailable, overallReasons);
        overall.addProperty("activationRecommendation", recommendation);
        overall.add("reasonCodes", reasonCodes(overallReasons));
        response.add("overall", overall);
        response.add("programs", programArray);
        return response;
    }

    private ProgramSummary summarizeProgram(CompiledDecorationProgram program, List<Observation> observations) {
        List<Observation> ordered = new ArrayList<>(observations);
        ordered.sort(OBSERVATION_ORDER);
        Metrics metrics = metrics(ordered, true);
        Map<String, List<Observation>> byPalette = new LinkedHashMap<>();
        for (Observation observation : ordered) {
            byPalette.computeIfAbsent(observation.slot().paletteSlotId(), ignored -> new ArrayList<>())
                    .add(observation);
        }

        JsonArray palettes = new JsonArray();
        JsonArray continuousProfiles = new JsonArray();
        LinkedHashSet<String> programReasons = new LinkedHashSet<>(metrics.reasonCodes());
        if (metrics.maxAdjacentHeightDelta() != null
                && metrics.maxAdjacentHeightDelta() > program.terrainPolicy().maxSlopeDelta()) {
            programReasons.add("CITY_DECORATION_TERRAIN_SLOPE_REVIEW_REQUIRED");
        }
        for (CompiledDecorationProgram.PaletteSlot paletteSlot : program.contentPalette().slots()) {
            List<Observation> paletteObservations = byPalette.getOrDefault(paletteSlot.slotId(), List.of());
            Metrics paletteMetrics = metrics(paletteObservations, false);
            JsonObject palette = metricsJson(paletteMetrics);
            palette.addProperty("paletteSlotId", paletteSlot.slotId());
            palette.addProperty("required", paletteSlot.required());
            palette.addProperty("phase", paletteSlot.phase().serializedName());
            palettes.add(palette);
            programReasons.addAll(paletteMetrics.reasonCodes());

            ContinuityProfile profile = continuityProfile(program, paletteSlot, paletteObservations);
            if (profile != null) {
                continuousProfiles.add(profile.json());
                programReasons.addAll(profile.reasonCodes());
            }
        }

        JsonObject json = metricsJson(metrics);
        json.addProperty("programId", program.programId());
        json.addProperty("patternType", program.pattern().type());
        json.addProperty("terrainMaxSlopeDelta", program.terrainPolicy().maxSlopeDelta());
        json.addProperty("allowWater", program.terrainPolicy().allowWater());
        json.add("paletteSlots", palettes);
        json.add("continuousBandProfiles", continuousProfiles);
        json.addProperty("activationRecommendation", programRecommendation(metrics, programReasons));
        json.add("reasonCodes", reasonCodes(programReasons));
        return new ProgramSummary(json, metrics.projectedSlotCount(), metrics.loadedSlotCount(),
                metrics.unavailableSlotCount(), Set.copyOf(programReasons));
    }

    private Metrics metrics(List<Observation> observations, boolean includeAllPaletteNeighbours) {
        int projected = observations.size();
        int loaded = 0;
        int unavailable = 0;
        int minHeight = Integer.MAX_VALUE;
        int maxHeight = Integer.MIN_VALUE;
        Map<Point, Observation> byPoint = new LinkedHashMap<>();
        for (Observation observation : observations) {
            Point point = Point.of(observation.slot());
            if (byPoint.put(point, observation) != null) {
                throw new IllegalArgumentException("CITY_DECORATION_TERRAIN_PROBE_SLOT_COORDINATE_DUPLICATE: "
                        + point.x() + "," + point.z());
            }
            if (observation.sample().loaded()) {
                loaded++;
                minHeight = Math.min(minHeight, observation.sample().surfaceY());
                maxHeight = Math.max(maxHeight, observation.sample().surfaceY());
            } else {
                unavailable++;
            }
        }
        int adjacentPairs = 0;
        int unavailableAdjacentPairs = 0;
        Integer maxAdjacentDelta = null;
        for (Map.Entry<Point, Observation> entry : byPoint.entrySet()) {
            for (Point neighbour : List.of(new Point(entry.getKey().x() + 1, entry.getKey().z()),
                    new Point(entry.getKey().x(), entry.getKey().z() + 1))) {
                Observation other = byPoint.get(neighbour);
                if (other == null) {
                    continue;
                }
                if (!includeAllPaletteNeighbours
                        && !entry.getValue().slot().paletteSlotId().equals(other.slot().paletteSlotId())) {
                    continue;
                }
                adjacentPairs++;
                if (!entry.getValue().sample().loaded() || !other.sample().loaded()) {
                    unavailableAdjacentPairs++;
                    continue;
                }
                int delta = Math.abs(entry.getValue().sample().surfaceY() - other.sample().surfaceY());
                maxAdjacentDelta = maxAdjacentDelta == null ? delta : Math.max(maxAdjacentDelta, delta);
            }
        }
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        if (unavailable > 0) {
            reasons.add("CITY_DECORATION_TERRAIN_CHUNK_UNAVAILABLE");
        }
        return new Metrics(projected, loaded, unavailable, loaded == 0 ? null : minHeight,
                loaded == 0 ? null : maxHeight, adjacentPairs, unavailableAdjacentPairs, maxAdjacentDelta,
                Set.copyOf(reasons));
    }

    private ContinuityProfile continuityProfile(CompiledDecorationProgram program,
                                                CompiledDecorationProgram.PaletteSlot paletteSlot,
                                                List<Observation> observations) {
        if (observations.isEmpty()) {
            return null;
        }
        CompiledDecorationProgram.Axis continuationAxis = continuationAxis(program.pattern());
        if (continuationAxis == null) {
            return null;
        }
        Map<Integer, List<Observation>> lines = new LinkedHashMap<>();
        for (Observation observation : observations) {
            int line = continuationAxis == CompiledDecorationProgram.Axis.U
                    ? observation.slot().localAnchor().v() : observation.slot().localAnchor().u();
            lines.computeIfAbsent(line, ignored -> new ArrayList<>()).add(observation);
        }
        int expectedPairs = 0;
        int loadedPairs = 0;
        int unavailablePairs = 0;
        int segments = 0;
        Integer maxDelta = null;
        for (List<Observation> line : lines.values()) {
            line.sort(Comparator.comparingInt(observation -> continuationAxis == CompiledDecorationProgram.Axis.U
                    ? observation.slot().localAnchor().u() : observation.slot().localAnchor().v()));
            boolean inLoadedSegment = false;
            for (int index = 0; index < line.size(); index++) {
                Observation current = line.get(index);
                if (current.sample().loaded()) {
                    if (!inLoadedSegment) {
                        segments++;
                        inLoadedSegment = true;
                    }
                } else {
                    inLoadedSegment = false;
                }
                if (index == 0) {
                    continue;
                }
                Observation previous = line.get(index - 1);
                int currentCoordinate = continuationAxis == CompiledDecorationProgram.Axis.U
                        ? current.slot().localAnchor().u() : current.slot().localAnchor().v();
                int previousCoordinate = continuationAxis == CompiledDecorationProgram.Axis.U
                        ? previous.slot().localAnchor().u() : previous.slot().localAnchor().v();
                if (currentCoordinate - previousCoordinate != 1) {
                    continue;
                }
                expectedPairs++;
                if (!current.sample().loaded() || !previous.sample().loaded()) {
                    unavailablePairs++;
                    continue;
                }
                loadedPairs++;
                int delta = Math.abs(current.sample().surfaceY() - previous.sample().surfaceY());
                maxDelta = maxDelta == null ? delta : Math.max(maxDelta, delta);
            }
        }
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        if (unavailablePairs > 0) {
            reasons.add("CITY_DECORATION_CONTINUOUS_BAND_UNAVAILABLE");
        }
        if (maxDelta != null && maxDelta > 1) {
            reasons.add("CITY_DECORATION_CONTINUOUS_BAND_SLOPE_RISK");
        }
        JsonObject json = new JsonObject();
        json.addProperty("programId", program.programId());
        json.addProperty("paletteSlotId", paletteSlot.slotId());
        json.addProperty("continuationAxis", continuationAxis.serializedName());
        json.addProperty("projectedSlotCount", observations.size());
        json.addProperty("loadedSlotCount", (int) observations.stream().filter(value -> value.sample().loaded()).count());
        json.addProperty("unavailableSlotCount", (int) observations.stream().filter(value -> !value.sample().loaded()).count());
        json.addProperty("expectedAdjacentPairCount", expectedPairs);
        json.addProperty("loadedAdjacentPairCount", loadedPairs);
        json.addProperty("unavailableAdjacentPairCount", unavailablePairs);
        addNullableInt(json, "maxAdjacentHeightDelta", maxDelta);
        json.addProperty("continuousLoadedSegmentCount", segments);
        json.add("reasonCodes", reasonCodes(reasons));
        return new ContinuityProfile(json, Set.copyOf(reasons));
    }

    private static CompiledDecorationProgram.Axis continuationAxis(CompiledDecorationProgram.PatternSpec pattern) {
        if (pattern instanceof CompiledDecorationProgram.CrossSectionRepeatPattern value) {
            return value.axis() == CompiledDecorationProgram.Axis.U
                    ? CompiledDecorationProgram.Axis.V : CompiledDecorationProgram.Axis.U;
        }
        if (pattern instanceof CompiledDecorationProgram.ParallelRowsPattern value) {
            return value.axis();
        }
        return null;
    }

    private static JsonObject metricsJson(Metrics metrics) {
        JsonObject json = new JsonObject();
        json.addProperty("projectedSlotCount", metrics.projectedSlotCount());
        json.addProperty("sampledSlotCount", metrics.loadedSlotCount());
        json.addProperty("loadedSlotCount", metrics.loadedSlotCount());
        json.addProperty("unavailableSlotCount", metrics.unavailableSlotCount());
        json.addProperty("coverageRatio", coverageRatio(metrics.projectedSlotCount(), metrics.loadedSlotCount()));
        addNullableInt(json, "heightMin", metrics.heightMin());
        addNullableInt(json, "heightMax", metrics.heightMax());
        Integer spread = metrics.heightMin() == null || metrics.heightMax() == null
                ? null : metrics.heightMax() - metrics.heightMin();
        addNullableInt(json, "heightSpread", spread);
        json.addProperty("adjacentPairCount", metrics.adjacentPairCount());
        json.addProperty("unavailableAdjacentPairCount", metrics.unavailableAdjacentPairCount());
        addNullableInt(json, "maxAdjacentHeightDelta", metrics.maxAdjacentHeightDelta());
        json.add("reasonCodes", reasonCodes(metrics.reasonCodes()));
        return json;
    }

    private static String programRecommendation(Metrics metrics, Set<String> reasons) {
        if (metrics.unavailableSlotCount() > 0) {
            return "await_chunk_load";
        }
        return hasSlopeRisk(reasons)
                ? "review_terrain_before_activation" : "ready_for_activation";
    }

    private static String overallRecommendation(int projected, int unavailable, Set<String> reasons) {
        if (projected == 0) {
            return "no_projected_slots";
        }
        if (unavailable > 0) {
            return "await_chunk_load";
        }
        return hasSlopeRisk(reasons)
                ? "review_terrain_before_activation" : "ready_for_activation";
    }

    private static boolean hasSlopeRisk(Set<String> reasons) {
        return reasons.contains("CITY_DECORATION_TERRAIN_SLOPE_REVIEW_REQUIRED")
                || reasons.contains("CITY_DECORATION_CONTINUOUS_BAND_SLOPE_RISK");
    }

    private static double coverageRatio(int projected, int loaded) {
        return projected == 0 ? 0.0D : loaded / (double) projected;
    }

    private static void addNullableInt(JsonObject json, String key, Integer value) {
        if (value == null) {
            json.add(key, JsonNull.INSTANCE);
        } else {
            json.addProperty(key, value);
        }
    }

    private static JsonArray reasonCodes(Set<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static final Comparator<Observation> OBSERVATION_ORDER = Comparator
            .comparing((Observation value) -> value.slot().worldAnchor().x())
            .thenComparing(value -> value.slot().worldAnchor().z())
            .thenComparing(value -> value.slot().slotId());

    public interface TerrainView {
        Sample sample(int worldX, int worldZ);
    }

    public record Sample(boolean loaded, int surfaceY) {
        public static Sample unavailable() {
            return new Sample(false, 0);
        }
    }

    private record Observation(DecorationSlot slot, Sample sample) {
    }

    private record Point(int x, int z) {
        private static Point of(DecorationSlot slot) {
            return new Point(slot.worldAnchor().x(), slot.worldAnchor().z());
        }
    }

    private record Metrics(int projectedSlotCount,
                           int loadedSlotCount,
                           int unavailableSlotCount,
                           Integer heightMin,
                           Integer heightMax,
                           int adjacentPairCount,
                           int unavailableAdjacentPairCount,
                           Integer maxAdjacentHeightDelta,
                           Set<String> reasonCodes) {
    }

    private record ProgramSummary(JsonObject json,
                                  int projectedSlotCount,
                                  int loadedSlotCount,
                                  int unavailableSlotCount,
                                  Set<String> reasonCodes) {
    }

    private record ContinuityProfile(JsonObject json, Set<String> reasonCodes) {
    }
}
