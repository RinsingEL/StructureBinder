package com.rinsing.geomantia.systems.realm_planning.application.reservation;

import com.google.gson.*;
import com.rinsing.geomantia.api.regions.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Immutable masks and separate, monotonic generation readiness, scoped to a world planning run. */
public final class RegionReservationStore {
    public static final String PLAN_FILE = "addon_region_reservations.json";
    public static final String READY_FILE = "addon_region_generation_ready.json";
    private static final String SCHEMA = "geomantia_region_reservations.v1";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    private RegionReservationStore() {}

    public record Snapshot(String dimensionId, List<ReservedRegion> regions, Set<String> readyIds) {
        public Snapshot {
            regions = List.copyOf(regions);
            readyIds = Set.copyOf(readyIds);
        }
        public boolean overlaps(RegionBounds bounds) { return regions.stream().anyMatch(r -> r.overlaps(bounds)); }
        public void requireFree(RegionBounds bounds) {
            for (var region : regions) if (region.overlaps(bounds))
                throw new IllegalArgumentException("ADDON_REGION_RESERVED: " + region.id());
        }
    }

    public static synchronized Snapshot read(Path run) throws IOException {
        Path path = run.resolve(PLAN_FILE);
        if (!Files.exists(path)) return new Snapshot("", List.of(), Set.of()); // Legacy runs.
        try {
            JsonObject json = readObject(path);
            if (!SCHEMA.equals(json.get("schema").getAsString())
                    || !surveyIdentity(run).equals(json.get("surveyIdentity").getAsString()))
                throw new IOException("ADDON_REGION_PLAN_STALE: " + run);
            String dimension = json.get("dimensionId").getAsString();
            List<ReservedRegion> regions = new ArrayList<>();
            for (JsonElement e : json.getAsJsonArray("regions")) regions.add(JSON.fromJson(e, ReservedRegion.class));
            validate(regions);
            Set<String> ready = new HashSet<>();
            Path readyPath = run.resolve(READY_FILE);
            if (Files.exists(readyPath)) {
                JsonObject state = readObject(readyPath);
                if (!identity(path).equals(state.get("planIdentity").getAsString()))
                    throw new IOException("ADDON_REGION_READINESS_STALE");
                for (JsonElement e : state.getAsJsonArray("readyIds")) ready.add(e.getAsString());
                if (!regions.stream().map(ReservedRegion::id).toList().containsAll(ready))
                    throw new IOException("ADDON_REGION_READINESS_UNKNOWN_ID");
            }
            return new Snapshot(dimension, regions, ready);
        } catch (RuntimeException e) { throw new IOException("ADDON_REGION_PLAN_INVALID: " + run, e); }
    }

    public static synchronized Snapshot create(Path run, String dimension, List<ReservedRegion> regions) throws IOException {
        if (Files.exists(run.resolve(PLAN_FILE))) throw new IllegalStateException("ADDON_REGION_PLAN_FROZEN");
        validate(regions);
        JsonObject json = new JsonObject();
        json.addProperty("schema", SCHEMA);
        json.addProperty("dimensionId", dimension);
        json.addProperty("surveyIdentity", surveyIdentity(run));
        json.add("regions", JSON.toJsonTree(regions));
        write(run.resolve(PLAN_FILE), json);
        return read(run);
    }

    public static synchronized void markReady(Path run, String id) throws IOException {
        if (!Files.isRegularFile(run.resolve(PLAN_FILE))) throw new IllegalStateException("ADDON_REGION_PLAN_NOT_READY");
        Snapshot snapshot = read(run);
        if (snapshot.regions().stream().noneMatch(r -> r.id().equals(id)))
            throw new IllegalArgumentException("ADDON_REGION_UNKNOWN: " + id);
        if (snapshot.readyIds().contains(id)) return;
        Set<String> ready = new TreeSet<>(snapshot.readyIds());
        ready.add(id);
        JsonObject json = new JsonObject();
        json.addProperty("planIdentity", identity(run.resolve(PLAN_FILE)));
        json.add("readyIds", JSON.toJsonTree(ready));
        write(run.resolve(READY_FILE), json);
    }

    public static void validate(List<ReservedRegion> regions) {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < regions.size(); i++) {
            var region = regions.get(i);
            if (!ids.add(region.id())) throw new IllegalArgumentException("ADDON_REGION_DUPLICATE: " + region.id());
            for (int j = 0; j < i; j++) for (var bounds : region.mask())
                if (regions.get(j).overlaps(bounds))
                    throw new IllegalArgumentException("ADDON_REGION_CONFLICT: " + region.id() + " / " + regions.get(j).id());
        }
    }

    private static JsonObject readObject(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static String identity(Path path) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String surveyIdentity(Path run) throws IOException {
        JsonObject source = readObject(run.resolve("world_survey_context.json"));
        JsonObject stable = new JsonObject();
        // Re-exporting the same sealed W changes timestamps and cache counters, not its planning identity.
        for (String key : List.of("surveyId", "dimensionId", "worldSeed", "worldBorderSizeBlocks", "cellStepBlocks",
                "configHash", "sealed", "gridOriginBlock", "gridSize", "scanBounds"))
            if (source.has(key)) stable.add(key, source.get(key));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(stable.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void write(Path path, JsonObject json) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "addon-regions-", ".tmp");
        try {
            Files.writeString(temp, JSON.toJson(json));
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
