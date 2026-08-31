package com.rinsing.geomantia.systems.gis.preview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeOverviewRendererTest {
    @TempDir
    Path tempDir;

    @Test
    void rendersStableBiomePaletteAndLegend() throws Exception {
        Path output = tempDir.resolve("biomes.png");
        new BiomeOverviewRenderer().render(List.of(
                new BiomeOverviewRenderer.Cell(0, 0, "minecraft:plains"),
                new BiomeOverviewRenderer.Cell(1, 0, "minecraft:ocean"),
                new BiomeOverviewRenderer.Cell(0, 1, "mod:crystal_fields"),
                new BiomeOverviewRenderer.Cell(1, 1, "mod:crystal_fields")),
                output, "Biome test", 16);

        assertTrue(Files.size(output) > 0L);
        assertTrue(ImageIO.read(output.toFile()).getWidth() > 2);
        assertEquals(new Color(135, 181, 87), BiomeOverviewRenderer.color("minecraft:plains"));
        assertEquals(BiomeOverviewRenderer.color("mod:crystal_fields"),
                BiomeOverviewRenderer.color("mod:crystal_fields"));
    }
}
