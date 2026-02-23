package com.user.terra_script.domain.territory.stage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.domain.world.scan.ScanPixel;
import com.user.terra_script.world.TerritoryManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class TerritoryPreviewExporter {
    private static final int PREVIEW_SIZE = 512;
    private static final int SEA_LEVEL = 63;
    private static final String IMAGE_FILE = "T3_territory_preview.png";
    private static final String LEGEND_FILE = "T3_territory_preview.legend.json";

    private TerritoryPreviewExporter() {}

    public static JsonObject export(MinecraftServer server, TerritoryManager.TerritoryResult result) {
        JsonObject out = new JsonObject();
        try {
            if (server == null || result == null || result.config == null) {
                out.addProperty("generated", false);
                out.addProperty("reason", "server_or_result_missing");
                return out;
            }
            var holder = ScanResultHolder.get();
            ScanPixel[][] map = TerritoryManager.getExpansionScanMap();
            if (map == null || map.length == 0 || map[0] == null) {
                map = holder.lastScanData;
            }
            String[][] ownership = TerritoryManager.globalOwnershipMap;
            if (map == null || map.length == 0 || map[0] == null || ownership == null) {
                out.addProperty("generated", false);
                out.addProperty("reason", "scan_or_ownership_missing");
                return out;
            }

            int radiusBlocks = holder.scanRadiusChunks * 16;
            int globalMinX = TerritoryManager.getExpansionMinX();
            int globalMinZ = TerritoryManager.getExpansionMinZ();
            int step = TerritoryManager.getExpansionStepBlocks();
            if (step <= 0) step = Math.max(1, holder.scanStep);
            if (globalMinX == 0 && globalMinZ == 0 && (map == holder.lastScanData)) {
                globalMinX = -radiusBlocks;
                globalMinZ = -radiusBlocks;
            }

            int minX = result.stats != null ? result.stats.minX : result.config.capitalX - 512;
            int maxX = result.stats != null ? result.stats.maxX : result.config.capitalX + 512;
            int minZ = result.stats != null ? result.stats.minZ : result.config.capitalZ - 512;
            int maxZ = result.stats != null ? result.stats.maxZ : result.config.capitalZ + 512;
            int pad = 128;
            minX -= pad;
            maxX += pad;
            minZ -= pad;
            maxZ += pad;
            if (minX >= maxX || minZ >= maxZ) {
                out.addProperty("generated", false);
                out.addProperty("reason", "invalid_bounds");
                return out;
            }

            BufferedImage image = new BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_ARGB);
            int territoryColor = 0xFF000000 | (result.config.color & 0x00FFFFFF);

            for (int px = 0; px < PREVIEW_SIZE; px++) {
                for (int pz = 0; pz < PREVIEW_SIZE; pz++) {
                    int worldX = minX + (int) Math.round(px * (maxX - minX) / (double) (PREVIEW_SIZE - 1));
                    int worldZ = minZ + (int) Math.round(pz * (maxZ - minZ) / (double) (PREVIEW_SIZE - 1));
                    int gx = (worldX - globalMinX) / step;
                    int gz = (worldZ - globalMinZ) / step;

                    int base = 0xFF1F4E79;
                    if (gx >= 0 && gx < map.length && gz >= 0 && gz < map[0].length) {
                        ScanPixel sp = map[gx][gz];
                        if (sp != null) {
                            base = classifyHeightColor(sp.height());
                        }
                    }

                    if (gx >= 0 && gx < ownership.length && gz >= 0 && gz < ownership[0].length) {
                        String owner = ownership[gx][gz];
                        if (result.config.id.equals(owner)) {
                            base = blend(base, territoryColor, 0.68);
                        }
                    }
                    image.setRGB(px, pz, base);
                }
            }

            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font("SansSerif", Font.BOLD, 16));
            int cx = (int) Math.round((result.config.capitalX - minX) * (PREVIEW_SIZE - 1) / (double) Math.max(1, (maxX - minX)));
            int cz = (int) Math.round((result.config.capitalZ - minZ) * (PREVIEW_SIZE - 1) / (double) Math.max(1, (maxZ - minZ)));
            cx = Math.max(0, Math.min(PREVIEW_SIZE - 1, cx));
            cz = Math.max(0, Math.min(PREVIEW_SIZE - 1, cz));
            g.setColor(new Color(255, 255, 255, 230));
            g.fillOval(cx - 4, cz - 4, 8, 8);
            // Draw a crosshair at the capital for fast visual pinpointing.
            g.setStroke(new BasicStroke(2f));
            g.setColor(new Color(0, 0, 0, 220));
            g.drawLine(cx - 10, cz, cx + 10, cz);
            g.drawLine(cx, cz - 10, cx, cz + 10);
            g.setColor(new Color(255, 255, 255, 240));
            g.drawLine(cx - 8, cz, cx + 8, cz);
            g.drawLine(cx, cz - 8, cx, cz + 8);
            g.setColor(new Color(0, 0, 0, 220));
            String title = result.config.name + " (" + result.config.id + ")";
            g.drawString(title, 12, 24);
            g.dispose();

            String folder = sanitize(result.config.id + "_" + result.config.name + "_" + result.config.capitalX + "_" + result.config.capitalZ);
            Path dir = server.getWorldPath(LevelResource.ROOT)
                    .resolve("terra_script")
                    .resolve("territory_previews")
                    .resolve(folder);
            Files.createDirectories(dir);
            ImageIO.write(image, "png", dir.resolve(IMAGE_FILE).toFile());

            JsonObject legend = new JsonObject();
            legend.addProperty("image", IMAGE_FILE);
            legend.addProperty("type", "territory_position_preview");
            JsonArray res = new JsonArray();
            res.add(PREVIEW_SIZE);
            res.add(PREVIEW_SIZE);
            legend.add("resolution", res);
            legend.addProperty("territory_id", result.config.id);
            legend.addProperty("territory_name", result.config.name);
            legend.addProperty("territory_color", toHex(territoryColor));
            legend.addProperty("capital_x", result.config.capitalX);
            legend.addProperty("capital_z", result.config.capitalZ);
            Files.writeString(dir.resolve(LEGEND_FILE), legend.toString(), StandardCharsets.UTF_8);

            out.addProperty("generated", true);
            out.addProperty("folder", "territory_previews/" + folder);
            out.addProperty("image", "territory_previews/" + folder + "/" + IMAGE_FILE);
            out.addProperty("legend", "territory_previews/" + folder + "/" + LEGEND_FILE);
            return out;
        } catch (Exception e) {
            out.addProperty("generated", false);
            out.addProperty("reason", e.getMessage() == null ? "preview_export_failed" : e.getMessage());
            return out;
        }
    }

    private static int classifyHeightColor(double h) {
        if (h < SEA_LEVEL) return 0xFF1F4E79;
        if (h < 70) return 0xFFA7D08C;
        if (h < 110) return 0xFF70AD47;
        if (h < 160) return 0xFFC9B458;
        if (h < 220) return 0xFF8B5A2B;
        return 0xFFD9D9D9;
    }

    private static int blend(int base, int overlay, double overlayWeight) {
        double ow = Math.max(0.0, Math.min(1.0, overlayWeight));
        double bw = 1.0 - ow;
        int br = (base >> 16) & 0xFF;
        int bg = (base >> 8) & 0xFF;
        int bb = base & 0xFF;
        int or = (overlay >> 16) & 0xFF;
        int og = (overlay >> 8) & 0xFF;
        int ob = overlay & 0xFF;
        int r = (int) Math.round(br * bw + or * ow);
        int g = (int) Math.round(bg * bw + og * ow);
        int b = (int) Math.round(bb * bw + ob * ow);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static String toHex(int argb) {
        return String.format(Locale.ROOT, "#%06X", (argb & 0x00FFFFFF));
    }

    private static String sanitize(String input) {
        if (input == null || input.isBlank()) return "unknown";
        return input.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
