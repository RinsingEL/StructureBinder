package com.user.terra_script.world.city.stage.c1.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CityC1ImageIntentModels {
    public static final int VERSION = 1;
    public static final int IMAGE_SIZE = 512;
    public static final int SOURCE_STEP_BLOCKS = 8;

    private CityC1ImageIntentModels() {}

    public static class PrepareRequest {
        public String city_id;
        public String territory_id;
        public int center_x;
        public int center_z;
        public String city_scale_bucket = "normal";
        public String city_role = "settlement";
        public String density = "mid";
        public String ecology = "adaptive";
        public String water_policy = "avoid_water";
        public String source_kind = "image2";
        public int radius_blocks = 1024;

        public static PrepareRequest fromJson(JsonObject json) {
            PrepareRequest request = new PrepareRequest();
            request.territory_id = stringValue(json, "territory_id", stringValue(json, "territoryId", null));
            request.center_x = intValue(json, "center_x", intValue(json, "centerX", 0));
            request.center_z = intValue(json, "center_z", intValue(json, "centerZ", 0));
            request.city_id = stringValue(json, "city_id", null);
            if (request.city_id == null || request.city_id.isBlank()) {
                request.city_id = "city_" + request.center_x + "_" + request.center_z;
            }

            request.city_scale_bucket = normalizeScaleBucket(
                    stringValue(json, "city_scale_bucket", inferScaleBucket(json))
            );
            request.city_role = normalizeText(stringValue(json, "city_role", "settlement"), "settlement");
            request.density = normalizeDensity(stringValue(json, "density", "mid"));
            request.ecology = normalizeText(stringValue(json, "ecology", stringValue(json, "ecology_policy", "adaptive")), "adaptive");
            request.water_policy = normalizeText(stringValue(json, "water_policy", inferWaterPolicy(json)), "avoid_water");
            request.source_kind = normalizeText(stringValue(json, "source_kind", "image2"), "image2");
            request.radius_blocks = clamp(intValue(json, "radius_blocks", 1024), 64, 4096);
            return request;
        }

        public Coordinate coordinate() {
            Coordinate coordinate = new Coordinate();
            coordinate.image_size.add(IMAGE_SIZE);
            coordinate.image_size.add(IMAGE_SIZE);
            coordinate.source_step_blocks = SOURCE_STEP_BLOCKS;
            coordinate.world_extent_blocks = worldExtentBlocks(city_scale_bucket);
            coordinate.world_origin_x = center_x - coordinate.world_extent_blocks / 2;
            coordinate.world_origin_z = center_z - coordinate.world_extent_blocks / 2;
            coordinate.pixel_to_block = coordinate.world_extent_blocks / (double) IMAGE_SIZE;
            coordinate.source_grid_size = Math.max(1, coordinate.world_extent_blocks / SOURCE_STEP_BLOCKS);
            coordinate.city_scale_bucket = city_scale_bucket;
            coordinate.rotation = "north_up";
            return coordinate;
        }

        public PreCityContext preCityContext() {
            PreCityContext ctx = new PreCityContext();
            ctx.territory_id = territory_id;
            ctx.center_x = center_x;
            ctx.center_z = center_z;
            ctx.city_scale_bucket = city_scale_bucket;
            ctx.city_role = city_role;
            ctx.density = density;
            ctx.ecology = ecology;
            ctx.water_policy = water_policy;
            return ctx;
        }
    }

    public static class UrbanIntentMap {
        public int version = VERSION;
        public String city_id;
        public Source source = new Source();
        public Coordinate coordinate = new Coordinate();
        public PreCityContext pre_city_context = new PreCityContext();
        public List<ColorMapping> color_mapping = new ArrayList<>();
        public GeometryLayers geometry_layers = new GeometryLayers();
        public BaseMapInfo base_map = new BaseMapInfo();
        public IntentPolygon city_boundary = new IntentPolygon();
        public List<DistrictPolygon> district_polygons = new ArrayList<>();
        public RoadSketch road_sketch = new RoadSketch();
        public List<AnchorPoint> anchor_points = new ArrayList<>();
        public Map<String, Map<String, Double>> district_weight_hints = new LinkedHashMap<>();
        public CvReport cv_report = new CvReport();
        public GeometryReport geometry_report = new GeometryReport();
        public BasicCheck basic_check = new BasicCheck();
    }

    public static class Source {
        public String kind;
        public String prompt_id;
        public String input_preview;
        public String raw_output;
        public String terrain_clean_ref;
        public String terrain_locator_ref;
        public String terrain_locator_json_ref;
        public String image2_concept_ref;
        public String geometry_design_ref;
        public String geometry_overlay_ref;
        public String geometry_review_ref;
        public String geometry_patch_ref;
        public String generated_at;
    }

    public static class Coordinate {
        public List<Integer> image_size = new ArrayList<>();
        public int source_step_blocks;
        public int world_extent_blocks;
        public int world_origin_x;
        public int world_origin_z;
        public double pixel_to_block;
        public int source_grid_size;
        public String city_scale_bucket;
        public String rotation;
        public int radius_blocks;
        public int center_x;
        public int center_z;
        public List<GridLine> grid_lines = new ArrayList<>();
        public String pixel_to_world_formula;
        public String world_to_pixel_formula;

        public IntentPoint toWorldPoint(int pixelX, int pixelZ) {
            IntentPoint point = new IntentPoint();
            point.pixel_x = clamp(pixelX, 0, IMAGE_SIZE - 1);
            point.pixel_z = clamp(pixelZ, 0, IMAGE_SIZE - 1);
            point.world_x = world_origin_x + (int) Math.round(point.pixel_x * pixel_to_block);
            point.world_z = world_origin_z + (int) Math.round(point.pixel_z * pixel_to_block);
            return point;
        }

        public IntentPoint toPixelPointFromWorld(int worldX, int worldZ) {
            IntentPoint point = new IntentPoint();
            point.world_x = worldX;
            point.world_z = worldZ;
            point.pixel_x = clamp((int) Math.round((worldX - world_origin_x) / pixel_to_block), 0, IMAGE_SIZE - 1);
            point.pixel_z = clamp((int) Math.round((worldZ - world_origin_z) / pixel_to_block), 0, IMAGE_SIZE - 1);
            return point;
        }
    }

    public static class GridLine {
        public String axis;
        public int pixel;
        public int world;
    }

    public static class PreCityContext {
        public String territory_id;
        public int center_x;
        public int center_z;
        public String city_scale_bucket;
        public String city_role;
        public String density;
        public String ecology;
        public String water_policy;
    }

    public static class ColorMapping {
        public String color_cluster_id;
        public String sample_rgb;
        public int tolerance = 32;
        public String layer_type;
        public String district_id;
        public Map<String, Double> function_weights = new LinkedHashMap<>();
        public String llm_reason = "deterministic_cv_mapping";
        public double confidence = 0.75;
        public int pixel_count;
    }

    public static class GeometryLayers {
        public String coordinate_space;
        public String city_boundary_ref;
        public List<String> district_polygon_refs = new ArrayList<>();
        public List<String> road_path_refs = new ArrayList<>();
        public List<String> anchor_point_refs = new ArrayList<>();
        public List<String> normalization_notes = new ArrayList<>();
    }

    public static class ColorMappingHint {
        public String sample_rgb;
        public String layer_type;
        public String district_id;
        public Map<String, Double> function_weights = new LinkedHashMap<>();
        public String llm_reason;
        public Double confidence;

        public static List<ColorMappingHint> fromJson(JsonObject json) {
            List<ColorMappingHint> hints = new ArrayList<>();
            if (json == null || !json.has("color_mapping") || !json.get("color_mapping").isJsonArray()) return hints;
            JsonArray array = json.getAsJsonArray("color_mapping");
            for (JsonElement element : array) {
                if (!element.isJsonObject()) continue;
                JsonObject obj = element.getAsJsonObject();
                ColorMappingHint hint = new ColorMappingHint();
                hint.sample_rgb = normalizeRgb(stringValue(obj, "sample_rgb", stringValue(obj, "color", null)));
                hint.layer_type = normalizeText(stringValue(obj, "layer_type", null), null);
                hint.district_id = stringValue(obj, "district_id", null);
                hint.llm_reason = stringValue(obj, "llm_reason", null);
                if (obj.has("confidence") && obj.get("confidence").isJsonPrimitive()) {
                    hint.confidence = obj.get("confidence").getAsDouble();
                }
                if (obj.has("function_weights") && obj.get("function_weights").isJsonObject()) {
                    JsonObject weights = obj.getAsJsonObject("function_weights");
                    for (Map.Entry<String, JsonElement> entry : weights.entrySet()) {
                        if (entry.getValue().isJsonPrimitive()) {
                            hint.function_weights.put(entry.getKey(), entry.getValue().getAsDouble());
                        }
                    }
                }
                if (hint.sample_rgb != null) hints.add(hint);
            }
            return hints;
        }
    }

    public static class BaseMapInfo {
        public String land_water;
        public String contours;
        public String existing_city_boundaries;
        public String territory_boundary;
        public List<String> major_geo_notes = new ArrayList<>();
    }

    public static class IntentPolygon {
        public String polygon_id;
        public List<IntentPoint> polygon = new ArrayList<>();
        public String source = "cv";
        public int pixel_area;
    }

    public static class DistrictPolygon extends IntentPolygon {
        public String district_id;
        public String dominant_function;
        public Map<String, Double> function_weights = new LinkedHashMap<>();
    }

    public static class RoadSketch {
        public List<RoadPath> paths = new ArrayList<>();
        public int pixel_count;
        public String source = "cv";
    }

    public static class RoadPath {
        public String road_id;
        public List<IntentPoint> polyline = new ArrayList<>();
        public String road_type = "main";
        public String source = "cv";
    }

    public static class AnchorPoint {
        public String anchor_id;
        public String type;
        public int pixel_x;
        public int pixel_z;
        public int x;
        public int z;
        public double weight = 1.0;
        public String source = "cv";
    }

    public static class IntentPoint {
        public int pixel_x;
        public int pixel_z;
        public int world_x;
        public int world_z;
    }

    public static class CvReport {
        public boolean ok;
        public int image_width;
        public int image_height;
        public int cluster_count;
        public int district_count;
        public int road_path_count;
        public int anchor_count;
        public List<String> warnings = new ArrayList<>();
        public List<String> blocking_errors = new ArrayList<>();
    }

    public static class GeometryReport {
        public boolean ok;
        public String coordinate_space;
        public int district_count;
        public int road_path_count;
        public int anchor_count;
        public int applied_patch_count;
        public String rendered_overlay_ref;
        public List<String> warnings = new ArrayList<>();
        public List<String> blocking_errors = new ArrayList<>();
    }

    public static class BasicCheck {
        public boolean ok;
        public List<String> warnings = new ArrayList<>();
        public List<String> boundary_conflicts = new ArrayList<>();
        public List<String> repair_actions = new ArrayList<>();
        public List<String> blocking_errors = new ArrayList<>();
    }

    static int worldExtentBlocks(String bucket) {
        return switch (normalizeScaleBucket(bucket)) {
            case "small" -> 1024;
            case "large" -> 3072;
            case "capital" -> 4096;
            default -> 2048;
        };
    }

    static String normalizeScaleBucket(String value) {
        String normalized = normalizeText(value, "normal");
        return switch (normalized) {
            case "small", "normal", "large", "capital" -> normalized;
            default -> "normal";
        };
    }

    static String normalizeDensity(String value) {
        String normalized = normalizeText(value, "mid");
        if ("medium".equals(normalized)) return "mid";
        if ("1".equals(normalized)) return "mid";
        return switch (normalized) {
            case "low", "mid", "high" -> normalized;
            default -> "mid";
        };
    }

    static String normalizeText(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]+", "_");
    }

    static String normalizeRgb(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        if (!v.startsWith("#")) v = "#" + v;
        return v.matches("#[0-9a-fA-F]{6}") ? v.toUpperCase(Locale.ROOT) : null;
    }

    static String stringValue(JsonObject json, String key, String fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) return fallback;
        return json.get(key).getAsString();
    }

    static int intValue(JsonObject json, String key, int fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) return fallback;
        return json.get(key).getAsInt();
    }

    private static String inferScaleBucket(JsonObject json) {
        int target = intValue(json, "target_chunk_count", intValue(json, "targetChunkCount", 0));
        if (target >= 360) return "capital";
        if (target >= 220) return "large";
        if (target > 0 && target <= 90) return "small";
        return "normal";
    }

    private static String inferWaterPolicy(JsonObject json) {
        if (json != null && json.has("allow_water_city") && json.get("allow_water_city").getAsBoolean()) {
            return "waterfront";
        }
        return "avoid_water";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
