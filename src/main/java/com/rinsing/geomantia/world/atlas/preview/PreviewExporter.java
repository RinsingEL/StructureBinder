package com.rinsing.geomantia.world.atlas.preview;

import com.rinsing.geomantia.world.atlas.GisAtlasConstants;
import com.rinsing.geomantia.world.atlas.cell.AtlasCell;
import com.rinsing.geomantia.world.atlas.cell.CellStateFlag;
import com.rinsing.geomantia.world.atlas.cell.LandformType;
import com.rinsing.geomantia.world.atlas.landform.LandformPatch;
import com.rinsing.geomantia.world.atlas.refresh.RefreshJob;
import com.rinsing.geomantia.world.atlas.region.AtlasRegion;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PreviewExporter {
    public void export(RefreshJob job, AtlasRegion region, Path previewDirectory) throws IOException {
        Files.createDirectories(previewDirectory);
        List<Map<String, Object>> layers = new ArrayList<>();
        layers.add(writeNumeric(region, previewDirectory, "elevation", "grayscale", Metric.ELEVATION));
        layers.add(writeWater(region, previewDirectory));
        layers.add(writeNumeric(region, previewDirectory, "slope", "heat", Metric.SLOPE));
        layers.add(writeNumeric(region, previewDirectory, "tpiSmall", "diverging", Metric.TPI_SMALL));
        layers.add(writeNumeric(region, previewDirectory, "tpiLarge", "diverging", Metric.TPI_LARGE));
        layers.add(writeLandform(region, previewDirectory));
        layers.add(writePatch(region, previewDirectory));
        writeManifest(job, region, previewDirectory.resolve("preview_manifest.json"), layers);
    }

    private static Map<String, Object> writeNumeric(AtlasRegion region, Path dir, String name, String palette,
            Metric metric) throws IOException {
        Range range = range(region, metric);
        int side = region.cellsPerSide();
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                double value = metric.value(region.cell(x, z));
                image.setRGB(x, z, colorNumeric(value, range, palette).getRGB());
            }
        }
        Path file = dir.resolve(name + ".png");
        ImageIO.write(image, "png", file.toFile());
        return layer(name, file.getFileName().toString(), range.min(), range.max(), palette);
    }

    private static Map<String, Object> writeWater(AtlasRegion region, Path dir) throws IOException {
        int side = region.cellsPerSide();
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                AtlasCell cell = region.cell(x, z);
                Color color = cell.isWater()
                        ? new Color(30, 95, 190)
                        : cell.waterDistance() <= 3.0 ? new Color(230, 205, 110) : new Color(195, 210, 180);
                image.setRGB(x, z, color.getRGB());
            }
        }
        Path file = dir.resolve("water.png");
        ImageIO.write(image, "png", file.toFile());
        return layer("water", file.getFileName().toString(), 0.0, 1.0, "water-shore");
    }

    private static Map<String, Object> writeLandform(AtlasRegion region, Path dir) throws IOException {
        int side = region.cellsPerSide();
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                image.setRGB(x, z, colorLandform(region.cell(x, z).landformType()).getRGB());
            }
        }
        Path file = dir.resolve("landform.png");
        ImageIO.write(image, "png", file.toFile());
        return layer("landform", file.getFileName().toString(), 0.0, 0.0, "landform");
    }

    private static Map<String, Object> writePatch(AtlasRegion region, Path dir) throws IOException {
        int side = region.cellsPerSide();
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                image.setRGB(x, z, colorLandform(region.cell(x, z).landformType()).darker().getRGB());
            }
        }
        graphics.setColor(Color.BLACK);
        for (LandformPatch patch : region.patches()) {
            int minX = Math.floorDiv(patch.blockMinX() - region.blockMinX(), region.cellStepBlocks());
            int minZ = Math.floorDiv(patch.blockMinZ() - region.blockMinZ(), region.cellStepBlocks());
            int maxX = Math.floorDiv(patch.blockMaxX() - region.blockMinX(), region.cellStepBlocks());
            int maxZ = Math.floorDiv(patch.blockMaxZ() - region.blockMinZ(), region.cellStepBlocks());
            graphics.drawRect(minX, minZ, Math.max(1, maxX - minX), Math.max(1, maxZ - minZ));
        }
        graphics.dispose();
        Path file = dir.resolve("patch.png");
        ImageIO.write(image, "png", file.toFile());
        return layer("patch", file.getFileName().toString(), 0.0, region.patches().size(), "patch-bounds");
    }

    private static void writeManifest(RefreshJob job, AtlasRegion region, Path path, List<Map<String, Object>> layers)
            throws IOException {
        Map<String, Integer> sourceCounts = new LinkedHashMap<>();
        int unknown = 0;
        boolean edgeDirty = false;
        for (AtlasCell cell : region.cells()) {
            if (cell.hasFlag(CellStateFlag.SAMPLED)) {
                sourceCounts.put(cell.sampleSource().contractName(),
                        sourceCounts.getOrDefault(cell.sampleSource().contractName(), 0) + 1);
            }
            if (cell.landformType() == LandformType.UNKNOWN) {
                unknown++;
            }
            edgeDirty = edgeDirty || cell.hasFlag(CellStateFlag.EDGE_DIRTY);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("manifestId", job.jobId() + "_preview");
        root.put("worldId", "runtime-prior");
        root.put("dimensionId", job.dimensionId());
        root.put("centerBlockX", job.centerBlockX());
        root.put("centerBlockZ", job.centerBlockZ());
        root.put("radiusChunks", job.radiusChunks());
        root.put("cellStepBlocks", job.cellStepBlocks());
        root.put("sampleMode", job.sampleMode().contractName());
        root.put("sourceCounts", sourceCounts);
        root.put("atlasVersion", GisAtlasConstants.ATLAS_VERSION);
        root.put("configVersion", GisAtlasConstants.CONFIG_VERSION);
        root.put("generatedAt", System.currentTimeMillis());
        root.put("layers", layers);
        root.put("hasEdgeDirty", edgeDirty);
        root.put("unknownCellCount", unknown);
        root.put("notes", "GIS v1 debug preview; JSON is not the production atlas cache.");
        Files.writeString(path, AtlasJson.GSON.toJson(root));
    }

    private static Range range(AtlasRegion region, Metric metric) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (AtlasCell cell : region.cells()) {
            if (!cell.hasFlag(CellStateFlag.SAMPLED)) {
                continue;
            }
            double value = metric.value(cell);
            if (!Double.isFinite(value)) {
                continue;
            }
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (!Double.isFinite(min) || !Double.isFinite(max) || min == max) {
            return new Range(0.0, Math.max(1.0, max));
        }
        return new Range(min, max);
    }

    private static Color colorNumeric(double value, Range range, String palette) {
        if (!Double.isFinite(value)) {
            return new Color(80, 80, 80);
        }
        double t = (value - range.min()) / Math.max(0.0001, range.max() - range.min());
        t = Math.max(0.0, Math.min(1.0, t));
        if ("diverging".equals(palette)) {
            if (t < 0.5) {
                double k = t * 2.0;
                return mix(new Color(30, 85, 170), Color.WHITE, k);
            }
            return mix(Color.WHITE, new Color(190, 65, 45), (t - 0.5) * 2.0);
        }
        if ("heat".equals(palette)) {
            return mix(new Color(245, 235, 170), new Color(170, 35, 45), t);
        }
        int c = (int) Math.round(35 + t * 205);
        return new Color(c, c, c);
    }

    private static Color mix(Color a, Color b, double t) {
        int r = (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t);
        int g = (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t);
        int bl = (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t);
        return new Color(r, g, bl);
    }

    private static Color colorLandform(LandformType type) {
        return switch (type) {
            case WATER -> new Color(35, 95, 190);
            case SHORE -> new Color(220, 195, 95);
            case PLAIN -> new Color(105, 170, 85);
            case TERRACE -> new Color(150, 165, 95);
            case SLOPE -> new Color(170, 135, 75);
            case CLIFF -> new Color(105, 95, 90);
            case RIDGE -> new Color(190, 85, 65);
            case VALLEY -> new Color(70, 140, 120);
            case BASIN -> new Color(120, 100, 160);
            case UNKNOWN -> new Color(80, 80, 80);
        };
    }

    private static Map<String, Object> layer(String name, String file, double minValue, double maxValue,
            String palette) {
        Map<String, Object> layer = new LinkedHashMap<>();
        layer.put("name", name);
        layer.put("file", file);
        layer.put("minValue", minValue);
        layer.put("maxValue", maxValue);
        layer.put("palette", palette);
        return layer;
    }

    private record Range(double min, double max) {
    }

    private enum Metric {
        ELEVATION {
            @Override
            double value(AtlasCell cell) {
                return cell.elevation();
            }
        },
        SLOPE {
            @Override
            double value(AtlasCell cell) {
                return cell.slope();
            }
        },
        TPI_SMALL {
            @Override
            double value(AtlasCell cell) {
                return cell.tpiSmall();
            }
        },
        TPI_LARGE {
            @Override
            double value(AtlasCell cell) {
                return cell.tpiLarge();
            }
        };

        abstract double value(AtlasCell cell);
    }
}
