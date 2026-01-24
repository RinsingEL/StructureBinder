package com.user.terra_script.world.city;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CityStage2Processor {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int DEFAULT_STEP = 2;

    public static class Stage2Result {
        public String cityId;
        public long createdAtEpochMs;
        public double globalAvgHeight;
        public double globalAvgRoughness;
        public double globalMinHeight;
        public double globalMaxHeight;
        public List<DistrictIntent> intents = new ArrayList<>();
    }

    public static class DistrictIntent {
        public int districtId;
        public String zoneType;
        public int layerIndex;
        public String density;
        public int buildableArea;
        public double avgHeight;
        public double avgRoughness;
        public double buildablePercent;
        public double forbiddenPercent;
        public String heightLevel;
        public int targetHeight;
        public String transition;
    }

    public static Stage2Result compute(CityStage1Processor.Stage1Result stage1) {
        Stage2Result result = new Stage2Result();
        if (stage1 == null) return result;
        result.cityId = stage1.cityId;
        result.createdAtEpochMs = System.currentTimeMillis();

        List<CityStage1Processor.BuildableStats> buildableStats = stage1.buildableStats != null
                ? stage1.buildableStats
                : new ArrayList<>();
        HeightSummary summary = summarize(buildableStats);
        result.globalAvgHeight = summary.avgHeight;
        result.globalAvgRoughness = summary.avgRoughness;
        result.globalMinHeight = summary.minHeight;
        result.globalMaxHeight = summary.maxHeight;

        int step = (int) Math.max(DEFAULT_STEP, Math.round((summary.maxHeight - summary.minHeight) / 6.0));
        Map<Integer, String> zones = stage1.districtZones;
        Map<Integer, Integer> layers = stage1.districtLayers;
        Map<Integer, String> densities = stage1.districtDensities;

        for (CityStage1Processor.BuildableStats stats : buildableStats) {
            DistrictIntent intent = new DistrictIntent();
            intent.districtId = stats.districtId;
            intent.zoneType = zones != null ? zones.get(stats.districtId) : null;
            intent.layerIndex = layers != null && layers.containsKey(stats.districtId)
                    ? layers.get(stats.districtId)
                    : 0;
            intent.density = densities != null ? densities.get(stats.districtId) : null;
            intent.buildableArea = stats.buildableArea;
            intent.avgHeight = stats.area > 0 ? stats.avgHeight : summary.avgHeight;
            intent.avgRoughness = stats.area > 0 ? stats.avgRoughness : summary.avgRoughness;
            intent.buildablePercent = stats.buildablePercent;
            intent.forbiddenPercent = stats.forbiddenPercent;

            String level = baseLevelFromDensity(intent.density, intent.zoneType);
            level = adjustLevel(level, intent.buildablePercent, intent.avgRoughness);
            intent.heightLevel = level;

            int offset = levelOffset(level, step);
            intent.targetHeight = (int) Math.round(intent.avgHeight + offset);
            intent.transition = chooseTransition(intent.avgRoughness, intent.buildablePercent);

            result.intents.add(intent);
        }

        return result;
    }

    public static File save(Stage2Result result) throws Exception {
        File file = FMLPaths.GAMEDIR.get()
                .resolve("terra_script_city_stage2_" + result.cityId + ".json")
                .toFile();
        Files.writeString(file.toPath(), GSON.toJson(result));
        return file;
    }

    public static Stage2Result load(String cityId) {
        try {
            File file = FMLPaths.GAMEDIR.get().resolve("terra_script_city_stage2_" + cityId + ".json").toFile();
            if (!file.exists()) return null;
            return GSON.fromJson(Files.readString(file.toPath()), Stage2Result.class);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static HeightSummary summarize(List<CityStage1Processor.BuildableStats> stats) {
        HeightSummary summary = new HeightSummary();
        if (stats == null || stats.isEmpty()) {
            summary.avgHeight = 0.0;
            summary.avgRoughness = 0.0;
            summary.minHeight = 0.0;
            summary.maxHeight = 0.0;
            return summary;
        }

        double sumH = 0.0;
        double sumR = 0.0;
        long area = 0;
        double minH = Double.MAX_VALUE;
        double maxH = -Double.MAX_VALUE;

        for (CityStage1Processor.BuildableStats s : stats) {
            if (s.area <= 0) continue;
            sumH += s.avgHeight * s.area;
            sumR += s.avgRoughness * s.area;
            area += s.area;
            minH = Math.min(minH, s.avgHeight);
            maxH = Math.max(maxH, s.avgHeight);
        }

        if (area > 0) {
            summary.avgHeight = sumH / area;
            summary.avgRoughness = sumR / area;
            summary.minHeight = minH;
            summary.maxHeight = maxH;
        } else {
            summary.avgHeight = 0.0;
            summary.avgRoughness = 0.0;
            summary.minHeight = 0.0;
            summary.maxHeight = 0.0;
        }

        return summary;
    }

    private static String baseLevelFromZone(String zoneType) {
        if ("CORE".equalsIgnoreCase(zoneType)) return "HIGH";
        if ("URBAN".equalsIgnoreCase(zoneType)) return "MID";
        if ("RING".equalsIgnoreCase(zoneType)) return "LOW";
        if ("BUFFER".equalsIgnoreCase(zoneType)) return "LOW";
        return "MID";
    }

    private static String baseLevelFromDensity(String density, String zoneType) {
        if (density != null) {
            String normalized = density.trim().toLowerCase();
            if ("high".equals(normalized)) return "HIGH";
            if ("mid".equals(normalized)) return "MID";
            if ("low".equals(normalized)) return "LOW";
            if ("1".equals(normalized)) return "MID";
        }
        return baseLevelFromZone(zoneType);
    }

    private static String adjustLevel(String level, double buildablePercent, double roughness) {
        int idx = levelIndex(level);
        if (buildablePercent < 0.35 || roughness > 2.5) idx--;
        if (buildablePercent > 0.85 && roughness < 1.2) idx++;
        return levelFromIndex(idx);
    }

    private static int levelOffset(String level, int step) {
        if ("HIGH".equals(level)) return step;
        if ("LOW".equals(level)) return -step;
        return 0;
    }

    private static String chooseTransition(double roughness, double buildablePercent) {
        if (buildablePercent < 0.25) return "CLAMP";
        if (roughness >= 2.5) return "TERRACE";
        return "RAMP";
    }

    private static int levelIndex(String level) {
        if ("LOW".equals(level)) return 0;
        if ("MID".equals(level)) return 1;
        if ("HIGH".equals(level)) return 2;
        return 1;
    }

    private static String levelFromIndex(int idx) {
        if (idx <= 0) return "LOW";
        if (idx >= 2) return "HIGH";
        return "MID";
    }

    private static class HeightSummary {
        double avgHeight;
        double avgRoughness;
        double minHeight;
        double maxHeight;
    }
}
