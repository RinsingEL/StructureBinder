package com.rinsing.geomantia.api.planning;

import com.google.gson.JsonObject;
import java.util.Objects;

/** A provider-compatible tool name, description and JSON object schema. */
public record PlanningTool(String name, String description, JsonObject parameters) {
    public PlanningTool {
        Objects.requireNonNull(name); Objects.requireNonNull(description);
        parameters = Objects.requireNonNull(parameters).deepCopy();
    }
    @Override public JsonObject parameters() { return parameters.deepCopy(); }
}
