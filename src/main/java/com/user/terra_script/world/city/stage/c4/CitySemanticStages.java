package com.user.terra_script.world.city.stage.c4;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CitySemanticStages {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String C4_FILE = "C4_FunctionPlan.json";
    public static final String C4_VALIDATED_FILE = "C4_FunctionPlan.validated.json";
    public static final String C5_FILE = "C5_ModuleGroups.json";

    private CitySemanticStages() {}

    public static class C4Plan {
        public String step = "C4";
        public boolean ok = true;
        public String city_id;
        public int version = 1;
        public long generated_at_epoch_ms;
        public List<DistrictFunction> district_functions = new ArrayList<>();
        public GlobalPolicies global_policies = new GlobalPolicies();
        public Map<String, List<String>> adjacency = new LinkedHashMap<>();
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

    public static class GlobalPolicies {
        public int min_function_diversity = 4;
        public double max_same_function_ratio = 0.45;
    }

    public static class C5Groups {
        public String step = "C5";
        public boolean ok = true;
        public String city_id;
        public long generated_at_epoch_ms;
        public List<ModuleGroup> groups = new ArrayList<>();
        public MergeStats merge_stats = new MergeStats();
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

    public static class Point {
        public double x;
        public double z;
    }

    public static C4Plan generateC4(CityInstance city) {
        C4Plan plan = new C4Plan();
        if (city == null) {
            plan.ok = false;
            return plan;
        }
        plan.city_id = city.id;
        plan.generated_at_epoch_ms = System.currentTimeMillis();

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
            df.primary_function = inferPrimaryFunction(df.zone_type, isWall, district.density);
            df.secondary_functions = inferSecondaryFunctions(df.primary_function, df.zone_type);
            df.priority = inferPriority(df.zone_type, district.density, df.chunk_count);
            df.constraints = inferConstraints(df.primary_function);
            df.notes = inferNotes(df.primary_function, isWall);

            plan.district_functions.add(df);
        }

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
        C5Groups groups = new C5Groups();
        if (city == null || c4Plan == null) {
            groups.ok = false;
            return groups;
        }
        groups.city_id = city.id;
        groups.generated_at_epoch_ms = System.currentTimeMillis();

        Map<String, DistrictFunction> dfMap = new LinkedHashMap<>();
        for (DistrictFunction df : c4Plan.district_functions) {
            dfMap.put(df.district_id, df);
        }
        Map<String, List<String>> adjacency = c4Plan.adjacency != null ? c4Plan.adjacency : Collections.emptyMap();

        Set<String> visited = new HashSet<>();
        int groupSeq = 1;
        for (DistrictFunction seed : c4Plan.district_functions) {
            if (visited.contains(seed.district_id)) continue;
            List<DistrictFunction> component = new ArrayList<>();
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(seed.district_id);
            visited.add(seed.district_id);

            while (!queue.isEmpty()) {
                String cur = queue.poll();
                DistrictFunction curDf = dfMap.get(cur);
                if (curDf == null) continue;
                component.add(curDf);
                for (String nei : adjacency.getOrDefault(cur, Collections.emptyList())) {
                    if (visited.contains(nei)) continue;
                    DistrictFunction neiDf = dfMap.get(nei);
                    if (neiDf == null) continue;
                    if (!sameGroupType(curDf, neiDf, crossLayerMerge)) continue;
                    visited.add(nei);
                    queue.add(nei);
                }
            }

            if (component.isEmpty()) continue;
            ModuleGroup group = toModuleGroup(component, groupSeq++);
            groups.groups.add(group);
        }

        groups.groups.sort(Comparator.comparing(g -> g.group_id));
        groups.merge_stats.input_district_count = c4Plan.district_functions.size();
        groups.merge_stats.output_group_count = groups.groups.size();
        int in = Math.max(1, groups.merge_stats.input_district_count);
        groups.merge_stats.fragment_reduction_ratio = clamp01(1.0 - (groups.merge_stats.output_group_count / (double) in));
        return groups;
    }

    public static void saveC4(Path cityDir, C4Plan plan) throws Exception {
        Files.createDirectories(cityDir);
        String json = GSON.toJson(plan);
        Files.writeString(cityDir.resolve(C4_FILE), json, StandardCharsets.UTF_8);
        Files.writeString(cityDir.resolve(C4_VALIDATED_FILE), json, StandardCharsets.UTF_8);
    }

    public static C4Plan loadC4(Path cityDir) throws Exception {
        Path file = cityDir.resolve(C4_FILE);
        if (!Files.exists(file)) return null;
        return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), C4Plan.class);
    }

    public static void saveC5(Path cityDir, C5Groups groups) throws Exception {
        Files.createDirectories(cityDir);
        Files.writeString(cityDir.resolve(C5_FILE), GSON.toJson(groups), StandardCharsets.UTF_8);
    }

    public static C5Groups loadC5(Path cityDir) throws Exception {
        Path file = cityDir.resolve(C5_FILE);
        if (!Files.exists(file)) return null;
        return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), C5Groups.class);
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
