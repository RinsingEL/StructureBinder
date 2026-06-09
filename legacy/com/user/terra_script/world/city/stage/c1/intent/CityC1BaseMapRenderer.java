package com.user.terra_script.world.city.stage.c1.intent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.user.terra_script.world.TerritoryManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.user.terra_script.world.city.stage.c1.intent.CityC1ImageIntentModels.IMAGE_SIZE;

public final class CityC1BaseMapRenderer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private CityC1BaseMapRenderer() {}

    public static CityC1ImageIntentModels.BaseMapInfo render(
            MinecraftServer server,
            CityC1ImageIntentModels.PrepareRequest request,
            CityC1ImageIntentModels.Coordinate coordinate,
            Path imagePath,
            Path legendPath
    ) throws Exception {
        BufferedImage image = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        ServerLevel level = server != null ? server.overworld() : null;
        int waterPixels = 0;
        int outsideTerritoryPixels = 0;
        int minHeight = Integer.MAX_VALUE;
        int maxHeight = Integer.MIN_VALUE;

        for (int z = 0; z < IMAGE_SIZE; z++) {
            for (int x = 0; x < IMAGE_SIZE; x++) {
                int worldX = coordinate.world_origin_x + (int) Math.round(x * coordinate.pixel_to_block);
                int worldZ = coordinate.world_origin_z + (int) Math.round(z * coordinate.pixel_to_block);
                boolean insideTerritory = isInsideTerritory(request.territory_id, worldX, worldZ);
                if (!insideTerritory) outsideTerritoryPixels++;

                Color color;
                if (level != null) {
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1;
                    minHeight = Math.min(minHeight, y);
                    maxHeight = Math.max(maxHeight, y);
                    boolean water = y > level.getMinBuildHeight()
                            && level.getBlockState(new net.minecraft.core.BlockPos(worldX, y, worldZ)).is(Blocks.WATER);
                    if (water) {
                        waterPixels++;
                        color = new Color(74, 137, 177);
                    } else {
                        int shade = Math.max(70, Math.min(190, 90 + y));
                        color = new Color(Math.max(50, shade - 35), Math.max(90, shade), Math.max(55, shade - 30));
                    }
                } else {
                    color = new Color(116, 145, 110);
                }
                if (!insideTerritory) {
                    color = blend(color, new Color(70, 70, 70), 0.45);
                }
                image.setRGB(x, z, color.getRGB());
            }
        }

        drawGrid(g);
        drawCenter(g, request, coordinate);
        drawFrame(g, request, coordinate);
        g.dispose();

        Files.createDirectories(imagePath.getParent());
        ImageIO.write(image, "png", imagePath.toFile());

        CityC1ImageIntentModels.BaseMapInfo info = new CityC1ImageIntentModels.BaseMapInfo();
        info.land_water = level != null ? "sampled_surface_water_pixels=" + waterPixels : "mc_level_unavailable_placeholder";
        info.contours = level != null ? "sampled_height_range=" + minHeight + ".." + maxHeight : "placeholder_no_height_scan";
        info.existing_city_boundaries = "not_rendered_in_c1_v1";
        info.territory_boundary = "outside_territory_pixels=" + outsideTerritoryPixels;
        info.major_geo_notes.add("image_size=512");
        info.major_geo_notes.add("source_step_blocks=8");
        info.major_geo_notes.add("world_extent_blocks=" + coordinate.world_extent_blocks);

        JsonObject legend = new JsonObject();
        legend.addProperty("type", "city_c1_image_intent_base_map");
        legend.addProperty("city_id", request.city_id);
        legend.addProperty("territory_id", request.territory_id);
        legend.addProperty("image", imagePath.getFileName().toString());
        legend.add("coordinate", GSON.toJsonTree(coordinate));
        legend.add("base_map", GSON.toJsonTree(info));
        Files.writeString(legendPath, GSON.toJson(legend), StandardCharsets.UTF_8);
        return info;
    }

    private static boolean isInsideTerritory(String territoryId, int worldX, int worldZ) {
        if (territoryId == null || territoryId.isBlank()) return true;
        long key = ChunkPos.asLong(worldX >> 4, worldZ >> 4);
        return TerritoryManager.isChunkWithinSovereignty(key, territoryId);
    }

    private static void drawGrid(Graphics2D g) {
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(255, 255, 255, 45));
        for (int i = 0; i <= IMAGE_SIZE; i += 64) {
            g.drawLine(i, 0, i, IMAGE_SIZE);
            g.drawLine(0, i, IMAGE_SIZE, i);
        }
    }

    private static void drawCenter(Graphics2D g, CityC1ImageIntentModels.PrepareRequest request, CityC1ImageIntentModels.Coordinate coordinate) {
        int px = (int) Math.round((request.center_x - coordinate.world_origin_x) / coordinate.pixel_to_block);
        int pz = (int) Math.round((request.center_z - coordinate.world_origin_z) / coordinate.pixel_to_block);
        g.setStroke(new BasicStroke(3f));
        g.setColor(new Color(255, 230, 70, 230));
        g.drawLine(px - 12, pz, px + 12, pz);
        g.drawLine(px, pz - 12, px, pz + 12);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.drawString("C", px + 8, pz - 8);
    }

    private static void drawFrame(Graphics2D g, CityC1ImageIntentModels.PrepareRequest request, CityC1ImageIntentModels.Coordinate coordinate) {
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(255, 255, 255, 180));
        g.drawRect(1, 1, IMAGE_SIZE - 3, IMAGE_SIZE - 3);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        String label = request.city_id + " / " + request.city_scale_bucket + " / extent " + coordinate.world_extent_blocks;
        g.setColor(new Color(0, 0, 0, 130));
        g.fillRect(8, 8, Math.min(480, label.length() * 7 + 12), 20);
        g.setColor(Color.WHITE);
        g.drawString(label, 14, 23);
    }

    private static Color blend(Color base, Color overlay, double alpha) {
        double beta = 1.0 - alpha;
        int r = (int) Math.round(base.getRed() * beta + overlay.getRed() * alpha);
        int g = (int) Math.round(base.getGreen() * beta + overlay.getGreen() * alpha);
        int b = (int) Math.round(base.getBlue() * beta + overlay.getBlue() * alpha);
        return new Color(r, g, b);
    }
}

