package com.user.terra_script.domain.world.stage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.user.terra_script.domain.world.scan.ScanPixel;
import com.user.terra_script.util.DBSCAN;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class QueryRegionPreviewExporter {
    private static final int PREVIEW_SIZE = 512;
    private static final int SEA_LEVEL = 63;

    private QueryRegionPreviewExporter() {}

    public static JsonObject export(
            MinecraftServer server,
            String targetType,
            String targetId,
            ScanPixel[][] mapData,       // 新增：完整的底层网格数据
            int mapWorldMinX,            // 新增：网格的绝对坐标
            int mapWorldMinZ,            // 新增：网格的绝对坐标
            int mapStep,                 // 新增：网格精度
            List<ScanPixel> basePixels,  // 用于计算边界
            List<OverlayInput> overlays
    ) {
        JsonObject result = new JsonObject();
        try {
            if (server == null || basePixels == null || basePixels.isEmpty() || mapData == null) {
                result.addProperty("generated", false);
                result.addProperty("reason", "base_pixels_or_map_missing");
                return result;
            }

            // 1. 计算候选区域的包围盒
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (ScanPixel p : basePixels) {
                if (p == null) continue;
                minX = Math.min(minX, p.x());
                maxX = Math.max(maxX, p.x());
                minZ = Math.min(minZ, p.z());
                maxZ = Math.max(maxZ, p.z());
            }
            
            // 增加一点边缘留白 (Padding)，让地图看起来不那么局促
            int paddingX = Math.max(16, (maxX - minX) / 20);
            int paddingZ = Math.max(16, (maxZ - minZ) / 20);
            minX -= paddingX;
            maxX += paddingX;
            minZ -= paddingZ;
            maxZ += paddingZ;

            if (minX >= maxX || minZ >= maxZ) {
                result.addProperty("generated", false);
                result.addProperty("reason", "invalid_bounds");
                return result;
            }

            BufferedImage image = new BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_ARGB);
            int mapW = mapData.length;
            int mapH = mapData[0].length;

            // 2. 绘制连续的背景地形 (包含海洋)
            for (int x = 0; x < PREVIEW_SIZE; x++) {
                for (int z = 0; z < PREVIEW_SIZE; z++) {
                    double worldX = minX + (x / (double) PREVIEW_SIZE) * (maxX - minX);
                    double worldZ = minZ + (z / (double) PREVIEW_SIZE) * (maxZ - minZ);

                    int gridX = (int) Math.floor((worldX - mapWorldMinX) / mapStep);
                    int gridZ = (int) Math.floor((worldZ - mapWorldMinZ) / mapStep);

                    double height = SEA_LEVEL - 10; // 默认深蓝海洋
                    if (gridX >= 0 && gridX < mapW && gridZ >= 0 && gridZ < mapH) {
                        ScanPixel p = mapData[gridX][gridZ];
                        if (p != null) height = p.height();
                    }
                    image.setRGB(x, z, classifyHeightColor(height));
                }
            }

            // 3. 绘制彩色候选色块
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            JsonArray bins = new JsonArray();
            int overlayCount = overlays == null ? 0 : overlays.size();
            List<Integer> palette = buildDistinctPalette(overlayCount);
            for (int i = 0; i < overlayCount; i++) {
                OverlayInput overlay = overlays.get(i);
                if (overlay == null || overlay.points == null || overlay.points.isEmpty()) continue;

                int color = palette.get(i);
                String colorHex = toHex(color);

                // 使用带有透明度(Alpha)的画笔来绘制，保留下方的地形纹理 (150/255 透明度)
                g.setColor(new Color((color & 0xFFFFFF) | (150 << 24), true)); 
                long sx = 0L;
                long sz = 0L;
                
                for (DBSCAN.Point p : overlay.points) {
                    int ix = toPreviewIndex(p.x, minX, maxX);
                    int iz = toPreviewIndex(p.z, minZ, maxZ);
                    // 用 3x3 的方块代替点，彻底消除“打孔网格”效应
                    g.fillRect(ix - 1, iz - 1, 3, 3);
                    sx += p.x;
                    sz += p.z;
                }

                int cx = toPreviewIndex((int) (sx / overlay.points.size()), minX, maxX);
                int cz = toPreviewIndex((int) (sz / overlay.points.size()), minZ, maxZ);
                
                g.setFont(new Font("SansSerif", Font.BOLD, 18));
                drawLabel(g, String.valueOf(overlay.previewLabel), cx, cz, color);

                JsonObject bin = new JsonObject();
                bin.addProperty("label", String.valueOf(overlay.previewLabel));
                bin.addProperty("cluster_id", overlay.clusterId);
                bin.addProperty("cluster_label", overlay.label);
                bin.addProperty("color", colorHex);
                bins.add(bin);
            }
            g.dispose();

            // 4. 保存文件
            String safeTarget = sanitize(targetType + "_" + targetId);
            String imageName = "query_region_preview_" + safeTarget + ".png";
            String legendName = "query_region_preview_" + safeTarget + ".legend.json";
            String cacheName = "query_region_preview_" + safeTarget + ".cache.json";

            Path dir = server.getWorldPath(LevelResource.ROOT).resolve("terra_script").resolve("cache").resolve("query_region");
            Files.createDirectories(dir);
            ImageIO.write(image, "png", dir.resolve(imageName).toFile());
            Files.writeString(dir.resolve(legendName), buildLegend(imageName, bins).toString(), StandardCharsets.UTF_8);

            JsonObject cache = new JsonObject();
            cache.addProperty("target_type", targetType);
            cache.addProperty("target_id", targetId);
            cache.add("clusters", bins);
            Files.writeString(dir.resolve(cacheName), cache.toString(), StandardCharsets.UTF_8);

            result.addProperty("generated", true);
            result.addProperty("preview_image", "cache/query_region/" + imageName);
            result.addProperty("legend", "cache/query_region/" + legendName);
            return result;
        } catch (Exception e) {
            result.addProperty("generated", false);
            result.addProperty("reason", e.getMessage() == null ? "export_failed" : e.getMessage());
            return result;
        }
    }

    private static JsonObject buildLegend(String imageName, JsonArray bins) {
        JsonObject legend = new JsonObject();
        legend.addProperty("image", imageName);
        legend.addProperty("type", "query_region_cluster_overlay");
        JsonArray res = new JsonArray(); res.add(PREVIEW_SIZE); res.add(PREVIEW_SIZE);
        legend.add("resolution", res);
        legend.addProperty("downsample", "point_projection");
        legend.add("bins", bins);
        return legend;
    }

    private static int classifyHeightColor(double h) {
        if (h < SEA_LEVEL) return 0xFF1F4E79;
        if (h < 70) return 0xFFA7D08C;
        if (h < 110) return 0xFF70AD47;
        if (h < 160) return 0xFFC9B458;
        if (h < 220) return 0xFF8B5A2B;
        return 0xFFD9D9D9;
    }

    private static int toPreviewIndex(int value, int min, int max) {
        if (max <= min) return 0;
        double ratio = (value - min) / (double) (max - min);
        int idx = (int) Math.round(ratio * (PREVIEW_SIZE - 1));
        return Math.max(0, Math.min(PREVIEW_SIZE - 1, idx));
    }

    private static void drawLabel(Graphics2D g, String text, int x, int y, int color) {
        if (text == null || text.isBlank()) return;
        int tx = Math.max(4, Math.min(PREVIEW_SIZE - 20, x));
        int ty = Math.max(16, Math.min(PREVIEW_SIZE - 4, y));
        g.setColor(new Color(0, 0, 0, 200));
        g.drawString(text, tx + 1, ty + 1);
        g.setColor(new Color(color));
        g.drawString(text, tx, ty);
    }

    private static List<Integer> buildDistinctPalette(int count) {
        int size = Math.max(0, count);
        List<Integer> colors = new ArrayList<>(size);
        if (size == 0) return colors;

        Random random = new Random(System.nanoTime());
        double hue = random.nextDouble();
        final double goldenStep = 0.6180339887498949; // Golden angle in [0,1)

        for (int i = 0; i < size; i++) {
            hue = (hue + goldenStep) % 1.0;
            float saturation = (float) (0.64 + random.nextDouble() * 0.24); // 0.64 - 0.88
            float brightness = (float) (0.75 + random.nextDouble() * 0.18); // 0.75 - 0.93
            int rgb = Color.HSBtoRGB((float) hue, saturation, brightness);
            colors.add(0xFF000000 | (rgb & 0x00FFFFFF));
        }
        return colors;
    }

    private static String toHex(int argb) { return String.format(Locale.ROOT, "#%06X", (argb & 0x00FFFFFF)); }
    private static String sanitize(String input) { return input == null ? "unknown" : input.replaceAll("[^a-zA-Z0-9._-]", "_"); }

    public static final class OverlayInput {
        public final char previewLabel; public final String label; public final int clusterId; public final List<DBSCAN.Point> points;
        public OverlayInput(char pl, String l, int cid, List<DBSCAN.Point> p) { this.previewLabel = pl; this.label = l; this.clusterId = cid; this.points = p; }
    }
}
