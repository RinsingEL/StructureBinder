package com.rinsing.geomantia.world.atlas.preview;

import com.rinsing.geomantia.world.atlas.cell.AtlasCell;
import com.rinsing.geomantia.world.atlas.cell.CellStateFlag;
import com.rinsing.geomantia.world.atlas.refresh.RefreshJob;
import com.rinsing.geomantia.world.atlas.region.AtlasRegion;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProgressExporter {
    public void export(RefreshJob job, AtlasRegion region, Path runDirectory) throws IOException {
        Files.createDirectories(runDirectory);
        writeImage(region, runDirectory.resolve("progress.png"));
        writeManifest(job, region, runDirectory.resolve("progress_manifest.json"));
    }

    private static void writeImage(AtlasRegion region, Path path) throws IOException {
        int side = region.cellsPerSide();
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                image.setRGB(x, z, colorFor(region.cell(x, z)).getRGB());
            }
        }
        ImageIO.write(image, "png", path.toFile());
    }

    private static Color colorFor(AtlasCell cell) {
        if (cell.hasFlag(CellStateFlag.FAILED)) {
            return new Color(210, 35, 45);
        }
        if (cell.hasFlag(CellStateFlag.EDGE_DIRTY) && cell.hasFlag(CellStateFlag.PATCH_READY)) {
            return new Color(235, 140, 35);
        }
        if (cell.hasFlag(CellStateFlag.PATCH_READY)) {
            return new Color(65, 165, 80);
        }
        if (cell.hasFlag(CellStateFlag.LANDFORM_READY)) {
            return new Color(230, 210, 40);
        }
        if (cell.hasFlag(CellStateFlag.METRICS_READY_LARGE)) {
            return new Color(150, 80, 190);
        }
        if (cell.hasFlag(CellStateFlag.METRICS_READY_SMALL)) {
            return new Color(60, 185, 205);
        }
        if (cell.hasFlag(CellStateFlag.SAMPLED)) {
            return new Color(70, 120, 210);
        }
        return new Color(120, 120, 120);
    }

    private static void writeManifest(RefreshJob job, AtlasRegion region, Path path) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("runId", job.jobId());
        root.put("center", Map.of("blockX", job.centerBlockX(), "blockZ", job.centerBlockZ()));
        root.put("radiusChunks", job.radiusChunks());
        root.put("cellStepBlocks", job.cellStepBlocks());
        root.put("totalCells", job.totalCells());
        root.put("counts", counts(region));
        root.put("currentRing", job.currentRing());
        root.put("status", job.status().contractName());
        root.put("updatedAt", System.currentTimeMillis());
        Files.writeString(path, AtlasJson.GSON.toJson(root));
    }

    private static Map<String, Integer> counts(AtlasRegion region) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("pending", 0);
        counts.put("sampled", 0);
        counts.put("metricsSmall", 0);
        counts.put("metricsLarge", 0);
        counts.put("classified", 0);
        counts.put("patched", 0);
        counts.put("edgeDirty", 0);
        counts.put("failed", 0);
        for (AtlasCell cell : region.cells()) {
            if (cell.hasFlag(CellStateFlag.FAILED)) {
                increment(counts, "failed");
            } else if (cell.hasFlag(CellStateFlag.PATCH_READY)) {
                increment(counts, "patched");
            } else if (cell.hasFlag(CellStateFlag.LANDFORM_READY)) {
                increment(counts, "classified");
            } else if (cell.hasFlag(CellStateFlag.METRICS_READY_LARGE)) {
                increment(counts, "metricsLarge");
            } else if (cell.hasFlag(CellStateFlag.METRICS_READY_SMALL)) {
                increment(counts, "metricsSmall");
            } else if (cell.hasFlag(CellStateFlag.SAMPLED)) {
                increment(counts, "sampled");
            } else {
                increment(counts, "pending");
            }
            if (cell.hasFlag(CellStateFlag.EDGE_DIRTY)) {
                increment(counts, "edgeDirty");
            }
        }
        return counts;
    }

    private static void increment(Map<String, Integer> counts, String key) {
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }
}
