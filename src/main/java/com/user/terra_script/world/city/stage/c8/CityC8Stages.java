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
        public int version = 4;
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
        public List<PlacementNode> placements = new ArrayList<>();
        public boolean arrangement_success = true;
        public List<String> arrangement_errors = new ArrayList<>();
        public List<String> arrangement_warnings = new ArrayList<>();
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
            System.out.println("[C8] area=" + safe(area.build_area_id)
                    + " group=" + safe(area.group_id)
                    + " layout_primary_count=" + (layoutPlan.primary_modules != null ? layoutPlan.primary_modules.size() : 0)
                    + " arrangement_seed_template=" + safe(arrangement != null && arrangement.seed != null ? arrangement.seed.start_template_id : "")
                    + " selection_template=" + safe(selection != null ? selection.selected_template : ""));
            FoundationItem item = buildFoundationItem(area, geometry, layoutPlan, arrangement, selection, heightData, c2ScanData);
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
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData
    ) {
        FoundationItem item = new FoundationItem();
        item.plot_id = area.build_area_id;
        item.build_area_id = area.build_area_id;
        item.build_area_numeric_id = area.build_area_numeric_id;
        item.group_id = area.group_id;
        item.anchor_module_id = layoutPlan.primary_modules.get(0).module_id;

        if (arrangement != null) {
            item.arrangement_type = arrangement.arrangement_type;
            if (arrangement.arrangement_params != null) item.arrangement_params.putAll(arrangement.arrangement_params);
            CityC8ArrangementEngine.SolveResult solveResult = CityC8ArrangementEngine.solve(area, geometry, layoutPlan, arrangement, heightData, c2ScanData);
            item.placements = solveResult.placements != null ? solveResult.placements : new ArrayList<>();
            item.arrangement_success = solveResult.success;
            item.arrangement_errors = solveResult.errors != null ? new ArrayList<>(solveResult.errors) : new ArrayList<>();
            item.arrangement_warnings = solveResult.warnings != null ? new ArrayList<>(solveResult.warnings) : new ArrayList<>();
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

        if ("PLATFORM_WITH_RETAINING_WALL".equals(item.foundation_type)) {
            addSupport(item, "retaining_wall", maxEdgeSide(terrain.edgeN, terrain.edgeE, terrain.edgeS, terrain.edgeW));
            addSupport(item, "stairs", minEdgeSide(terrain.edgeN, terrain.edgeE, terrain.edgeS, terrain.edgeW));
        } else if ("TERRACE".equals(item.foundation_type)) {
            addSupport(item, "stairs", "E");
        }

        for (PlacementNode node : item.placements) {
            if (node != null) node.y = item.base_y;
        }
        return item;
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
