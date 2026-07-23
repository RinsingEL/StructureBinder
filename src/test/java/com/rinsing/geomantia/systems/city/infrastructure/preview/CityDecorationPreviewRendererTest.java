package com.rinsing.geomantia.systems.city.infrastructure.preview;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.dressing.CityDecorationProgramPlanner;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgramPlan;
import com.rinsing.geomantia.systems.city.application.dressing.DecorationSlot;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityDecorationPreviewRendererTest {
    @Test
    void rendersMemberMaskShapesCrossSectionObstaclesAndIndex(@TempDir Path tempDir) throws Exception {
        CompiledDecorationProgram irregular = program("irregular_patch",
                new CompiledDecorationProgram.TargetMask("irregular", List.of(
                        new BlockBounds(0, 0, 23, 5), new BlockBounds(0, 12, 23, 17),
                        new BlockBounds(0, 6, 5, 11), new BlockBounds(18, 6, 23, 11))),
                frame(0, 0), new CompiledDecorationProgram.TargetMaskShape(),
                new CompiledDecorationProgram.UniformFillPattern("grass"),
                palette(Map.of("grass", "geomantia:grass_patch")));
        CompiledDecorationProgram ellipse = program("ellipse_garden",
                mask("ellipse", 40, 0, 64, 24), frame(52, 12),
                new CompiledDecorationProgram.EllipseShape(0, 0, 10, 6),
                new CompiledDecorationProgram.UniformFillPattern("flower"),
                palette(Map.of("flower", "geomantia:flower_mix")));
        CompiledDecorationProgram ring = program("ring_garden",
                mask("ring", 70, 0, 94, 24), frame(82, 12),
                new CompiledDecorationProgram.RingShape(0, 0, 4, 4, 10, 10),
                new CompiledDecorationProgram.UniformFillPattern("path"),
                palette(Map.of("path", "geomantia:gravel_path")));
        Map<String, String> crossContents = new LinkedHashMap<>();
        crossContents.put("fence", "geomantia:oak_fence");
        crossContents.put("field", "geomantia:farmland");
        crossContents.put("water", "geomantia:water_channel");
        CompiledDecorationProgram crossSection = program("farm_cross_section",
                mask("farm", 0, 30, 22, 40), frame(0, 30),
                new CompiledDecorationProgram.TargetMaskShape(),
                new CompiledDecorationProgram.CrossSectionRepeatPattern(CompiledDecorationProgram.Axis.U, 0,
                        List.of(new CompiledDecorationProgram.CrossSectionBand("fence", 1),
                                new CompiledDecorationProgram.CrossSectionBand("field", 3),
                                new CompiledDecorationProgram.CrossSectionBand("water", 1),
                                new CompiledDecorationProgram.CrossSectionBand("field", 3),
                                new CompiledDecorationProgram.CrossSectionBand("fence", 1))),
                palette(crossContents));
        CompiledDecorationProgramPlan.HardObstacle obstacle = new CompiledDecorationProgramPlan.HardObstacle(
                "locked_structure", "manor", new BlockBounds(9, 7, 14, 10));
        CompiledDecorationProgramPlan plan = new CompiledDecorationProgramPlan(
                CompiledDecorationProgramPlan.SCHEMA, "city_test", "catalog_hash", List.of(obstacle),
                List.of(irregular, ellipse, ring, crossSection));
        List<DecorationSlot> slots = new CityDecorationProgramPlanner().project(plan,
                new BlockBounds(0, 0, 94, 40));

        assertTrue(slots.stream().anyMatch(slot -> slot.programId().equals("ellipse_garden")
                && slot.worldAnchor().equals(new BlockPoint(52, 12))));
        assertFalse(slots.stream().anyMatch(slot -> slot.programId().equals("ring_garden")
                && slot.worldAnchor().equals(new BlockPoint(82, 12))));

        CityDecorationPreviewRenderer renderer = new CityDecorationPreviewRenderer();
        JsonObject index = renderer.render(plan, slots, tempDir);

        assertEquals("city_decoration_preview_index.v0.3", index.get("schemaVersion").getAsString());
        assertFalse(index.get("terrainOutcomeAvailable").getAsBoolean());
        assertEquals(4, index.getAsJsonArray("previews").size());
        assertTrue(Files.exists(tempDir.resolve("city_decoration_preview_index.json")));
        for (JsonElement element : index.getAsJsonArray("previews")) {
            JsonObject preview = element.getAsJsonObject();
            Path path = tempDir.resolve(preview.get("fileName").getAsString());
            assertTrue(Files.exists(path));
            BufferedImage image = ImageIO.read(path.toFile());
            assertNotNull(image);
            assertEquals(CityDecorationPreviewRenderer.WIDTH, image.getWidth());
            assertEquals(CityDecorationPreviewRenderer.HEIGHT, image.getHeight());
            assertTrue(nonCanvasPixelCount(image) > 20_000, preview.get("programId").getAsString());
            assertFalse(preview.getAsJsonArray("legend").isEmpty());
            assertFalse(preview.getAsJsonArray("legend").get(0).getAsJsonObject()
                    .getAsJsonArray("contentRefs").isEmpty());
        }

        BufferedImage irregularImage = ImageIO.read(tempDir.resolve(
                "city_decoration_preview_irregular_patch.png").toFile());
        BlockBounds irregularCrop = new BlockBounds(-4, -4, 27, 21);
        CityDecorationPreviewRenderer.Transform irregularTransform =
                CityDecorationPreviewRenderer.transform(irregularCrop);
        int gapRgb = irregularImage.getRGB(centerX(irregularTransform, 7), centerZ(irregularTransform, 8));
        assertEquals(CityDecorationPreviewRenderer.PLOT_COLOR.getRGB(), gapRgb,
                "a gap inside the target bbox must remain blank");
        Color obstaclePixel = new Color(irregularImage.getRGB(centerX(irregularTransform, 10),
                centerZ(irregularTransform, 8)), true);
        assertTrue(obstaclePixel.getRed() > obstaclePixel.getGreen() + 40,
                "hard obstacle hole must be visibly red");

        BufferedImage crossImage = ImageIO.read(tempDir.resolve(
                "city_decoration_preview_farm_cross_section.png").toFile());
        for (String paletteSlotId : List.of("fence", "field", "water")) {
            assertTrue(containsRgb(crossImage, CityDecorationPreviewRenderer.slotColor(paletteSlotId).getRGB()),
                    paletteSlotId + " band color must be present");
        }
        JsonObject crossPreview = index.getAsJsonArray("previews").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(preview -> "farm_cross_section".equals(preview.get("programId").getAsString()))
                .findFirst().orElseThrow();
        assertEquals(3, crossPreview.getAsJsonArray("legend").size());
    }

    @Test
    void rejectsEmptyOrInvalidProjection(@TempDir Path tempDir) {
        CompiledDecorationProgram program = program("invalid",
                mask("full", 0, 0, 9, 9), frame(0, 0),
                new CompiledDecorationProgram.TargetMaskShape(),
                new CompiledDecorationProgram.GridRepeatPattern("point", 4, 4, 0, 0),
                palette(Map.of("point", "geomantia:bench")));
        CompiledDecorationProgramPlan.HardObstacle obstacle = new CompiledDecorationProgramPlan.HardObstacle(
                "locked_structure", "house", new BlockBounds(0, 0, 2, 2));
        CompiledDecorationProgramPlan plan = new CompiledDecorationProgramPlan(
                CompiledDecorationProgramPlan.SCHEMA, "city_test", "catalog_hash", List.of(obstacle),
                List.of(program));
        CityDecorationPreviewRenderer renderer = new CityDecorationPreviewRenderer();

        assertTrue(assertThrows(IllegalArgumentException.class, () -> renderer.render(plan, List.of(), tempDir))
                .getMessage().contains("PREVIEW_SLOTS_REQUIRED"));
        DecorationSlot blocked = new DecorationSlot("blocked", "invalid", "point", BlockPoint.ORIGIN,
                new CompiledDecorationProgram.LocalPoint(0, 0), 0);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> renderer.render(plan, List.of(blocked), tempDir))
                .getMessage().contains("SLOT_IN_HARD_OBSTACLE"));
        DecorationSlot unknownPalette = new DecorationSlot("unknown", "invalid", "missing",
                new BlockPoint(4, 4), new CompiledDecorationProgram.LocalPoint(4, 4), 0);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> renderer.render(plan, List.of(unknownPalette), tempDir))
                .getMessage().contains("PALETTE_SLOT_UNAVAILABLE"));
    }

    private static CompiledDecorationProgram program(String id,
                                                     CompiledDecorationProgram.TargetMask mask,
                                                     CompiledDecorationProgram.CoordinateFrame frame,
                                                     CompiledDecorationProgram.ShapeSpec shape,
                                                     CompiledDecorationProgram.PatternSpec pattern,
                                                     CompiledDecorationProgram.ContentPalette palette) {
        return new CompiledDecorationProgram(CompiledDecorationProgram.SCHEMA, id, 0, 42L, mask, frame,
                shape, pattern, palette,
                new CompiledDecorationProgram.TerrainPolicy(2, false,
                        CompiledDecorationProgram.InvalidTerrainAction.CLIP),
                new CompiledDecorationProgram.ConflictPolicy(CompiledDecorationProgram.ConflictAction.SKIP, 1));
    }

    private static CompiledDecorationProgram.TargetMask mask(String id, int minX, int minZ, int maxX, int maxZ) {
        return new CompiledDecorationProgram.TargetMask(id, List.of(new BlockBounds(minX, minZ, maxX, maxZ)));
    }

    private static CompiledDecorationProgram.CoordinateFrame frame(int originX, int originZ) {
        return new CompiledDecorationProgram.CoordinateFrame(new BlockPoint(originX, originZ),
                new CompiledDecorationProgram.Vector2(1, 0), new CompiledDecorationProgram.Vector2(0, 1));
    }

    private static CompiledDecorationProgram.ContentPalette palette(Map<String, String> contents) {
        List<CompiledDecorationProgram.PaletteSlot> slots = new ArrayList<>();
        contents.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                slots.add(new CompiledDecorationProgram.PaletteSlot(entry.getKey(),
                        CompiledDecorationProgram.Phase.SURFACE,
                        List.of(new CompiledDecorationProgram.ContentEntry(entry.getValue(), 1.0)), true)));
        return new CompiledDecorationProgram.ContentPalette(slots);
    }

    private static int centerX(CityDecorationPreviewRenderer.Transform transform, int worldX) {
        return (transform.x(worldX) + transform.x(worldX + 1)) / 2;
    }

    private static int centerZ(CityDecorationPreviewRenderer.Transform transform, int worldZ) {
        return (transform.z(worldZ) + transform.z(worldZ + 1)) / 2;
    }

    private static int nonCanvasPixelCount(BufferedImage image) {
        int count = 0;
        int canvas = CityDecorationPreviewRenderer.CANVAS_COLOR.getRGB();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) != canvas) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean containsRgb(BufferedImage image, int rgb) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) == rgb) {
                    return true;
                }
            }
        }
        return false;
    }
}
