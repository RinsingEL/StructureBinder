package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CityStructureEnvelopeProfiler {
    public static final String FACTS_SCHEMA = "city_structure_envelope_facts.v0.1";
    public static final int DEFAULT_SAMPLE_COUNT = 256;

    public Result profile(Path baseDirectory,
                          JsonObject terraSenseProfileSource,
                          List<String> structureIds,
                          int sampleCount,
                          StructureEnvelopeSampler sampler) throws IOException {
        long started = System.nanoTime();
        if (sampler == null) {
            throw new IllegalArgumentException("StructureEnvelopeSampler is required.");
        }
        int requestedSamples = sampleCount <= 0 ? DEFAULT_SAMPLE_COUNT : sampleCount;
        CityStructureProfileCatalog.ImportedCatalog catalog =
                CityStructureProfileCatalog.importCatalog(baseDirectory, terraSenseProfileSource);
        Map<String, CityStructureProfileCatalog.StructureProfile> profiles = catalog.byId();
        List<String> selectedIds = structureIds == null || structureIds.isEmpty()
                ? List.copyOf(profiles.keySet())
                : structureIds;

        JsonArray structures = new JsonArray();
        JsonArray hardBlocks = new JsonArray();
        JsonArray warnings = new JsonArray();
        JsonArray needsReview = new JsonArray();
        catalog.warnings().forEach(warnings::add);
        catalog.needsReview().forEach(needsReview::add);

        for (String structureId : selectedIds) {
            CityStructureProfileCatalog.StructureProfile profile = profiles.get(structureId);
            if (profile == null) {
                hardBlocks.add(structureId + ": structureId is not in TerraSense catalog.");
                continue;
            }
            structures.add(profileOne(profile, requestedSamples, sampler));
        }

        JsonObject facts = new JsonObject();
        facts.addProperty("schemaVersion", FACTS_SCHEMA);
        facts.addProperty("sampleCountRequested", requestedSamples);
        facts.add("sourceTerraSenseProfileSource", terraSenseProfileSource.deepCopy());
        facts.add("structures", structures);
        JsonObject quality = new JsonObject();
        quality.addProperty("passed", hardBlocks.isEmpty());
        quality.addProperty("score", hardBlocks.isEmpty() ? 100 : 0);
        quality.add("hardBlocks", hardBlocks);
        quality.add("warnings", warnings);
        quality.add("needsReview", needsReview);
        JsonObject metrics = new JsonObject();
        metrics.addProperty("structureCount", structures.size());
        quality.add("metrics", metrics);
        facts.add("quality", quality);
        facts.add("timingMs", timing(started));
        return new Result(facts, quality);
    }

    private JsonObject profileOne(CityStructureProfileCatalog.StructureProfile profile,
                                  int sampleCount,
                                  StructureEnvelopeSampler sampler) {
        List<EnvelopeSample> valid = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        String structureConfigHash = "";
        String sourcePackHash = "";
        for (int i = 0; i < sampleCount; i++) {
            EnvelopeSample sample = sampler.sample(profile, i);
            if (structureConfigHash.isBlank()) {
                structureConfigHash = sample.structureConfigHash();
            }
            if (sourcePackHash.isBlank()) {
                sourcePackHash = sample.sourcePackHash();
            }
            if (sample.valid()) {
                valid.add(sample);
            } else {
                failures.add(sample.reasonCode().isBlank() ? "INVALID_SAMPLE" : sample.reasonCode());
            }
        }

        JsonObject obj = new JsonObject();
        obj.addProperty("structureId", profile.structureId());
        obj.addProperty("profileHash", profileHash(profile));
        obj.addProperty("structureConfigHash", structureConfigHash);
        obj.addProperty("sourcePackHash", sourcePackHash);
        obj.addProperty("generationConfigHash", generationConfigHash(profile, structureConfigHash));
        obj.addProperty("sampleCount", sampleCount);
        obj.addProperty("validSampleCount", valid.size());
        obj.addProperty("invalidSampleCount", sampleCount - valid.size());
        obj.addProperty("invalidRatio", sampleCount == 0 ? 1.0 : (sampleCount - valid.size()) / (double) sampleCount);
        obj.addProperty("percentilePolicy", "min bounds use lower tail, max bounds use upper tail");
        obj.add("localEnvelopeP50", boundsJson(percentileBounds(valid, 50)));
        obj.add("localEnvelopeP90", boundsJson(percentileBounds(valid, 90)));
        obj.add("localEnvelopeP95", boundsJson(percentileBounds(valid, 95)));
        obj.add("localEnvelopeP99", boundsJson(percentileBounds(valid, 99)));
        obj.add("maxObservedEnvelope", boundsJson(maxObserved(valid)));
        obj.add("pieceCount", percentileNumbers(valid.stream().mapToInt(EnvelopeSample::pieceCount).boxed().toList()));
        obj.add("areaBlocks", percentileNumbers(valid.stream()
                .mapToInt(sample -> sample.localBounds().widthBlocks() * sample.localBounds().heightBlocks())
                .boxed().toList()));
        obj.add("validSamples", validSamples(valid));
        obj.add("bboxGroups", bboxGroups(valid));
        JsonArray samplePreview = new JsonArray();
        for (int i = 0; i < Math.min(16, valid.size()); i++) {
            samplePreview.add(sampleJson(valid.get(i)));
        }
        obj.add("samplePreview", samplePreview);
        obj.add("failureSummary", summarize(failures));
        return obj;
    }

    private static JsonArray validSamples(List<EnvelopeSample> valid) {
        JsonArray array = new JsonArray();
        for (EnvelopeSample sample : valid) {
            array.add(sampleJson(sample));
        }
        return array;
    }

    private static JsonArray bboxGroups(List<EnvelopeSample> valid) {
        Map<String, BBoxGroupAccumulator> groups = new LinkedHashMap<>();
        for (EnvelopeSample sample : valid) {
            String key = bboxGroupKey(sample.localBounds(), sample.pieceCount());
            groups.computeIfAbsent(key, ignored -> new BBoxGroupAccumulator(key, sample.localBounds(),
                    sample.pieceCount())).add(sample);
        }
        JsonArray array = new JsonArray();
        groups.values().stream()
                .sorted(Comparator.comparingInt(BBoxGroupAccumulator::sampleCount).reversed()
                        .thenComparing(BBoxGroupAccumulator::groupKey))
                .forEach(group -> array.add(group.asJson(valid.size())));
        return array;
    }

    private static JsonObject sampleJson(EnvelopeSample sample) {
        JsonObject sampleObj = new JsonObject();
        sampleObj.addProperty("sampleIndex", sample.sampleIndex());
        sampleObj.add("localBounds", boundsJson(sample.localBounds()));
        sampleObj.addProperty("pieceCount", sample.pieceCount());
        sampleObj.addProperty("areaBlocks", sample.localBounds().widthBlocks() * sample.localBounds().heightBlocks());
        sampleObj.addProperty("bboxGroupKey", bboxGroupKey(sample.localBounds(), sample.pieceCount()));
        return sampleObj;
    }

    private static JsonObject percentileNumbers(List<Integer> values) {
        JsonObject obj = new JsonObject();
        if (values.isEmpty()) {
            obj.addProperty("p50", 0);
            obj.addProperty("p90", 0);
            obj.addProperty("p95", 0);
            obj.addProperty("p99", 0);
            obj.addProperty("max", 0);
            return obj;
        }
        List<Integer> sorted = values.stream().sorted().toList();
        obj.addProperty("p50", percentile(sorted, 50));
        obj.addProperty("p90", percentile(sorted, 90));
        obj.addProperty("p95", percentile(sorted, 95));
        obj.addProperty("p99", percentile(sorted, 99));
        obj.addProperty("max", sorted.get(sorted.size() - 1));
        return obj;
    }

    private static JsonObject summarize(List<String> values) {
        JsonObject obj = new JsonObject();
        for (String value : values) {
            obj.addProperty(value, obj.has(value) ? obj.get(value).getAsInt() + 1 : 1);
        }
        return obj;
    }

    private static BlockBounds percentileBounds(List<EnvelopeSample> samples, int coveragePercent) {
        if (samples.isEmpty()) {
            return new BlockBounds(0, 0, 0, 0);
        }
        int lowerTail = Math.max(0, (100 - coveragePercent) / 2);
        int upperTail = 100 - lowerTail;
        List<Integer> minXs = sorted(samples, Axis.MIN_X);
        List<Integer> minZs = sorted(samples, Axis.MIN_Z);
        List<Integer> maxXs = sorted(samples, Axis.MAX_X);
        List<Integer> maxZs = sorted(samples, Axis.MAX_Z);
        return new BlockBounds(
                percentile(minXs, lowerTail),
                percentile(minZs, lowerTail),
                percentile(maxXs, upperTail),
                percentile(maxZs, upperTail));
    }

    private static BlockBounds maxObserved(List<EnvelopeSample> samples) {
        if (samples.isEmpty()) {
            return new BlockBounds(0, 0, 0, 0);
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (EnvelopeSample sample : samples) {
            BlockBounds bounds = sample.localBounds();
            minX = Math.min(minX, bounds.minX());
            minZ = Math.min(minZ, bounds.minZ());
            maxX = Math.max(maxX, bounds.maxX());
            maxZ = Math.max(maxZ, bounds.maxZ());
        }
        return new BlockBounds(minX, minZ, maxX, maxZ);
    }

    private static List<Integer> sorted(List<EnvelopeSample> samples, Axis axis) {
        return samples.stream()
                .map(sample -> switch (axis) {
                    case MIN_X -> sample.localBounds().minX();
                    case MIN_Z -> sample.localBounds().minZ();
                    case MAX_X -> sample.localBounds().maxX();
                    case MAX_Z -> sample.localBounds().maxZ();
                })
                .sorted()
                .toList();
    }

    private static int percentile(List<Integer> sortedValues, int percentile) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        if (percentile <= 0) {
            return sortedValues.get(0);
        }
        if (percentile >= 100) {
            return sortedValues.get(sortedValues.size() - 1);
        }
        int index = (int) Math.ceil((percentile / 100.0) * sortedValues.size()) - 1;
        index = Math.max(0, Math.min(index, sortedValues.size() - 1));
        return sortedValues.get(index);
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    public static String profileHash(CityStructureProfileCatalog.StructureProfile profile) {
        return sha256(CityJson.GSON.toJson(profile.asJson()));
    }

    public static String generationConfigHash(CityStructureProfileCatalog.StructureProfile profile,
                                              String structureConfigHash) {
        JsonObject obj = new JsonObject();
        obj.addProperty("structureId", profile.structureId());
        obj.addProperty("profileType", profile.profileType());
        obj.addProperty("footprintMode", profile.footprintMode());
        obj.add("fixedFootprint", profile.fixedFootprint().asJson());
        obj.add("expectedAreaRange", profile.expectedAreaRange().asJson());
        obj.addProperty("maxDistanceFromCenterBlocks", profile.maxDistanceFromCenterBlocks());
        obj.addProperty("structureConfigHash", structureConfigHash == null ? "" : structureConfigHash);
        return sha256(CityJson.GSON.toJson(obj));
    }

    public static String bboxGroupKey(BlockBounds bounds, int pieceCount) {
        String raw = pieceCount + ":" + bounds.minX() + ":" + bounds.minZ() + ":"
                + bounds.maxX() + ":" + bounds.maxZ();
        return "bbox_" + sha256(raw).substring(0, 16);
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static JsonObject timing(long started) {
        JsonObject timing = new JsonObject();
        timing.addProperty("total", (System.nanoTime() - started) / 1_000_000L);
        return timing;
    }

    private enum Axis {
        MIN_X, MIN_Z, MAX_X, MAX_Z
    }

    private static final class BBoxGroupAccumulator {
        private final String groupKey;
        private final BlockBounds localEnvelope;
        private final int pieceCount;
        private final List<Integer> sampleIndexes = new ArrayList<>();

        private BBoxGroupAccumulator(String groupKey, BlockBounds localEnvelope, int pieceCount) {
            this.groupKey = groupKey;
            this.localEnvelope = localEnvelope;
            this.pieceCount = pieceCount;
        }

        void add(EnvelopeSample sample) {
            sampleIndexes.add(sample.sampleIndex());
        }

        String groupKey() {
            return groupKey;
        }

        int sampleCount() {
            return sampleIndexes.size();
        }

        JsonObject asJson(int totalValidSamples) {
            JsonObject obj = new JsonObject();
            obj.addProperty("groupKey", groupKey);
            obj.addProperty("sampleCount", sampleIndexes.size());
            obj.addProperty("ratio", totalValidSamples == 0 ? 0.0 : sampleIndexes.size() / (double) totalValidSamples);
            obj.add("localEnvelope", boundsJson(localEnvelope));
            obj.addProperty("pieceCount", pieceCount);
            obj.addProperty("areaBlocks", localEnvelope.widthBlocks() * localEnvelope.heightBlocks());
            JsonArray examples = new JsonArray();
            for (int i = 0; i < Math.min(8, sampleIndexes.size()); i++) {
                examples.add(sampleIndexes.get(i));
            }
            obj.add("exampleSampleIndexes", examples);
            return obj;
        }
    }

    public interface StructureEnvelopeSampler {
        EnvelopeSample sample(CityStructureProfileCatalog.StructureProfile profile, int sampleIndex);
    }

    public record EnvelopeSample(int sampleIndex, boolean valid, BlockBounds localBounds, int pieceCount,
                                 String reasonCode, String structureConfigHash, String sourcePackHash) {
        public static EnvelopeSample valid(int sampleIndex, BlockBounds localBounds, int pieceCount,
                                           String structureConfigHash, String sourcePackHash) {
            return new EnvelopeSample(sampleIndex, true, localBounds, pieceCount, "",
                    structureConfigHash == null ? "" : structureConfigHash,
                    sourcePackHash == null ? "" : sourcePackHash);
        }

        public static EnvelopeSample invalid(int sampleIndex, String reasonCode,
                                             String structureConfigHash, String sourcePackHash) {
            return new EnvelopeSample(sampleIndex, false, new BlockBounds(0, 0, 0, 0), 0,
                    reasonCode == null ? "INVALID_SAMPLE" : reasonCode,
                    structureConfigHash == null ? "" : structureConfigHash,
                    sourcePackHash == null ? "" : sourcePackHash);
        }
    }

    public record Result(JsonObject structureEnvelopeFacts, JsonObject qualityReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", qualityReport.get("passed").getAsBoolean());
            obj.add("structureEnvelopeFacts", structureEnvelopeFacts);
            obj.add("qualityReport", qualityReport);
            obj.add("timingMs", structureEnvelopeFacts.getAsJsonObject("timingMs"));
            return obj;
        }
    }
}
