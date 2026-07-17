package com.rinsing.geomantia.systems.city.infrastructure.preview;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgramPlan;
import com.rinsing.geomantia.systems.city.application.dressing.DecorationShapeEvaluator;
import com.rinsing.geomantia.systems.city.application.dressing.DecorationSlot;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationTerrainRunCompiler;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders review images from resolved geometry without pretending the target is its bounding box. */
public final class CityDecorationPreviewRenderer {
    static final int WIDTH = 1000;
    static final int HEIGHT = 760;
    static final Color CANVAS_COLOR = new Color(246, 246, 242);
    static final Color PLOT_COLOR = new Color(255, 255, 252);
    static final Color TARGET_MASK_COLOR = new Color(185, 190, 183);
    static final Color SHAPE_COLOR = new Color(95, 152, 105, 92);
    static final Color HARD_OBSTACLE_COLOR = new Color(196, 70, 62, 150);

    private static final int PLOT_LEFT = 48;
    private static final int PLOT_TOP = 96;
    private static final int PLOT_RIGHT = 760;
    private static final int PLOT_BOTTOM = 712;
    private static final int CROP_MARGIN_BLOCKS = 4;

    private final DecorationShapeEvaluator shapeEvaluator = new DecorationShapeEvaluator();

    public JsonObject render(CompiledDecorationProgramPlan plan,
                             List<DecorationSlot> slotProjection,
                             Path outputDirectory) throws IOException {
        return render(plan, slotProjection, null, outputDirectory);
    }

    public JsonObject render(CompiledDecorationProgramPlan plan,
                             List<DecorationSlot> slotProjection,
                             CityDecorationTerrainRunCompiler.FrozenPlan frozenTerrainPlan,
                             Path outputDirectory) throws IOException {
        if (plan == null || plan.programs().isEmpty()) {
            throw new IllegalArgumentException("CITY_DECORATION_PREVIEW_PLAN_REQUIRED");
        }
        if (slotProjection == null || slotProjection.isEmpty()) {
            throw new IllegalArgumentException("CITY_DECORATION_PREVIEW_SLOTS_REQUIRED");
        }
        if (outputDirectory == null) {
            throw new IllegalArgumentException("CITY_DECORATION_PREVIEW_OUTPUT_REQUIRED");
        }
        Map<String, CompiledDecorationProgram> programs = new LinkedHashMap<>();
        plan.programsInExecutionOrder().forEach(program -> programs.put(program.programId(), program));
        Map<String, List<DecorationSlot>> slotsByProgram = validateAndGroup(plan, programs, slotProjection);
        Map<String, CityDecorationTerrainRunCompiler.SlotOutcome> terrainOutcomes = frozenTerrainPlan == null
                ? Map.of() : frozenTerrainPlan.outcomesBySlotId();

        Files.createDirectories(outputDirectory);
        JsonObject index = new JsonObject();
        index.addProperty("schemaVersion", "city_decoration_preview_index.v0.3");
        index.addProperty("cityId", plan.cityId());
        index.addProperty("catalogHash", plan.catalogHash());
        index.addProperty("previewMode", "per_program_zoomed");
        index.addProperty("terrainOutcomeAvailable", frozenTerrainPlan != null);
        index.addProperty("frozenRunCount", frozenTerrainPlan == null ? 0 : frozenTerrainPlan.runs().size());
        index.addProperty("foundationSegmentCount", frozenTerrainPlan == null
                ? 0 : frozenTerrainPlan.foundationSegments().size());
        JsonArray previews = new JsonArray();
        for (CompiledDecorationProgram program : plan.programsInExecutionOrder()) {
            List<DecorationSlot> slots = slotsByProgram.get(program.programId());
            if (slots == null || slots.isEmpty()) {
                throw new IllegalArgumentException("CITY_DECORATION_PREVIEW_PROGRAM_SLOTS_REQUIRED: "
                        + program.programId());
            }
            String fileName = "city_decoration_preview_" + safe(program.programId()) + ".png";
            Path imagePath = outputDirectory.resolve(fileName);
            List<CompiledDecorationProgramPlan.HardObstacle> obstacles = plan.hardObstacles().stream()
                    .filter(obstacle -> obstacle.blockBounds().overlaps(program.targetMask().bounds()))
                    .toList();
            renderProgram(program, slots, obstacles, terrainOutcomes, imagePath);
            previews.add(previewEntry(program, slots, obstacles, terrainOutcomes, imagePath));
        }
        index.add("previews", previews);
        Path indexPath = outputDirectory.resolve("city_decoration_preview_index.json");
        index.addProperty("indexPath", indexPath.toString());
        Files.writeString(indexPath, CityJson.GSON.toJson(index));
        return index;
    }

