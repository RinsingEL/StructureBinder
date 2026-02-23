package com.user.terra_script.world.city.stage.c2;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.user.terra_script.domain.world.scan.ScanPixel;
import com.user.terra_script.world.city.CityInstance;
import com.user.terra_script.world.city.district.District;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CityC3PolygonPreviewExporter {
    private static final int PREVIEW_SIZE = 512;
    private static final int SEA_LEVEL = 63;
    private static final String IMAGE_FILE = "C3_polygon_preview.png";
    private static final String LEGEND_FILE = "C3_polygon_preview.legend.json";

    private CityC3PolygonPreviewExporter() {}

    public static JsonObject export(
            MinecraftServer server,
            String cityId,
            CityInstance city,
            CityC2ScanBinaryIO.C2ScanData scanData,
            CityC3OwnershipIO.OwnershipData ownershipData
    ) throws Exception {
        JsonObject out = new JsonObject();
        if (server == null || cityId == null || cityId.isBlank() || city == null || scanData == null
                || scanData.map == null || scanData.map.length == 0 || scanData.map[0] == null) {
            out.addProperty("generated", false);
            out.addProperty("reason", "invalid_input");
            return out;
        }

        BufferedImage image = new BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_ARGB);
        renderTerrain(image, scanData.map);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        drawPolygons(g, city, scanData, ownershipData);
        drawCenterCross(g, city.config != null ? city.config.centerX : 0, city.config != null ? city.config.centerZ : 0, scanData);
        g.dispose();

        Path cityDir = server.getWorldPath(LevelResource.ROOT)
                .resolve("terra_script")
                .resolve("cities")
                .resolve(cityId);
        Files.createDirectories(cityDir);
        ImageIO.write(image, "png", cityDir.resolve(IMAGE_FILE).toFile());

        JsonObject legend = new JsonObject();
        legend.addProperty("image", IMAGE_FILE);
        legend.addProperty("type", "city_c3_polygon_preview");
        legend.addProperty("city_id", cityId);
        legend.addProperty("source_scan", "C2_step_scan");
        legend.addProperty("scan_step", scanData.step);
        legend.addProperty("origin_x", scanData.originX);
        legend.addProperty("origin_z", scanData.originZ);
        legend.addProperty("width_blocks", scanData.widthBlocks);
        legend.addProperty("height_blocks", scanData.heightBlocks);
        legend.addProperty("district_count", city.districts != null ? city.districts.size() : 0);
        legend.addProperty("ownership_step", ownershipData != null ? ownershipData.step : -1);
        legend.add("district_codes", buildDistrictCodeLegend(city));
        JsonArray resolution = new JsonArray();
        resolution.add(PREVIEW_SIZE);
        resolution.add(PREVIEW_SIZE);
        legend.add("resolution", resolution);
        Files.writeString(cityDir.resolve(LEGEND_FILE), legend.toString(), StandardCharsets.UTF_8);

        out.addProperty("generated", true);
        out.addProperty("image", "cities/" + cityId + "/" + IMAGE_FILE);
        out.addProperty("legend", "cities/" + cityId + "/" + LEGEND_FILE);
        out.addProperty("source_scan_step", scanData.step);
        return out;
    }

    private static void renderTerrain(BufferedImage image, ScanPixel[][] map) {
        int srcW = map.length;
        int srcH = map[0].length;
        for (int px = 0; px < PREVIEW_SIZE; px++) {
            int sx = mapIndex(px, srcW);
            for (int pz = 0; pz < PREVIEW_SIZE; pz++) {
                int sz = mapIndex(pz, srcH);
                image.setRGB(px, pz, classify(map[sx][sz]));
            }
        }
    }

    private static void drawPolygons(
            Graphics2D g,
            CityInstance city,
            CityC2ScanBinaryIO.C2ScanData scanData,
            CityC3OwnershipIO.OwnershipData ownershipData
    ) {
        if (city.districts == null || city.districts.isEmpty()) return;
        List<District> sorted = new ArrayList<>(city.districts);
        sorted.sort(Comparator.comparingInt(d -> d.id));
        Map<Integer, Integer> codeByDistrict = new HashMap<>();
        Map<Integer, Color> colorByDistrict = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            codeByDistrict.put(sorted.get(i).id, i + 1);
            colorByDistrict.put(sorted.get(i).id, colorByLayer(sorted.get(i).layerIndex));
        }

        if (ownershipData != null && ownershipData.owner != null) {
            drawOwnershipOverlay(g, ownershipData, scanData, colorByDistrict);
        } else {
            drawPolygonFallback(g, sorted, scanData);
        }

        for (District district : sorted) {
            if (district == null) continue;
            int cx = toPreviewCoord(district.centerX, scanData.originX, scanData.widthBlocks);
            int cz = toPreviewCoord(district.centerZ, scanData.originZ, scanData.heightBlocks);
            int code = codeByDistrict.getOrDefault(district.id, district.id);
            drawNumberBadge(g, cx, cz, String.valueOf(code));
        }
    }

    private static void drawPolygonFallback(Graphics2D g, List<District> sorted, CityC2ScanBinaryIO.C2ScanData scanData) {
        g.setStroke(new BasicStroke(2f));
        for (District district : sorted) {
            if (district == null) continue;
            List<double[]> poly = district.polygonVertices;
            if (poly == null || poly.size() < 3) continue;
            int n = poly.size();
            int[] xs = new int[n];
            int[] zs = new int[n];
            for (int i = 0; i < n; i++) {
                double[] v = poly.get(i);
                if (v == null || v.length < 2) continue;
                xs[i] = toPreviewCoord(v[0], scanData.originX, scanData.widthBlocks);
                zs[i] = toPreviewCoord(v[1], scanData.originZ, scanData.heightBlocks);
            }
            Color color = colorByLayer(district.layerIndex);
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 55));
            g.fillPolygon(xs, zs, n);
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 220));
            g.drawPolygon(xs, zs, n);
        }
    }

    private static void drawOwnershipOverlay(
            Graphics2D g,
            CityC3OwnershipIO.OwnershipData ownershipData,
            CityC2ScanBinaryIO.C2ScanData scanData,
            Map<Integer, Color> colorByDistrict
    ) {
        int[][] owner = ownershipData.owner;
        if (owner == null || owner.length == 0 || owner[0] == null) return;

        for (int px = 0; px < PREVIEW_SIZE; px++) {
            int worldX = toWorldCoord(px, scanData.originX, scanData.widthBlocks);
            int ox = worldX - ownershipData.originX;
            if (ox < 0 || ox >= ownershipData.width) continue;
            for (int pz = 0; pz < PREVIEW_SIZE; pz++) {
                int worldZ = toWorldCoord(pz, scanData.originZ, scanData.heightBlocks);
                int oz = worldZ - ownershipData.originZ;
                if (oz < 0 || oz >= ownershipData.height) continue;
                int districtId = owner[ox][oz];
                if (districtId < 0) continue;
                Color base = colorByDistrict.getOrDefault(districtId, new Color(180, 180, 180));
                g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 44));
                g.fillRect(px, pz, 1, 1);
            }
        }

        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(20, 20, 20, 210));
        for (int x = 0; x < ownershipData.width; x++) {
            for (int z = 0; z < ownershipData.height; z++) {
                int id = owner[x][z];
                if (id < 0) continue;
                int worldX = ownershipData.originX + x;
                int worldZ = ownershipData.originZ + z;
                if (x + 1 >= ownershipData.width || owner[x + 1][z] != id) {
                    int sx = toPreviewCoord(worldX + 1, scanData.originX, scanData.widthBlocks);
                    int sy0 = toPreviewCoord(worldZ, scanData.originZ, scanData.heightBlocks);
                    int sy1 = toPreviewCoord(worldZ + 1, scanData.originZ, scanData.heightBlocks);
                    g.drawLine(sx, sy0, sx, sy1);
                }
                if (z + 1 >= ownershipData.height || owner[x][z + 1] != id) {
                    int sy = toPreviewCoord(worldZ + 1, scanData.originZ, scanData.heightBlocks);
                    int sx0 = toPreviewCoord(worldX, scanData.originX, scanData.widthBlocks);
                    int sx1 = toPreviewCoord(worldX + 1, scanData.originX, scanData.widthBlocks);
                    g.drawLine(sx0, sy, sx1, sy);
                }
            }
        }
    }

    private static void drawNumberBadge(Graphics2D g, int cx, int cz, String text) {
        if (text == null) return;
        FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(text);
        int radius = Math.max(8, (textWidth + 8) / 2);
        g.setColor(new Color(0, 0, 0, 210));
        g.fillOval(cx - radius, cz - radius, radius * 2, radius * 2);
        g.setColor(new Color(255, 255, 255, 245));
        int tx = cx - (textWidth / 2);
        int ty = cz + ((fm.getAscent() - fm.getDescent()) / 2);
        g.drawString(text, tx, ty);
    }

    private static JsonArray buildDistrictCodeLegend(CityInstance city) {
        JsonArray arr = new JsonArray();
        if (city == null || city.districts == null || city.districts.isEmpty()) return arr;
        List<District> sorted = new ArrayList<>(city.districts);
        sorted.sort(Comparator.comparingInt(d -> d.id));
        for (int i = 0; i < sorted.size(); i++) {
            District d = sorted.get(i);
            JsonObject item = new JsonObject();
            item.addProperty("code", i + 1);
            item.addProperty("district_id", d.id);
            item.addProperty("layer_index", d.layerIndex);
            item.addProperty("zone_type", d.zoneType);
            arr.add(item);
        }
        return arr;
    }

    private static void drawCenterCross(Graphics2D g, int centerX, int centerZ, CityC2ScanBinaryIO.C2ScanData scanData) {
        int cx = toPreviewCoord(centerX, scanData.originX, scanData.widthBlocks);
        int cz = toPreviewCoord(centerZ, scanData.originZ, scanData.heightBlocks);
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(0, 0, 0, 220));
        g.drawLine(cx - 10, cz, cx + 10, cz);
        g.drawLine(cx, cz - 10, cx, cz + 10);
        g.setColor(new Color(255, 255, 255, 240));
        g.drawLine(cx - 8, cz, cx + 8, cz);
        g.drawLine(cx, cz - 8, cx, cz + 8);
        g.fillOval(cx - 3, cz - 3, 6, 6);
    }

    private static Color colorByLayer(int layerIndex) {
        return switch (Math.max(0, layerIndex) % 6) {
            case 0 -> new Color(220, 70, 70);
            case 1 -> new Color(75, 140, 225);
            case 2 -> new Color(80, 170, 95);
            case 3 -> new Color(190, 135, 60);
            case 4 -> new Color(150, 90, 190);
            default -> new Color(90, 180, 180);
        };
    }

    private static int mapIndex(int out, int srcSize) {
        if (srcSize <= 1) return 0;
        int idx = (int) Math.floor(out * (srcSize / (double) PREVIEW_SIZE));
        return Math.max(0, Math.min(srcSize - 1, idx));
    }

    private static int toPreviewCoord(double worldCoord, int originCoord, int worldSpan) {
        if (worldSpan <= 1) return 0;
        double ratio = (worldCoord - originCoord) / Math.max(1.0, worldSpan - 1.0);
        int coord = (int) Math.round(ratio * (PREVIEW_SIZE - 1));
        return Math.max(0, Math.min(PREVIEW_SIZE - 1, coord));
    }

    private static int toWorldCoord(int previewCoord, int originCoord, int worldSpan) {
        if (worldSpan <= 1) return originCoord;
        double ratio = previewCoord / (double) Math.max(1, PREVIEW_SIZE - 1);
        return originCoord + (int) Math.round(ratio * Math.max(1, worldSpan - 1));
    }

    private static int classify(ScanPixel p) {
        if (p == null) return 0xFF1F4E79;
        if (!p.isLand()) return 0xFF1F4E79;
        int h = p.height();
        if (h < SEA_LEVEL) return 0xFF1F4E79;
        if (h < 70) return 0xFFA7D08C;
        if (h < 110) return 0xFF70AD47;
        if (h < 160) return 0xFFC9B458;
        if (h < 220) return 0xFF8B5A2B;
        return 0xFFD9D9D9;
    }
}
