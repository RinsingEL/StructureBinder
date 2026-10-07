package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/** Responses API definitions for the release-safe subset of the existing MCP planning tools. */
public final class ProviderPlanningToolCatalog {
    private ProviderPlanningToolCatalog() {
    }

    public static JsonArray definitions(List<String> allowedTools) {
        JsonArray result = new JsonArray();
        for (String name : allowedTools) result.add(definition(name));
        return result;
    }

    private static JsonObject definition(String name) {
        if (com.rinsing.geomantia.systems.city.application.CityD4Workflow.TOOLS.contains(name))
            return function(name, name.equals("city_d4_materials")
                    ? com.rinsing.geomantia.systems.city.application.CityMaterialCatalogBrowser.INSTRUCTION
                    : java.util.Set.of("city_d4_history","city_d4_reopen","city_d4_restore").contains(name)
                    ? "D4 成功方案版本：history 查询历史与当前 base hash；reopen 重开未入施工流程的定稿；restore 恢复同城同 Context 版本。绑定当前 revision 和接受/草稿 hash，重新编译为草稿、看新图再定稿；失败预算保持，施工流程启动后拒绝。"
                    : "D4 按主次查询素材、声明一区一核心与必需/填充角色，编译后看局部与全城预览；指定 targetDistrictId 可修订已保存区，保留其他区。整体性由 AI 判断，允许隔河，不要求接触；挤占不能破坏其他区功能。", stageSchema(name));
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
            case "realm_t2_retarget" -> function(name,
                    "Change only the current unselected realm's continent, preserving designs and completed cores. "
                            + "Use when the assigned continent is blocked by the starter exploration area. "
                            + "Omit targetContinentId to use the largest eligible continent. The next turn opens fresh candidates.",
                    object(properties("targetContinentId", string())));
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
            case "realm_t4_patch_planning_remove_city" -> function(name,
                    "Remove a previously added city seed (capital or non-capital) from the active T4 planning session before finalize.",
                    object(properties("runId", string(), "planningSessionId", string(), "citySeedId", string()),
                            "planningSessionId", "citySeedId"));
            case "realm_t4_patch_planning_finalize" -> function(name,
                    "Finalize the active realm after its required city seeds are present and its current distribution proposal was reviewed with decision=accept. The existing service merges "
                            + "the realm into the registry; this realm's cities can start before other realms finish T4. Global T2/T3 must already be complete.",
                    object(properties("runId", string(), "planningSessionId", string(),
                            "cityQueueOrderingMode", enumeration("global_radial", "realm_grouped")),
                            "planningSessionId"));
            case "realm_t4_patch_planning_preview" -> function(name,
                    "Show the current whole-realm city proposal, including every city, its design bounds and protection bounds. Compare service roles, sizes and positions before review/finalize.",
                    object(properties("runId",string(),"planningSessionId",string()),"planningSessionId"));
            case "realm_t4_patch_planning_review" -> function(name,
                    "After viewing the current distribution image, assess the whole national city proposal. Use its exact proposalHash; accept only a sound distribution, or revise and adjust cities. Changes invalidate the review.",
                    object(properties("runId",string(),"planningSessionId",string(),"proposalHash",string(),
                            "decision",enumeration("accept","revise"),"assessment",string()),
                            "planningSessionId","proposalHash","decision","assessment"));
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
                    "Review the current capital site using actual terrain/biome evidence, author requirements and available styles. "
                            + "In decisionReason explain environmental fit and any locally suitable authored style to carry into D4. "
                            + "Accept a usable site with an explicit compatible style adjustment; request reselection when fixed author requirements cannot fit. "
                            + "A migration/trade story alone does not resolve an acknowledged environmental mismatch.",
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
                    "First submit designIntent (group roles/intents/patches), then batch materialSelections to search/select authored materials and receive capacity estimates. These stages do not accept geometry. Design a district in DRAFT, refine it after local preview review, then review/refine the whole city. Submit designReview separately: baseDraftHash + groupIds (1..3) requests images; repeat the COMPLETE designReview object after viewing, retaining baseDraftHash and groupIds (or overview) INSIDE it and adding assessment. Never submit assessment alone or move review fields to the request root. Use overview=true instead of groupIds for the final city review. Whole-city refinement must address missing urban transitions with purposeful outward/adjacent arrays and verify their direction and gap reduction in the new overview; road connectivity alone is insufficient. Follow the handbook action/check/repair requirements and matching case triggers. Design quality comes first: refine weak layouts boldly, assess visible spatial relationships rather than retention alone, and repair lost design substance after reductions. Suggested initial array ranges are not final caps or stopping criteria. FINAL must match the reviewed draft. Normal review stages do not consume rejection budgets. No automatic buildings for area fill or connections. Submit a CityBlueprint, or use blueprintPatch (replace-only JSON Pointer operations) with "
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
                "functionalFocus", array(string()), "name", string(),
                "serviceHierarchy", described(string(), "自由描述城市在全国体系中的服务范围与分工，如双中心之一、宗教中心或季节性据点；与物理规模独立。旧层级标签仍可使用。"),
                "positioning", string(), "gameplayRequirements", array(string()),
                "theoreticalScale", enumeration("large_city", "city", "town", "village", "hamlet", "outpost", "capital"),
                "selectionReason", string());
        values.add("styleDirection", mapSchema(string()));
        if (addCity) {
            values.add("citySeedId", string());
            values.add("role", string());
            values.add("satelliteOf", string());
            values.add("trigger", string());
            return object(values, "planningSessionId", "citySeedId", "role",
                    "selectionReason");
        }
        return object(values, "planningSessionId", "selectionReason");
    }

    private static JsonObject dispositionSchema() {
        return nonEmptyArray(object(properties("districtId",string(),"independent",bool(),
                "reason",described(string(),"独立只限用途支持的外围区；不能因为难连接或有缺口而独立。"),
                "peripheralRole",enumeration("BORDER_OUTPOST","PERIPHERAL_RESOURCE","SUBURBAN_INDUSTRY","OTHER_PERIPHERAL")),"districtId","independent"));
    }

    private static JsonObject stageSchema(String name) {
        JsonObject source=submitSchema().getAsJsonObject("properties");
        JsonObject blueprint=source.getAsJsonObject("cityBlueprint").getAsJsonObject("properties");
        JsonObject p=properties("runId",string(),"citySeedId",string(),"contextId",string(),"workflowRevision",integer());
        List<String> required=new java.util.ArrayList<>(List.of("contextId","workflowRevision"));
        switch(name) {
            case "city_d4_answers" -> {
                p.add("baseDraftHash",string());
                p.add("overviewAnswers",com.rinsing.geomantia.systems.city.application.CityDesignQuestions.schema(true));
                p.add("districtAnswers",mapSchema(com.rinsing.geomantia.systems.city.application.CityDesignQuestions.schema(false)));
                required.addAll(List.of("baseDraftHash","overviewAnswers","districtAnswers"));
            }
            case "city_d4_overview" -> {
                JsonObject settings=new JsonObject();
                for(String key:List.of("designIntent","styleProfile","roadProfile","surfaceDetailProfile","surfaceMaterials")) settings.add(key,blueprint.get(key).deepCopy());
                settings.add("outdoorPlan",object(properties("mode",enumeration("GENERATE","PRESERVE"),"envelopeProfile",enumeration("COMPACT","BALANCED","LOOSE"),"foundationProfileRef",string()),"mode","envelopeProfile","foundationProfileRef"));
                p.add("overview",object(properties("citySettings",object(settings,"designIntent","styleProfile","roadProfile","surfaceDetailProfile","outdoorPlan"),"districts",source.getAsJsonObject("designIntent").getAsJsonObject("properties").get("groups").deepCopy(),"districtDisposition",dispositionSchema()),"citySettings","districts")); required.add("overview");
            }
            case "city_d4_district" -> {
                p.add("districtDesign",districtSchema(blueprint,false));required.add("districtDesign");
                p.add("targetDistrictId",described(string(),"修订已保存区；初次设计默认 currentDistrict。"));
                p.add("baseDraftHash",string());p.add("assessment",described(string(),"修订时说明当前预览依据。"));
            }
            case "city_d4_integrate" -> {
                JsonObject changes=districtSchema(blueprint,true);
                JsonObject cp=changes.getAsJsonObject("properties");
                for(String key:List.of("landscapes","surfaceMaterials","removeGroupIds","removeCompositionIds","removeLandscapeIds"))cp.remove(key);
                p.add("changes",changes);p.add("targetDistrictId",string());p.add("protectedDistrictIds",array(string()));
                p.add("expansionMode",enumeration("ADJUST_ARRAY","OUTWARD_ARRAY","REPAIR_CORE"));p.add("integrationIntent",string());
                p.add("baseDraftHash",string());p.add("assessment",described(string(),"看当前总览判断整体性及各区功能是否保留；允许隔河，不要求接触。"));
                p.add("previousExpansionComplete",described(bool(),"切换扩张区前，确认上一区已形成整体性。"));
                required.addAll(List.of("changes","targetDistrictId","protectedDistrictIds","expansionMode","integrationIntent","baseDraftHash","assessment"));
            }
            case "city_d4_mark" -> {
                p.add("baseDraftHash",string());p.add("assessment",string());p.add("districtDisposition",dispositionSchema());
                required.addAll(List.of("baseDraftHash","assessment","districtDisposition"));
            }
            case "city_d4_preview" -> {
                p.add("baseDraftHash",string());p.add("overview",bool());JsonObject ids=nonEmptyArray(string());ids.addProperty("maxItems",3);p.add("groupIds",ids);required.add("baseDraftHash");
            }
            case "city_d4_finalize" -> {
                p.add("baseDraftHash",string());p.add("assessment",string());p.add("functionsPreserved",described(bool(),"所有功能区保留有效主体，不能只剩无关配套。"));
                required.addAll(List.of("baseDraftHash","assessment","functionsPreserved"));p.add("autoAdvanceAfterD4",bool());
            }
            case "city_d4_materials","city_d4_example","city_d4_blocks" -> {
                String key=switch(name){case "city_d4_materials" -> "materialSelections";case "city_d4_example" -> "designExample";default -> "blockMaterials";};p.add(key,source.get(key).deepCopy());required.add(key);
            }
            case "city_d4_reopen" -> {
                p.add("baseBlueprintHash",string());p.add("reason",string());required.addAll(List.of("baseBlueprintHash","reason"));
            }
            case "city_d4_restore" -> {
                p.add("versionId",string());p.add("reason",string());p.add("baseBlueprintHash",string());p.add("baseDraftHash",string());
                required.addAll(List.of("versionId","reason"));
            }
            case "city_d4_history", "city_d4_handbook" -> { }
            default -> throw new IllegalArgumentException("Unknown D4 tool: "+name);
        }
        if(java.util.Set.of("city_d4_overview","city_d4_district","city_d4_integrate").contains(name)) {
            p.add("designAnswers",com.rinsing.geomantia.systems.city.application.CityDesignQuestions.schema(name.equals("city_d4_overview")));
            required.add("designAnswers");
        }
        return object(p,required.toArray(String[]::new));
    }
    private static JsonObject districtSchema(JsonObject blueprint,boolean update) {
        JsonObject fields=new JsonObject();
        for(String key:List.of("groups","arrayCompositions","relations","surfaceMaterials")) fields.add(key,blueprint.get(key).deepCopy());
        fields.getAsJsonObject("surfaceMaterials").getAsJsonObject("properties").remove("defaults");
        JsonObject outdoor=blueprint.getAsJsonObject("outdoorPlan").getAsJsonObject("properties");
        for(String key:List.of("foundationGroupIds","landscapes"))fields.add(key,outdoor.get(key).deepCopy());
        if(update) {
            for(String key:List.of("groups","arrayCompositions","landscapes")) {
                JsonObject item=fields.getAsJsonObject(key).getAsJsonObject("items"); relaxUpdateRequired(item);
                JsonArray required=new JsonArray();required.add(key.equals("groups")?"groupId":key.equals("landscapes")?"landscapeId":"compositionId");item.add("required",required);
                item.getAsJsonObject("properties").add("clearFields",described(array(string()),"明确清除该对象可省略字段，例如 placementRelation；不能清除 ID，不使用 JSON Pointer。"));
                fields.getAsJsonObject(key).remove("minItems");
            }
            for(String key:List.of("removeGroupIds","removeCompositionIds","removeLandscapeIds"))fields.add(key,array(string()));
        }
        if(!update) fields.add("core",object(properties("groupId",string(),"structureRef",string()),"groupId","structureRef"));
        JsonObject schema=update?object(fields):object(fields,"groups","core");
        if(update) schema.addProperty("description","按 ID 合并，未提供的字段保留；扩张不允许删除设计对象。relations 和 foundationGroupIds 如提供则替换本设计内清单。新对象仍须完整配置；已有组只允许 groupId、structureCount、densityClass、algorithmProfileRef、connectionPlan，改嵌套时可用 clearFields 清除 placementRelation。");
        return schema;
    }

    private static void relaxUpdateRequired(com.google.gson.JsonElement node) {
        if(node.isJsonObject()) {
            JsonObject object=node.getAsJsonObject();object.remove("required");object.remove("oneOf");
            for(var entry:object.entrySet())relaxUpdateRequired(entry.getValue());
        } else if(node.isJsonArray()) for(var child:node.getAsJsonArray())relaxUpdateRequired(child);
    }

    private static JsonObject submitSchema() {
        JsonObject blueprintProperties = properties(
                "schema", enumeration("city_blueprint"), "cityId", string(),
                "sourceD3Ref", artifactRefSchema(), "catalogSnapshotRef", artifactRefSchema(),
                "generationSeed", integer(), "designIntent", designIntentSchema(),
                "styleProfile", profileRefSchema(), "groups", nonEmptyArray(groupSchema()),
                "arrayCompositions", array(arrayCompositionSchema()), "relations", array(relationSchema()),
                "roadProfile", profileRefSchema(), "surfaceDetailProfile", profileRefSchema(),
                "outdoorPlan", outdoorPlanSchema(), "surfaceMaterials", surfaceMaterialsSchema());
        JsonObject blueprint = object(blueprintProperties,
                "designIntent",
                "styleProfile", "groups", "arrayCompositions", "relations", "roadProfile",
                "surfaceDetailProfile", "outdoorPlan");
        JsonObject reviewGroups = nonEmptyArray(string());
        reviewGroups.addProperty("maxItems", 3);
        reviewGroups.addProperty("uniqueItems", true);
        return object(properties(
                "runId", string(), "citySeedId", string(), "contextId", string(),
                "designIntent", object(properties("groups", nonEmptyArray(object(properties(
                        "groupId", string(), "role", string(), "intent", string(), "preferredPatchRefs", nonEmptyArray(string())),
                        "groupId", "role", "intent", "preferredPatchRefs"))), "groups"),
                "materialSelections", nonEmptyArray(object(properties("groupId", string(), "query", string(),
                        "filters", object(properties(
                                "roles", array(enumeration("core", "fill", "structure", "self_contained", "unknown")),
                                "functionIds", described(array(string()), "共享功能树 ID；从返回的 facets.functions 选择。子功能匹配父级，父级不推导子级。"),
                                "functionMode", enumeration("all", "any"),
                                "styles", described(array(string()), "任一风格精确匹配，空数组不限；风格不限定种族。"),
                                "categories", array(enumeration("specialty","common")),
                                "assetTags", array(enumeration("infrastructure","landscape")),
                                "rawFunctionTerms", described(array(string()), "所有原始用途标签都须匹配；用于未细分或未映射的用途。"))),
                        "limit", boundedInteger(0, 100, "默认20；0只取完整匹配集的联动统计。"),
                        "offset", boundedInteger(0, Integer.MAX_VALUE, "默认0，按稳定 structureRef 排序；使用 nextOffset 翻页。"),
                        "structureRefs", array(string()), "fillPoolRefs", array(string())), "groupId")),
                "designReview", object(properties("baseDraftHash", string(), "groupIds", reviewGroups,
                        "overview", bool(), "assessment", string()), "baseDraftHash"),
                "designExample", object(properties("caseId", string(), "reloadImages", bool()), "caseId"),
                "blockMaterials", object(properties("slot",string(),"query",string(),"page",integer(),"landscapeProfileRef",string(),"previewBlockId",string()),"slot"),
                "cityBlueprint", blueprint, "autoAdvanceAfterD4", bool(),
                "proportionMode", enumeration("EXACT_SHARES", "RELATIVE_WEIGHTS"),
                "submissionMode", enumeration("DRAFT", "FINAL"),
                "baseBlueprintHash", string(), "baseDraftHash", string(),
                "blueprintPatch", nonEmptyArray(object(properties("op", enumeration("replace"), "path", string(),
                        "value", new JsonObject()), "op", "path", "value"))),
                "contextId");
    }

    private static JsonObject surfaceMaterialsSchema() {
        JsonObject slots=new JsonObject();
        for(String slot:new java.util.TreeSet<>(com.rinsing.geomantia.systems.city.domain.blueprint.CitySurfaceMaterials.CITY_SLOTS))slots.add(slot,string());
        JsonObject roadSlots=new JsonObject();
        for(String slot:new java.util.TreeSet<>(com.rinsing.geomantia.systems.city.domain.blueprint.CitySurfaceMaterials.ROAD_SLOTS))roadSlots.add(slot,string());
        JsonObject landscapeSlots=new JsonObject();
        for(String slot:new java.util.TreeSet<>(com.rinsing.geomantia.systems.city.domain.blueprint.CitySurfaceMaterials.LANDSCAPE_SLOTS))landscapeSlots.add(slot,string());
        return object(properties("defaults",object(slots),"groups",mapSchema(object(slots)),
                "roads",mapSchema(object(roadSlots)),"landscapes",mapSchema(object(landscapeSlots))));
    }
    private static JsonObject mapSchema(JsonObject values){
        JsonObject schema=new JsonObject();schema.addProperty("type","object");schema.add("additionalProperties",values);return schema;
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
                "priority", described(enumeration("CORE", "STANDARD", "PERIPHERAL"), "全城合并后仅一个 CORE，不是每区一个。先看 d4Workflow.submissionRules.savedCoreOwners；其他区已占用则本区使用 STANDARD/PERIPHERAL。嵌套构图中心由 centerGroupId 决定。"),
                "structureCount", described(integer(), "Planned building count including required refs: 1..1024 and >= requiredStructureRefs count. CONTIGUOUS template landscapes may use hundreds of repeated tiles; raise scale, not jitter or gaps. CENTER_SYMMETRIC uses an odd total. This is a design count, not a retained-count gate."),
                "extentClass", enumeration("SMALL", "MEDIUM", "LARGE"),
                "densityClass", enumeration("SPARSE", "BALANCED", "DENSE"),
                "algorithmProfileRef", string(),
                "terrainPolicy", enumeration("CONFORM", "BALANCED", "ASSERTIVE"),
                "requiredStructureRefs", described(nonEmptyArray(string()), "每组至少一个必需结构；fillPools 不能替代。"), "fillPoolRef", string(), "fillPools", weightedPoolsSchema(),
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
        JsonObject result = object(values, "groupId", "groupKind", "preferredPatchRefs", "preferredPatchZone", "role",
                "priority", "extentClass", "densityClass", "algorithmProfileRef", "terrainPolicy",
                "requiredStructureRefs", "compositionProfileRef", "attachedFeatures",
                "targetAreaShare", "spaceComposition", "expansionPolicy", "buildingGreeneryPolicy");
        result.add("oneOf", com.google.gson.JsonParser.parseString("[{\"required\":[\"fillPoolRef\"]},{\"required\":[\"fillPools\"]}]"));
        return result;
    }

    private static JsonObject placementRelationSchema() {
        return object(properties(
                "kind", enumeration("BETWEEN_PATCHES", "ALONG_PATCH_BOUNDARY", "BETWEEN_GROUPS"),
                "patchRefs", array(string()), "groupRefs", array(string())),
                "kind", "patchRefs", "groupRefs");
    }

    private static JsonObject weightedPoolsSchema() {
        JsonObject weight = number(); weight.addProperty("exclusiveMinimum", 0);
        return described(nonEmptyArray(object(properties("poolRef", string(), "weight", weight), "poolRef", "weight")),
                "Weighted fill pools. Use this OR the legacy single pool reference, never both. Roll once per expansion unit.");
    }

    private static JsonObject connectionPlanSchema() {
        return object(properties(
                "structurePoolRef", string(), "structurePools", weightedPoolsSchema(), "algorithmProfileRef", described(string(),
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
                "foundationProfileRef", string(), "foundationGroupIds", described(array(string()),"明确需要台地/铺地的本区建筑组 ID；未列出的组保留原地面。"),
                "landscapes", array(landscapeSchema())),
                "mode", "envelopeProfile", "foundationProfileRef", "foundationGroupIds", "landscapes");
    }

    private static JsonObject landscapeSchema() {
        JsonObject values = properties(
                "landscapeId", string(), "landscapeProfileRef", string(),
                "purpose", enumeration("FUNCTIONAL", "COMPOSITIONAL", "AMBIENT"),
                "originMode", enumeration("ATTACHED", "FREE_STANDING"),
                "owner", described(object(properties("groupId", string(), "requiredStructureRef", string()), "groupId"),
                        "景观归属功能组，不依赖建筑落位。新方案只填写 groupId；旧 requiredStructureRef 仅为位置参考。"),
                "placementDomain", enumeration("URBAN_RESIDUAL", "FOUNDATION_EDGE", "BETWEEN_GROUPS", "ALONG_WATER"),
                "instanceCount", integer(), "parcelCount", integer(), "preferredPatchRefs", array(string()),
                "terrainPolicy", enumeration("CONFORM", "BALANCED", "ASSERTIVE"), "required", bool(),
                "fillSelection", fillSelectionSchema(),
                "growth", object(properties("seed",object(properties("x",integer(),"z",integer()),"x","z"),
                        "targetCellCount",integer(),"allowedLandformTypes",array(string())),
                        "seed","targetCellCount","allowedLandformTypes"));
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

    private static JsonObject boundedInteger(int min, int max, String description) {
        JsonObject value = described(integer(), description);
        value.addProperty("minimum", min); value.addProperty("maximum", max); return value;
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
