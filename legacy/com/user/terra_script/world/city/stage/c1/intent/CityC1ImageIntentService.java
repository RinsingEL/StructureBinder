package com.user.terra_script.world.city.stage.c1.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

public final class CityC1ImageIntentService {
    private CityC1ImageIntentService() {}

    public static JsonObject prepare(MinecraftServer server, CityC1ImageIntentModels.PrepareRequest request) throws Exception {
        validatePrepareRequest(request);
        Path cityDir = CityC1ImageIntentIO.cityDir(server, request.city_id);
        Path artifactDir = CityC1ImageIntentIO.artifactDir(cityDir);
        CityC1ImageIntentModels.Coordinate coordinate = request.coordinate();

        Path baseMap = artifactDir.resolve(CityC1ImageIntentIO.BASE_MAP_FILE);
        Path baseLegend = artifactDir.resolve(CityC1ImageIntentIO.BASE_MAP_LEGEND_FILE);
        CityC1ImageIntentModels.BaseMapInfo baseMapInfo = CityC1BaseMapRenderer.render(
                server,
                request,
                coordinate,
                baseMap,
                baseLegend
        );

        String prompt = buildPrompt(request, coordinate);
        Files.writeString(artifactDir.resolve(CityC1ImageIntentIO.PROMPT_FILE), prompt, StandardCharsets.UTF_8);

        JsonObject manifest = new JsonObject();
        manifest.addProperty("version", CityC1ImageIntentModels.VERSION);
        manifest.addProperty("prepared_at", Instant.now().toString());
        manifest.add("request", CityC1ImageIntentIO.gson().toJsonTree(request));
        manifest.add("coordinate", CityC1ImageIntentIO.gson().toJsonTree(coordinate));
        manifest.add("base_map", CityC1ImageIntentIO.gson().toJsonTree(baseMapInfo));
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("base_map", CityC1ImageIntentIO.relativeArtifactPath(request.city_id, CityC1ImageIntentIO.BASE_MAP_FILE));
        artifacts.addProperty("base_map_legend", CityC1ImageIntentIO.relativeArtifactPath(request.city_id, CityC1ImageIntentIO.BASE_MAP_LEGEND_FILE));
        artifacts.addProperty("prompt", CityC1ImageIntentIO.relativeArtifactPath(request.city_id, CityC1ImageIntentIO.PROMPT_FILE));
        artifacts.addProperty("manifest", CityC1ImageIntentIO.relativeArtifactPath(request.city_id, CityC1ImageIntentIO.MANIFEST_FILE));
        manifest.add("artifacts", artifacts);
        CityC1ImageIntentIO.writeJsonElement(artifactDir.resolve(CityC1ImageIntentIO.MANIFEST_FILE), manifest);

        JsonObject out = new JsonObject();
        out.addProperty("status", "prepared");
        out.addProperty("step", "C1_IMAGE_INTENT_PREPARE");
        out.addProperty("city_id", request.city_id);
        out.addProperty("territory_id", request.territory_id);
        out.add("coordinate", CityC1ImageIntentIO.gson().toJsonTree(coordinate));
        out.add("base_map", CityC1ImageIntentIO.gson().toJsonTree(baseMapInfo));
        out.add("artifacts", artifacts);
        out.addProperty("next_action", "GENERATE_IMAGE_WITH_city_c1_image_intent_generate_OR_IMPORT_MASK");
        return out;
    }