    private Map<String, List<DecorationSlot>> validateAndGroup(
            CompiledDecorationProgramPlan plan,
            Map<String, CompiledDecorationProgram> programs,
            List<DecorationSlot> slots) {
        Map<String, List<DecorationSlot>> grouped = new LinkedHashMap<>();
        for (DecorationSlot slot : slots) {
            CompiledDecorationProgram program = programs.get(slot.programId());
            if (program == null) {
                throw new IllegalArgumentException("CITY_DECORATION_PREVIEW_PROGRAM_UNKNOWN: " + slot.programId());
            }
            program.contentPalette().requireSlot(slot.paletteSlotId());
            int x = slot.worldAnchor().x();
            int z = slot.worldAnchor().z();
            if (!shapeEvaluator.contains(program, x, z)) {
                throw new IllegalArgumentException("CITY_DECORATION_PREVIEW_SLOT_OUTSIDE_SHAPE: " + slot.slotId());
            }
            for (CompiledDecorationProgramPlan.HardObstacle obstacle : plan.hardObstacles()) {
                if (obstacle.blockBounds().contains(x, z)) {
                    throw new IllegalArgumentException("CITY_DECORATION_PREVIEW_SLOT_IN_HARD_OBSTACLE: "
                            + slot.slotId());
                }
            }
            grouped.computeIfAbsent(slot.programId(), ignored -> new ArrayList<>()).add(slot);
        }
        grouped.values().forEach(values -> values.sort(DecorationSlot.STABLE_ORDER));
        return grouped;
    }

    private void renderProgram(CompiledDecorationProgram program,
                               List<DecorationSlot> slots,
                               List<CompiledDecorationProgramPlan.HardObstacle> obstacles,
                               Map<String, CityDecorationTerrainRunCompiler.SlotOutcome> terrainOutcomes,
                               Path imagePath) throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(CANVAS_COLOR);
            g.fillRect(0, 0, WIDTH, HEIGHT);
            g.setColor(PLOT_COLOR);
            g.fillRect(PLOT_LEFT, PLOT_TOP, PLOT_RIGHT - PLOT_LEFT, PLOT_BOTTOM - PLOT_TOP);

