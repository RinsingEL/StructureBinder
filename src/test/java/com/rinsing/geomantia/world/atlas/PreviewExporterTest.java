package com.rinsing.geomantia.world.atlas;

import com.rinsing.geomantia.world.atlas.cell.LandformType;
import com.rinsing.geomantia.world.atlas.landform.PatchMerger;
import com.rinsing.geomantia.world.atlas.preview.PreviewExporter;
import com.rinsing.geomantia.world.atlas.refresh.RefreshJob;
import com.rinsing.geomantia.world.atlas.refresh.RefreshPriority;
import com.rinsing.geomantia.world.atlas.refresh.SampleMode;
import com.rinsing.geomantia.world.atlas.region.AtlasRegion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewExporterTest {
    @TempDir
    Path tempDir;

    @Test
    void patchPreviewDrawsCellPatchShapeInsteadOfEnvelope() throws Exception {
        GisSampleConfig sampleConfig = GisSampleConfig.defaults();
        AtlasRegion region = new AtlasRegion("minecraft:overworld", 0, 0, sampleConfig);
        markLandform(region, 1, 1, LandformType.PLAIN);
        markLandform(region, 2, 1, LandformType.PLAIN);
        markLandform(region, 1, 2, LandformType.PLAIN);
        new PatchMerger(GisClassifierConfig.defaults()).merge(region);

        RefreshJob job = new RefreshJob("preview_test", region.dimensionId(), 0, 0, 1,
                sampleConfig.dependencyMarginCells(), sampleConfig.cellStepBlocks(), SampleMode.PRIOR,
                RefreshPriority.DEBUG, sampleConfig.budgetCellsPerBatch());

        new PreviewExporter().export(job, region, tempDir);

        BufferedImage patch = ImageIO.read(tempDir.resolve("patch.png").toFile());
        int scale = patch.getWidth() / region.cellsPerSide();
        assertEquals(0, patch.getWidth() % region.cellsPerSide());
        assertTrue(scale > 1, "patch preview needs per-cell pixels for boundaries and fill color");

        int realBoundary = rgb(patch, center(2, scale), lowerEdge(1, scale));
        int envelopeOnlyCorner = rgb(patch, center(2, scale), center(2, scale));

        assertEquals(rgb(Color.BLACK), realBoundary);
        assertNotEquals(rgb(Color.BLACK), envelopeOnlyCorner);
        String manifest = Files.readString(tempDir.resolve("preview_manifest.json"));
        assertTrue(manifest.contains("patch-cell-boundaries"));
        assertTrue(manifest.contains("\"legend\": \"legend.png\""));
        assertTrue(Files.exists(tempDir.resolve("legend.png")));
    }

    private static void markLandform(AtlasRegion region, int x, int z, LandformType type) {
        region.cell(x, z).setLandformType(type);
    }

    private static int center(int cell, int scale) {
        return cell * scale + scale / 2;
    }

    private static int lowerEdge(int cell, int scale) {
        return cell * scale + scale - 1;
    }

    private static int rgb(BufferedImage image, int x, int z) {
        return image.getRGB(x, z) & 0x00FFFFFF;
    }

    private static int rgb(Color color) {
        return color.getRGB() & 0x00FFFFFF;
    }
}
