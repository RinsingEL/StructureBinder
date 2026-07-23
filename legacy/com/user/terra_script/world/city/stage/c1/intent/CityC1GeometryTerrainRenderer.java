package com.user.terra_script.world.city.stage.c1.intent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.user.terra_script.server.mcp.TerritoryController;

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
import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class CityC1GeometryTerrainRenderer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private CityC1GeometryTerrainRenderer() {}

    public static RenderResult render(
            String cityId,
            CityC1ImageIntentModels.PrepareRequest request,
            TerritoryController.WindowSelection selection,
            int sampleStep,
            Set<Long> ownedChunks,
            Path cleanPath,
            Path locatorPath,
            Path locatorJsonPath
    ) throws Exception {
        int imageSize = CityC1ImageIntentModels.IMAGE_SIZE;
        int radiusBlocks = request.radius_blocks;
        CityC1ImageIntentModels.Coordinate coordinate = coordinate(request, selection, radiusBlocks, sampleStep);

        BufferedImage clean = renderTerrain(selection, sampleStep, ownedChunks, imageSize, radiusBlocks);
        BufferedImage locator = copy(clean);
        drawLocator(locator, coordinate, selection, sampleStep);

        Files.createDirectories(cleanPath.getParent());
        ImageIO.write(clean, "png", cleanPath.toFile());
        ImageIO.write(locator, "png", locatorPath.toFile());
        JsonObject locatorJson = locatorJson(cityId, request, coordinate, selection, sampleStep);
        Files.writeString(locatorJsonPath, GSON.toJson(locatorJson), StandardCharsets.UTF_8);

        RenderResult result = new RenderResult();
        result.coordinate = coordinate;
        result.locatorJson = locatorJson;
        result.baseMapInfo = baseMapInfo(selection.records);
        return result;
    }

    public static CityC1ImageIntentModels.Coordinate coordinate(
            CityC1ImageIntentModels.PrepareRequest request,
            TerritoryController.WindowSelection selection,
            int radiusBlocks,
            int sampleStep
    ) {
        CityC1ImageIntentModels.Coordinate coordinate = new CityC1ImageIntentModels.Coordinate();
        coordinate.image_size.add(CityC1ImageIntentModels.IMAGE_SIZE);
        coordinate.image_size.add(CityC1ImageIntentModels.IMAGE_SIZE);
        coordinate.source_step_blocks = sampleStep;
        coordinate.radius_blocks = radiusBlocks;
        coordinate.world_extent_blocks = radiusBlocks * 2;
        coordinate.world_origin_x = selection.centerX - radiusBlocks;
        coordinate.world_origin_z = selection.centerZ - radiusBlocks;
        coordinate.center_x = selection.centerX;
        coordinate.center_z = selection.centerZ;
        coordinate.pixel_to_block = coordinate.world_extent_blocks / (double) CityC1ImageIntentModels.IMAGE_SIZE;
        coordinate.source_grid_size = Math.max(1, coordinate.world_extent_blocks / Math.max(1, sampleStep));
        coordinate.city_scale_bucket = request.city_scale_bucket;
        coordinate.rotation = "north_up";
        coordinate.pixel_to_world_formula = "world_x = world_origin_x + pixel_x * pixel_to_block; world_z = world_origin_z + pixel_z * pixel_to_block";
        coordinate.world_to_pixel_formula = "pixel_x = (world_x - world_origin_x) / pixel_to_block; pixel_z = (world_z - world_origin_z) / pixel_to_block";
        for (int p = 0; p <= CityC1ImageIntentModels.IMAGE_SIZE; p += 64) {
            int pixel = Math.min(p, CityC1ImageIntentModels.IMAGE_SIZE - 1);
            CityC1ImageIntentModels.GridLine vertical = new CityC1ImageIntentModels.GridLine();
            vertical.axis = "x";
            vertical.pixel = pixel;
            vertical.world = coordinate.world_origin_x + (int) Math.round(pixel * coordinate.pixel_to_block);
            coordinate.grid_lines.add(vertical);

            CityC1ImageIntentModels.GridLine horizontal = new CityC1ImageIntentModels.GridLine();
            horizontal.axis = "z";
            horizontal.pixel = pixel;
            horizontal.world = coordinate.world_origin_z + (int) Math.round(pixel * coordinate.pixel_to_block);
            coordinate.grid_lines.add(horizontal);
        }
        return coordinate;
    }

    private static BufferedImage renderTerrain(
            TerritoryController.WindowSelection selection,
            int sampleStep,
            Set<Long> ownedChunks,
            int imageSize,
            int radiusBlocks
    ) {
        BufferedImage image = new BufferedImage(imageSize, imageSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        List<TerritoryController.CellRecord> records = selection.records;
        int minHeight = records.stream().mapToInt(r -> r.height).min().orElse(0);
        int maxHeight = records.stream().mapToInt(r -> r.height).max().orElse(minHeight + 1);
        int heightRange = Math.max(1, maxHeight - minHeight);
        double blockToPixel = imageSize / (radiusBlocks * 2.0);
        int cellPixels = Math.max(1, (int) Math.ceil(sampleStep * blockToPixel));

        g.setColor(new Color(28, 32, 34));
        g.fillRect(0, 0, imageSize, imageSize);
        for (TerritoryController.CellRecord r : records) {
            int px = worldToPixel(r.x, selection.centerX, radiusBlocks, imageSize);
            int pz = worldToPixel(r.z, selection.centerZ, radiusBlocks, imageSize);
            if (px < -cellPixels || pz < -cellPixels || px >= imageSize + cellPixels || pz >= imageSize + cellPixels) continue;
            Color color = terrainColor(r, minHeight, heightRange);
            if (ownedChunks != null && !ownedChunks.isEmpty() && !ownedChunks.contains(net.minecraft.world.level.ChunkPos.asLong(r.x >> 4, r.z >> 4))) {
                color = blend(color, new Color(34, 34, 34), 0.55);
            }
            g.setColor(color);
            g.fillRect(px, pz, cellPixels, cellPixels);
        }
        g.dispose();
        return image;
    }

    private static void drawLocator(
            BufferedImage image,
            CityC1ImageIntentModels.Coordinate coordinate,
            TerritoryController.WindowSelection selection,
            int sampleStep
    ) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(1f));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        for (CityC1ImageIntentModels.GridLine line : coordinate.grid_lines) {
            int p = line.pixel;
            g.setColor(p == 256 ? new Color(255, 235, 80, 180) : new Color(255, 255, 255, 85));
            if ("x".equals(line.axis)) {
                g.drawLine(p, 0, p, CityC1ImageIntentModels.IMAGE_SIZE);
                drawLabel(g, "x=" + line.world, p + 3, 14);
            } else {
                g.drawLine(0, p, CityC1ImageIntentModels.IMAGE_SIZE, p);
                drawLabel(g, "z=" + line.world, 4, p - 4);
            }
        }
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(255, 235, 80, 230));
        g.drawLine(246, 256, 266, 256);
        g.drawLine(256, 246, 256, 266);
        drawLabel(g, "center " + selection.centerX + "," + selection.centerZ + " step=" + sampleStep, 12, 504);
        g.dispose();
    }

    private static JsonObject locatorJson(
            String cityId,
            CityC1ImageIntentModels.PrepareRequest request,
            CityC1ImageIntentModels.Coordinate coordinate,
            TerritoryController.WindowSelection selection,
            int sampleStep
    ) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "c1_terrain_locator");
        root.addProperty("version", CityC1ImageIntentModels.VERSION);
        root.addProperty("city_id", cityId);
        root.addProperty("territory_id", request.territory_id);
        root.addProperty("generated_at", Instant.now().toString());
        root.add("coordinate", CityC1ImageIntentIO.gson().toJsonTree(coordinate));
        JsonObject window = new JsonObject();
        window.addProperty("center_x", selection.centerX);
        window.addProperty("center_z", selection.centerZ);
        window.addProperty("requested_center_x", selection.requestedCenterX);
        window.addProperty("requested_center_z", selection.requestedCenterZ);
        window.addProperty("radius_blocks", coordinate.radius_blocks);
        window.addProperty("world_extent_blocks", coordinate.world_extent_blocks);
        window.addProperty("image_size", CityC1ImageIntentModels.IMAGE_SIZE);
        window.addProperty("sample_step", sampleStep);
        window.addProperty("matched_cells", selection.records.size());
        window.addProperty("reanchored", selection.reanchored);
        root.add("window", window);
        JsonArray required = new JsonArray();
        required.add("city_boundary.polygon");
        required.add("district_polygons[].polygon");
        required.add("road_sketch.paths[].polyline");
        required.add("anchor_points[]");
        root.add("required_output_layers", required);
        return root;
    }

    private static CityC1ImageIntentModels.BaseMapInfo baseMapInfo(List<TerritoryController.CellRecord> records) {
        CityC1ImageIntentModels.BaseMapInfo info = new CityC1ImageIntentModels.BaseMapInfo();
        boolean hasWater = records.stream().anyMatch(r -> r.height <= 62);
        boolean hasLand = records.stream().anyMatch(r -> r.height > 62);
        info.land_water = hasWater && hasLand ? "mixed_land_water" : hasWater ? "mostly_water" : "mostly_land";
        info.contours = "height_and_slope_ramp_from_t4";
        info.existing_city_boundaries = "not_rendered_in_geometry_prepare";
        info.territory_boundary = "outside_t3_chunks_darkened_when_available";
        info.major_geo_notes.add("terrain_clean_has_no_grid_or_labels");
        info.major_geo_notes.add("terrain_locator_grid_lines_are_authoritative_for_coordinates");
        return info;
    }

    private static Color terrainColor(TerritoryController.CellRecord r, int minHeight, int heightRange) {
        double h = clamp01((r.height - minHeight) / (double) heightRange);
        double slope = clamp01(r.slope / 12.0);
        if (r.height <= 62) {
            int blue = (int) Math.round(120 + h * 70);
            return new Color(45, 95, blue);
        }
        int red = (int) Math.round(70 + h * 105 + slope * 35);
        int green = (int) Math.round(105 + h * 95 - slope * 28);
        int blue = (int) Math.round(72 + h * 58 - slope * 18);
        return new Color(clamp(red, 0, 255), clamp(green, 0, 255), clamp(blue, 0, 255));
    }

    private static void drawLabel(Graphics2D g, String text, int x, int y) {
        int width = Math.min(160, Math.max(36, text.length() * 6 + 6));
        int height = 14;
        g.setColor(new Color(0, 0, 0, 135));
        g.fillRect(x - 2, y - 11, width, height);
        g.setColor(Color.WHITE);
        g.drawString(text, x + 1, y);
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return copy;
    }

    private static int worldToPixel(int world, int center, int radiusBlocks, int imageSize) {
        double normalized = (world - (center - radiusBlocks)) / (radiusBlocks * 2.0);
        return (int) Math.round(normalized * (imageSize - 1));
    }

    private static Color blend(Color base, Color overlay, double alpha) {
        double beta = 1.0 - alpha;
        int r = (int) Math.round(base.getRed() * beta + overlay.getRed() * alpha);
        int g = (int) Math.round(base.getGreen() * beta + overlay.getGreen() * alpha);
        int b = (int) Math.round(base.getBlue() * beta + overlay.getBlue() * alpha);
        return new Color(r, g, b);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public static final class RenderResult {
        public CityC1ImageIntentModels.Coordinate coordinate;
        public CityC1ImageIntentModels.BaseMapInfo baseMapInfo;
        public JsonObject locatorJson;
    }
}
