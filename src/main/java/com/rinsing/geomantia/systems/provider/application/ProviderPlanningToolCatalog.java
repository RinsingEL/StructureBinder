package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/** Responses API definitions for the release-safe subset of the existing MCP planning tools. */
final class ProviderPlanningToolCatalog {
    private ProviderPlanningToolCatalog() {
    }

    static JsonArray definitions(List<String> allowedTools) {
        JsonArray result = new JsonArray();
        for (String name : allowedTools) result.add(definition(name));
        return result;
    }

    private static JsonObject definition(String name) {
        return switch (name) {
            case "realm_w_refresh" -> function(name,
                    "Run or resume the one sealed W survey for this world. The host locks runId. Use the requested "
                            + "origin-centered release defaults from the current planning state.",
                    object(properties(
                            "runId", string(), "planningRadiusBlocks", integer(), "cellStepBlocks", integer(),
                            "microSampleStrideBlocks", integer(), "localSlopeRadiusBlocks", integer(),
                            "preferGeneratorNativeTerrain", bool(), "qualityMode", enumeration("smoke", "strict"),
                            "resumePolicy", enumeration("use_cache", "rescan", "use_cache_strict"),
                            "centerBlockX", integer(), "centerBlockZ", integer(), "dimensionId", string(),
                            "worldTheme", object())));
            case "realm_t1_prepare" -> function(name,
                    "Create all RealmProfiles together from the sealed W overview and attached map previews. "
                            + "Use distinct culture, industry, material and landform preferences.",
                    object(properties("runId", string(), "realmProfiles", array(object()),
                            "realmCount", integer(), "targetContinentId", string(), "allowAiDraftProfile", bool()),
                            "realmProfiles", "realmCount"));
            case "realm_t2_select_coordinate" -> function(name,
                    "Submit the frozen realm_t2 Patch selection for the active realm. Normal Provider operation "
                            + "must use patchSelectionRef, not invented grid coordinates.",
                    object(properties("runId", string(), "realmId", string(), "patchSelectionRef", string(),
                            "reason", string(), "selectedBy", enumeration("ai"), "allowSnap", bool()),
                            "patchSelectionRef", "reason"));
            case "realm_t3_expand" -> function(name,
                    "Expand every T2-complete realm together exactly once. Never call this separately per realm.",
                    object(properties("runId", string(), "normalizationGroup", string(),
                            "allowUnclaimedLand", bool(), "qualityMode", enumeration("smoke", "strict"),
                            "expansionModel", enumeration("quota_frontier", "action_budget"))));
            case "realm_t4_patch_planning_create" -> function(name,
                    "Create the artifact-backed T4 planning session for only the active realm.",
                    object(properties("runId", string(), "realmId", string(), "planningSessionId", string())));
            case "realm_t4_patch_planning_select_capital" -> function(name,
                    "Turn one reviewed realm_t4 patchSelectionRef into the realm's unique capital.",
                    t4SeedSchema(false));
            case "realm_t4_patch_planning_add_city" -> function(name,
                    "Add one non-capital city from a distinct reviewed realm_t4 patchSelectionRef. Use a stable "
                            + "citySeedId derived from the realm and function.",
                    t4SeedSchema(true));
            case "realm_t4_patch_planning_finalize" -> function(name,
                    "Finalize the active realm after its required city seeds are present. The existing service merges "
                            + "the realm into the registry and creates the City queue only when all realms are covered.",
                    object(properties("runId", string(), "planningSessionId", string(),
                            "cityQueueOrderingMode", enumeration("global_radial", "realm_grouped")),
                            "planningSessionId"));
            case "city_design_queue_refresh" -> function(name,
                    "Build or reconcile the existing persistent City queue after all realms completed T4.",
                    object(properties("runId", string(),
                            "orderingMode", enumeration("global_radial", "realm_grouped"))));
            case "city_design_queue_status" -> function(name,
                    "Read the current persistent city queue. Follow its status, reasonCode and nextAction.",
                    object(properties("runId", string()), "runId"));
            case "city_plan_d3" -> function(name,
                    "Build the current city's D3 terrain review. The host supplies the active run and city. "
                            + "Use the returned patchExplorer session and nextAction; do not invent patch IDs.",
                    object(properties(
                            "runId", string(), "citySeedId", string(),
                            "preferGeneratorNativeTerrain", bool(), "patchScanPaddingBlocks", number())));
            case "city_review_d3_site" -> function(name,
                    "Review the current capital site from returned D3 evidence and preview. Accept only if the "
                            + "terrain supports the intended capital; otherwise request reselection.",
                    object(properties(
                            "runId", string(), "citySeedId", string(),
                            "decision", enumeration("accept_selected_site", "reselect_required"),
                            "decisionReason", string(), "reviewedBy", enumeration("ai")),
                            "decision", "decisionReason"));
            case "patch_explorer_open" -> function(name,
                    "Open or restore Patch Explorer for the active host-locked realm_t2, realm_t4 or city_d4 scope. "
                            + "Inspect returned overview images; never invent scope identities or patch IDs.",
                    object(properties(
                            "runId", string(), "scopeType", enumeration("realm_t2", "realm_t4", "city_d4"),
                            "scopeId", string(), "realmId", string(), "citySeedId", string(), "sessionId", string(),
                            "preferGeneratorNativeTerrain", bool())));
            case "patch_explorer_show_candidates" -> function(name,
                    "Show Top Patch candidates for landform types you choose from the active session. Inspect the "
                            + "attached preview, area and capacity evidence before selecting.",
                    object(properties(
                            "runId", string(), "sessionId", string(),
                            "interestTypes", array(string()), "page", integer(), "pageToken", string(),
                            "pageSize", integer()),
                            "sessionId", "interestTypes"));
            case "patch_explorer_select_candidate" -> function(name,
                    "Freeze one candidate already shown in this Patch Explorer session. Use the exact candidateId "
                            + "and explain the terrain/profile evidence supporting it.",
                    object(properties("runId", string(), "sessionId", string(), "candidateId", string(),
                            "selectionReason", string()), "sessionId", "candidateId", "selectionReason"));
            case "city_prepare_d4_blueprint_context" -> function(name,
                    "Freeze the complete D4 decision context after Patch review. The host injects installed "
                            + "TerraSense profiles, template catalog and Blueprint reference catalog; never pass paths.",
                    object(properties("runId", string(), "citySeedId", string())));
            case "city_submit_d4_blueprint" -> function(name,
                    "Submit one complete CityBlueprint using only the returned cityBlueprintContext. Root fields are "
                            + "schema, cityId, sourceD3Ref, catalogSnapshotRef, generationSeed, designIntent, "
                            + "styleProfile, groups, arrayCompositions, relations, roadProfile, surfaceDetailProfile "
                            + "and outdoorPlan. Use exact catalog refs, reviewed Patch refs and supported enum values. "
                            + "On rejection revise from the returned path/issues; on compile failure respect failureCount.",
                    submitSchema());
            case "city_post_d4_auto_compile_status" -> function(name,
                    "Read the current city's deterministic post-D4 queue. If waiting_for_generation, stop. If "
                            + "needs_agent, use only workflowResponse and returned artifacts to decide whether to revise.",
                    object(properties("runId", string(), "citySeedId", string())));
            case "city_post_d4_auto_compile_retry" -> function(name,
                    "Retry deterministic post-D4 work only when the Blueprint is unchanged and the returned evidence "
                            + "says the program/environment issue was fixed. Never use this to bypass a Blueprint failure.",
                    object(properties("runId", string(), "citySeedId", string())));
            default -> throw new IllegalArgumentException("Unsupported Provider planning tool: " + name);
        };
    }

