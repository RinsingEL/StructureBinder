package com.rinsing.geomantia.systems.realm_planning.application.access;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public final class PlanningAreaAccessPolicy {
    private static final Set<String> RELEASED_STATUSES = Set.of(
            "waiting_for_generation", "waiting_for_worldgen", "completed");

    private final Path debugRoot;
    private final PlanningAreaAccessConfig config;

    public PlanningAreaAccessPolicy(Path debugRoot, PlanningAreaAccessConfig config) {
        this.debugRoot = debugRoot.toAbsolutePath().normalize();
        this.config = config;
    }

    public Decision evaluate(String dimensionId, double blockX, double blockZ) {
        if (!config.enabled() || !config.managedDimensions().contains(dimensionId)) {
            return Decision.allowed("UNMANAGED_DIMENSION");
        }
        if (insideRadius(blockX, blockZ, 0, 0, config.initialActivityRadiusBlocks())) {
            return Decision.allowed("INITIAL_ACTIVITY_AREA");
        }
        DesignedArea area = findReleasedArea(dimensionId, blockX, blockZ);
        if (area != null) {
            return new Decision(true, "RELEASED_CITY_AREA", area.runId(), area.citySeedId());
        }
        return Decision.denied("PLANNING_AREA_NOT_RELEASED");
    }

    private DesignedArea findReleasedArea(String dimensionId, double blockX, double blockZ) {
        if (!Files.isDirectory(debugRoot)) return null;
        try (var runs = Files.list(debugRoot)) {
            return runs.filter(Files::isDirectory)
                    .map(runDir -> findReleasedArea(runDir, dimensionId, blockX, blockZ))
                    .filter(area -> area != null)
                    .findFirst().orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private DesignedArea findReleasedArea(Path runDir, String dimensionId, double blockX, double blockZ) {
        Path registryPath = runDir.resolve("city_seed_registry.json");
        if (!Files.isRegularFile(registryPath)) return null;
        try {
            JsonObject manifest = readObject(runDir.resolve("world_survey_manifest.json"));
            JsonObject manifestConfig = manifest != null && manifest.has("config")
                    && manifest.get("config").isJsonObject() ? manifest.getAsJsonObject("config") : new JsonObject();
            if (!dimensionId.equals(stringValue(manifestConfig, "dimensionId", "minecraft:overworld"))) return null;
            int step = intValue(manifestConfig, "cellStepBlocks", 128);
            JsonArray seeds = readObject(registryPath).getAsJsonArray("citySeeds");
            if (seeds == null) return null;
            for (var element : seeds) {
                if (!element.isJsonObject()) continue;
                JsonObject seed = element.getAsJsonObject();
                String citySeedId = stringValue(seed, "citySeedId", "");
                if (!released(runDir, citySeedId)) continue;
                JsonObject anchor = object(seed, "anchorBlock");
                int x = anchor.has("x") ? anchor.get("x").getAsInt()
                        : intValue(object(seed, "anchorGrid"), "x", 0) * step;
                int z = anchor.has("z") ? anchor.get("z").getAsInt()
                        : intValue(object(seed, "anchorGrid"), "z", 0) * step;
                int radius = Math.max(step, intValue(seed, "planningRadiusCells", 1) * step);
                if (insideRadius(blockX, blockZ, x, z, radius)) {
                    return new DesignedArea(runDir.getFileName().toString(), citySeedId);
                }
            }
        } catch (RuntimeException | IOException ignored) {
            return null;
        }
        return null;
    }

    private static boolean released(Path runDir, String citySeedId) throws IOException {
        String safeCity = citySeedId.replaceAll("[^A-Za-z0-9._-]", "_");
        Path manifestPath = runDir.resolve("city_test_runs").resolve(safeCity).resolve("test_run_manifest.json");
        JsonObject manifest = readObject(manifestPath);
        if (manifest != null && RELEASED_STATUSES.contains(stringValue(manifest, "status", ""))) return true;
        Path autoStatePath = runDir.resolve("automation").resolve("post_d4").resolve(safeCity + ".json");
        JsonObject autoState = readObject(autoStatePath);
        return autoState != null && RELEASED_STATUSES.contains(stringValue(autoState, "status", ""));
    }

    private static boolean insideRadius(double x, double z, int centerX, int centerZ, int radius) {
        double dx = x - centerX;
        double dz = z - centerZ;
        return dx * dx + dz * dz <= (double) radius * radius;
    }

    private static JsonObject readObject(Path path) throws IOException {
        return Files.isRegularFile(path)
                ? JsonParser.parseString(Files.readString(path)).getAsJsonObject() : null;
    }

    private static JsonObject object(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : new JsonObject();
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : fallback;
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsInt() : fallback;
    }

    public record Decision(boolean allowed, String reasonCode, String runId, String citySeedId) {
        static Decision allowed(String reasonCode) {
            return new Decision(true, reasonCode, "", "");
        }

        static Decision denied(String reasonCode) {
            return new Decision(false, reasonCode, "", "");
        }
    }

    private record DesignedArea(String runId, String citySeedId) {
    }
}
