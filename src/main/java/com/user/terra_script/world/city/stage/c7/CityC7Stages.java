package com.user.terra_script.world.city.stage.c7;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.user.terra_script.world.city.stage.CityC35CatalogIO;
import com.user.terra_script.world.city.stage.StructureTemplateQueryService;
import com.user.terra_script.world.city.stage.c6.CityC6Stages;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CityC7Stages {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final String C7_FILE = "C7_TemplateSelection.json";

    private static final List<String> VILLAGE_CIVIC_L = List.of(
            "minecraft:village/plains/town_centers/plains_meeting_point_1",
            "minecraft:village/plains/town_centers/plains_meeting_point_2",
            "minecraft:village/plains/town_centers/plains_meeting_point_3",
            "minecraft:village/savanna/town_centers/savanna_meeting_point_1",
            "minecraft:village/taiga/town_centers/taiga_meeting_point_1"
    );
    private static final List<String> VILLAGE_HOUSE_M = List.of(
            "minecraft:village/plains/houses/plains_medium_house_1",
            "minecraft:village/plains/houses/plains_medium_house_2",
            "minecraft:village/plains/houses/plains_big_house_1",
            "minecraft:village/taiga/houses/taiga_medium_house_1",
            "minecraft:village/savanna/houses/savanna_medium_house_1",
            "minecraft:village/desert/houses/desert_medium_house_1"
    );
    private static final List<String> VILLAGE_HOUSE_S = List.of(
            "minecraft:village/plains/houses/plains_small_house_1",
            "minecraft:village/plains/houses/plains_small_house_2",
            "minecraft:village/plains/houses/plains_small_house_3",
            "minecraft:village/plains/houses/plains_small_house_4",
            "minecraft:village/savanna/houses/savanna_small_house_1",
            "minecraft:village/taiga/houses/taiga_small_house_1",
            "minecraft:village/desert/houses/desert_small_house_1",
            "minecraft:village/snowy/houses/snowy_small_house_1"
    );

    private CityC7Stages() {}

    private static final class Catalog {
        public String step;
        public boolean ok;
        public String city_id;
        public int catalog_version;
        public long generated_at_epoch_ms;
        public List<String> function_enum_table = new ArrayList<>();
        public List<CatalogStructure> structures = new ArrayList<>();
    }

    private static final class CatalogStructure extends CityC35CatalogIO.CatalogStructure {}
    private static final class Size extends CityC35CatalogIO.Size {}
    private static final class Orientation extends CityC35CatalogIO.Orientation {}
    private static final class FunctionCandidate extends CityC35CatalogIO.FunctionCandidate {}
    private static final class TagSource extends CityC35CatalogIO.TagSource {}

    public static class C7Selection {
        public String step = "C7";
        public boolean ok = true;
        public String city_id;
        public long generated_at_epoch_ms;
        public String catalog_source = "hardcoded_vanilla_village_templates";
        public String decision_source = "program_fallback";
        public String selection_mode = "strict_function_filter";
        public int filtered_candidate_count;
        public String strict_filter_failure_reason;
        public int puzzle_depth = 0;
        public List<TemplateSelectionItem> selections = new ArrayList<>();
        public List<GroupArrangementDecision> arrangements = new ArrayList<>();
    }

    public static class GroupArrangementDecision {
        public String group_id;
        public String build_area_id;
        public String arrangement_type;
        public SeedSpec seed = new SeedSpec();
        public LimitsSpec limits = new LimitsSpec();
        public TerminationSpec termination = new TerminationSpec();
        public Map<String, Object> arrangement_params = new LinkedHashMap<>();
        public Map<String, Object> linear = new LinkedHashMap<>();
        public Map<String, Object> courtyard = new LinkedHashMap<>();
        public Map<String, Object> spine_branch = new LinkedHashMap<>();
        public Map<String, Object> cluster = new LinkedHashMap<>();
        public String preset_pool_ref;
        public String notes;
        public List<String> validated_primary_modules = new ArrayList<>();
        public List<SelectedComponent> selected_components = new ArrayList<>();
    }

    public static class SeedSpec {
        public int start_x;
        public int start_z;
        public int start_rotation;
        public String start_template_id;
        public String start_connector_dir;
    }

    public static class LimitsSpec {
        public int max_depth = 6;
        public int max_pieces = 24;
        public int max_branch_per_depth = 2;
    }

    public static class TerminationSpec {
        public boolean require_closure = true;
        public boolean allow_trim_leaf = true;
        public boolean rollback_on_unclosed_middle = true;
    }

    public static class SelectedComponent {
        public String component_id;
        public String role;
        public String template_id;
        public boolean required = true;
        public double weight = 1.0;
        public String attach_to_component_id;
        public ComponentRule rule = new ComponentRule();
    }

    public static class ComponentRule {
        public String anchor_preference = "primary_center";
        public int max_distance_from_anchor = 48;
        public int min_spacing = 8;
        public boolean prefer_edge;
        public boolean snap_to_water;
        public String terrain_mode = "follow_ground";
        public String prefer_axis = "auto";
        public List<Integer> allowed_rotations = new ArrayList<>(List.of(0, 90, 180, 270));
    }

    public static class TemplateSelectionItem {
        public String group_id;
        public String build_area_id;
        public String module_id;
        public String function_role;
        public String interaction_role = "FRONT_TO_PLAZA";
        public String size_tier = "M";
        public String selected_template;
        public String landing_hint;
        public String growth_axis;
        public String vertical_role;
        public int vertical_clearance;
        public boolean vertical_capable;
        public String arrangement_type;
        public SeedSpec seed = new SeedSpec();
        public LimitsSpec limits = new LimitsSpec();
        public TerminationSpec termination = new TerminationSpec();
        public Map<String, Object> arrangement_params = new LinkedHashMap<>();
        public Map<String, Object> strategy_params = new LinkedHashMap<>();
        public List<String> top_k_templates = new ArrayList<>();
        public List<String> fallback_chain = new ArrayList<>();
        public List<SelectedComponent> selected_components = new ArrayList<>();
        public int filtered_candidate_count;
        public String strict_filter_failure_reason;
        public String notes;
    }

    public static C7Selection generate(String cityId, CityC6Stages.C6Layout c6Layout) {
        C7Selection result = new C7Selection();
        result.city_id = cityId;
        result.generated_at_epoch_ms = System.currentTimeMillis();
        if (c6Layout == null || c6Layout.plans == null) {
            result.ok = false;
            return result;
        }

        Catalog catalog = loadCatalog();
        result.catalog_source = catalog != null && catalog.ok
                ? CityC35CatalogIO.catalogPath().toString()
                : "hardcoded_vanilla_village_templates";

        for (CityC6Stages.LayoutPlan plan : c6Layout.plans) {
            if (plan == null || plan.primary_modules == null || plan.primary_modules.isEmpty()) continue;
            boolean consumable = plan.validated || plan.decision_mode == null || plan.decision_mode.isBlank();
            if (!consumable) continue;
            result.arrangements.add(buildFallbackArrangement(plan, catalog, result));
            for (CityC6Stages.PrimaryModule module : plan.primary_modules) {
                result.selections.add(buildFallbackSelection(plan, module, catalog, result));
            }
        }
        result.selections.sort(Comparator.comparing(i -> safe(i.build_area_id) + "|" + safe(i.module_id)));
        result.arrangements.sort(Comparator.comparing(i -> safe(i.build_area_id) + "|" + safe(i.group_id)));
        return result;
    }

    public static C7Selection fromDecisionRequest(String cityId, CityC6Stages.C6Layout c6Layout, JsonObject request) {
        C7Selection result = new C7Selection();
        result.city_id = cityId;
        result.generated_at_epoch_ms = System.currentTimeMillis();
        result.decision_source = "ai_decision_submit";
        result.selection_mode = "ai_decision_submit";
        Catalog catalog = loadCatalog();
        result.catalog_source = catalog != null && catalog.ok
                ? CityC35CatalogIO.catalogPath().toString()
                : "hardcoded_vanilla_village_templates";

        if (request != null && request.has("arrangements") && request.get("arrangements").isJsonArray()) {
            for (JsonElement element : request.getAsJsonArray("arrangements")) {
                if (!element.isJsonObject()) continue;
                GroupArrangementDecision arrangement = parseArrangement(element.getAsJsonObject(), c6Layout);
                if (arrangement == null) continue;
                result.arrangements.add(arrangement);
                result.selections.addAll(expandSelectionsFromArrangement(arrangement, catalog));
            }
        }
        result.selections.sort(Comparator.comparing(i -> safe(i.build_area_id) + "|" + safe(i.module_id)));
        result.arrangements.sort(Comparator.comparing(i -> safe(i.build_area_id) + "|" + safe(i.group_id)));
        return result;
    }

    public static void save(Path cityDir, C7Selection selection) throws Exception {
        if (cityDir == null || selection == null) return;
        Files.createDirectories(cityDir);
        Files.writeString(cityDir.resolve(C7_FILE), GSON.toJson(selection), StandardCharsets.UTF_8);
    }

    public static C7Selection load(Path cityDir) throws Exception {
        Path file = cityDir.resolve(C7_FILE);
        if (!Files.exists(file)) return null;
        return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), C7Selection.class);
    }

    private static GroupArrangementDecision buildFallbackArrangement(CityC6Stages.LayoutPlan plan, Catalog catalog, C7Selection selection) {
        GroupArrangementDecision arrangement = new GroupArrangementDecision();
        arrangement.group_id = plan.group_id;
        arrangement.build_area_id = plan.build_area_id;
        arrangement.arrangement_type = inferArrangementType(plan.group_id);
        arrangement.preset_pool_ref = catalog != null && catalog.ok
                ? CityC35CatalogIO.catalogPath().toString()
                : "hardcoded_vanilla_village_templates";
        arrangement.notes = "Program fallback arrangement; replace with AI decision when available.";
        arrangement.arrangement_params.put("spacing", defaultSpacing(arrangement.arrangement_type));
        arrangement.arrangement_params.put("axis", "auto");
        arrangement.limits.max_depth = 6;
        arrangement.limits.max_pieces = 12;
        arrangement.limits.max_branch_per_depth = 1;
        arrangement.termination.require_closure = true;
        arrangement.termination.allow_trim_leaf = true;
        arrangement.termination.rollback_on_unclosed_middle = true;
        applyDefaultStrategyParams(arrangement);
        if (plan.primary_modules != null) {
            for (CityC6Stages.PrimaryModule module : plan.primary_modules) {
                if (module == null) continue;
                if (arrangement.seed.start_template_id == null || arrangement.seed.start_template_id.isBlank()) {
                    arrangement.seed.start_x = (int) Math.round(module.anchor.x);
                    arrangement.seed.start_z = (int) Math.round(module.anchor.z);
                    arrangement.seed.start_connector_dir = defaultConnectorDir(arrangement.arrangement_type);
                    SelectedComponent seedComponent = buildFallbackComponent(plan, module, catalog, selection);
                    arrangement.seed.start_template_id = seedComponent.template_id;
                    arrangement.seed.start_rotation = resolveStartRotation(findStructure(catalog, arrangement.seed.start_template_id), arrangement.seed.start_connector_dir, arrangement.arrangement_type);
                }
                arrangement.validated_primary_modules.add(module.module_id);
                arrangement.selected_components.add(buildFallbackComponent(plan, module, catalog, selection));
            }
        }
        return arrangement;
    }

    private static TemplateSelectionItem buildFallbackSelection(CityC6Stages.LayoutPlan plan, CityC6Stages.PrimaryModule module, Catalog catalog, C7Selection selection) {
        TemplateSelectionItem item = new TemplateSelectionItem();
        item.group_id = plan.group_id;
        item.build_area_id = plan.build_area_id;
        item.module_id = module != null && module.module_id != null ? module.module_id : ("m_" + safe(plan.group_id) + "_default");

        String category = "residential";
        String sizeTier = "M";
        if (module != null && module.template_hint != null) {
            category = module.template_hint.category != null ? module.template_hint.category : category;
            sizeTier = module.template_hint.size_tier != null ? module.template_hint.size_tier : sizeTier;
        }
        item.size_tier = normalizeTier(sizeTier);
        item.function_role = inferFunctionRole(category, item.module_id, plan.group_id);
        item.interaction_role = inferInteractionRole(item.function_role);
        item.arrangement_type = inferArrangementType(plan.group_id);
        item.seed.start_x = module != null && module.anchor != null ? (int) Math.round(module.anchor.x) : 0;
        item.seed.start_z = module != null && module.anchor != null ? (int) Math.round(module.anchor.z) : 0;
        item.seed.start_rotation = defaultRotation(item.arrangement_type);
        item.seed.start_connector_dir = defaultConnectorDir(item.arrangement_type);
        item.arrangement_params.put("spacing", defaultSpacing(item.arrangement_type));
        item.arrangement_params.put("axis", "auto");
        item.limits.max_depth = 6;
        item.limits.max_pieces = 12;
        item.limits.max_branch_per_depth = 1;
        item.termination.require_closure = true;
        item.termination.allow_trim_leaf = true;
        item.termination.rollback_on_unclosed_middle = true;
        item.strategy_params.putAll(defaultStrategyParams(item.arrangement_type));

        List<String> candidates = chooseCandidates(category, item.size_tier, item.function_role, plan.group_id, item.arrangement_type, item.seed.start_connector_dir, true, catalog, item, selection);
        item.top_k_templates = new ArrayList<>(candidates.subList(0, Math.min(3, candidates.size())));
        item.selected_template = item.top_k_templates.isEmpty() ? null : item.top_k_templates.get(0);
        item.seed.start_template_id = item.selected_template;
        item.seed.start_rotation = resolveStartRotation(findStructure(catalog, item.selected_template), item.seed.start_connector_dir, item.arrangement_type);
        for (String c : item.top_k_templates) {
            if (!c.equals(item.selected_template)) item.fallback_chain.add(c);
        }

        if (catalog != null && catalog.ok && item.selected_template != null && !item.selected_template.isBlank()) {
            CatalogStructure selected = findStructure(catalog, item.selected_template);
            if (selected != null) {
                item.landing_hint = safe(selected.landing_hint);
                item.growth_axis = safe(selected.growth_axis);
                item.vertical_role = safe(selected.vertical_role);
                item.vertical_clearance = Math.max(0, selected.vertical_clearance);
                item.vertical_capable = !item.growth_axis.isBlank() || !item.vertical_role.isBlank() || item.vertical_clearance > 0;
            }
        }

        item.selected_components.add(buildFallbackComponent(plan, module, catalog, selection));
        item.notes = catalog != null && catalog.ok && !item.top_k_templates.isEmpty()
                ? "Catalog-driven strict fallback selection"
                : "Strict function filter found no candidate";
        return item;
    }

    private static SelectedComponent buildFallbackComponent(CityC6Stages.LayoutPlan plan, CityC6Stages.PrimaryModule module, Catalog catalog, C7Selection selection) {
        SelectedComponent component = new SelectedComponent();
        component.component_id = module != null && module.module_id != null ? module.module_id : ("component_" + safe(plan.group_id));
        component.role = inferComponentRole(plan.group_id);

        String category = module != null && module.template_hint != null ? module.template_hint.category : "residential";
        String sizeTier = module != null && module.template_hint != null ? module.template_hint.size_tier : "M";
        String functionRole = inferFunctionRole(category, component.component_id, plan.group_id);
        String arrangementType = inferArrangementType(plan.group_id);
        TemplateSelectionItem scratch = new TemplateSelectionItem();
        List<String> candidates = chooseCandidates(
                category,
                normalizeTier(sizeTier),
                functionRole,
                plan.group_id,
                arrangementType,
                defaultConnectorDir(arrangementType),
                true,
                catalog,
                scratch,
                selection
        );
        component.template_id = candidates.isEmpty() ? null : candidates.get(0);
        component.rule = defaultRuleForArrangement(arrangementType, component.role);
        return component;
    }

    private static GroupArrangementDecision parseArrangement(JsonObject json, CityC6Stages.C6Layout c6Layout) {
        String groupId = readString(json, "group_id");
        String buildAreaId = readString(json, "build_area_id");
        if ((groupId == null || groupId.isBlank()) && (buildAreaId == null || buildAreaId.isBlank())) return null;

        GroupArrangementDecision arrangement = new GroupArrangementDecision();
        arrangement.group_id = groupId;
        arrangement.build_area_id = buildAreaId;
        arrangement.arrangement_type = readString(json, "arrangement_type");
        arrangement.preset_pool_ref = readString(json, "preset_pool_ref");
        arrangement.notes = readString(json, "notes");
        if (arrangement.arrangement_type == null || arrangement.arrangement_type.isBlank()) {
            arrangement.arrangement_type = inferArrangementType(groupId);
        }
        if (json.has("arrangement_params") && json.get("arrangement_params").isJsonObject()) {
            arrangement.arrangement_params = GSON.fromJson(json.get("arrangement_params"), LinkedHashMap.class);
        }
        if (json.has("seed") && json.get("seed").isJsonObject()) {
            arrangement.seed = GSON.fromJson(json.get("seed"), SeedSpec.class);
        }
        if (json.has("limits") && json.get("limits").isJsonObject()) {
            arrangement.limits = GSON.fromJson(json.get("limits"), LimitsSpec.class);
        }
        if (json.has("termination") && json.get("termination").isJsonObject()) {
            arrangement.termination = GSON.fromJson(json.get("termination"), TerminationSpec.class);
        }
        if (json.has("linear") && json.get("linear").isJsonObject()) {
            arrangement.linear = GSON.fromJson(json.get("linear"), LinkedHashMap.class);
        }
        if (json.has("courtyard") && json.get("courtyard").isJsonObject()) {
            arrangement.courtyard = GSON.fromJson(json.get("courtyard"), LinkedHashMap.class);
        }
        if (json.has("spine_branch") && json.get("spine_branch").isJsonObject()) {
            arrangement.spine_branch = GSON.fromJson(json.get("spine_branch"), LinkedHashMap.class);
        }
        if (json.has("cluster") && json.get("cluster").isJsonObject()) {
            arrangement.cluster = GSON.fromJson(json.get("cluster"), LinkedHashMap.class);
        }
        if (json.has("validated_primary_modules") && json.get("validated_primary_modules").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("validated_primary_modules")) {
                if (element != null && element.isJsonPrimitive()) arrangement.validated_primary_modules.add(element.getAsString());
            }
        }
        if (json.has("selected_components") && json.get("selected_components").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("selected_components")) {
                if (!element.isJsonObject()) continue;
                arrangement.selected_components.add(parseComponent(element.getAsJsonObject(), arrangement.arrangement_type));
            }
        }

        CityC6Stages.LayoutPlan plan = findPlan(c6Layout, groupId, buildAreaId);
        if (plan != null) {
            arrangement.group_id = arrangement.group_id != null ? arrangement.group_id : plan.group_id;
            arrangement.build_area_id = arrangement.build_area_id != null ? arrangement.build_area_id : plan.build_area_id;
            if (arrangement.validated_primary_modules.isEmpty() && plan.primary_modules != null) {
                for (CityC6Stages.PrimaryModule module : plan.primary_modules) {
                    if (module != null && module.module_id != null) arrangement.validated_primary_modules.add(module.module_id);
                }
            }
        }
        return arrangement;
    }

    private static SelectedComponent parseComponent(JsonObject json, String arrangementType) {
        SelectedComponent component = new SelectedComponent();
        component.component_id = readString(json, "component_id");
        component.role = readString(json, "role");
        component.template_id = readString(json, "template_id");
        component.required = !json.has("required") || json.get("required").getAsBoolean();
        component.weight = json.has("weight") ? json.get("weight").getAsDouble() : 1.0;
        component.attach_to_component_id = readString(json, "attach_to_component_id");
        component.rule = defaultRuleForArrangement(arrangementType, component.role);
        if (json.has("rule") && json.get("rule").isJsonObject()) {
            JsonObject ruleJson = json.getAsJsonObject("rule");
            String anchorPreference = readString(ruleJson, "anchor_preference");
            if (anchorPreference != null) component.rule.anchor_preference = anchorPreference;
            if (ruleJson.has("max_distance_from_anchor")) component.rule.max_distance_from_anchor = ruleJson.get("max_distance_from_anchor").getAsInt();
            if (ruleJson.has("min_spacing")) component.rule.min_spacing = ruleJson.get("min_spacing").getAsInt();
            if (ruleJson.has("prefer_edge")) component.rule.prefer_edge = ruleJson.get("prefer_edge").getAsBoolean();
            if (ruleJson.has("snap_to_water")) component.rule.snap_to_water = ruleJson.get("snap_to_water").getAsBoolean();
            String terrainMode = readString(ruleJson, "terrain_mode");
            if (terrainMode != null) component.rule.terrain_mode = terrainMode;
            String preferAxis = readString(ruleJson, "prefer_axis");
            if (preferAxis != null) component.rule.prefer_axis = preferAxis;
            if (ruleJson.has("allowed_rotations") && ruleJson.get("allowed_rotations").isJsonArray()) {
                component.rule.allowed_rotations.clear();
                for (JsonElement rotation : ruleJson.getAsJsonArray("allowed_rotations")) {
                    if (rotation.isJsonPrimitive()) component.rule.allowed_rotations.add(rotation.getAsInt());
                }
            }
        }
        return component;
    }

    private static List<TemplateSelectionItem> expandSelectionsFromArrangement(GroupArrangementDecision arrangement, Catalog catalog) {
        List<TemplateSelectionItem> items = new ArrayList<>();
        if (arrangement == null || arrangement.selected_components == null) return items;
        int index = 1;
        for (SelectedComponent component : arrangement.selected_components) {
            if (component == null) continue;
            TemplateSelectionItem item = new TemplateSelectionItem();
            item.group_id = arrangement.group_id;
            item.build_area_id = arrangement.build_area_id;
            item.module_id = component.component_id != null ? component.component_id : (arrangement.group_id + "_component_" + index);
            item.function_role = inferFunctionRole(component.role, item.module_id, arrangement.group_id);
            item.interaction_role = inferInteractionRole(item.function_role);
            item.selected_template = component.template_id;
            item.arrangement_type = arrangement.arrangement_type;
            item.seed = arrangement.seed;
            item.limits = arrangement.limits;
            item.termination = arrangement.termination;
            item.arrangement_params = arrangement.arrangement_params != null ? new LinkedHashMap<>(arrangement.arrangement_params) : new LinkedHashMap<>();
            item.strategy_params = strategyParamsFor(arrangement);
            item.selected_components.add(component);
            item.size_tier = inferSizeTierFromTemplate(component.template_id, catalog);
            if (catalog != null && catalog.ok && component.template_id != null) {
                CatalogStructure selected = findStructure(catalog, component.template_id);
                if (selected != null) {
                    item.landing_hint = safe(selected.landing_hint);
                    item.growth_axis = safe(selected.growth_axis);
                    item.vertical_role = safe(selected.vertical_role);
                    item.vertical_clearance = Math.max(0, selected.vertical_clearance);
                    item.vertical_capable = !item.growth_axis.isBlank() || !item.vertical_role.isBlank() || item.vertical_clearance > 0;
                }
            }
            item.notes = arrangement.notes;
            items.add(item);
            index++;
        }
        return items;
    }

    private static String inferArrangementType(String groupId) {
        String normalized = safe(groupId).toLowerCase(Locale.ROOT);
        if (normalized.contains("port")) return "LINEAR_DOCK";
        if (normalized.contains("market") || normalized.contains("civic")) return "COURTYARD";
        if (normalized.contains("residential") || normalized.contains("shop")) return "SPINE_BRANCH";
        if (normalized.contains("farm")) return "TERRACE_CHAIN";
        return "RING";
    }

    private static int defaultSpacing(String arrangementType) {
        String normalized = safe(arrangementType).toUpperCase(Locale.ROOT);
        if ("LINEAR_DOCK".equals(normalized)) return 12;
        if ("COURTYARD".equals(normalized)) return 10;
        if ("SPINE_BRANCH".equals(normalized)) return 9;
        if ("TERRACE_CHAIN".equals(normalized)) return 8;
        return 10;
    }

    private static int defaultRotation(String arrangementType) {
        String normalized = safe(arrangementType).toUpperCase(Locale.ROOT);
        if ("LINEAR_DOCK".equals(normalized) || "LINEAR".equals(normalized)) return 90;
        return 0;
    }

    private static String defaultConnectorDir(String arrangementType) {
        String normalized = safe(arrangementType).toUpperCase(Locale.ROOT);
        if ("LINEAR_DOCK".equals(normalized) || "LINEAR".equals(normalized)) return "east";
        if ("COURTYARD".equals(normalized) || "RING".equals(normalized)) return "south";
        return "east";
    }

    private static void applyDefaultStrategyParams(GroupArrangementDecision arrangement) {
        Map<String, Object> params = defaultStrategyParams(arrangement.arrangement_type);
        String normalized = safe(arrangement.arrangement_type).toUpperCase(Locale.ROOT);
        if ("COURTYARD".equals(normalized) || "RING".equals(normalized)) arrangement.courtyard.putAll(params);
        else if ("SPINE_BRANCH".equals(normalized)) arrangement.spine_branch.putAll(params);
        else if ("CLUSTER".equals(normalized)) arrangement.cluster.putAll(params);
        else arrangement.linear.putAll(params);
    }

    private static Map<String, Object> strategyParamsFor(GroupArrangementDecision arrangement) {
        if (arrangement == null) return new LinkedHashMap<>();
        String normalized = safe(arrangement.arrangement_type).toUpperCase(Locale.ROOT);
        if ("COURTYARD".equals(normalized) || "RING".equals(normalized)) return arrangement.courtyard != null ? new LinkedHashMap<>(arrangement.courtyard) : new LinkedHashMap<>();
        if ("SPINE_BRANCH".equals(normalized)) return arrangement.spine_branch != null ? new LinkedHashMap<>(arrangement.spine_branch) : new LinkedHashMap<>();
        if ("CLUSTER".equals(normalized)) return arrangement.cluster != null ? new LinkedHashMap<>(arrangement.cluster) : new LinkedHashMap<>();
        return arrangement.linear != null ? new LinkedHashMap<>(arrangement.linear) : new LinkedHashMap<>();
    }

    private static Map<String, Object> defaultStrategyParams(String arrangementType) {
        Map<String, Object> params = new LinkedHashMap<>();
        String normalized = safe(arrangementType).toUpperCase(Locale.ROOT);
        if ("LINEAR_DOCK".equals(normalized) || "LINEAR".equals(normalized)) {
            params.put("primary_axis", "x");
            params.put("forward_dirs", List.of("east"));
            params.put("preferred_forward_dir", "east");
            params.put("segment_spacing", 12);
            params.put("lane_count", 1);
            params.put("allow_side_branches", false);
            params.put("side_branch_interval", 99);
            params.put("side_branch_max_length", 0);
            params.put("alternate_branch_side", false);
            params.put("allow_reverse_growth", false);
            params.put("front_loaded_start", true);
        } else if ("COURTYARD".equals(normalized) || "RING".equals(normalized)) {
            params.put("center_mode", "seed_is_center");
            params.put("ring_count", 1);
            params.put("ring_spacing", 10);
            params.put("arc_coverage_deg", 300);
            params.put("entry_gap_dir", "south");
            params.put("entry_gap_width", 1);
            params.put("prefer_symmetric_pairs", true);
            params.put("allow_corner_emphasis", true);
            params.put("corner_piece_weight", 1.5);
            params.put("inward_facing", true);
        } else if ("SPINE_BRANCH".equals(normalized)) {
            params.put("spine_axis", "x");
            params.put("spine_dirs", List.of("east"));
            params.put("spine_spacing", 10);
            params.put("spine_length_target", 6);
            params.put("branch_dirs", List.of("north", "south"));
            params.put("branch_spacing", 8);
            params.put("branch_interval", 2);
            params.put("branch_max_length", 3);
            params.put("branch_balance_mode", "alternate");
            params.put("allow_terminal_hub", true);
            params.put("terminal_hub_size", 2);
        } else if ("CLUSTER".equals(normalized)) {
            params.put("cluster_count", 3);
            params.put("cluster_radius", 14);
            params.put("cluster_spacing", 18);
            params.put("cluster_shape", "ellipse");
            params.put("scatter_mode", "weighted_random");
            params.put("allow_micro_paths", true);
            params.put("intra_cluster_branch_limit", 2);
            params.put("cluster_center_bias", "medium");
            params.put("edge_avoidance", 0.7);
            params.put("overlap_tolerance", 0.0);
        }
        return params;
    }

    private static String inferComponentRole(String groupId) {
        String normalized = safe(groupId).toLowerCase(Locale.ROOT);
        if (normalized.contains("port")) return "dock_head";
        if (normalized.contains("market")) return "market_stall";
        if (normalized.contains("civic")) return "centerpiece";
        if (normalized.contains("farm")) return "farm_plot";
        return "primary";
    }

    private static ComponentRule defaultRuleForArrangement(String arrangementType, String role) {
        ComponentRule rule = new ComponentRule();
        String arrangement = safe(arrangementType).toUpperCase(Locale.ROOT);
        String normalizedRole = safe(role).toLowerCase(Locale.ROOT);
        if ("LINEAR_DOCK".equals(arrangement)) {
            rule.anchor_preference = "edge_near_water";
            rule.min_spacing = 10;
            rule.snap_to_water = true;
            rule.prefer_edge = true;
            rule.prefer_axis = "x";
        } else if ("COURTYARD".equals(arrangement)) {
            rule.anchor_preference = "primary_center";
            rule.min_spacing = 8;
            rule.prefer_axis = "radial";
        } else if ("SPINE_BRANCH".equals(arrangement)) {
            rule.anchor_preference = "primary_center";
            rule.min_spacing = 9;
            rule.prefer_axis = "long_axis";
        } else if ("TERRACE_CHAIN".equals(arrangement)) {
            rule.anchor_preference = "slope_mid";
            rule.min_spacing = 8;
            rule.prefer_axis = "slope";
        }
        if (normalizedRole.contains("center")) {
            rule.max_distance_from_anchor = 16;
        }
        return rule;
    }

    private static List<String> chooseCandidates(
            String category,
            String sizeTier,
            String functionRole,
            String groupId,
            String arrangementType,
            String startConnectorDir,
            boolean rootCandidate,
            Catalog catalog,
            TemplateSelectionItem item,
            C7Selection selection
    ) {
        StructureTemplateQueryService.QueryRequest request = new StructureTemplateQueryService.QueryRequest();
        request.function_tag = functionRole;
        request.size_tier = sizeTier;
        request.arrangement_type = arrangementType;
        request.require_connector = false;
        request.strict_tag_source = true;
        StructureTemplateQueryService.QueryResult queryResult = chooseCandidatesFromCatalog(request, groupId, startConnectorDir, rootCandidate, catalog);
        if (item != null) {
            item.filtered_candidate_count = queryResult.candidate_count;
            item.strict_filter_failure_reason = queryResult.failure_reason;
        }
        if (selection != null) {
            selection.filtered_candidate_count = Math.max(selection.filtered_candidate_count, queryResult.candidate_count);
            if (!queryResult.ok && (selection.strict_filter_failure_reason == null || selection.strict_filter_failure_reason.isBlank())) {
                selection.strict_filter_failure_reason = queryResult.failure_reason;
                selection.ok = false;
            }
        }
        return queryResult.candidates.stream().map(candidate -> candidate.structure_id).toList();
    }

    private static StructureTemplateQueryService.QueryResult chooseCandidatesFromCatalog(
            StructureTemplateQueryService.QueryRequest request,
            String groupId,
            String startConnectorDir,
            boolean rootCandidate,
            Catalog catalog
    ) {
        StructureTemplateQueryService.QueryResult empty = new StructureTemplateQueryService.QueryResult();
        empty.function_tag = request.function_tag;
        if (catalog == null || !catalog.ok || catalog.structures == null || catalog.structures.isEmpty()) {
            empty.ok = false;
            empty.failure_reason = "catalog_not_found";
            return empty;
        }

        String tier = normalizeTier(request.size_tier);
        String normalizedFunction = StructureTemplateQueryService.normalizeCatalogFunction(request.function_tag);
        String preferredPool = inferPreferredPool(groupId, request.arrangement_type, normalizedFunction);
        StructureTemplateQueryService.QueryResult filtered = StructureTemplateQueryService.queryTemplates(catalog.structures, request);
        if (!filtered.ok) return filtered;
        List<ScoredTemplate> scored = new ArrayList<>();
        for (StructureTemplateQueryService.Candidate candidate : filtered.candidates) {
            CatalogStructure structure = findStructure(catalog, candidate.structure_id);
            if (structure == null) continue;
            double score = scoreStructure(structure, tier, normalizedFunction, groupId, preferredPool, startConnectorDir, rootCandidate);
            if (score <= 0.0) continue;
            scored.add(new ScoredTemplate(structure.structure_id, score));
        }
        scored.sort(Comparator.comparingDouble(ScoredTemplate::score).reversed().thenComparing(ScoredTemplate::id));

        List<StructureTemplateQueryService.Candidate> ranked = new ArrayList<>();
        for (ScoredTemplate candidate : scored) {
            StructureTemplateQueryService.Candidate metadata = filtered.candidates.stream()
                    .filter(item -> candidate.id().equals(item.structure_id))
                    .findFirst()
                    .orElse(null);
            if (metadata != null) ranked.add(metadata);
            if (ranked.size() >= 6) break;
        }
        filtered.candidates = ranked;
        filtered.candidate_count = ranked.size();
        filtered.ok = !ranked.isEmpty();
        if (!filtered.ok) filtered.failure_reason = "no_candidates_after_strict_function_filter";
        return filtered;
    }

    private static double scoreStructure(
            CatalogStructure structure,
            String sizeTier,
            String functionRole,
            String groupId,
            String preferredPool,
            String startConnectorDir,
            boolean rootCandidate
    ) {
        double score = 0.0;
        String pieceRole = safe(structure.piece_role).toUpperCase(Locale.ROOT);
        if ("START".equals(pieceRole)) score += rootCandidate ? 0.48 : 0.20;
        else if ("SINGLE".equals(pieceRole)) score += rootCandidate ? 0.34 : 0.16;
        else if ("MIDDLE".equals(pieceRole)) score += 0.06;
        else if ("END".equals(pieceRole)) score -= rootCandidate ? 0.18 : 0.0;

        if (rootCandidate && !"START".equals(pieceRole) && !"SINGLE".equals(pieceRole)) {
            score -= 0.10;
        }

        String structureTier = normalizeTier(structure.size_tier);
        if (sizeTier.equals(structureTier)) score += 0.20;
        else if (isNeighborTier(sizeTier, structureTier)) score += 0.10;

        double bestFunctionScore = 0.0;
        if (structure.function_candidates != null) {
            for (CityC35CatalogIO.FunctionCandidate candidate : structure.function_candidates) {
                if (candidate == null) continue;
                String actual = StructureTemplateQueryService.normalizeCatalogFunction(candidate.function);
                if (functionRole.equals(actual)) bestFunctionScore = Math.max(bestFunctionScore, candidate.score);
                else if (isCompatibleFunction(functionRole, actual)) bestFunctionScore = Math.max(bestFunctionScore, candidate.score * 0.75);
            }
        }
        score += bestFunctionScore;

        double poolScore = scorePoolAffinity(structure, preferredPool);
        if (!preferredPool.isBlank() && poolScore <= 0.0) score -= 0.05;
        score += poolScore;

        if (rootCandidate) {
            if (!supportsSeedConnector(structure, startConnectorDir)) {
                return 0.0;
            }
            score += 0.36;
        }

        String path = safe(structure.path).toLowerCase(Locale.ROOT);
        String group = safe(groupId).toLowerCase(Locale.ROOT);
        if (group.contains("port") && (path.contains("ocean") || path.contains("ship") || path.contains("lighthouse") || path.contains("harbor") || path.contains("port"))) score += 0.12;
        if ((group.contains("defense") || group.contains("tower")) && (path.contains("tower") || path.contains("outpost"))) score += 0.08;
        if (group.contains("market") && (path.contains("market") || path.contains("shop"))) score += 0.08;
        if (group.contains("farm") && path.contains("farm")) score += 0.08;
        return score;
    }

    private static String inferPreferredPool(String groupId, String arrangementType, String functionRole) {
        String group = safe(groupId).toLowerCase(Locale.ROOT);
        String arrangement = safe(arrangementType).toLowerCase(Locale.ROOT);
        String function = safe(functionRole).toLowerCase(Locale.ROOT);
        if (group.contains("port") || arrangement.contains("dock") || function.contains("port")) return "port";
        if (group.contains("market") || function.contains("market") || function.contains("commercial")) return "market";
        if (group.contains("farm") || function.contains("farm")) return "farm";
        if (group.contains("civic") || function.contains("civic")) return "civic";
        if (group.contains("residential") || function.contains("residential")) return "residential";
        return "";
    }

    private static double scorePoolAffinity(CatalogStructure structure, String preferredPool) {
        if (structure == null || preferredPool == null || preferredPool.isBlank()) return 0.0;
        String pool = safe(structure.preset_pool).toLowerCase(Locale.ROOT);
        if (pool.isBlank()) return 0.0;
        if (pool.contains(preferredPool)) return 0.42;
        String path = safe(structure.path).toLowerCase(Locale.ROOT);
        return path.contains(preferredPool) ? 0.18 : 0.0;
    }

    private static boolean supportsSeedConnector(CatalogStructure structure, String startConnectorDir) {
        DirectionMatch match = resolveSeedConnector(structure, startConnectorDir);
        return match != null;
    }

    private static int resolveStartRotation(CatalogStructure structure, String startConnectorDir, String arrangementType) {
        DirectionMatch match = resolveSeedConnector(structure, startConnectorDir);
        if (match != null) return match.rotation;
        return defaultRotation(arrangementType);
    }

    private static DirectionMatch resolveSeedConnector(CatalogStructure structure, String startConnectorDir) {
        if (structure == null || startConnectorDir == null || startConnectorDir.isBlank()) return null;
        String wanted = startConnectorDir.trim().toLowerCase(Locale.ROOT);
        if (structure.connectors != null && !structure.connectors.isEmpty()) {
            for (Integer rotation : allowedRotations(structure)) {
                for (CityC35CatalogIO.ConnectorSpec connector : structure.connectors) {
                    if (connector == null) continue;
                    String facing = rotateDirection(connector.facing, rotation);
                    if (wanted.equals(facing)) return new DirectionMatch(rotation);
                }
            }
        }
        if (structure.connector_dirs != null) {
            for (String dir : structure.connector_dirs) {
                if (wanted.equalsIgnoreCase(dir)) return new DirectionMatch(rotationForDirection(wanted));
            }
        }
        if (structure.orientation != null && structure.orientation.jigsaw_facing != null) {
            for (String dir : structure.orientation.jigsaw_facing) {
                if (wanted.equalsIgnoreCase(dir)) return new DirectionMatch(rotationForDirection(wanted));
            }
        }
        return null;
    }

    private static List<Integer> allowedRotations(CatalogStructure structure) {
        if (structure != null && structure.constraints != null && structure.constraints.allowed_rotations != null && !structure.constraints.allowed_rotations.isEmpty()) {
            return structure.constraints.allowed_rotations;
        }
        if (structure != null && structure.orientation != null && structure.orientation.rotations != null && !structure.orientation.rotations.isEmpty()) {
            return structure.orientation.rotations;
        }
        return List.of(0, 90, 180, 270);
    }

    private static String rotateDirection(String direction, int rotation) {
        String base = safe(direction).toLowerCase(Locale.ROOT);
        List<String> order = List.of("north", "east", "south", "west");
        int index = order.indexOf(base);
        if (index < 0) return base;
        int turns = (((rotation % 360) + 360) % 360) / 90;
        return order.get((index + turns) % order.size());
    }

    private static int rotationForDirection(String direction) {
        return switch (safe(direction).toLowerCase(Locale.ROOT)) {
            case "east" -> 90;
            case "south" -> 180;
            case "west" -> 270;
            default -> 0;
        };
    }

    private static String inferSizeTierFromTemplate(String templateId, Catalog catalog) {
        CatalogStructure structure = findStructure(catalog, templateId);
        return structure != null ? normalizeTier(structure.size_tier) : "M";
    }

    private static boolean isNeighborTier(String wanted, String actual) {
        if ("M".equals(wanted) && ("S".equals(actual) || "L".equals(actual))) return true;
        if ("S".equals(wanted) && "M".equals(actual)) return true;
        if ("L".equals(wanted) && "M".equals(actual)) return true;
        return false;
    }

    private static boolean isCompatibleFunction(String wanted, String actual) {
        if (wanted.equals(actual)) return true;
        if ("port".equals(wanted) && ("fishing".equals(actual) || "civic_center".equals(actual))) return true;
        if ("civic_center".equals(wanted) && ("civic_center".equals(actual) || "commercial".equals(actual))) return true;
        if ("residential".equals(wanted) && "residential".equals(actual)) return true;
        if ("military".equals(wanted) && ("military".equals(actual) || "port".equals(actual))) return true;
        if ("commercial".equals(wanted) && ("market".equals(actual) || "commercial".equals(actual))) return true;
        return false;
    }

    private static String inferFunctionRole(String category, String moduleId, String groupId) {
        String g = safe(groupId).toLowerCase(Locale.ROOT);
        if (g.contains("port")) return "port";
        if (g.contains("market")) return "market";
        if (g.contains("shop")) return "commercial";
        if (g.contains("school")) return "civic_center";
        if (g.contains("farm")) return "farm";
        if (g.contains("defense") || g.contains("tower") || g.contains("fort")) return "military";
        if (g.contains("residential")) return "residential";

        String c = safe(category).toLowerCase(Locale.ROOT);
        String m = safe(moduleId).toLowerCase(Locale.ROOT);
        if (c.contains("plaza") || c.contains("civic") || m.contains("core")) return "civic_center";
        if (m.contains("market")) return "market";
        if (m.contains("shop")) return "commercial";
        if (m.contains("military") || m.contains("fort")) return "military";
        if (m.contains("port") || m.contains("dock") || m.contains("harbor")) return "port";
        if (m.contains("farm")) return "farm";
        return "residential";
    }

    private static String inferInteractionRole(String functionRole) {
        if ("civic_center".equals(functionRole) || "market".equals(functionRole)) return "FRONT_TO_PLAZA";
        if ("military".equals(functionRole) || "port".equals(functionRole)) return "EDGE_ATTACH";
        return "FRONT_TO_STREET";
    }

    private static List<String> merge(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        if (a != null) out.addAll(a);
        if (b != null) {
            for (String s : b) if (!out.contains(s)) out.add(s);
        }
        return out;
    }

    private static String normalizeTier(String raw) {
        String s = safe(raw).toUpperCase(Locale.ROOT);
        return Arrays.asList("S", "M", "L").contains(s) ? s : "M";
    }

    private static Catalog loadCatalog() {
        try {
            Catalog catalog = CityC35CatalogIO.loadCatalog(GSON, Catalog.class);
            if (catalog == null) {
                System.out.println("[C7] loadCatalog result=null");
                return null;
            }
            catalog.ok = catalog.ok && catalog.structures != null;
            System.out.println("[C7] loadCatalog ok=" + catalog.ok + " structure_count=" + (catalog.structures != null ? catalog.structures.size() : 0));
            return catalog;
        } catch (Exception ignored) {
            System.out.println("[C7] loadCatalog exception=" + ignored.getClass().getSimpleName() + " msg=" + ignored.getMessage());
            return null;
        }
    }

    private static CatalogStructure findStructure(Catalog catalog, String structureId) {
        if (catalog == null || catalog.structures == null || structureId == null || structureId.isBlank()) return null;
        for (CatalogStructure structure : catalog.structures) {
            if (structure != null && structureId.equals(structure.structure_id)) return structure;
        }
        return null;
    }

    private static CityC6Stages.LayoutPlan findPlan(CityC6Stages.C6Layout layout, String groupId, String buildAreaId) {
        if (layout == null || layout.plans == null) return null;
        for (CityC6Stages.LayoutPlan plan : layout.plans) {
            if (plan == null) continue;
            if (groupId != null && groupId.equals(plan.group_id)) return plan;
            if (buildAreaId != null && buildAreaId.equals(plan.build_area_id)) return plan;
        }
        return null;
    }

    private static String readString(JsonObject json, String key) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) return null;
        return json.get(key).getAsString();
    }

    private static String safe(String raw) {
        return raw == null ? "" : raw;
    }

    private record ScoredTemplate(String id, double score) {}

    private record DirectionMatch(int rotation) {}
}
