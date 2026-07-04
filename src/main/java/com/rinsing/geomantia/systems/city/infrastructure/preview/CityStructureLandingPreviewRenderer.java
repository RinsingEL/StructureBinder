package com.rinsing.geomantia.systems.city.infrastructure.preview;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary;
import com.rinsing.geomantia.systems.city.domain.model.PatchMemberCell;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

public final class CityStructureLandingPreviewRenderer {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 900;
    private static final int PAD = 64;

    public Path renderD4(JsonObject anchorMap, Path outputDirectory) throws IOException {
        return renderD4(anchorMap, null, outputDirectory);
    }

    public Path renderD4(JsonObject anchorMap, CityLandformReviewPackage reviewPackage,
                         Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("structure_anchor_preview.png");
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            BlockBounds gridBounds = gridBounds(anchorMap);
            Transform t = transform(gridBounds);
            drawPatchBackdrop(g, t, gridBounds, reviewPackage);
            drawGrid(g, t, gridBounds);
            int i = 0;
            for (JsonElement elem : array(anchorMap, "anchors")) {
                JsonObject anchor = elem.getAsJsonObject();
                i++;
                drawRect(g, t, bounds(anchor, "safetyEnvelope"), new Color(98, 96, 89, 18),
                        new Color(89, 82, 70, 80), 0.9f);
                drawRect(g, t, bounds(anchor, "maskEnvelope"), new Color(202, 108, 62, 35),
                        new Color(178, 84, 46, 125), 1.1f);
                drawRect(g, t, bounds(anchor, "collisionEnvelope"), new Color(204, 79, 63, 45),
                        new Color(158, 59, 49, 160), 1.5f);
                drawRect(g, t, bounds(anchor, "plannedFootprint"), color(i, 120), color(i, 235), 2.4f);
                drawLabel(g, t, point(anchor, "anchorBlock"), string(anchor, "anchorId"));
            }
            title(g, "City D4 structure anchor preview",
                    "patch backdrop + green=planned red=collision orange=mask gray=safety anchors="
                            + array(anchorMap, "anchors").size());
            sideSummary(g, anchorMap, "anchors");
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    public Path renderD4Candidates(JsonObject candidateSet, Path outputDirectory) throws IOException {
        return renderD4Candidates(candidateSet, null, outputDirectory);
    }

    public Path renderD4Candidates(JsonObject candidateSet, CityLandformReviewPackage reviewPackage,
                                   Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("anchor_candidate_preview.png");
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            BlockBounds gridBounds = gridBounds(candidateSet);
            Transform t = transform(gridBounds);
            drawPatchBackdrop(g, t, gridBounds, reviewPackage);
            drawGrid(g, t, gridBounds);
            drawFrozenAnchors(g, t, candidateSet);
            int i = 0;
            for (JsonElement slotElem : array(candidateSet, "slotCandidates")) {
                JsonObject slot = slotElem.getAsJsonObject();
                for (JsonElement candElem : array(slot, "candidates")) {
                    JsonObject candidate = candElem.getAsJsonObject();
                    i++;
                    String code = "C" + i;
                    drawRect(g, t, bounds(candidate, "estimatedSafetyEnvelope"), new Color(98, 96, 89, 14),
                            new Color(89, 82, 70, 64), 0.8f);
                    drawRect(g, t, bounds(candidate, "estimatedMaskEnvelope"), new Color(202, 108, 62, 24),
                            new Color(178, 84, 46, 105), 1.0f);
                    drawRect(g, t, bounds(candidate, "estimatedCollisionEnvelope"), new Color(204, 79, 63, 34),
                            new Color(158, 59, 49, 145), 1.4f);
                    BlockPoint anchor = point(candidate, "anchorBlock");
                    drawPoint(g, t, anchor, color(i, 235));
                    drawBadge(g, t, anchor, code, color(i, 235));
                }
            }
            title(g, "City D4 anchor candidate preview",
                    "patch backdrop + blue=frozen selected C*=current candidates red=collision orange=mask gray=safety candidates="
                            + candidateCount(candidateSet));
            candidateSummary(g, candidateSet);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    public Path renderEnvelopeFacts(JsonObject facts, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("structure_envelope_profile_preview.png");
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            BlockBounds bounds = envelopeFactsBounds(facts);
            Transform t = transform(bounds);
            drawGrid(g, t, bounds);
            int i = 0;
            for (JsonElement elem : array(facts, "structures")) {
                JsonObject structure = elem.getAsJsonObject();
                i++;
                int offsetX = ((i - 1) % 4) * 180;
                int offsetZ = ((i - 1) / 4) * 160;
                BlockPoint origin = new BlockPoint(bounds.minX() + 60 + offsetX, bounds.minZ() + 70 + offsetZ);
                drawLocal(g, t, origin, bounds(structure, "maxObservedEnvelope"),
                        new Color(98, 96, 89, 32), new Color(89, 82, 70, 125), 1.0f);
                drawLocal(g, t, origin, bounds(structure, "localEnvelopeP99"),
                        new Color(202, 108, 62, 48), new Color(178, 84, 46, 165), 1.5f);
                drawLocal(g, t, origin, bounds(structure, "localEnvelopeP95"),
                        new Color(65, 145, 108, 85), new Color(39, 111, 78, 210), 2.0f);
                JsonArray groups = array(structure, "bboxGroups");
                if (!groups.isEmpty()) {
                    JsonObject dominant = groups.get(0).getAsJsonObject();
                    drawLocal(g, t, origin, bounds(dominant, "localEnvelope"),
                            new Color(72, 126, 193, 44), new Color(50, 88, 156, 190), 2.4f);
                }
                drawLabel(g, t, origin, trim(string(structure, "structureId"), 24));
            }
            title(g, "City structure envelope facts preview",
                    "blue=dominant bbox group green=P95 orange=P99 gray=maxObserved structures="
                            + array(facts, "structures").size());
            sideSummary(g, facts, "structures");
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    public Path renderD5(JsonObject reservationMaskPlan, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("reservation_mask_preview.png");
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            Transform t = transform(gridBounds(reservationMaskPlan));
            drawGrid(g, t, gridBounds(reservationMaskPlan));
            for (JsonElement elem : array(reservationMaskPlan, "vegetationLimitedMask")) {
                drawRect(g, t, bounds(elem.getAsJsonObject(), "blockBounds"), new Color(77, 145, 92, 44),
                        new Color(77, 145, 92, 110), 1.0f);
            }
            for (JsonElement elem : array(reservationMaskPlan, "noVegetationMask")) {
                drawRect(g, t, bounds(elem.getAsJsonObject(), "blockBounds"), new Color(64, 136, 93, 85),
                        new Color(38, 103, 70, 175), 1.5f);
            }
            for (JsonElement elem : array(reservationMaskPlan, "noVanillaStructureMask")) {
                JsonObject mask = elem.getAsJsonObject();
                drawRect(g, t, bounds(mask, "blockBounds"), new Color(201, 82, 70, 38),
                        new Color(160, 54, 48, 170), 1.8f);
                drawLabel(g, t, bounds(mask, "blockBounds").center(), string(mask, "sourceRef"));
            }
            title(g, "City D5 reservation mask preview",
                    "green=no vegetation red=no vanilla structure masks="
                            + array(reservationMaskPlan, "noVegetationMask").size());
            sideSummary(g, reservationMaskPlan, "reservationReason");
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    public Path renderD6(JsonObject materializationPlan, JsonObject trace, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("structure_materialization_preview.png");
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            Transform t = transform(gridBounds(object(materializationPlan, "sourceStructureAnchorMap")));
            drawGrid(g, t, gridBounds(object(materializationPlan, "sourceStructureAnchorMap")));
            JsonArray structures = array(materializationPlan, "structures");
            if (structures.isEmpty()) {
                structures = array(materializationPlan, "plannedWorldgenStructures");
            }
            int i = 0;
            for (JsonElement elem : structures) {
                JsonObject structure = elem.getAsJsonObject();
                i++;
                drawRect(g, t, bounds(structure, "maskEnvelope"), new Color(202, 108, 62, 28),
                        new Color(178, 84, 46, 110), 0.9f);
                drawRect(g, t, bounds(structure, "collisionEnvelope"), new Color(207, 81, 70, 36),
                        new Color(150, 62, 52, 110), 1.1f);
                String footprintKey = object(structure, "actualFootprint").size() > 0
                        ? "actualFootprint" : "plannedFootprint";
                drawRect(g, t, bounds(structure, footprintKey), color(i, 105), color(i, 225), 2.5f);
                drawPieces(g, t, structure);
                drawLabel(g, t, bounds(structure, footprintKey).center(), string(structure, "anchorId"));
            }
            title(g, "City D6 worldgen plan preview",
                    "planned worldgen structures=" + structures.size()
                            + " failures=" + object(trace, "failureSummary").size()
                            + " blue/green=actual red=collision orange=mask");
            traceSummary(g, trace);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    public Path renderD7(JsonObject ledger, JsonObject trace, JsonObject materializationPlan,
                         Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("placed_structure_preview.png");
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            Transform t = transform(gridBounds(object(materializationPlan, "sourceStructureAnchorMap")));
            drawGrid(g, t, gridBounds(object(materializationPlan, "sourceStructureAnchorMap")));
            int i = 0;
            for (JsonElement elem : array(ledger, "placedStructures")) {
                JsonObject placed = elem.getAsJsonObject();
                i++;
                drawRect(g, t, bounds(placed, "maskEnvelope"), new Color(202, 108, 62, 25),
                        new Color(178, 84, 46, 100), 0.9f);
                drawRect(g, t, bounds(placed, "collisionEnvelope"), new Color(207, 81, 70, 34),
                        new Color(150, 62, 52, 115), 1.0f);
                drawRect(g, t, bounds(placed, "actualFootprint"), color(i, 118), color(i, 235), 2.5f);
                drawPieces(g, t, placed);
                drawLabel(g, t, bounds(placed, "actualFootprint").center(), string(placed, "anchorId"));
            }
            title(g, "City D6 true-run materialization preview",
                    "placed=" + array(ledger, "placedStructures").size()
                            + " waiting=" + object(trace, "waitingSummary").size()
                            + " failures=" + object(trace, "failureSummary").size());
            traceSummary(g, trace);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    private static void drawPieces(Graphics2D g, Transform t, JsonObject structure) {
        int pieceIndex = 0;
        for (JsonElement elem : array(structure, "pieceBoxes")) {
            JsonObject piece = elem.getAsJsonObject();
            JsonObject box = object(piece, "box");
            if (box == null) {
                continue;
            }
            BlockBounds bounds = bounds(box);
            g.setColor(new Color(244, 186, 69, 76));
            fillBounds(g, t, bounds);
            g.setColor(new Color(159, 100, 20, 210));
            g.setStroke(new BasicStroke(1.0f));
            drawBounds(g, t, bounds);
            if (pieceIndex < 12) {
                drawLabel(g, t, bounds.center(), "P" + pieceIndex);
            }
            pieceIndex++;
        }
    }

    private static void drawPatchBackdrop(Graphics2D g, Transform t, BlockBounds gridBounds,
                                          CityLandformReviewPackage reviewPackage) {
        if (reviewPackage == null) {
            return;
        }
        for (LandformPatchSummary patch : reviewPackage.landformPatches()) {
            Color base = landformColor(patch.landformType());
            if (!patch.memberCells().isEmpty()) {
                drawPatchCells(g, t, patch, memberCellStepBlocks(patch, reviewPackage.grid().cellStepBlocks()), base);
            } else {
                if (patch.blockBounds().overlaps(gridBounds)) {
                    BlockBounds clipped = clip(patch.blockBounds(), gridBounds);
                    drawRect(g, t, clipped, withAlpha(base, 54), withAlpha(base.darker(), 82), 0.7f);
                }
            }
        }
        for (LandformPatchSummary patch : reviewPackage.landformPatches()) {
            drawPatchLabel(g, t, patch);
        }
    }

    private static void drawPatchCells(Graphics2D g, Transform t, LandformPatchSummary patch,
                                       int cellStepBlocks, Color base) {
        g.setColor(withAlpha(base, 58));
        for (PatchMemberCell cell : patch.memberCells()) {
            int x1 = t.x(cell.blockMinX());
            int z1 = t.z(cell.blockMinZ());
            int x2 = t.x(cell.blockMinX() + cellStepBlocks);
            int z2 = t.z(cell.blockMinZ() + cellStepBlocks);
            g.fillRect(Math.min(x1, x2), Math.min(z1, z2), Math.max(1, Math.abs(x2 - x1)),
                    Math.max(1, Math.abs(z2 - z1)));
        }
        g.setColor(withAlpha(base.darker(), 42));
        g.setStroke(new BasicStroke(0.6f));
        for (PatchMemberCell cell : patch.memberCells()) {
            int x1 = t.x(cell.blockMinX());
            int z1 = t.z(cell.blockMinZ());
            int x2 = t.x(cell.blockMinX() + cellStepBlocks);
            int z2 = t.z(cell.blockMinZ() + cellStepBlocks);
            g.drawRect(Math.min(x1, x2), Math.min(z1, z2), Math.max(1, Math.abs(x2 - x1)),
                    Math.max(1, Math.abs(z2 - z1)));
        }
    }

    private static void drawPatchLabel(Graphics2D g, Transform t, LandformPatchSummary patch) {
        String label = patch.mapLabel();
        if (label == null || label.isBlank()) {
            return;
        }
        int x = t.x(patch.centerBlock().x());
        int z = t.z(patch.centerBlock().z());
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(new Color(32, 38, 32, 118));
        g.drawString(trim(label, 10), x - metrics.stringWidth(trim(label, 10)) / 2,
                z + metrics.getAscent() / 2);
    }

    private static void drawFrozenAnchors(Graphics2D g, Transform t, JsonObject candidateSet) {
        int index = 0;
        for (JsonElement elem : array(candidateSet, "occupiedEnvelopes")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            index++;
            JsonObject occupied = elem.getAsJsonObject();
            drawRect(g, t, bounds(occupied, "blockBounds"), new Color(67, 112, 178, 34),
                    new Color(47, 86, 148, 150), 1.6f);
        }
        index = 0;
        for (JsonElement elem : array(candidateSet, "selectedAnchors")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            index++;
            JsonObject anchor = elem.getAsJsonObject();
            BlockPoint point = point(anchor, "anchorBlock");
            drawPoint(g, t, point, new Color(67, 112, 178, 235));
            drawBadge(g, t, point, "S" + index, new Color(67, 112, 178, 235));
        }
    }

    private static void drawPoint(Graphics2D g, Transform t, BlockPoint point, Color color) {
        int x = t.x(point.x());
        int z = t.z(point.z());
        g.setColor(color);
        g.fillOval(x - 5, z - 5, 10, 10);
        g.setColor(new Color(38, 42, 38, 210));
        g.setStroke(new BasicStroke(1.2f));
        g.drawOval(x - 5, z - 5, 10, 10);
    }

    private static void drawBadge(Graphics2D g, Transform t, BlockPoint point, String label, Color fill) {
        int x = t.x(point.x());
        int z = t.z(point.z());
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
        FontMetrics metrics = g.getFontMetrics();
        int w = Math.max(20, metrics.stringWidth(label) + 8);
        int h = 16;
        int left = x + 7;
        int top = z - h / 2;
        g.setColor(new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), 220));
        g.fillRoundRect(left, top, w, h, 5, 5);
        g.setColor(new Color(35, 39, 35, 210));
        g.setStroke(new BasicStroke(1.0f));
        g.drawRoundRect(left, top, w, h, 5, 5);
        g.setColor(Color.WHITE);
        g.drawString(label, left + 4, top + 12);
    }

    private static void candidateSummary(Graphics2D g, JsonObject candidateSet) {
        int x = 820;
        int y = 90;
        g.setColor(new Color(32, 34, 34));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.drawString("candidate legend", x, y);
        y += 24;
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        int selectedIndex = 0;
        for (JsonElement selectedElem : array(candidateSet, "selectedAnchors")) {
            if (!selectedElem.isJsonObject() || y > HEIGHT - 60) {
                continue;
            }
            selectedIndex++;
            JsonObject selected = selectedElem.getAsJsonObject();
            g.drawString("S" + selectedIndex + " selected "
                    + trim(string(selected, "slotId") + " " + string(selected, "candidateId"), 48), x, y);
            y += 15;
        }
        if (selectedIndex > 0) {
            y += 8;
        }
        int candidateIndex = 0;
        for (JsonElement slotElem : array(candidateSet, "slotCandidates")) {
            JsonObject slot = slotElem.getAsJsonObject();
            g.drawString(trim(string(slot, "slotId") + " " + string(slot, "displayRole"), 54), x, y);
            y += 16;
            for (JsonElement candElem : array(slot, "candidates")) {
                if (y > HEIGHT - 40) {
                    break;
                }
                JsonObject candidate = candElem.getAsJsonObject();
                candidateIndex++;
                String score = object(candidate, "scoreBreakdown").has("total")
                        ? String.format(java.util.Locale.ROOT, "%.2f",
                        object(candidate, "scoreBreakdown").get("total").getAsDouble())
                        : "";
                g.drawString("  C" + candidateIndex + " " + score + " "
                        + trim(string(candidate, "candidateId"), 42), x, y);
                y += 15;
            }
            y += 3;
            if (y > HEIGHT - 40) {
                break;
            }
        }
    }

    private static void sideSummary(Graphics2D g, JsonObject obj, String arrayKey) {
        int x = 820;
        int y = 90;
        g.setColor(new Color(32, 34, 34));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.drawString(arrayKey, x, y);
        y += 24;
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        int index = 0;
        for (JsonElement elem : array(obj, arrayKey)) {
            if (!elem.isJsonObject() || index >= 22) {
                continue;
            }
            JsonObject item = elem.getAsJsonObject();
            String id = firstNonBlank(string(item, "anchorId"), string(item, "sourceRef"), "item_" + index);
            String structure = string(item, "structureId");
            String text = structure.isBlank() ? id : id + " " + structure;
            g.drawString(trim(text, 56), x, y);
            y += 17;
            index++;
        }
    }

    private static void traceSummary(Graphics2D g, JsonObject trace) {
        int x = 820;
        int y = 90;
        g.setColor(new Color(32, 34, 34));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.drawString("trace summary", x, y);
        y += 22;
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        y = drawObject(g, x, y, "waiting", object(trace, "waitingSummary"));
        drawObject(g, x, y + 6, "failures", object(trace, "failureSummary"));
    }

    private static int drawObject(Graphics2D g, int x, int y, String label, JsonObject obj) {
        g.drawString(label + ":", x, y);
        y += 16;
        for (String key : obj.keySet()) {
            g.drawString("  " + key + "=" + obj.get(key).getAsString(), x, y);
            y += 16;
        }
        return y;
    }

    private static int candidateCount(JsonObject candidateSet) {
        int count = 0;
        for (JsonElement elem : array(candidateSet, "slotCandidates")) {
            count += array(elem.getAsJsonObject(), "candidates").size();
        }
        return count;
    }

    private static void title(Graphics2D g, String title, String subtitle) {
        g.setColor(new Color(22, 25, 28));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        g.drawString(title, 24, 32);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        g.drawString(subtitle, 24, 54);
    }

    private static void drawGrid(Graphics2D g, Transform t, BlockBounds bounds) {
        g.setColor(new Color(82, 85, 77, 190));
        g.setStroke(new BasicStroke(1.4f));
        drawBounds(g, t, bounds);
        g.setColor(new Color(74, 80, 74, 28));
        for (int x = bounds.minX(); x <= bounds.maxX(); x += 32) {
            int sx = t.x(x);
            g.drawLine(sx, t.z(bounds.minZ()), sx, t.z(bounds.maxZ()));
        }
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z += 32) {
            int sz = t.z(z);
            g.drawLine(t.x(bounds.minX()), sz, t.x(bounds.maxX()), sz);
        }
    }

    private static void drawRect(Graphics2D g, Transform t, BlockBounds bounds,
                                 Color fill, Color stroke, float strokeWidth) {
        g.setColor(fill);
        fillBounds(g, t, bounds);
        g.setColor(stroke);
        g.setStroke(new BasicStroke(strokeWidth));
        drawBounds(g, t, bounds);
    }

    private static void drawLocal(Graphics2D g, Transform t, BlockPoint origin, BlockBounds local,
                                  Color fill, Color stroke, float strokeWidth) {
        drawRect(g, t, new BlockBounds(
                origin.x() + local.minX(),
                origin.z() + local.minZ(),
                origin.x() + local.maxX(),
                origin.z() + local.maxZ()), fill, stroke, strokeWidth);
    }

    private static void drawLabel(Graphics2D g, Transform t, BlockPoint point, String label) {
        if (label == null || label.isBlank()) {
            return;
        }
        int x = t.x(point.x());
        int z = t.z(point.z());
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
        int w = Math.min(170, g.getFontMetrics().stringWidth(label) + 8);
        g.setColor(new Color(248, 236, 181, 220));
        g.fillRect(x + 3, z - 13, w, 15);
        g.setColor(new Color(86, 58, 20, 230));
        g.drawRect(x + 3, z - 13, w, 15);
        g.drawString(trim(label, 24), x + 7, z - 2);
    }

    private static Color color(int index, int alpha) {
        Color[] colors = {
                new Color(61, 151, 113),
                new Color(71, 125, 188),
                new Color(203, 137, 48),
                new Color(139, 101, 179),
                new Color(54, 151, 164),
                new Color(180, 92, 102)
        };
        Color base = colors[Math.floorMod(index, colors.length)];
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
    }

    private static Color landformColor(LandformType type) {
        Map<LandformType, Color> colors = landformColors();
        return colors.getOrDefault(type, new Color(158, 158, 158));
    }

    private static Map<LandformType, Color> landformColors() {
        Map<LandformType, Color> map = new EnumMap<>(LandformType.class);
        map.put(LandformType.WATER, new Color(54, 132, 196));
        map.put(LandformType.SHORE, new Color(225, 206, 104));
        map.put(LandformType.PLAIN, new Color(89, 160, 91));
        map.put(LandformType.TERRACE, new Color(126, 176, 86));
        map.put(LandformType.SLOPE, new Color(215, 139, 55));
        map.put(LandformType.CLIFF, new Color(121, 85, 72));
        map.put(LandformType.RIDGE, new Color(142, 92, 166));
        map.put(LandformType.VALLEY, new Color(60, 173, 164));
        map.put(LandformType.BASIN, new Color(103, 124, 134));
        map.put(LandformType.UNKNOWN, new Color(158, 158, 158));
        return map;
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private static int memberCellStepBlocks(LandformPatchSummary patch, int fallbackStepBlocks) {
        int minStep = Integer.MAX_VALUE;
        for (PatchMemberCell left : patch.memberCells()) {
            for (PatchMemberCell right : patch.memberCells()) {
                int dx = Math.abs(left.blockMinX() - right.blockMinX());
                int dz = Math.abs(left.blockMinZ() - right.blockMinZ());
                if (dx > 0) {
                    minStep = Math.min(minStep, dx);
                }
                if (dz > 0) {
                    minStep = Math.min(minStep, dz);
                }
            }
        }
        if (minStep == Integer.MAX_VALUE) {
            return Math.max(1, fallbackStepBlocks);
        }
        return Math.max(1, minStep);
    }

    private static BlockBounds clip(BlockBounds bounds, BlockBounds clip) {
        return new BlockBounds(
                Math.max(bounds.minX(), clip.minX()),
                Math.max(bounds.minZ(), clip.minZ()),
                Math.min(bounds.maxX(), clip.maxX()),
                Math.min(bounds.maxZ(), clip.maxZ()));
    }

    private static BufferedImage baseImage() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(244, 241, 232));
            g.fillRect(0, 0, WIDTH, HEIGHT);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void setup(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    }

    private static Transform transform(BlockBounds bounds) {
        double sx = (800 - PAD * 2) / (double) Math.max(1, bounds.widthBlocks());
        double sz = (HEIGHT - PAD * 2) / (double) Math.max(1, bounds.heightBlocks());
        return new Transform(bounds.minX(), bounds.minZ(), Math.min(sx, sz));
    }

    private static BlockBounds gridBounds(JsonObject obj) {
        JsonObject grid = object(obj, "grid");
        if (grid == null) {
            return new BlockBounds(0, 0, 256, 256);
        }
        JsonObject blockBounds = object(grid, "blockBounds");
        if (blockBounds != null) {
            return bounds(blockBounds);
        }
        int minX = intValue(grid, "originBlockX", 0);
        int minZ = intValue(grid, "originBlockZ", 0);
        int step = intValue(grid, "cellStepBlocks", 4);
        int cellsX = intValue(grid, "cellsX", 64);
        int cellsZ = intValue(grid, "cellsZ", 64);
        return new BlockBounds(minX, minZ, minX + cellsX * step, minZ + cellsZ * step);
    }

    private static BlockBounds envelopeFactsBounds(JsonObject facts) {
        int count = array(facts, "structures").size();
        int width = Math.max(360, Math.min(4, Math.max(1, count)) * 180 + 160);
        int rows = Math.max(1, (int) Math.ceil(count / 4.0));
        int height = Math.max(260, rows * 160 + 160);
        return new BlockBounds(0, 0, width, height);
    }

    private static JsonArray array(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : new JsonArray();
    }

    private static JsonObject object(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonObject() ? obj.getAsJsonObject(key) : new JsonObject();
    }

    private static BlockBounds bounds(JsonObject obj, String key) {
        JsonObject source = object(obj, key);
        return bounds(source);
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(
                intValue(obj, "minX", 0),
                intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0),
                intValue(obj, "maxZ", 0));
    }

    private static BlockPoint point(JsonObject obj, String key) {
        JsonObject source = object(obj, key);
        return new BlockPoint(intValue(source, "x", 0), intValue(source, "z", 0));
    }

    private static void fillBounds(Graphics2D g, Transform t, BlockBounds bounds) {
        int x1 = t.x(bounds.minX());
        int z1 = t.z(bounds.minZ());
        int x2 = t.x(bounds.maxX());
        int z2 = t.z(bounds.maxZ());
        g.fillRect(Math.min(x1, x2), Math.min(z1, z2), Math.max(1, Math.abs(x2 - x1)),
                Math.max(1, Math.abs(z2 - z1)));
    }

    private static void drawBounds(Graphics2D g, Transform t, BlockBounds bounds) {
        int x1 = t.x(bounds.minX());
        int z1 = t.z(bounds.minZ());
        int x2 = t.x(bounds.maxX());
        int z2 = t.z(bounds.maxZ());
        g.drawRect(Math.min(x1, x2), Math.min(z1, z2), Math.max(1, Math.abs(x2 - x1)),
                Math.max(1, Math.abs(z2 - z1)));
    }

    private static String string(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : defaultValue;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String trim(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, Math.max(0, max - 1)) + "~";
    }

    private record Transform(int minX, int minZ, double scale) {
        int x(int blockX) {
            return PAD + (int) Math.round((blockX - minX) * scale);
        }

        int z(int blockZ) {
            return PAD + (int) Math.round((blockZ - minZ) * scale);
        }
    }
}
