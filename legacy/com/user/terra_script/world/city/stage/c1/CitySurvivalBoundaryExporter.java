package com.user.terra_script.world.city.stage.c1;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

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
import java.util.Comparator;

public final class CitySurvivalBoundaryExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int PREVIEW_SIZE = 512;
    private static final String PLAN_FILE = "C1_BoundaryPlan.json";
    private static final String REPORT_FILE = "C1_validation_report.json";
    private static final String PREVIEW_FILE = "C1_boundary_preview.png";

    private CitySurvivalBoundaryExporter() {}

    public static JsonObject export(MinecraftServer server, CitySurvivalBoundaryPlanner.Result result) throws Exception {
        JsonObject out = new JsonObject();
        if (server == null || result == null || result.cityId == null || result.cityId.isBlank()) {
            out.addProperty("generated", false);
            out.addProperty("reason", "invalid_input");
            return out;
        }

        Path cityDir = server.getWorldPath(LevelResource.ROOT)
                .resolve("terra_script")
                .resolve("cities")
                .resolve(result.cityId);
        Files.createDirectories(cityDir);

        JsonObject plan = toPlanJson(result);
        JsonObject report = toValidationJson(result);
        Files.writeString(cityDir.resolve(PLAN_FILE), GSON.toJson(plan), StandardCharsets.UTF_8);
        Files.writeString(cityDir.resolve(REPORT_FILE), GSON.toJson(report), StandardCharsets.UTF_8);
        ImageIO.write(renderPreview(result), "png", cityDir.resolve(PREVIEW_FILE).toFile());

        out.addProperty("generated", true);
        out.addProperty("plan", "cities/" + result.cityId + "/" + PLAN_FILE);
        out.addProperty("validation_report", "cities/" + result.cityId + "/" + REPORT_FILE);
        out.addProperty("preview", "cities/" + result.cityId + "/" + PREVIEW_FILE);
        return out;
    }

    public static JsonObject toPlanJson(CitySurvivalBoundaryPlanner.Result result) {
        JsonObject root = new JsonObject();
        root.addProperty("step", "C1_BOUNDARY_PLAN");
        root.addProperty("city_id", result.cityId);
        root.addProperty("territory_id", result.territoryId);
        root.addProperty("status", result.status);
        root.addProperty("attempt", result.attempt);
        root.addProperty("boundary_source", "survival_heatmap");
        root.addProperty("fallback_used", result.fallbackUsed);
        root.addProperty("reanchored", result.reanchored);
        JsonObject center = new JsonObject();
        center.addProperty("requested_chunk_x", result.requestedCenterChunkX);
        center.addProperty("requested_chunk_z", result.requestedCenterChunkZ);
        center.addProperty("anchor_chunk_x", result.anchorChunkX);
        center.addProperty("anchor_chunk_z", result.anchorChunkZ);
        root.add("center", center);
        root.addProperty("target_chunk_count", result.targetChunkCount);
        root.add("allowed_polygon", new JsonArray());
        root.add("claimed_chunks_compat", claimedChunks(result));
        root.add("risk_tags", stringArray(result.riskTags));
        root.add("warnings", stringArray(result.warnings));
        root.add("validation", toValidationJson(result));
        return root;
    }

    public static JsonObject toValidationJson(CitySurvivalBoundaryPlanner.Result result) {
        JsonObject validation = new JsonObject();
        validation.addProperty("status", result.status);
        validation.addProperty("fallback_used", result.fallbackUsed);
        validation.add("blocking_errors", stringArray(result.blockingErrors));
        validation.add("warnings", stringArray(result.warnings));
        validation.add("risk_tags", stringArray(result.riskTags));
        validation.addProperty("claimed_chunk_count", result.choices.size());
        return validation;
    }

    private static JsonArray claimedChunks(CitySurvivalBoundaryPlanner.Result result) {
        JsonArray arr = new JsonArray();
        result.choices.stream()
                .sorted(Comparator.comparingInt((CitySurvivalBoundaryPlanner.ChunkChoice c) -> c.chunkX)
                        .thenComparingInt(c -> c.chunkZ))
                .forEach(choice -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("chunk_x", choice.chunkX);
                    item.addProperty("chunk_z", choice.chunkZ);
                    item.addProperty("layer_index", choice.layerIndex);
                    item.addProperty("layer_type", choice.layerType);
                    item.addProperty("heat", choice.heat);
                    item.addProperty("terrain_risk", choice.terrainRisk);
                    item.addProperty("water_risk", choice.waterRisk);
                    item.addProperty("rough_risk", choice.roughRisk);
                    arr.add(item);
                });
        return arr;
    }

    private static JsonArray stringArray(Iterable<String> values) {
        JsonArray arr = new JsonArray();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) arr.add(value);
            }
        }
        return arr;
    }

    private static BufferedImage renderPreview(CitySurvivalBoundaryPlanner.Result result) {
        BufferedImage image = new BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(42, 73, 58));
        g.fillRect(0, 0, PREVIEW_SIZE, PREVIEW_SIZE);
        if (result.choices.isEmpty()) {
            drawText(g, "C1 survival boundary: no chunks", 18, 28);
            g.dispose();
            return image;
        }

        Bounds bounds = Bounds.of(result);
        int pad = 32;
        int usable = PREVIEW_SIZE - pad * 2;
        double scale = usable / (double) Math.max(1, Math.max(bounds.maxX - bounds.minX + 1, bounds.maxZ - bounds.minZ + 1));
        for (CitySurvivalBoundaryPlanner.ChunkChoice choice : result.choices) {
            int x = pad + (int) Math.round((choice.chunkX - bounds.minX) * scale);
            int z = pad + (int) Math.round((choice.chunkZ - bounds.minZ) * scale);
            int size = Math.max(2, (int) Math.ceil(scale));
            g.setColor(colorFor(choice));
            g.fillRect(x, z, size, size);
        }
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(255, 255, 255, 220));
        for (CitySurvivalBoundaryPlanner.ChunkChoice choice : result.choices) {
            int x = pad + (int) Math.round((choice.chunkX - bounds.minX) * scale);
            int z = pad + (int) Math.round((choice.chunkZ - bounds.minZ) * scale);
            int size = Math.max(2, (int) Math.ceil(scale));
            g.drawRect(x, z, size, size);
        }
        int anchorX = pad + (int) Math.round((result.anchorChunkX - bounds.minX) * scale);
        int anchorZ = pad + (int) Math.round((result.anchorChunkZ - bounds.minZ) * scale);
        g.setColor(new Color(255, 230, 80));
        g.setStroke(new BasicStroke(3f));
        g.drawOval(anchorX - 6, anchorZ - 6, 12, 12);
        drawText(g, "C1 survival boundary / " + result.status, 18, 24);
        drawText(g, "chunks=" + result.choices.size() + " risks=" + result.riskTags, 18, PREVIEW_SIZE - 18);
        g.dispose();
        return image;
    }

    private static Color colorFor(CitySurvivalBoundaryPlanner.ChunkChoice choice) {
        if ("CORE".equals(choice.layerType)) return new Color(255, 214, 102, 220);
        if ("URBAN".equals(choice.layerType)) return new Color(96, 180, 120, 210);
        return new Color(120, 170, 240, 190);
    }

    private static void drawText(Graphics2D g, String text, int x, int y) {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        g.setColor(new Color(0, 0, 0, 155));
        g.fillRoundRect(x - 5, y - 15, Math.min(470, text.length() * 8 + 12), 22, 8, 8);
        g.setColor(Color.WHITE);
        g.drawString(text, x, y);
    }

    private static final class Bounds {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        static Bounds of(CitySurvivalBoundaryPlanner.Result result) {
            Bounds bounds = new Bounds();
            for (CitySurvivalBoundaryPlanner.ChunkChoice choice : result.choices) {
                bounds.minX = Math.min(bounds.minX, choice.chunkX);
                bounds.minZ = Math.min(bounds.minZ, choice.chunkZ);
                bounds.maxX = Math.max(bounds.maxX, choice.chunkX);
                bounds.maxZ = Math.max(bounds.maxZ, choice.chunkZ);
            }
            return bounds;
        }
    }
}
