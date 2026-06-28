package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class CityStructureEnvelopeFacts {
    private final Map<String, Fact> byStructureId;

    private CityStructureEnvelopeFacts(Map<String, Fact> byStructureId) {
        this.byStructureId = Map.copyOf(byStructureId);
    }

    public static CityStructureEnvelopeFacts empty() {
        return new CityStructureEnvelopeFacts(Map.of());
    }

    public static CityStructureEnvelopeFacts load(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return empty();
        }
        JsonObject obj = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        Map<String, Fact> facts = new LinkedHashMap<>();
        JsonArray structures = obj.has("structures") && obj.get("structures").isJsonArray()
                ? obj.getAsJsonArray("structures") : new JsonArray();
        for (JsonElement elem : structures) {
            if (!elem.isJsonObject()) {
                continue;
            }
            Fact fact = Fact.from(elem.getAsJsonObject());
            facts.put(fact.structureId(), fact);
        }
        return new CityStructureEnvelopeFacts(facts);
    }

    public Optional<Fact> validFactFor(CityStructureProfileCatalog.StructureProfile profile) {
        Fact fact = byStructureId.get(profile.structureId());
        if (fact == null) {
            return Optional.empty();
        }
        String expected = CityStructureEnvelopeProfiler.profileHash(profile);
        return fact.profileHash().equals(expected) ? Optional.of(fact) : Optional.empty();
    }

    public Optional<Fact> factFor(String structureId) {
        return Optional.ofNullable(byStructureId.get(structureId));
    }

    public boolean emptyFacts() {
        return byStructureId.isEmpty();
    }

    public record Fact(String structureId, String profileHash, String structureConfigHash, String sourcePackHash,
                       int sampleCount, int validSampleCount, double invalidRatio,
                       BlockBounds p95Envelope, BlockBounds p99Envelope, BlockBounds maxObservedEnvelope) {
        static Fact from(JsonObject obj) {
            return new Fact(
                    stringValue(obj, "structureId", ""),
                    stringValue(obj, "profileHash", ""),
                    stringValue(obj, "structureConfigHash", ""),
                    stringValue(obj, "sourcePackHash", ""),
                    intValue(obj, "sampleCount", 0),
                    intValue(obj, "validSampleCount", 0),
                    doubleValue(obj, "invalidRatio", 1.0),
                    bounds(obj, "localEnvelopeP95"),
                    bounds(obj, "localEnvelopeP99"),
                    bounds(obj, "maxObservedEnvelope"));
        }

        public JsonObject asSummaryJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("structureId", structureId);
            obj.addProperty("profileHash", profileHash);
            obj.addProperty("structureConfigHash", structureConfigHash);
            obj.addProperty("sourcePackHash", sourcePackHash);
            obj.addProperty("sampleCount", sampleCount);
            obj.addProperty("validSampleCount", validSampleCount);
            obj.addProperty("invalidRatio", invalidRatio);
            obj.add("localEnvelopeP95", boundsJson(p95Envelope));
            obj.add("localEnvelopeP99", boundsJson(p99Envelope));
            obj.add("maxObservedEnvelope", boundsJson(maxObservedEnvelope));
            return obj;
        }
    }

    private static BlockBounds bounds(JsonObject obj, String key) {
        JsonObject source = obj != null && obj.has(key) && obj.get(key).isJsonObject()
                ? obj.getAsJsonObject(key) : new JsonObject();
        return new BlockBounds(
                intValue(source, "minX", 0),
                intValue(source, "minZ", 0),
                intValue(source, "maxX", 0),
                intValue(source, "maxZ", 0));
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private static String stringValue(JsonObject obj, String key, String defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : defaultValue;
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : defaultValue;
    }

    private static double doubleValue(JsonObject obj, String key, double defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsDouble() : defaultValue;
    }
}
