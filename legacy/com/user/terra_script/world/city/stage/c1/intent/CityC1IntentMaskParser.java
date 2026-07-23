package com.user.terra_script.world.city.stage.c1.intent;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.user.terra_script.world.city.stage.c1.intent.CityC1ImageIntentModels.IMAGE_SIZE;

public final class CityC1IntentMaskParser {
    private static final int MIN_COMPONENT_PIXELS = 18;
    private static final int MIN_CLUSTER_PIXELS = 24;

    private CityC1IntentMaskParser() {}

    public static BufferedImage normalizeToCanvas(BufferedImage input) {
        if (input.getWidth() == IMAGE_SIZE && input.getHeight() == IMAGE_SIZE) return input;
        BufferedImage out = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(input, 0, 0, IMAGE_SIZE, IMAGE_SIZE, null);
        g.dispose();
        return out;
    }

    public static ParseResult parse(
            BufferedImage image,
            CityC1ImageIntentModels.Coordinate coordinate,
            List<CityC1ImageIntentModels.ColorMappingHint> hints
    ) {
        BufferedImage normalized = normalizeToCanvas(image);
        ClusterData clusterData = cluster(normalized);
        List<Cluster> clusters = new ArrayList<>(clusterData.clusters.values());
        clusters.sort(Comparator.comparingInt((Cluster c) -> c.pixelCount).reversed());

        Map<Integer, CityC1ImageIntentModels.ColorMappingHint> hintByColor = hintsByColor(hints);
        for (Cluster cluster : clusters) {
            CityC1ImageIntentModels.ColorMappingHint hint = hintByColor.get(cluster.rgb);
            if (hint != null) {
                cluster.layerType = hint.layer_type != null ? hint.layer_type : classify(cluster);
                cluster.districtId = hint.district_id;
                cluster.functionWeights.putAll(hint.function_weights);
                cluster.reason = hint.llm_reason != null ? hint.llm_reason : "color_mapping_hint";
                cluster.confidence = hint.confidence != null ? hint.confidence : 0.92;
            } else {
                cluster.layerType = classify(cluster);
                cluster.reason = "deterministic_color_heuristic";
                cluster.confidence = 0.70;
            }
        }

        ParseResult result = new ParseResult();
        result.normalized = normalized;
        result.color_mappings = buildColorMappings(clusters);
        result.cv_report.image_width = normalized.getWidth();
        result.cv_report.image_height = normalized.getHeight();
        result.cv_report.cluster_count = clusters.size();

        List<Component> boundaryComponents = new ArrayList<>();
        List<Component> districtComponents = new ArrayList<>();
        List<Component> roadComponents = new ArrayList<>();
        List<Component> anchorComponents = new ArrayList<>();

        for (Cluster cluster : clusters) {
            if ("background".equals(cluster.layerType) || "water".equals(cluster.layerType)) continue;
            List<Component> components = componentsFor(clusterData.clusterByPixel, cluster.clusterId, cluster.rgb);
            for (Component component : components) {
                if (component.pixelCount < MIN_COMPONENT_PIXELS) continue;
                component.cluster = cluster;
                switch (cluster.layerType) {
                    case "boundary" -> boundaryComponents.add(component);
                    case "road" -> roadComponents.add(component);
                    case "anchor" -> anchorComponents.add(component);
                    default -> districtComponents.add(component);
                }
            }
        }

        Component boundary = mergedBoundary(!boundaryComponents.isEmpty() ? boundaryComponents : districtComponents);
        if (boundary != null) {
            result.city_boundary = polygonFromBounds("city_boundary", boundary, coordinate);
            result.city_boundary.source = boundaryComponents.isEmpty() ? "cv_union_no_explicit_boundary" : "cv_boundary_color";
        }

        int districtIndex = 1;
        districtComponents.sort(Comparator.comparingInt((Component c) -> c.pixelCount).reversed());
        for (Component component : districtComponents) {
            if (component.pixelCount < Math.max(MIN_COMPONENT_PIXELS, normalized.getWidth() * normalized.getHeight() / 900)) continue;
            CityC1ImageIntentModels.DistrictPolygon district = new CityC1ImageIntentModels.DistrictPolygon();
            district.polygon_id = "district_polygon_" + districtIndex;
            district.district_id = component.cluster.districtId != null && !component.cluster.districtId.isBlank()
                    ? component.cluster.districtId
                    : "district_" + districtIndex;
            district.dominant_function = dominantFunction(component.cluster);
            district.function_weights.putAll(component.cluster.functionWeights);
            if (district.function_weights.isEmpty()) {
                district.function_weights.put(district.dominant_function, 1.0);
            }
            district.source = "cv";
            district.pixel_area = component.pixelCount;
            district.polygon = rectanglePolygon(component.minX, component.minZ, component.maxX, component.maxZ, coordinate);
            result.district_polygons.add(district);
            result.district_weight_hints.put(district.district_id, district.function_weights);
            districtIndex++;
        }

        int roadIndex = 1;
        for (Component component : roadComponents) {
            CityC1ImageIntentModels.RoadPath path = new CityC1ImageIntentModels.RoadPath();
            path.road_id = "road_" + roadIndex++;
            if ((component.maxX - component.minX) >= (component.maxZ - component.minZ)) {
                path.polyline.add(coordinate.toWorldPoint(component.minX, (component.minZ + component.maxZ) / 2));
                path.polyline.add(coordinate.toWorldPoint(component.maxX, (component.minZ + component.maxZ) / 2));
            } else {
                path.polyline.add(coordinate.toWorldPoint((component.minX + component.maxX) / 2, component.minZ));
                path.polyline.add(coordinate.toWorldPoint((component.minX + component.maxX) / 2, component.maxZ));
            }
            result.road_sketch.paths.add(path);
            result.road_sketch.pixel_count += component.pixelCount;
        }

        int anchorIndex = 1;
        for (Component component : anchorComponents) {
            CityC1ImageIntentModels.IntentPoint point = coordinate.toWorldPoint(
                    (component.minX + component.maxX) / 2,
                    (component.minZ + component.maxZ) / 2
            );
            CityC1ImageIntentModels.AnchorPoint anchor = new CityC1ImageIntentModels.AnchorPoint();
            anchor.anchor_id = "anchor_" + anchorIndex++;
            anchor.type = anchorType(component.cluster);
            anchor.pixel_x = point.pixel_x;
            anchor.pixel_z = point.pixel_z;
            anchor.x = point.world_x;
            anchor.z = point.world_z;
            result.anchor_points.add(anchor);
        }

        result.cv_report.district_count = result.district_polygons.size();
        result.cv_report.road_path_count = result.road_sketch.paths.size();
        result.cv_report.anchor_count = result.anchor_points.size();
        if (result.city_boundary == null || result.city_boundary.polygon.isEmpty()) {
            result.cv_report.blocking_errors.add("missing_city_boundary");
        }
        if (result.district_polygons.isEmpty()) {
            result.cv_report.blocking_errors.add("missing_district_polygons");
        }
        if (result.road_sketch.paths.isEmpty()) {
            result.cv_report.warnings.add("missing_road_sketch");
        }
        if (result.anchor_points.isEmpty()) {
            result.cv_report.warnings.add("missing_anchor_points");
        }
        result.cv_report.ok = result.cv_report.blocking_errors.isEmpty();
        return result;
    }

