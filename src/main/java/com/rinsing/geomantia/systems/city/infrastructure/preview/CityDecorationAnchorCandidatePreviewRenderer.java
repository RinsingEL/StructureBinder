package com.rinsing.geomantia.systems.city.infrastructure.preview;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgramPlan;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationAnchorCandidatePlanner;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Renders one compact decision image for a key-decoration anchor candidate set. */
public final class CityDecorationAnchorCandidatePreviewRenderer {
    private static final int WIDTH = 1000;
    private static final int HEIGHT = 760;
    private static final int LEFT = 48;
    private static final int TOP = 88;
    private static final int RIGHT = 948;
    private static final int BOTTOM = 712;
    private static final Color[] CANDIDATE_COLORS = {
            new Color(35, 112, 178), new Color(222, 126, 43), new Color(53, 142, 83),
            new Color(173, 70, 96), new Color(126, 85, 169), new Color(32, 143, 150),
            new Color(155, 125, 36), new Color(93, 101, 110)
    };

    public JsonObject render(CompiledDecorationProgramPlan plan,
                             JsonObject candidateSet,
                             Path outputDirectory) throws IOException {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(candidateSet, "candidateSet");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        String programId = requiredString(candidateSet, "programId");
        CompiledDecorationProgram program = plan.programs().stream()
                .filter(value -> value.programId().equals(programId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "CITY_DECORATION_ANCHOR_CANDIDATE_PROGRAM_UNKNOWN: " + programId));
        if (!CityDecorationAnchorCandidatePlanner.CANDIDATE_SET_SCHEMA.equals(
                requiredString(candidateSet, "schemaVersion"))) {
            throw new IllegalArgumentException("CITY_DECORATION_ANCHOR_CANDIDATE_SET_SCHEMA_UNSUPPORTED");
        }

        Files.createDirectories(outputDirectory);
        Path imagePath = outputDirectory.resolve("city_decoration_anchor_candidates_"
                + safe(programId) + ".png").toAbsolutePath().normalize();
        JsonArray candidates = candidateSet.getAsJsonArray("candidates");
        JsonArray fixedDecorations = candidateSet.getAsJsonArray("fixedDecorationObstacles");
        renderImage(program, plan.hardObstacles(), fixedDecorations, candidates, imagePath);

        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", "city_decoration_anchor_candidate_preview.v0.1");
        result.addProperty("cityId", plan.cityId());
        result.addProperty("programId", programId);
        result.addProperty("fileName", imagePath.getFileName().toString());
        result.addProperty("path", imagePath.toString());
        result.addProperty("candidateCount", candidates.size());
        result.addProperty("terrainSampling", "not_performed");
        return result;
    }

