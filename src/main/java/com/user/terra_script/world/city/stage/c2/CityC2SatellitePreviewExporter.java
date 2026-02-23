package com.user.terra_script.world.city.stage.c2;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.user.terra_script.domain.world.scan.ScanPixel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class CityC2SatellitePreviewExporter {
    private static final int PREVIEW_SIZE = 512;
    private static final int SEA_LEVEL = 63;
    private static final String IMAGE_FILE = "C2_satellite_preview.png";
    private static final String LEGEND_FILE = "C2_satellite_preview.legend.json";

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
            Map<Long, ?> claimedChunks
    ) throws Exception {
        JsonObject out = new JsonObject();
        if (server == null || cityId == null || cityId.isBlank() || map == null || map.length == 0 || map[0] == null) {
            out.addProperty("generated", false);
            out.addProperty("reason", "invalid_input");
            return out;
        }

        BufferedImage image = new BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_ARGB);
        int srcW = map.length;
        int srcH = map[0].length;
        for (int px = 0; px < PREVIEW_SIZE; px++) {
            int sx = mapIndex(px, srcW);
            for (int pz = 0; pz < PREVIEW_SIZE; pz++) {
                int sz = mapIndex(pz, srcH);
                ScanPixel sp = map[sx][sz];
                image.setRGB(px, pz, classify(sp));
            }
        }

        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        drawCityChunkGrid(g, claimedChunks, originX, originZ, worldWidth, worldHeight);
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
        g.dispose();

        Path cityDir = server.getWorldPath(LevelResource.ROOT)
                .resolve("terra_script")
                .resolve("cities")
                .resolve(cityId);
        Files.createDirectories(cityDir);
        ImageIO.write(image, "png", cityDir.resolve(IMAGE_FILE).toFile());

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
        Files.writeString(cityDir.resolve(LEGEND_FILE), legend.toString(), StandardCharsets.UTF_8);

        out.addProperty("generated", true);
        out.addProperty("image", "cities/" + cityId + "/" + IMAGE_FILE);
        out.addProperty("legend", "cities/" + cityId + "/" + LEGEND_FILE);
        return out;
    }

    private static void drawCityChunkGrid(
            Graphics2D g,
            Map<Long, ?> claimedChunks,
            int originX,
            int originZ,
            int worldWidth,
            int worldHeight
    ) {
        if (g == null || claimedChunks == null || claimedChunks.isEmpty()) return;

        g.setStroke(new BasicStroke(1f));

        // Draw thin internal chunk mesh so C2 grid ownership is visible.
        g.setColor(new Color(255, 255, 255, 96));
        for (Long key : claimedChunks.keySet()) {
            if (key == null) continue;
            int chunkX = ChunkPos.getX(key);
            int chunkZ = ChunkPos.getZ(key);
            int minX = chunkX << 4;
            int minZ = chunkZ << 4;
            int px0 = toPreviewCoord(minX, originX, worldWidth);
            int pz0 = toPreviewCoord(minZ, originZ, worldHeight);
            int px1 = toPreviewCoord(minX + 16, originX, worldWidth);
            int pz1 = toPreviewCoord(minZ + 16, originZ, worldHeight);
            int w = Math.max(1, px1 - px0);
            int h = Math.max(1, pz1 - pz0);
            g.drawRect(px0, pz0, w, h);
        }

        // Highlight external outline for quick shape recognition.
        g.setColor(new Color(0, 0, 0, 200));
        for (Long key : claimedChunks.keySet()) {
            if (key == null) continue;
            int chunkX = ChunkPos.getX(key);
            int chunkZ = ChunkPos.getZ(key);
            int minX = chunkX << 4;
            int minZ = chunkZ << 4;
            int px0 = toPreviewCoord(minX, originX, worldWidth);
            int pz0 = toPreviewCoord(minZ, originZ, worldHeight);
            int px1 = toPreviewCoord(minX + 16, originX, worldWidth);
            int pz1 = toPreviewCoord(minZ + 16, originZ, worldHeight);

            boolean hasWest = claimedChunks.containsKey(ChunkPos.asLong(chunkX - 1, chunkZ));
            boolean hasEast = claimedChunks.containsKey(ChunkPos.asLong(chunkX + 1, chunkZ));
            boolean hasNorth = claimedChunks.containsKey(ChunkPos.asLong(chunkX, chunkZ - 1));
            boolean hasSouth = claimedChunks.containsKey(ChunkPos.asLong(chunkX, chunkZ + 1));

            if (!hasWest) g.drawLine(px0, pz0, px0, pz1);
            if (!hasEast) g.drawLine(px1, pz0, px1, pz1);
            if (!hasNorth) g.drawLine(px0, pz0, px1, pz0);
            if (!hasSouth) g.drawLine(px0, pz1, px1, pz1);
        }
    }

    private static int mapIndex(int out, int srcSize) {
        if (srcSize <= 1) return 0;
        int idx = (int) Math.floor(out * (srcSize / (double) PREVIEW_SIZE));
        return Math.max(0, Math.min(srcSize - 1, idx));
    }

    private static int toPreviewCoord(int worldCoord, int originCoord, int worldSpan) {
        if (worldSpan <= 1) return 0;
        double ratio = (worldCoord - originCoord) / (double) Math.max(1, worldSpan - 1);
        int coord = (int) Math.round(ratio * (PREVIEW_SIZE - 1));
        return Math.max(0, Math.min(PREVIEW_SIZE - 1, coord));
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