    private static ClusterData cluster(BufferedImage image) {
        ClusterData data = new ClusterData();
        data.clusterByPixel = new int[IMAGE_SIZE][IMAGE_SIZE];
        int nextId = 1;
        Map<Integer, Integer> clusterIdByRgb = new LinkedHashMap<>();

        for (int z = 0; z < IMAGE_SIZE; z++) {
            for (int x = 0; x < IMAGE_SIZE; x++) {
                int argb = image.getRGB(x, z);
                Color color = new Color(argb, true);
                if (color.getAlpha() < 80 || isBackground(color)) {
                    data.clusterByPixel[x][z] = 0;
                    continue;
                }
                int rgb = quantizedRgb(color);
                Integer id = clusterIdByRgb.get(rgb);
                if (id == null) {
                    id = nextId++;
                    clusterIdByRgb.put(rgb, id);
                    Cluster cluster = new Cluster();
                    cluster.clusterId = id;
                    cluster.rgb = rgb;
                    data.clusters.put(id, cluster);
                }
                Cluster cluster = data.clusters.get(id);
                cluster.pixelCount++;
                cluster.sumR += color.getRed();
                cluster.sumG += color.getGreen();
                cluster.sumB += color.getBlue();
                data.clusterByPixel[x][z] = id;
            }
        }

        data.clusters.entrySet().removeIf(entry -> entry.getValue().pixelCount < MIN_CLUSTER_PIXELS);
        for (int z = 0; z < IMAGE_SIZE; z++) {
            for (int x = 0; x < IMAGE_SIZE; x++) {
                if (!data.clusters.containsKey(data.clusterByPixel[x][z])) data.clusterByPixel[x][z] = 0;
            }
        }
        return data;
    }

