package com.user.terra_script.world.city.stage.c8;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import com.user.terra_script.world.city.stage.c6.CityC6Stages;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CityC8Stages {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final String C8_PLAN_FILE = "C8_FoundationPlan.json";

    private CityC8Stages() {}

    public static class C8Plan {
        public String step = "C8";
        public boolean ok = true;
        public String city_id;
        public int version = 1;
        public long generated_at_epoch_ms;
        public List<FoundationItem> foundations = new ArrayList<>();
    }

    public static class FoundationItem {
        public String plot_id;
        public String build_area_id;
        public int build_area_numeric_id;
        public String group_id;
        public String foundation_type;
        public String strategy;
        public int base_y;
        public int delta_height;
        public TerrainImpactBBox terrain_impact_bbox = new TerrainImpactBBox();
        public List<SupportAction> supports = new ArrayList<>();
        public TerrainMetrics terrain_metrics = new TerrainMetrics();
    }

    public static class TerrainImpactBBox {
        public int minX;
        public int minZ;
        public int maxX;
        public int maxZ;
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

        List<CityC6Stages.BuildAreaSummary> areas = c6Summary.areas != null ? c6Summary.areas : Collections.emptyList();
        areas.sort(Comparator.comparing(a -> a.build_area_id));
        for (CityC6Stages.BuildAreaSummary area : areas) {
            if (area == null) continue;
            List<Long> blockKeys = blocksByArea.getOrDefault(area.build_area_numeric_id, Collections.emptyList());
            FoundationItem item = buildFoundationItem(area, blockKeys, heightData);
            plan.foundations.add(item);
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

    private static FoundationItem buildFoundationItem(
            CityC6Stages.BuildAreaSummary area,
            List<Long> blockKeys,
            CityStage1BinaryIO.HeightData heightData
    ) {
        FoundationItem item = new FoundationItem();
        item.plot_id = area.build_area_id;
        item.build_area_id = area.build_area_id;
        item.build_area_numeric_id = area.build_area_numeric_id;
        item.group_id = area.group_id;

        List<Integer> heights = new ArrayList<>(Math.max(16, blockKeys.size()));
        long sum = 0L;
        int minH = Integer.MAX_VALUE;
        int maxH = Integer.MIN_VALUE;
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        long sumX = 0L;
        long sumZ = 0L;

        int northSum = 0, eastSum = 0, southSum = 0, westSum = 0;
        int northCnt = 0, eastCnt = 0, southCnt = 0, westCnt = 0;

        for (Long key : blockKeys) {
            if (key == null) continue;
            int x = unpackX(key);
            int z = unpackZ(key);
            int h = heightAt(heightData, x, z);
            heights.add(h);
            sum += h;
            minH = Math.min(minH, h);
            maxH = Math.max(maxH, h);
            minX = Math.min(minX, x);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxZ = Math.max(maxZ, z);
            sumX += x;
            sumZ += z;
        }

        if (heights.isEmpty()) {
            minH = safeRound(area.avg_height);
            maxH = minH;
            minX = area.bbox.minX;
            minZ = area.bbox.minZ;
            maxX = area.bbox.maxX;
            maxZ = area.bbox.maxZ;
            heights.add(minH);
            sum = minH;
            sumX = safeRound(area.centroid.x);
            sumZ = safeRound(area.centroid.z);
        }

        double avgH = sum / (double) heights.size();
        Collections.sort(heights);
        double p50 = heights.get(Math.max(0, heights.size() / 2));
        int relief = Math.max(0, maxH - minH);

        for (Long key : blockKeys) {
            if (key == null) continue;
            int x = unpackX(key);
            int z = unpackZ(key);
            int h = heightAt(heightData, x, z);
            if (z == minZ) {
                northSum += h;
                northCnt++;
            }
            if (x == maxX) {
                eastSum += h;
                eastCnt++;
            }
            if (z == maxZ) {
                southSum += h;
                southCnt++;
            }
            if (x == minX) {
                westSum += h;
                westCnt++;
            }
        }

        int edgeN = northCnt > 0 ? safeRound(northSum / (double) northCnt) : safeRound(avgH);
        int edgeE = eastCnt > 0 ? safeRound(eastSum / (double) eastCnt) : safeRound(avgH);
        int edgeS = southCnt > 0 ? safeRound(southSum / (double) southCnt) : safeRound(avgH);
        int edgeW = westCnt > 0 ? safeRound(westSum / (double) westCnt) : safeRound(avgH);

        double slopeAvg = estimateSlopeAvg(blockKeys, heightData);
        boolean strongEdgeDelta = Math.abs(edgeN - edgeS) >= 6 || Math.abs(edgeE - edgeW) >= 6;

        item.foundation_type = "NONE";
        item.strategy = "FOLLOW_AVG";
        if (relief <= 1 && slopeAvg < 0.4) {
            item.foundation_type = "NONE";
            item.strategy = "FOLLOW_AVG";
        } else if (relief >= 9) {
            item.foundation_type = "TERRACE";
            item.strategy = "FOLLOW_P50";
        } else if (strongEdgeDelta && relief >= 4) {
            item.foundation_type = "PLATFORM_WITH_RETAINING_WALL";
            item.strategy = "CUT_AND_FILL";
        } else {
            item.foundation_type = "PLATFORM";
            item.strategy = "CUT_AND_FILL";
        }

        item.base_y = "FOLLOW_P50".equals(item.strategy) ? safeRound(p50) : safeRound(avgH);
        item.delta_height = relief;

        item.terrain_impact_bbox.minX = minX - 1;
        item.terrain_impact_bbox.minZ = minZ - 1;
        item.terrain_impact_bbox.maxX = maxX + 1;
        item.terrain_impact_bbox.maxZ = maxZ + 1;

        if ("PLATFORM_WITH_RETAINING_WALL".equals(item.foundation_type)) {
            addSupport(item, "retaining_wall", maxEdgeSide(edgeN, edgeE, edgeS, edgeW));
            addSupport(item, "stairs", minEdgeSide(edgeN, edgeE, edgeS, edgeW));
        } else if ("TERRACE".equals(item.foundation_type)) {
            addSupport(item, "stairs", "E");
        }

        item.terrain_metrics.height_min = minH;
        item.terrain_metrics.height_max = maxH;
        item.terrain_metrics.height_avg = round3(avgH);
        item.terrain_metrics.height_p50 = round3(p50);
        item.terrain_metrics.slope_avg = round3(slopeAvg);
        item.terrain_metrics.edge_n = edgeN;
        item.terrain_metrics.edge_e = edgeE;
        item.terrain_metrics.edge_s = edgeS;
        item.terrain_metrics.edge_w = edgeW;
        return item;
    }

    private static void addSupport(FoundationItem item, String type, String side) {
        SupportAction action = new SupportAction();
        action.type = type;
        action.side = side;
        item.supports.add(action);
    }

    private static double estimateSlopeAvg(List<Long> blockKeys, CityStage1BinaryIO.HeightData heightData) {
        if (blockKeys == null || blockKeys.isEmpty()) return 0.0;
        Map<Long, Integer> h = new HashMap<>();
        for (Long key : blockKeys) {
            if (key == null) continue;
            int x = unpackX(key);
            int z = unpackZ(key);
            h.put(key, heightAt(heightData, x, z));
        }
        int[][] dirs = new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        double sum = 0.0;
        int cnt = 0;
        for (Long key : h.keySet()) {
            int x = unpackX(key);
            int z = unpackZ(key);
            int base = h.getOrDefault(key, 0);
            for (int[] d : dirs) {
                long nk = packBlock(x + d[0], z + d[1]);
                Integer nh = h.get(nk);
                if (nh == null) continue;
                sum += Math.abs(base - nh);
                cnt++;
            }
        }
        if (cnt <= 0) return 0.0;
        return sum / cnt;
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

    private static int heightAt(CityStage1BinaryIO.HeightData data, int worldX, int worldZ) {
        int ix = worldX - data.originX;
        int iz = worldZ - data.originZ;
        if (ix < 0 || iz < 0 || ix >= data.width || iz >= data.height) return 0;
        return data.heightMap[ix][iz];
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

    private static int safeRound(double v) {
        return (int) Math.round(v);
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
