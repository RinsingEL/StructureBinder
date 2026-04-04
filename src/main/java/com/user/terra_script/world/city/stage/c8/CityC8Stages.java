package com.user.terra_script.world.city.stage.c8;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.user.terra_script.world.city.stage.CityHeightResolver;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import com.user.terra_script.world.city.stage.c2.CityC2ScanBinaryIO;
import com.user.terra_script.world.city.stage.c6.CityC6Stages;
import com.user.terra_script.world.city.stage.c7.CityC7Stages;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CityC8Stages {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final String C8_PLAN_FILE = "C8_FoundationPlan.json";

    private CityC8Stages() {}

    public static class C8Plan {
        public String step = "C8";
        public boolean ok = true;
        public String city_id;
        /** 当前 C8 施工会话契约版本。 */
        public int version = 5;
        public long generated_at_epoch_ms;
        public List<FoundationItem> foundations = new ArrayList<>();
    }

    public static class FoundationItem {
        public String plot_id;
        public String build_area_id;
        public int build_area_numeric_id;
        public String group_id;
        public String anchor_module_id;
        public String arrangement_type;
        public Map<String, Object> arrangement_params = new LinkedHashMap<>();
        public String foundation_type;
        public String strategy;
        public int base_y;
        public int delta_height;
        public String selected_template;
        public String function_role;
        public String interaction_role;
        public List<String> top_k_templates = new ArrayList<>();
        public List<String> fallback_chain = new ArrayList<>();
        public String landing_hint;
        public String growth_axis;
        public String vertical_role;
        public int vertical_clearance;
        public boolean vertical_capable;
        public String vertical_mode_hint = "none";
        public TerrainImpactExtent terrain_impact_extent = new TerrainImpactExtent();
        public List<SupportAction> supports = new ArrayList<>();
        public TerrainMetrics terrain_metrics = new TerrainMetrics();
        /** 对应的工头计划。 */
        public CityC7Stages.ForemanPlan foreman_plan;
        /** 当前施工会话状态，使用稳定英文枚举。 */
        public String session_state = "READY_FOR_AI";
        /** 当前建造区的节点队列摘要。 */
        public QueueSummary queue_summary = new QueueSummary();
        /** 当前活跃节点。 */
        public NodeTask active_node;
        /** 当前待施工节点队列。 */
        public List<NodeTask> node_queue = new ArrayList<>();
        /** 当前已通过校验的节点。 */
        public List<NodeTask> validated_nodes = new ArrayList<>();
        /** 当前失败尝试记录。 */
        public List<FailedAttempt> failed_attempts = new ArrayList<>();
        /** 当前建造区的基台上下文。 */
        public FoundationContext foundation_context = new FoundationContext();
        /** 当前节点调试信息。 */
        public List<NodeDebugRecord> node_debug = new ArrayList<>();
        /** 兼容保留：已校验节点映射到旧 placement 结构。 */
        public List<PlacementNode> placements = new ArrayList<>();
        public boolean arrangement_success = true;
        public List<String> arrangement_errors = new ArrayList<>();
        public List<String> arrangement_warnings = new ArrayList<>();
    }

    public static class QueueSummary {
        /** 待 AI 决策节点数。 */
        public int ready_for_ai_count;
        /** 已通过校验节点数。 */
        public int validated_count;
        /** 已失败节点数。 */
        public int failed_count;
        /** 队列总节点数。 */
        public int total_count;
    }

    public static class FoundationContext {
        /** 基台类型，保留稳定英文枚举。 */
        public String foundation_type;
        /** 取高策略，保留稳定英文枚举。 */
        public String strategy;
        /** 基准高度。 */
        public int base_y;
        /** 高差。 */
        public int delta_height;
        /** 基台说明，统一使用中文自然语言。 */
        public String foundation_note;
    }

    public static class TerrainRelaxProfile {
        /** 当前放宽说明，统一使用中文自然语言。 */
        public String note = "未放宽地形限制。";
        /** 最大高度差容差。 */
        public Integer max_height_delta;
        /** 最大坡度容差。 */
        public Double max_slope;
    }

    public static class NodeTask {
        /** 节点稳定编号。 */
        public String node_id;
        /** 父节点编号。 */
        public String parent_node_id;
        /** 当前阶段稳定键。 */
        public String phase_key;
        /** 当前阶段中文名称。 */
        public String phase_name;
        /** 当前节点目标职责。 */
        public String target_role;
        /** 当前节点目标结构种类。 */
        public String target_structure_kind;
        /** 当前节点状态，使用稳定英文枚举。 */
        public String status = "READY_FOR_AI";
        /** 当前节点允许连接方向。 */
        public List<String> allowed_connector_dirs = new ArrayList<>();
        /** 当前节点来源连接器。 */
        public String incoming_connector_id;
        /** 当前模板池编号。 */
        public String template_pool_id;
        /** 当前候选模板列表。 */
        public List<String> candidate_template_ids = new ArrayList<>();
        /** 当前已选模板编号。 */
        public String selected_template_id;
        /** 当前已选连接方向。 */
        public String selected_connector_dir;
        /** 当前已选旋转角度。 */
        public Integer selected_rotation;
        /** 节点世界坐标。 */
        public Integer x;
        /** 节点世界高度。 */
        public Integer y;
        /** 节点世界坐标。 */
        public Integer z;
        /** 与旧 placement 兼容的 build_order。 */
        public Integer build_order;
        /** 当前重试次数。 */
        public int retry_count;
        /** 最近错误码。 */
        public String last_error_code;
        /** 最近错误说明，统一使用中文自然语言。 */
        public String last_error_message;
        /** 当前地形放宽配置。 */
        public TerrainRelaxProfile terrain_relax_profile = new TerrainRelaxProfile();
        /** 校验成功后生成的落位节点。 */
        public PlacementNode placement;
    }

    public static class FailedAttempt {
        /** 尝试编号。 */
        public String attempt_id;
        /** 对应节点编号。 */
        public String node_id;
        /** 当前尝试模板。 */
        public String template_id;
        /** 当前尝试方向。 */
        public String connector_dir;
        /** 错误码。 */
        public String error_code;
        /** 错误阶段。 */
        public String error_stage;
        /** 错误详情，统一使用中文自然语言。 */
        public String error_details;
        /** 是否允许重试。 */
        public boolean retryable = true;
    }

    public static class NodeDebugRecord {
        /** 对应节点编号。 */
        public String node_id;
        /** 当前调试阶段。 */
        public String stage;
        /** 当前调试说明。 */
        public String detail;
    }

    public static class NodeDecision {
        /** 提交的节点编号。 */
        public String node_id;
        /** 选择的模板编号。 */
        public String selected_template_id;
        /** 选择的连接方向。 */
        public String selected_connector_dir;
        /** 选择的旋转角度。 */
        public Integer selected_rotation;
        /** 根节点或特例节点允许直接传入坐标。 */
        public Integer x;
        public Integer z;
        /** 可选的地形放宽配置。 */
        public TerrainRelaxProfile terrain_relax_profile = new TerrainRelaxProfile();
    }

    public static class NodeSubmitResult {
        /** 是否通过校验。 */
        public boolean ok;
        /** 当前节点。 */
        public NodeTask node;
        /** 已写入的 placement。 */
        public PlacementNode placement;
        /** 当前错误码。 */
        public String error_code;
        /** 当前错误说明。 */
        public String error_message;
    }

    public static class PlacementNode {
        public String node_id;
        public String component_id;
        public String template_id;
        public String role;
        public int x;
        public int y;
        public int z;
        public int rotation;
        public int level;
        public String attach_to_component_id;
        public String parent_node_id;
        public String placement_reason;
        public Integer build_order;
        public String incoming_parent_connector_id;
        public String incoming_child_connector_id;
        public Integer incoming_parent_connector_x;
        public Integer incoming_parent_connector_z;
        public Integer incoming_child_connector_x;
        public Integer incoming_child_connector_z;
        public String incoming_connector_dir;
        public List<String> outgoing_connector_ids = new ArrayList<>();
        public boolean terminalized;
        public String fallback_terminal_template_id;
        public Integer fallback_terminal_x;
        public Integer fallback_terminal_z;
        public Integer fallback_terminal_rotation;
        public Integer footprint_min_x;
        public Integer footprint_min_z;
        public Integer footprint_max_x;
        public Integer footprint_max_z;
    }

    public static class TerrainImpactExtent {
        public int minX;
        public int minZ;
        public int maxX;
        public int maxZ;
    }

    public static class AreaGeometry {
        public String build_area_id;
        public int build_area_numeric_id;
        public int min_x;
        public int min_z;
        public int max_x;
        public int max_z;
        public double centroid_x;
        public double centroid_z;
        public boolean valid;
        public List<Long> block_keys = new ArrayList<>();
        public transient Set<Long> block_set = new LinkedHashSet<>();

        public int spanX() {
            return valid ? Math.max(1, max_x - min_x + 1) : 1;
        }

        public int spanZ() {
            return valid ? Math.max(1, max_z - min_z + 1) : 1;
        }
    }

    public static class SupportAction {
        public String type;
        public String side;
    }

    public static class TerrainMetrics {
        public int height_min;
        public int height_max;
        public double height_avg;
        public double height_p50;
        public double slope_avg;
        public int edge_n;
        public int edge_e;
        public int edge_s;
        public int edge_w;
    }

    public static C8Plan generate(
            String cityId,
            CityC6Stages.C6Summary c6Summary,
            CityC6Stages.C6Layout c6Layout,
            CityStage1BinaryIO.HeightData heightData,
            Map<Long, Integer> indexByBlock
    ) {
        return generate(cityId, c6Summary, c6Layout, null, heightData, null, indexByBlock);
    }

    public static C8Plan generate(
            String cityId,
            CityC6Stages.C6Summary c6Summary,
            CityC6Stages.C6Layout c6Layout,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            Map<Long, Integer> indexByBlock
    ) {
        return generate(cityId, c6Summary, c6Layout, null, heightData, c2ScanData, indexByBlock);
    }

    public static C8Plan generate(
            String cityId,
            CityC6Stages.C6Summary c6Summary,
            CityC6Stages.C6Layout c6Layout,
            CityC7Stages.C7Selection c7Selection,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            Map<Long, Integer> indexByBlock
    ) {
        C8Plan plan = new C8Plan();
        plan.city_id = cityId;
        plan.generated_at_epoch_ms = System.currentTimeMillis();
        if (c6Summary == null || c6Layout == null || heightData == null || indexByBlock == null || indexByBlock.isEmpty()) {
            plan.ok = false;
            return plan;
        }

        Map<Integer, List<Long>> blocksByArea = new HashMap<>();
        for (Map.Entry<Long, Integer> e : indexByBlock.entrySet()) {
            if (e == null || e.getKey() == null || e.getValue() == null) continue;
            blocksByArea.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }

        Map<String, CityC7Stages.GroupArrangementDecision> arrangements = indexArrangements(c7Selection);
        Map<String, CityC7Stages.TemplateSelectionItem> selections = indexSelections(c7Selection);
        Map<String, CityC7Stages.ForemanPlan> foremanPlans = indexForemanPlans(c7Selection);
        Map<String, CityC6Stages.LayoutPlan> plansByArea = indexPlans(c6Layout);

        List<CityC6Stages.BuildAreaSummary> areas = c6Summary.areas != null ? new ArrayList<>(c6Summary.areas) : Collections.emptyList();
        areas.sort(Comparator.comparing(a -> a.build_area_id));
        for (CityC6Stages.BuildAreaSummary area : areas) {
            if (area == null) continue;
            CityC6Stages.LayoutPlan layoutPlan = plansByArea.get(area.build_area_id);
            if (layoutPlan == null || layoutPlan.primary_modules == null || layoutPlan.primary_modules.isEmpty()) continue;
            boolean consumable = layoutPlan.validated || layoutPlan.decision_mode == null || layoutPlan.decision_mode.isBlank();
            if (!consumable) continue;
            List<Long> blockKeys = blocksByArea.getOrDefault(area.build_area_numeric_id, Collections.emptyList());
            AreaGeometry geometry = buildAreaGeometry(area, blockKeys);
            CityC7Stages.GroupArrangementDecision arrangement = arrangements.getOrDefault(area.build_area_id, arrangements.get(area.group_id));
            CityC7Stages.TemplateSelectionItem selection = selections.getOrDefault(area.build_area_id, selections.get(area.group_id));
            CityC7Stages.ForemanPlan foremanPlan = foremanPlans.getOrDefault(area.build_area_id, foremanPlans.get(area.group_id));
            System.out.println("[C8] area=" + safe(area.build_area_id)
                    + " group=" + safe(area.group_id)
                    + " layout_primary_count=" + (layoutPlan.primary_modules != null ? layoutPlan.primary_modules.size() : 0)
                    + " arrangement_seed_template=" + safe(arrangement != null && arrangement.seed != null ? arrangement.seed.start_template_id : "")
                    + " selection_template=" + safe(selection != null ? selection.selected_template : ""));
            FoundationItem item = buildFoundationItem(area, geometry, layoutPlan, arrangement, selection, foremanPlan, heightData, c2ScanData);
            if (item != null) plan.foundations.add(item);
        }
        return plan;
    }

    public static void save(Path cityDir, C8Plan plan) throws Exception {
        if (cityDir == null || plan == null) return;
        Files.createDirectories(cityDir);
        Files.writeString(cityDir.resolve(C8_PLAN_FILE), GSON.toJson(plan), StandardCharsets.UTF_8);
    }

    public static C8Plan load(Path cityDir) throws Exception {
        Path file = cityDir.resolve(C8_PLAN_FILE);
        if (!Files.exists(file)) return null;
        return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), C8Plan.class);
    }

    public static Path saveDebug(Path cityDir, String groupId, C8Plan plan) throws Exception {
        if (cityDir == null || groupId == null || groupId.isBlank() || plan == null) return null;
        Path groupDir = com.user.terra_script.world.city.stage.CityGroupPathUtil.resolveGroupDir(cityDir, groupId);
        Path file = groupDir.resolve("c8_arrangement_debug.json");
        Files.writeString(file, GSON.toJson(plan), StandardCharsets.UTF_8);
        return file;
    }

    private static FoundationItem buildFoundationItem(
            CityC6Stages.BuildAreaSummary area,
            AreaGeometry geometry,
            CityC6Stages.LayoutPlan layoutPlan,
            CityC7Stages.GroupArrangementDecision arrangement,
            CityC7Stages.TemplateSelectionItem selection,
            CityC7Stages.ForemanPlan foremanPlan,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData
    ) {
        FoundationItem item = new FoundationItem();
        item.plot_id = area.build_area_id;
        item.build_area_id = area.build_area_id;
        item.build_area_numeric_id = area.build_area_numeric_id;
        item.group_id = area.group_id;
        item.anchor_module_id = layoutPlan.primary_modules.get(0).module_id;

        item.foreman_plan = foremanPlan;
        if (arrangement != null) {
            item.arrangement_type = arrangement.arrangement_type;
            if (arrangement.arrangement_params != null) item.arrangement_params.putAll(arrangement.arrangement_params);
        }
        if (selection != null) {
            item.selected_template = selection.selected_template;
            item.function_role = selection.function_role;
            item.interaction_role = selection.interaction_role;
            item.top_k_templates = selection.top_k_templates != null ? new ArrayList<>(selection.top_k_templates) : new ArrayList<>();
            item.fallback_chain = selection.fallback_chain != null ? new ArrayList<>(selection.fallback_chain) : new ArrayList<>();
            item.landing_hint = selection.landing_hint;
            item.growth_axis = selection.growth_axis;
            item.vertical_role = selection.vertical_role;
            item.vertical_clearance = Math.max(0, selection.vertical_clearance);
            item.vertical_capable = selection.vertical_capable;
            item.vertical_mode_hint = inferVerticalMode(selection);
            if ((item.arrangement_type == null || item.arrangement_type.isBlank()) && selection.arrangement_type != null) {
                item.arrangement_type = selection.arrangement_type;
            }
            if (item.arrangement_params.isEmpty() && selection.arrangement_params != null) {
                item.arrangement_params.putAll(selection.arrangement_params);
            }
        }
        System.out.println("[C8] foundation build_area=" + safe(item.build_area_id)
                + " selected_template=" + safe(item.selected_template)
                + " placement_count=" + (item.placements != null ? item.placements.size() : 0)
                + " warnings=" + (item.arrangement_warnings != null ? item.arrangement_warnings.size() : 0));

        TerrainStats terrain = analyzeTerrain(area, geometry, heightData, c2ScanData);
        item.foundation_type = terrain.foundation_type;
        item.strategy = terrain.strategy;
        item.base_y = terrain.baseY;
        item.delta_height = terrain.relief;
        item.terrain_impact_extent.minX = terrain.minX - 1;
        item.terrain_impact_extent.minZ = terrain.minZ - 1;
        item.terrain_impact_extent.maxX = terrain.maxX + 1;
        item.terrain_impact_extent.maxZ = terrain.maxZ + 1;
        item.terrain_metrics.height_min = terrain.minH;
        item.terrain_metrics.height_max = terrain.maxH;
        item.terrain_metrics.height_avg = round3(terrain.avgH);
        item.terrain_metrics.height_p50 = round3(terrain.p50);
        item.terrain_metrics.slope_avg = round3(terrain.slopeAvg);
        item.terrain_metrics.edge_n = terrain.edgeN;
        item.terrain_metrics.edge_e = terrain.edgeE;
        item.terrain_metrics.edge_s = terrain.edgeS;
        item.terrain_metrics.edge_w = terrain.edgeW;
        item.foundation_context.foundation_type = item.foundation_type;
        item.foundation_context.strategy = item.strategy;
        item.foundation_context.base_y = item.base_y;
        item.foundation_context.delta_height = item.delta_height;
        item.foundation_context.foundation_note = foundationNote(item.foundation_type, item.delta_height);

        if ("PLATFORM_WITH_RETAINING_WALL".equals(item.foundation_type)) {
            addSupport(item, "retaining_wall", maxEdgeSide(terrain.edgeN, terrain.edgeE, terrain.edgeS, terrain.edgeW));
            addSupport(item, "stairs", minEdgeSide(terrain.edgeN, terrain.edgeE, terrain.edgeS, terrain.edgeW));
        } else if ("TERRACE".equals(item.foundation_type)) {
            addSupport(item, "stairs", "E");
        }

        initializeForemanSession(item, area, layoutPlan, arrangement, selection);
        return item;
    }

    private static void initializeForemanSession(
            FoundationItem item,
            CityC6Stages.BuildAreaSummary area,
            CityC6Stages.LayoutPlan layoutPlan,
            CityC7Stages.GroupArrangementDecision arrangement,
            CityC7Stages.TemplateSelectionItem selection
    ) {
        item.session_state = "READY_FOR_AI";
        item.active_node = null;
        item.node_queue.clear();
        item.validated_nodes.clear();
        item.failed_attempts.clear();
        item.node_debug.clear();
        item.placements.clear();
        item.arrangement_success = true;
        item.arrangement_errors.clear();
        item.arrangement_warnings.clear();

        NodeTask startNode = createStartNode(item, area, layoutPlan, arrangement, selection);
        if (startNode == null) {
            item.session_state = "FAILED";
            item.arrangement_success = false;
            item.arrangement_errors.add("missing_start_node");
            item.arrangement_warnings.add("未能从工头计划或兼容字段中推导出起始节点。");
            updateQueueSummary(item);
            return;
        }
        item.active_node = startNode;
        item.node_queue.add(startNode);
        item.node_debug.add(debug(startNode.node_id, "init", "已初始化起始节点，等待 AI 提交当前节点施工决策。"));
        updateQueueSummary(item);
    }

    private static NodeTask createStartNode(
            FoundationItem item,
            CityC6Stages.BuildAreaSummary area,
            CityC6Stages.LayoutPlan layoutPlan,
            CityC7Stages.GroupArrangementDecision arrangement,
            CityC7Stages.TemplateSelectionItem selection
    ) {
        NodeTask node = new NodeTask();
        CityC7Stages.StartNode start = item.foreman_plan != null ? item.foreman_plan.start_node : null;
        node.node_id = start != null && start.node_id != null && !start.node_id.isBlank()
                ? start.node_id
                : "start_" + safe(item.group_id);
        node.parent_node_id = null;
        node.phase_key = start != null && start.phase_key != null ? start.phase_key : "phase1";
        node.phase_name = start != null && start.phase_name != null ? start.phase_name : "先修前庭";
        node.target_role = start != null && start.target_role != null ? start.target_role : "主体开工节点";
        node.target_structure_kind = start != null && start.target_structure_kind != null ? start.target_structure_kind : "主体建筑";
        node.status = "READY_FOR_AI";
        if (start != null && start.allowed_connector_dirs != null) node.allowed_connector_dirs.addAll(start.allowed_connector_dirs);
        if (node.allowed_connector_dirs.isEmpty()) {
            node.allowed_connector_dirs.add(arrangement != null && arrangement.seed != null && arrangement.seed.start_connector_dir != null
                    ? arrangement.seed.start_connector_dir
                    : "east");
        }
        node.template_pool_id = start != null ? start.template_pool_id : "pool_" + safe(item.group_id);
        if (start != null && start.candidate_template_ids != null) node.candidate_template_ids.addAll(start.candidate_template_ids);
        if (node.candidate_template_ids.isEmpty() && selection != null && selection.top_k_templates != null) {
            node.candidate_template_ids.addAll(selection.top_k_templates);
        }
        if (node.candidate_template_ids.isEmpty() && selection != null && selection.selected_template != null) {
            node.candidate_template_ids.add(selection.selected_template);
        }
        if (node.candidate_template_ids.isEmpty() && arrangement != null && arrangement.seed != null && arrangement.seed.start_template_id != null) {
            node.candidate_template_ids.add(arrangement.seed.start_template_id);
        }
        node.x = arrangement != null && arrangement.seed != null && arrangement.seed.start_x != 0
                ? arrangement.seed.start_x
                : layoutPlan != null && layoutPlan.primary_modules != null && !layoutPlan.primary_modules.isEmpty()
                ? safeRound(layoutPlan.primary_modules.get(0).anchor.x)
                : safeRound(area.centroid.x);
        node.z = arrangement != null && arrangement.seed != null && arrangement.seed.start_z != 0
                ? arrangement.seed.start_z
                : layoutPlan != null && layoutPlan.primary_modules != null && !layoutPlan.primary_modules.isEmpty()
                ? safeRound(layoutPlan.primary_modules.get(0).anchor.z)
                : safeRound(area.centroid.z);
        node.y = item.base_y;
        node.selected_rotation = arrangement != null && arrangement.seed != null ? arrangement.seed.start_rotation : 0;
        node.build_order = 0;
        return node.candidate_template_ids.isEmpty() ? null : node;
    }

    public static NodeSubmitResult submitNodeDecision(
            FoundationItem item,
            AreaGeometry geometry,
            NodeDecision decision,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData
    ) {
        NodeSubmitResult result = new NodeSubmitResult();
        if (item == null || geometry == null || decision == null || decision.node_id == null || decision.node_id.isBlank()) {
            result.ok = false;
            result.error_code = "invalid_submit_request";
            result.error_message = "节点提交请求缺少必要字段。";
            return result;
        }
        NodeTask node = findNode(item, decision.node_id);
        if (node == null) {
            result.ok = false;
            result.error_code = "node_not_found";
            result.error_message = "未找到要提交的施工节点。";
            return result;
        }
        node.selected_template_id = decision.selected_template_id;
        node.selected_connector_dir = decision.selected_connector_dir;
        node.selected_rotation = decision.selected_rotation != null ? decision.selected_rotation : node.selected_rotation;
        if (decision.x != null) node.x = decision.x;
        if (decision.z != null) node.z = decision.z;
        if (decision.terrain_relax_profile != null) node.terrain_relax_profile = decision.terrain_relax_profile;
        node.status = "AI_PROPOSED";

        String invalidReason = validateDecisionInput(node);
        if (invalidReason != null) {
            registerFailure(item, node, node.selected_template_id, node.selected_connector_dir, "invalid_node_decision", "input", invalidReason);
            result.ok = false;
            result.node = node;
            result.error_code = "invalid_node_decision";
            result.error_message = invalidReason;
            return result;
        }

        PlacementNode placement = buildPlacementNode(item, node);
        String rejectReason = validatePlacement(item, geometry, placement, heightData, c2ScanData, node);
        if (rejectReason != null) {
            registerFailure(item, node, node.selected_template_id, node.selected_connector_dir, rejectReason, "validation", localizedRejectReason(rejectReason));
            result.ok = false;
            result.node = node;
            result.error_code = rejectReason;
            result.error_message = localizedRejectReason(rejectReason);
            return result;
        }

        node.status = "VALIDATED";
        node.placement = placement;
        node.last_error_code = null;
        node.last_error_message = null;
        node.y = placement.y;
        item.validated_nodes.add(node);
        item.placements.add(placement);
        item.node_queue.removeIf(existing -> existing != null && decision.node_id.equals(existing.node_id));
        if (item.active_node != null && decision.node_id.equals(item.active_node.node_id)) {
            item.active_node = null;
        }
        item.node_debug.add(debug(node.node_id, "validated", "节点已通过程序校验，进入待建造列表。"));
        enqueueChildNodes(item, node, placement);
        item.active_node = nextReadyNode(item);
        item.session_state = item.active_node != null ? "READY_FOR_AI" : "READY_FOR_BUILD";
        updateQueueSummary(item);

        result.ok = true;
        result.node = node;
        result.placement = placement;
        return result;
    }

    public static NodeTask retryNode(FoundationItem item, String nodeId) {
        if (item == null || nodeId == null || nodeId.isBlank()) return null;
        NodeTask node = findNode(item, nodeId);
        if (node == null) {
            for (NodeTask validated : item.validated_nodes) {
                if (validated != null && nodeId.equals(validated.node_id)) {
                    node = validated;
                    break;
                }
            }
        }
        if (node == null) return null;
        node.status = "READY_FOR_AI";
        node.retry_count++;
        node.last_error_code = null;
        node.last_error_message = "已重新打开该节点，等待新的施工决策。";
        if (item.node_queue.stream().noneMatch(existing -> existing != null && nodeId.equals(existing.node_id))) {
            item.node_queue.add(node);
        }
        item.active_node = node;
        item.session_state = "READY_FOR_AI";
        item.node_debug.add(debug(node.node_id, "retry", "节点已重新打开，可继续提交新的模板或方向方案。"));
        updateQueueSummary(item);
        return node;
    }

    private static NodeTask findNode(FoundationItem item, String nodeId) {
        if (item == null || nodeId == null) return null;
        if (item.active_node != null && nodeId.equals(item.active_node.node_id)) return item.active_node;
        for (NodeTask node : item.node_queue) {
            if (node != null && nodeId.equals(node.node_id)) return node;
        }
        for (NodeTask node : item.validated_nodes) {
            if (node != null && nodeId.equals(node.node_id)) return node;
        }
        return null;
    }

    private static void enqueueChildNodes(FoundationItem item, NodeTask parent, PlacementNode placement) {
        if (item == null || parent == null || placement == null || placement.outgoing_connector_ids == null) return;
        int nextOrder = placement.build_order != null ? placement.build_order + 1 : item.validated_nodes.size();
        int index = 1;
        for (String connectorId : placement.outgoing_connector_ids) {
            if (connectorId == null || connectorId.isBlank()) continue;
            NodeTask child = new NodeTask();
            child.node_id = parent.node_id + "_child_" + index;
            child.parent_node_id = parent.node_id;
            child.phase_key = nextPhaseKey(item, parent.phase_key);
            child.phase_name = phaseName(item, child.phase_key);
            child.target_role = child.phase_key.equals(parent.phase_key) ? "继续补足当前骨架节点" : "进入下一阶段的衔接节点";
            child.target_structure_kind = child.phase_key.equals("phase4") ? "外围收尾节点" : "延伸节点";
            child.status = "READY_FOR_AI";
            child.incoming_connector_id = connectorId;
            child.template_pool_id = parent.template_pool_id;
            child.candidate_template_ids.addAll(parent.candidate_template_ids);
            child.allowed_connector_dirs.addAll(parent.allowed_connector_dirs);
            child.build_order = nextOrder + index;
            child.y = item.base_y;
            item.node_queue.add(child);
            item.node_debug.add(debug(child.node_id, "enqueue", "根据已校验节点打开的新连接器生成子节点，等待后续施工。"));
            index++;
        }
    }

    private static NodeTask nextReadyNode(FoundationItem item) {
        if (item == null || item.node_queue == null) return null;
        item.node_queue.sort(Comparator.comparingInt(node -> node != null && node.build_order != null ? node.build_order : Integer.MAX_VALUE));
        for (NodeTask node : item.node_queue) {
            if (node != null && "READY_FOR_AI".equals(node.status)) return node;
        }
        return null;
    }

    private static void updateQueueSummary(FoundationItem item) {
        if (item == null) return;
        item.queue_summary = new QueueSummary();
        if (item.node_queue != null) {
            for (NodeTask node : item.node_queue) {
                if (node == null) continue;
                item.queue_summary.total_count++;
                if ("READY_FOR_AI".equals(node.status) || "AI_PROPOSED".equals(node.status)) {
                    item.queue_summary.ready_for_ai_count++;
                } else if ("FAILED".equals(node.status)) {
                    item.queue_summary.failed_count++;
                }
            }
        }
        if (item.validated_nodes != null) {
            item.queue_summary.validated_count = item.validated_nodes.size();
            item.queue_summary.total_count += item.validated_nodes.size();
        }
    }

    private static String validateDecisionInput(NodeTask node) {
        if (node.selected_template_id == null || node.selected_template_id.isBlank()) {
            node.last_error_code = "missing_template";
            node.last_error_message = "当前节点还没有选择模板。";
            return node.last_error_message;
        }
        if (!node.candidate_template_ids.isEmpty() && !node.candidate_template_ids.contains(node.selected_template_id)) {
            node.last_error_code = "template_out_of_pool";
            node.last_error_message = "当前选择的模板不在允许模板池内。";
            return node.last_error_message;
        }
        if (node.selected_connector_dir != null && !node.selected_connector_dir.isBlank()
                && !node.allowed_connector_dirs.isEmpty()
                && !node.allowed_connector_dirs.contains(node.selected_connector_dir)) {
            node.last_error_code = "connector_dir_not_allowed";
            node.last_error_message = "当前选择的连接方向不在允许方向列表内。";
            return node.last_error_message;
        }
        return null;
    }

    private static PlacementNode buildPlacementNode(FoundationItem item, NodeTask node) {
        PlacementNode placement = new PlacementNode();
        placement.node_id = node.node_id;
        placement.parent_node_id = node.parent_node_id;
        placement.template_id = node.selected_template_id;
        placement.role = node.target_role;
        placement.x = node.x != null ? node.x : 0;
        placement.y = node.y != null ? node.y : item.base_y;
        placement.z = node.z != null ? node.z : 0;
        placement.rotation = node.selected_rotation != null ? node.selected_rotation : 0;
        placement.build_order = node.build_order;
        placement.incoming_parent_connector_id = node.incoming_connector_id;
        placement.incoming_connector_dir = node.selected_connector_dir;
        placement.outgoing_connector_ids = syntheticOutgoingConnectors(node);
        placement.footprint_min_x = placement.x;
        placement.footprint_min_z = placement.z;
        placement.footprint_max_x = placement.x;
        placement.footprint_max_z = placement.z;
        return placement;
    }

    private static List<String> syntheticOutgoingConnectors(NodeTask node) {
        List<String> out = new ArrayList<>();
        if (node == null || node.allowed_connector_dirs == null) return out;
        int index = 1;
        for (String dir : node.allowed_connector_dirs) {
            if (dir == null || dir.isBlank()) continue;
            out.add("next_" + safe(node.node_id) + "_" + dir + "_" + index);
            index++;
            if (index > 2) break;
        }
        return out;
    }

    private static String validatePlacement(
            FoundationItem item,
            AreaGeometry geometry,
            PlacementNode placement,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            NodeTask node
    ) {
        if (!containsFootprint(geometry, placement.footprint_min_x, placement.footprint_min_z, placement.footprint_max_x, placement.footprint_max_z)) {
            return "out_of_area";
        }
        for (PlacementNode existing : item.placements) {
            if (existing == null) continue;
            boolean separated = placement.footprint_max_x < existing.footprint_min_x
                    || placement.footprint_min_x > existing.footprint_max_x
                    || placement.footprint_max_z < existing.footprint_min_z
                    || placement.footprint_min_z > existing.footprint_max_z;
            if (!separated) return "footprint_collision";
        }
        if (heightData != null) {
            int h = heightAt(heightData, c2ScanData, placement.x, placement.z);
            int allowed = node != null && node.terrain_relax_profile != null && node.terrain_relax_profile.max_height_delta != null
                    ? node.terrain_relax_profile.max_height_delta
                    : Math.max(3, item.delta_height + 2);
            if (Math.abs(h - item.base_y) > allowed) return "terrain_rejected";
            placement.y = item.base_y;
        }
        return null;
    }

    private static void registerFailure(
            FoundationItem item,
            NodeTask node,
            String templateId,
            String connectorDir,
            String errorCode,
            String errorStage,
            String details
    ) {
        node.status = "FAILED";
        node.last_error_code = errorCode;
        node.last_error_message = details;
        FailedAttempt attempt = new FailedAttempt();
        attempt.attempt_id = node.node_id + "_attempt_" + (item.failed_attempts.size() + 1);
        attempt.node_id = node.node_id;
        attempt.template_id = templateId;
        attempt.connector_dir = connectorDir;
        attempt.error_code = errorCode;
        attempt.error_stage = errorStage;
        attempt.error_details = details;
        attempt.retryable = true;
        item.failed_attempts.add(attempt);
        item.arrangement_success = false;
        item.arrangement_errors.add(errorCode);
        item.arrangement_warnings.add(details);
        item.node_debug.add(debug(node.node_id, errorStage, details));
        item.active_node = node;
        item.session_state = "READY_FOR_AI";
        updateQueueSummary(item);
    }

    private static NodeDebugRecord debug(String nodeId, String stage, String detail) {
        NodeDebugRecord record = new NodeDebugRecord();
        record.node_id = nodeId;
        record.stage = stage;
        record.detail = detail;
        return record;
    }

    private static String localizedRejectReason(String rejectReason) {
        return switch (safe(rejectReason)) {
            case "out_of_area" -> "当前节点落位超出了建造区边界。";
            case "footprint_collision" -> "当前节点与已通过校验的节点发生了占地重叠。";
            case "terrain_rejected" -> "当前节点的地形条件不满足要求。";
            default -> "当前节点校验失败，请调整模板、方向或坐标后重试。";
        };
    }

    private static String nextPhaseKey(FoundationItem item, String currentPhaseKey) {
        if (item != null && item.foreman_plan != null && item.foreman_plan.phase_list != null && !item.foreman_plan.phase_list.isEmpty()) {
            List<CityC7Stages.PhasePlan> phases = item.foreman_plan.phase_list;
            for (int i = 0; i < phases.size(); i++) {
                CityC7Stages.PhasePlan phase = phases.get(i);
                if (phase != null && safe(currentPhaseKey).equals(phase.phase_key)) {
                    if (i + 1 < phases.size() && phases.get(i + 1) != null) {
                        return phases.get(i + 1).phase_key;
                    }
                    return phase.phase_key;
                }
            }
            if (phases.get(0) != null) return phases.get(0).phase_key;
        }
        if ("phase1".equals(currentPhaseKey)) return "phase2";
        if ("phase2".equals(currentPhaseKey)) return "phase3";
        if ("phase3".equals(currentPhaseKey)) return "phase4";
        return "phase4";
    }

    private static String phaseName(FoundationItem item, String phaseKey) {
        if (item != null && item.foreman_plan != null && item.foreman_plan.phase_list != null) {
            for (CityC7Stages.PhasePlan phase : item.foreman_plan.phase_list) {
                if (phase != null && safe(phaseKey).equals(phase.phase_key) && phase.phase_name != null && !phase.phase_name.isBlank()) {
                    return phase.phase_name;
                }
            }
        }
        if ("phase1".equals(phaseKey)) return "先立主体骨架";
        if ("phase2".equals(phaseKey)) return "再推进核心主体";
        if ("phase3".equals(phaseKey)) return "再补附属节点";
        return "最后做外围收尾";
    }

    private static TerrainStats analyzeTerrain(
            CityC6Stages.BuildAreaSummary area,
            AreaGeometry geometry,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData
    ) {
        TerrainStats stats = new TerrainStats();
        List<Long> blockKeys = geometry != null && geometry.block_keys != null ? geometry.block_keys : Collections.emptyList();
        List<Integer> heights = new ArrayList<>(Math.max(16, blockKeys.size()));
        long sum = 0L;
        stats.minH = Integer.MAX_VALUE;
        stats.maxH = Integer.MIN_VALUE;
        stats.minX = Integer.MAX_VALUE;
        stats.minZ = Integer.MAX_VALUE;
        stats.maxX = Integer.MIN_VALUE;
        stats.maxZ = Integer.MIN_VALUE;

        for (Long key : blockKeys) {
            if (key == null) continue;
            int x = unpackX(key);
            int z = unpackZ(key);
            int h = heightAt(heightData, c2ScanData, x, z);
            heights.add(h);
            sum += h;
            stats.minH = Math.min(stats.minH, h);
            stats.maxH = Math.max(stats.maxH, h);
            stats.minX = Math.min(stats.minX, x);
            stats.minZ = Math.min(stats.minZ, z);
            stats.maxX = Math.max(stats.maxX, x);
            stats.maxZ = Math.max(stats.maxZ, z);
        }

        if (heights.isEmpty()) {
            stats.minH = safeRound(area.avg_height);
            stats.maxH = stats.minH;
            int fallbackX = safeRound(area.centroid.x);
            int fallbackZ = safeRound(area.centroid.z);
            stats.minX = fallbackX;
            stats.minZ = fallbackZ;
            stats.maxX = fallbackX;
            stats.maxZ = fallbackZ;
            heights.add(stats.minH);
            sum = stats.minH;
        }

        stats.avgH = sum / (double) heights.size();
        Collections.sort(heights);
        stats.p50 = heights.get(Math.max(0, heights.size() / 2));
        stats.relief = Math.max(0, stats.maxH - stats.minH);
        stats.slopeAvg = estimateSlopeAvg(blockKeys, heightData, c2ScanData);

        int northSum = 0, eastSum = 0, southSum = 0, westSum = 0;
        int northCnt = 0, eastCnt = 0, southCnt = 0, westCnt = 0;
        for (Long key : blockKeys) {
            if (key == null) continue;
            int x = unpackX(key);
            int z = unpackZ(key);
            int h = heightAt(heightData, c2ScanData, x, z);
            if (z == stats.minZ) { northSum += h; northCnt++; }
            if (x == stats.maxX) { eastSum += h; eastCnt++; }
            if (z == stats.maxZ) { southSum += h; southCnt++; }
            if (x == stats.minX) { westSum += h; westCnt++; }
        }
        stats.edgeN = northCnt > 0 ? safeRound(northSum / (double) northCnt) : safeRound(stats.avgH);
        stats.edgeE = eastCnt > 0 ? safeRound(eastSum / (double) eastCnt) : safeRound(stats.avgH);
        stats.edgeS = southCnt > 0 ? safeRound(southSum / (double) southCnt) : safeRound(stats.avgH);
        stats.edgeW = westCnt > 0 ? safeRound(westSum / (double) westCnt) : safeRound(stats.avgH);

        boolean strongEdgeDelta = Math.abs(stats.edgeN - stats.edgeS) >= 6 || Math.abs(stats.edgeE - stats.edgeW) >= 6;
        stats.foundation_type = "NONE";
        stats.strategy = "FOLLOW_AVG";
        if (stats.relief <= 1 && stats.slopeAvg < 0.4) {
            stats.foundation_type = "NONE";
            stats.strategy = "FOLLOW_AVG";
        } else if (stats.relief >= 9) {
            stats.foundation_type = "TERRACE";
            stats.strategy = "FOLLOW_P50";
        } else if (strongEdgeDelta && stats.relief >= 4) {
            stats.foundation_type = "PLATFORM_WITH_RETAINING_WALL";
            stats.strategy = "CUT_AND_FILL";
        } else {
            stats.foundation_type = "PLATFORM";
            stats.strategy = "CUT_AND_FILL";
        }
        stats.baseY = "FOLLOW_P50".equals(stats.strategy) ? safeRound(stats.p50) : safeRound(stats.avgH);
        return stats;
    }

    private static Map<String, CityC7Stages.GroupArrangementDecision> indexArrangements(CityC7Stages.C7Selection selection) {
        Map<String, CityC7Stages.GroupArrangementDecision> index = new HashMap<>();
        if (selection == null || selection.arrangements == null) return index;
        for (CityC7Stages.GroupArrangementDecision item : selection.arrangements) {
            if (item == null) continue;
            if (item.build_area_id != null && !item.build_area_id.isBlank()) index.putIfAbsent(item.build_area_id, item);
            if (item.group_id != null && !item.group_id.isBlank()) index.putIfAbsent(item.group_id, item);
        }
        return index;
    }

    private static Map<String, CityC7Stages.TemplateSelectionItem> indexSelections(CityC7Stages.C7Selection selection) {
        Map<String, CityC7Stages.TemplateSelectionItem> index = new HashMap<>();
        if (selection == null || selection.selections == null) return index;
        for (CityC7Stages.TemplateSelectionItem item : selection.selections) {
            if (item == null) continue;
            if (item.build_area_id != null && !item.build_area_id.isBlank()) index.putIfAbsent(item.build_area_id, item);
            if (item.group_id != null && !item.group_id.isBlank()) index.putIfAbsent(item.group_id, item);
        }
        return index;
    }

    private static Map<String, CityC7Stages.ForemanPlan> indexForemanPlans(CityC7Stages.C7Selection selection) {
        Map<String, CityC7Stages.ForemanPlan> index = new HashMap<>();
        if (selection == null) return index;
        if (selection.foreman_plans != null) {
            for (CityC7Stages.ForemanPlan item : selection.foreman_plans) {
                if (item == null) continue;
                if (item.build_area_id != null && !item.build_area_id.isBlank()) index.putIfAbsent(item.build_area_id, item);
                if (item.group_id != null && !item.group_id.isBlank()) index.putIfAbsent(item.group_id, item);
            }
        }
        if (selection.foreman_plan != null) {
            if (selection.foreman_plan.build_area_id != null && !selection.foreman_plan.build_area_id.isBlank()) {
                index.putIfAbsent(selection.foreman_plan.build_area_id, selection.foreman_plan);
            }
            if (selection.foreman_plan.group_id != null && !selection.foreman_plan.group_id.isBlank()) {
                index.putIfAbsent(selection.foreman_plan.group_id, selection.foreman_plan);
            }
        }
        return index;
    }

    private static Map<String, CityC6Stages.LayoutPlan> indexPlans(CityC6Stages.C6Layout c6Layout) {
        Map<String, CityC6Stages.LayoutPlan> index = new HashMap<>();
        if (c6Layout == null || c6Layout.plans == null) return index;
        for (CityC6Stages.LayoutPlan plan : c6Layout.plans) {
            if (plan == null || plan.build_area_id == null) continue;
            index.put(plan.build_area_id, plan);
        }
        return index;
    }

    private static String inferVerticalMode(CityC7Stages.TemplateSelectionItem selection) {
        if (selection == null || !selection.vertical_capable) return "none";
        String role = selection.vertical_role != null ? selection.vertical_role.trim().toLowerCase() : "";
        if (role.contains("up") && role.contains("down")) return "both";
        if (role.contains("up")) return "up_only";
        if (role.contains("down")) return "down_only";
        return "both";
    }

    private static String foundationNote(String foundationType, int deltaHeight) {
        if ("PLATFORM_WITH_RETAINING_WALL".equals(foundationType)) {
            return "当前地形边缘高差较明显，建议先修平台再补挡墙。";
        }
        if ("TERRACE".equals(foundationType)) {
            return "当前地形起伏较大，建议按台地顺序逐段施工。";
        }
        if ("PLATFORM".equals(foundationType)) {
            return "当前建造区适合先压出稳定平台，再继续主体施工。";
        }
        return deltaHeight <= 1 ? "当前地形较平缓，可直接按地表高度推进施工。" : "当前地形需先做轻量基台处理后再继续施工。";
    }

    private static void addSupport(FoundationItem item, String type, String side) {
        for (SupportAction existing : item.supports) {
            if (existing != null && type.equals(existing.type) && side.equals(existing.side)) return;
        }
        SupportAction action = new SupportAction();
        action.type = type;
        action.side = side;
        item.supports.add(action);
    }

    private static double estimateSlopeAvg(List<Long> blockKeys, CityStage1BinaryIO.HeightData heightData, CityC2ScanBinaryIO.C2ScanData c2ScanData) {
        if (blockKeys == null || blockKeys.isEmpty()) return 0.0;
        Map<Long, Integer> heights = new HashMap<>();
        for (Long key : blockKeys) {
            if (key == null) continue;
            int x = unpackX(key);
            int z = unpackZ(key);
            heights.put(key, heightAt(heightData, c2ScanData, x, z));
        }
        int[][] dirs = new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        double sum = 0.0;
        int cnt = 0;
        for (Long key : heights.keySet()) {
            int x = unpackX(key);
            int z = unpackZ(key);
            int base = heights.getOrDefault(key, 0);
            for (int[] d : dirs) {
                Integer nh = heights.get(packBlock(x + d[0], z + d[1]));
                if (nh == null) continue;
                sum += Math.abs(base - nh);
                cnt++;
            }
        }
        return cnt <= 0 ? 0.0 : sum / cnt;
    }

    private static String maxEdgeSide(int n, int e, int s, int w) {
        int max = Math.max(Math.max(n, e), Math.max(s, w));
        if (max == n) return "N";
        if (max == e) return "E";
        if (max == s) return "S";
        return "W";
    }

    private static String minEdgeSide(int n, int e, int s, int w) {
        int min = Math.min(Math.min(n, e), Math.min(s, w));
        if (min == n) return "N";
        if (min == e) return "E";
        if (min == s) return "S";
        return "W";
    }

    private static int heightAt(CityStage1BinaryIO.HeightData data, CityC2ScanBinaryIO.C2ScanData c2ScanData, int worldX, int worldZ) {
        return CityHeightResolver.resolveHeight(data, c2ScanData, worldX, worldZ);
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) key;
    }

    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    public static List<Long> collectAreaBlockKeys(Map<Long, Integer> indexByBlock, int buildAreaNumericId) {
        if (indexByBlock == null || indexByBlock.isEmpty()) return Collections.emptyList();
        List<Long> out = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : indexByBlock.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) continue;
            if (entry.getValue() == buildAreaNumericId) out.add(entry.getKey());
        }
        return out;
    }

    public static AreaGeometry buildAreaGeometry(CityC6Stages.BuildAreaSummary area, List<Long> blockKeys) {
        AreaGeometry geometry = new AreaGeometry();
        if (area != null) {
            geometry.build_area_id = area.build_area_id;
            geometry.build_area_numeric_id = area.build_area_numeric_id;
            geometry.centroid_x = area.centroid != null ? area.centroid.x : 0.0;
            geometry.centroid_z = area.centroid != null ? area.centroid.z : 0.0;
        }
        if (blockKeys == null || blockKeys.isEmpty()) {
            geometry.min_x = safeRound(geometry.centroid_x);
            geometry.min_z = safeRound(geometry.centroid_z);
            geometry.max_x = geometry.min_x;
            geometry.max_z = geometry.min_z;
            geometry.valid = false;
            return geometry;
        }
        geometry.block_keys = new ArrayList<>(blockKeys);
        geometry.block_set = new LinkedHashSet<>(blockKeys);
        geometry.min_x = Integer.MAX_VALUE;
        geometry.min_z = Integer.MAX_VALUE;
        geometry.max_x = Integer.MIN_VALUE;
        geometry.max_z = Integer.MIN_VALUE;
        long sumX = 0L;
        long sumZ = 0L;
        Set<Long> seen = new HashSet<>();
        for (Long key : blockKeys) {
            if (key == null || !seen.add(key)) continue;
            int x = unpackX(key);
            int z = unpackZ(key);
            geometry.min_x = Math.min(geometry.min_x, x);
            geometry.min_z = Math.min(geometry.min_z, z);
            geometry.max_x = Math.max(geometry.max_x, x);
            geometry.max_z = Math.max(geometry.max_z, z);
            sumX += x;
            sumZ += z;
        }
        int count = Math.max(1, seen.size());
        geometry.centroid_x = sumX / (double) count;
        geometry.centroid_z = sumZ / (double) count;
        geometry.valid = !geometry.block_set.isEmpty();
        return geometry;
    }

    public static boolean containsAreaBlock(AreaGeometry geometry, int x, int z) {
        return geometry != null && geometry.block_set != null && geometry.block_set.contains(packBlock(x, z));
    }

    public static boolean containsFootprint(AreaGeometry geometry, Integer minX, Integer minZ, Integer maxX, Integer maxZ) {
        if (geometry == null || !geometry.valid || geometry.block_set == null || minX == null || minZ == null || maxX == null || maxZ == null) {
            return false;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!containsAreaBlock(geometry, x, z)) return false;
            }
        }
        return true;
    }

    private static int safeRound(double v) {
        return (int) Math.round(v);
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class TerrainStats {
        int minH;
        int maxH;
        int minX;
        int minZ;
        int maxX;
        int maxZ;
        double avgH;
        double p50;
        double slopeAvg;
        int relief;
        int edgeN;
        int edgeE;
        int edgeS;
        int edgeW;
        int baseY;
        String foundation_type;
        String strategy;
    }
}
