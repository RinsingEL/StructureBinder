package com.rinsing.geomantia.api.planning;

import com.google.gson.JsonObject;
import java.util.List;

/**
 * Trusted Java addon, not executable model output. Metadata is frozen during server registration.
 * Callbacks run on the server thread and must not block on AI/network work.
 * Persist addon results before isComplete becomes true. Execute must tolerate retry after a crash.
 */
public interface PlanningExtension {
    int API_VERSION = 1;
    String id();
    String version();
    String title();
    default PlanningHook hook() { return PlanningHook.AFTER_CITY_PLANNING; }
    /** Required extension IDs at the same hook, completed first for this city. */
    default List<String> after() { return List.of(); }
    List<PlanningTool> tools();
    default boolean applies(PlanningExtensionContext context) throws Exception { return true; }
    /** Read durable addon state for this exact city/taskRevision, not an AI completion claim. */
    boolean isComplete(PlanningExtensionContext context) throws Exception;
    /** Concise instructions and facts for the current task; full host artifacts remain browsable. */
    JsonObject prepare(PlanningExtensionContext context) throws Exception;
    /** Validate business rules before mutation. Return ok:false/error for correctable input. */
    JsonObject execute(PlanningExtensionContext context, String tool, JsonObject arguments) throws Exception;
}