    private static List<Component> componentsFor(int[][] clusterByPixel, int clusterId, int rgb) {
        boolean[][] visited = new boolean[IMAGE_SIZE][IMAGE_SIZE];
        List<Component> out = new ArrayList<>();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        for (int z = 0; z < IMAGE_SIZE; z++) {
            for (int x = 0; x < IMAGE_SIZE; x++) {
                if (visited[x][z] || clusterByPixel[x][z] != clusterId) continue;
                Component component = new Component();
                component.rgb = rgb;
                ArrayDeque<int[]> queue = new ArrayDeque<>();
                queue.add(new int[]{x, z});
                visited[x][z] = true;
                while (!queue.isEmpty()) {
                    int[] p = queue.removeFirst();
                    component.add(p[0], p[1]);
                    for (int[] dir : dirs) {
                        int nx = p[0] + dir[0];
                        int nz = p[1] + dir[1];
                        if (nx < 0 || nz < 0 || nx >= IMAGE_SIZE || nz >= IMAGE_SIZE) continue;
                        if (visited[nx][nz] || clusterByPixel[nx][nz] != clusterId) continue;
                        visited[nx][nz] = true;
                        queue.add(new int[]{nx, nz});
                    }
                }
                out.add(component);
            }
        }
        return out;
    }

    private static Component mergedBoundary(List<Component> components) {
        if (components == null || components.isEmpty()) return null;
        Component merged = new Component();
        for (Component component : components) {
            merged.pixelCount += component.pixelCount;
            merged.minX = Math.min(merged.minX, component.minX);
            merged.minZ = Math.min(merged.minZ, component.minZ);
            merged.maxX = Math.max(merged.maxX, component.maxX);
            merged.maxZ = Math.max(merged.maxZ, component.maxZ);
        }
        return merged;
    }

    private static CityC1ImageIntentModels.IntentPolygon polygonFromBounds(
            String id,
            Component component,
            CityC1ImageIntentModels.Coordinate coordinate
    ) {
        CityC1ImageIntentModels.IntentPolygon polygon = new CityC1ImageIntentModels.IntentPolygon();
        polygon.polygon_id = id;
        polygon.pixel_area = component.pixelCount;
        polygon.polygon = rectanglePolygon(component.minX, component.minZ, component.maxX, component.maxZ, coordinate);
        return polygon;
    }

    private static List<CityC1ImageIntentModels.IntentPoint> rectanglePolygon(
            int minX,
            int minZ,
            int maxX,
            int maxZ,
            CityC1ImageIntentModels.Coordinate coordinate
    ) {
        List<CityC1ImageIntentModels.IntentPoint> polygon = new ArrayList<>();
        polygon.add(coordinate.toWorldPoint(minX, minZ));
        polygon.add(coordinate.toWorldPoint(maxX, minZ));
        polygon.add(coordinate.toWorldPoint(maxX, maxZ));
        polygon.add(coordinate.toWorldPoint(minX, maxZ));
        return polygon;
    }

    private static List<CityC1ImageIntentModels.ColorMapping> buildColorMappings(List<Cluster> clusters) {
        List<CityC1ImageIntentModels.ColorMapping> mappings = new ArrayList<>();
        int districtIndex = 1;
        for (Cluster cluster : clusters) {
            CityC1ImageIntentModels.ColorMapping mapping = new CityC1ImageIntentModels.ColorMapping();
            mapping.color_cluster_id = "cluster_" + cluster.clusterId;
            mapping.sample_rgb = hex(cluster.averageRgb());
            mapping.layer_type = cluster.layerType;
            mapping.pixel_count = cluster.pixelCount;
            mapping.llm_reason = cluster.reason;
            mapping.confidence = cluster.confidence;
            if ("district".equals(cluster.layerType)) {
                mapping.district_id = cluster.districtId != null ? cluster.districtId : "district_" + districtIndex++;
                if (cluster.districtId == null) cluster.districtId = mapping.district_id;
                mapping.function_weights.putAll(cluster.functionWeights);
                if (mapping.function_weights.isEmpty()) mapping.function_weights.put(dominantFunction(cluster), 1.0);
            }
            mappings.add(mapping);
        }
        return mappings;
    }

