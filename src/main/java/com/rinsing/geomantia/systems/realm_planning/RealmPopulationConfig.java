package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;

/** World-owned planning quantities; city counts include the capital. */
public record RealmPopulationConfig(int realmCount, int minCitiesPerRealm, int maxCitiesPerRealm) {
    public RealmPopulationConfig {
        if (realmCount < 1 || realmCount > 12)
            throw new IllegalArgumentException("REALM_COUNT_CONFIG: realmCount must be an integer from 1 to 12.");
        if (minCitiesPerRealm < 1 || maxCitiesPerRealm < minCitiesPerRealm)
            throw new IllegalArgumentException("CITY_COUNT_CONFIG: require 1 <= minCitiesPerRealm <= maxCitiesPerRealm; counts include the capital.");
    }
    public static RealmPopulationConfig defaults() { return new RealmPopulationConfig(3, 1, 4); }
    public static RealmPopulationConfig load(Path debugRoot) throws IOException {
        Path path = debugRoot.toAbsolutePath().normalize().getParent().resolve("config/geomantia/realm_planning.json");
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(defaults().asJson()));
            return defaults();
        }
        return fromJson(JsonParser.parseString(Files.readString(path)).getAsJsonObject());
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
