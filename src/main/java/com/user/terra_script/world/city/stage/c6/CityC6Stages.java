package com.user.terra_script.world.city.stage.c6;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.user.terra_script.world.city.CityInstance;
import com.user.terra_script.world.city.district.District;
import com.user.terra_script.world.city.stage.CityHeightResolver;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import com.user.terra_script.world.city.stage.c1.CityStage1Processor;
import com.user.terra_script.world.city.stage.c2.CityC2ScanBinaryIO;
import com.user.terra_script.world.city.stage.c4.CitySemanticStages;
import net.minecraft.world.level.ChunkPos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    public static final int RECT_ATTEMPT_LIMIT = 3;
    public static final double MIN_TOTAL_PRIMARY_AREA_RATIO = 0.50;
    public static final double MIN_COVERAGE_RATIO = 0.80;

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
    }

    public static class TemplateHint {
        public String category;
        public String size_tier;
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
        public BBox mask_bbox = new BBox();
        public Point centroid = new Point();
        public int rect_limit = RECT_ATTEMPT_LIMIT;
        public double min_total_primary_area_ratio = MIN_TOTAL_PRIMARY_AREA_RATIO;
        public int current_attempt_count;
        public PreviewPaths previews = new PreviewPaths();
    }

    public static class PreviewPaths {
        public String height;
        public String hillshade;
        public String roughness;
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
        public boolean valid;
        public String reason;
    }

    private static class ModuleMeta {
        String group_id;
        String function;
        String layer;
    }

    public static C6Bundle generate(
            CityInstance city,
            CitySemanticStages.C5Groups c5Groups,
            CityStage1BinaryIO.HeightData heightData,
            List<List<CityStage1Processor.BlockCoord>> buildableGroups,
            C6FillStyle fillStyle
    ) {
        return generate(city, c5Groups, heightData, null, buildableGroups, fillStyle);
    }

    public static C6Bundle generate(
            CityInstance city,
            CitySemanticStages.C5Groups c5Groups,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData,
            List<List<CityStage1Processor.BlockCoord>> buildableGroups,
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

        if (city == null || c5Groups == null || heightData == null || buildableGroups == null) {
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
                List<CityStage1Processor.BlockCoord> points = entry.getValue();
                if (points.isEmpty()) continue;
                ModuleMeta meta = moduleMetaMap.get(entry.getKey());
                BuildAreaSummary area = buildAreaSummary(meta, points, heightData, c2ScanData, areaSeq++);
                summary.areas.add(area);
                for (CityStage1Processor.BlockCoord point : points) {
                    blockToArea.put(packBlock(point.x, point.z), area.build_area_numeric_id);
                }
                BuildAreaSummary prev = bestAreaByGroup.get(area.group_id);
                if (prev == null || area.area_blocks > prev.area_blocks) {
                    bestAreaByGroup.put(area.group_id, area);
                }
            }
        }

        summary.areas.sort(Comparator.comparing(a -> a.build_area_id));
        layout.plans = buildPlans(fillStyle, bestAreaByGroup);
        decisionInput.groups = buildDecisionInputs(bestAreaByGroup);
        bundle.indexed_block_count = blockToArea.size();
        bundle.index_by_block = blockToArea;
        return bundle;
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
            plan = buildEmptyPlan(area, C6FillStyle.PLAZA_RING);
            layout.plans.add(plan);
        }
        plan.build_area_id = area.build_area_id;
        plan.validated = true;
        plan.decision_mode = candidate.decision_mode;
        plan.accepted_attempt_index = candidate.attempt_index;
        plan.primary_modules = new ArrayList<>();
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
                module.template_hint = defaultTemplateHint(area);
                plan.primary_modules.add(module);
                index++;
            }
        }
        plan.notes = "Validated AI decision (" + candidate.decision_mode + ")";
        layout.generated_at_epoch_ms = System.currentTimeMillis();
    }

    public static TemplateHint defaultTemplateHint(BuildAreaSummary area) {
        TemplateHint hint = new TemplateHint();
        hint.category = "plaza_or_civic";
        hint.size_tier = sizeTier(area != null ? area.area_blocks : 0);
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

    private static List<LayoutPlan> buildPlans(C6FillStyle fillStyle, Map<String, BuildAreaSummary> bestAreaByGroup) {
        List<LayoutPlan> plans = new ArrayList<>();
        List<BuildAreaSummary> areas = new ArrayList<>(bestAreaByGroup.values());
        areas.sort(Comparator.comparing(a -> a.group_id));
        for (BuildAreaSummary area : areas) plans.add(buildEmptyPlan(area, fillStyle));
        return plans;
    }

    private static LayoutPlan buildEmptyPlan(BuildAreaSummary area, C6FillStyle fillStyle) {
        LayoutPlan plan = new LayoutPlan();
        plan.group_id = area.group_id;
        plan.build_area_id = area.build_area_id;
        plan.fill_style = fillStyle.name();
        plan.fill_params = PlazaRingArranger.defaultParams(area.area_blocks);
        plan.rect_sizes = PlazaRingArranger.defaultRectSizes();
        plan.notes = "Prepared for AI rectangle decision; no default primary modules.";
        return plan;
    }

    private static List<GroupDecisionInput> buildDecisionInputs(Map<String, BuildAreaSummary> bestAreaByGroup) {
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
            item.mask_bbox = area.bbox;
            item.centroid = area.centroid;
            items.add(item);
        }
        return items;
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

    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
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