    public static JsonObject importIntentImage(MinecraftServer server, JsonObject json) throws Exception {
        String cityId = stringValue(json, "city_id", null);
        if (cityId == null || cityId.isBlank()) {
            CityC1ImageIntentModels.PrepareRequest request = CityC1ImageIntentModels.PrepareRequest.fromJson(json);
            cityId = request.city_id;
        }
        Path cityDir = CityC1ImageIntentIO.cityDir(server, cityId);
        Path artifactDirPath = cityDir.resolve(CityC1ImageIntentIO.ARTIFACT_DIR);
        JsonObject manifest = CityC1ImageIntentIO.readJsonObject(artifactDirPath.resolve(CityC1ImageIntentIO.MANIFEST_FILE));

        CityC1ImageIntentModels.PrepareRequest request = requestFrom(manifest, json);
        validatePrepareRequest(request);
        Path artifactDir = CityC1ImageIntentIO.artifactDir(cityDir);
        CityC1ImageIntentModels.Coordinate coordinate = coordinateFrom(manifest, request);
        CityC1ImageIntentModels.BaseMapInfo baseMap = baseMapFrom(manifest);
        BufferedImage rawImage = readInputImage(json, cityDir, artifactDir);
        if (rawImage == null) {
            throw new IllegalArgumentException("Missing image_base64 or image_path for C1 image intent import.");
        }

        Path rawOutput = artifactDir.resolve(CityC1ImageIntentIO.RAW_OUTPUT_FILE);
        ImageIO.write(rawImage, "png", rawOutput.toFile());
        BufferedImage normalized = CityC1IntentMaskParser.normalizeToCanvas(rawImage);
        Path maskFile = artifactDir.resolve(CityC1ImageIntentIO.MASK_FILE);
        ImageIO.write(normalized, "png", maskFile.toFile());

        List<CityC1ImageIntentModels.ColorMappingHint> hints = CityC1ImageIntentModels.ColorMappingHint.fromJson(json);
        CityC1IntentMaskParser.ParseResult parsed = CityC1IntentMaskParser.parse(normalized, coordinate, hints);

        CityC1ImageIntentModels.UrbanIntentMap map = new CityC1ImageIntentModels.UrbanIntentMap();
        map.city_id = request.city_id;
        map.source.kind = stringValue(json, "source_kind", request.source_kind);
        map.source.prompt_id = stringValue(json, "prompt_id", "c1_image_intent_v1");
        map.source.input_preview = CityC1ImageIntentIO.relativeArtifactPath(request.city_id, CityC1ImageIntentIO.BASE_MAP_FILE);
        map.source.raw_output = CityC1ImageIntentIO.relativeArtifactPath(request.city_id, CityC1ImageIntentIO.RAW_OUTPUT_FILE);
        map.source.generated_at = stringValue(json, "generated_at", Instant.now().toString());
        map.coordinate = coordinate;
        map.pre_city_context = request.preCityContext();
        map.base_map = baseMap;
        map.color_mapping = parsed.color_mappings;
        map.city_boundary = parsed.city_boundary != null ? parsed.city_boundary : new CityC1ImageIntentModels.IntentPolygon();
        map.district_polygons = parsed.district_polygons;
        map.road_sketch = parsed.road_sketch;
        map.anchor_points = parsed.anchor_points;
        map.district_weight_hints = parsed.district_weight_hints;
        map.cv_report = parsed.cv_report;
        map.basic_check = CityC1IntentValidation.validate(
                map,
                (worldX, worldZ) -> {
                    if (request.territory_id == null || request.territory_id.isBlank()) return true;
                    return com.user.terra_script.world.TerritoryManager.isChunkWithinSovereignty(
                            ChunkPos.asLong(worldX >> 4, worldZ >> 4),
                            request.territory_id
                    );
                }
        );

        CityC1ImageIntentIO.writeJson(artifactDir.resolve(CityC1ImageIntentIO.CV_REPORT_FILE), map.cv_report);
        CityC1ImageIntentIO.writeJson(artifactDir.resolve(CityC1ImageIntentIO.BASIC_CHECK_FILE), map.basic_check);
        CityC1ImageIntentIO.writeJson(artifactDir.resolve(CityC1ImageIntentIO.COLOR_CLUSTERS_FILE), map.color_mapping);
        CityC1IntentPreviewExporter.exportOverlay(
                readOptionalImage(artifactDir.resolve(CityC1ImageIntentIO.BASE_MAP_FILE)),
                map,
                artifactDir.resolve(CityC1ImageIntentIO.OVERLAY_FILE)
        );

        boolean accepted = map.cv_report.ok && map.basic_check.ok;
        if (accepted) {
            CityC1ImageIntentIO.writeUrbanIntentMap(cityDir, map);
        }

        JsonObject artifacts = artifactPayload(request.city_id, accepted);
        JsonObject out = new JsonObject();
        out.addProperty("status", accepted ? "imported" : "blocked");
        out.addProperty("step", "C1_IMAGE_INTENT_IMPORT");
        out.addProperty("city_id", request.city_id);
        out.addProperty("urban_intent_saved", accepted);
        out.add("cv_report", CityC1ImageIntentIO.gson().toJsonTree(map.cv_report));
        out.add("basic_check", CityC1ImageIntentIO.gson().toJsonTree(map.basic_check));
        out.add("artifacts", artifacts);
        if (!accepted) {
            out.addProperty("message", "C1 image intent import produced debug artifacts but did not overwrite UrbanIntentMap.json.");
        }
        return out;
    }