    private static void renderImage(CompiledDecorationProgram program,
                                    List<CompiledDecorationProgramPlan.HardObstacle> allObstacles,
                                    JsonArray fixedDecorations,
                                    JsonArray candidates,
                                    Path imagePath) throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(246, 246, 242));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            g.setColor(new Color(255, 255, 252));
            g.fillRect(LEFT, TOP, RIGHT - LEFT, BOTTOM - TOP);
            BlockBounds crop = expand(program.targetMask().bounds(), 3);
            Transform transform = new Transform(crop);

            g.setColor(new Color(188, 193, 186));
            for (BlockBounds member : program.targetMask().memberBounds()) {
                fillBounds(g, transform, member);
            }
            for (CompiledDecorationProgramPlan.HardObstacle obstacle : allObstacles) {
                if (obstacle.blockBounds().overlaps(crop)) {
                    g.setColor(new Color(194, 65, 58, 145));
                    fillBounds(g, transform, obstacle.blockBounds());
                    g.setColor(new Color(130, 36, 33, 220));
                    g.setStroke(new BasicStroke(1.6f));
                    drawBounds(g, transform, obstacle.blockBounds());
                }
            }
            for (JsonElement element : fixedDecorations) {
                JsonObject fixed = element.getAsJsonObject();
                BlockBounds clearance = bounds(fixed.getAsJsonObject("clearanceBounds"));
                BlockBounds footprint = bounds(fixed.getAsJsonObject("footprintBounds"));
                g.setColor(new Color(104, 76, 151, 45));
                fillBounds(g, transform, clearance);
                g.setColor(new Color(104, 76, 151, 160));
                g.setStroke(new BasicStroke(1.4f));
                drawBounds(g, transform, clearance);
                g.setColor(new Color(88, 57, 139, 145));
                fillBounds(g, transform, footprint);
            }

            for (int index = candidates.size() - 1; index >= 0; index--) {
                JsonObject candidate = candidates.get(index).getAsJsonObject();
                Color base = CANDIDATE_COLORS[index % CANDIDATE_COLORS.length];
                BlockBounds clearance = bounds(candidate.getAsJsonObject("clearanceBounds"));
                BlockBounds footprint = bounds(candidate.getAsJsonObject("footprintBounds"));
                g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 38));
                fillBounds(g, transform, clearance);
                g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 130));
                g.setStroke(new BasicStroke(1.2f));
                drawBounds(g, transform, clearance);
                g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 175));
                fillBounds(g, transform, footprint);
                g.setColor(base.darker());
                g.setStroke(new BasicStroke(2.0f));
                drawBounds(g, transform, footprint);
                JsonObject anchor = candidate.getAsJsonObject("worldAnchor");
                int x = transform.x(anchor.get("x").getAsInt());
                int z = transform.z(anchor.get("z").getAsInt());
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
                g.setColor(new Color(20, 22, 20));
                g.drawString(Integer.toString(index + 1), x + 3, z - 3);
            }

            g.setColor(new Color(30, 32, 30));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 19));
            g.drawString("Key decoration anchor candidates: " + trim(program.programId(), 52), 36, 32);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            g.drawString("target mask=" + program.targetMask().maskId() + "  candidates=" + candidates.size()
                    + "  terrain=not_performed", 36, 56);
            g.setColor(new Color(72, 76, 72));
            g.setStroke(new BasicStroke(1.4f));
            g.drawRect(LEFT, TOP, RIGHT - LEFT, BOTTOM - TOP);
        } finally {
            g.dispose();
        }
        if (!ImageIO.write(image, "png", imagePath.toFile())) {
            throw new IOException("CITY_DECORATION_ANCHOR_CANDIDATE_PREVIEW_PNG_WRITER_UNAVAILABLE: " + imagePath);
        }
    }

    private static void fillBounds(Graphics2D g, Transform transform, BlockBounds bounds) {
        int left = transform.x(bounds.minX());
        int top = transform.z(bounds.minZ());
        int right = transform.x(bounds.maxX() + 1);
        int bottom = transform.z(bounds.maxZ() + 1);
        g.fillRect(Math.min(left, right), Math.min(top, bottom), Math.max(1, Math.abs(right - left)),
                Math.max(1, Math.abs(bottom - top)));
    }

    private static void drawBounds(Graphics2D g, Transform transform, BlockBounds bounds) {
        int left = transform.x(bounds.minX());
        int top = transform.z(bounds.minZ());
        int right = transform.x(bounds.maxX() + 1);
        int bottom = transform.z(bounds.maxZ() + 1);
        g.drawRect(Math.min(left, right), Math.min(top, bottom), Math.max(1, Math.abs(right - left)),
                Math.max(1, Math.abs(bottom - top)));
    }

    private static BlockBounds bounds(JsonObject value) {
        return new BlockBounds(value.get("minX").getAsInt(), value.get("minZ").getAsInt(),
                value.get("maxX").getAsInt(), value.get("maxZ").getAsInt());
    }

    private static BlockBounds expand(BlockBounds bounds, int margin) {
        return new BlockBounds(bounds.minX() - margin, bounds.minZ() - margin,
                bounds.maxX() + margin, bounds.maxZ() + margin);
    }

    private static String requiredString(JsonObject value, String key) {
        JsonElement element = value.get(key);
        if (element == null || !element.isJsonPrimitive() || element.getAsString().isBlank()) {
            throw new IllegalArgumentException("CITY_DECORATION_ANCHOR_CANDIDATE_PREVIEW_FIELD_REQUIRED: " + key);
        }
        return element.getAsString();
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "~";
    }

    private static final class Transform {
        private final int minX;
        private final int minZ;
        private final double scale;
        private final double offsetX;
        private final double offsetZ;

        Transform(BlockBounds crop) {
            this.minX = crop.minX();
            this.minZ = crop.minZ();
            double scaleX = (RIGHT - LEFT) / (double) Math.max(1, crop.widthBlocks());
            double scaleZ = (BOTTOM - TOP) / (double) Math.max(1, crop.heightBlocks());
            this.scale = Math.min(scaleX, scaleZ);
            this.offsetX = LEFT + ((RIGHT - LEFT) - crop.widthBlocks() * scale) / 2.0D;
            this.offsetZ = TOP + ((BOTTOM - TOP) - crop.heightBlocks() * scale) / 2.0D;
        }

        int x(int worldX) {
            return (int) Math.round(offsetX + (worldX - minX) * scale);
        }

        int z(int worldZ) {
            return (int) Math.round(offsetZ + (worldZ - minZ) * scale);
        }
    }
}
