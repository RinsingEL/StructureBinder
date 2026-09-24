package com.rinsing.geomantia.systems.provider.application;
import com.google.gson.*;
import java.util.List;
@FunctionalInterface
public interface PlanningToolExecutor {
    JsonElement execute(String toolName, JsonObject arguments) throws Exception;
    default JsonArray definitions(List<String> tools) { return ProviderPlanningToolCatalog.definitions(tools); }
}
