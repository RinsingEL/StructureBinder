package com.rinsing.geomantia.systems.city.infrastructure.preview;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlanner;
import com.rinsing.geomantia.systems.city.application.outdoor.CityUrbanSpacePlan;
import com.rinsing.geomantia.systems.city.domain.landuse.BoundaryPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.CardinalDirection;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.landuse.LandscapeFillProgram;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.VegetationPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRule;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityLandUsePreviewRendererTest {
    @Test
    void rendersTerrainAreasExclusionsCorridorBoundaryAndGate(@TempDir Path tempDir) throws Exception {
        BlockBounds bounds = new BlockBounds(0, 0, 31, 31);
        LandUseTerrainField terrain = terrain(bounds);
        LandUseAreaPlan.Area area = new LandUseAreaPlan.Area("farmstead", "agriculture", "agriculture",
                List.of("farm_group"), List.of("farmhouse"), List.of(new BlockPoint(8, 8)),
                List.of(new LandUseAreaPlan.ScanlineSpan(8, 4, 20),
                        new LandUseAreaPlan.ScanlineSpan(9, 4, 20),
                        new LandUseAreaPlan.ScanlineSpan(10, 4, 20)),
                List.of(new BlockBounds(8, 8, 10, 10)),
                List.of(new LandUseAreaPlan.BoundaryLoop(List.of(new BlockPoint(4, 8),
                        new BlockPoint(21, 8), new BlockPoint(21, 11), new BlockPoint(4, 11)), false)),
                List.of(new LandUseAreaPlan.GateSlot("farm_gate", new BlockPoint(12, 8),
                        CardinalDirection.NORTH, "farmhouse")), 42,
                SurfacePolicy.CULTIVATE, VegetationPolicy.CLEAR, BoundaryPolicy.FENCE, "agriculture");
        LandUseAreaPlan plan = new LandUseAreaPlan(LandUseAreaPlan.SCHEMA,
                "city_land_use_rules", "city_preview", "hash", bounds, List.of(area),
                List.of(new LandUseAreaPlan.ScanlineSpan(0, 0, 31)),
                List.of(new LandUseAreaPlan.CorridorExclusion("farm_corridor",
                        new BlockBounds(12, 5, 12, 8), "farm_gate")), List.of());

        JsonObject metadata = new CityLandUsePreviewRenderer().render(terrain, plan, tempDir);

        Path output = tempDir.resolve("land_use_preview.png");
        assertTrue(Files.isRegularFile(output));
        assertEquals("city_land_use_preview", metadata.get("schema").getAsString());
        assertEquals(1, metadata.get("logicalAreaCount").getAsInt());
        BufferedImage image = ImageIO.read(output.toFile());
        assertNotNull(image);
        assertEquals(CityLandUsePreviewRenderer.WIDTH, image.getWidth());
        assertEquals(CityLandUsePreviewRenderer.HEIGHT, image.getHeight());
        assertTrue(nonCanvasPixels(image) > 100_000);
        assertTrue(!metadata.has("envelopeBlocks"), "legacy metadata must remain v0.1-shaped");
    }

    @Test
    void rendersUrbanEnvelopeResidualClassesAndUnknownWarning(@TempDir Path tempDir) throws Exception {
        BlockBounds bounds = new BlockBounds(0, 0, 31, 31);
        LandUseTerrainField terrain = terrain(bounds);
        LandUseAreaPlan plan = plan(bounds);
        List<LandUseAreaPlan.ScanlineSpan> envelope = new ArrayList<>();
        for (int z = 4; z <= 27; z++) envelope.add(new LandUseAreaPlan.ScanlineSpan(z, 4, 27));
        List<CityUrbanSpacePlan.ResidualRegion> residuals = new ArrayList<>();
        CityUrbanSpacePlan.ResidualDisposition[] dispositions =
                CityUrbanSpacePlan.ResidualDisposition.values();
        for (int index = 0; index < dispositions.length; index++) {
            residuals.add(new CityUrbanSpacePlan.ResidualRegion("residual_" + index,
                    CityUrbanSpacePlan.ResidualClass.SMALL_ENCLOSED, dispositions[index],
                    List.of(new LandUseAreaPlan.ScanlineSpan(14 + index * 2, 22, 24)),
                    List.of("farm_group"),
                    dispositions[index] == CityUrbanSpacePlan.ResidualDisposition.ABSORB_NEIGHBOR
                            ? "farm_group" : "",
                    3, false, false));
        }
        CityUrbanSpacePlan urban = new CityUrbanSpacePlan(CityUrbanSpacePlan.SCHEMA,
                "city_preview", "sha256:test", true, 6, bounds, envelope, residuals,
                new CityUrbanSpacePlan.CoverageSummary(576, 120, 9, 4, 3, 12, 4));

        JsonObject metadata = new CityLandUsePreviewRenderer().render(terrain, plan, urban, tempDir);

        assertEquals("city_land_use_preview", metadata.get("schema").getAsString());
        assertEquals("sha256:test", metadata.get("urbanSpacePlanHash").getAsString());
        assertEquals(576, metadata.get("envelopeBlocks").getAsInt());
        assertEquals(3, metadata.get("absorbedResidualBlocks").getAsInt());
        assertEquals(12, metadata.get("explicitResidualBlocks").getAsInt());
        assertEquals(4, metadata.get("unknownResidualBlocks").getAsInt());
        assertTrue(metadata.get("unknownResidualWarning").getAsBoolean());
        assertEquals("UNKNOWN_RESIDUAL_PRESENT", metadata.get("coverageStatus").getAsString());
        JsonObject dispositionCounts = metadata.getAsJsonObject("residualDispositionBlocks");
        for (CityUrbanSpacePlan.ResidualDisposition disposition : dispositions) {
            assertEquals(3, dispositionCounts.get(disposition.name()).getAsInt());
        }
        BufferedImage image = ImageIO.read(tempDir.resolve("land_use_preview.png").toFile());
        assertNotNull(image);
        assertTrue(nonCanvasPixels(image) > 100_000);
    }

    @Test
    void overlaysFrozenRelayRegionsAndProvenance(@TempDir Path tempDir) throws Exception {
        BlockBounds bounds = new BlockBounds(0, 0, 31, 31);
        LandUseTerrainField terrain = terrain(bounds);
        LandUseAreaPlan plan = plan(bounds);
        LandUseSurfaceSettings settings = LandUseSurfaceSettings.defaults(SurfacePolicy.CULTIVATE)
                .forRelayRegionGrowth();
        List<CityLandUseSurfacePrintPlan.RegionSpan> regionSpans = new ArrayList<>();
        for (int z = 8; z <= 10; z++) {
            regionSpans.add(new CityLandUseSurfacePrintPlan.RegionSpan(z, 4, 11, "r1", "role:flower"));
            regionSpans.add(new CityLandUseSurfacePrintPlan.RegionSpan(z, 12, 12, "r2", "role:leaf"));
            regionSpans.add(new CityLandUseSurfacePrintPlan.RegionSpan(z, 13, 13, "r3", "role:water"));
            regionSpans.add(new CityLandUseSurfacePrintPlan.RegionSpan(z, 14, 20, "r4", "role:flower"));
        }
        BlockPoint source = new BlockPoint(4, 8);
        CityLandUseSurfacePrintPlan.RelayRegionGrowthRecipe recipe =
                new CityLandUseSurfacePrintPlan.RelayRegionGrowthRecipe(
                        settings.surfaceBlockId(), settings.cropBlockId(), settings.channelBankBlockId(),
                        settings.channelWaterBlockId(), settings.channelBankOverlayBlockId(), "",
                        "fill:flower_leaf", "role:flower", 17L, source,
                        List.of(new CityLandUseSurfacePrintPlan.RelayRoleDefinition("role:flower",
                                        LandscapeFillProgram.MaterialRole.PRIMARY_CONTENT,
                                        LandscapeFillProgram.GrowthForm.PATCH, 24 / 51.0),
                                new CityLandUseSurfacePrintPlan.RelayRoleDefinition("role:leaf",
                                        LandscapeFillProgram.MaterialRole.BANK,
                                        LandscapeFillProgram.GrowthForm.CORRIDOR, 3 / 51.0),
                                new CityLandUseSurfacePrintPlan.RelayRoleDefinition("role:water",
                                        LandscapeFillProgram.MaterialRole.WATER,
                                        LandscapeFillProgram.GrowthForm.CORRIDOR, 3 / 51.0),
                                new CityLandUseSurfacePrintPlan.RelayRoleDefinition("role:flower",
                                        LandscapeFillProgram.MaterialRole.PRIMARY_CONTENT,
                                        LandscapeFillProgram.GrowthForm.PATCH, 21 / 51.0)),
                        List.of(new CityLandUseSurfacePrintPlan.RelayContentWeight("content:poppy", 1)),
                        regionSpans,
                        List.of(new CityLandUseSurfacePrintPlan.RegionTrace("r1", "", "role:flower",
                                        LandscapeFillProgram.GrowthForm.PATCH, source, null, 24, 24),
                                new CityLandUseSurfacePrintPlan.RegionTrace("r2", "r1", "role:leaf",
                                        LandscapeFillProgram.GrowthForm.CORRIDOR, new BlockPoint(12, 8),
                                        new BlockPoint(11, 8), 3, 3),
                                new CityLandUseSurfacePrintPlan.RegionTrace("r3", "r2", "role:water",
                                        LandscapeFillProgram.GrowthForm.CORRIDOR, new BlockPoint(13, 8),
                                        new BlockPoint(12, 8), 3, 3),
                                new CityLandUseSurfacePrintPlan.RegionTrace("r4", "r3", "role:flower",
                                        LandscapeFillProgram.GrowthForm.PATCH, new BlockPoint(14, 8),
                                        new BlockPoint(13, 8), 21, 21)));
        CityLandUseSurfacePrintPlan surfacePlan = new CityLandUseSurfacePrintPlan(
                CityLandUseSurfacePrintPlan.SCHEMA, plan.cityId(), plan.planHash(), "",
                List.of(new CityLandUseSurfacePrintPlan.AreaPrint("farm/surface", "farmstead",
                        List.of("farm_group"), settings, plan.areas().get(0).memberSpans(), List.of(),
                        LandUseSurfaceSettings.SurfaceAlgorithm.RELAY_REGION_GROWTH, source, recipe)));

        JsonObject metadata = new CityLandUsePreviewRenderer().render(terrain, plan, surfacePlan, tempDir);

        assertEquals("city_land_use_preview", metadata.get("schema").getAsString());
        assertEquals(1, metadata.get("relayGrowthAreaCount").getAsInt());
        assertEquals(CityLandUseSurfacePrintPlan.SCHEMA,
                metadata.get("surfacePrintPlanSchema").getAsString());
        JsonObject fillArea = metadata.getAsJsonArray("relayGrowthAreas").get(0).getAsJsonObject();
        assertEquals("fill:flower_leaf", fillArea.get("fillProfileRef").getAsString());
        assertEquals(51, fillArea.get("actualBlockCount").getAsInt());
        assertEquals(3, fillArea.getAsJsonArray("roles").size());
        assertEquals(4, fillArea.getAsJsonArray("regions").size());
        double actualShareSum = fillArea.getAsJsonArray("roles").asList().stream()
                .mapToDouble(role -> role.getAsJsonObject().get("actualShare").getAsDouble()).sum();
        assertEquals(1.0, actualShareSum, 0.000001);
        BufferedImage image = ImageIO.read(tempDir.resolve("land_use_preview.png").toFile());
        assertNotNull(image);
        assertTrue(nonCanvasPixels(image) > 100_000);
    }

    @Test
    void rendersReviewFixtureWithDifferentLandscapeShapesAndFillPrograms(@TempDir Path tempDir) throws Exception {
        BlockBounds bounds = new BlockBounds(0, 0, 127, 127);
        List<LandUseAreaPlan.ScanlineSpan> farmSpans = organicSpans(34, 34, 29, 23, 3);
        List<LandUseAreaPlan.ScanlineSpan> flowerSpans = organicSpans(94, 29, 25, 20, 7);
        List<LandUseAreaPlan.ScanlineSpan> woodlandSpans = organicSpans(82, 86, 35, 24, 11);
        LandUseAreaPlan.Area farm = landscapeArea("farm", "farm_group", farmSpans,
                new BlockBounds(31, 31, 34, 34));
        LandUseAreaPlan.Area flowers = landscapeArea("flowers", "flower_group", flowerSpans,
                new BlockBounds(91, 26, 94, 29));
        LandUseAreaPlan.Area woodland = landscapeArea("woodland", "woodland_group", woodlandSpans,
                new BlockBounds(79, 83, 82, 86));
        LandUseAreaPlan plan = new LandUseAreaPlan(LandUseAreaPlan.SCHEMA,
                "city_land_use_rules", "city_preview", "landscape-review-hash", bounds,
                List.of(farm, flowers, woodland), List.of(), List.of(), List.of());
        LandUseSurfaceSettings settings = LandUseSurfaceSettings.defaults(SurfacePolicy.CULTIVATE)
                .forRelayRegionGrowth();
        List<LandUseSeedGroup> groups = List.of(
                landscapeGroup("farm_group", farm.structureFootprintExclusions().get(0), settings,
                        fill("fill:irrigated_fields", "role:cultivated",
                                List.of("role:cultivated", "role:bank", "role:water", "role:bank",
                                        "role:cultivated"),
                                List.of(role("role:cultivated", LandscapeFillProgram.MaterialRole.PRIMARY_CONTENT, 0.82),
                                        role("role:bank", LandscapeFillProgram.MaterialRole.BANK, 0.12),
                                        role("role:water", LandscapeFillProgram.MaterialRole.WATER, 0.06)),
                                List.of(new LandscapeFillProgram.ContentWeight("content:wheat", 3),
                                        new LandscapeFillProgram.ContentWeight("content:carrot", 1)), 3103L)),
                landscapeGroup("flower_group", flowers.structureFootprintExclusions().get(0), settings,
                        fill("fill:flower_leaf_islands", "role:flower",
                                List.of("role:flower", "role:leaf", "role:flower"),
                                List.of(role("role:flower", LandscapeFillProgram.MaterialRole.PRIMARY_CONTENT, 0.82),
                                        role("role:leaf", LandscapeFillProgram.MaterialRole.BANK, 0.18)),
                                List.of(new LandscapeFillProgram.ContentWeight("content:poppy", 2),
                                        new LandscapeFillProgram.ContentWeight("content:cornflower", 1)), 7707L)),
                landscapeGroup("woodland_group", woodland.structureFootprintExclusions().get(0), settings,
                        fill("fill:woodland_breaks", "role:trees",
                                List.of("role:trees", "role:shrub", "role:gravel", "role:shrub", "role:trees"),
                                List.of(role("role:trees", LandscapeFillProgram.MaterialRole.PRIMARY_CONTENT, 0.67),
                                        role("role:shrub", LandscapeFillProgram.MaterialRole.BANK, 0.20),
                                        role("role:gravel", LandscapeFillProgram.MaterialRole.GROUND, 0.13)),
                                List.of(new LandscapeFillProgram.ContentWeight("content:oak", 3),
                                        new LandscapeFillProgram.ContentWeight("content:birch", 1)), 11111L)));

        CityLandUseSurfacePrintPlan surfacePlan = new CityLandUseSurfacePrintPlanner().plan(
                plan, groups, terrain(bounds));
        JsonObject metadata = new CityLandUsePreviewRenderer().render(
                terrain(bounds), plan, surfacePlan, tempDir);

        assertEquals(3, metadata.get("relayGrowthAreaCount").getAsInt());
        assertEquals(3, metadata.getAsJsonArray("relayGrowthAreas").size());
        assertTrue(metadata.getAsJsonArray("relayGrowthAreas").asList().stream()
                .map(value -> value.getAsJsonObject().get("fillProfileRef").getAsString())
                .distinct().count() == 3);
        assertEquals(3, surfacePlan.areas().size());
        assertTrue(surfacePlan.areas().stream().map(CityLandUseSurfacePrintPlan.AreaPrint::recipe)
                .map(CityLandUseSurfacePrintPlan.RelayRegionGrowthRecipe.class::cast)
                .allMatch(recipe -> recipe.regionTraces().size() >= 3
                        && recipe.regionTraces().stream().skip(1)
                        .allMatch(trace -> trace.sourceFrontier() != null)));
        Path image = tempDir.resolve("land_use_preview.png");
        assertTrue(Files.isRegularFile(image));
        String exportPath = System.getenv("GEOMANTIA_LANDSCAPE_PREVIEW_OUTPUT");
        if (exportPath != null && !exportPath.isBlank()) {
            Path destination = Path.of(exportPath).toAbsolutePath().normalize();
            Files.createDirectories(destination.getParent());
            Files.copy(image, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static LandUseAreaPlan plan(BlockBounds bounds) {
        LandUseAreaPlan.Area area = new LandUseAreaPlan.Area("farmstead", "agriculture", "agriculture",
                List.of("farm_group"), List.of("farmhouse"), List.of(new BlockPoint(8, 8)),
                List.of(new LandUseAreaPlan.ScanlineSpan(8, 4, 20),
                        new LandUseAreaPlan.ScanlineSpan(9, 4, 20),
                        new LandUseAreaPlan.ScanlineSpan(10, 4, 20)),
                List.of(new BlockBounds(8, 8, 10, 10)),
                List.of(new LandUseAreaPlan.BoundaryLoop(List.of(new BlockPoint(4, 8),
                        new BlockPoint(21, 8), new BlockPoint(21, 11), new BlockPoint(4, 11)), false)),
                List.of(new LandUseAreaPlan.GateSlot("farm_gate", new BlockPoint(12, 8),
                        CardinalDirection.NORTH, "farmhouse")), 42,
                SurfacePolicy.CULTIVATE, VegetationPolicy.CLEAR, BoundaryPolicy.FENCE, "agriculture");
        return new LandUseAreaPlan(LandUseAreaPlan.SCHEMA,
                "city_land_use_rules", "city_preview", "hash", bounds, List.of(area),
                List.of(new LandUseAreaPlan.ScanlineSpan(0, 0, 31)),
                List.of(new LandUseAreaPlan.CorridorExclusion("farm_corridor",
                        new BlockBounds(12, 5, 12, 8), "farm_gate")), List.of());
    }

    private static LandUseAreaPlan.Area landscapeArea(String areaId,
                                                       String groupId,
                                                       List<LandUseAreaPlan.ScanlineSpan> spans,
                                                       BlockBounds footprint) {
        int blockCount = spans.stream().mapToInt(span -> span.maxX() - span.minX() + 1).sum();
        return new LandUseAreaPlan.Area(areaId, areaId, areaId, List.of(groupId), List.of(groupId),
                List.of(new BlockPoint(footprint.minX() - 1, footprint.minZ())), spans, List.of(footprint),
                List.of(), List.of(), blockCount, SurfacePolicy.CULTIVATE, VegetationPolicy.PRESERVE,
                BoundaryPolicy.OPEN, areaId);
    }

    private static LandUseSeedGroup landscapeGroup(String groupId,
                                                    BlockBounds footprint,
                                                    LandUseSurfaceSettings settings,
                                                    LandscapeFillProgram fill) {
        LandUseRule rule = new LandUseRule(groupId, groupId, List.of(groupId), 1, 0, 1,
                4_000, 4_000, 1, 0, 0, 10, 0, 1, false, SurfacePolicy.CULTIVATE,
                VegetationPolicy.PRESERVE, BoundaryPolicy.OPEN, groupId);
        return new LandUseSeedGroup(groupId, rule, settings, List.of(groupId), List.of(footprint),
                List.of(new BlockPoint(footprint.minX() - 8, footprint.minZ())), List.of(),
                1, 100, 4_000, 4_000, 1, List.of(), LandUseSeedGroup.GrowthBias.neutral(),
                LandUseSeedGroup.TerrainBias.BALANCED, List.of(), LandUseSeedGroup.LayerRole.LANDSCAPE,
                null, fill);
    }

    private static LandscapeFillProgram fill(String profileRef,
                                              String primaryRoleRef,
                                              List<String> sequence,
                                              List<LandscapeFillProgram.RoleDefinition> roles,
                                              List<LandscapeFillProgram.ContentWeight> content,
                                              long seed) {
        Map<String, Long> occurrences = sequence.stream()
                .collect(java.util.stream.Collectors.groupingBy(value -> value,
                        LinkedHashMap::new, java.util.stream.Collectors.counting()));
        List<LandscapeFillProgram.RoleDefinition> stages = sequence.stream().map(roleRef -> {
            LandscapeFillProgram.RoleDefinition role = roles.stream()
                    .filter(candidate -> candidate.roleRef().equals(roleRef)).findFirst().orElseThrow();
            return new LandscapeFillProgram.RoleDefinition(role.roleRef(), role.materialRole(),
                    role.growthForm(), role.targetShare() / occurrences.get(roleRef));
        }).toList();
        return new LandscapeFillProgram(profileRef, primaryRoleRef, stages, content, seed);
    }

    private static LandscapeFillProgram.RoleDefinition role(
            String roleRef,
            LandscapeFillProgram.MaterialRole materialRole,
            double share) {
        LandscapeFillProgram.GrowthForm form = materialRole == LandscapeFillProgram.MaterialRole.PRIMARY_CONTENT
                ? LandscapeFillProgram.GrowthForm.PATCH : LandscapeFillProgram.GrowthForm.CORRIDOR;
        return new LandscapeFillProgram.RoleDefinition(roleRef, materialRole, form, share);
    }

    private static List<LandUseAreaPlan.ScanlineSpan> organicSpans(
            int centerX, int centerZ, int radiusX, int radiusZ, int phase) {
        List<LandUseAreaPlan.ScanlineSpan> spans = new ArrayList<>();
        int wobble = 0;
        int drift = 0;
        for (int z = centerZ - radiusZ; z <= centerZ + radiusZ; z++) {
            wobble = Math.max(-3, Math.min(3, wobble
                    + Math.floorMod(z * 37 + phase * 53, 3) - 1));
            drift = Math.max(-4, Math.min(4, drift
                    + Math.floorMod(z * 19 + phase * 31, 3) - 1));
            int taper = Math.max(0, Math.abs(z - centerZ) - radiusZ / 2);
            int halfWidth = Math.max(4, radiusX - taper + wobble);
            spans.add(new LandUseAreaPlan.ScanlineSpan(z,
                    centerX - halfWidth + drift, centerX + halfWidth + drift));
        }
        return List.copyOf(spans);
    }

    private static LandUseTerrainField terrain(BlockBounds bounds) {
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        int maxCellX = Math.floorDiv(bounds.maxX(), 4);
        int maxCellZ = Math.floorDiv(bounds.maxZ(), 4);
        for (int z = 0; z <= maxCellZ; z++) {
            for (int x = 0; x <= maxCellX; x++) {
                cells.add(new LandUseTerrainField.Cell(x, z, x * 4, z * 4, 4,
                        68 + z, x / 3.0, 1, 0.5, z == 7, z == 7 ? 2 : 0, z == 7 ? 0 : 20,
                        x < 2 ? "minecraft:forest" : "minecraft:plains", "plain", "p", true));
            }
        }
        return new LandUseTerrainField(LandUseTerrainField.SCHEMA,
                "city_preview", bounds, 4, cells);
    }

    private static int nonCanvasPixels(BufferedImage image) {
        int canvas = image.getRGB(0, 0);
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) if (image.getRGB(x, y) != canvas) count++;
        }
        return count;
    }
}