    public static JsonObject data(MinecraftServer server, String cityId) throws Exception {
        if (cityId == null || cityId.isBlank()) throw new IllegalArgumentException("Missing city_id");
        Path cityDir = CityC1ImageIntentIO.cityDir(server, cityId);
        Path artifactDir = CityC1ImageIntentIO.artifactDir(cityDir);

        JsonObject out = new JsonObject();
        out.addProperty("status", "ok");
        out.addProperty("step", "C1_IMAGE_INTENT_DATA");
        out.addProperty("city_id", cityId);
        Path urbanIntent = cityDir.resolve(CityC1ImageIntentIO.URBAN_INTENT_FILE);
        out.addProperty("urban_intent_exists", Files.exists(urbanIntent));
        if (Files.exists(urbanIntent)) {
            out.add("urban_intent_map", JsonParser.parseString(Files.readString(urbanIntent, StandardCharsets.UTF_8)));
        }
        JsonObject manifest = CityC1ImageIntentIO.readJsonObject(artifactDir.resolve(CityC1ImageIntentIO.MANIFEST_FILE));
        if (manifest != null) out.add("manifest", manifest);
        JsonObject cv = CityC1ImageIntentIO.readJsonObject(artifactDir.resolve(CityC1ImageIntentIO.CV_REPORT_FILE));
        if (cv != null) out.add("cv_report", cv);
        JsonObject basic = CityC1ImageIntentIO.readJsonObject(artifactDir.resolve(CityC1ImageIntentIO.BASIC_CHECK_FILE));
        if (basic != null) out.add("basic_check", basic);
        out.add("artifacts", artifactPayload(cityId, Files.exists(urbanIntent)));
        return out;
    }

    private static void validatePrepareRequest(CityC1ImageIntentModels.PrepareRequest request) {
        if (request == null || request.territory_id == null || request.territory_id.isBlank()) {
            throw new IllegalArgumentException("Missing territory_id");
        }
        if (request.city_id == null || request.city_id.isBlank()) {
            throw new IllegalArgumentException("Missing city_id");
        }
    }

    private static String buildPrompt(CityC1ImageIntentModels.PrepareRequest request, CityC1ImageIntentModels.Coordinate coordinate) {
        return """
                Create a clean 512x512 top-down city planning intent mask over the supplied base map.
                Keep the map north-up and preserve the visible terrain relationships from the base map.
                Output distinct flat colors with crisp edges. Do not use gradients, shadows, transparency, labels, legends, or decorative texture.
                Required layers:
                - one clear city boundary enclosing the intended city area
                - 3 to 7 filled functional district polygons inside the boundary
                - a simple connected main road sketch linking entries, anchors, and major districts
                - optional small anchor points for gates, plazas, ports, bridges, or landmarks
                City context:
                - city_id: %s
                - territory_id: %s
                - center: %d, %d
                - scale bucket: %s
                - role: %s
                - density: %s
                - ecology: %s
                - water policy: %s
                Coordinate contract:
                - image size: 512x512
                - source step blocks: 8
                - world extent blocks: %d
                Return only the machine-readable intent image.
                """.formatted(
                request.city_id,
                request.territory_id,
                request.center_x,
                request.center_z,
                request.city_scale_bucket,
                request.city_role,
                request.density,
                request.ecology,
                request.water_policy,
                coordinate.world_extent_blocks
        );
    }

    private static CityC1ImageIntentModels.PrepareRequest requestFrom(JsonObject manifest, JsonObject fallback) {
        if (manifest != null && manifest.has("request") && manifest.get("request").isJsonObject()) {
            return CityC1ImageIntentIO.gson().fromJson(manifest.getAsJsonObject("request"), CityC1ImageIntentModels.PrepareRequest.class);
        }
        return CityC1ImageIntentModels.PrepareRequest.fromJson(fallback);
    }

    private static CityC1ImageIntentModels.Coordinate coordinateFrom(JsonObject manifest, CityC1ImageIntentModels.PrepareRequest request) {
        if (manifest != null && manifest.has("coordinate") && manifest.get("coordinate").isJsonObject()) {
            return CityC1ImageIntentIO.gson().fromJson(manifest.getAsJsonObject("coordinate"), CityC1ImageIntentModels.Coordinate.class);
        }
        return request.coordinate();
    }

