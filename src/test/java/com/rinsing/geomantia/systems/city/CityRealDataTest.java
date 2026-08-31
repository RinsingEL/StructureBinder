package com.rinsing.geomantia.systems.city;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.application.CityLandformReviewBuilder;
import com.rinsing.geomantia.systems.city.application.CitySiteContextBuilder;
import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.CitySiteContext;
import com.rinsing.geomantia.systems.city.domain.model.TerritoryCheckResult;
import com.rinsing.geomantia.systems.city.infrastructure.preview.CityLandformReviewMapRenderer;
import com.rinsing.geomantia.systems.gis.application.refresh.GisRefreshService;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshResult;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegionStore;
import com.rinsing.geomantia.systems.gis.testsupport.GisTestCase;
import com.rinsing.geomantia.systems.gis.testsupport.SyntheticAtlasSampler;
import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CityRealDataTest {

    private final CityPlanningConfig config = CityPlanningConfig.defaults();
    private final CitySiteContextBuilder siteBuilder = new CitySiteContextBuilder(config);
    private final CityLandformReviewBuilder reviewBuilder = new CityLandformReviewBuilder(config);

    @TempDir
    Path tempDir;

    @Test
    void d2_fromRealSeed_producesValidContext() throws Exception {
        // Load actual city seed from the latest run
        Path runDir = Path.of("run/realm_debug/realm_reterraforged_t4_crop_4096_20260614");
        Path registryPath = runDir.resolve("city_seed_registry.json");
        Path manifestPath = runDir.resolve("world_survey_manifest.json");
        if (!Files.exists(registryPath)) {
            return; // Skip if real data not available
        }
        assertTrue(Files.exists(manifestPath), "Real run must keep world_survey_manifest.json");

        String json = Files.readString(registryPath);
        JsonObject registry = JsonParser.parseString(json).getAsJsonObject();
        JsonObject manifest = JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();
        JsonObject runConfig = manifest.getAsJsonObject("config");
        JsonArray seeds = registry.getAsJsonArray("citySeeds");
        assertNotNull(seeds);
        assertTrue(seeds.size() > 0, "Should have at least one city seed");

        // Use the first city seed
        JsonObject seed = seeds.get(0).getAsJsonObject();
        String cityId = seed.get("citySeedId").getAsString();
        String realmId = seed.get("realmId").getAsString();
        String role = seed.get("role").getAsString();
        String scale = seed.get("theoreticalScale").getAsString();
        int anchorBlockX = seed.getAsJsonObject("anchorBlock").get("x").getAsInt();
        int anchorBlockZ = seed.getAsJsonObject("anchorBlock").get("z").getAsInt();
        int planningRadiusCells = seed.get("planningRadiusCells").getAsInt();
        int gisCellStep = runConfig.get("cellStepBlocks").getAsInt();
        String dimensionId = runConfig.get("dimensionId").getAsString();

        List<CitySiteContextBuilder.TerritoryCellRef> territoryCells = ownedTerritoryCells(runDir, realmId);
        assertFalse(territoryCells.isEmpty(), "Real run should provide owned territory cells for the seed realm");

        CitySiteContext ctx = siteBuilder.build(
                cityId, realmId, dimensionId,
                cityId, seed.get("candidateId").getAsString(),
                anchorBlockX, anchorBlockZ,
                role, scale,
                planningRadiusCells, gisCellStep,
                territoryCells);

        assertNotNull(ctx);
        assertEquals(cityId, ctx.cityId());
        assertEquals(realmId, ctx.realmId());
        assertEquals(dimensionId, ctx.dimensionId());
        assertEquals(anchorBlockX, ctx.anchorBlock().x());
        assertEquals(anchorBlockZ, ctx.anchorBlock().z());
        assertTrue(ctx.planningRadiusBlocks() > 0);
        assertNotEquals(TerritoryCheckResult.UNKNOWN, ctx.territoryCheckResult(),
                "Real run territory map should be wired into D2");

        JsonObject ctxJson = ctx.asJson();
        assertTrue(ctxJson.has("schema"));
        assertTrue(ctxJson.has("grid"));
        assertTrue(ctxJson.has("bounds"));
        assertTrue(ctxJson.has("entryCandidates"));

        System.out.println("D2 real data test passed for seed: " + cityId);
        System.out.println("  Anchor block: (" + anchorBlockX + ", " + anchorBlockZ + ")");
        System.out.println("  Planning radius: " + ctx.planningRadiusBlocks() + " blocks");
        System.out.println("  Grid cell step: " + ctx.grid().cellStepBlocks());
        System.out.println("  W/T cell step: " + gisCellStep);
        System.out.println("  Territory check: " + ctx.territoryCheckResult());
        System.out.println("  Scale: " + scale + ", role: " + role);
    }

    @Test
    void d3_synthetic_fullPipeline_producesCompleteJson() throws Exception {
        // Use synthetic terrain for D3 pipeline test
        GisTestCase testCase = GisTestCase.byId("mixed");
        GisSampleConfig sampleConfig = GisSampleConfig.defaults().withCellStepBlocks(8);
        AtlasRegionStore store = new AtlasRegionStore(sampleConfig);
        GisRefreshService gisService = new GisRefreshService(sampleConfig, GisClassifierConfig.defaults(),
                store, new SyntheticAtlasSampler(testCase.profile()));

        RefreshResult refreshResult = gisService.refresh(
                testCase.dimensionId(),
                testCase.centerBlockX(), testCase.centerBlockZ(),
                testCase.radiusChunks(),
                testCase.sampleMode(),
                com.rinsing.geomantia.systems.gis.application.refresh.RefreshPriority.DEBUG,
                tempDir.resolve("city_synthetic"));

        assertNotNull(refreshResult.patches());
        assertFalse(refreshResult.patches().isEmpty(), "Synthetic terrain should produce patches");

        // Build D2 context using the synthetic data
        CitySiteContext ctx = siteBuilder.build(
                "city_synth", "realm_synth", "minecraft:overworld",
                "seed_synth", "cand_synth",
                testCase.centerBlockX(), testCase.centerBlockZ(),
                "village", "village",
                16, 4, null);

        // Build D3 with synthetic patches
        CityLandformReviewPackage reviewPkg = reviewBuilder.build(ctx, refreshResult.patches());
        Path imagePath = new CityLandformReviewMapRenderer()
                .render(ctx, reviewPkg, refreshResult.patches(), tempDir.resolve("city_review_map"));
        reviewPkg = reviewPkg.withReviewMap(imagePath.toString(), List.of(imagePath.toString()));

        assertNotNull(reviewPkg);
        assertFalse(reviewPkg.landformPatches().isEmpty());
        assertFalse(reviewPkg.legend().isEmpty());
        assertFalse(reviewPkg.planningContext().isEmpty());
        assertFalse(reviewPkg.reviewMapImage().isBlank());
        assertTrue(Files.exists(imagePath));
        assertTrue(Files.size(imagePath) > 0);

        // Verify JSON output
        JsonObject pkgJson = reviewPkg.asJson();
        assertTrue(pkgJson.has("landformPatches"));
        JsonArray patchArr = pkgJson.getAsJsonArray("landformPatches");
        assertTrue(patchArr.size() > 0);

        // Each patch should have all required fields
        JsonObject firstPatch = patchArr.get(0).getAsJsonObject();
        assertTrue(firstPatch.has("landformPatchId"));
        assertTrue(firstPatch.has("mapLabel"));
        assertTrue(firstPatch.has("displayLandformName"));
        assertTrue(firstPatch.has("landformType"));
        assertTrue(firstPatch.has("areaClass"));
        assertTrue(firstPatch.has("metricsSummary"));
        assertTrue(firstPatch.has("summaryFacts"));

        assertFalse(firstPatch.get("mapLabel").getAsString().isEmpty(),
                "mapLabel should not be empty");

        System.out.println("D3 synthetic test produced " + patchArr.size() + " patch summaries");
        System.out.println("  Labels: " + patchArr.toString());
    }

    private static List<CitySiteContextBuilder.TerritoryCellRef> ownedTerritoryCells(Path runDir,
                                                                                    String realmId) throws Exception {
        Path territoryPath = runDir.resolve("realm_territory_map.json");
        if (!Files.exists(territoryPath)) {
            return List.of();
        }

        JsonObject territoryMap = JsonParser.parseString(Files.readString(territoryPath)).getAsJsonObject();
        JsonArray cells = territoryMap.getAsJsonArray("territoryCells");
        List<CitySiteContextBuilder.TerritoryCellRef> result = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            JsonObject cell = cells.get(i).getAsJsonObject();
            if (!realmId.equals(cell.get("realmId").getAsString())) {
                continue;
            }
            if (!"owned".equalsIgnoreCase(cell.get("status").getAsString())) {
                continue;
            }
            result.add(new CitySiteContextBuilder.TerritoryCellRef(
                    cell.get("gridX").getAsInt(),
                    cell.get("gridZ").getAsInt()));
        }
        return result;
    }
}
