package com.user.terra_script.world.city.stage.c4;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.user.terra_script.world.city.CityConfig;
import com.user.terra_script.world.city.CityInstance;
import com.user.terra_script.world.city.district.District;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CitySemanticStages {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String C4_FILE = "C4_FunctionPlan.json";
    public static final String C4_VALIDATED_FILE = "C4_FunctionPlan.validated.json";
    public static final String C4_WHITELIST_FILE = "C4_FunctionWhitelist.json";
    public static final String C3_5_FUNCTION_ENUM_FILE = "C3_5_FunctionEnumTable.json";
    public static final String C5_FILE = "C5_ModuleGroups.json";
    public static final String C5_MERGE_LOG_FILE = "C5_MergeLog.json";
    private static final String FUNCTION_WHITELIST_VERSION = "ai_dynamic_v1";
    private static final List<String> DEFAULT_PRIMARY_FUNCTIONS = List.of("core", "market", "port", "residential", "defense");
    private static final List<String> DEFAULT_SECONDARY_FUNCTIONS = List.of("storage", "amenity", "craft", "garden", "watch");

    private CitySemanticStages() {}

    public static class C4Plan {
        public String step = "C4";
        public boolean ok = true;
        public String city_id;
        public int version = 1;
        public long generated_at_epoch_ms;
        public List<DistrictFunction> district_functions = new ArrayList<>();
        public String function_whitelist_version = FUNCTION_WHITELIST_VERSION;
        public Map<String, List<String>> adjacency = new LinkedHashMap<>();
    }

    public static class FunctionWhitelist {
        public String step = "C4_WHITELIST";
        public boolean ok = true;
        public String city_id;
        public String version = FUNCTION_WHITELIST_VERSION;
        public long generated_at_epoch_ms;
        public String source = "mcp_ai";
        public String rationale = "";
        public List<String> primary_functions = new ArrayList<>();
        public List<String> secondary_functions = new ArrayList<>();
    }

    public static class DistrictFunction {
        public String district_id;
        public int district_numeric_id;
        public String layer;
        public String zone_type;
        public String primary_function;
        public List<String> secondary_functions = new ArrayList<>();
        public double priority;
        public Constraints constraints = new Constraints();
        public String notes;
        public int chunk_count;
        public int area_blocks;
        public Point centroid = new Point();
    }

    public static class Constraints {
        public List<String> avoid_adjacent = new ArrayList<>();
        public List<String> prefer_adjacent = new ArrayList<>();
    }

    public static class C5Groups {
        public String step = "C5";
        public boolean ok = true;
        public String city_id;
        public long generated_at_epoch_ms;
        public List<ModuleGroup> groups = new ArrayList<>();
        public MergeStats merge_stats = new MergeStats();
        public MergePolicy policy = MergePolicy.defaults();
        public List<MergeLogEntry> merge_log = new ArrayList<>();
        public Quality quality = new Quality();
    }

    public static class ModuleGroup {
        public String group_id;
        public String function;
        public String layer;
        public List<String> district_ids = new ArrayList<>();
        public List<Integer> district_numeric_ids = new ArrayList<>();
        public int area_blocks;
        public Point centroid = new Point();
        public Connectivity connectivity = new Connectivity();
    }

    public static class Connectivity {
        public int component_count = 1;
        public double compactness;
    }

    public static class MergeStats {
        public int input_district_count;
        public int output_group_count;
        public double fragment_reduction_ratio;
    }

    public static class MergePolicy {
        public boolean cross_layer_merge = false;
        public String adjacency_mode = "shared_edge_only";
        public int min_district_area_chunks = 2;
        public boolean allow_cross_function_absorb_for_tiny = true;
        public boolean split_disconnected_group = true;
        public double min_compactness = 0.15;

        public static MergePolicy defaults() {
            return new MergePolicy();
        }
    }

    public static class MergeLogEntry {
        public String type;
        public String district_id;
        public String from_function;
        public String to_group_id;
        public String reason;
    }

    public static class Quality {
        public int disconnected_groups;
        public int low_compactness_groups;
    }

    public static class Point {
        public double x;
        public double z;
    }

    public static C4Plan generateC4(CityInstance city, FunctionWhitelist whitelistInput) {
        C4Plan plan = new C4Plan();
        if (city == null) {
            plan.ok = false;
            return plan;
        }
        FunctionWhitelist whitelist = sanitizeWhitelist(whitelistInput, city != null ? city.id : null);
        if (whitelist.primary_functions.isEmpty()) {
            plan.ok = false;
            return plan;
        }
        plan.city_id = city.id;
        plan.generated_at_epoch_ms = System.currentTimeMillis();
        plan.function_whitelist_version = whitelist.version;

        CityConfig.LayerLayout layout = city.getLayerLayout();
        Map<Integer, Set<Integer>> adjacencyMap = buildDistrictAdjacency(city);
        List<District> districts = city.districts != null ? city.districts : Collections.emptyList();
        districts.sort(Comparator.comparingInt(d -> d.id));

        for (District district : districts) {
            DistrictFunction df = new DistrictFunction();
            df.district_numeric_id = district.id;
            df.district_id = formatDistrictId(district.id, district.layerIndex);
            df.layer = layerName(layout, district.layerIndex);
            df.zone_type = normalizeUpper(district.zoneType, "URBAN");
            df.chunk_count = district.memberChunks != null ? district.memberChunks.size() : 0;
            df.area_blocks = df.chunk_count * 256;
            df.centroid.x = district.centerX;
            df.centroid.z = district.centerZ;

            boolean isWall = isWallLayer(layout, district.layerIndex);
            String inferredPrimary = inferPrimaryFunction(df.zone_type, isWall, district.density);
            String primaryFallback = pickPrimaryFallback(df.zone_type, df.layer, whitelist.primary_functions, inferredPrimary);
            df.primary_function = validateFunction(inferredPrimary, primaryFallback, whitelist.primary_functions);
            df.secondary_functions = inferSecondaryFunctions(df.primary_function, df.zone_type);
            df.priority = inferPriority(df.zone_type, district.density, df.chunk_count);
            df.constraints = inferConstraints(df.primary_function);
            df.notes = inferNotes(df.primary_function, isWall);
            sanitizeDistrictFunction(df, whitelist);

            plan.district_functions.add(df);
        }
        applyLayerFunctionMix(city, plan.district_functions, whitelist);

        Map<String, DistrictFunction> byId = new HashMap<>();
        for (DistrictFunction df : plan.district_functions) byId.put(df.district_id, df);
        for (District district : districts) {
            String from = formatDistrictId(district.id, district.layerIndex);
            Set<Integer> toSet = adjacencyMap.getOrDefault(district.id, Collections.emptySet());
            List<String> neighbors = new ArrayList<>();
            for (int id : toSet) {
                District match = findDistrictById(districts, id);
                if (match == null) continue;
                String nid = formatDistrictId(match.id, match.layerIndex);
                if (byId.containsKey(nid)) neighbors.add(nid);
            }
            neighbors.sort(String::compareTo);
            plan.adjacency.put(from, neighbors);
        }

        return plan;
    }

    public static C5Groups generateC5(CityInstance city, C4Plan c4Plan, boolean crossLayerMerge) {
        MergePolicy policy = MergePolicy.defaults();
        policy.cross_layer_merge = crossLayerMerge;
        return generateC5(city, c4Plan, policy);
    }

    public static C5Groups generateC5(CityInstance city, C4Plan c4Plan, MergePolicy policyInput) {
        C5Groups groups = new C5Groups();
        if (city == null || c4Plan == null) {
            groups.ok = false;
            return groups;
        }
        MergePolicy policy = sanitizePolicy(policyInput);
        groups.city_id = city.id;
        groups.generated_at_epoch_ms = System.currentTimeMillis();
        groups.policy = policy;

        Map<String, DistrictFunction> dfMap = new LinkedHashMap<>();
        FunctionWhitelist whitelist = sanitizeWhitelist(loadWhitelistFromC4(c4Plan), c4Plan != null ? c4Plan.city_id : null);
        for (DistrictFunction df : c4Plan.district_functions) {
            if (df == null || df.district_id == null || df.district_id.isBlank()) continue;
            dfMap.put(df.district_id, sanitizeDistrictFunction(df, whitelist));
        }
        Map<String, List<String>> adjacency = c4Plan.adjacency != null ? c4Plan.adjacency : Collections.emptyMap();
        List<GroupDraft> drafts = buildBaseDrafts(c4Plan.district_functions, dfMap, adjacency, policy.cross_layer_merge);

        absorbTinyDistricts(drafts, dfMap, adjacency, groups.merge_log, policy);
        drafts = cleanupAndRenumber(drafts);

        if (policy.split_disconnected_group) {
            drafts = splitDisconnectedDrafts(drafts, dfMap, adjacency, groups.merge_log);
            drafts = cleanupAndRenumber(drafts);
        }

        int groupSeq = 1;
        for (GroupDraft draft : drafts) {
            List<DistrictFunction> component = new ArrayList<>();
            for (String districtId : draft.districtIds) {
                DistrictFunction df = dfMap.get(districtId);
                if (df != null) component.add(df);
            }
            if (component.isEmpty()) continue;
            ModuleGroup group = toModuleGroup(component, groupSeq++);
            groups.groups.add(group);
        }

        groups.groups.sort(Comparator.comparing(g -> g.group_id));
        groups.merge_stats.input_district_count = dfMap.size();
        groups.merge_stats.output_group_count = groups.groups.size();
        int in = Math.max(1, groups.merge_stats.input_district_count);
        groups.merge_stats.fragment_reduction_ratio = clamp01(1.0 - (groups.merge_stats.output_group_count / (double) in));
        groups.quality.disconnected_groups = countDisconnectedGroups(groups.groups, adjacency);
        groups.quality.low_compactness_groups = countLowCompactness(groups.groups, policy.min_compactness);
        return groups;
    }

    public static void saveC4(Path cityDir, C4Plan plan) throws Exception {
        Files.createDirectories(cityDir);
        FunctionWhitelist whitelist = loadFunctionWhitelist(cityDir);
        C4Plan validated = validateC4(plan, whitelist);
        Files.writeString(cityDir.resolve(C4_FILE), GSON.toJson(plan), StandardCharsets.UTF_8);
        Files.writeString(cityDir.resolve(C4_VALIDATED_FILE), GSON.toJson(validated), StandardCharsets.UTF_8);
    }

    public static void saveFunctionWhitelist(Path cityDir, FunctionWhitelist whitelist) throws Exception {
        Files.createDirectories(cityDir);
        FunctionWhitelist normalized = sanitizeWhitelist(whitelist, whitelist != null ? whitelist.city_id : null);
        Files.writeString(cityDir.resolve(C4_WHITELIST_FILE), GSON.toJson(normalized), StandardCharsets.UTF_8);
    }

    public static FunctionWhitelist loadFunctionWhitelist(Path cityDir) throws Exception {
        Path file = cityDir.resolve(C4_WHITELIST_FILE);
        if (!Files.exists(file)) return null;
        FunctionWhitelist raw = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), FunctionWhitelist.class);
        return sanitizeWhitelist(raw, raw != null ? raw.city_id : null);
    }


    public static FunctionWhitelist loadFunctionWhitelistOrEnum(Path cityDir, String cityId) throws Exception {
        FunctionWhitelist whitelist = loadFunctionWhitelist(cityDir);
        if (whitelist != null && whitelist.ok) return whitelist;
        FunctionWhitelist fallback = new FunctionWhitelist();
        fallback.city_id = cityId;
        fallback.version = FUNCTION_WHITELIST_VERSION;
        fallback.source = "enum_fallback";
        fallback.primary_functions = new ArrayList<>(DEFAULT_PRIMARY_FUNCTIONS);
        fallback.secondary_functions = new ArrayList<>(DEFAULT_SECONDARY_FUNCTIONS);
        fallback.ok = true;
        return sanitizeWhitelist(fallback, cityId);
    }
    public static C4Plan loadC4(Path cityDir) throws Exception {
        Path file = cityDir.resolve(C4_FILE);
        if (!Files.exists(file)) return null;
        return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), C4Plan.class);
    }

    public static C4Plan loadC4Validated(Path cityDir) throws Exception {
        Path file = cityDir.resolve(C4_VALIDATED_FILE);
        if (!Files.exists(file)) return null;
        return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), C4Plan.class);
    }

    public static void saveC5(Path cityDir, C5Groups groups) throws Exception {
        Files.createDirectories(cityDir);
        Files.writeString(cityDir.resolve(C5_FILE), GSON.toJson(groups), StandardCharsets.UTF_8);
        Files.writeString(cityDir.resolve(C5_MERGE_LOG_FILE), GSON.toJson(groups != null ? groups.merge_log : Collections.emptyList()), StandardCharsets.UTF_8);
    }

    public static C5Groups loadC5(Path cityDir) throws Exception {
        Path file = cityDir.resolve(C5_FILE);
        if (!Files.exists(file)) return null;
        return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), C5Groups.class);
    }

    private static class GroupDraft {
        int id;
        String function;
        String layer;
        Set<String> districtIds = new HashSet<>();
    }

    private static MergePolicy sanitizePolicy(MergePolicy policyInput) {
        MergePolicy policy = policyInput == null ? MergePolicy.defaults() : policyInput;
        if (policy.adjacency_mode == null || policy.adjacency_mode.isBlank()) {
            policy.adjacency_mode = "shared_edge_only";
        }
        if (!"shared_edge_only".equals(policy.adjacency_mode)) {
            policy.adjacency_mode = "shared_edge_only";
        }
        policy.min_district_area_chunks = Math.max(0, policy.min_district_area_chunks);
        policy.min_compactness = clamp01(policy.min_compactness);
        return policy;
    }

    private static DistrictFunction sanitizeDistrictFunction(DistrictFunction source, FunctionWhitelist whitelist) {
        DistrictFunction df = source == null ? new DistrictFunction() : source;
        List<String> primaryWhitelist = whitelist != null ? whitelist.primary_functions : Collections.emptyList();
        List<String> secondaryWhitelist = secondaryWhitelist(whitelist);
        String primaryFallback = pickPrimaryFallback(df.zone_type, df.layer, primaryWhitelist, df.primary_function);
        df.primary_function = validateFunction(df.primary_function, primaryFallback, primaryWhitelist);
        if (df.secondary_functions == null) df.secondary_functions = new ArrayList<>();
        List<String> filteredSecondary = new ArrayList<>();
        for (String secondary : df.secondary_functions) {
            String normalized = validateFunction(secondary, null, secondaryWhitelist);
            if (normalized == null || filteredSecondary.contains(normalized)) continue;
            filteredSecondary.add(normalized);
        }
        if (filteredSecondary.isEmpty() && !secondaryWhitelist.isEmpty()) {
            String secondaryFallback = pickSecondaryFallback(df.primary_function, df.zone_type, secondaryWhitelist);
            if (secondaryFallback != null) filteredSecondary.add(secondaryFallback);
        }
        df.secondary_functions = filteredSecondary;
        if (df.constraints == null) df.constraints = new Constraints();
        if (df.constraints.avoid_adjacent == null) df.constraints.avoid_adjacent = new ArrayList<>();
        if (df.constraints.prefer_adjacent == null) df.constraints.prefer_adjacent = new ArrayList<>();
        df.layer = normalizeLower(df.layer, "layer_0");
        df.priority = clamp01(df.priority);
        return df;
    }

    private static C4Plan validateC4(C4Plan plan, FunctionWhitelist whitelist) {
        C4Plan copy = plan == null ? new C4Plan() : GSON.fromJson(GSON.toJson(plan), C4Plan.class);
        if (copy.district_functions == null) copy.district_functions = new ArrayList<>();
        FunctionWhitelist normalizedWhitelist = sanitizeWhitelist(whitelist, copy.city_id);
        copy.function_whitelist_version = normalizedWhitelist.version;
        for (DistrictFunction df : copy.district_functions) {
            sanitizeDistrictFunction(df, normalizedWhitelist);
            normalizeConstraintList(df.constraints.avoid_adjacent);
            normalizeConstraintList(df.constraints.prefer_adjacent);
        }
        if (copy.adjacency == null) copy.adjacency = new LinkedHashMap<>();
        return copy;
    }

    private static void normalizeConstraintList(List<String> list) {
        if (list == null) return;
        List<String> normalized = new ArrayList<>();
        for (String item : list) {
            String value = normalizeLower(item, null);
            if (value == null || normalized.contains(value)) continue;
            normalized.add(value);
        }
        list.clear();
        list.addAll(normalized);
    }

    private static FunctionWhitelist sanitizeWhitelist(FunctionWhitelist source, String cityId) {
        FunctionWhitelist out = source == null ? new FunctionWhitelist() : source;
        out.city_id = cityId != null && !cityId.isBlank() ? cityId : out.city_id;
        out.version = normalizeLower(out.version, FUNCTION_WHITELIST_VERSION);
        out.source = normalizeLower(out.source, "mcp_ai");
        if (out.rationale == null) out.rationale = "";
        if (out.generated_at_epoch_ms <= 0) out.generated_at_epoch_ms = System.currentTimeMillis();
        out.primary_functions = normalizeFunctionList(out.primary_functions);
        out.secondary_functions = normalizeFunctionList(out.secondary_functions);
        out.ok = !out.primary_functions.isEmpty() && !out.secondary_functions.isEmpty();
        return out;
    }

    private static List<String> normalizeFunctionList(List<String> input) {
        if (input == null || input.isEmpty()) return new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (String item : input) {
            String normalized = normalizeLower(item, null);
            if (normalized == null || normalized.isBlank()) continue;
            unique.add(normalized);
        }
        return new ArrayList<>(unique);
    }

    private static FunctionWhitelist loadWhitelistFromC4(C4Plan c4Plan) {
        FunctionWhitelist fallback = new FunctionWhitelist();
        if (c4Plan != null) {
            fallback.city_id = c4Plan.city_id;
            fallback.version = normalizeLower(c4Plan.function_whitelist_version, FUNCTION_WHITELIST_VERSION);
        }
        return fallback;
    }


    private static List<String> secondaryWhitelist(FunctionWhitelist whitelist) {
        if (whitelist == null || whitelist.secondary_functions == null || whitelist.secondary_functions.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(whitelist.secondary_functions);
    }
    private static String validateFunction(String function, String fallback, List<String> whitelist) {
        String normalized = normalizeLower(function, fallback);
        if (normalized == null) return fallback;
        if (whitelist == null || whitelist.isEmpty()) return normalized;
        return whitelist.contains(normalized) ? normalized : fallback;
    }

    private static void applyLayerFunctionMix(CityInstance city, List<DistrictFunction> districtFunctions, FunctionWhitelist whitelist) {
        if (districtFunctions == null || districtFunctions.isEmpty()) return;
        List<String> primaryWhitelist = whitelist != null ? whitelist.primary_functions : Collections.emptyList();
        if (primaryWhitelist.isEmpty()) return;

        double centerX = 0.0;
        double centerZ = 0.0;
        boolean hasConfigCenter = city != null && city.config != null;
        if (hasConfigCenter) {
            centerX = city.config.centerX;
            centerZ = city.config.centerZ;
        } else {
            for (DistrictFunction df : districtFunctions) {
                centerX += df.centroid.x;
                centerZ += df.centroid.z;
            }
            centerX /= districtFunctions.size();
            centerZ /= districtFunctions.size();
        }

        Map<String, List<DistrictFunction>> byLayer = new LinkedHashMap<>();
        for (DistrictFunction df : districtFunctions) {
            if (df == null) continue;
            byLayer.computeIfAbsent(normalizeLower(df.layer, "layer_0"), k -> new ArrayList<>()).add(df);
        }

        for (Map.Entry<String, List<DistrictFunction>> entry : byLayer.entrySet()) {
            assignFunctionsForLayer(entry.getKey(), entry.getValue(), centerX, centerZ, whitelist);
        }
    }

    private static void assignFunctionsForLayer(
            String layerName,
            List<DistrictFunction> layerDistricts,
            double centerX,
            double centerZ,
            FunctionWhitelist whitelist
    ) {
        if (layerDistricts == null || layerDistricts.size() <= 1) return;
        String zoneType = normalizeUpper(layerDistricts.get(0).zone_type, "URBAN");
        List<String> candidates = buildLayerPrimaryCandidates(zoneType, layerName, whitelist != null ? whitelist.primary_functions : Collections.emptyList());
        if (candidates.size() <= 1) return;

        int targetFunctionCount = targetLayerFunctionCount(zoneType, layerDistricts.size(), candidates.size());
        if (targetFunctionCount <= 1) return;

        List<String> selectedFunctions = selectLayerFunctions(layerDistricts, candidates, targetFunctionCount);
        if (selectedFunctions.size() <= 1) return;

        List<DistrictFunction> anchors = pickSpatialAnchors(layerDistricts, selectedFunctions.size());
        if (anchors.isEmpty()) return;

        for (DistrictFunction df : layerDistricts) {
            int bestIndex = 0;
            double bestScore = Double.MAX_VALUE;
            for (int i = 0; i < anchors.size(); i++) {
                DistrictFunction anchor = anchors.get(i);
                double dx = df.centroid.x - anchor.centroid.x;
                double dz = df.centroid.z - anchor.centroid.z;
                double centerBias = i == 0 ? 0.0 : 0.0001; // Keep tie-break deterministic.
                double score = dx * dx + dz * dz + centerBias;
                if (score < bestScore) {
                    bestScore = score;
                    bestIndex = i;
                }
            }
            String primary = selectedFunctions.get(Math.max(0, Math.min(selectedFunctions.size() - 1, bestIndex)));
            df.primary_function = primary;
            df.secondary_functions = inferSecondaryFunctions(primary, df.zone_type);
            df.constraints = inferConstraints(primary);
            boolean isWallLike = normalizeLower(layerName, "").contains("wall");
            df.notes = inferNotes(primary, isWallLike);
            sanitizeDistrictFunction(df, whitelist);
        }
    }

    private static List<String> buildLayerPrimaryCandidates(String zoneType, String layerName, List<String> whitelist) {
        List<String> out = new ArrayList<>();
        String zone = normalizeUpper(zoneType, "URBAN");
        boolean wallLike = normalizeLower(layerName, "").contains("wall");

        if ("CORE".equals(zone)) {
            addIfAllowed(out, whitelist, "civic_center");
            addIfAllowed(out, whitelist, "market");
            addIfAllowed(out, whitelist, "service");
            addIfAllowed(out, whitelist, "residential_mid");
            addIfAllowed(out, whitelist, "craft");
        } else if ("URBAN".equals(zone)) {
            addIfAllowed(out, whitelist, "residential_mid");
            addIfAllowed(out, whitelist, "market");
            addIfAllowed(out, whitelist, "service");
            addIfAllowed(out, whitelist, "craft");
            addIfAllowed(out, whitelist, "residential_low");
        } else if ("RING".equals(zone)) {
            if (wallLike) addIfAllowed(out, whitelist, "fortification");
            addIfAllowed(out, whitelist, "residential_low");
            addIfAllowed(out, whitelist, "market");
            addIfAllowed(out, whitelist, "craft");
        } else if ("BUFFER".equals(zone)) {
            addIfAllowed(out, whitelist, "green_buffer");
            addIfAllowed(out, whitelist, "ecology");
            addIfAllowed(out, whitelist, "residential_low");
            addIfAllowed(out, whitelist, "craft");
        }

        for (String f : whitelist) {
            if (f != null && !f.isBlank() && !out.contains(f)) out.add(f);
        }
        return out;
    }

    private static int targetLayerFunctionCount(String zoneType, int districtCount, int candidateCount) {
        if (districtCount <= 3 || candidateCount <= 1) return 1;
        String zone = normalizeUpper(zoneType, "URBAN");
        int target;
        if ("CORE".equals(zone)) {
            target = districtCount >= 10 ? 2 : 1;
        } else if ("BUFFER".equals(zone)) {
            target = districtCount >= 14 ? 2 : 1;
        } else if ("RING".equals(zone)) {
            target = districtCount >= 12 ? 3 : (districtCount >= 6 ? 2 : 1);
        } else {
            target = districtCount >= 16 ? 4 : (districtCount >= 9 ? 3 : 2);
        }
        return Math.max(1, Math.min(target, candidateCount));
    }

    private static List<String> selectLayerFunctions(
            List<DistrictFunction> layerDistricts,
            List<String> candidates,
            int targetCount
    ) {
        List<String> selected = new ArrayList<>();
        for (DistrictFunction df : layerDistricts) {
            String primary = normalizeLower(df.primary_function, null);
            if (primary == null || !candidates.contains(primary) || selected.contains(primary)) continue;
            selected.add(primary);
            if (selected.size() >= targetCount) return selected;
        }
        for (String candidate : candidates) {
            if (selected.contains(candidate)) continue;
            selected.add(candidate);
            if (selected.size() >= targetCount) break;
        }
        return selected;
    }

    private static List<DistrictFunction> pickSpatialAnchors(List<DistrictFunction> districts, int k) {
        List<DistrictFunction> anchors = new ArrayList<>();
        if (districts == null || districts.isEmpty() || k <= 0) return anchors;

        DistrictFunction first = districts.get(0);
        for (DistrictFunction df : districts) {
            if (df.centroid.x + df.centroid.z < first.centroid.x + first.centroid.z) first = df;
        }
        anchors.add(first);

        while (anchors.size() < k && anchors.size() < districts.size()) {
            DistrictFunction best = null;
            double bestMinDist = -1.0;
            for (DistrictFunction df : districts) {
                if (anchors.contains(df)) continue;
                double minDist = Double.MAX_VALUE;
                for (DistrictFunction anchor : anchors) {
                    double dx = df.centroid.x - anchor.centroid.x;
                    double dz = df.centroid.z - anchor.centroid.z;
                    double dist = dx * dx + dz * dz;
                    if (dist < minDist) minDist = dist;
                }
                if (minDist > bestMinDist) {
                    bestMinDist = minDist;
                    best = df;
                }
            }
            if (best == null) break;
            anchors.add(best);
        }
        return anchors;
    }

    private static void addIfAllowed(List<String> out, List<String> whitelist, String value) {
        if (value == null || value.isBlank()) return;
        if (whitelist != null && !whitelist.isEmpty() && !whitelist.contains(value)) return;
        if (!out.contains(value)) out.add(value);
    }

    private static String pickPrimaryFallback(
            String zoneType,
            String layer,
            List<String> whitelist,
            String inferredPrimary
    ) {
        String normalizedInferred = normalizeLower(inferredPrimary, null);
        if (whitelist == null || whitelist.isEmpty()) {
            return normalizedInferred != null ? normalizedInferred : "residential_mid";
        }

        String zone = normalizeUpper(zoneType, "URBAN");
        List<String> candidates = new ArrayList<>();
        if ("CORE".equals(zone)) {
            candidates.add("civic_center");
            candidates.add("market");
            candidates.add("residential_mid");
            candidates.add("fortification");
        } else if ("URBAN".equals(zone)) {
            candidates.add("residential_mid");
            candidates.add("market");
            candidates.add("craft");
            candidates.add("service");
            candidates.add("residential_low");
        } else if ("RING".equals(zone)) {
            candidates.add("fortification");
            candidates.add("residential_low");
            candidates.add("market");
            candidates.add("craft");
        } else if ("BUFFER".equals(zone)) {
            candidates.add("green_buffer");
            candidates.add("ecology");
            candidates.add("residential_low");
        }

        String normalizedLayer = normalizeLower(layer, "");
        if (normalizedLayer.contains("wall")) {
            candidates.add(0, "fortification");
        }
        if (normalizedInferred != null) {
            candidates.add(0, normalizedInferred);
        }
        for (String candidate : candidates) {
            if (whitelist.contains(candidate)) return candidate;
        }
        return whitelist.get(0);
    }

    private static String pickSecondaryFallback(String primaryFunction, String zoneType, List<String> secondaryWhitelist) {
        if (secondaryWhitelist == null || secondaryWhitelist.isEmpty()) return null;
        for (String candidate : inferSecondaryFunctions(primaryFunction, zoneType)) {
            String normalized = normalizeLower(candidate, null);
            if (normalized != null && secondaryWhitelist.contains(normalized)) return normalized;
        }
        return secondaryWhitelist.get(0);
    }

    private static List<GroupDraft> buildBaseDrafts(
            List<DistrictFunction> districtFunctions,
            Map<String, DistrictFunction> dfMap,
            Map<String, List<String>> adjacency,
            boolean crossLayerMerge
    ) {
        List<GroupDraft> drafts = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        int seq = 1;
        if (districtFunctions == null) return drafts;

        for (DistrictFunction seed : districtFunctions) {
            if (seed == null || seed.district_id == null || seed.district_id.isBlank()) continue;
            if (visited.contains(seed.district_id)) continue;
            GroupDraft draft = new GroupDraft();
            draft.id = seq++;
            draft.function = seed.primary_function;
            draft.layer = seed.layer;

            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(seed.district_id);
            visited.add(seed.district_id);
            while (!queue.isEmpty()) {
                String currentId = queue.poll();
                DistrictFunction current = dfMap.get(currentId);
                if (current == null) continue;
                draft.districtIds.add(currentId);
                for (String nei : adjacency.getOrDefault(currentId, Collections.emptyList())) {
                    if (visited.contains(nei)) continue;
                    DistrictFunction neiDf = dfMap.get(nei);
                    if (neiDf == null) continue;
                    if (!sameGroupType(current, neiDf, crossLayerMerge)) continue;
                    visited.add(nei);
                    queue.add(nei);
                }
            }
            if (!draft.districtIds.isEmpty()) drafts.add(draft);
        }
        return drafts;
    }

    private static void absorbTinyDistricts(
            List<GroupDraft> drafts,
            Map<String, DistrictFunction> dfMap,
            Map<String, List<String>> adjacency,
            List<MergeLogEntry> mergeLog,
            MergePolicy policy
    ) {
        if (drafts == null || drafts.isEmpty()) return;
        int minAreaBlocks = Math.max(0, policy.min_district_area_chunks) * 256;
        if (minAreaBlocks <= 0) return;

        Map<String, GroupDraft> districtToGroup = indexDistrictToGroup(drafts);
        List<DistrictFunction> tinyDistricts = new ArrayList<>();
        for (DistrictFunction df : dfMap.values()) {
            if (df == null || df.area_blocks >= minAreaBlocks) continue;
            tinyDistricts.add(df);
        }

        tinyDistricts.sort(Comparator.comparingInt(d -> d.area_blocks));
        for (DistrictFunction tiny : tinyDistricts) {
            GroupDraft source = districtToGroup.get(tiny.district_id);
            if (source == null) continue;

            GroupDraft best = null;
            int bestNeighborTouch = -1;
            int bestArea = -1;
            for (String neiId : adjacency.getOrDefault(tiny.district_id, Collections.emptyList())) {
                GroupDraft candidate = districtToGroup.get(neiId);
                if (candidate == null || candidate == source) continue;
                if (!policy.allow_cross_function_absorb_for_tiny
                        && !safeEq(candidate.function, tiny.primary_function)) {
                    continue;
                }
                int area = groupArea(candidate, dfMap);
                int touch = countGroupAdjacencyTouches(candidate, tiny.district_id, adjacency);
                boolean better = touch > bestNeighborTouch || (touch == bestNeighborTouch && area > bestArea);
                if (!better) continue;
                best = candidate;
                bestNeighborTouch = touch;
                bestArea = area;
            }
            if (best == null) continue;
            source.districtIds.remove(tiny.district_id);
            best.districtIds.add(tiny.district_id);
            districtToGroup.put(tiny.district_id, best);

            MergeLogEntry log = new MergeLogEntry();
            log.type = "tiny_absorb";
            log.district_id = tiny.district_id;
            log.from_function = tiny.primary_function;
            log.to_group_id = "draft_" + best.id;
            log.reason = "area_below_min_threshold";
            mergeLog.add(log);
        }
    }

    private static int groupArea(GroupDraft draft, Map<String, DistrictFunction> dfMap) {
        int sum = 0;
        for (String districtId : draft.districtIds) {
            DistrictFunction df = dfMap.get(districtId);
            if (df != null) sum += Math.max(0, df.area_blocks);
        }
        return sum;
    }

    private static int countGroupAdjacencyTouches(GroupDraft group, String districtId, Map<String, List<String>> adjacency) {
        int count = 0;
        for (String nei : adjacency.getOrDefault(districtId, Collections.emptyList())) {
            if (group.districtIds.contains(nei)) count++;
        }
        return count;
    }

    private static Map<String, GroupDraft> indexDistrictToGroup(List<GroupDraft> drafts) {
        Map<String, GroupDraft> index = new HashMap<>();
        for (GroupDraft draft : drafts) {
            if (draft == null || draft.districtIds == null) continue;
            for (String districtId : draft.districtIds) {
                index.put(districtId, draft);
            }
        }
        return index;
    }

    private static List<GroupDraft> cleanupAndRenumber(List<GroupDraft> drafts) {
        List<GroupDraft> cleaned = new ArrayList<>();
        int seq = 1;
        for (GroupDraft draft : drafts) {
            if (draft == null || draft.districtIds == null || draft.districtIds.isEmpty()) continue;
            draft.id = seq++;
            cleaned.add(draft);
        }
        return cleaned;
    }

    private static List<GroupDraft> splitDisconnectedDrafts(
            List<GroupDraft> drafts,
            Map<String, DistrictFunction> dfMap,
            Map<String, List<String>> adjacency,
            List<MergeLogEntry> mergeLog
    ) {
        List<GroupDraft> out = new ArrayList<>();
        int seq = 1;
        for (GroupDraft draft : drafts) {
            if (draft == null || draft.districtIds == null || draft.districtIds.isEmpty()) continue;
            List<Set<String>> components = connectedComponents(draft.districtIds, adjacency);
            if (components.size() <= 1) {
                draft.id = seq++;
                out.add(draft);
                continue;
            }
            for (Set<String> component : components) {
                GroupDraft split = new GroupDraft();
                split.id = seq++;
                split.function = draft.function;
                split.layer = draft.layer;
                split.districtIds.addAll(component);
                out.add(split);
            }
            MergeLogEntry log = new MergeLogEntry();
            log.type = "split_disconnected";
            log.district_id = null;
            log.from_function = draft.function;
            log.to_group_id = "split_components_" + components.size();
            log.reason = "component_count_gt_1";
            mergeLog.add(log);
        }
        return out;
    }

    private static List<Set<String>> connectedComponents(Set<String> nodes, Map<String, List<String>> adjacency) {
        List<Set<String>> components = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String node : nodes) {
            if (visited.contains(node)) continue;
            Set<String> component = new HashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(node);
            visited.add(node);
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                component.add(cur);
                for (String nei : adjacency.getOrDefault(cur, Collections.emptyList())) {
                    if (!nodes.contains(nei) || visited.contains(nei)) continue;
                    visited.add(nei);
                    queue.add(nei);
                }
            }
            if (!component.isEmpty()) components.add(component);
        }
        return components;
    }

    private static int countDisconnectedGroups(List<ModuleGroup> groups, Map<String, List<String>> adjacency) {
        int count = 0;
        for (ModuleGroup group : groups) {
            if (group == null || group.district_ids == null || group.district_ids.isEmpty()) continue;
            Set<String> nodes = new HashSet<>(group.district_ids);
            if (connectedComponents(nodes, adjacency).size() > 1) count++;
        }
        return count;
    }

    private static int countLowCompactness(List<ModuleGroup> groups, double minCompactness) {
        int count = 0;
        for (ModuleGroup group : groups) {
            if (group == null || group.connectivity == null) continue;
            if (group.connectivity.compactness < minCompactness) count++;
        }
        return count;
    }

    private static boolean sameGroupType(DistrictFunction a, DistrictFunction b, boolean crossLayerMerge) {
        if (a == null || b == null) return false;
        if (!safeEq(a.primary_function, b.primary_function)) return false;
        return crossLayerMerge || safeEq(a.layer, b.layer);
    }

    private static ModuleGroup toModuleGroup(List<DistrictFunction> component, int seq) {
        ModuleGroup group = new ModuleGroup();
        DistrictFunction first = component.get(0);
        group.function = first.primary_function;
        group.layer = first.layer;
        group.group_id = "g_" + normalizeLower(first.primary_function, "misc") + "_" + String.format(Locale.ROOT, "%02d", seq);

        double sumX = 0.0;
        double sumZ = 0.0;
        int sumArea = 0;
        for (DistrictFunction df : component) {
            group.district_ids.add(df.district_id);
            group.district_numeric_ids.add(df.district_numeric_id);
            sumArea += Math.max(0, df.area_blocks);
            double w = Math.max(1, df.area_blocks);
            sumX += df.centroid.x * w;
            sumZ += df.centroid.z * w;
        }
        group.area_blocks = sumArea;
        double denom = Math.max(1.0, component.stream().mapToDouble(d -> Math.max(1, d.area_blocks)).sum());
        group.centroid.x = sumX / denom;
        group.centroid.z = sumZ / denom;
        group.connectivity.component_count = 1;
        group.connectivity.compactness = clamp01(1.0 / Math.sqrt(Math.max(1, component.size())));
        return group;
    }

    private static Map<Integer, Set<Integer>> buildDistrictAdjacency(CityInstance city) {
        Map<Integer, Set<Integer>> adjacency = new HashMap<>();
        if (city == null || city.districts == null) return adjacency;

        Map<Long, Integer> owner = new HashMap<>();
        for (District d : city.districts) {
            adjacency.putIfAbsent(d.id, new HashSet<>());
            if (d.memberChunks == null) continue;
            for (long chunk : d.memberChunks) {
                owner.put(chunk, d.id);
            }
        }

        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (District d : city.districts) {
            if (d.memberChunks == null) continue;
            for (long chunk : d.memberChunks) {
                int cx = net.minecraft.world.level.ChunkPos.getX(chunk);
                int cz = net.minecraft.world.level.ChunkPos.getZ(chunk);
                for (int[] dir : dirs) {
                    long nei = net.minecraft.world.level.ChunkPos.asLong(cx + dir[0], cz + dir[1]);
                    Integer nid = owner.get(nei);
                    if (nid == null || nid == d.id) continue;
                    adjacency.get(d.id).add(nid);
                }
            }
        }
        return adjacency;
    }

    private static District findDistrictById(List<District> list, int id) {
        for (District d : list) if (d.id == id) return d;
        return null;
    }

    private static String inferPrimaryFunction(String zoneType, boolean isWallLayer, String density) {
        String zone = normalizeUpper(zoneType, "URBAN");
        if ("CORE".equals(zone)) return "civic_center";
        if ("RING".equals(zone)) return isWallLayer ? "fortification" : "outer_residential";
        if ("BUFFER".equals(zone)) return "green_buffer";
        String d = normalizeLower(density, "");
        if ("high".equals(d)) return "market";
        if ("low".equals(d)) return "residential_low";
        return "residential_mid";
    }

    private static List<String> inferSecondaryFunctions(String primary, String zoneType) {
        List<String> list = new ArrayList<>();
        if ("civic_center".equals(primary)) list.add("market");
        else if ("market".equals(primary)) list.add("residential_mid");
        else if ("fortification".equals(primary)) list.add("military");
        else if ("green_buffer".equals(primary)) list.add("ecology");
        else if ("outer_residential".equals(primary)) list.add("craft");
        if ("URBAN".equalsIgnoreCase(zoneType) && !list.contains("service")) list.add("service");
        return list;
    }

    private static Constraints inferConstraints(String primary) {
        Constraints c = new Constraints();
        if ("civic_center".equals(primary)) {
            c.avoid_adjacent.add("heavy_industry");
            c.prefer_adjacent.add("market");
        } else if ("fortification".equals(primary)) {
            c.avoid_adjacent.add("cemetery");
            c.prefer_adjacent.add("military");
        } else if ("green_buffer".equals(primary)) {
            c.avoid_adjacent.add("heavy_industry");
            c.prefer_adjacent.add("residential_low");
        } else if ("market".equals(primary)) {
            c.avoid_adjacent.add("cemetery");
            c.prefer_adjacent.add("residential_mid");
        }
        return c;
    }

    private static String inferNotes(String primary, boolean isWallLayer) {
        if ("fortification".equals(primary) || isWallLayer) return "环带防御与城墙相关区";
        if ("civic_center".equals(primary)) return "核心行政与公共服务区";
        if ("green_buffer".equals(primary)) return "生态缓冲区";
        return "常规功能分区";
    }

    private static double inferPriority(String zoneType, String density, int chunkCount) {
        double p = 0.65;
        String zone = normalizeUpper(zoneType, "");
        if ("CORE".equals(zone)) p += 0.2;
        else if ("URBAN".equals(zone)) p += 0.08;
        else if ("RING".equals(zone)) p += 0.03;
        String d = normalizeLower(density, "");
        if ("high".equals(d)) p += 0.04;
        if (chunkCount >= 16) p += 0.03;
        return clamp01(p);
    }

    private static String layerName(CityConfig.LayerLayout layout, int index) {
        if (layout == null || layout.layers == null || layout.layers.isEmpty()) return "layer_" + index;
        int idx = Math.max(0, Math.min(layout.layers.size() - 1, index));
        CityConfig.LayerConfig layer = layout.layers.get(idx);
        String name = layer != null ? layer.name : null;
        if (name == null || name.isBlank()) return "layer_" + idx;
        return name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static boolean isWallLayer(CityConfig.LayerLayout layout, int index) {
        if (layout == null || layout.layers == null || layout.layers.isEmpty()) return false;
        int idx = Math.max(0, Math.min(layout.layers.size() - 1, index));
        CityConfig.LayerConfig layer = layout.layers.get(idx);
        return layer != null && (layer.isWall || layer.wallLayer || layer.wall != null);
    }

    private static String formatDistrictId(int districtId, int layerIndex) {
        return "d_" + String.format(Locale.ROOT, "%02d", districtId) + "_l" + Math.max(0, layerIndex);
    }

    private static String normalizeUpper(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeLower(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean safeEq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static double clamp01(double value) {
        if (value < 0) return 0;
        return Math.min(1, value);
    }
}