    private static CityC1ImageIntentModels.BaseMapInfo baseMapFrom(JsonObject manifest) {
        if (manifest != null && manifest.has("base_map") && manifest.get("base_map").isJsonObject()) {
            return CityC1ImageIntentIO.gson().fromJson(manifest.getAsJsonObject("base_map"), CityC1ImageIntentModels.BaseMapInfo.class);
        }
        CityC1ImageIntentModels.BaseMapInfo info = new CityC1ImageIntentModels.BaseMapInfo();
        info.land_water = "unknown_import_without_prepare";
        info.contours = "unknown_import_without_prepare";
        info.existing_city_boundaries = "unknown_import_without_prepare";
        info.territory_boundary = "unknown_import_without_prepare";
        return info;
    }

    private static BufferedImage readInputImage(JsonObject json, Path cityDir, Path artifactDir) throws Exception {
        String base64 = stringValue(json, "image_base64", null);
        if (base64 != null && !base64.isBlank()) {
            int comma = base64.indexOf(',');
            if (comma >= 0) base64 = base64.substring(comma + 1);
            byte[] bytes = Base64.getDecoder().decode(base64);
            return ImageIO.read(new ByteArrayInputStream(bytes));
        }
        String imagePath = stringValue(json, "image_path", stringValue(json, "raw_output_path", null));
        if (imagePath == null || imagePath.isBlank()) return null;
        Path path = resolveInputPath(imagePath, cityDir, artifactDir);
        return Files.exists(path) ? ImageIO.read(path.toFile()) : null;
    }

    private static Path resolveInputPath(String imagePath, Path cityDir, Path artifactDir) {
        Path path = Paths.get(imagePath);
        if (path.isAbsolute()) return path;
        Path inArtifact = artifactDir.resolve(imagePath).normalize();
        if (Files.exists(inArtifact)) return inArtifact;
        return cityDir.resolve(imagePath).normalize();
    }

    private static BufferedImage readOptionalImage(Path path) {
        try {
            return Files.exists(path) ? ImageIO.read(path.toFile()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject artifactPayload(String cityId, boolean includeUrbanIntent) {
        JsonObject artifacts = new JsonObject();
        if (includeUrbanIntent) {
            artifacts.addProperty("urban_intent_map", CityC1ImageIntentIO.relativeCityPath(cityId, CityC1ImageIntentIO.URBAN_INTENT_FILE));
        }
        artifacts.addProperty("manifest", CityC1ImageIntentIO.relativeArtifactPath(cityId, CityC1ImageIntentIO.MANIFEST_FILE));
        artifacts.addProperty("prompt", CityC1ImageIntentIO.relativeArtifactPath(cityId, CityC1ImageIntentIO.PROMPT_FILE));
        artifacts.addProperty("base_map", CityC1ImageIntentIO.relativeArtifactPath(cityId, CityC1ImageIntentIO.BASE_MAP_FILE));
        artifacts.addProperty("raw_output", CityC1ImageIntentIO.relativeArtifactPath(cityId, CityC1ImageIntentIO.RAW_OUTPUT_FILE));
        artifacts.addProperty("normalized_mask", CityC1ImageIntentIO.relativeArtifactPath(cityId, CityC1ImageIntentIO.MASK_FILE));
        artifacts.addProperty("overlay", CityC1ImageIntentIO.relativeArtifactPath(cityId, CityC1ImageIntentIO.OVERLAY_FILE));
        artifacts.addProperty("cv_report", CityC1ImageIntentIO.relativeArtifactPath(cityId, CityC1ImageIntentIO.CV_REPORT_FILE));
        artifacts.addProperty("basic_check", CityC1ImageIntentIO.relativeArtifactPath(cityId, CityC1ImageIntentIO.BASIC_CHECK_FILE));
        artifacts.addProperty("color_clusters", CityC1ImageIntentIO.relativeArtifactPath(cityId, CityC1ImageIntentIO.COLOR_CLUSTERS_FILE));
        return artifacts;
    }

    private static String stringValue(JsonObject json, String key, String fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) return fallback;
        JsonElement element = json.get(key);
        return element.isJsonPrimitive() ? element.getAsString() : fallback;
    }
}
