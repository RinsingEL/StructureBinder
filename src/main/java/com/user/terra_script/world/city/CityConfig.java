package com.user.terra_script.world.city;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CityConfig {
    // 基础归属
    public String territoryId;
    public int continentId;

    // 选址
    public int centerX; // Block X
    public int centerZ; // Block Z

    // 是否允许中心点落在显著水域（默认 false）
    @SerializedName(value = "allow_water_city", alternate = {"allowWaterCity"})
    public boolean allowWaterCity = false;

    // 规模 (单位: Chunk)
    public int targetChunkCount;

    // 扩张偏好
    public enum ExpansionBias {
        @SerializedName("balanced") BALANCED,
        @SerializedName("north") NORTH,
        @SerializedName("south") SOUTH,
        @SerializedName("east") EAST,
        @SerializedName("west") WEST,
        @SerializedName("coastal") COASTAL, // 倾向于水边
        @SerializedName("inland") INLAND    // 倾向于远离水
    }
    public ExpansionBias bias = ExpansionBias.BALANCED;

    // 生态策略
    public enum EcologyPolicy {
        @SerializedName("preserve") PRESERVE, // 保留树木，建筑避让
        @SerializedName("adaptive") ADAPTIVE, // 部分清除
        @SerializedName("clear") CLEAR        // 强力推平
    }
    public EcologyPolicy ecology = EcologyPolicy.ADAPTIVE;

    // 密度与风格
    public String density = "medium"; // low, medium, high

    // 层级配置
    @SerializedName(value = "layer_count", alternate = {"层级数量"})
    public int layerCount = 3;

    @SerializedName(value = "layer_thresholds", alternate = {"层级阈值"})
    public List<Double> layerThresholds = new ArrayList<>();

    @SerializedName(value = "layers", alternate = {"层配置"})
    public List<LayerConfig> layers = new ArrayList<>();

    // 运行时生成的唯一ID (例如 "city_102_55")
    public transient String cityInstanceId;

    public LayerLayout resolveLayerLayout() {
        List<LayerConfig> resolved = new ArrayList<>();
        if (layers != null) {
            for (LayerConfig layer : layers) {
                if (layer != null) resolved.add(layer);
            }
        }

        if (resolved.isEmpty()) {
            int count = clamp(layerCount, 3, 10);
            resolved.add(defaultLayer("Core", "CORE", "high"));
            for (int i = 0; i < count - 2; i++) {
                resolved.add(defaultLayer("Urban " + (i + 1), "URBAN", "mid"));
            }
            resolved.add(defaultLayer("Buffer", "BUFFER", "low"));
        } else {
            if (resolved.size() < 3) {
                int needed = 3 - resolved.size();
                for (int i = 0; i < needed; i++) {
                    resolved.add(defaultLayer("Urban " + (i + 1), "URBAN", "mid"));
                }
            }
            if (resolved.size() > 10) {
                resolved = new ArrayList<>(resolved.subList(0, 10));
            }
        }

        int lastIndex = resolved.size() - 1;
        for (int i = 0; i < resolved.size(); i++) {
            LayerConfig layer = resolved.get(i);
            layer.type = normalizeLayerType(layer.type);
            if (layer.type == null) {
                layer.type = i == 0 ? "CORE" : (i == lastIndex ? "BUFFER" : "URBAN");
            }

            layer.density = normalizeDensity(layer.density);
            if (layer.density == null) {
                layer.density = defaultDensityForType(layer.type);
            }

            if (layer.ecology == null) {
                layer.ecology = ecology != null ? ecology : EcologyPolicy.ADAPTIVE;
            }

            if (layer.name == null || layer.name.isBlank()) {
                layer.name = defaultNameForType(layer.type, i + 1);
            }

            if (layer.weight <= 0) {
                layer.weight = 1;
            }

            // Keep old/new wall flags compatible.
            layer.wallLayer = layer.wallLayer || layer.isWall || layer.wall != null;
            layer.isWall = layer.wallLayer;
        }

        resolved.get(0).type = "CORE";
        resolved.get(lastIndex).type = "BUFFER";

        layerCount = resolved.size();
        double[] thresholds = resolveThresholds(layerThresholds, resolved.size());
        return new LayerLayout(resolved, thresholds);
    }

    private static double[] resolveThresholds(List<Double> input, int layerCount) {
        int count = Math.max(0, layerCount - 1);
        double[] thresholds = new double[count];
        if (input != null && input.size() >= count && count > 0) {
            double prev = 0.0;
            for (int i = 0; i < count; i++) {
                double value = input.get(i) != null ? input.get(i) : ((i + 1.0) / layerCount);
                if (Double.isNaN(value)) value = (i + 1.0) / layerCount;
                value = clamp(value, 0.0, 1.0);
                if (value < prev) value = prev;
                thresholds[i] = value;
                prev = value;
            }
            return thresholds;
        }
        for (int i = 0; i < count; i++) {
            thresholds[i] = (i + 1.0) / layerCount;
        }
        return thresholds;
    }

    private static LayerConfig defaultLayer(String name, String type, String density) {
        LayerConfig layer = new LayerConfig();
        layer.name = name;
        layer.type = type;
        layer.density = density;
        return layer;
    }

    private static String defaultDensityForType(String type) {
        if ("CORE".equals(type)) return "high";
        if ("BUFFER".equals(type)) return "low";
        if ("RING".equals(type)) return "low";
        return "mid";
    }

    private static String defaultNameForType(String type, int index) {
        if ("CORE".equals(type)) return "Core";
        if ("BUFFER".equals(type)) return "Buffer";
        if ("RING".equals(type)) return "Ring";
        return "Urban " + index;
    }

    public static String normalizeLayerType(String type) {
        if (type == null) return null;
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CORE", "URBAN", "RING", "BUFFER" -> normalized;
            default -> "URBAN";
        };
    }

    public static String normalizeDensity(String density) {
        if (density == null) return null;
        String normalized = density.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "high", "mid", "low", "1" -> normalized;
            default -> "mid";
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static class LayerLayout {
        public final List<LayerConfig> layers;
        public final double[] thresholds;

        public LayerLayout(List<LayerConfig> layers, double[] thresholds) {
            this.layers = layers;
            this.thresholds = thresholds;
        }

        public int indexForRatio(double ratio) {
            double r = clamp(ratio, 0.0, 1.0);
            for (int i = 0; i < thresholds.length; i++) {
                if (r <= thresholds[i]) return i;
            }
            return layers.size() - 1;
        }

        public LayerConfig layerAt(int index) {
            int idx = clamp(index, 0, layers.size() - 1);
            return layers.get(idx);
        }

        public int findLayerIndexByType(String type) {
            String target = normalizeLayerType(type);
            if (target == null) return -1;
            for (int i = 0; i < layers.size(); i++) {
                if (target.equals(layers.get(i).type)) return i;
            }
            return -1;
        }
    }

    public static class LayerConfig {
        @SerializedName(value = "name", alternate = {"层名"})
        public String name;

        @SerializedName(value = "type", alternate = {"层类型"})
        public String type;

        @SerializedName(value = "density", alternate = {"功能密度"})
        public String density;

        @SerializedName(value = "ecology", alternate = {"生态策略"})
        public EcologyPolicy ecology;

        @SerializedName(value = "weight", alternate = {"权重"})
        public int weight = 1;

        @SerializedName(value = "is_wall", alternate = {"isWall", "是否城墙"})
        public boolean isWall;

        @SerializedName(value = "wall_layer", alternate = {"是否墙层"})
        public boolean wallLayer;

        @SerializedName(value = "wall", alternate = {"墙体"})
        public WallConfig wall;
    }

    public static class WallConfig {
        @SerializedName(value = "type", alternate = {"类型"})
        public String type;

        @SerializedName(value = "thickness_blocks", alternate = {"厚度方块"})
        public int thicknessBlocks;

        @SerializedName(value = "gate_count", alternate = {"城门数量"})
        public int[] gateCount;
    }
}