            BlockBounds crop = expand(program.targetMask().bounds(), CROP_MARGIN_BLOCKS);
            Transform transform = transform(crop);
            drawGrid(g, transform, crop);
            drawTargetMembers(g, transform, program.targetMask().memberBounds());
            drawShape(g, transform, program);
            drawObstacles(g, transform, obstacles);
            drawSlots(g, transform, slots, terrainOutcomes);
            drawHeader(g, program, slots, obstacles);
            drawLegend(g, program);
            g.setColor(new Color(80, 84, 80));
            g.setStroke(new BasicStroke(1.4f));
            g.drawRect(PLOT_LEFT, PLOT_TOP, PLOT_RIGHT - PLOT_LEFT, PLOT_BOTTOM - PLOT_TOP);
        } finally {
            g.dispose();
        }
        if (!ImageIO.write(image, "png", imagePath.toFile())) {
            throw new IOException("CITY_DECORATION_PREVIEW_PNG_WRITER_UNAVAILABLE: " + imagePath);
        }
    }

    private void drawGrid(Graphics2D g, Transform transform, BlockBounds crop) {
        g.setColor(new Color(90, 94, 90, 28));
        g.setStroke(new BasicStroke(1.0f));
        int step = 16;
        for (int x = Math.floorDiv(crop.minX(), step) * step; x <= crop.maxX(); x += step) {
            g.drawLine(transform.x(x), PLOT_TOP, transform.x(x), PLOT_BOTTOM);
        }
        for (int z = Math.floorDiv(crop.minZ(), step) * step; z <= crop.maxZ(); z += step) {
            g.drawLine(PLOT_LEFT, transform.z(z), PLOT_RIGHT, transform.z(z));
        }
    }

    private void drawTargetMembers(Graphics2D g, Transform transform, List<BlockBounds> members) {
        g.setColor(TARGET_MASK_COLOR);
        for (BlockBounds member : members) {
            fillBounds(g, transform, member);
        }
    }

    private void drawShape(Graphics2D g, Transform transform, CompiledDecorationProgram program) {
        g.setColor(SHAPE_COLOR);
        BlockBounds bounds = program.targetMask().bounds();
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                if (shapeEvaluator.contains(program, x, z)) {
                    fillCell(g, transform, x, z);
                }
            }
        }
    }

    private void drawObstacles(Graphics2D g, Transform transform,
                               List<CompiledDecorationProgramPlan.HardObstacle> obstacles) {
        for (CompiledDecorationProgramPlan.HardObstacle obstacle : obstacles) {
            g.setColor(HARD_OBSTACLE_COLOR);
            fillBounds(g, transform, obstacle.blockBounds());
            g.setColor(new Color(138, 42, 38, 220));
            g.setStroke(new BasicStroke(1.8f));
            drawBounds(g, transform, obstacle.blockBounds());
        }
    }

    private void drawSlots(Graphics2D g, Transform transform, List<DecorationSlot> slots,
                           Map<String, CityDecorationTerrainRunCompiler.SlotOutcome> terrainOutcomes) {
        for (DecorationSlot slot : slots) {
            CityDecorationTerrainRunCompiler.SlotOutcome outcome = terrainOutcomes.get(slot.slotId());
            Color color = outcomeColor(slot.paletteSlotId(), outcome);
            int x = transform.x(slot.worldAnchor().x());
            int z = transform.z(slot.worldAnchor().z());
            int size = Math.max(4, (int) Math.ceil(transform.scale()));
            g.setColor(color);
            g.fillOval(x - size / 2, z - size / 2, size, size);
            g.setColor(new Color(30, 32, 30, 210));
            g.setStroke(new BasicStroke(0.9f));
            g.drawOval(x - size / 2, z - size / 2, size, size);
            if (outcome != null && (outcome.decision() == CityDecorationTerrainRunCompiler.Decision.TERMINATE
                    || outcome.decision() == CityDecorationTerrainRunCompiler.Decision.DEFER)) {
                g.drawLine(x - size / 2, z - size / 2, x + size / 2, z + size / 2);
                g.drawLine(x + size / 2, z - size / 2, x - size / 2, z + size / 2);
            } else if (outcome != null && outcome.targetY() > outcome.surfaceY()) {
                g.setColor(new Color(24, 132, 164, 230));
                g.setStroke(new BasicStroke(2.0f));
                g.drawRect(x - size / 2 - 2, z - size / 2 - 2, size + 4, size + 4);
            }
        }
    }

    private static Color outcomeColor(String paletteSlotId,
                                      CityDecorationTerrainRunCompiler.SlotOutcome outcome) {
        if (outcome == null || outcome.decision() == CityDecorationTerrainRunCompiler.Decision.PLACE) {
            return slotColor(paletteSlotId);
        }
        return switch (outcome.decision()) {
            case END_CAP -> new Color(228, 168, 44);
            case TERMINATE -> new Color(196, 58, 52);
            case DEFER -> new Color(116, 120, 124);
            case PLACE -> slotColor(paletteSlotId);
        };
    }

    private void drawHeader(Graphics2D g, CompiledDecorationProgram program,
                            List<DecorationSlot> slots,
                            List<CompiledDecorationProgramPlan.HardObstacle> obstacles) {
        g.setColor(new Color(30, 32, 30));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 19));
        g.drawString("City decoration: " + trim(program.programId(), 48), 36, 34);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        g.drawString("shape=" + program.shape().type() + "  pattern=" + program.pattern().type()
                + "  slots=" + slots.size() + "  obstacles=" + obstacles.size(), 36, 58);
        g.drawString("mask members=" + program.targetMask().memberBounds().size()
                + "  origin=" + program.coordinateFrame().origin().x() + ","
                + program.coordinateFrame().origin().z(), 36, 77);
    }

    private void drawLegend(Graphics2D g, CompiledDecorationProgram program) {
        int x = 786;
        int y = 116;
        g.setColor(new Color(30, 32, 30));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.drawString("Palette", x, y);
        y += 26;
        for (CompiledDecorationProgram.PaletteSlot slot : program.contentPalette().slots()) {
            Color color = slotColor(slot.slotId());
            g.setColor(color);
            g.fillRect(x, y - 11, 12, 12);
            g.setColor(new Color(35, 37, 35));
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
            g.drawString(trim(slot.slotId(), 24), x + 19, y);
            y += 17;
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            for (CompiledDecorationProgram.ContentEntry entry : slot.entries()) {
                g.drawString(trim(entry.contentRef(), 29), x + 19, y);
                y += 14;
            }
            y += 8;
        }
        g.setColor(HARD_OBSTACLE_COLOR);
        g.fillRect(x, Math.min(y, HEIGHT - 50), 12, 12);
        g.setColor(new Color(35, 37, 35));
        g.drawString("hard obstacle", x + 19, Math.min(y + 11, HEIGHT - 39));
    }

    private JsonObject previewEntry(CompiledDecorationProgram program,
                                    List<DecorationSlot> slots,
                                    List<CompiledDecorationProgramPlan.HardObstacle> obstacles,
                                    Map<String, CityDecorationTerrainRunCompiler.SlotOutcome> terrainOutcomes,
                                    Path imagePath) {
        JsonObject entry = new JsonObject();
        entry.addProperty("programId", program.programId());
        entry.addProperty("fileName", imagePath.getFileName().toString());
        entry.addProperty("path", imagePath.toString());
        entry.addProperty("shapeType", program.shape().type());
        entry.addProperty("patternType", program.pattern().type());
        entry.addProperty("slotCount", slots.size());
        entry.addProperty("targetMemberCount", program.targetMask().memberBounds().size());
        entry.addProperty("hardObstacleCount", obstacles.size());
        List<CityDecorationTerrainRunCompiler.SlotOutcome> outcomes = slots.stream()
                .map(slot -> terrainOutcomes.get(slot.slotId())).filter(java.util.Objects::nonNull).toList();
        entry.addProperty("terrainOutcomeCount", outcomes.size());
        entry.addProperty("endCapCount", outcomes.stream().filter(outcome ->
                outcome.decision() == CityDecorationTerrainRunCompiler.Decision.END_CAP).count());
        entry.addProperty("terminatedSlotCount", outcomes.stream().filter(outcome ->
                outcome.decision() == CityDecorationTerrainRunCompiler.Decision.TERMINATE).count());
        entry.addProperty("deferredSlotCount", outcomes.stream().filter(outcome ->
                outcome.decision() == CityDecorationTerrainRunCompiler.Decision.DEFER).count());
        entry.addProperty("foundationSlotCount", outcomes.stream().filter(outcome ->
                outcome.targetY() > outcome.surfaceY()).count());
        JsonArray legend = new JsonArray();
        for (CompiledDecorationProgram.PaletteSlot slot : program.contentPalette().slots()) {
            JsonObject item = new JsonObject();
            item.addProperty("paletteSlotId", slot.slotId());
            item.addProperty("phase", slot.phase().serializedName());
            item.addProperty("required", slot.required());
            JsonArray refs = new JsonArray();
            slot.entries().forEach(content -> refs.add(content.contentRef()));
            item.add("contentRefs", refs);
            legend.add(item);
        }
        entry.add("legend", legend);
        return entry;
    }

    static Color slotColor(String paletteSlotId) {
        Color[] colors = {
                new Color(48, 119, 180), new Color(224, 128, 50), new Color(70, 145, 91),
                new Color(173, 79, 104), new Color(125, 92, 170), new Color(44, 146, 153),
                new Color(154, 128, 48), new Color(102, 108, 115)
        };
        return colors[Math.floorMod(paletteSlotId.hashCode(), colors.length)];
    }

    static Transform transform(BlockBounds crop) {
        double sx = (PLOT_RIGHT - PLOT_LEFT) / (double) Math.max(1, crop.widthBlocks());
        double sz = (PLOT_BOTTOM - PLOT_TOP) / (double) Math.max(1, crop.heightBlocks());
        double scale = Math.min(sx, sz);
        double drawnWidth = crop.widthBlocks() * scale;
        double drawnHeight = crop.heightBlocks() * scale;
        double offsetX = PLOT_LEFT + ((PLOT_RIGHT - PLOT_LEFT) - drawnWidth) / 2.0;
        double offsetZ = PLOT_TOP + ((PLOT_BOTTOM - PLOT_TOP) - drawnHeight) / 2.0;
        return new Transform(crop.minX(), crop.minZ(), scale, offsetX, offsetZ);
    }

    private void fillCell(Graphics2D g, Transform transform, int x, int z) {
        int left = transform.x(x);
        int top = transform.z(z);
        int right = transform.x(x + 1);
        int bottom = transform.z(z + 1);
        g.fillRect(Math.min(left, right), Math.min(top, bottom), Math.max(1, Math.abs(right - left)),
                Math.max(1, Math.abs(bottom - top)));
    }

    private void fillBounds(Graphics2D g, Transform transform, BlockBounds bounds) {
        int left = transform.x(bounds.minX());
        int top = transform.z(bounds.minZ());
        int right = transform.x(bounds.maxX() + 1);
        int bottom = transform.z(bounds.maxZ() + 1);
        g.fillRect(Math.min(left, right), Math.min(top, bottom), Math.max(1, Math.abs(right - left)),
                Math.max(1, Math.abs(bottom - top)));
    }

    private void drawBounds(Graphics2D g, Transform transform, BlockBounds bounds) {
        int left = transform.x(bounds.minX());
        int top = transform.z(bounds.minZ());
        int right = transform.x(bounds.maxX() + 1);
        int bottom = transform.z(bounds.maxZ() + 1);
        g.drawRect(Math.min(left, right), Math.min(top, bottom), Math.max(1, Math.abs(right - left)),
                Math.max(1, Math.abs(bottom - top)));
    }

    private static BlockBounds expand(BlockBounds bounds, int margin) {
        return new BlockBounds(bounds.minX() - margin, bounds.minZ() - margin,
                bounds.maxX() + margin, bounds.maxZ() + margin);
    }

    private static String safe(String raw) {
        return raw == null || raw.isBlank() ? "program" : raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "~";
    }

    record Transform(int minX, int minZ, double scale, double offsetX, double offsetZ) {
        int x(int worldX) {
            return (int) Math.round(offsetX + (worldX - minX) * scale);
        }

        int z(int worldZ) {
            return (int) Math.round(offsetZ + (worldZ - minZ) * scale);
        }
    }
}
