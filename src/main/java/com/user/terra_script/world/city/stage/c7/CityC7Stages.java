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
        /** 当前 C7 工头计划契约版本。 */
        public int version = 2;
        public String step = "C7";
        public boolean ok = true;
        public String city_id;
        public long generated_at_epoch_ms;
        public String catalog_source = "hardcoded_vanilla_village_templates";
        public String decision_source = "program_fallback";
        public String selection_mode = "strict_function_filter";
        public boolean strict_tag_source = true;
        public int filtered_candidate_count;
        public String strict_filter_failure_reason;
        public int puzzle_depth = 0;
        /** 兼容保留：旧版模板选择结果。 */
        public List<TemplateSelectionItem> selections = new ArrayList<>();
        /** 兼容保留：旧版排列决策结果。 */
        public List<GroupArrangementDecision> arrangements = new ArrayList<>();
        /** 当前主工头计划视图；group 级文件默认只保留一个。 */
        public ForemanPlan foreman_plan;
        /** 城市级场景下保留全部工头计划。 */
        public List<ForemanPlan> foreman_plans = new ArrayList<>();
        /** 当前主工头计划对应的阶段列表，便于直接读取。 */
        public List<PhasePlan> phase_list = new ArrayList<>();
    }

    public static class ForemanPlan {
        /** 所属组编号。 */
        public String group_id;
        /** 所属建造区编号。 */
        public String build_area_id;
        /** 全局建设目标，统一使用中文自然语言。 */
        public String global_goal;
        /** 唯一开工节点。 */
        public StartNode start_node = new StartNode();
        /** 当前工头计划的阶段列表。 */
        public List<PhasePlan> phase_list = new ArrayList<>();
        /** 全局硬约束说明；说明值统一中文，协议值保持稳定机器值。 */
        public Map<String, Object> global_constraints = new LinkedHashMap<>();
        /** 连接器目标规则。 */
        public List<ConnectorTargetRule> connector_target_rules = new ArrayList<>();
        /** 模板池引用。 */
        public List<TemplatePoolRef> template_pool_refs = new ArrayList<>();
        /** 工头备注，统一使用中文自然语言。 */
        public String foreman_notes;
    }

    public static class PhasePlan {
        /** 阶段稳定键，供程序排序和流转使用。 */
        public String phase_key;
        /** 阶段中文短句，供 AI 和调试显示使用。 */
        public String phase_name;
        /** 阶段目标，统一使用中文自然语言。 */
        public String phase_goal;
        /** 阶段禁区，统一使用中文自然语言。 */
        public String phase_blockers;
        /** 阶段完成提示，统一使用中文自然语言。 */
        public String phase_done_hint;
    }

    public static class StartNode {
        /** 节点稳定编号。 */
        public String node_id;
        /** 节点类型；若用于协议判定则保持稳定机器值。 */
        public String node_type;
        /** 所属阶段稳定键。 */
        public String phase_key;
        /** 所属阶段中文名称。 */
        public String phase_name;
        /** 节点目标职责，统一使用中文自然语言。 */
        public String target_role;
        /** 节点目标结构种类，统一使用中文自然语言。 */
        public String target_structure_kind;
        /** 来源连接器编号；根节点允许为空。 */
        public String source_connector_id;
        /** 允许连接方向，继续使用稳定英文枚举。 */
        public List<String> allowed_connector_dirs = new ArrayList<>();
        /** 模板池编号。 */
        public String template_pool_id;
        /** 候选模板列表。 */
        public List<String> candidate_template_ids = new ArrayList<>();
        /** 是否必需。 */
        public boolean required = true;
    }

    public static class ConnectorTargetRule {
        /** 规则所属阶段稳定键。 */
        public String phase_key;
        /** 来源连接器方向。 */
        public String connector_dir;
        /** 目标职责说明，统一使用中文自然语言。 */
        public String target_role;
        /** 规则说明，统一使用中文自然语言。 */
        public String rule_note;
    }

    public static class TemplatePoolRef {
        /** 模板池编号。 */
        public String template_pool_id;
        /** 模板池显示名称，统一使用中文自然语言。 */
        public String display_name;
        /** 引用模板列表。 */
        public List<String> template_ids = new ArrayList<>();
        /** 模板池说明，统一使用中文自然语言。 */
        public String note;
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
        return generate(cityId, c6Layout, true);
    }

    public static C7Selection generate(String cityId, CityC6Stages.C6Layout c6Layout, boolean strictTagSource) {
        C7Selection result = new C7Selection();
        result.city_id = cityId;
        result.generated_at_epoch_ms = System.currentTimeMillis();
        result.strict_tag_source = strictTagSource;
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
            GroupArrangementDecision arrangement = buildFallbackArrangement(plan, catalog, result);
            result.arrangements.add(arrangement);
            result.foreman_plans.add(buildForemanPlan(plan, arrangement, null, catalog));
            for (CityC6Stages.PrimaryModule module : plan.primary_modules) {
                TemplateSelectionItem item = buildFallbackSelection(plan, module, catalog, result);
                result.selections.add(item);
            }
        }
        return finalizeSelectionResult(result);
    }

    public static C7Selection fromDecisionRequest(String cityId, CityC6Stages.C6Layout c6Layout, JsonObject request) {
        return fromDecisionRequest(cityId, c6Layout, request, true);
    }

    public static C7Selection fromDecisionRequest(String cityId, CityC6Stages.C6Layout c6Layout, JsonObject request, boolean strictTagSource) {
        C7Selection result = new C7Selection();
        result.city_id = cityId;
        result.generated_at_epoch_ms = System.currentTimeMillis();
        result.decision_source = "ai_decision_submit";
        result.selection_mode = "ai_decision_submit";
        result.strict_tag_source = strictTagSource;
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
                List<TemplateSelectionItem> expanded = expandSelectionsFromArrangement(arrangement, catalog);
                result.selections.addAll(expanded);
                result.foreman_plans.add(buildForemanPlan(findPlan(c6Layout, arrangement.group_id, arrangement.build_area_id), arrangement, expanded, catalog));
            }
        }
        return finalizeSelectionResult(result);
    }

    private static C7Selection finalizeSelectionResult(C7Selection result) {
        result.selections.sort(Comparator.comparing(i -> safe(i.build_area_id) + "|" + safe(i.module_id)));
        result.arrangements.sort(Comparator.comparing(i -> safe(i.build_area_id) + "|" + safe(i.group_id)));
        result.foreman_plans.sort(Comparator.comparing(i -> safe(i.build_area_id) + "|" + safe(i.group_id)));
        result.foreman_plan = result.foreman_plans.isEmpty() ? null : result.foreman_plans.get(0);
        result.phase_list = result.foreman_plan != null
                ? new ArrayList<>(result.foreman_plan.phase_list)
                : new ArrayList<>();
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
        String functionRole = null;
        String interactionRole = null;
        if (module != null && module.template_hint != null) {
            category = module.template_hint.category != null ? module.template_hint.category : category;
            sizeTier = module.template_hint.size_tier != null ? module.template_hint.size_tier : sizeTier;
            functionRole = module.template_hint.function_tag;
            interactionRole = module.template_hint.interaction_role;
        }
        if (module != null && module.structure_guidance != null) {
            if (module.structure_guidance.function_tag != null && !module.structure_guidance.function_tag.isBlank()) {
                functionRole = module.structure_guidance.function_tag;
            }
            if (module.structure_guidance.interaction_role != null && !module.structure_guidance.interaction_role.isBlank()) {
                interactionRole = module.structure_guidance.interaction_role;
            }
            if (module.structure_guidance.target_size_tiers != null && !module.structure_guidance.target_size_tiers.isEmpty()) {
                sizeTier = module.structure_guidance.target_size_tiers.get(0);
            }
        }
        item.size_tier = normalizeTier(sizeTier);
        item.function_role = functionRole != null && !functionRole.isBlank()
                ? functionRole
                : inferFunctionRole(category, item.module_id, plan.group_id);
        item.interaction_role = interactionRole != null && !interactionRole.isBlank()
                ? interactionRole
                : inferInteractionRole(item.function_role);
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
        candidates = prioritizeGuidanceCandidates(candidates, module);
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
                ? "目录严格筛选后得到的默认模板方案"
                : "严格功能筛选后没有找到可用模板";
        return item;
    }

    private static ForemanPlan buildForemanPlan(
            CityC6Stages.LayoutPlan plan,
            GroupArrangementDecision arrangement,
            List<TemplateSelectionItem> expandedSelections,
            Catalog catalog
    ) {
        ForemanPlan foremanPlan = new ForemanPlan();
        foremanPlan.group_id = arrangement != null ? arrangement.group_id : (plan != null ? plan.group_id : null);
        foremanPlan.build_area_id = arrangement != null ? arrangement.build_area_id : (plan != null ? plan.build_area_id : null);
        foremanPlan.global_goal = inferGlobalGoal(arrangement, plan);
        foremanPlan.foreman_notes = inferForemanNotes(arrangement, plan);
        foremanPlan.global_constraints.put("单一起点说明", "当前阶段只允许一个起始节点，先把主线稳定下来。");
        foremanPlan.global_constraints.put("施工顺序说明", "先按阶段推进，再按节点队列逐个施工。");
        foremanPlan.global_constraints.put("失败处理说明", "硬约束失败后只记录原因并等待后续重试，不自动改写祖先结构。");
        foremanPlan.global_constraints.put("允许放宽项", "当前仅允许按需放宽地形落地限制。");

        List<TemplateSelectionItem> selections = expandedSelections;
        if ((selections == null || selections.isEmpty()) && arrangement != null) {
            selections = expandSelectionsFromArrangement(arrangement, catalog);
        }
        TemplateSelectionItem primarySelection = selections != null && !selections.isEmpty() ? selections.get(0) : null;
        SelectedComponent primaryComponent = arrangement != null && arrangement.selected_components != null && !arrangement.selected_components.isEmpty()
                ? arrangement.selected_components.get(0)
                : null;
        foremanPlan.phase_list.addAll(defaultPhasePlans(arrangement, primarySelection, plan));

        StartNode startNode = foremanPlan.start_node;
        startNode.node_id = "start_" + safe(foremanPlan.group_id);
        startNode.node_type = "START";
        startNode.phase_key = !foremanPlan.phase_list.isEmpty() ? foremanPlan.phase_list.get(0).phase_key : "phase1";
        startNode.phase_name = !foremanPlan.phase_list.isEmpty() ? foremanPlan.phase_list.get(0).phase_name : "先立主体骨架";
        startNode.target_role = inferStartTargetRole(arrangement, primarySelection);
        startNode.target_structure_kind = inferTargetStructureKind(arrangement);
        startNode.source_connector_id = null;
        startNode.allowed_connector_dirs.addAll(defaultStartDirs(arrangement));
        startNode.template_pool_id = inferTemplatePoolId(arrangement, primarySelection);
        if (primarySelection != null && primarySelection.top_k_templates != null) {
            startNode.candidate_template_ids.addAll(primarySelection.top_k_templates);
        }
        if (startNode.candidate_template_ids.isEmpty() && primarySelection != null && primarySelection.selected_template != null) {
            startNode.candidate_template_ids.add(primarySelection.selected_template);
        }
        if (startNode.candidate_template_ids.isEmpty() && primaryComponent != null && primaryComponent.template_id != null) {
            startNode.candidate_template_ids.add(primaryComponent.template_id);
        }
        startNode.required = primaryComponent == null || primaryComponent.required;

        TemplatePoolRef poolRef = new TemplatePoolRef();
        poolRef.template_pool_id = startNode.template_pool_id != null ? startNode.template_pool_id : "pool_default";
        poolRef.display_name = "主体开工模板池";
        poolRef.template_ids.addAll(startNode.candidate_template_ids);
        poolRef.note = "该模板池用于当前组的首个开工节点，后续节点只能在程序允许的模板池范围内继续选择。";
        foremanPlan.template_pool_refs.add(poolRef);

        for (PhasePlan phasePlan : foremanPlan.phase_list) {
            ConnectorTargetRule rule = new ConnectorTargetRule();
            rule.phase_key = phasePlan.phase_key;
            rule.connector_dir = defaultConnectorDir(arrangement != null ? arrangement.arrangement_type : null);
            rule.target_role = phaseTargetRole(phasePlan.phase_key, arrangement);
            rule.rule_note = phaseRuleNote(phasePlan.phase_key);
            foremanPlan.connector_target_rules.add(rule);
        }
        return foremanPlan;
    }

    private static SelectedComponent buildFallbackComponent(CityC6Stages.LayoutPlan plan, CityC6Stages.PrimaryModule module, Catalog catalog, C7Selection selection) {
        SelectedComponent component = new SelectedComponent();
        component.component_id = module != null && module.module_id != null ? module.module_id : ("component_" + safe(plan.group_id));
        component.role = inferComponentRole(plan.group_id);

        String category = module != null && module.template_hint != null ? module.template_hint.category : "residential";
        String sizeTier = module != null && module.template_hint != null ? module.template_hint.size_tier : "M";
        String functionRole = module != null && module.template_hint != null && module.template_hint.function_tag != null
                ? module.template_hint.function_tag
                : inferFunctionRole(category, component.component_id, plan.group_id);
        if (module != null && module.structure_guidance != null) {
            if (module.structure_guidance.function_tag != null && !module.structure_guidance.function_tag.isBlank()) {
                functionRole = module.structure_guidance.function_tag;
            }
            if (module.structure_guidance.target_size_tiers != null && !module.structure_guidance.target_size_tiers.isEmpty()) {
                sizeTier = module.structure_guidance.target_size_tiers.get(0);
            }
        }
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
        candidates = prioritizeGuidanceCandidates(candidates, module);
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

    private static List<PhasePlan> defaultPhasePlans(
            GroupArrangementDecision arrangement,
            TemplateSelectionItem primarySelection,
            CityC6Stages.LayoutPlan plan
    ) {
        String functionRole = primarySelection != null ? safe(primarySelection.function_role).toLowerCase(Locale.ROOT) : "";
        String startRole = semanticStartRole(functionRole);
        String bodyKind = semanticBodyKind(functionRole);
        String supportKind = semanticSupportKind(functionRole);
        List<PhasePlan> out = new ArrayList<>();
        out.add(phase("phase1", "先把" + startRole + "立住", "优先把" + startRole + "和第一段" + bodyKind + "稳定下来。", "不要一开始就把外围部分同时铺开。", startRole + "和首个主体连接稳定后即可进入下一阶段。"));
        out.add(phase("phase2", "再推进主体主线", "顺着当前已经成立的主体方向继续把" + bodyKind + "往前推进。", "不要在主体主线还不稳定时四处分叉。", bodyKind + "的连续骨架形成后即可进入下一阶段。"));
        out.add(phase("phase3", "再补整体附属", "围绕已经稳定的主体补齐" + supportKind + "和过渡节点。", "不要越过主体直接跳到最后收尾。", supportKind + "与主体的连接关系稳定后即可进入下一阶段。"));
        out.add(phase("phase4", "最后处理边缘收尾", "处理边缘空缺、末端节点和整体收尾。", "不要回头推翻已经稳定的主体骨架。", "边缘空缺与收尾节点处理完成后即可结束本轮施工。"));
        return out;
    }

    private static String semanticStartRole(String functionRole) {
        return switch (safe(functionRole).toLowerCase(Locale.ROOT)) {
            case "port" -> "起始岸线节点";
            case "market", "commercial" -> "入口与前场";
            case "civic_center" -> "主入口和中轴起点";
            case "farm" -> "坡地入口节点";
            default -> "起始节点";
        };
    }

    private static String semanticBodyKind(String functionRole) {
        return switch (safe(functionRole).toLowerCase(Locale.ROOT)) {
            case "port" -> "主体岸线";
            case "market", "commercial" -> "主体空间";
            case "civic_center" -> "主体建筑";
            case "farm" -> "主体平台";
            default -> "主体部分";
        };
    }

    private static String semanticSupportKind(String functionRole) {
        return switch (safe(functionRole).toLowerCase(Locale.ROOT)) {
            case "port" -> "侧向附属";
            case "market", "commercial" -> "周边附属";
            case "civic_center" -> "两侧附属";
            case "farm" -> "平台附属";
            default -> "附属部分";
        };
    }

    private static PhasePlan phase(String key, String name, String goal, String blockers, String doneHint) {
        PhasePlan phase = new PhasePlan();
        phase.phase_key = key;
        phase.phase_name = name;
        phase.phase_goal = goal;
        phase.phase_blockers = blockers;
        phase.phase_done_hint = doneHint;
        return phase;
    }

    private static String inferGlobalGoal(GroupArrangementDecision arrangement, CityC6Stages.LayoutPlan plan) {
        String arrangementType = arrangement != null ? safe(arrangement.arrangement_type).toUpperCase(Locale.ROOT) : inferArrangementType(plan != null ? plan.group_id : null);
        return switch (arrangementType) {
            case "LINEAR_DOCK" -> "沿主干方向先立住核心起点，再顺着主要连接链逐段向前推进。";
            case "COURTYARD", "RING" -> "优先围绕中心入口与前庭建立主体框架，再补足两侧和外围收尾。";
            case "SPINE_BRANCH" -> "先把主轴搭出来，再逐步补两侧附属节点，避免一开始就四处分叉。";
            case "TERRACE_CHAIN" -> "优先沿坡地或台地顺序修出主线，再补局部平台和收尾节点。";
            default -> "先把主结构和入口骨架定住，再逐步向两侧和外围扩展。";
        };
    }

    private static String inferForemanNotes(GroupArrangementDecision arrangement, CityC6Stages.LayoutPlan plan) {
        String groupId = arrangement != null ? arrangement.group_id : (plan != null ? plan.group_id : "");
        return "当前组 " + safe(groupId) + " 采用单一起点、逐节点推进的施工方式；每次只处理一个活跃节点，失败后先记录原因，再决定是否重试。";
    }

    private static String inferStartTargetRole(GroupArrangementDecision arrangement, TemplateSelectionItem primarySelection) {
        if (primarySelection != null && primarySelection.function_role != null) {
            return switch (primarySelection.function_role) {
                case "port" -> "沿岸起始节点";
                case "market" -> "前场入口节点";
                case "civic_center" -> "中轴主厅节点";
                case "farm" -> "台地主入口节点";
                default -> "主体开工节点";
            };
        }
        String arrangementType = arrangement != null ? safe(arrangement.arrangement_type).toUpperCase(Locale.ROOT) : "";
        if ("LINEAR_DOCK".equals(arrangementType)) return "码头起始节点";
        if ("COURTYARD".equals(arrangementType) || "RING".equals(arrangementType)) return "入口起始节点";
        if ("SPINE_BRANCH".equals(arrangementType)) return "中轴起始节点";
        return "主体开工节点";
    }

    private static String inferTargetStructureKind(GroupArrangementDecision arrangement) {
        String arrangementType = arrangement != null ? safe(arrangement.arrangement_type).toUpperCase(Locale.ROOT) : "";
        if ("LINEAR_DOCK".equals(arrangementType)) return "主码头";
        if ("COURTYARD".equals(arrangementType) || "RING".equals(arrangementType)) return "主厅";
        if ("SPINE_BRANCH".equals(arrangementType)) return "主轴主体";
        if ("TERRACE_CHAIN".equals(arrangementType)) return "台地主体";
        return "主体建筑";
    }

    private static List<String> defaultStartDirs(GroupArrangementDecision arrangement) {
        String arrangementType = arrangement != null ? safe(arrangement.arrangement_type).toUpperCase(Locale.ROOT) : "";
        if ("COURTYARD".equals(arrangementType) || "RING".equals(arrangementType)) return List.of("south", "east");
        if ("SPINE_BRANCH".equals(arrangementType)) return List.of("east", "south");
        return List.of(defaultConnectorDir(arrangementType));
    }

    private static String inferTemplatePoolId(GroupArrangementDecision arrangement, TemplateSelectionItem primarySelection) {
        if (primarySelection != null && primarySelection.function_role != null && !primarySelection.function_role.isBlank()) {
            return "pool_" + primarySelection.function_role.toLowerCase(Locale.ROOT);
        }
        if (arrangement != null && arrangement.group_id != null && !arrangement.group_id.isBlank()) {
            return "pool_" + arrangement.group_id.toLowerCase(Locale.ROOT);
        }
        return "pool_default";
    }

    private static String phaseTargetRole(String phaseKey, GroupArrangementDecision arrangement) {
        if ("phase1".equals(phaseKey)) return "前庭入口节点";
        if ("phase2".equals(phaseKey)) return inferTargetStructureKind(arrangement);
        if ("phase3".equals(phaseKey)) return "两侧附属节点";
        return "外围收尾节点";
    }

    private static String phaseRuleNote(String phaseKey) {
        if ("phase1".equals(phaseKey)) return "当前阶段优先连接入口方向，不要过早转向外围。";
        if ("phase2".equals(phaseKey)) return "当前阶段优先维持主轴或主厅连续性。";
        if ("phase3".equals(phaseKey)) return "当前阶段允许向两侧展开，但仍要服从主体骨架。";
        return "当前阶段以补空缺和收尾为主，不再主动扩张主结构。";
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
        request.strict_tag_source = selection == null || selection.strict_tag_source;
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
            boolean hasConnectors = structure.connectors != null && !structure.connectors.isEmpty();
            if (hasConnectors && !supportsSeedConnector(structure, startConnectorDir)) {
                return 0.0;
            }
            score += hasConnectors ? 0.36 : 0.12;
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

    private static List<String> prioritizeGuidanceCandidates(List<String> candidates, CityC6Stages.PrimaryModule module) {
        if (candidates == null || candidates.isEmpty() || module == null || module.template_hint == null || module.template_hint.recommended_templates == null) {
            return candidates;
        }
        List<String> prioritized = new ArrayList<>();
        for (String templateId : module.template_hint.recommended_templates) {
            if (templateId != null && candidates.contains(templateId) && !prioritized.contains(templateId)) prioritized.add(templateId);
        }
        for (String candidate : candidates) {
            if (!prioritized.contains(candidate)) prioritized.add(candidate);
        }
        return prioritized;
    }

    private static String normalizeTier(String raw) {
        String s = safe(raw).toUpperCase(Locale.ROOT);
        return Arrays.asList("S", "M", "L").contains(s) ? s : "M";
    }

    private static Catalog loadCatalog() {
        try {
            Catalog catalog = CityC35CatalogIO.loadCatalog(GSON, Catalog.class);
            if (catalog == null) return null;
            catalog.ok = catalog.ok && catalog.structures != null;
            return catalog;
        } catch (Exception ignored) {
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
