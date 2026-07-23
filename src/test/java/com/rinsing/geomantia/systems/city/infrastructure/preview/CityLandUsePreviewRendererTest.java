package com.rinsing.geomantia.systems.city.infrastructure.preview;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.landuse.BoundaryPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.CardinalDirection;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.VegetationPolicy;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        LandUseAreaPlan plan = new LandUseAreaPlan(LandUseAreaPlan.CURRENT_SCHEMA_VERSION,
                "city_land_use_rules.v0.1", "city_preview", "hash", bounds, List.of(area),
                List.of(new LandUseAreaPlan.ScanlineSpan(0, 0, 31)),
                List.of(new LandUseAreaPlan.CorridorExclusion("farm_corridor",
                        new BlockBounds(12, 5, 12, 8), "farm_gate")), List.of());

        JsonObject metadata = new CityLandUsePreviewRenderer().render(terrain, plan, tempDir);

        Path output = tempDir.resolve("land_use_preview.png");
        assertTrue(Files.isRegularFile(output));
        assertEquals("city_land_use_preview.v0.1", metadata.get("schemaVersion").getAsString());
        assertEquals(1, metadata.get("logicalAreaCount").getAsInt());
        BufferedImage image = ImageIO.read(output.toFile());
        assertNotNull(image);
        assertEquals(CityLandUsePreviewRenderer.WIDTH, image.getWidth());
        assertEquals(CityLandUsePreviewRenderer.HEIGHT, image.getHeight());
        assertTrue(nonCanvasPixels(image) > 100_000);
    }

    private static LandUseTerrainField terrain(BlockBounds bounds) {
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        for (int z = 0; z < 8; z++) {
            for (int x = 0; x < 8; x++) {
                cells.add(new LandUseTerrainField.Cell(x, z, x * 4, z * 4, 4,
                        68 + z, x / 3.0, 1, 0.5, z == 7, z == 7 ? 2 : 0, z == 7 ? 0 : 20,
                        x < 2 ? "minecraft:forest" : "minecraft:plains", "plain", "p", true));
            }
        }
        return new LandUseTerrainField(LandUseTerrainField.CURRENT_SCHEMA_VERSION,
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
