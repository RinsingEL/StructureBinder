package com.user.terra_script.world.city.stage.c6;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.user.terra_script.world.city.CityInstance;
import com.user.terra_script.world.city.district.District;
import com.user.terra_script.world.city.stage.CityC35CatalogIO;
import com.user.terra_script.world.city.stage.CityHeightResolver;
import com.user.terra_script.world.city.stage.StructureTemplateQueryService;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import com.user.terra_script.world.city.stage.c1.CityStage1Processor;
import com.user.terra_script.world.city.stage.c2.CityC2ScanBinaryIO;
import com.user.terra_script.world.city.stage.c2.CityC3OwnershipIO;
import com.user.terra_script.world.city.stage.c4.CitySemanticStages;
import net.minecraft.world.level.ChunkPos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CityC6Stages {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int INDEX_VERSION = 1;

    public static final String C6_INDEX_FILE = "C6_BuildAreaIndex.dat";
    public static final String C6_SUMMARY_FILE = "C6_BuildAreaSummary.json";
    public static final String C6_LAYOUT_FILE = "C6_BuildAreaLayout.json";
    public static final String C6_RECT_DECISION_INPUT_FILE = "C6_RectDecisionInput.json";
    public static final String C6_RECT_CANDIDATES_FILE = "C6_RectCandidates.json";
    public static final String C6_RECT_VALIDATION_FILE = "C6_RectValidation.json";
    private static final String GROWTH_RULES_RESOURCE = "/terra_script/c6_growth_buffer_rules.json";

    public static final int RECT_ATTEMPT_LIMIT = 3;
    public static final double MIN_TOTAL_PRIMARY_AREA_RATIO = 0.50;
    public static final double MIN_COVERAGE_RATIO = 0.80;
    private static volatile GrowthRuleTable growthRuleTableCache;

    private CityC6Stages() {}

    public static class C6Bundle {
        public C6Summary summary;
        public C6Layout layout;
        public C6RectDecisionInput decision_input;
        public C6RectCandidates candidates;
        public C6RectValidation validation;
        public int indexed_block_count;
        public transient Map<Long, Integer> index_by_block = new LinkedHashMap<>();
    }

    public static class C6Summary {
        public String step = "C6";
        public boolean ok = true;
        public String city_id;
        public int rules_version = 2;
        public String fill_style = C6FillStyle.PLAZA_RING.name();
        public long generated_at_epoch_ms;
        public List<BuildAreaSummary> areas = new ArrayList<>();
    }

    public static class BuildAreaSummary {
        public String build_area_id;
        public int build_area_numeric_id;
        public String group_id;
        public String function;
        public String layer;
        public int area_blocks;
        public BBox bbox = new BBox();
        public Point centroid = new Point();
        public double avg_height;
        public List<String> ascii_map = new ArrayList<>();
    }

    public static class BBox {
        public int minX;
        public int minZ;
        public int maxX;
        public int maxZ;
    }

    public static class Point {
        public double x;
        public double z;
    }

    public static class C6Layout {
        public String step = "C6";
        public boolean ok = true;
        public String city_id;
        public String fill_style = C6FillStyle.PLAZA_RING.name();
        public int version = 2;
        public long generated_at_epoch_ms;
        public List<LayoutPlan> plans = new ArrayList<>();
    }

    public static class LayoutPlan {
        public String group_id;
        public String build_area_id;
        public String fill_style = C6FillStyle.PLAZA_RING.name();
        public List<PrimaryModule> primary_modules = new ArrayList<>();
        public List<SecondaryFill> secondary_fill = new ArrayList<>();
        public PlazaRingParams fill_params;
        public List<RectSize> rect_sizes = new ArrayList<>();
        public RectGuidance rect_guidance = new RectGuidance();
        public String notes;
        public boolean validated;
        public String decision_mode;
        public int accepted_attempt_index;
    }

    public static class PrimaryModule {
        public String module_id;
        public String rect_id;
        public String role = "primary";
        public Point anchor = new Point();
        public double importance;
        public int w;
        public int h;
        public int minX;
        public int minZ;
        public int maxX;
        public int maxZ;
        public TemplateHint template_hint = new TemplateHint();
        public RectGuidance structure_guidance = new RectGuidance();
    }

    public static class TemplateHint {
        public String category;
        public String size_tier;
        public String function_tag;
        public String interaction_role;
        public String guidance_source;
        public boolean fallback_path;
        public List<String> recommended_templates = new ArrayList<>();
        public List<String> target_size_tiers = new ArrayList<>();
    }

    public static class SecondaryFill {
        public String zone;
        public String style;
        public Object params;
    }

    public static class PlazaRingParams {
        public String plaza_shape = "CIRCLE";
        public List<Integer> plaza_radius_blocks = new ArrayList<>();
        public List<Integer> plaza_padding_blocks = new ArrayList<>();
        public int ring_count = 1;
        public List<List<Integer>> ring_depth_blocks = new ArrayList<>();
        public List<List<Integer>> ring_gap_blocks = new ArrayList<>();
        public int opening_count = 1;
        public List<Integer> opening_width_blocks = new ArrayList<>();
        public List<Integer> opening_angle_deg = new ArrayList<>();
        public List<String> opening_prefer_dirs = new ArrayList<>();
        public List<Integer> min_spacing_blocks = new ArrayList<>();
        public double jitter = 0.35;
        public String rotation_mode = "TANGENT";
        public List<Integer> rotation_jitter_deg = new ArrayList<>();
        public boolean respect_build_area_boundary = true;
        public double reserve_decor_ratio = 0.10;
    }

    public static class RectSize {
        public String id;
        public List<Integer> w_blocks = new ArrayList<>();
        public List<Integer> h_blocks = new ArrayList<>();
        public double weight;
        public int min_count;
        public int max_count;
    }

    public static class IntRange {
        public int min;
        public int recommended;
        public int max;
    }

    public static class DoubleRange {
        public double min;
        public double recommended;
        public double max;
    }

    public static class TemplateExample {
        public String structure_id;
        public String size_tier;
        public int width_blocks;
        public int height_blocks;
        public int area_blocks;
    }

    public static class RectGuidance {
        public String function_tag;
        public String interaction_role;
        public String guidance_source;
        public boolean fallback_path;
        public int candidate_count;
        public Map<String, Integer> connector_reserve_by_side = new LinkedHashMap<>();
        public int edge_buffer_blocks;
        public int growth_buffer_blocks;
        public String requires_expansion_side = "auto";
        public List<String> design_notes = new ArrayList<>();
        public List<String> target_size_tiers = new ArrayList<>();
        public IntRange recommended_rect_count = new IntRange();
        public IntRange rect_area_blocks = new IntRange();
        public IntRange rect_width_blocks = new IntRange();
        public IntRange rect_height_blocks = new IntRange();
        public DoubleRange aspect_ratio = new DoubleRange();
        public IntRange main_template_area_blocks = new IntRange();
        public IntRange main_template_width_blocks = new IntRange();
        public IntRange main_template_height_blocks = new IntRange();
        public List<TemplateExample> template_examples = new ArrayList<>();
    }

    public static class C6RectDecisionInput {
        public String step = "C6_prepare";
        public boolean ok = true;
        public String city_id;
        public int rect_limit = RECT_ATTEMPT_LIMIT;
        public double min_total_primary_area_ratio = MIN_TOTAL_PRIMARY_AREA_RATIO;
        public long generated_at_epoch_ms;
        public List<GroupDecisionInput> groups = new ArrayList<>();
    }

    public static class GroupDecisionInput {
        public String group_id;
        public String build_area_id;
        public int build_area_numeric_id;
        public String function;
        public String layer;
        public int polygon_area_blocks;
        public BBox polygon_reference_bbox = new BBox();
        public Point centroid = new Point();
        public int rect_limit = RECT_ATTEMPT_LIMIT;
        public double min_total_primary_area_ratio = MIN_TOTAL_PRIMARY_AREA_RATIO;
        public int current_attempt_count;
        public List<String> polygon_ascii_map = new ArrayList<>();
        public List<String> boundary_notes = new ArrayList<>();
        public PreviewPaths previews = new PreviewPaths();
        public RectGuidance rect_guidance = new RectGuidance();
    }

    public static class PreviewPaths {
        public String height;
        public String hillshade;
        public String roughness;
        public String polygon_overview;
        public String bbox_overview;
        public String rect_preview;
    }

    public static class C6RectCandidates {
        public String step = "C6_decide_validate";
        public boolean ok = true;
        public String city_id;
        public long updated_at_epoch_ms;
        public List<GroupRectCandidate> items = new ArrayList<>();
    }

    public static class GroupRectCandidate {
        public String group_id;
        public String build_area_id;
        public int attempt_index;
        public String decision_mode;
        public List<RectDecision> rects = new ArrayList<>();
    }

    public static class RectDecision {
        public String rect_id;
        public String role = "primary";
        public int cx;
        public int cz;
        public int w;
        public int h;
        public int minX;
        public int minZ;
        public int maxX;
        public int maxZ;
    }

    public static class C6RectValidation {
        public String step = "C6_decide_validate";
        public boolean ok = true;
        public String city_id;
        public long updated_at_epoch_ms;
        public List<GroupRectValidation> items = new ArrayList<>();
    }

    public static class GroupRectValidation {
        public String group_id;
        public String build_area_id;
        public int attempt_index;
        public String decision_mode;
        public List<RectValidationItem> rects = new ArrayList<>();
        public int total_primary_rect_area;
        public int polygon_area_blocks;
        public double total_primary_area_ratio;
        public boolean all_rects_valid;
        public boolean accepted;
        public boolean decision_terminal;
        public boolean continue_allowed;
        public boolean finalized_into_layout;
        public boolean structure_validation_passed;
        public int structure_compatible_rects;
        public int matching_main_template_count;
        public List<String> test_logs = new ArrayList<>();
        public String reason;
    }

    public static class RectValidationItem {
        public String rect_id;
        public int cx;
        public int cz;
        public int w;
        public int h;
        public int minX;
        public int minZ;
        public int maxX;
        public int maxZ;
        public int inside_functional_blocks;
        public int total_rect_blocks;
        public double coverage_ratio;
        public double aspect_ratio;
        public boolean structure_fit;
        public int matching_template_count;
        public List<String> matching_templates = new ArrayList<>();
        public List<String> test_logs = new ArrayList<>();
        public boolean valid;
        public String reason;
        public String structure_reason;
    }

    private static class ModuleMeta {
        String group_id;
        String function;
        String layer;
    }

    private static class GrowthRuleTable {
        List<GrowthRule> rules = new ArrayList<>();
    }

    private static class GrowthRule {
        String function_tag;
        String area_tier;
        int growth_buffer_blocks;
        int edge_buffer_blocks;
        String default_expansion_side;
    }

    private static final class ConnectorReserveStats {
        Map<String, List<Integer>> perSide = new LinkedHashMap<>();
        Map<String, Integer> fallbackSides = new LinkedHashMap<>();

        ConnectorReserveStats() {
            for (String side : List.of("north", "east", "south", "west")) {
                perSide.put(side, new ArrayList<>());
                fallbackSides.put(side, 0);
            }
        }
    }

    public static C6Bundle generate(
            CityInstance city,
            CitySemanticStages.C5Groups c5Groups,
            CityStage1BinaryIO.HeightData heightData,
            List<List<CityStage1Processor.BlockCoord>> buildableGroups,
            C6FillStyle fillStyle
    ) {
        return generate(city, c5Groups, heightData, null, buildableGroups, null, fillStyle);
    }

    public static C6Bundle generate(
            CityInstance city,
            CitySemanticStages.C5Groups c5Groups,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            List<List<CityStage1Processor.BlockCoord>> buildableGroups,
            CityC3OwnershipIO.OwnershipData ownershipData,
            C6FillStyle fillStyle
    ) {
        C6Bundle bundle = new C6Bundle();
        C6Summary summary = new C6Summary();
        C6Layout layout = new C6Layout();
        C6RectDecisionInput decisionInput = new C6RectDecisionInput();
        C6RectCandidates candidates = new C6RectCandidates();
        C6RectValidation validation = new C6RectValidation();
        bundle.summary = summary;
        bundle.layout = layout;
        bundle.decision_input = decisionInput;
        bundle.candidates = candidates;
        bundle.validation = validation;

        if (city == null || c5Groups == null || heightData == null || (buildableGroups == null && ownershipData == null)) {
            summary.ok = false;
            layout.ok = false;
            decisionInput.ok = false;
            candidates.ok = false;
            validation.ok = false;
            return bundle;
        }

        long now = System.currentTimeMillis();
        summary.city_id = city.id;
        summary.generated_at_epoch_ms = now;
        summary.fill_style = fillStyle.name();
        layout.city_id = city.id;
        layout.generated_at_epoch_ms = now;
        layout.fill_style = fillStyle.name();
        decisionInput.city_id = city.id;
        decisionInput.generated_at_epoch_ms = now;
        candidates.city_id = city.id;
        candidates.updated_at_epoch_ms = now;
        validation.city_id = city.id;
        validation.updated_at_epoch_ms = now;

        Map<Integer, District> districtById = new HashMap<>();
        for (District district : city.districts) {
            if (district != null) districtById.put(district.id, district);
        }

        Map<Long, ModuleMeta> chunkToModule = new HashMap<>();
        for (CitySemanticStages.ModuleGroup group : c5Groups.groups) {
            if (group == null) continue;
            for (Integer districtId : group.district_numeric_ids) {
                District district = districtById.get(districtId);
                if (district == null || district.memberChunks == null) continue;
                for (Long chunkKey : district.memberChunks) {
                    if (chunkKey == null) continue;
                    chunkToModule.putIfAbsent(chunkKey, toModuleMeta(group));
                }
            }
        }

        Map<String, BuildAreaSummary> bestAreaByGroup = new HashMap<>();
        Map<Long, Integer> blockToArea = new LinkedHashMap<>();
        int areaSeq = 1;
        if (ownershipData != null) {
            areaSeq = populateAreasFromOwnership(summary, bestAreaByGroup, blockToArea, chunkToModule, heightData, c2ScanData, ownershipData, areaSeq);
        }
        if (summary.areas.isEmpty()) {
            areaSeq = populateAreasFromBuildableGroups(summary, bestAreaByGroup, blockToArea, chunkToModule, heightData, c2ScanData, buildableGroups, areaSeq);
        }

        summary.areas.sort(Comparator.comparing(a -> a.build_area_id));
        Map<String, RectGuidance> rectGuidanceByGroup = buildRectGuidanceByGroup(bestAreaByGroup);
        layout.plans = buildPlans(fillStyle, bestAreaByGroup, rectGuidanceByGroup);
        decisionInput.groups = buildDecisionInputs(bestAreaByGroup, rectGuidanceByGroup);
        bundle.indexed_block_count = blockToArea.size();
        bundle.index_by_block = blockToArea;
        return bundle;
    }

    private static int populateAreasFromBuildableGroups(
            C6Summary summary,
            Map<String, BuildAreaSummary> bestAreaByGroup,
            Map<Long, Integer> blockToArea,
            Map<Long, ModuleMeta> chunkToModule,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            List<List<CityStage1Processor.BlockCoord>> buildableGroups,
            int areaSeq
    ) {
        if (buildableGroups == null) return areaSeq;
        for (List<CityStage1Processor.BlockCoord> blockGroup : buildableGroups) {
            if (blockGroup == null || blockGroup.isEmpty()) continue;

            Map<String, List<CityStage1Processor.BlockCoord>> splitByModule = new HashMap<>();
            Map<String, ModuleMeta> moduleMetaMap = new HashMap<>();
            for (CityStage1Processor.BlockCoord bc : blockGroup) {
                if (bc == null) continue;
                long chunkKey = ChunkPos.asLong(bc.x >> 4, bc.z >> 4);
                ModuleMeta meta = chunkToModule.get(chunkKey);
                if (meta == null) continue;
                splitByModule.computeIfAbsent(meta.group_id, k -> new ArrayList<>()).add(bc);
                moduleMetaMap.put(meta.group_id, meta);
            }

            for (Map.Entry<String, List<CityStage1Processor.BlockCoord>> entry : splitByModule.entrySet()) {
                ModuleMeta meta = moduleMetaMap.get(entry.getKey());
                areaSeq = registerArea(summary, bestAreaByGroup, blockToArea, meta, entry.getValue(), heightData, c2ScanData, areaSeq);
            }
        }
        return areaSeq;
    }

    private static int populateAreasFromOwnership(
            C6Summary summary,
            Map<String, BuildAreaSummary> bestAreaByGroup,
            Map<Long, Integer> blockToArea,
            Map<Long, ModuleMeta> chunkToModule,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            CityC3OwnershipIO.OwnershipData ownershipData,
            int areaSeq
    ) {
        if (ownershipData == null || ownershipData.owner == null) return areaSeq;
        Map<String, List<CityStage1Processor.BlockCoord>> splitByModule = new HashMap<>();
        Map<String, ModuleMeta> moduleMetaMap = new HashMap<>();
        for (int x = 0; x < ownershipData.width; x++) {
            for (int z = 0; z < ownershipData.height; z++) {
                int districtId = ownershipData.owner[x][z];
                if (districtId < 0) continue;
                int worldX = ownershipData.originX + x * Math.max(1, ownershipData.step);
                int worldZ = ownershipData.originZ + z * Math.max(1, ownershipData.step);
                long chunkKey = ChunkPos.asLong(worldX >> 4, worldZ >> 4);
                ModuleMeta meta = chunkToModule.get(chunkKey);
                if (meta == null) continue;
                CityStage1Processor.BlockCoord coord = new CityStage1Processor.BlockCoord();
                coord.x = worldX;
                coord.z = worldZ;
                splitByModule.computeIfAbsent(meta.group_id, k -> new ArrayList<>()).add(coord);
                moduleMetaMap.put(meta.group_id, meta);
            }
        }
        for (Map.Entry<String, List<CityStage1Processor.BlockCoord>> entry : splitByModule.entrySet()) {
            ModuleMeta meta = moduleMetaMap.get(entry.getKey());
            areaSeq = registerArea(summary, bestAreaByGroup, blockToArea, meta, entry.getValue(), heightData, c2ScanData, areaSeq);
        }
        return areaSeq;
    }

    private static int registerArea(
            C6Summary summary,
            Map<String, BuildAreaSummary> bestAreaByGroup,
            Map<Long, Integer> blockToArea,
            ModuleMeta meta,
            List<CityStage1Processor.BlockCoord> points,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            int areaSeq
    ) {
        if (meta == null || points == null || points.isEmpty()) return areaSeq;
        BuildAreaSummary area = buildAreaSummary(meta, points, heightData, c2ScanData, areaSeq++);
        summary.areas.add(area);
        for (CityStage1Processor.BlockCoord point : points) {
            blockToArea.put(packBlock(point.x, point.z), area.build_area_numeric_id);
        }
        BuildAreaSummary prev = bestAreaByGroup.get(area.group_id);
        if (prev == null || area.area_blocks > prev.area_blocks) {
            bestAreaByGroup.put(area.group_id, area);
        }
        return areaSeq;
    }

    public static void save(Path cityDir, C6Bundle bundle) throws Exception {
        if (bundle == null) return;
        Files.createDirectories(cityDir);
        if (bundle.summary != null) Files.writeString(cityDir.resolve(C6_SUMMARY_FILE), GSON.toJson(bundle.summary), StandardCharsets.UTF_8);
        if (bundle.layout != null) Files.writeString(cityDir.resolve(C6_LAYOUT_FILE), GSON.toJson(bundle.layout), StandardCharsets.UTF_8);
        if (bundle.decision_input != null) Files.writeString(cityDir.resolve(C6_RECT_DECISION_INPUT_FILE), GSON.toJson(bundle.decision_input), StandardCharsets.UTF_8);
        if (bundle.candidates != null) Files.writeString(cityDir.resolve(C6_RECT_CANDIDATES_FILE), GSON.toJson(bundle.candidates), StandardCharsets.UTF_8);
        if (bundle.validation != null) Files.writeString(cityDir.resolve(C6_RECT_VALIDATION_FILE), GSON.toJson(bundle.validation), StandardCharsets.UTF_8);
        writeIndexDat(cityDir.resolve(C6_INDEX_FILE), bundle.index_by_block);
    }

    public static void saveLayout(Path cityDir, C6Layout layout) throws Exception {
        if (cityDir == null || layout == null) return;
        Files.createDirectories(cityDir);
        Files.writeString(cityDir.resolve(C6_LAYOUT_FILE), GSON.toJson(layout), StandardCharsets.UTF_8);
    }

    public static void saveDecisionInput(Path cityDir, C6RectDecisionInput input) throws Exception {
        if (cityDir == null || input == null) return;
        Files.createDirectories(cityDir);
        Files.writeString(cityDir.resolve(C6_RECT_DECISION_INPUT_FILE), GSON.toJson(input), StandardCharsets.UTF_8);
    }

    public static void saveCandidates(Path cityDir, C6RectCandidates candidates) throws Exception {
        if (cityDir == null || candidates == null) return;
        Files.createDirectories(cityDir);
        Files.writeString(cityDir.resolve(C6_RECT_CANDIDATES_FILE), GSON.toJson(candidates), StandardCharsets.UTF_8);
    }

    public static void saveValidation(Path cityDir, C6RectValidation validation) throws Exception {
        if (cityDir == null || validation == null) return;
        Files.createDirectories(cityDir);
        Files.writeString(cityDir.resolve(C6_RECT_VALIDATION_FILE), GSON.toJson(validation), StandardCharsets.UTF_8);
    }

    public static C6Summary loadSummary(Path cityDir) throws Exception {
        Path file = cityDir.resolve(C6_SUMMARY_FILE);
        return Files.exists(file) ? GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), C6Summary.class) : null;
    }

    public static C6Layout loadLayout(Path cityDir) throws Exception {
        Path file = cityDir.resolve(C6_LAYOUT_FILE);
        return Files.exists(file) ? GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), C6Layout.class) : null;
    }

    public static C6RectDecisionInput loadDecisionInput(Path cityDir) throws Exception {
        Path file = cityDir.resolve(C6_RECT_DECISION_INPUT_FILE);
        return Files.exists(file) ? GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), C6RectDecisionInput.class) : null;
    }

    public static C6RectCandidates loadCandidates(Path cityDir) throws Exception {
        Path file = cityDir.resolve(C6_RECT_CANDIDATES_FILE);
        return Files.exists(file) ? GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), C6RectCandidates.class) : new C6RectCandidates();
    }

    public static C6RectValidation loadValidation(Path cityDir) throws Exception {
        Path file = cityDir.resolve(C6_RECT_VALIDATION_FILE);
        return Files.exists(file) ? GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), C6RectValidation.class) : new C6RectValidation();
    }

    public static GroupDecisionInput findDecisionGroup(C6RectDecisionInput input, String groupId) {
        if (input == null || input.groups == null || groupId == null) return null;
        for (GroupDecisionInput item : input.groups) if (item != null && groupId.equals(item.group_id)) return item;
        return null;
    }

    public static LayoutPlan findPlanByGroup(C6Layout layout, String groupId) {
        if (layout == null || layout.plans == null || groupId == null) return null;
        for (LayoutPlan plan : layout.plans) if (plan != null && groupId.equals(plan.group_id)) return plan;
        return null;
    }

    public static GroupRectCandidate findLatestCandidate(C6RectCandidates candidates, String groupId) {
        if (candidates == null || candidates.items == null || groupId == null) return null;
        GroupRectCandidate latest = null;
        for (GroupRectCandidate item : candidates.items) {
            if (item == null || !groupId.equals(item.group_id)) continue;
            if (latest == null || item.attempt_index >= latest.attempt_index) latest = item;
        }
        return latest;
    }

    public static GroupRectValidation findLatestValidation(C6RectValidation validation, String groupId) {
        if (validation == null || validation.items == null || groupId == null) return null;
        GroupRectValidation latest = null;
        for (GroupRectValidation item : validation.items) {
            if (item == null || !groupId.equals(item.group_id)) continue;
            if (latest == null || item.attempt_index >= latest.attempt_index) latest = item;
        }
        return latest;
    }

    public static int currentAttemptCount(C6RectCandidates candidates, String groupId) {
        GroupRectCandidate latest = findLatestCandidate(candidates, groupId);
        return latest != null ? latest.attempt_index : 0;
    }

    public static void upsertCandidate(C6RectCandidates candidates, GroupRectCandidate candidate) {
        if (candidates == null || candidate == null) return;
        if (candidates.items == null) candidates.items = new ArrayList<>();
        candidates.items.removeIf(item -> item != null && eq(item.group_id, candidate.group_id) && item.attempt_index == candidate.attempt_index);
        candidates.items.add(candidate);
        candidates.items.sort(Comparator.comparing((GroupRectCandidate item) -> safe(item.group_id)).thenComparingInt(item -> item.attempt_index));
        candidates.updated_at_epoch_ms = System.currentTimeMillis();
    }

    public static void upsertValidation(C6RectValidation validation, GroupRectValidation item) {
        if (validation == null || item == null) return;
        if (validation.items == null) validation.items = new ArrayList<>();
        validation.items.removeIf(existing -> existing != null && eq(existing.group_id, item.group_id) && existing.attempt_index == item.attempt_index);
        validation.items.add(item);
        validation.items.sort(Comparator.comparing((GroupRectValidation v) -> safe(v.group_id)).thenComparingInt(v -> v.attempt_index));
        validation.updated_at_epoch_ms = System.currentTimeMillis();
    }

    public static void updateAttemptCount(C6RectDecisionInput input, String groupId, int attemptCount) {
        GroupDecisionInput group = findDecisionGroup(input, groupId);
        if (group != null) group.current_attempt_count = Math.max(0, attemptCount);
    }

    public static void applyAcceptedDecision(C6Layout layout, BuildAreaSummary area, GroupRectCandidate candidate) {
        if (layout == null || area == null || candidate == null) return;
        LayoutPlan plan = findPlanByGroup(layout, area.group_id);
        if (plan == null) {
            plan = buildEmptyPlan(area, C6FillStyle.PLAZA_RING, null);
            layout.plans.add(plan);
        }
        plan.build_area_id = area.build_area_id;
        plan.validated = true;
        plan.decision_mode = candidate.decision_mode;
        plan.accepted_attempt_index = candidate.attempt_index;
        plan.primary_modules = new ArrayList<>();
        RectGuidance areaGuidance = plan.rect_guidance != null ? plan.rect_guidance : fallbackRectGuidance(area);
        if ("submit_rects".equals(candidate.decision_mode) && candidate.rects != null) {
            int index = 1;
            for (RectDecision rect : candidate.rects) {
                if (rect == null) continue;
                PrimaryModule module = new PrimaryModule();
                module.module_id = area.group_id + "_primary_" + index;
                module.rect_id = rect.rect_id;
                module.anchor = new Point();
                module.anchor.x = rect.cx;
                module.anchor.z = rect.cz;
                module.importance = index == 1 ? 1.0 : Math.max(0.25, 1.0 - index * 0.15);
                module.w = rect.w;
                module.h = rect.h;
                module.minX = rect.minX;
                module.minZ = rect.minZ;
                module.maxX = rect.maxX;
                module.maxZ = rect.maxZ;
                module.structure_guidance = adaptGuidanceForRect(areaGuidance, rect);
                module.template_hint = defaultTemplateHint(area, rect, module.structure_guidance);
                System.out.println("[C6] applyAcceptedDecision group=" + safeId(area.group_id)
                        + " module=" + module.module_id
                        + " rect=" + safeId(rect.rect_id)
                        + " rect_size=" + rect.w + "x" + rect.h
                        + " rect_area=" + (Math.max(0, rect.w) * Math.max(0, rect.h))
                        + " area_blocks=" + (area != null ? area.area_blocks : 0)
                        + " assigned_size_tier=" + (module.template_hint != null ? module.template_hint.size_tier : ""));
                plan.primary_modules.add(module);
                index++;
            }
        }
        plan.notes = "Validated AI decision (" + candidate.decision_mode + ")";
        layout.generated_at_epoch_ms = System.currentTimeMillis();
    }

    public static TemplateHint defaultTemplateHint(BuildAreaSummary area) {
        return defaultTemplateHint(area, null, null);
    }

    public static TemplateHint defaultTemplateHint(BuildAreaSummary area, RectDecision rect) {
        return defaultTemplateHint(area, rect, null);
    }

    public static TemplateHint defaultTemplateHint(BuildAreaSummary area, RectDecision rect, RectGuidance guidance) {
        TemplateHint hint = new TemplateHint();
        hint.category = classifyCategory(area, guidance);
        int areaBlocks = rect != null && rect.w > 0 && rect.h > 0
                ? rect.w * rect.h
                : (area != null ? area.area_blocks : 0);
        hint.size_tier = preferredSizeTier(areaBlocks, guidance);
        if (guidance != null) {
            hint.function_tag = guidance.function_tag;
            hint.interaction_role = guidance.interaction_role;
            hint.guidance_source = guidance.guidance_source;
            hint.fallback_path = guidance.fallback_path;
            hint.target_size_tiers = new ArrayList<>(guidance.target_size_tiers != null ? guidance.target_size_tiers : List.of());
            if (guidance.template_examples != null) {
                for (TemplateExample example : guidance.template_examples) {
                    if (example != null && example.structure_id != null && !example.structure_id.isBlank()) {
                        hint.recommended_templates.add(example.structure_id);
                    }
                }
            }
        }
        System.out.println("[C6] defaultTemplateHint group=" + safeId(area != null ? area.group_id : "")
                + " build_area=" + safeId(area != null ? area.build_area_id : "")
                + " rect=" + safeId(rect != null ? rect.rect_id : "")
                + " rect_size=" + (rect != null ? rect.w : 0) + "x" + (rect != null ? rect.h : 0)
                + " chosen_area_blocks=" + areaBlocks
                + " fallback_area_blocks=" + (area != null ? area.area_blocks : 0)
                + " size_tier=" + hint.size_tier);
        return hint;
    }

    private static ModuleMeta toModuleMeta(CitySemanticStages.ModuleGroup group) {
        ModuleMeta meta = new ModuleMeta();
        meta.group_id = group.group_id;
        meta.function = group.function;
        meta.layer = group.layer;
        return meta;
    }

    private static BuildAreaSummary buildAreaSummary(ModuleMeta meta, List<CityStage1Processor.BlockCoord> points, CityStage1BinaryIO.HeightData heightData, CityC2ScanBinaryIO.C2ScanData c2ScanData, int numericId) {
        BuildAreaSummary area = new BuildAreaSummary();
        area.group_id = meta.group_id;
        area.function = meta.function;
        area.layer = meta.layer;
        area.build_area_numeric_id = numericId;
        area.build_area_id = "ba_" + safeId(meta.group_id) + "_a" + String.format(Locale.ROOT, "%03d", numericId);
        area.area_blocks = points.size();

        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        long sumX = 0, sumZ = 0, sumH = 0;
        Set<Long> pointSet = new HashSet<>();
        for (CityStage1Processor.BlockCoord p : points) {
            if (p == null) continue;
            minX = Math.min(minX, p.x);
            minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x);
            maxZ = Math.max(maxZ, p.z);
            sumX += p.x;
            sumZ += p.z;
            sumH += heightAt(heightData, c2ScanData, p.x, p.z);
            pointSet.add(packBlock(p.x, p.z));
        }
        area.bbox.minX = minX;
        area.bbox.minZ = minZ;
        area.bbox.maxX = maxX;
        area.bbox.maxZ = maxZ;
        area.centroid.x = sumX / (double) Math.max(1, points.size());
        area.centroid.z = sumZ / (double) Math.max(1, points.size());
        area.avg_height = sumH / (double) Math.max(1, points.size());
        area.ascii_map = asciiPreview(pointSet, minX, minZ, maxX, maxZ, 32, 18);
        return area;
    }

    private static List<LayoutPlan> buildPlans(C6FillStyle fillStyle, Map<String, BuildAreaSummary> bestAreaByGroup, Map<String, RectGuidance> rectGuidanceByGroup) {
        List<LayoutPlan> plans = new ArrayList<>();
        List<BuildAreaSummary> areas = new ArrayList<>(bestAreaByGroup.values());
        areas.sort(Comparator.comparing(a -> a.group_id));
        for (BuildAreaSummary area : areas) plans.add(buildEmptyPlan(area, fillStyle, rectGuidanceByGroup != null ? rectGuidanceByGroup.get(area.group_id) : null));
        return plans;
    }

    private static LayoutPlan buildEmptyPlan(BuildAreaSummary area, C6FillStyle fillStyle, RectGuidance guidance) {
        LayoutPlan plan = new LayoutPlan();
        plan.group_id = area.group_id;
        plan.build_area_id = area.build_area_id;
        plan.fill_style = fillStyle.name();
        plan.rect_guidance = guidance != null ? copyGuidance(guidance) : fallbackRectGuidance(area);
        plan.fill_params = PlazaRingArranger.defaultParams(area.area_blocks, plan.rect_guidance);
        plan.rect_sizes = PlazaRingArranger.defaultRectSizes(plan.rect_guidance);
        plan.notes = "Prepared for AI rectangle decision; no default primary modules.";
        return plan;
    }

    private static List<GroupDecisionInput> buildDecisionInputs(Map<String, BuildAreaSummary> bestAreaByGroup, Map<String, RectGuidance> rectGuidanceByGroup) {
        List<GroupDecisionInput> items = new ArrayList<>();
        List<BuildAreaSummary> areas = new ArrayList<>(bestAreaByGroup.values());
        areas.sort(Comparator.comparing(a -> a.group_id));
        for (BuildAreaSummary area : areas) {
            GroupDecisionInput item = new GroupDecisionInput();
            item.group_id = area.group_id;
            item.build_area_id = area.build_area_id;
            item.build_area_numeric_id = area.build_area_numeric_id;
            item.function = area.function;
            item.layer = area.layer;
            item.polygon_area_blocks = area.area_blocks;
            item.polygon_reference_bbox = copyBBox(area.bbox);
            item.centroid = area.centroid;
            item.polygon_ascii_map = new ArrayList<>(area.ascii_map);
            item.boundary_notes = buildBoundaryNotes(area);
            item.rect_guidance = rectGuidanceByGroup != null && rectGuidanceByGroup.containsKey(area.group_id)
                    ? copyGuidance(rectGuidanceByGroup.get(area.group_id))
                    : fallbackRectGuidance(area);
            items.add(item);
        }
        return items;
    }

    private static List<String> buildBoundaryNotes(BuildAreaSummary area) {
        List<String> notes = new ArrayList<>();
        notes.add("Rect legality is evaluated against polygon coverage, not bbox containment.");
        notes.add("polygon_reference_bbox is only a preview framing reference.");
        notes.add("C6 does not decide final buildability; later stages may adapt terrain.");
        if (area != null) notes.add("Polygon area blocks: " + area.area_blocks);
        return notes;
    }

    private static BBox copyBBox(BBox source) {
        BBox copy = new BBox();
        if (source == null) return copy;
        copy.minX = source.minX;
        copy.minZ = source.minZ;
        copy.maxX = source.maxX;
        copy.maxZ = source.maxZ;
        return copy;
    }

    static RectGuidance deriveRectGuidance(BuildAreaSummary area, List<? extends CityC35CatalogIO.CatalogStructure> structures) {
        if (area == null) return new RectGuidance();
        String functionTag = inferFunctionTag(area);
        GrowthRule growthRule = resolveGrowthRule(functionTag, area != null ? area.area_blocks : 0);
        StructureTemplateQueryService.QueryResult query = queryFootprintCandidates(structures, functionTag, true);
        String source = "catalog_strict_footprint";
        if (query == null || !query.ok) {
            query = queryFootprintCandidates(structures, functionTag, false);
            source = "catalog_relaxed_footprint";
        }
        if (query == null || !query.ok || query.candidates == null || query.candidates.isEmpty()) {
            return fallbackRectGuidance(area);
        }

        Map<String, CityC35CatalogIO.CatalogStructure> byId = new HashMap<>();
        for (CityC35CatalogIO.CatalogStructure structure : structures) {
            if (structure != null && structure.structure_id != null) byId.put(structure.structure_id, structure);
        }

        List<TemplateExample> allTemplates = new ArrayList<>();
        for (StructureTemplateQueryService.Candidate candidate : query.candidates) {
            TemplateExample example = toTemplateExample(byId.get(candidate.structure_id));
            if (example == null) continue;
            if (example.size_tier == null || example.size_tier.isBlank()) example.size_tier = normalizeTier(candidate.size_tier);
            allTemplates.add(example);
        }
        if (allTemplates.isEmpty()) return fallbackRectGuidance(area);

        allTemplates.sort(Comparator.comparingInt((TemplateExample item) -> item.area_blocks).reversed().thenComparing(item -> safe(item.structure_id)));

        RectGuidance guidance = new RectGuidance();
        guidance.function_tag = functionTag;
        guidance.interaction_role = inferInteractionRole(functionTag);
        guidance.guidance_source = source;
        guidance.fallback_path = false;
        guidance.candidate_count = allTemplates.size();
        guidance.target_size_tiers = distinctTiers(allTemplates);
        guidance.edge_buffer_blocks = resolveEdgeBuffer(growthRule, byId, query.candidates);
        guidance.growth_buffer_blocks = growthRule != null ? Math.max(0, growthRule.growth_buffer_blocks) : defaultGrowthBuffer(functionTag);

        List<Integer> areas = sortedInts(allTemplates, template -> template.area_blocks);
        List<Integer> widths = sortedInts(allTemplates, template -> template.width_blocks);
        List<Integer> heights = sortedInts(allTemplates, template -> template.height_blocks);
        List<Double> ratios = sortedRatios(allTemplates);
        int medianArea = medianInt(areas);

        List<TemplateExample> mainTemplates = new ArrayList<>();
        for (TemplateExample template : allTemplates) {
            if (template.area_blocks >= medianArea) mainTemplates.add(template);
        }
        if (mainTemplates.isEmpty()) mainTemplates.add(allTemplates.get(0));
        mainTemplates.sort(Comparator.comparingInt((TemplateExample item) -> item.area_blocks).reversed());

        List<Integer> mainAreas = sortedInts(mainTemplates, template -> template.area_blocks);
        List<Integer> mainWidths = sortedInts(mainTemplates, template -> template.width_blocks);
        List<Integer> mainHeights = sortedInts(mainTemplates, template -> template.height_blocks);
        fillRange(guidance.main_template_area_blocks, min(mainAreas), medianInt(mainAreas), max(mainAreas));
        fillRange(guidance.main_template_width_blocks, min(mainWidths), medianInt(mainWidths), max(mainWidths));
        fillRange(guidance.main_template_height_blocks, min(mainHeights), medianInt(mainHeights), max(mainHeights));

        fillRange(guidance.rect_width_blocks,
                Math.max(4, (int) Math.ceil(guidance.main_template_width_blocks.min * 1.10)),
                Math.max(5, (int) Math.ceil(guidance.main_template_width_blocks.recommended * 1.18)),
                Math.max(6, (int) Math.ceil(guidance.main_template_width_blocks.max * 1.35)));
        fillRange(guidance.rect_height_blocks,
                Math.max(4, (int) Math.ceil(guidance.main_template_height_blocks.min * 1.10)),
                Math.max(5, (int) Math.ceil(guidance.main_template_height_blocks.recommended * 1.18)),
                Math.max(6, (int) Math.ceil(guidance.main_template_height_blocks.max * 1.35)));
        int rectAreaMin = Math.max(24, (int) Math.ceil(guidance.main_template_area_blocks.min * 1.20));
        int rectAreaRecommended = Math.max(rectAreaMin, (int) Math.ceil(guidance.main_template_area_blocks.recommended * 1.30));
        int rectAreaMax = Math.max(rectAreaRecommended, Math.min(Math.max(area.area_blocks, rectAreaRecommended), (int) Math.ceil(guidance.main_template_area_blocks.max * 1.65)));
        fillRange(guidance.rect_area_blocks, rectAreaMin, rectAreaRecommended, rectAreaMax);

        double minRatio = ratios.isEmpty() ? 1.0 : ratios.get(0);
        double maxRatio = ratios.isEmpty() ? 2.0 : ratios.get(ratios.size() - 1);
        fillRange(guidance.aspect_ratio, round3(Math.max(1.0, minRatio)), round3(Math.max(1.0, medianDouble(ratios))), round3(Math.max(1.0, maxRatio)));

        int preferredRects = guidance.rect_area_blocks.recommended <= 0
                ? 1
                : Math.max(1, Math.round(area.area_blocks / (float) guidance.rect_area_blocks.recommended));
        fillRange(guidance.recommended_rect_count, Math.max(1, preferredRects - 1), preferredRects, Math.max(preferredRects, preferredRects + 1));

        guidance.connector_reserve_by_side = resolveConnectorReserveBySide(byId, query.candidates, guidance);
        guidance.requires_expansion_side = resolveExpansionSide(growthRule, guidance.connector_reserve_by_side, guidance.interaction_role);
        guidance.design_notes = buildDesignNotes(guidance);
        System.out.println("[C6] deriveRectGuidance group=" + safeId(area.group_id)
                + " function=" + functionTag
                + " candidates=" + guidance.candidate_count
                + " edge_buffer=" + guidance.edge_buffer_blocks
                + " growth_buffer=" + guidance.growth_buffer_blocks
                + " expansion_side=" + guidance.requires_expansion_side
                + " connector_reserve=" + guidance.connector_reserve_by_side);

        int sampleCount = Math.min(4, mainTemplates.size());
        for (int i = 0; i < sampleCount; i++) guidance.template_examples.add(copyTemplate(mainTemplates.get(i)));
        return guidance;
    }

    private static StructureTemplateQueryService.QueryResult queryFootprintCandidates(List<? extends CityC35CatalogIO.CatalogStructure> structures, String functionTag, boolean strict) {
        if (structures == null || structures.isEmpty()) return null;
        StructureTemplateQueryService.QueryRequest request = new StructureTemplateQueryService.QueryRequest();
        request.function_tag = functionTag;
        request.strict_tag_source = strict;
        return StructureTemplateQueryService.queryTemplates(structures, request);
    }

    private static Map<String, RectGuidance> buildRectGuidanceByGroup(Map<String, BuildAreaSummary> bestAreaByGroup) {
        Map<String, RectGuidance> result = new HashMap<>();
        List<CityC35CatalogIO.CatalogStructure> structures = loadCatalogStructures();
        for (BuildAreaSummary area : bestAreaByGroup.values()) {
            result.put(area.group_id, deriveRectGuidance(area, structures));
        }
        return result;
    }

    private static List<CityC35CatalogIO.CatalogStructure> loadCatalogStructures() {
        try {
            CityC35CatalogIO.StructureCatalog catalog = CityC35CatalogIO.loadCatalog(GSON, CityC35CatalogIO.StructureCatalog.class);
            if (catalog == null || !catalog.ok || catalog.structures == null) return Collections.emptyList();
            return catalog.structures;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private static RectGuidance fallbackRectGuidance(BuildAreaSummary area) {
        RectGuidance guidance = new RectGuidance();
        guidance.function_tag = inferFunctionTag(area);
        guidance.interaction_role = inferInteractionRole(guidance.function_tag);
        guidance.guidance_source = "fallback_default_rect_profile";
        guidance.fallback_path = true;
        GrowthRule growthRule = resolveGrowthRule(guidance.function_tag, area != null ? area.area_blocks : 0);
        guidance.edge_buffer_blocks = growthRule != null ? Math.max(0, growthRule.edge_buffer_blocks) : defaultEdgeBuffer(guidance.function_tag);
        guidance.growth_buffer_blocks = growthRule != null ? Math.max(0, growthRule.growth_buffer_blocks) : defaultGrowthBuffer(guidance.function_tag);
        guidance.requires_expansion_side = growthRule != null && growthRule.default_expansion_side != null
                ? growthRule.default_expansion_side
                : defaultExpansionSide(guidance.function_tag, guidance.interaction_role);
        guidance.connector_reserve_by_side = defaultConnectorReserveBySide(guidance.requires_expansion_side, guidance.interaction_role);
        guidance.target_size_tiers = new ArrayList<>(List.of(sizeTier(area != null ? area.area_blocks : 0)));
        int bboxWidth = area != null ? Math.max(1, area.bbox.maxX - area.bbox.minX + 1) : 1;
        int bboxHeight = area != null ? Math.max(1, area.bbox.maxZ - area.bbox.minZ + 1) : 1;
        int approxEdge = (int) Math.round(Math.sqrt(Math.max(1, area != null ? area.area_blocks : 1)));
        int mainWidth = Math.max(5, Math.min(bboxWidth, approxEdge));
        int mainHeight = Math.max(5, Math.min(bboxHeight, Math.max(5, approxEdge - 2)));
        fillRange(guidance.main_template_width_blocks, Math.max(4, mainWidth - 2), mainWidth, Math.min(bboxWidth, mainWidth + 2));
        fillRange(guidance.main_template_height_blocks, Math.max(4, mainHeight - 2), mainHeight, Math.min(bboxHeight, mainHeight + 2));
        fillRange(guidance.main_template_area_blocks,
                Math.max(20, guidance.main_template_width_blocks.min * guidance.main_template_height_blocks.min),
                Math.max(30, guidance.main_template_width_blocks.recommended * guidance.main_template_height_blocks.recommended),
                Math.max(40, guidance.main_template_width_blocks.max * guidance.main_template_height_blocks.max));
        fillRange(guidance.rect_width_blocks,
                guidance.main_template_width_blocks.min + 1,
                guidance.main_template_width_blocks.recommended + 2,
                guidance.main_template_width_blocks.max + 4);
        fillRange(guidance.rect_height_blocks,
                guidance.main_template_height_blocks.min + 1,
                guidance.main_template_height_blocks.recommended + 2,
                guidance.main_template_height_blocks.max + 4);
        fillRange(guidance.rect_area_blocks,
                Math.max(30, guidance.rect_width_blocks.min * guidance.rect_height_blocks.min),
                Math.max(42, guidance.rect_width_blocks.recommended * guidance.rect_height_blocks.recommended),
                Math.max(56, Math.min(area != null ? area.area_blocks : 56, guidance.rect_width_blocks.max * guidance.rect_height_blocks.max)));
        fillRange(guidance.aspect_ratio, 1.0, round3(Math.max(guidance.rect_width_blocks.recommended, guidance.rect_height_blocks.recommended)
                / (double) Math.max(1, Math.min(guidance.rect_width_blocks.recommended, guidance.rect_height_blocks.recommended))), 2.2);
        int recommendedCount = guidance.rect_area_blocks.recommended <= 0 || area == null
                ? 1
                : Math.max(1, Math.round(area.area_blocks / (float) guidance.rect_area_blocks.recommended));
        fillRange(guidance.recommended_rect_count, Math.max(1, recommendedCount - 1), recommendedCount, Math.max(recommendedCount, recommendedCount + 1));
        guidance.design_notes = buildDesignNotes(guidance);
        System.out.println("[C6] fallbackRectGuidance group=" + safeId(area != null ? area.group_id : "")
                + " function=" + guidance.function_tag
                + " edge_buffer=" + guidance.edge_buffer_blocks
                + " growth_buffer=" + guidance.growth_buffer_blocks
                + " expansion_side=" + guidance.requires_expansion_side
                + " connector_reserve=" + guidance.connector_reserve_by_side);
        return guidance;
    }

    private static RectGuidance adaptGuidanceForRect(RectGuidance guidance, RectDecision rect) {
        RectGuidance copy = copyGuidance(guidance);
        if (rect == null || rect.w <= 0 || rect.h <= 0) return copy;
        copy.rect_area_blocks.recommended = rect.w * rect.h;
        copy.rect_width_blocks.recommended = rect.w;
        copy.rect_height_blocks.recommended = rect.h;
        copy.aspect_ratio.recommended = round3(Math.max(rect.w, rect.h) / (double) Math.max(1, Math.min(rect.w, rect.h)));
        if (copy.template_examples != null && !copy.template_examples.isEmpty()) {
            List<TemplateExample> fits = new ArrayList<>();
            for (TemplateExample example : copy.template_examples) {
                if (example != null && templateFits(rect.w, rect.h, example.width_blocks, example.height_blocks)) {
                    fits.add(copyTemplate(example));
                }
            }
            if (!fits.isEmpty()) copy.template_examples = fits;
        }
        return copy;
    }

    private static GrowthRule resolveGrowthRule(String functionTag, int polygonAreaBlocks) {
        GrowthRuleTable table = loadGrowthRuleTable();
        String areaTier = areaTier(polygonAreaBlocks);
        GrowthRule fallback = null;
        if (table == null || table.rules == null) return null;
        for (GrowthRule rule : table.rules) {
            if (rule == null) continue;
            if (!normalizeFunctionTag(functionTag).equals(normalizeFunctionTag(rule.function_tag))) continue;
            if (areaTier.equalsIgnoreCase(safe(rule.area_tier))) return rule;
            if ("XL".equals(areaTier) && "L".equalsIgnoreCase(safe(rule.area_tier))) fallback = rule;
        }
        return fallback;
    }

    private static GrowthRuleTable loadGrowthRuleTable() {
        if (growthRuleTableCache != null) return growthRuleTableCache;
        synchronized (CityC6Stages.class) {
            if (growthRuleTableCache != null) return growthRuleTableCache;
            try (InputStream in = CityC6Stages.class.getResourceAsStream(GROWTH_RULES_RESOURCE)) {
                GrowthRuleTable table = new GrowthRuleTable();
                if (in == null) {
                    growthRuleTableCache = table;
                    return growthRuleTableCache;
                }
                GrowthRule[] rules = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), GrowthRule[].class);
                if (rules != null) table.rules = new ArrayList<>(Arrays.asList(rules));
                growthRuleTableCache = table;
                return growthRuleTableCache;
            } catch (Exception ignored) {
                growthRuleTableCache = new GrowthRuleTable();
                return growthRuleTableCache;
            }
        }
    }

    private static int resolveEdgeBuffer(
            GrowthRule growthRule,
            Map<String, CityC35CatalogIO.CatalogStructure> byId,
            List<StructureTemplateQueryService.Candidate> candidates
    ) {
        int value = growthRule != null ? Math.max(0, growthRule.edge_buffer_blocks) : 0;
        if (candidates != null) {
            for (StructureTemplateQueryService.Candidate candidate : candidates) {
                CityC35CatalogIO.CatalogStructure structure = byId.get(candidate.structure_id);
                if (structure != null && structure.constraints != null) {
                    value = Math.max(value, Math.max(0, structure.constraints.min_edge_buffer));
                }
            }
        }
        return value > 0 ? value : 2;
    }

    private static int defaultEdgeBuffer(String functionTag) {
        return List.of("civic_center", "port", "military").contains(normalizeFunctionTag(functionTag)) ? 3 : 2;
    }

    private static int defaultGrowthBuffer(String functionTag) {
        return switch (normalizeFunctionTag(functionTag)) {
            case "market", "port" -> 8;
            case "residential" -> 5;
            case "civic_center" -> 4;
            case "military" -> 3;
            default -> 4;
        };
    }

    private static Map<String, Integer> resolveConnectorReserveBySide(
            Map<String, CityC35CatalogIO.CatalogStructure> byId,
            List<StructureTemplateQueryService.Candidate> candidates,
            RectGuidance guidance
    ) {
        ConnectorReserveStats stats = new ConnectorReserveStats();
        Map<String, List<CityC35CatalogIO.CatalogStructure>> structuresByPool = new HashMap<>();
        for (CityC35CatalogIO.CatalogStructure structure : byId.values()) {
            String pool = safe(structure != null ? structure.preset_pool : "").toLowerCase(Locale.ROOT);
            if (pool.isBlank()) continue;
            structuresByPool.computeIfAbsent(pool, ignored -> new ArrayList<>()).add(structure);
        }

        if (candidates != null) {
            for (StructureTemplateQueryService.Candidate candidate : candidates) {
                CityC35CatalogIO.CatalogStructure structure = byId.get(candidate.structure_id);
                if (structure == null || structure.connectors == null) continue;
                for (CityC35CatalogIO.ConnectorSpec connector : structure.connectors) {
                    if (connector == null) continue;
                    String side = normalizeSide(connector.facing);
                    if (!stats.perSide.containsKey(side)) continue;
                    boolean resolvedPool = false;
                    for (String poolId : CityC35CatalogIO.connectorPoolRefs(connector)) {
                        List<CityC35CatalogIO.CatalogStructure> poolMembers = structuresByPool.get(safe(poolId).toLowerCase(Locale.ROOT));
                        if (poolMembers == null || poolMembers.isEmpty()) continue;
                        resolvedPool = true;
                        for (CityC35CatalogIO.CatalogStructure child : poolMembers) {
                            int axisSize = connectorAxisSize(side, child);
                            if (axisSize > 0) stats.perSide.get(side).add(axisSize);
                        }
                    }
                    if (!resolvedPool) stats.fallbackSides.put(side, Math.max(stats.fallbackSides.get(side), 2));
                }
            }
        }

        Map<String, Integer> reserves = new LinkedHashMap<>();
        for (String side : List.of("north", "east", "south", "west")) {
            List<Integer> values = stats.perSide.get(side);
            if (values != null && !values.isEmpty()) {
                values.sort(Integer::compareTo);
                int p75 = values.get((int) Math.floor((values.size() - 1) * 0.75));
                reserves.put(side, Math.max(0, (int) Math.ceil(p75 * 0.6)));
            } else {
                reserves.put(side, stats.fallbackSides.getOrDefault(side, 0));
            }
        }
        if (allZero(reserves)) return defaultConnectorReserveBySide(defaultExpansionSide(guidance.function_tag, guidance.interaction_role), guidance.interaction_role);
        return reserves;
    }

    private static boolean allZero(Map<String, Integer> values) {
        for (Integer value : values.values()) if (value != null && value > 0) return false;
        return true;
    }

    private static int connectorAxisSize(String side, CityC35CatalogIO.CatalogStructure child) {
        return switch (normalizeSide(side)) {
            case "north", "south" -> footprintHeight(child);
            case "east", "west" -> footprintWidth(child);
            default -> 0;
        };
    }

    private static Map<String, Integer> defaultConnectorReserveBySide(String expansionSide, String interactionRole) {
        Map<String, Integer> reserves = new LinkedHashMap<>();
        for (String side : List.of("north", "east", "south", "west")) reserves.put(side, 1);
        String preferred = normalizeSide(expansionSide);
        if (!"auto".equals(preferred)) reserves.put(preferred, "EDGE_ATTACH".equalsIgnoreCase(safe(interactionRole)) ? 3 : 2);
        return reserves;
    }

    private static String resolveExpansionSide(GrowthRule rule, Map<String, Integer> connectorReserveBySide, String interactionRole) {
        String defaultSide = rule != null && rule.default_expansion_side != null && !rule.default_expansion_side.isBlank()
                ? normalizeSide(rule.default_expansion_side)
                : defaultExpansionSide(rule != null ? rule.function_tag : null, interactionRole);
        String winner = defaultSide;
        int best = -1;
        for (Map.Entry<String, Integer> entry : connectorReserveBySide.entrySet()) {
            int value = entry.getValue() != null ? entry.getValue() : 0;
            if (value > best) {
                best = value;
                winner = normalizeSide(entry.getKey());
            }
        }
        return winner == null || winner.isBlank() ? "auto" : winner;
    }

    private static String defaultExpansionSide(String functionTag, String interactionRole) {
        if ("EDGE_ATTACH".equalsIgnoreCase(safe(interactionRole))) return "east";
        if ("FRONT_TO_PLAZA".equalsIgnoreCase(safe(interactionRole))) return "south";
        if ("FRONT_TO_STREET".equalsIgnoreCase(safe(interactionRole))) return "south";
        return switch (normalizeFunctionTag(functionTag)) {
            case "market", "civic_center", "residential" -> "south";
            case "port" -> "east";
            default -> "auto";
        };
    }

    private static List<String> buildDesignNotes(RectGuidance guidance) {
        List<String> notes = new ArrayList<>();
        notes.add("Rect must leave edge buffer beyond the main template footprint.");
        if (guidance.connector_reserve_by_side != null && !guidance.connector_reserve_by_side.isEmpty()) {
            notes.add("Rect must reserve space on expansion sides for connector attachment.");
        }
        if (guidance.growth_buffer_blocks > 0) {
            notes.add("Growth space should not be absorbed entirely by the main footprint.");
        }
        if (guidance.requires_expansion_side != null && !"auto".equalsIgnoreCase(guidance.requires_expansion_side)) {
            notes.add("Do not draw long strips if the required expansion side cannot grow safely.");
        }
        return notes;
    }

    private static String areaTier(int polygonAreaBlocks) {
        if (polygonAreaBlocks >= 4500) return "XL";
        if (polygonAreaBlocks >= 2200) return "L";
        if (polygonAreaBlocks >= 900) return "M";
        return "S";
    }

    private static String normalizeFunctionTag(String raw) {
        return StructureTemplateQueryService.normalizeCatalogFunction(raw);
    }

    private static String normalizeSide(String raw) {
        String side = safe(raw).toLowerCase(Locale.ROOT);
        return switch (side) {
            case "north", "east", "south", "west" -> side;
            default -> "auto";
        };
    }

    private static RectGuidance copyGuidance(RectGuidance source) {
        RectGuidance copy = new RectGuidance();
        if (source == null) return copy;
        copy.function_tag = source.function_tag;
        copy.interaction_role = source.interaction_role;
        copy.guidance_source = source.guidance_source;
        copy.fallback_path = source.fallback_path;
        copy.candidate_count = source.candidate_count;
        copy.edge_buffer_blocks = source.edge_buffer_blocks;
        copy.growth_buffer_blocks = source.growth_buffer_blocks;
        copy.requires_expansion_side = source.requires_expansion_side;
        copy.design_notes = new ArrayList<>(source.design_notes != null ? source.design_notes : List.of());
        copy.connector_reserve_by_side = new LinkedHashMap<>();
        if (source.connector_reserve_by_side != null) copy.connector_reserve_by_side.putAll(source.connector_reserve_by_side);
        copy.target_size_tiers = new ArrayList<>(source.target_size_tiers != null ? source.target_size_tiers : List.of());
        copy.recommended_rect_count = copyRange(source.recommended_rect_count);
        copy.rect_area_blocks = copyRange(source.rect_area_blocks);
        copy.rect_width_blocks = copyRange(source.rect_width_blocks);
        copy.rect_height_blocks = copyRange(source.rect_height_blocks);
        copy.aspect_ratio = copyRange(source.aspect_ratio);
        copy.main_template_area_blocks = copyRange(source.main_template_area_blocks);
        copy.main_template_width_blocks = copyRange(source.main_template_width_blocks);
        copy.main_template_height_blocks = copyRange(source.main_template_height_blocks);
        if (source.template_examples != null) {
            for (TemplateExample example : source.template_examples) copy.template_examples.add(copyTemplate(example));
        }
        return copy;
    }

    private static IntRange copyRange(IntRange source) {
        IntRange copy = new IntRange();
        if (source == null) return copy;
        copy.min = source.min;
        copy.recommended = source.recommended;
        copy.max = source.max;
        return copy;
    }

    private static DoubleRange copyRange(DoubleRange source) {
        DoubleRange copy = new DoubleRange();
        if (source == null) return copy;
        copy.min = source.min;
        copy.recommended = source.recommended;
        copy.max = source.max;
        return copy;
    }

    private static TemplateExample copyTemplate(TemplateExample source) {
        if (source == null) return null;
        TemplateExample copy = new TemplateExample();
        copy.structure_id = source.structure_id;
        copy.size_tier = source.size_tier;
        copy.width_blocks = source.width_blocks;
        copy.height_blocks = source.height_blocks;
        copy.area_blocks = source.area_blocks;
        return copy;
    }

    private static TemplateExample toTemplateExample(CityC35CatalogIO.CatalogStructure structure) {
        if (structure == null) return null;
        int width = footprintWidth(structure);
        int height = footprintHeight(structure);
        if (width <= 0 || height <= 0) return null;
        TemplateExample example = new TemplateExample();
        example.structure_id = structure.structure_id;
        example.size_tier = normalizeTier(structure.size_tier);
        example.width_blocks = width;
        example.height_blocks = height;
        example.area_blocks = width * height;
        return example;
    }

    private static int footprintWidth(CityC35CatalogIO.CatalogStructure structure) {
        if (structure != null && structure.placement != null && structure.placement.footprint != null) {
            CityC35CatalogIO.Footprint footprint = structure.placement.footprint;
            if (footprint.max_x >= footprint.min_x) return footprint.max_x - footprint.min_x + 1;
        }
        return structure != null && structure.size != null ? Math.max(0, structure.size.width) : 0;
    }

    private static int footprintHeight(CityC35CatalogIO.CatalogStructure structure) {
        if (structure != null && structure.placement != null && structure.placement.footprint != null) {
            CityC35CatalogIO.Footprint footprint = structure.placement.footprint;
            if (footprint.max_z >= footprint.min_z) return footprint.max_z - footprint.min_z + 1;
        }
        return structure != null && structure.size != null ? Math.max(0, structure.size.length) : 0;
    }

    private static String inferFunctionTag(BuildAreaSummary area) {
        String function = StructureTemplateQueryService.normalizeCatalogFunction(area != null ? area.function : null);
        if (!function.isBlank()) return function;
        String group = safe(area != null ? area.group_id : "").toLowerCase(Locale.ROOT);
        if (group.contains("port") || group.contains("dock") || group.contains("harbor")) return "port";
        if (group.contains("market") || group.contains("shop")) return "market";
        if (group.contains("farm")) return "farm";
        if (group.contains("civic") || group.contains("plaza") || group.contains("school")) return "civic_center";
        if (group.contains("tower") || group.contains("fort") || group.contains("defense")) return "military";
        return "residential";
    }

    private static String inferInteractionRole(String functionTag) {
        String normalized = safe(functionTag).toLowerCase(Locale.ROOT);
        if (List.of("civic_center", "market", "commercial").contains(normalized)) return "FRONT_TO_PLAZA";
        if (List.of("port", "military").contains(normalized)) return "EDGE_ATTACH";
        return "FRONT_TO_STREET";
    }

    private static String classifyCategory(BuildAreaSummary area, RectGuidance guidance) {
        String functionTag = guidance != null && guidance.function_tag != null ? guidance.function_tag : inferFunctionTag(area);
        if (List.of("civic_center", "market", "commercial").contains(functionTag)) return "plaza_or_civic";
        if ("port".equals(functionTag)) return "port_or_trade";
        if ("military".equals(functionTag)) return "defense";
        return "residential";
    }

    private static String preferredSizeTier(int areaBlocks, RectGuidance guidance) {
        if (guidance != null && guidance.target_size_tiers != null && !guidance.target_size_tiers.isEmpty()) {
            if (areaBlocks >= 2500 && guidance.target_size_tiers.contains("L")) return "L";
            if (areaBlocks >= 900 && guidance.target_size_tiers.contains("M")) return "M";
            if (guidance.target_size_tiers.contains("S")) return "S";
            return guidance.target_size_tiers.get(0);
        }
        return sizeTier(areaBlocks);
    }

    private static String normalizeTier(String raw) {
        String normalized = safe(raw).toUpperCase(Locale.ROOT);
        return List.of("S", "M", "L").contains(normalized) ? normalized : "M";
    }

    private static List<String> distinctTiers(List<TemplateExample> examples) {
        List<String> tiers = new ArrayList<>();
        for (TemplateExample example : examples) {
            String tier = normalizeTier(example != null ? example.size_tier : null);
            if (!tiers.contains(tier)) tiers.add(tier);
        }
        if (tiers.isEmpty()) tiers.add("M");
        tiers.sort(Comparator.comparingInt(CityC6Stages::tierOrder));
        return tiers;
    }

    private static int tierOrder(String tier) {
        return switch (normalizeTier(tier)) {
            case "S" -> 0;
            case "M" -> 1;
            case "L" -> 2;
            default -> 1;
        };
    }

    private static List<Integer> sortedInts(List<TemplateExample> templates, ValueExtractor extractor) {
        List<Integer> values = new ArrayList<>();
        for (TemplateExample template : templates) values.add(extractor.value(template));
        values.sort(Integer::compareTo);
        return values;
    }

    private static List<Double> sortedRatios(List<TemplateExample> templates) {
        List<Double> values = new ArrayList<>();
        for (TemplateExample template : templates) {
            values.add(Math.max(template.width_blocks, template.height_blocks) / (double) Math.max(1, Math.min(template.width_blocks, template.height_blocks)));
        }
        values.sort(Double::compareTo);
        return values;
    }

    private static int medianInt(List<Integer> values) {
        return values == null || values.isEmpty() ? 0 : values.get(values.size() / 2);
    }

    private static double medianDouble(List<Double> values) {
        return values == null || values.isEmpty() ? 0.0 : values.get(values.size() / 2);
    }

    private static int min(List<Integer> values) {
        return values == null || values.isEmpty() ? 0 : values.get(0);
    }

    private static int max(List<Integer> values) {
        return values == null || values.isEmpty() ? 0 : values.get(values.size() - 1);
    }

    private static void fillRange(IntRange range, int min, int recommended, int max) {
        range.min = Math.max(0, min);
        range.recommended = Math.max(range.min, recommended);
        range.max = Math.max(range.recommended, max);
    }

    private static void fillRange(DoubleRange range, double min, double recommended, double max) {
        range.min = Math.max(0.0, min);
        range.recommended = Math.max(range.min, recommended);
        range.max = Math.max(range.recommended, max);
    }

    static boolean templateFits(int rectW, int rectH, int tplW, int tplH) {
        return (rectW >= tplW && rectH >= tplH) || (rectW >= tplH && rectH >= tplW);
    }

    private static List<String> asciiPreview(Set<Long> points, int minX, int minZ, int maxX, int maxZ, int outW, int outH) {
        List<String> rows = new ArrayList<>();
        int width = Math.max(1, maxX - minX + 1);
        int height = Math.max(1, maxZ - minZ + 1);
        int stepX = Math.max(1, (int) Math.ceil(width / (double) outW));
        int stepZ = Math.max(1, (int) Math.ceil(height / (double) outH));
        for (int z = minZ; z <= maxZ; z += stepZ) {
            StringBuilder row = new StringBuilder();
            for (int x = minX; x <= maxX; x += stepX) {
                boolean filled = false;
                for (int sx = 0; sx < stepX && !filled; sx++) for (int sz = 0; sz < stepZ; sz++) if (points.contains(packBlock(x + sx, z + sz))) { filled = true; break; }
                row.append(filled ? '#' : '.');
            }
            rows.add(row.toString());
            if (rows.size() >= outH) break;
        }
        return rows;
    }

    private static int heightAt(CityStage1BinaryIO.HeightData data, CityC2ScanBinaryIO.C2ScanData c2ScanData, int worldX, int worldZ) {
        return CityHeightResolver.resolveHeight(data, c2ScanData, worldX, worldZ);
    }

    private static String safeId(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean eq(String a, String b) {
        return safe(a).equals(safe(b));
    }

    private static String sizeTier(int areaBlocks) {
        if (areaBlocks >= 2500) return "L";
        if (areaBlocks >= 900) return "M";
        return "S";
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    @FunctionalInterface
    private interface ValueExtractor {
        int value(TemplateExample template);
    }

    private static void writeIndexDat(Path file, Map<Long, Integer> blockToArea) throws Exception {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file.toFile()))) {
            out.writeInt(INDEX_VERSION);
            out.writeInt(blockToArea != null ? blockToArea.size() : 0);
            if (blockToArea == null) return;
            for (Map.Entry<Long, Integer> e : blockToArea.entrySet()) {
                int x = (int) (e.getKey() >> 32);
                int z = (int) (long) e.getKey();
                out.writeInt(x);
                out.writeInt(z);
                out.writeInt(e.getValue() != null ? e.getValue() : 0);
            }
        }
    }

    public static Map<Long, Integer> loadIndex(Path cityDir) throws Exception {
        Path file = cityDir.resolve(C6_INDEX_FILE);
        if (!Files.exists(file)) return null;
        Map<Long, Integer> map = new LinkedHashMap<>();
        try (DataInputStream in = new DataInputStream(new FileInputStream(file.toFile()))) {
            int version = in.readInt();
            if (version != INDEX_VERSION) return null;
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int x = in.readInt();
                int z = in.readInt();
                int areaId = in.readInt();
                map.put(packBlock(x, z), areaId);
            }
        }
        return map;
    }
}
