package com.rinsing.geomantia.systems.city.application.queue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record CityDesignQueueConfig(boolean enabled, OrderingMode orderingMode) {
    public static final String SCHEMA = "geomantia_city_design_queue_config.v0.1";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public CityDesignQueueConfig {
        orderingMode = orderingMode == null ? OrderingMode.GLOBAL_RADIAL : orderingMode;
    }

    public static CityDesignQueueConfig defaults() {
        return new CityDesignQueueConfig(true, OrderingMode.GLOBAL_RADIAL);
    }

    public static CityDesignQueueConfig loadOrCreate(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            CityDesignQueueConfig defaults = defaults();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(defaults.asJson()));
            return defaults;
        }
        JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        if (!SCHEMA.equals(stringValue(json, "schema", ""))) {
            throw new IllegalArgumentException("CITY_DESIGN_QUEUE_CONFIG_SCHEMA_UNSUPPORTED");
        }
        return new CityDesignQueueConfig(booleanValue(json, "enabled", true),
                OrderingMode.fromContract(stringValue(json, "orderingMode", "global_radial")));
    }

    public JsonObject asJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schema", SCHEMA);
        json.addProperty("enabled", enabled);
        json.addProperty("orderingMode", orderingMode.contractName());
        return json;
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
    }

    public enum OrderingMode {
        GLOBAL_RADIAL("global_radial"),
        REALM_GROUPED("realm_grouped");

        private final String contractName;

        OrderingMode(String contractName) {
            this.contractName = contractName;
        }

        public String contractName() {
            return contractName;
        }

        public static OrderingMode fromContract(String value) {
            for (OrderingMode mode : values()) {
                if (mode.contractName.equalsIgnoreCase(value == null ? "" : value.trim())) return mode;
            }
            throw new IllegalArgumentException("CITY_DESIGN_QUEUE_ORDERING_MODE_INVALID: " + value);
        }
    }
}
