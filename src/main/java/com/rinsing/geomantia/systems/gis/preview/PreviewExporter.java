package com.rinsing.geomantia.systems.gis.preview;

import com.rinsing.geomantia.systems.gis.GisAtlasConstants;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.domain.cell.AtlasCell;
import com.rinsing.geomantia.systems.gis.domain.cell.CellStateFlag;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshJob;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegion;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
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
    private static final int PATCH_CELL_PIXELS = 4;
    private static final Color PATCH_BOUNDARY_COLOR = Color.BLACK;
    private final GisSampleConfig sampleConfig;

    public PreviewExporter() {
        this(GisSampleConfig.defaults());
    }

    public PreviewExporter(GisSampleConfig sampleConfig) {
        this.sampleConfig = sampleConfig;
    }

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
        Path legend = writeLegend(previewDirectory);
        writeManifest(job, region, previewDirectory.resolve("preview_manifest.json"), layers,
                legend.getFileName().toString());
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
        int scale = PATCH_CELL_PIXELS;
        BufferedImage image = new BufferedImage(side * scale, side * scale, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                fillCell(image, x, z, scale, colorLandform(region.cell(x, z).landformType()));
            }
        }
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                drawPatchBoundary(image, region, x, z, scale);
            }
        }
        Path file = dir.resolve("patch.png");
        ImageIO.write(image, "png", file.toFile());
        return layer("patch", file.getFileName().toString(), 0.0, region.patches().size(), "patch-cell-boundaries");
    }

    private static Path writeLegend(Path dir) throws IOException {
        int width = 430;
        int rowHeight = 20;
        int top = 38;
        int rows = LandformType.values().length + 5;
        int height = top + rows * rowHeight + 12;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(245, 245, 245));
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        graphics.drawString("GIS 预览图例(GIS preview legend)", 12, 20);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        int y = top;
        for (LandformType type : LandformType.values()) {
            drawLegendRow(graphics, y, colorLandform(type), landformLegendLabel(type));
            y += rowHeight;
        }
        y += 4;
        drawLegendRow(graphics, y, PATCH_BOUNDARY_COLOR, "黑线(patch member boundary)");
        y += rowHeight;
        drawLegendRow(graphics, y, new Color(80, 80, 80), "灰色(unknown / not classified)");
        y += rowHeight;
        drawLegendRow(graphics, y, new Color(30, 95, 190), "水体图层蓝色(water layer blue)");
        y += rowHeight;
        drawLegendRow(graphics, y, new Color(230, 205, 110), "岸线候选色(shore candidate)");
        y += rowHeight;
        drawLegendRow(graphics, y, Color.WHITE, "数值图层(numeric): manifest min/max + palette");
        graphics.dispose();
        Path file = dir.resolve("legend.png");
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    private static String landformLegendLabel(LandformType type) {
        return switch (type) {
            case WATER -> "水体(water)";
            case SHORE -> "岸线(shore)";
            case PLAIN -> "平原(plain)";
            case TERRACE -> "台地(terrace)";
            case SLOPE -> "坡地(slope)";
            case CLIFF -> "悬崖(cliff)";
            case RIDGE -> "山脊(ridge)";
            case VALLEY -> "谷地(valley)";
            case BASIN -> "盆地(basin)";
            case UNKNOWN -> "未知(unknown)";
        };
    }

    private static void drawLegendRow(Graphics2D graphics, int y, Color color, String label) {
        graphics.setColor(color);
        graphics.fillRect(12, y - 11, 14, 14);
        graphics.setColor(Color.BLACK);
        graphics.drawRect(12, y - 11, 14, 14);
        graphics.drawString(label, 34, y);
    }

    private static void fillCell(BufferedImage image, int cellX, int cellZ, int scale, Color color) {
        int rgb = color.getRGB();
        int minX = cellX * scale;
        int minZ = cellZ * scale;
        for (int z = minZ; z < minZ + scale; z++) {
            for (int x = minX; x < minX + scale; x++) {
                image.setRGB(x, z, rgb);
            }
        }
    }

    private static void drawPatchBoundary(BufferedImage image, AtlasRegion region, int cellX, int cellZ, int scale) {
        AtlasCell cell = region.cell(cellX, cellZ);
        if (cell.patchId().isBlank()) {
            return;
        }
        int minX = cellX * scale;
        int minZ = cellZ * scale;
        int maxX = minX + scale - 1;
        int maxZ = minZ + scale - 1;
        int rgb = PATCH_BOUNDARY_COLOR.getRGB();
        if (isPatchBoundary(region, cell, 0, -1)) {
            drawHorizontal(image, minX, maxX, minZ, rgb);
        }
        if (isPatchBoundary(region, cell, 0, 1)) {
            drawHorizontal(image, minX, maxX, maxZ, rgb);
        }
        if (isPatchBoundary(region, cell, -1, 0)) {
            drawVertical(image, minX, minZ, maxZ, rgb);
        }
        if (isPatchBoundary(region, cell, 1, 0)) {
            drawVertical(image, maxX, minZ, maxZ, rgb);
        }
    }

    private static boolean isPatchBoundary(AtlasRegion region, AtlasCell cell, int dx, int dz) {
        AtlasCell neighbor = region.cell(cell.localCellX() + dx, cell.localCellZ() + dz);
        return neighbor == null || !neighbor.patchId().equals(cell.patchId());
    }

    private static void drawHorizontal(BufferedImage image, int minX, int maxX, int y, int rgb) {
        for (int x = minX; x <= maxX; x++) {
            image.setRGB(x, y, rgb);
        }
    }

    private static void drawVertical(BufferedImage image, int x, int minZ, int maxZ, int rgb) {
        for (int z = minZ; z <= maxZ; z++) {
            image.setRGB(x, z, rgb);
        }
    }

    private void writeManifest(RefreshJob job, AtlasRegion region, Path path, List<Map<String, Object>> layers,
            String legendFile) throws IOException {
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
        root.put("metricRadiiCells", metricRadiiCells(sampleConfig));
        root.put("metricRadiiBlocks", metricRadiiBlocks(sampleConfig));
        root.put("sampleMode", job.sampleMode().contractName());
        root.put("sourceCounts", sourceCounts);
        root.put("atlasVersion", GisAtlasConstants.ATLAS_VERSION);
        root.put("configId", GisAtlasConstants.CONFIG_ID);
        root.put("generatedAt", System.currentTimeMillis());
        root.put("layers", layers);
        root.put("legend", legendFile);
        root.put("hasEdgeDirty", edgeDirty);
        root.put("unknownCellCount", unknown);
        root.put("notes", "GIS current debug preview; JSON is not the production atlas cache.");
        Files.writeString(path, AtlasJson.GSON.toJson(root));
    }

    private static Map<String, Integer> metricRadiiCells(GisSampleConfig config) {
        return Map.of(
                "slope", config.slopeRadiusCells(),
                "localRelief", config.localReliefRadiusCells(),
                "roughness", config.roughnessRadiusCells(),
                "tpiSmall", config.tpiSmallRadiusCells(),
                "tpiLarge", config.tpiLargeRadiusCells(),
                "maxWaterDistance", config.maxWaterDistanceCells(),
                "dependencyMargin", config.dependencyMarginCells()
        );
    }

    private static Map<String, Integer> metricRadiiBlocks(GisSampleConfig config) {
        int step = config.cellStepBlocks();
        return Map.of(
                "slope", config.slopeRadiusCells() * step,
                "localRelief", config.localReliefRadiusCells() * step,
                "roughness", config.roughnessRadiusCells() * step,
                "tpiSmall", config.tpiSmallRadiusCells() * step,
                "tpiLarge", config.tpiLargeRadiusCells() * step,
                "maxWaterDistance", config.maxWaterDistanceCells() * step,
                "dependencyMargin", config.dependencyMarginCells() * step
        );
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
        ELEVATION,
        SLOPE,
        TPI_SMALL,
        TPI_LARGE;

        double value(AtlasCell cell) {
            return switch (this) {
                case ELEVATION -> cell.elevation();
                case SLOPE -> cell.slope();
                case TPI_SMALL -> cell.tpiSmall();
                case TPI_LARGE -> cell.tpiLarge();
            };
        }
    }
}
