package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;

/** Global defaults copied once into each world; city counts include the capital. */
public record RealmPopulationConfig(int realmCount, int minCitiesPerRealm, int maxCitiesPerRealm) {
    public RealmPopulationConfig {
        if (realmCount < 1 || realmCount > 12)
            throw new IllegalArgumentException("REALM_COUNT_CONFIG: realmCount must be an integer from 1 to 12.");
        if (minCitiesPerRealm < 1 || maxCitiesPerRealm < minCitiesPerRealm)
            throw new IllegalArgumentException("CITY_COUNT_CONFIG: require 1 <= minCitiesPerRealm <= maxCitiesPerRealm; counts include the capital.");
    }
    public static RealmPopulationConfig defaults() { return new RealmPopulationConfig(3, 1, 4); }
    /** Called during mod setup, before the player creates or opens a world. Never overwrites user edits. */
    public static Path ensureGlobalConfig(Path configDirectory) throws IOException {
        Path path = configDirectory.resolve("geomantia/realm_planning.json");
        writeIfAbsent(path, defaults());
        return path;
    }
    public static RealmPopulationConfig load(Path debugRoot) throws IOException {
        return load(debugRoot, net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());
    }
    static synchronized RealmPopulationConfig load(Path debugRoot, Path configDirectory) throws IOException {
        Path snapshot = debugRoot.toAbsolutePath().normalize().getParent()
                .resolve("config/geomantia/realm_planning.json");
        // Includes files created by the previous world-local implementation.
        if (Files.exists(snapshot)) return read(snapshot);
        RealmPopulationConfig selected = configDirectory == null ? defaults() : read(ensureGlobalConfig(configDirectory));
        writeIfAbsent(snapshot, selected);
        return read(snapshot);
    }
    private static RealmPopulationConfig read(Path path) throws IOException {
        try {
            return fromJson(JsonParser.parseString(Files.readString(path)).getAsJsonObject());
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("REALM_PLANNING_CONFIG: invalid configuration at " + path
                    + "; " + failure.getMessage(), failure);
        }
    }
    private static void writeIfAbsent(Path path, RealmPopulationConfig config) throws IOException {
        Files.createDirectories(path.getParent());
        try {
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(config.asJson()) + "\n",
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException existing) {
            // Startup or another caller already supplied the file; its values remain authoritative.
        }
    }
    public static RealmPopulationConfig fromJson(JsonObject json) {
        var d = defaults();
        return new RealmPopulationConfig(integer(json,"realmCount",d.realmCount),
                integer(json,"minCitiesPerRealm",d.minCitiesPerRealm), integer(json,"maxCitiesPerRealm",d.maxCitiesPerRealm));
    }
    private static int integer(JsonObject json, String key, int fallback) {
        if (!json.has(key)) return fallback;
        try {
            var value = json.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            return value.getAsBigDecimal().intValueExact();
        } catch (RuntimeException failure) { throw new IllegalArgumentException("REALM_PLANNING_CONFIG: " + key + " must be an integer.", failure); }
    }
    public JsonObject asJson() {
        JsonObject json = new JsonObject();
        json.addProperty("realmCount",realmCount);
        json.addProperty("minCitiesPerRealm",minCitiesPerRealm);
        json.addProperty("maxCitiesPerRealm",maxCitiesPerRealm);
        return json;
    }
    public void requireRealmCount(int actual) {
        if (actual != realmCount) throw new IllegalArgumentException("REALM_COUNT_CONFIG: expected " + realmCount
                + " realm profiles, received " + actual + "; add or remove realm profiles to match the configured total.");
    }
    public void requireCityCount(int actual, boolean finalizing) {
        if (actual > maxCitiesPerRealm || finalizing && actual < minCitiesPerRealm)
            throw new IllegalArgumentException("CITY_COUNT_CONFIG: configured range " + minCitiesPerRealm + ".." + maxCitiesPerRealm
                    + " including the capital; current/proposed count=" + actual
                    + (actual > maxCitiesPerRealm ? ". Do not add more cities." : ". Select at least " + (minCitiesPerRealm-actual) + " more suitable city sites before finalizing."));
    }
}