    private static JsonObject t4SeedSchema(boolean addCity) {
        JsonObject values = properties("runId", string(), "planningSessionId", string(),
                "patchSelectionRef", string(), "candidateRangeCells", integer(), "minimumAreaBlocks", integer(),
                "subregionId", string(), "requiredConditions", array(string()), "coreFunctions", array(string()),
                "selectionReason", string());
        if (addCity) {
            values.add("citySeedId", string());
            values.add("role", string());
            values.add("theoreticalScale", enumeration("large_city", "city", "town", "village", "outpost"));
            values.add("satelliteOf", string());
            values.add("trigger", string());
            return object(values, "planningSessionId", "patchSelectionRef", "citySeedId", "role",
                    "selectionReason");
        }
        return object(values, "planningSessionId", "patchSelectionRef", "selectionReason");
    }

    private static JsonObject submitSchema() {
        JsonObject blueprintProperties = properties(
                "schema", enumeration("city_blueprint"), "cityId", string(), "sourceD3Ref", object(),
                "catalogSnapshotRef", object(), "generationSeed", integer(), "designIntent", object(),
                "styleProfile", object(), "groups", array(object()), "arrayCompositions", array(object()),
                "relations", array(object()), "roadProfile", object(), "surfaceDetailProfile", object(),
                "outdoorPlan", object());
        JsonObject blueprint = object(blueprintProperties,
                "schema", "cityId", "sourceD3Ref", "catalogSnapshotRef", "generationSeed", "designIntent",
                "styleProfile", "groups", "arrayCompositions", "relations", "roadProfile",
                "surfaceDetailProfile", "outdoorPlan");
        return object(properties(
                "runId", string(), "citySeedId", string(), "contextId", string(),
                "cityBlueprint", blueprint, "autoAdvanceAfterD4", bool()),
                "contextId", "cityBlueprint");
    }

    private static JsonObject function(String name, String description, JsonObject parameters) {
        JsonObject result = new JsonObject();
        result.addProperty("type", "function");
        result.addProperty("name", name);
        result.addProperty("description", description);
        result.add("parameters", parameters);
        return result;
    }

    private static JsonObject object() {
        JsonObject result = new JsonObject();
        result.addProperty("type", "object");
        return result;
    }

    private static JsonObject object(JsonObject properties, String... requiredNames) {
        JsonObject result = object();
        result.addProperty("additionalProperties", false);
        result.add("properties", properties);
        if (requiredNames.length > 0) {
            JsonArray required = new JsonArray();
            for (String name : requiredNames) required.add(name);
            result.add("required", required);
        }
        return result;
    }

    private static JsonObject properties(Object... values) {
        JsonObject result = new JsonObject();
        for (int index = 0; index < values.length; index += 2) {
            result.add((String) values[index], (JsonObject) values[index + 1]);
        }
        return result;
    }

    private static JsonObject string() {
        return typed("string");
    }

    private static JsonObject number() {
        return typed("number");
    }

    private static JsonObject integer() {
        return typed("integer");
    }

    private static JsonObject bool() {
        return typed("boolean");
    }

    private static JsonObject array(JsonObject items) {
        JsonObject result = typed("array");
        result.add("items", items);
        return result;
    }

    private static JsonObject enumeration(String... values) {
        JsonObject result = string();
        JsonArray allowed = new JsonArray();
        for (String value : values) allowed.add(value);
        result.add("enum", allowed);
        return result;
    }

    private static JsonObject typed(String type) {
        JsonObject result = new JsonObject();
        result.addProperty("type", type);
        return result;
    }
}
