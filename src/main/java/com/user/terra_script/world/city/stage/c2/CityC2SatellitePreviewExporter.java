package com.user.terra_script.world.city.stage.c2;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.user.terra_script.domain.world.scan.ScanPixel;
import com.user.terra_script.util.PreviewOverlayUtil;
import com.user.terra_script.util.TerrainFeatureComputer;
import com.user.terra_script.world.city.CityInstance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CityC2SatellitePreviewExporter {
    private static final int PREVIEW_SIZE = 512;
    private static final int SEA_LEVEL = 63;
    private static final String IMAGE_FILE = "C2_satellite_preview.png";
    private static final String LEGEND_FILE = "C2_satellite_preview.legend.json";
    private static final String HILLSHADE_IMAGE_FILE = "C2_satellite_hillshade.png";
    private static final String ROUGHNESS_IMAGE_FILE = "C2_satellite_roughness.png";
    private static final String LAYER_LABELS_FILE = "C2_layer_labels.json";
    private static final double HILLSHADE_AZIMUTH_DEG = 315.0;
    private static final double HILLSHADE_ALTITUDE_DEG = 45.0;

    private CityC2SatellitePreviewExporter() {}

    public static JsonObject export(
            MinecraftServer server,
            String cityId,
            ScanPixel[][] map,
            int originX,
            int originZ,
            int scanStep,
            int worldWidth,
            int worldHeight,
            int centerX,
            int centerZ,
            Map<Long, CityInstance.LayerAssignment> claimedChunks
    ) throws Exception {
        JsonObject out = new JsonObject();
        if (server == null || cityId == null || cityId.isBlank() || map == null || map.length == 0 || map[0] == null) {
            out.addProperty("generated", false);
            out.addProperty("reason", "invalid_input");
            return out;
        }

        BufferedImage image = renderHeight(map);
        double[][] heightDs = downsampleAverage(map, ValueField.HEIGHT);
        double[][] hillshade = buildHillshade(heightDs);
        BufferedImage hillshadeImage = renderHillshade(hillshade);
        double[][] roughnessRaw = TerrainFeatureComputer.computeRoughness(map, scanStep, 2, false);
        double[][] roughnessDs = downsampleAverage(roughnessRaw);
        BufferedImage roughnessImage = renderClassified(
                roughnessDs,
                new double[]{0.4, 0.8, 1.5, 2.5},
                new int[]{0xFF4CAF50, 0xFFF4D35E, 0xFFF08A4B, 0xFFD1495B, 0xFF6A1B9A}
        );

        List<LayerLabel> layerLabels = computeLayerLabels(claimedChunks);
        applyCityOverlay(image, claimedChunks, layerLabels, originX, originZ, worldWidth, worldHeight, centerX, centerZ);
        applyCityOverlay(hillshadeImage, claimedChunks, layerLabels, originX, originZ, worldWidth, worldHeight, centerX, centerZ);
        applyCityOverlay(roughnessImage, claimedChunks, layerLabels, originX, originZ, worldWidth, worldHeight, centerX, centerZ);

        PreviewOverlayUtil.GridSpec gridSpec = new PreviewOverlayUtil.GridSpec();
        gridSpec.previewSize = PREVIEW_SIZE;
        gridSpec.originX = originX;
        gridSpec.originZ = originZ;
        gridSpec.widthBlocks = worldWidth;
        gridSpec.heightBlocks = worldHeight;
        gridSpec.sampleStepBlocks = Math.max(1, scanStep);
        gridSpec.legendText = PreviewOverlayUtil.defaultLegendText(gridSpec);
        PreviewOverlayUtil.applyGridOverlay(image, gridSpec);
        PreviewOverlayUtil.applyGridOverlay(hillshadeImage, gridSpec);
        PreviewOverlayUtil.applyGridOverlay(roughnessImage, gridSpec);

        Path cityDir = server.getWorldPath(LevelResource.ROOT)
                .resolve("terra_script")
                .resolve("cities")
                .resolve(cityId);
        Files.createDirectories(cityDir);
        ImageIO.write(image, "png", cityDir.resolve(IMAGE_FILE).toFile());
        ImageIO.write(hillshadeImage, "png", cityDir.resolve(HILLSHADE_IMAGE_FILE).toFile());
        ImageIO.write(roughnessImage, "png", cityDir.resolve(ROUGHNESS_IMAGE_FILE).toFile());

        JsonObject legend = new JsonObject();
        legend.addProperty("image", IMAGE_FILE);
        legend.addProperty("type", "city_c2_satellite_preview");
        legend.addProperty("city_id", cityId);
        legend.addProperty("scan_step", scanStep);
        legend.addProperty("origin_x", originX);
        legend.addProperty("origin_z", originZ);
        legend.addProperty("width_blocks", worldWidth);
        legend.addProperty("height_blocks", worldHeight);
        legend.addProperty("center_x", centerX);
        legend.addProperty("center_z", centerZ);
        JsonArray resolution = new JsonArray();
        resolution.add(PREVIEW_SIZE);
        resolution.add(PREVIEW_SIZE);
        legend.add("resolution", resolution);
        legend.add("height_bins", buildHeightBinsLegend());
        legend.add("grid", PreviewOverlayUtil.buildGridMetadata(gridSpec));
        Files.writeString(cityDir.resolve(LEGEND_FILE), legend.toString(), StandardCharsets.UTF_8);

        JsonObject labelDoc = buildLayerLabelDoc(layerLabels);
        Files.writeString(cityDir.resolve(LAYER_LABELS_FILE), labelDoc.toString(), StandardCharsets.UTF_8);

        out.addProperty("generated", true);
        out.addProperty("image", "cities/" + cityId + "/" + IMAGE_FILE);
        out.addProperty("hillshade", "cities/" + cityId + "/" + HILLSHADE_IMAGE_FILE);
        out.addProperty("roughness", "cities/" + cityId + "/" + ROUGHNESS_IMAGE_FILE);
        out.addProperty("legend", "cities/" + cityId + "/" + LEGEND_FILE);
        out.addProperty("layer_labels", "cities/" + cityId + "/" + LAYER_LABELS_FILE);
        return out;
    }

    private static void applyCityOverlay(
            BufferedImage image,
            Map<Long, CityInstance.LayerAssignment> claimedChunks,
            List<LayerLabel> layerLabels,
            int originX,
            int originZ,
            int worldWidth,
            int worldHeight,
            int centerX,
            int centerZ
    ) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        drawLayerBoundaries(g, claimedChunks, originX, originZ, worldWidth, worldHeight);
        drawLayerNumbers(g, layerLabels, originX, originZ, worldWidth, worldHeight);
        drawCenterCross(g, centerX, centerZ, originX, originZ, worldWidth, worldHeight);
        g.dispose();
    }

    private static void drawLayerBoundaries(
            Graphics2D g,
            Map<Long, CityInstance.LayerAssignment> claimedChunks,
            int originX,
            int originZ,
            int worldWidth,
            int worldHeight
    ) {
        if (g == null || claimedChunks == null || claimedChunks.isEmpty()) return;

        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(0, 0, 0, 225));
        for (Map.Entry<Long, CityInstance.LayerAssignment> entry : claimedChunks.entrySet()) {
            Long key = entry.getKey();
            if (key == null) continue;
            int chunkX = ChunkPos.getX(key);
            int chunkZ = ChunkPos.getZ(key);
            int minX = chunkX << 4;
            int minZ = chunkZ << 4;
            int px0 = toPreviewCoord(minX, originX, worldWidth);
            int pz0 = toPreviewCoord(minZ, originZ, worldHeight);
            int px1 = toPreviewCoord(minX + 16, originX, worldWidth);
            int pz1 = toPreviewCoord(minZ + 16, originZ, worldHeight);

            if (isDifferentLayer(claimedChunks, entry.getValue(), chunkX - 1, chunkZ)) g.drawLine(px0, pz0, px0, pz1);
            if (isDifferentLayer(claimedChunks, entry.getValue(), chunkX + 1, chunkZ)) g.drawLine(px1, pz0, px1, pz1);
            if (isDifferentLayer(claimedChunks, entry.getValue(), chunkX, chunkZ - 1)) g.drawLine(px0, pz0, px1, pz0);
            if (isDifferentLayer(claimedChunks, entry.getValue(), chunkX, chunkZ + 1)) g.drawLine(px0, pz1, px1, pz1);
        }
    }

    private static boolean isDifferentLayer(
            Map<Long, CityInstance.LayerAssignment> claimedChunks,
            CityInstance.LayerAssignment self,
            int neighborX,
            int neighborZ
    ) {
        CityInstance.LayerAssignment other = claimedChunks.get(ChunkPos.asLong(neighborX, neighborZ));
        if (other == null) return true;
        int selfIdx = self != null ? self.layerIndex : -1;
        return other.layerIndex != selfIdx;
    }

    private static void drawLayerNumbers(
            Graphics2D g,
            List<LayerLabel> layerLabels,
            int originX,
            int originZ,
            int worldWidth,
            int worldHeight
    ) {
        if (g == null || layerLabels == null || layerLabels.isEmpty()) return;
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        for (LayerLabel label : layerLabels) {
            int px = toPreviewCoord((int) Math.round(label.centroid.getX()), originX, worldWidth);
            int pz = toPreviewCoord((int) Math.round(label.centroid.getY()), originZ, worldHeight);
            String text = Integer.toString(label.labelNo);
            g.setColor(new Color(0, 0, 0, 220));
            g.drawString(text, px - 5, pz + 6);
            g.setColor(new Color(255, 255, 255, 240));
            g.drawString(text, px - 6, pz + 5);
        }
    }

    private static void drawCenterCross(
            Graphics2D g,
            int centerX,
            int centerZ,
            int originX,
            int originZ,
            int worldWidth,
            int worldHeight
    ) {
        g.setStroke(new BasicStroke(2f));
        int cx = toPreviewCoord(centerX, originX, worldWidth);
        int cz = toPreviewCoord(centerZ, originZ, worldHeight);
        g.setColor(new Color(0, 0, 0, 220));
        g.drawLine(cx - 10, cz, cx + 10, cz);
        g.drawLine(cx, cz - 10, cx, cz + 10);
        g.setColor(new Color(255, 255, 255, 240));
        g.drawLine(cx - 8, cz, cx + 8, cz);
        g.drawLine(cx, cz - 8, cx, cz + 8);
        g.fillOval(cx - 3, cz - 3, 6, 6);
    }

    private static int toPreviewCoord(int worldCoord, int originCoord, int worldSpan) {
        if (worldSpan <= 1) return 0;
        double ratio = (worldCoord - originCoord) / (double) Math.max(1, worldSpan - 1);
        int coord = (int) Math.round(ratio * (PREVIEW_SIZE - 1));
        return Math.max(0, Math.min(PREVIEW_SIZE - 1, coord));
    }

    private static BufferedImage renderHeight(ScanPixel[][] map) {
        BufferedImage image = new BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_ARGB);
        int srcW = map.length;
        int srcH = map[0].length;
        for (int px = 0; px < PREVIEW_SIZE; px++) {
            int sx = mapIndex(px, srcW);
            for (int pz = 0; pz < PREVIEW_SIZE; pz++) {
                int sz = mapIndex(pz, srcH);
                image.setRGB(px, pz, classifyHeightColor(map[sx][sz]));
            }
        }
        return image;
    }

    private static BufferedImage renderHillshade(double[][] hillshade) {
        BufferedImage image = new BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < PREVIEW_SIZE; x++) {
            for (int y = 0; y < PREVIEW_SIZE; y++) {
                int v = (int) Math.max(0, Math.min(255, Math.round(hillshade[x][y] * 255.0)));
                int rgb = 0xFF000000 | (v << 16) | (v << 8) | v;
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    private static BufferedImage renderClassified(double[][] values, double[] bins, int[] colors) {
        BufferedImage image = new BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < PREVIEW_SIZE; x++) {
            for (int y = 0; y < PREVIEW_SIZE; y++) {
                int idx = classify(values[x][y], bins);
                image.setRGB(x, y, colors[Math.max(0, Math.min(colors.length - 1, idx))]);
            }
        }
        return image;
    }

    private static int mapIndex(int out, int srcSize) {
        if (srcSize <= 1) return 0;
        int idx = (int) Math.floor(out * (srcSize / (double) PREVIEW_SIZE));
        return Math.max(0, Math.min(srcSize - 1, idx));
    }

    private static double[][] downsampleAverage(ScanPixel[][] map, ValueField field) {
        int srcW = map.length;
        int srcH = map[0].length;
        double[][] out = new double[PREVIEW_SIZE][PREVIEW_SIZE];
        for (int ox = 0; ox < PREVIEW_SIZE; ox++) {
            int x0 = mapStart(ox, srcW);
            int x1 = mapEnd(ox, srcW);
            for (int oy = 0; oy < PREVIEW_SIZE; oy++) {
                int y0 = mapStart(oy, srcH);
                int y1 = mapEnd(oy, srcH);
                double sum = 0.0;
                int count = 0;
                for (int x = x0; x < x1; x++) {
                    for (int y = y0; y < y1; y++) {
                        ScanPixel p = map[x][y];
                        if (p == null) continue;
                        sum += field == ValueField.HEIGHT ? p.height() : p.temperature();
                        count++;
                    }
                }
                out[ox][oy] = count > 0 ? (sum / count) : 0.0;
            }
        }
        return out;
    }

    private static double[][] downsampleAverage(double[][] values) {
        int srcW = values.length;
        int srcH = values[0].length;
        double[][] out = new double[PREVIEW_SIZE][PREVIEW_SIZE];
        for (int ox = 0; ox < PREVIEW_SIZE; ox++) {
            int x0 = mapStart(ox, srcW);
            int x1 = mapEnd(ox, srcW);
            for (int oy = 0; oy < PREVIEW_SIZE; oy++) {
                int y0 = mapStart(oy, srcH);
                int y1 = mapEnd(oy, srcH);
                double sum = 0.0;
                int count = 0;
                for (int x = x0; x < x1; x++) {
                    for (int y = y0; y < y1; y++) {
                        double v = values[x][y];
                        if (Double.isNaN(v) || Double.isInfinite(v)) continue;
                        sum += v;
                        count++;
                    }
                }
                out[ox][oy] = count > 0 ? (sum / count) : 0.0;
            }
        }
        return out;
    }

    private static double[][] buildHillshade(double[][] elevation) {
        int w = elevation.length;
        int h = elevation[0].length;
        double[][] out = new double[w][h];
        double azimuth = Math.toRadians(HILLSHADE_AZIMUTH_DEG);
        double zenith = Math.toRadians(90.0 - HILLSHADE_ALTITUDE_DEG);

        for (int x = 0; x < w; x++) {
            int xm = Math.max(0, x - 1);
            int xp = Math.min(w - 1, x + 1);
            for (int y = 0; y < h; y++) {
                int ym = Math.max(0, y - 1);
                int yp = Math.min(h - 1, y + 1);

                double dzdx = (elevation[xp][y] - elevation[xm][y]) * 0.5;
                double dzdy = (elevation[x][yp] - elevation[x][ym]) * 0.5;
                double slope = Math.atan(Math.sqrt(dzdx * dzdx + dzdy * dzdy));
                double aspect = Math.atan2(dzdy, -dzdx);
                if (aspect < 0) aspect += Math.PI * 2.0;

                double shade = Math.cos(zenith) * Math.cos(slope)
                        + Math.sin(zenith) * Math.sin(slope) * Math.cos(azimuth - aspect);
                out[x][y] = Math.max(0.0, Math.min(1.0, shade));
            }
        }
        return out;
    }

    private static int mapStart(int out, int src) {
        return (int) Math.floor(out * (src / (double) PREVIEW_SIZE));
    }

    private static int mapEnd(int out, int src) {
        return Math.max(mapStart(out, src) + 1, (int) Math.floor((out + 1) * (src / (double) PREVIEW_SIZE)));
    }

    private static int classifyHeightColor(ScanPixel p) {
        if (p == null || !p.isLand()) return 0xFF1F4E79;
        int h = p.height();
        if (h < SEA_LEVEL) return 0xFF1F4E79;
        if (h < 70) return 0xFFA7D08C;
        if (h < 90) return 0xFF8BCF6B;
        if (h < 110) return 0xFF70AD47;
        if (h < 135) return 0xFFDCC36D;
        if (h < 160) return 0xFFC9B458;
        if (h < 190) return 0xFFA4723E;
        if (h < 220) return 0xFF8B5A2B;
        return 0xFFD9D9D9;
    }

    private static int classify(double value, double[] bins) {
        for (int i = 0; i < bins.length; i++) {
            if (value <= bins[i]) return i;
        }
        return bins.length;
    }

    private static JsonArray buildHeightBinsLegend() {
        JsonArray bins = new JsonArray();
        bins.add(bin("<sea_level", Double.NEGATIVE_INFINITY, SEA_LEVEL, "#1F4E79"));
        bins.add(bin("sea_level_to_70", SEA_LEVEL, 70, "#A7D08C"));
        bins.add(bin("70_to_90", 70, 90, "#8BCF6B"));
        bins.add(bin("90_to_110", 90, 110, "#70AD47"));
        bins.add(bin("110_to_135", 110, 135, "#DCC36D"));
        bins.add(bin("135_to_160", 135, 160, "#C9B458"));
        bins.add(bin("160_to_190", 160, 190, "#A4723E"));
        bins.add(bin("190_to_220", 190, 220, "#8B5A2B"));
        bins.add(bin(">220", 220, Double.POSITIVE_INFINITY, "#D9D9D9"));
        return bins;
    }

    private static JsonObject bin(String label, double min, double max, String colorHex) {
        JsonObject b = new JsonObject();
        b.addProperty("label", label);
        if (Double.isFinite(min)) b.addProperty("min", min);
        if (Double.isFinite(max)) b.addProperty("max", max);
        b.addProperty("color", colorHex);
        return b;
    }

    private static List<LayerLabel> computeLayerLabels(Map<Long, CityInstance.LayerAssignment> claimedChunks) {
        if (claimedChunks == null || claimedChunks.isEmpty()) return List.of();
        Map<Integer, LayerAccumulator> byLayer = new HashMap<>();
        for (Map.Entry<Long, CityInstance.LayerAssignment> entry : claimedChunks.entrySet()) {
            Long key = entry.getKey();
            if (key == null) continue;
            CityInstance.LayerAssignment assignment = entry.getValue();
            int layerIdx = assignment != null ? assignment.layerIndex : -1;
            LayerAccumulator acc = byLayer.computeIfAbsent(layerIdx, k -> new LayerAccumulator(layerIdx, assignment != null ? assignment.layerType : "UNKNOWN"));
            int chunkX = ChunkPos.getX(key);
            int chunkZ = ChunkPos.getZ(key);
            acc.sumX += (chunkX << 4) + 8.0;
            acc.sumZ += (chunkZ << 4) + 8.0;
            acc.count++;
        }

        List<LayerLabel> labels = new ArrayList<>();
        List<LayerAccumulator> ordered = byLayer.values().stream()
                .sorted(Comparator.comparingInt(v -> v.layerIndex))
                .toList();
        int labelNo = 1;
        for (LayerAccumulator acc : ordered) {
            if (acc.count <= 0) continue;
            Point2D centroid = new Point2D.Double(acc.sumX / acc.count, acc.sumZ / acc.count);
            labels.add(new LayerLabel(labelNo++, acc.layerIndex, acc.layerType, acc.count, centroid));
        }
        return labels;
    }

    private static JsonObject buildLayerLabelDoc(List<LayerLabel> labels) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "city_c2_layer_labels");
        JsonArray arr = new JsonArray();
        for (LayerLabel label : labels) {
            JsonObject item = new JsonObject();
            item.addProperty("label_no", label.labelNo);
            item.addProperty("layer_index", label.layerIndex);
            item.addProperty("layer_type", label.layerType);
            item.addProperty("chunk_count", label.chunkCount);
            item.addProperty("centroid_x", label.centroid.getX());
            item.addProperty("centroid_z", label.centroid.getY());
            arr.add(item);
        }
        root.add("labels", arr);
        return root;
    }

    private enum ValueField {
        HEIGHT,
        TEMPERATURE
    }

    private static final class LayerAccumulator {
        final int layerIndex;
        final String layerType;
        double sumX;
        double sumZ;
        int count;

        LayerAccumulator(int layerIndex, String layerType) {
            this.layerIndex = layerIndex;
            this.layerType = layerType == null || layerType.isBlank() ? "UNKNOWN" : layerType;
        }
    }

    private static final class LayerLabel {
        final int labelNo;
        final int layerIndex;
        final String layerType;
        final int chunkCount;
        final Point2D centroid;

        LayerLabel(int labelNo, int layerIndex, String layerType, int chunkCount, Point2D centroid) {
            this.labelNo = labelNo;
            this.layerIndex = layerIndex;
            this.layerType = layerType;
            this.chunkCount = chunkCount;
            this.centroid = centroid;
        }
    }
}

