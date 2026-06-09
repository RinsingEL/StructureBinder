package com.user.terra_script.world.city.stage.c1.intent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

public final class CityC1ImageIntentIO {
    public static final String ARTIFACT_DIR = "c1_image_intent";
    public static final String URBAN_INTENT_FILE = "UrbanIntentMap.json";
    public static final String MANIFEST_FILE = "C1_image_intent_manifest.json";
    public static final String PROMPT_FILE = "C1_image2_prompt.md";
    public static final String BASE_MAP_FILE = "C1_base_map.png";
    public static final String BASE_MAP_LEGEND_FILE = "C1_base_map.legend.json";
    public static final String RAW_OUTPUT_FILE = "C1_intent_raw.png";
    public static final String MASK_FILE = "C1_intent_mask.png";
    public static final String OVERLAY_FILE = "C1_intent_overlay.png";
    public static final String CV_REPORT_FILE = "C1_cv_report.json";
    public static final String BASIC_CHECK_FILE = "C1_basic_check.json";
    public static final String COLOR_CLUSTERS_FILE = "C1_color_clusters.json";
    public static final String GEOMETRY_MANIFEST_FILE = "C1_geometry_manifest.json";
    public static final String GEOMETRY_PROMPT_FILE = "C1_geometry_prompt.md";
    public static final String TERRAIN_CLEAN_FILE = "terrain_clean.png";
    public static final String TERRAIN_LOCATOR_FILE = "terrain_locator.png";
    public static final String TERRAIN_LOCATOR_JSON_FILE = "terrain_locator.json";
    public static final String IMAGE2_CONCEPT_FILE = "image2_concept.png";
    public static final String GEOMETRY_DESIGN_FILE = "C1_geometry_design.json";
    public static final String GEOMETRY_OVERLAY_FILE = "C1_geometry_overlay.png";
    public static final String GEOMETRY_REVIEW_FILE = "C1_geometry_review.json";
    public static final String GEOMETRY_PATCH_FILE = "C1_geometry_patch.json";
    public static final String GEOMETRY_REPORT_FILE = "C1_geometry_report.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> FORBIDDEN_CONTRACT_KEYS = Set.of(
            "target_chunk_count",
            "targetChunkCount",
            "bias",
            "layers",
            "layer_count",
            "layerCount",
            "layer_thresholds",
            "layerThresholds",
            "selected_cluster_ref",
            "selectedClusterRef",
            "legacy",
            "legacy_hint",
            "legacy_summary"
    );

    private CityC1ImageIntentIO() {}

    public static Path cityDir(MinecraftServer server, String cityId) {
        if (server != null) {
            return server.getWorldPath(LevelResource.ROOT)
                    .resolve("terra_script")
                    .resolve("cities")
                    .resolve(cityId);
        }
        return Paths.get("terra_script", "cities", cityId);
    }

    public static Path artifactDir(Path cityDir) throws Exception {
        Path dir = cityDir.resolve(ARTIFACT_DIR);
        Files.createDirectories(dir);
        return dir;
    }

    public static String relativeCityPath(String cityId, String fileName) {
        return "cities/" + cityId + "/" + fileName;
    }

    public static String relativeArtifactPath(String cityId, String fileName) {
        return "cities/" + cityId + "/" + ARTIFACT_DIR + "/" + fileName;
    }

    public static void writeJson(Path file, Object value) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(value), StandardCharsets.UTF_8);
    }

    public static void writeJsonElement(Path file, JsonElement value) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(value), StandardCharsets.UTF_8);
    }

    public static JsonObject readJsonObject(Path file) throws Exception {
        if (file == null || !Files.exists(file)) return null;
        JsonElement element = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    public static CityC1ImageIntentModels.UrbanIntentMap readUrbanIntentMap(Path cityDir) throws Exception {
        Path file = cityDir.resolve(URBAN_INTENT_FILE);
        if (!Files.exists(file)) return null;
        return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), CityC1ImageIntentModels.UrbanIntentMap.class);
    }

    public static void writeUrbanIntentMap(Path cityDir, CityC1ImageIntentModels.UrbanIntentMap map) throws Exception {
        JsonElement tree = GSON.toJsonTree(map);
        assertNoForbiddenContractKeys(tree, "");
        writeJsonElement(cityDir.resolve(URBAN_INTENT_FILE), tree);
    }

    public static void assertNoForbiddenContractKeys(JsonElement element, String path) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            for (String key : obj.keySet()) {
                if (FORBIDDEN_CONTRACT_KEYS.contains(key)) {
                    throw new IllegalStateException("UrbanIntentMap contains forbidden legacy key: " + path + "/" + key);
                }
                assertNoForbiddenContractKeys(obj.get(key), path + "/" + key);
            }
        } else if (element.isJsonArray()) {
            int i = 0;
            for (JsonElement item : element.getAsJsonArray()) {
                assertNoForbiddenContractKeys(item, path + "[" + i + "]");
                i++;
            }
        }
    }

    public static Gson gson() {
        return GSON;
    }
}
