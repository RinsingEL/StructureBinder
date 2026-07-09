package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        return profile(baseDirectory, terraSenseProfileSource, structureIds, sampleCount, sampler,
                CacheOptions.disabled());
    }

    public Result profile(Path baseDirectory,
                          JsonObject terraSenseProfileSource,
                          List<String> structureIds,
                          int sampleCount,
                          StructureEnvelopeSampler sampler,
                          CacheOptions cacheOptions) throws IOException {
        long started = System.nanoTime();
        if (sampler == null) {
            throw new IllegalArgumentException("StructureEnvelopeSampler is required.");
        }
        CacheOptions effectiveCache = cacheOptions == null ? CacheOptions.disabled() : cacheOptions;
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
            structures.add(profileOne(profile, requestedSamples, sampler, effectiveCache));
        }

        JsonObject facts = new JsonObject();
        facts.addProperty("schemaVersion", FACTS_SCHEMA);
        facts.addProperty("sampleCountRequested", requestedSamples);
        facts.add("sourceTerraSenseProfileSource", terraSenseProfileSource.deepCopy());
        facts.add("profileCache", cacheSummary(effectiveCache, structures));
        facts.add("structures", structures);
        JsonObject quality = new JsonObject();
        quality.addProperty("passed", hardBlocks.isEmpty());
        quality.addProperty("score", hardBlocks.isEmpty() ? 100 : 0);
        quality.add("hardBlocks", hardBlocks);
        quality.add("warnings", warnings);
        quality.add("needsReview", needsReview);
        JsonObject metrics = new JsonObject();
        metrics.addProperty("structureCount", structures.size());
        addCacheMetrics(metrics, structures);
        quality.add("metrics", metrics);
        facts.add("quality", quality);
        facts.add("timingMs", timing(started));
        return new Result(facts, quality);
    }

    private JsonObject profileOne(CityStructureProfileCatalog.StructureProfile profile,
                                  int sampleCount,
                                  StructureEnvelopeSampler sampler,
                                  CacheOptions cacheOptions) throws IOException {
        if (!cacheOptions.enabled()) {
            JsonObject fresh = profileOneFresh(profile, sampleCount, sampler);
            annotateCacheNotUsed(fresh);
            return fresh;
        }
        Files.createDirectories(cacheOptions.cacheDirectory());
        Path cachePath = cachePath(cacheOptions, profile.structureId());
        CacheIdentity expectedIdentity = sampler.cacheIdentity(profile);
        String expectedCacheKey = expectedIdentity.hasStableInputs()
                ? cacheKey(profile, sampleCount, expectedIdentity, cacheOptions.contextProfileHash())
                : "";
        String recomputeReason = "CACHE_MISS";
        if (!cacheOptions.forceRefresh() && Files.exists(cachePath)) {
            JsonObject cached = JsonParser.parseString(Files.readString(cachePath)).getAsJsonObject();
            if (!expectedCacheKey.isBlank()
                    && expectedCacheKey.equals(stringValue(cached, "cacheKey", ""))
                    && sampleCount == intValue(cached, "sampleCount", -1)
                    && profileHash(profile).equals(stringValue(cached, "profileHash", ""))) {
                JsonObject hit = cached.deepCopy();
                hit.addProperty("cacheStatus", "hit");
                hit.addProperty("cacheReason", "CACHE_KEY_MATCH");
                hit.addProperty("cachePath", cachePath.toAbsolutePath().toString());
                return hit;
            }
            recomputeReason = "CACHE_KEY_STALE";
        } else if (cacheOptions.forceRefresh()) {
            recomputeReason = "FORCE_REFRESH";
        }

        JsonObject fresh = profileOneFresh(profile, sampleCount, sampler);
        CacheIdentity finalIdentity = CacheIdentity.fromProfileFact(fresh, expectedIdentity.generationContextHash());
        String finalCacheKey = cacheKey(profile, sampleCount, finalIdentity, cacheOptions.contextProfileHash());
        JsonObject identity = cacheIdentityJson(profile, sampleCount, finalIdentity,
                cacheOptions.contextProfileHash());
        fresh.add("cacheIdentity", identity);
        fresh.addProperty("cacheKey", finalCacheKey);
        fresh.addProperty("cacheStatus", "CACHE_KEY_STALE".equals(recomputeReason)
                ? "stale_recomputed" : "miss_recomputed");
        fresh.addProperty("cacheReason", recomputeReason);
        fresh.addProperty("cachePath", cachePath.toAbsolutePath().toString());
        Files.writeString(cachePath, CityJson.GSON.toJson(fresh));
        return fresh;
    }

    private JsonObject profileOneFresh(CityStructureProfileCatalog.StructureProfile profile,
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

    private static void annotateCacheNotUsed(JsonObject obj) {
        obj.addProperty("cacheStatus", "not_used");
        obj.addProperty("cacheReason", "CACHE_DISABLED");
    }

    private static void addCacheMetrics(JsonObject metrics, JsonArray structures) {
        int hit = 0;
        int miss = 0;
        int stale = 0;
        int notUsed = 0;
        for (JsonElement elem : structures) {
            if (!elem.isJsonObject()) {
                continue;
            }
            String status = stringValue(elem.getAsJsonObject(), "cacheStatus", "");
            switch (status) {
                case "hit" -> hit++;
                case "miss_recomputed" -> miss++;
                case "stale_recomputed" -> stale++;
                case "not_used" -> notUsed++;
                default -> {
                }
            }
        }
        metrics.addProperty("cacheHitCount", hit);
        metrics.addProperty("cacheMissRecomputedCount", miss);
        metrics.addProperty("cacheStaleRecomputedCount", stale);
        metrics.addProperty("cacheNotUsedCount", notUsed);
    }

    private static JsonObject cacheSummary(CacheOptions cacheOptions, JsonArray structures) {
        JsonObject summary = new JsonObject();
        summary.addProperty("enabled", cacheOptions.enabled());
        summary.addProperty("cacheDirectory", cacheOptions.enabled()
                ? cacheOptions.cacheDirectory().toAbsolutePath().toString() : "");
        summary.addProperty("forceRefresh", cacheOptions.forceRefresh());
        JsonObject metrics = new JsonObject();
        addCacheMetrics(metrics, structures);
        summary.add("metrics", metrics);
        return summary;
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

    public static String cacheKey(CityStructureProfileCatalog.StructureProfile profile,
                                  int sampleCount,
                                  CacheIdentity identity,
                                  String contextProfileHash) {
        return sha256(CityJson.GSON.toJson(cacheIdentityJson(profile, sampleCount, identity, contextProfileHash)));
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

    private static JsonObject cacheIdentityJson(CityStructureProfileCatalog.StructureProfile profile,
                                                int sampleCount,
                                                CacheIdentity identity,
                                                String contextProfileHash) {
        CacheIdentity safeIdentity = identity == null ? CacheIdentity.unknown() : identity;
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", "city_structure_profile_cache_key.v0.1");
        obj.addProperty("structureId", profile.structureId());
        obj.addProperty("profileHash", profileHash(profile));
        obj.addProperty("structureConfigHash", safeIdentity.structureConfigHash());
        obj.addProperty("sourcePackHash", safeIdentity.sourcePackHash());
        obj.addProperty("generationConfigHash", generationConfigHash(profile, safeIdentity.structureConfigHash()));
        obj.addProperty("generationContextHash", safeIdentity.generationContextHash());
        obj.addProperty("d2ContextProfileHash", contextProfileHash == null ? "" : contextProfileHash);
        obj.addProperty("sampleCount", sampleCount);
        return obj;
    }

    private static Path cachePath(CacheOptions options, String structureId) {
        return options.cacheDirectory().resolve("structure_" + sha256(structureId).substring(0, 24) + ".json");
    }

    private static String stringValue(JsonObject obj, String key, String defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : defaultValue;
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : defaultValue;
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

        default CacheIdentity cacheIdentity(CityStructureProfileCatalog.StructureProfile profile) {
            return CacheIdentity.unknown();
        }
    }

    public record CacheOptions(Path cacheDirectory, String contextProfileHash, boolean forceRefresh) {
        public static CacheOptions disabled() {
            return new CacheOptions(null, "", false);
        }

        public static CacheOptions enabled(Path cacheDirectory, String contextProfileHash, boolean forceRefresh) {
            if (cacheDirectory == null) {
                return disabled();
            }
            return new CacheOptions(cacheDirectory, contextProfileHash == null ? "" : contextProfileHash,
                    forceRefresh);
        }

        boolean enabled() {
            return cacheDirectory != null;
        }
    }

    public record CacheIdentity(String structureConfigHash, String sourcePackHash, String generationContextHash) {
        public CacheIdentity {
            structureConfigHash = structureConfigHash == null ? "" : structureConfigHash;
            sourcePackHash = sourcePackHash == null ? "" : sourcePackHash;
            generationContextHash = generationContextHash == null ? "" : generationContextHash;
        }

        public static CacheIdentity unknown() {
            return new CacheIdentity("", "", "");
        }

        static CacheIdentity fromProfileFact(JsonObject fact, String generationContextHash) {
            return new CacheIdentity(
                    stringValue(fact, "structureConfigHash", ""),
                    stringValue(fact, "sourcePackHash", ""),
                    generationContextHash);
        }

        boolean hasStableInputs() {
            return !structureConfigHash.isBlank() && !sourcePackHash.isBlank();
        }
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