    private static Map<Integer, CityC1ImageIntentModels.ColorMappingHint> hintsByColor(
            List<CityC1ImageIntentModels.ColorMappingHint> hints
    ) {
        Map<Integer, CityC1ImageIntentModels.ColorMappingHint> out = new HashMap<>();
        if (hints == null) return out;
        for (CityC1ImageIntentModels.ColorMappingHint hint : hints) {
            if (hint == null || hint.sample_rgb == null) continue;
            out.put(quantizedRgb(new Color(parseHex(hint.sample_rgb))), hint);
        }
        return out;
    }

    private static String classify(Cluster cluster) {
        Color color = new Color(cluster.averageRgb());
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        float hue = hsb[0];
        float saturation = hsb[1];
        float brightness = hsb[2];
        if (brightness < 0.18f) return "boundary";
        if (saturation < 0.20f && brightness < 0.72f) return "road";
        if (hue > 0.53f && hue < 0.68f && saturation > 0.35f) return "water";
        if ((hue > 0.78f || hue < 0.05f) && saturation > 0.45f && cluster.pixelCount < 1200) return "anchor";
        return "district";
    }

    private static String dominantFunction(Cluster cluster) {
        if (!cluster.functionWeights.isEmpty()) {
            return cluster.functionWeights.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("mixed");
        }
        Color color = new Color(cluster.averageRgb());
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        float hue = hsb[0];
        if (hue > 0.09f && hue < 0.18f) return "market";
        if (hue > 0.20f && hue < 0.45f) return "ecology";
        if (hue > 0.50f && hue < 0.72f) return "port";
        if (hue > 0.72f && hue < 0.86f) return "civic";
        return "residential";
    }

    private static String anchorType(Cluster cluster) {
        String function = dominantFunction(cluster);
        if ("port".equals(function)) return "port";
        if ("civic".equals(function)) return "plaza";
        return "landmark";
    }

    private static boolean isBackground(Color color) {
        if (color.getRed() > 238 && color.getGreen() > 238 && color.getBlue() > 238) return true;
        return color.getAlpha() < 80;
    }

    private static int quantizedRgb(Color color) {
        int r = (color.getRed() / 32) * 32;
        int g = (color.getGreen() / 32) * 32;
        int b = (color.getBlue() / 32) * 32;
        return (r << 16) | (g << 8) | b;
    }

    private static int parseHex(String value) {
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (v.startsWith("#")) v = v.substring(1);
        return Integer.parseInt(v, 16) & 0xFFFFFF;
    }

    private static String hex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    public static class ParseResult {
        public BufferedImage normalized;
        public CityC1ImageIntentModels.IntentPolygon city_boundary;
        public List<CityC1ImageIntentModels.DistrictPolygon> district_polygons = new ArrayList<>();
        public CityC1ImageIntentModels.RoadSketch road_sketch = new CityC1ImageIntentModels.RoadSketch();
        public List<CityC1ImageIntentModels.AnchorPoint> anchor_points = new ArrayList<>();
        public Map<String, Map<String, Double>> district_weight_hints = new LinkedHashMap<>();
        public List<CityC1ImageIntentModels.ColorMapping> color_mappings = new ArrayList<>();
        public CityC1ImageIntentModels.CvReport cv_report = new CityC1ImageIntentModels.CvReport();
    }

    private static class ClusterData {
        int[][] clusterByPixel;
        Map<Integer, Cluster> clusters = new LinkedHashMap<>();
    }

    private static class Cluster {
        int clusterId;
        int rgb;
        int pixelCount;
        long sumR;
        long sumG;
        long sumB;
        String layerType;
        String districtId;
        String reason = "deterministic_color_heuristic";
        double confidence = 0.70;
        Map<String, Double> functionWeights = new LinkedHashMap<>();

        int averageRgb() {
            int count = Math.max(1, pixelCount);
            int r = (int) Math.max(0, Math.min(255, sumR / count));
            int g = (int) Math.max(0, Math.min(255, sumG / count));
            int b = (int) Math.max(0, Math.min(255, sumB / count));
            return (r << 16) | (g << 8) | b;
        }
    }

    private static class Component {
        int rgb;
        int pixelCount;
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        Cluster cluster;

        void add(int x, int z) {
            pixelCount++;
            minX = Math.min(minX, x);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxZ = Math.max(maxZ, z);
        }
    }
}
