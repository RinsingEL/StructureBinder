package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/** Exact-input checkpoints for successful array searches; never treats a checkpoint as final acceptance. */
final class CityCandidateMemo {
    // Terrain/catalog bytes are already covered by contextId. Do not serialize the entire GIS for every slot.
    private static final Gson STATE_GSON = new GsonBuilder().addSerializationExclusionStrategy(new ExclusionStrategy() {
        public boolean shouldSkipClass(Class<?> type) { return false; }
        public boolean shouldSkipField(FieldAttributes field) {
            return java.util.Set.of("patches", "connectionPatches", "patchByRef").contains(field.getName());
        }
    }).create();
    static JsonElement stateIdentity(Object state) { return STATE_GSON.toJsonTree(state); }
    private final Path directory;
    private final String context;
    private int writes;
    private int hits;
    CityCandidateMemo(Path directory, String context) { this.directory = directory; this.context = context; }

    String key(JsonObject inputs) {
        return hash("candidate-memo-v1\n" + context + "\n" + inputs);
    }

    CityStructureArrayCandidatePlanner.Result load(String key) {
        Path file = directory.resolve(key + ".json");
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 1_048_576) return null;
            JsonObject value = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            JsonObject payload = value.getAsJsonObject("payload");
            if (!key.equals(value.get("inputHash").getAsString())
                    || !hash(payload.toString()).equals(value.get("payloadHash").getAsString())) return null;
            hits++;
            return new CityStructureArrayCandidatePlanner.Result(payload.getAsJsonObject("plan"),
                    payload.getAsJsonObject("candidates"), payload.getAsJsonObject("quality"));
        } catch (IOException | RuntimeException invalid) { return null; }
    }

    void save(String key, CityStructureArrayCandidatePlanner.Result result) {
        if (result.arrayCandidateSet().getAsJsonArray("arrayCandidates").isEmpty() || writes >= 64) return;
        JsonObject payload = new JsonObject();
        payload.add("plan", result.arrayCandidatePlan()); payload.add("candidates", result.arrayCandidateSet());
        payload.add("quality", result.qualityReport());
        JsonObject value = new JsonObject();
        value.addProperty("inputHash", key); value.addProperty("payloadHash", hash(payload.toString())); value.add("payload", payload);
        String raw = value.toString();
        if (raw.getBytes(StandardCharsets.UTF_8).length > 1_048_576) return;
        try {
            Files.createDirectories(directory);
            if (Files.exists(directory.resolve(key + ".json"))) return;
            try (var files = Files.list(directory)) { if (files.limit(128).count() >= 128) return; }
            Files.writeString(directory.resolve(key + ".json"), raw, StandardOpenOption.CREATE_NEW);
            writes++;
        } catch (IOException unavailable) { /* Optional optimization; normal compilation remains authoritative. */ }
    }

    JsonObject statistics() {
        JsonObject value = new JsonObject();
        value.addProperty("schema", "city_array_checkpoint_statistics");
        value.addProperty("reusedSearches", hits); value.addProperty("savedSearches", writes);
        value.addProperty("policy", "EXACT_INPUT_DEPENDENCIES_FINAL_SAFETY_ALWAYS_RECHECKED");
        return value;
    }

    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
