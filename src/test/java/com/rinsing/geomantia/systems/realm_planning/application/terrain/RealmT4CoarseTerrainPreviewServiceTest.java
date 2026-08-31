package com.rinsing.geomantia.systems.realm_planning.application.terrain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealmT4CoarseTerrainPreviewServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void samplesOwnedCellCentersOnceAcrossNegativeCoordinatesAndHoles() throws Exception {
        Path root = tempDir.resolve("realm_debug");
        Path run = writeRun(root, "run_negative", 16, List.of(
                territory(-2, -1, "realm_a", "owned"),
                territory(-1, -1, "realm_a", "owned"),
                territory(-2, 1, "realm_a", "owned"),
                territory(-1, 1, "realm_a", "wild"),
                territory(0, 0, "realm_b", "owned")
        ));
        List<String> calls = new ArrayList<>();
        TerrainPreviewProvider provider = provider("native_preview", TerrainPreviewSourceKind.GENERATOR_NATIVE,
                true, "rtf:seed:settings", (x, z) -> {
                    calls.add(x + "," + z);
                    return new TerrainPreviewSample(x, z, 80.0 + x / 8.0 + z / 16.0,
                            z > 0, z > 0 ? "minecraft:river" : "minecraft:plains",
                            z > 0 ? "rtf:river" : "rtf:plains", "rtf:climate_temperate");
                });
        TerrainPreviewProviderSelection selection = new TerrainPreviewProviderSelector(List.of(provider)).select();

        RealmT4CoarseTerrainPreviewService.Result result = new RealmT4CoarseTerrainPreviewService(root)
                .ensure("run_negative", "realm_a", "minecraft:overworld", selection);

        assertFalse(result.cacheHit());
        assertEquals(List.of("-24,-8", "-8,-8", "-24,24"), calls);
        assertEquals(run.resolve("realm_t4_terrain_preview/realm_a_coarse_terrain_evidence.json"),
                result.evidencePath());
        assertTrue(Files.size(result.previewPath()) > 0L);
        assertNotNull(ImageIO.read(result.previewPath().toFile()));
        JsonObject evidence = result.evidence();
        assertEquals(RealmT4CoarseTerrainPreviewService.SCHEMA,
                evidence.get("schema").getAsString());
        assertTrue(evidence.get("advisoryOnly").getAsBoolean());
        assertEquals("city_d3_site_review", evidence.get("requiredNextGate").getAsString());
        assertEquals(3, evidence.getAsJsonArray("cells").size());
        assertEquals(3, evidence.getAsJsonObject("grid").get("ownedCellCount").getAsInt());
        assertEquals("owned_cell_center_once",
                evidence.getAsJsonObject("grid").get("samplingPattern").getAsString());
        assertEquals(-2, evidence.getAsJsonObject("grid").get("minGridX").getAsInt());
        assertEquals(1, evidence.getAsJsonObject("grid").get("maxGridZ").getAsInt());
        JsonObject first = evidence.getAsJsonArray("cells").get(0).getAsJsonObject();
        assertEquals(-24, first.get("blockX").getAsInt());
        assertEquals(-8, first.get("blockZ").getAsInt());
        assertEquals(1, first.get("neighborCount").getAsInt());
        assertTrue(evidence.getAsJsonObject("summary").get("robustRelief").getAsDouble() >= 0.0);
        assertEquals(1.0 / 3.0, evidence.getAsJsonObject("summary").get("waterFrac").getAsDouble(), 0.0001);
        assertEquals(2, evidence.getAsJsonObject("summary").getAsJsonObject("terrainIdHistogram")
                .get("rtf:plains").getAsInt());
        assertEquals(3, evidence.getAsJsonObject("summary").getAsJsonObject("sourceBiomeIdHistogram")
                .get("rtf:climate_temperate").getAsInt());

        JsonObject persisted = JsonParser.parseString(Files.readString(result.evidencePath())).getAsJsonObject();
        assertEquals(result.evidence().get("sourceIdentity").getAsString(),
                persisted.get("sourceIdentity").getAsString());
        assertEquals("realm_t4_terrain_preview/realm_a_coarse_height_water_preview.png",
                persisted.getAsJsonObject("artifacts").get("heightWaterPreview").getAsString());
    }

    @Test
    void cacheBindsTerritoryProviderFingerprintAndGridWhilePreservingFallbackProvenance() throws Exception {
        Path root = tempDir.resolve("realm_debug");
        Path run = writeRun(root, "run_cache", 32, List.of(
                territory(0, 0, "realm_a", "owned"),
                territory(1, 0, "realm_a", "owned")
        ));
        int[] calls = {0};
        TerrainPreviewProvider fallback = provider("current_atlas_sampler", TerrainPreviewSourceKind.GIS_ATLAS_SAMPLER,
                false, "world:seed:settings:v1", (x, z) -> {
                    calls[0]++;
                    return new TerrainPreviewSample(x, z, 64 + x, false, "minecraft:plains");
                });
        TerrainPreviewProviderSelection selection = new TerrainPreviewProviderSelection(fallback,
                "current_atlas_sampler", "gis_atlas_sampler", false,
                "rtf_native=runtime_api_unavailable", "world:seed:settings:v1", "surface_height_prior");
        RealmT4CoarseTerrainPreviewService service = new RealmT4CoarseTerrainPreviewService(root);

        RealmT4CoarseTerrainPreviewService.Result first = service.ensure(
                "run_cache", "realm_a", "minecraft:overworld", selection);
        RealmT4CoarseTerrainPreviewService.Result cached = service.ensure(
                "run_cache", "realm_a", "minecraft:overworld", selection);

        assertFalse(first.cacheHit());
        assertTrue(cached.cacheHit());
        assertEquals(2, calls[0], "cache hit must not call the provider again");
        JsonObject provider = cached.evidence().getAsJsonObject("provider");
        assertEquals("current_atlas_sampler", provider.get("providerId").getAsString());
        assertEquals("gis_atlas_sampler", provider.get("sourceKind").getAsString());
        assertFalse(provider.get("fastPath").getAsBoolean());
        assertEquals("rtf_native=runtime_api_unavailable", provider.get("fallbackReason").getAsString());

        JsonObject territory = JsonParser.parseString(Files.readString(run.resolve("realm_territory_map.json")))
                .getAsJsonObject();
        territory.getAsJsonArray("territoryCells").add(territory(2, 0, "realm_a", "owned"));
        Files.writeString(run.resolve("realm_territory_map.json"), territory.toString());
        RealmT4CoarseTerrainPreviewService.Result territoryChanged = service.ensure(
                "run_cache", "realm_a", "minecraft:overworld", selection);
        assertFalse(territoryChanged.cacheHit());
        assertEquals(5, calls[0], "changed territory must resample all current owned cells");

        TerrainPreviewProviderSelection changedFingerprint = new TerrainPreviewProviderSelection(fallback,
                "current_atlas_sampler", "gis_atlas_sampler", false,
                "rtf_native=runtime_api_unavailable", "world:seed:settings:v2", "surface_height_prior");
        RealmT4CoarseTerrainPreviewService.Result providerChanged = service.ensure(
                "run_cache", "realm_a", "minecraft:overworld", changedFingerprint);
        assertFalse(providerChanged.cacheHit());
        assertEquals(8, calls[0]);

        JsonObject context = JsonParser.parseString(Files.readString(run.resolve("world_survey_context.json")))
                .getAsJsonObject();
        context.addProperty("cellStepBlocks", 64);
        Files.writeString(run.resolve("world_survey_context.json"), context.toString());
        RealmT4CoarseTerrainPreviewService.Result gridChanged = service.ensure(
                "run_cache", "realm_a", "minecraft:overworld", changedFingerprint);
        assertFalse(gridChanged.cacheHit());
        assertEquals(11, calls[0]);
        assertEquals(64, gridChanged.evidence().getAsJsonObject("grid").get("cellStepBlocks").getAsInt());
    }

    private static Path writeRun(Path root, String runId, int step, List<JsonObject> territoryCells) throws Exception {
        Path run = root.resolve(runId);
        Files.createDirectories(run);
        JsonObject context = new JsonObject();
        context.addProperty("sealed", true);
        context.addProperty("dimensionId", "minecraft:overworld");
        context.addProperty("cellStepBlocks", step);
        Files.writeString(run.resolve("world_survey_context.json"), context.toString());
        JsonObject territory = new JsonObject();
        territory.addProperty("territoryMapId", "territory_" + runId);
        JsonArray cells = new JsonArray();
        territoryCells.forEach(cells::add);
        territory.add("territoryCells", cells);
        Files.writeString(run.resolve("realm_territory_map.json"), territory.toString());
        return run;
    }

    private static JsonObject territory(int x, int z, String realmId, String status) {
        JsonObject result = new JsonObject();
        result.addProperty("gridX", x);
        result.addProperty("gridZ", z);
        result.addProperty("realmId", realmId);
        result.addProperty("status", status);
        return result;
    }

    private static TerrainPreviewProvider provider(String providerId, TerrainPreviewSourceKind sourceKind,
            boolean fastPath, String fingerprint, SampleFunction sample) {
        TerrainPreviewProviderDescriptor descriptor = new TerrainPreviewProviderDescriptor(
                providerId, sourceKind, fastPath, fingerprint);
        return new TerrainPreviewProvider() {
            @Override
            public TerrainPreviewProviderDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public TerrainPreviewProviderAvailability availability() {
                return TerrainPreviewProviderAvailability.ready();
            }

            @Override
            public TerrainPreviewSample sample(int blockX, int blockZ) {
                return sample.sample(blockX, blockZ);
            }
        };
    }

    @FunctionalInterface
    private interface SampleFunction {
        TerrainPreviewSample sample(int blockX, int blockZ);
    }
}
