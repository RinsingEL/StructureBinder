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
                    "Run or resume the one sealed W survey for this world. The host locks runId and the complete "
                            + "origin-centered survey range from config/geomantia/world_survey.json. Never choose "
                            + "or override W range parameters.",
                    object(properties(
                            "runId", string(), "cellStepBlocks", integer(),
                            "microSampleStrideBlocks", integer(), "localSlopeRadiusBlocks", integer(),
                            "preferGeneratorNativeTerrain", bool(), "qualityMode", enumeration("smoke", "strict"),
                            "resumePolicy", enumeration("use_cache", "rescan", "use_cache_strict"),
                            "worldTheme", object())));
            case "realm_t1_prepare" -> function(name,
                    "Create all RealmProfiles together from the sealed W overview and attached map previews. "
                            + "Use distinct culture, industry, material and landform preferences.",
                    object(properties("runId", string(), "realmProfiles", array(com.rinsing.geomantia.systems.realm_planning.RealmProfileInput.schema()),
                            "realmCount", integer()),
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
                    "Choose the unique capital using the displayed sessionId + candidateId and a selectionReason. "
                            + "The host freezes and commits this decision. Existing patchSelectionRef is also accepted as an alternative.",
                    t4SeedSchema(false));
            case "realm_t4_patch_planning_add_city" -> function(name,
                    "Add a non-capital city using a displayed sessionId + candidateId and selectionReason; the host freezes it. "
                            + "Use a stable citySeedId derived from the realm and function.",
                    t4SeedSchema(true));
            case "realm_t4_patch_planning_finalize" -> function(name,
                    "Finalize the active realm after its required city seeds are present. The existing service merges "
                            + "the realm into the registry; this realm's cities can start before other realms finish T4. Global T2/T3 must already be complete.",
                    object(properties("runId", string(), "planningSessionId", string(),
                            "cityQueueOrderingMode", enumeration("global_radial", "realm_grouped")),
                            "planningSessionId"));
            case "city_design_queue_refresh" -> function(name,
                    "Build or reconcile the persistent City queue from the currently finalized T4 realm registries.",
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
            case "city_inspect_d3_patches" -> function(name,
                    "Optional read-only terrain evidence for the current city only. Returns complete patch records "
                            + "in original order, with optional landformType or exact landformPatchId filter. "
                            + "page starts at 0; pageSize defaults to 8, maximum 16. Do not read all pages mechanically.",
                    object(properties("page", integer(), "pageSize", integer(),
                            "landformType", string(), "landformPatchId", string())));
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
                    "Submit a CityBlueprint, or use blueprintPatch (replace-only JSON Pointer operations) with "
                            + "baseBlueprintHash (accepted) or baseDraftHash (rejected draft) from revisionEvidence to change only affected fields. Never send both hashes. Exactly one input is allowed. "
                            + "The host fills omitted schema/cityId/sourceD3Ref/catalogSnapshotRef/generationSeed; conflicting explicit identities are rejected. "
                            + "proportionMode=RELATIVE_WEIGHTS normalizes group, spaceComposition and landscape role shares before the unchanged author validation. "
                            + "Patches use EXACT_SHARES only. Canonical root fields are "
                            + "schema, cityId, sourceD3Ref, catalogSnapshotRef, generationSeed, designIntent, "
                            + "styleProfile, groups, arrayCompositions, relations, roadProfile, surfaceDetailProfile "
                            + "and outdoorPlan. CONNECTION is the only relation kind that generates a terrain-routed "
                            + "main road. Connect all non-isolated structure groups into one reachable network and "
                            + "use CONNECTION for the traffic backbone, especially between distant districts; only "
                            + "an intentional peripheral outpost may opt out with allowRelationConnection=false. "
                            + "For connectionPlan.parameters, compound_cluster accepts only clusterShape, while "
                            + "guide_line_dual_side accepts only sideMode, stagger and widthClass; never combine "
                            + "the two parameter families. "
                            + "Use exact catalog refs, reviewed Patch refs and supported enum values. "
                            + "On rejection revise from the returned path/issues; on compile failure respect failureCount.",
                    submitSchema());
            case "city_post_d4_auto_compile_status" -> function(name,
                    "Read the current city's deterministic post-D4 queue. If waiting_for_generation, stop. If "
                            + "needs_agent, use workflowResponse failureSummary (failed group, structure, filters, hard "
                            + "blocks and recommended action) to decide whether to revise; never guess from a generic code.",
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
                "sessionId", string(), "candidateId", string(),
                "patchSelectionRef", string(), "candidateRangeCells", integer(), "minimumAreaBlocks", integer(),
                "subregionId", string(), "requiredConditions", array(string()), "coreFunctions", array(string()),
                "selectionReason", string());
        if (addCity) {
            values.add("citySeedId", string());
            values.add("role", string());
            values.add("theoreticalScale", enumeration("large_city", "city", "town", "village", "outpost"));
            values.add("satelliteOf", string());
            values.add("trigger", string());
            return object(values, "planningSessionId", "citySeedId", "role",
                    "selectionReason");
        }
        return object(values, "planningSessionId", "selectionReason");
    }

    private static JsonObject submitSchema() {
        JsonObject blueprintProperties = properties(
                "schema", enumeration("city_blueprint"), "cityId", string(),
                "sourceD3Ref", artifactRefSchema(), "catalogSnapshotRef", artifactRefSchema(),
                "generationSeed", integer(), "designIntent", designIntentSchema(),
                "styleProfile", profileRefSchema(), "groups", nonEmptyArray(groupSchema()),
                "arrayCompositions", array(arrayCompositionSchema()), "relations", array(relationSchema()),
                "roadProfile", profileRefSchema(), "surfaceDetailProfile", profileRefSchema(),
                "outdoorPlan", outdoorPlanSchema());
        JsonObject blueprint = object(blueprintProperties,
                "designIntent",
                "styleProfile", "groups", "arrayCompositions", "relations", "roadProfile",
                "surfaceDetailProfile", "outdoorPlan");
        return object(properties(
                "runId", string(), "citySeedId", string(), "contextId", string(),
                "cityBlueprint", blueprint, "autoAdvanceAfterD4", bool(),
                "proportionMode", enumeration("EXACT_SHARES", "RELATIVE_WEIGHTS"),
                "submissionMode", enumeration("DRAFT", "FINAL"),
                "baseBlueprintHash", string(), "baseDraftHash", string(),
                "blueprintPatch", nonEmptyArray(object(properties("op", enumeration("replace"), "path", string(),
                        "value", new JsonObject()), "op", "path", "value"))),
                "contextId");
    }

    private static JsonObject artifactRefSchema() {
        return object(properties("path", string(), "schema", string(), "contentHash", string()),
                "path", "schema", "contentHash");
    }

    private static JsonObject designIntentSchema() {
        return object(properties("cityIdentity", string(), "theme", string(),
                "functionalRoles", nonEmptyArray(string())),
                "cityIdentity", "theme", "functionalRoles");
    }

    private static JsonObject profileRefSchema() {
        return object(properties("profileRef", string()), "profileRef");
    }

    private static JsonObject groupSchema() {
        JsonObject values = properties(
                "groupId", string(), "groupKind", enumeration("STRUCTURE"),
                "preferredPatchRefs", nonEmptyArray(string()),
                "preferredPatchZone", enumeration("CENTER", "NORTH", "EAST", "SOUTH", "WEST"),
                "placementRelation", placementRelationSchema(), "role", string(),
                "priority", enumeration("CORE", "STANDARD", "PERIPHERAL"),
                "extentClass", enumeration("SMALL", "MEDIUM", "LARGE"),
                "densityClass", enumeration("SPARSE", "BALANCED", "DENSE"),
                "algorithmProfileRef", string(),
                "terrainPolicy", enumeration("CONFORM", "BALANCED", "ASSERTIVE"),
                "requiredStructureRefs", array(string()), "fillPoolRef", string(),
                "connectionPlan", connectionPlanSchema(), "compositionProfileRef", string(),
                "attachedFeatures", array(string()), "targetAreaShare", number(),
                "spaceComposition", object(properties("buildingShare", number(), "landscapeShare", number(),
                        "openSpaceShare", number()), "buildingShare", "landscapeShare", "openSpaceShare"),
                "expansionPolicy", object(properties("allowOutwardExpansion", bool(),
                        "allowRelationConnection", described(bool(),
                                "Keep true for every normal structure district that must join the city relation and "
                                        + "road network. False is reserved for an intentionally isolated peripheral "
                                        + "outpost."), "stopWhenTargetReached", bool()),
                        "allowOutwardExpansion", "allowRelationConnection", "stopWhenTargetReached"),
                "buildingGreeneryPolicy", object(properties(
                        "coverage", enumeration("NONE", "SPARSE", "BALANCED", "LUSH"),
                        "patternPreference", enumeration("TEMPLATE_DEFAULT", "FREEFORM", "FIELD_GRID", "MIXED"),
                        "densityPreference", enumeration("TEMPLATE_DEFAULT", "LOW", "MEDIUM", "HIGH")),
                        "coverage", "patternPreference", "densityPreference"));
        return object(values, "groupId", "groupKind", "preferredPatchRefs", "preferredPatchZone", "role",
                "priority", "extentClass", "densityClass", "algorithmProfileRef", "terrainPolicy",
                "requiredStructureRefs", "fillPoolRef", "compositionProfileRef", "attachedFeatures",
                "targetAreaShare", "spaceComposition", "expansionPolicy", "buildingGreeneryPolicy");
    }

    private static JsonObject placementRelationSchema() {
        return object(properties(
                "kind", enumeration("BETWEEN_PATCHES", "ALONG_PATCH_BOUNDARY", "BETWEEN_GROUPS"),
                "patchRefs", array(string()), "groupRefs", array(string())),
                "kind", "patchRefs", "groupRefs");
    }

    private static JsonObject connectionPlanSchema() {
        return object(properties(
                "structurePoolRef", string(), "algorithmProfileRef", described(string(),
                        "Resolve this catalog profile to its planner family before choosing parameters."),
                "densityClass", enumeration("SPARSE", "BALANCED", "DENSE"),
                "parameters", connectionParametersSchema()));
    }

    private static JsonObject connectionParametersSchema() {
        JsonObject result = object();
        result.addProperty("description", "Choose exactly one planner-family shape. compound_cluster permits only "
                + "clusterShape. guide_line_dual_side permits only sideMode, stagger and widthClass. Never mix them.");
        JsonArray anyOf = new JsonArray();
        anyOf.add(object(properties("clusterShape", described(
                enumeration("ORGANIC_COMPACT", "GRID", "COURTYARD", "L_SHAPE", "U_SHAPE"),
                "Only for a profile resolved to compound_cluster."))));
        anyOf.add(object(properties(
                "sideMode", described(enumeration("LEFT", "RIGHT", "BOTH"),
                        "Only for a profile resolved to guide_line_dual_side."),
                "stagger", described(bool(), "Only for guide_line_dual_side."),
                "widthClass", described(enumeration("NARROW", "MEDIUM", "WIDE"),
                        "Only for guide_line_dual_side."))));
        result.add("anyOf", anyOf);
        return result;
    }

    private static JsonObject arrayCompositionSchema() {
        return object(properties("compositionId", string(), "algorithmProfileRef", string(),
                "centerGroupId", string(), "memberGroupIds", nonEmptyArray(string())),
                "compositionId", "algorithmProfileRef", "centerGroupId", "memberGroupIds");
    }

    private static JsonObject relationSchema() {
        return object(properties(
                "fromGroupId", string(), "toGroupId", string(),
                "relationKind", described(
                        enumeration("HIERARCHY", "ADJACENCY", "CONNECTION", "BUFFER", "DISTANCE", "DIRECTION"),
                        "CONNECTION is the only kind that generates a terrain-routed main road. ADJACENCY and "
                                + "HIERARCHY may organize compact districts but do not create a road; DISTANCE, "
                                + "BUFFER and DIRECTION are spatial constraints only."),
                "strength", enumeration("HARD", "SOFT"),
                "distancePreference", enumeration("NONE", "NEAR", "FAR"),
                "directionPreference", enumeration("NONE", "NORTH", "EAST", "SOUTH", "WEST")),
                "fromGroupId", "toGroupId", "relationKind", "strength");
    }

    private static JsonObject outdoorPlanSchema() {
        return object(properties(
                "mode", enumeration("GENERATE", "PRESERVE"),
                "envelopeProfile", enumeration("COMPACT", "BALANCED", "LOOSE"),
                "foundationProfileRef", string(), "spatialGrounds", array(spatialGroundSchema()),
                "landscapes", array(landscapeSchema())),
                "mode", "envelopeProfile", "foundationProfileRef", "spatialGrounds", "landscapes");
    }

    private static JsonObject spatialGroundSchema() {
        return object(properties(
                "sourceGroupId", string(),
                "sharedSpaceType", enumeration("CIVIC_SQUARE", "MARKET_STREET", "RESIDENTIAL_COURT",
                        "FARMSTEAD", "GENERAL_URBAN"),
                "hierarchyLevel", enumeration("PRIMARY", "SECONDARY", "LOCAL"),
                "membership", enumeration("URBAN", "LANDSCAPE")),
                "sourceGroupId", "sharedSpaceType", "hierarchyLevel", "membership");
    }

    private static JsonObject landscapeSchema() {
        JsonObject values = properties(
                "landscapeId", string(), "landscapeProfileRef", string(),
                "purpose", enumeration("FUNCTIONAL", "COMPOSITIONAL", "AMBIENT"),
                "originMode", enumeration("ATTACHED", "FREE_STANDING"),
                "owner", object(properties("groupId", string(), "requiredStructureRef", string()), "groupId"),
                "placementDomain", enumeration("URBAN_RESIDUAL", "FOUNDATION_EDGE", "BETWEEN_GROUPS", "ALONG_WATER"),
                "instanceCount", integer(), "parcelCount", integer(), "preferredPatchRefs", array(string()),
                "terrainPolicy", enumeration("CONFORM", "BALANCED", "ASSERTIVE"), "required", bool(),
                "fillSelection", fillSelectionSchema());
        return object(values, "landscapeId", "landscapeProfileRef", "purpose", "originMode",
                "instanceCount", "parcelCount", "preferredPatchRefs", "terrainPolicy", "required",
                "fillSelection");
    }

    private static JsonObject fillSelectionSchema() {
        JsonObject roleShare = object(properties("roleRef", string(),
                "growthForm", enumeration("PATCH", "CORRIDOR"), "targetShare", number()),
                "roleRef", "growthForm", "targetShare");
        JsonObject contentWeight = object(properties("contentRef", string(), "weight", number()),
                "contentRef", "weight");
        JsonObject variant = object(properties("fillProfileRef", string(), "selectionWeight", number(),
                "roleShares", nonEmptyArray(roleShare), "contentWeights", array(contentWeight)),
                "fillProfileRef", "selectionWeight", "roleShares", "contentWeights");
        return object(properties("variants", nonEmptyArray(variant)), "variants");
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

    private static JsonObject nonEmptyArray(JsonObject items) {
        JsonObject result = array(items);
        result.addProperty("minItems", 1);
        return result;
    }

    private static JsonObject enumeration(String... values) {
        JsonObject result = string();
        JsonArray allowed = new JsonArray();
        for (String value : values) allowed.add(value);
        result.add("enum", allowed);
        return result;
    }

    private static JsonObject described(JsonObject schema, String description) {
        schema.addProperty("description", description);
        return schema;
    }

    private static JsonObject typed(String type) {
        JsonObject result = new JsonObject();
        result.addProperty("type", type);
        return result;
    }
}
