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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CityStructureLandingPreviewRenderer {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 900;
    private static final int PAD = 64;
    private static final int D4_CLUSTER_LINK_GAP_BLOCKS = 64;
    private static final Color D2_BODY_FILL = new Color(50, 126, 184, 96);
    private static final Color D2_BODY_STROKE = new Color(28, 81, 140, 238);
    private static final Color COLLISION_FILL = new Color(204, 79, 63, 45);
    private static final Color COLLISION_STROKE = new Color(158, 59, 49, 170);
    private static final Color MASK_FILL = new Color(202, 108, 62, 35);
    private static final Color MASK_STROKE = new Color(178, 84, 46, 135);

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
                drawD4Geometry(g, t, d4AnchorGeometry(anchor));
                drawBadge(g, t, point(anchor, "anchorBlock"), "A" + i, color(i, 235));
            }
            title(g, "City D4 structure anchor preview",
                    "D2 body=blue collision=red mask=orange; A*=anchor index; anchors="
                            + array(anchorMap, "anchors").size());
            d4AnchorSummary(g, anchorMap);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        renderD4AnchorClusterDetail(anchorMap, reviewPackage, outputDirectory);
        return path;
    }

    public Path renderD4SlotCandidateDebugOverview(JsonObject candidateSet, CityLandformReviewPackage reviewPackage,
                                                   Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("structure_slot_candidate_debug_overview.png");
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            BlockBounds gridBounds = gridBounds(candidateSet);
            Transform t = transform(gridBounds);
            Map<String, Integer> slotIndexes = slotIndexes(candidateSet);
            drawPatchBackdrop(g, t, gridBounds, reviewPackage);
            drawGrid(g, t, gridBounds);
            drawClusterRelationshipLines(g, t, candidateSet, slotIndexes);
            drawClusterSelectedAnchors(g, t, candidateSet, slotIndexes);
            drawClusterCandidates(g, t, candidateSet, slotIndexes);
            title(g, "City D4 slot candidate debug overview",
                    "debug only; same-slot color; filled=selected; hollow=current candidates; red dashed=overlap; AUTO=top candidate");
            clusterCandidateSummary(g, candidateSet, slotIndexes);
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
                    BlockPoint anchor = point(candidate, "anchorBlock");
                    drawPoint(g, t, anchor, color(i, 235));
                    drawBadge(g, t, anchor, code, color(i, 235));
                }
            }
            title(g, "City D4 anchor candidate preview",
                    "patch backdrop + blue=frozen selected C*=current candidates; bbox hidden; candidates="
                            + candidateCount(candidateSet));
            candidateSummary(g, candidateSet);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    public Path renderD4StructureClusterGroupCandidates(JsonObject candidateSet,
                                                        CityLandformReviewPackage reviewPackage,
                                                        Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("structure_cluster_group_candidates.png");
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            BlockBounds gridBounds = gridBounds(candidateSet);
            Transform t = transform(gridBounds);
            drawPatchBackdrop(g, t, gridBounds, reviewPackage);
            drawGrid(g, t, gridBounds);
            int groupIndex = 0;
            for (JsonElement groupElem : array(candidateSet, "groupCandidates")) {
                if (!groupElem.isJsonObject()) {
                    continue;
                }
                groupIndex++;
                JsonObject group = groupElem.getAsJsonObject();
                Color groupColor = color(groupIndex, 235);
                drawStructureClusterGroupRelations(g, t, candidateSet, group, groupColor);
                for (JsonElement itemElem : array(group, "items")) {
                    if (!itemElem.isJsonObject()) {
                        continue;
                    }
                    JsonObject item = itemElem.getAsJsonObject();
                    BlockPoint anchor = point(item, "anchorBlock");
                    drawPoint(g, t, anchor, groupColor);
                    drawBadge(g, t, anchor, slotLabel(item), groupColor);
                }
            }
            title(g, "City D4 structure cluster group candidates",
                    "same color = one complete group; labels = structure slots; bbox hidden from main preview; groups="
                            + array(candidateSet, "groupCandidates").size());
            structureClusterGroupSummary(g, candidateSet);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    public Path renderD4ArrayCandidates(JsonObject candidateSet, CityLandformReviewPackage reviewPackage,
                                        Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("d4_array_candidate_preview.png");
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            BlockBounds gridBounds = gridBounds(candidateSet);
            Transform t = transform(gridBounds);
            drawPatchBackdrop(g, t, gridBounds, reviewPackage);
            drawGrid(g, t, gridBounds);
            int groupIndex = 0;
            for (JsonElement groupElem : array(candidateSet, "arrayCandidates")) {
                JsonObject group = groupElem.getAsJsonObject();
                groupIndex++;
                int itemIndex = 0;
                for (JsonElement itemElem : array(group, "items")) {
                    JsonObject item = itemElem.getAsJsonObject();
                    itemIndex++;
                    BlockPoint anchor = point(item, "anchorBlock");
                    drawPoint(g, t, anchor, color(groupIndex + itemIndex, 235));
                    drawBadge(g, t, anchor, "G" + groupIndex + "." + itemIndex,
                            color(groupIndex + itemIndex, 235));
                }
            }
            title(g, "City D4 array candidate preview",
                    "patch backdrop + G=item group candidates; bbox hidden; groups="
                            + array(candidateSet, "arrayCandidates").size());
            arrayCandidateSummary(g, candidateSet);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    public Path renderD4ArrayExpansionCandidates(JsonObject candidateSet,
                                                  CityLandformReviewPackage reviewPackage,
                                                  Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("d4_array_expansion_candidates.png");
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            BlockBounds gridBounds = gridBounds(candidateSet);
            Transform t = transform(gridBounds);
            drawPatchBackdrop(g, t, gridBounds, reviewPackage);
            drawGrid(g, t, gridBounds);
            JsonObject space = object(candidateSet, "expansionSpace");
            drawOptionalRect(g, t, space, "focusBodyEnvelope", D2_BODY_FILL, D2_BODY_STROKE, 2.3f);
            drawOptionalRect(g, t, space, "focusCollisionEnvelope", new Color(204, 79, 63, 38),
                    new Color(158, 59, 49, 190), 1.6f);
            drawOptionalRect(g, t, space, "selectedExpansionAvailableBounds", new Color(65, 145, 108, 22),
                    new Color(39, 111, 78, 150), 1.2f);
            if (space.has("selectedExpansionEntryPoint") && space.get("selectedExpansionEntryPoint").isJsonObject()) {
                drawGateway(g, t, point(space, "selectedExpansionEntryPoint"), new Color(39, 111, 78));
            }
            int candidateIndex = 0;
            for (JsonElement candidateElem : array(candidateSet, "arrayCandidates")) {
                if (!candidateElem.isJsonObject()) {
                    continue;
                }
                candidateIndex++;
                JsonObject candidate = candidateElem.getAsJsonObject();
                Color candidateColor = color(candidateIndex, 235);
                int itemIndex = 0;
                BlockPoint previous = null;
                for (JsonElement itemElem : array(candidate, "items")) {
                    if (!itemElem.isJsonObject()) {
                        continue;
                    }
                    itemIndex++;
                    JsonObject item = itemElem.getAsJsonObject();
                    BlockPoint anchor = point(item, "anchorBlock");
                    if (previous != null) {
                        drawLine(g, t, previous, anchor, withAlpha(candidateColor, 115), 1.3f, false);
                    }
                    drawD4Geometry(g, t, d4CandidateGeometry(item));
                    drawPoint(g, t, anchor, candidateColor);
                    drawBadge(g, t, anchor, "E" + candidateIndex + "." + itemIndex, candidateColor);
                    previous = anchor;
                }
            }
            title(g, "City D4 outward array candidates",
                    "blue=D2 body red=collision orange=mask green=outward space; one color = candidate group");
            expansionCandidateSummary(g, candidateSet);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        renderD4ArrayExpansionCandidateDetail(candidateSet, reviewPackage, outputDirectory);
        return path;
    }

    public Path renderD4ArrayLayoutLoop(JsonObject loopState, CityLandformReviewPackage reviewPackage,
                                        Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("d4_array_layout_preview.png");
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            BlockBounds gridBounds = gridBounds(loopState);
            Transform t = transform(gridBounds);
            drawPatchBackdrop(g, t, gridBounds, reviewPackage);
            drawGrid(g, t, gridBounds);
            JsonObject zones = object(loopState, "functionalArrayZones");
            int zoneIndex = 0;
            for (JsonElement zoneElem : array(zones, "arrayZones")) {
                if (!zoneElem.isJsonObject()) {
                    continue;
                }
                zoneIndex++;
                JsonObject zone = zoneElem.getAsJsonObject();
                Color zoneColor = color(zoneIndex, 235);
                drawArrayLayoutSubZones(g, t, zone, zoneColor);
                drawArrayLayoutZoneRelations(g, t, zone, zoneColor);
                int itemIndex = 0;
                for (JsonElement itemElem : array(zone, "items")) {
                    if (!itemElem.isJsonObject()) {
                        continue;
                    }
                    itemIndex++;
                    JsonObject item = itemElem.getAsJsonObject();
                    BlockPoint anchor = point(item, "anchorBlock");
                    drawPoint(g, t, anchor, zoneColor);
                    drawBadge(g, t, anchor, arrayLayoutItemLabel(item, itemIndex), zoneColor);
                }
                for (JsonElement accessElem : array(zone, "roadAccessPoints")) {
                    if (!accessElem.isJsonObject()) {
                        continue;
                    }
                    JsonObject access = accessElem.getAsJsonObject();
                    BlockPoint gateway = point(access, "anchorBlock");
                    drawGateway(g, t, gateway, zoneColor);
                }
            }
            title(g, "City D4 array layout loop preview",
                    "bbox hidden; parent/subZones shown for v0.3; square = RoadWeaver gateway; iteration="
                            + intValue(loopState, "iteration", 0));
            arrayLayoutLoopSummary(g, loopState);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    private static void renderD4AnchorClusterDetail(JsonObject anchorMap,
                                                    CityLandformReviewPackage reviewPackage,
                                                    Path outputDirectory) throws IOException {
        List<AnchorPreview> cluster = densestAnchorCluster(anchorMap);
        if (cluster.isEmpty()) {
            return;
        }
        Path path = outputDirectory.resolve("structure_anchor_cluster_preview.png");
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            BlockBounds viewport = expand(unionMasks(cluster), 24);
            Transform t = detailTransform(viewport);
            drawPatchBackdrop(g, t, viewport, reviewPackage);
            drawGrid(g, t, viewport);
            for (AnchorPreview preview : cluster) {
                drawD4Geometry(g, t, preview.geometry());
                drawBadge(g, t, point(preview.anchor(), "anchorBlock"), "A" + preview.index(),
                        color(preview.index(), 235));
            }
            title(g, "City D4 local structure cluster",
                    "D2 body=blue collision=red mask=orange; cluster anchors=" + cluster.size());
            drawD4DetailLegend(g, cluster);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
    }

    private static void renderD4ArrayExpansionCandidateDetail(JsonObject candidateSet,
                                                               CityLandformReviewPackage reviewPackage,
                                                               Path outputDirectory) throws IOException {
        JsonArray candidates = array(candidateSet, "arrayCandidates");
        if (candidates.isEmpty() || !candidates.get(0).isJsonObject()) {
            return;
        }
        JsonObject candidate = candidates.get(0).getAsJsonObject();
        List<PreviewGeometry> geometries = new ArrayList<>();
        for (JsonElement itemElem : array(candidate, "items")) {
            if (itemElem.isJsonObject()) {
                geometries.add(d4CandidateGeometry(itemElem.getAsJsonObject()));
            }
        }
        if (geometries.isEmpty()) {
            return;
        }

        JsonObject space = object(candidateSet, "expansionSpace");
        BlockBounds viewport = expand(unionGeometryAndFocus(geometries,
                hasBounds(space, "focusCollisionEnvelope") ? bounds(space, "focusCollisionEnvelope") : null), 24);
        Path path = outputDirectory.resolve("d4_array_expansion_candidate_detail.png");
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            Transform t = detailTransform(viewport);
            drawPatchBackdrop(g, t, viewport, reviewPackage);
            drawGrid(g, t, viewport);
            drawOptionalRect(g, t, space, "selectedExpansionAvailableBounds", new Color(65, 145, 108, 20),
                    new Color(39, 111, 78, 135), 1.1f);
            drawOptionalRect(g, t, space, "focusBodyEnvelope", D2_BODY_FILL, D2_BODY_STROKE, 2.4f);
            drawOptionalRect(g, t, space, "focusCollisionEnvelope", new Color(204, 79, 63, 28),
                    new Color(158, 59, 49, 200), 1.8f);
            if (hasBounds(space, "focusCollisionEnvelope")) {
                drawBadge(g, t, bounds(space, "focusCollisionEnvelope").center(), "F", new Color(158, 59, 49));
            }
            int itemIndex = 0;
            for (JsonElement itemElem : array(candidate, "items")) {
                if (!itemElem.isJsonObject()) {
                    continue;
                }
                itemIndex++;
                JsonObject item = itemElem.getAsJsonObject();
                drawD4Geometry(g, t, d4CandidateGeometry(item));
                BlockPoint anchor = point(item, "anchorBlock");
                drawPoint(g, t, anchor, color(itemIndex, 235));
                drawBadge(g, t, anchor, "E1." + itemIndex, color(itemIndex, 235));
            }
            title(g, "City D4 outward candidate local detail",
                    "E1=" + trim(string(candidate, "candidateId"), 42)
                            + " | blue=D2 body red=collision orange=mask F=focus collision");
            drawExpansionDetailLegend(g, candidate, space);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
    }

    static BlockBounds d2BodyBounds(JsonObject item) {
        if (hasBounds(item, "actualFootprint")) {
            return bounds(item, "actualFootprint");
        }
        if (hasBounds(item, "plannedFootprint")) {
            return bounds(item, "plannedFootprint");
        }
        BlockPoint anchor = point(item, "anchorBlock");
        return new BlockBounds(anchor.x(), anchor.z(), anchor.x(), anchor.z());
    }

    private static PreviewGeometry d4AnchorGeometry(JsonObject anchor) {
        BlockBounds body = d2BodyBounds(anchor);
        BlockBounds collision = firstBounds(anchor, body, "collisionEnvelope", "reservedEnvelope");
        BlockBounds mask = firstBounds(anchor, collision, "maskEnvelope");
        return new PreviewGeometry(body, collision, mask);
    }

    private static PreviewGeometry d4CandidateGeometry(JsonObject item) {
        BlockBounds body = d2BodyBounds(item);
        BlockBounds collision = firstBounds(item, body, "estimatedCollisionEnvelope", "collisionEnvelope");
        BlockBounds mask = firstBounds(item, collision, "estimatedMaskEnvelope", "maskEnvelope");
        return new PreviewGeometry(body, collision, mask);
    }

    private static void drawD4Geometry(Graphics2D g, Transform t, PreviewGeometry geometry) {
        drawRect(g, t, geometry.mask(), MASK_FILL, MASK_STROKE, 1.0f);
        drawRect(g, t, geometry.collision(), COLLISION_FILL, COLLISION_STROKE, 1.5f);
        drawRect(g, t, geometry.body(), D2_BODY_FILL, D2_BODY_STROKE, 2.5f);
    }

    private static List<AnchorPreview> densestAnchorCluster(JsonObject anchorMap) {
        List<AnchorPreview> anchors = new ArrayList<>();
        int index = 0;
        for (JsonElement elem : array(anchorMap, "anchors")) {
            if (elem.isJsonObject()) {
                index++;
                JsonObject anchor = elem.getAsJsonObject();
                anchors.add(new AnchorPreview(index, anchor, d4AnchorGeometry(anchor)));
            }
        }
        List<AnchorPreview> best = List.of();
        for (AnchorPreview seed : anchors) {
            List<AnchorPreview> component = new ArrayList<>();
            component.add(seed);
            for (int cursor = 0; cursor < component.size(); cursor++) {
                AnchorPreview current = component.get(cursor);
                for (AnchorPreview candidate : anchors) {
                    if (!component.contains(candidate)
                            && expand(current.geometry().collision(), D4_CLUSTER_LINK_GAP_BLOCKS)
                            .overlaps(candidate.geometry().collision())) {
                        component.add(candidate);
                    }
                }
            }
            if (component.size() > best.size()
                    || component.size() == best.size() && unionMasks(component).widthBlocks()
                    * unionMasks(component).heightBlocks() < unionMasks(best).widthBlocks()
                    * unionMasks(best).heightBlocks()) {
                best = component;
            }
        }
        best.sort(Comparator.comparingInt(AnchorPreview::index));
        return best;
    }

    private static BlockBounds unionMasks(List<AnchorPreview> previews) {
        BlockBounds union = previews.get(0).geometry().mask();
        for (int i = 1; i < previews.size(); i++) {
            union = union(union, previews.get(i).geometry().mask());
        }
        return union;
    }

    private static BlockBounds unionGeometryAndFocus(List<PreviewGeometry> geometries, BlockBounds focus) {
        BlockBounds union = focus == null ? geometries.get(0).mask() : focus;
        for (PreviewGeometry geometry : geometries) {
            union = union(union, geometry.mask());
        }
        return union;
    }

    private static void d4AnchorSummary(Graphics2D g, JsonObject anchorMap) {
        int x = 820;
        int y = 90;
        g.setColor(new Color(32, 34, 34));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.drawString("D4 geometry legend", x, y);
        y += 22;
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        y = legendRow(g, x, y, D2_BODY_STROKE, "blue = D2 body");
        y = legendRow(g, x, y, COLLISION_STROKE, "red = collision clearance");
        y = legendRow(g, x, y, MASK_STROKE, "orange = mask margin");
        y += 8;
        int index = 0;
        for (JsonElement elem : array(anchorMap, "anchors")) {
            if (!elem.isJsonObject() || y > HEIGHT - 44) {
                break;
            }
            index++;
            JsonObject anchor = elem.getAsJsonObject();
            PreviewGeometry geometry = d4AnchorGeometry(anchor);
            g.setColor(new Color(32, 34, 34));
            g.drawString("A" + index + " " + shortStructureName(firstString(anchor, "templateId", "templateRef"))
                    + " NBT " + dimensions(geometry.body()), x, y);
            y += 15;
            g.drawString("   C " + dimensions(geometry.collision()) + " M " + dimensions(geometry.mask()), x, y);
            y += 18;
        }
    }

    private static void drawD4DetailLegend(Graphics2D g, List<AnchorPreview> cluster) {
        int x = 24;
        int y = HEIGHT - 58;
        g.setColor(new Color(250, 248, 240, 228));
        g.fillRoundRect(x - 8, y - 20, 610, 54, 5, 5);
        g.setColor(new Color(48, 48, 42, 170));
        g.drawRoundRect(x - 8, y - 20, 610, 54, 5, 5);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        StringBuilder summary = new StringBuilder();
        for (AnchorPreview preview : cluster) {
            if (!summary.isEmpty()) {
                summary.append(" | ");
            }
            summary.append("A").append(preview.index()).append(" ")
                    .append(shortStructureName(firstString(preview.anchor(), "templateId", "templateRef")))
                    .append(" ").append(dimensions(preview.geometry().body()));
        }
        g.setColor(new Color(32, 34, 34));
        g.drawString(trim(summary.toString(), 82), x, y);
        g.drawString("NBT body dimensions; collision and mask remain visible around each body.", x, y + 17);
    }

    private static void drawExpansionDetailLegend(Graphics2D g, JsonObject candidate, JsonObject space) {
        int x = 24;
        int y = HEIGHT - 58;
        g.setColor(new Color(250, 248, 240, 228));
        g.fillRoundRect(x - 8, y - 20, 700, 54, 5, 5);
        g.setColor(new Color(48, 48, 42, 170));
        g.drawRoundRect(x - 8, y - 20, 700, 54, 5, 5);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        StringBuilder summary = new StringBuilder();
        int index = 0;
        for (JsonElement itemElem : array(candidate, "items")) {
            if (!itemElem.isJsonObject()) {
                continue;
            }
            index++;
            JsonObject item = itemElem.getAsJsonObject();
            if (!summary.isEmpty()) {
                summary.append(" | ");
            }
            summary.append("E1.").append(index).append(" ")
                    .append(shortStructureName(firstString(item, "templateId", "templateRef"))).append(" ")
                    .append(dimensions(d4CandidateGeometry(item).body()));
        }
        g.setColor(new Color(32, 34, 34));
        g.drawString(trim(summary.toString(), 94), x, y);
        if (hasBounds(space, "focusBodyEnvelope") && !array(candidate, "items").isEmpty()
                && array(candidate, "items").get(0).isJsonObject()) {
            BlockBounds parent = bounds(space, "focusBodyEnvelope");
            BlockBounds firstBody = d4CandidateGeometry(array(candidate, "items").get(0).getAsJsonObject()).body();
            g.drawString("F -> E1.1 NBT body edge gap=" + formatDistance(edgeDistanceBlocks(parent, firstBody))
                    + " blocks", x, y + 17);
        } else {
            g.drawString("Focus NBT body unavailable; red box is its collision boundary.", x, y + 17);
        }
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
                drawLabel(g, t, bounds(structure, footprintKey).center(), string(structure, "anchorId"));
            }
            title(g, "City D6 worldgen plan preview",
                    "planned worldgen structures=" + structures.size()
                            + " failures=" + object(trace, "failureSummary").size()
                            + " body=exact NBT footprint red=collision orange=mask");
            traceSummary(g, trace);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    public Path renderD7(JsonObject ledger, JsonObject trace, JsonObject materializationPlan,
                         Path outputDirectory) throws IOException {
        return renderD7(ledger, trace, materializationPlan, null, outputDirectory);
    }

    public Path renderD7(JsonObject ledger, JsonObject trace, JsonObject materializationPlan,
                         CityLandformReviewPackage reviewPackage, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("placed_structure_preview.png");
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        try {
            setup(g);
            BlockBounds gridBounds = gridBounds(object(materializationPlan, "sourceStructureAnchorMap"));
            Transform t = transform(gridBounds);
            drawPatchBackdrop(g, t, gridBounds, reviewPackage);
            drawGrid(g, t, gridBounds);
            int i = 0;
            for (JsonElement elem : array(ledger, "placedStructures")) {
                JsonObject placed = elem.getAsJsonObject();
                i++;
                drawRect(g, t, bounds(placed, "maskEnvelope"), new Color(202, 108, 62, 25),
                        new Color(178, 84, 46, 100), 0.9f);
                drawRect(g, t, bounds(placed, "collisionEnvelope"), new Color(207, 81, 70, 34),
                        new Color(150, 62, 52, 115), 1.0f);
                drawRect(g, t, bounds(placed, "actualFootprint"), color(i, 118), color(i, 235), 2.5f);
                drawLabel(g, t, bounds(placed, "actualFootprint").center(), string(placed, "anchorId"));
            }
            title(g, "City D7 placed structure preview",
                    "patch backdrop + body=exact NBT footprint red=collision orange=mask placed="
                            + array(ledger, "placedStructures").size()
                            + " waiting=" + object(trace, "waitingSummary").size()
                            + " failures=" + object(trace, "failureSummary").size());
            traceSummary(g, trace);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        return path;
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

    private static void drawClusterRelationshipLines(Graphics2D g, Transform t, JsonObject candidateSet,
                                                     Map<String, Integer> slotIndexes) {
        Map<String, BlockPoint> selectedCenters = selectedSlotCenters(candidateSet);
        for (JsonElement slotElem : array(object(candidateSet, "sourceDesignSlotPlan"), "slots")) {
            if (!slotElem.isJsonObject()) {
                continue;
            }
            JsonObject slot = slotElem.getAsJsonObject();
            String slotId = string(slot, "slotId");
            BlockPoint source = selectedCenters.get(slotId);
            if (source == null) {
                continue;
            }
            Color color = slotColor(slotId, slotIndexes, 105);
            for (JsonElement hintElem : array(slot, "relationHints")) {
                JsonObject hint = hintElem.getAsJsonObject();
                BlockPoint target = selectedCenters.get(string(hint, "targetSlotId"));
                if (target != null) {
                    drawLine(g, t, source, target, color, 1.0f, false);
                }
            }
        }

        for (JsonElement slotElem : array(candidateSet, "slotCandidates")) {
            if (!slotElem.isJsonObject()) {
                continue;
            }
            JsonObject slot = slotElem.getAsJsonObject();
            JsonObject designSlot = designSlot(candidateSet, string(slot, "slotId"));
            Color color = slotColor(string(slot, "slotId"), slotIndexes, 85);
            for (JsonElement candElem : array(slot, "candidates")) {
                JsonObject candidate = candElem.getAsJsonObject();
                BlockPoint source = point(candidate, "anchorBlock");
                for (JsonElement hintElem : array(designSlot, "relationHints")) {
                    JsonObject hint = hintElem.getAsJsonObject();
                    BlockPoint target = selectedCenters.get(string(hint, "targetSlotId"));
                    if (target != null) {
                        drawLine(g, t, source, target, color, 0.8f, true);
                    }
                }
            }
        }
    }

    private static void drawClusterSelectedAnchors(Graphics2D g, Transform t, JsonObject candidateSet,
                                                  Map<String, Integer> slotIndexes) {
        for (JsonElement elem : array(candidateSet, "occupiedEnvelopes")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject occupied = elem.getAsJsonObject();
            String slotId = string(occupied, "sourceSlotId");
            Color color = slotColor(slotId, slotIndexes, 42);
            Color stroke = slotColor(slotId, slotIndexes, 145);
            drawRect(g, t, bounds(occupied, "blockBounds"), color, stroke, 1.4f);
        }

        int index = 0;
        for (JsonElement elem : array(candidateSet, "selectedAnchors")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            index++;
            JsonObject anchor = elem.getAsJsonObject();
            String slotId = string(anchor, "slotId");
            Color color = slotColor(slotId, slotIndexes, 122);
            Color stroke = slotColor(slotId, slotIndexes, 235);
            drawOptionalRect(g, t, anchor, "estimatedCollisionEnvelope", color, stroke, 2.2f);
            BlockPoint point = point(anchor, "anchorBlock");
            drawPoint(g, t, point, stroke);
            drawBadge(g, t, point, "S" + index, stroke);
            drawLabel(g, t, point, trim(slotId, 18));
        }
    }

    private static void drawClusterCandidates(Graphics2D g, Transform t, JsonObject candidateSet,
                                              Map<String, Integer> slotIndexes) {
        JsonArray occupied = array(candidateSet, "occupiedEnvelopes");
        for (JsonElement slotElem : array(candidateSet, "slotCandidates")) {
            if (!slotElem.isJsonObject()) {
                continue;
            }
            JsonObject slot = slotElem.getAsJsonObject();
            String slotId = string(slot, "slotId");
            Color base = slotColor(slotId, slotIndexes, 235);
            int candidateIndex = 0;
            for (JsonElement candElem : array(slot, "candidates")) {
                if (!candElem.isJsonObject()) {
                    continue;
                }
                candidateIndex++;
                JsonObject candidate = candElem.getAsJsonObject();
                BlockBounds collision = bounds(candidate, "estimatedCollisionEnvelope");
                boolean overlap = overlapsAny(collision, occupied);
                boolean autoPick = candidateIndex == 1;
                if (overlap) {
                    drawDashedRect(g, t, collision, new Color(214, 70, 64, 34),
                            new Color(196, 49, 44, 230), autoPick ? 3.0f : 2.0f);
                } else {
                    drawRect(g, t, collision, withAlpha(base, autoPick ? 34 : 18),
                            withAlpha(base, autoPick ? 235 : 180), autoPick ? 2.8f : 1.7f);
                }
                BlockPoint point = point(candidate, "anchorBlock");
                drawPoint(g, t, point, overlap ? new Color(214, 70, 64, 235) : base);
                String label = candidateOrdinal(candidate, candidateIndex);
                drawBadge(g, t, point, autoPick ? "AUTO " + label : label,
                        overlap ? new Color(214, 70, 64, 235) : base);
            }
        }
    }

    private static void clusterCandidateSummary(Graphics2D g, JsonObject candidateSet,
                                                Map<String, Integer> slotIndexes) {
        int x = 820;
        int y = 90;
        g.setColor(new Color(32, 34, 34));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.drawString("cluster overview", x, y);
        y += 22;
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        g.drawString("current=" + trim(string(candidateSet, "currentSlotId"), 42), x, y);
        y += 16;
        g.drawString("selected=" + array(candidateSet, "selectedAnchors").size()
                + " occupied=" + array(candidateSet, "occupiedEnvelopes").size()
                + " candidates=" + candidateCount(candidateSet), x, y);
        y += 24;
        y = legendRow(g, x, y, new Color(67, 112, 178, 235), "filled selected footprint");
        y = legendRow(g, x, y, new Color(61, 151, 113, 235), "hollow current candidate");
        y = legendRow(g, x, y, new Color(214, 70, 64, 235), "red dashed collision conflict");
        y += 10;

        JsonArray occupied = array(candidateSet, "occupiedEnvelopes");
        for (JsonElement slotElem : array(candidateSet, "slotCandidates")) {
            if (!slotElem.isJsonObject() || y > HEIGHT - 70) {
                break;
            }
            JsonObject slot = slotElem.getAsJsonObject();
            String slotId = string(slot, "slotId");
            Color color = slotColor(slotId, slotIndexes, 235);
            y = legendRow(g, x, y, color, trim(slotId + " " + string(slot, "displayRole"), 46));
            int candidateIndex = 0;
            for (JsonElement candElem : array(slot, "candidates")) {
                if (!candElem.isJsonObject() || y > HEIGHT - 45) {
                    break;
                }
                candidateIndex++;
                JsonObject candidate = candElem.getAsJsonObject();
                String score = object(candidate, "scoreBreakdown").has("total")
                        ? String.format(java.util.Locale.ROOT, "%.2f",
                        object(candidate, "scoreBreakdown").get("total").getAsDouble())
                        : "";
                boolean overlap = overlapsAny(bounds(candidate, "estimatedCollisionEnvelope"), occupied);
                g.setColor(overlap ? new Color(168, 42, 38) : new Color(32, 34, 34));
                g.drawString("  " + (candidateIndex == 1 ? "AUTO " : "     ")
                        + candidateOrdinal(candidate, candidateIndex)
                        + " " + score + " " + (overlap ? "overlap " : "")
                        + trim(string(candidate, "candidateId"), 26), x, y);
                y += 15;
            }
            y += 4;
        }
    }

    private static int legendRow(Graphics2D g, int x, int y, Color color, String text) {
        g.setColor(withAlpha(color, 120));
        g.fillRect(x, y - 10, 13, 10);
        g.setColor(color);
        g.drawRect(x, y - 10, 13, 10);
        g.setColor(new Color(32, 34, 34));
        g.drawString(text, x + 20, y);
        return y + 16;
    }

    private static Map<String, Integer> slotIndexes(JsonObject candidateSet) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        int index = 0;
        for (JsonElement elem : array(object(candidateSet, "sourceDesignSlotPlan"), "placementOrder")) {
            String slotId = elem.isJsonPrimitive() ? elem.getAsString() : "";
            if (!slotId.isBlank() && !indexes.containsKey(slotId)) {
                indexes.put(slotId, index++);
            }
        }
        for (JsonElement elem : array(candidateSet, "selectedAnchors")) {
            if (elem.isJsonObject()) {
                String slotId = string(elem.getAsJsonObject(), "slotId");
                if (!slotId.isBlank() && !indexes.containsKey(slotId)) {
                    indexes.put(slotId, index++);
                }
            }
        }
        for (JsonElement elem : array(candidateSet, "slotCandidates")) {
            if (elem.isJsonObject()) {
                String slotId = string(elem.getAsJsonObject(), "slotId");
                if (!slotId.isBlank() && !indexes.containsKey(slotId)) {
                    indexes.put(slotId, index++);
                }
            }
        }
        return indexes;
    }

    private static Map<String, BlockPoint> selectedSlotCenters(JsonObject candidateSet) {
        Map<String, BlockPoint> result = new HashMap<>();
        for (JsonElement elem : array(candidateSet, "selectedAnchors")) {
            if (elem.isJsonObject()) {
                JsonObject anchor = elem.getAsJsonObject();
                result.put(string(anchor, "slotId"), point(anchor, "anchorBlock"));
            }
        }
        return result;
    }

    private static JsonObject designSlot(JsonObject candidateSet, String slotId) {
        for (JsonElement elem : array(object(candidateSet, "sourceDesignSlotPlan"), "slots")) {
            if (elem.isJsonObject()) {
                JsonObject slot = elem.getAsJsonObject();
                if (slotId.equals(string(slot, "slotId"))) {
                    return slot;
                }
            }
        }
        return new JsonObject();
    }

    private static boolean overlapsAny(BlockBounds bounds, JsonArray occupiedEnvelopes) {
        for (JsonElement elem : occupiedEnvelopes) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject occupied = elem.getAsJsonObject();
            if (bounds.overlaps(bounds(occupied, "blockBounds"))) {
                return true;
            }
        }
        return false;
    }

    private static void drawOptionalRect(Graphics2D g, Transform t, JsonObject obj, String key,
                                         Color fill, Color stroke, float strokeWidth) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonObject()) {
            return;
        }
        drawRect(g, t, bounds(obj, key), fill, stroke, strokeWidth);
    }

    private static void drawDashedRect(Graphics2D g, Transform t, BlockBounds bounds,
                                       Color fill, Color stroke, float strokeWidth) {
        g.setColor(fill);
        fillBounds(g, t, bounds);
        g.setColor(stroke);
        g.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10.0f, new float[]{7.0f, 5.0f}, 0.0f));
        drawBounds(g, t, bounds);
    }

    private static void drawLine(Graphics2D g, Transform t, BlockPoint from, BlockPoint to,
                                 Color color, float width, boolean dashed) {
        g.setColor(color);
        if (dashed) {
            g.setStroke(new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10.0f, new float[]{6.0f, 5.0f}, 0.0f));
        } else {
            g.setStroke(new BasicStroke(width));
        }
        g.drawLine(t.x(from.x()), t.z(from.z()), t.x(to.x()), t.z(to.z()));
    }

    private static Color slotColor(String slotId, Map<String, Integer> slotIndexes, int alpha) {
        int index = slotIndexes.getOrDefault(slotId, Math.floorMod(slotId.hashCode(), 12));
        return color(index + 1, alpha);
    }

    private static String candidateOrdinal(JsonObject candidate, int fallbackIndex) {
        String id = string(candidate, "candidateId");
        int underscore = id.lastIndexOf('_');
        if (underscore >= 0 && underscore + 1 < id.length()) {
            String suffix = id.substring(underscore + 1);
            boolean numeric = true;
            for (int i = 0; i < suffix.length(); i++) {
                if (!Character.isDigit(suffix.charAt(i))) {
                    numeric = false;
                    break;
                }
            }
            if (numeric) {
                return suffix;
            }
        }
        return "C" + fallbackIndex;
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

    private static void arrayCandidateSummary(Graphics2D g, JsonObject candidateSet) {
        int x = 820;
        int y = 90;
        g.setColor(new Color(32, 34, 34));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.drawString("array candidate legend", x, y);
        y += 24;
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        int groupIndex = 0;
        for (JsonElement groupElem : array(candidateSet, "arrayCandidates")) {
            if (y > HEIGHT - 45) {
                break;
            }
            JsonObject group = groupElem.getAsJsonObject();
            groupIndex++;
            String score = object(group, "scoreBreakdown").has("total")
                    ? String.format(java.util.Locale.ROOT, "%.2f",
                    object(group, "scoreBreakdown").get("total").getAsDouble())
                    : "";
            g.drawString("G" + groupIndex + " " + score + " "
                    + trim(string(group, "arrayCandidateId"), 48), x, y);
            y += 15;
            g.drawString("  pattern=" + trim(string(group, "arrayPattern"), 40)
                    + " items=" + array(group, "items").size(), x, y);
            y += 18;
        }
    }

    private static void expansionCandidateSummary(Graphics2D g, JsonObject candidateSet) {
        int x = 820;
        int y = 90;
        g.setColor(new Color(32, 34, 34));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.drawString("outward candidate legend", x, y);
        y += 24;
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JsonObject space = object(candidateSet, "expansionSpace");
        g.drawString("direction=" + trim(string(space, "selectedDirection"), 18), x, y);
        y += 15;
        g.drawString("patch=" + trim(string(space, "selectedTargetPatchRef"), 42), x, y);
        y += 20;
        int candidateIndex = 0;
        for (JsonElement candidateElem : array(candidateSet, "arrayCandidates")) {
            if (!candidateElem.isJsonObject() || y > HEIGHT - 42) {
                continue;
            }
            candidateIndex++;
            JsonObject candidate = candidateElem.getAsJsonObject();
            g.drawString("E" + candidateIndex + " score="
                    + String.format(java.util.Locale.ROOT, "%.0f", candidate.get("score").getAsDouble()), x, y);
            y += 15;
            g.drawString("  " + trim(string(candidate, "candidateId"), 48)
                    + " items=" + array(candidate, "items").size(), x, y);
            y += 18;
        }
    }

    private static void drawArrayLayoutZoneRelations(Graphics2D g, Transform t, JsonObject zone, Color zoneColor) {
        BlockPoint previous = null;
        g.setColor(withAlpha(zoneColor, 118));
        g.setStroke(new BasicStroke(1.5f));
        for (JsonElement itemElem : array(zone, "items")) {
            if (!itemElem.isJsonObject()) {
                continue;
            }
            BlockPoint current = point(itemElem.getAsJsonObject(), "anchorBlock");
            if (previous != null) {
                g.drawLine(t.x(previous.x()), t.z(previous.z()), t.x(current.x()), t.z(current.z()));
            }
            previous = current;
        }
    }

    private static void drawArrayLayoutSubZones(Graphics2D g, Transform t, JsonObject zone, Color zoneColor) {
        JsonArray subZones = array(zone, "subZones");
        if (subZones.isEmpty()) {
            return;
        }
        int index = 0;
        for (JsonElement elem : subZones) {
            if (!elem.isJsonObject()) {
                continue;
            }
            index++;
            JsonObject subZone = elem.getAsJsonObject();
            BlockBounds bounds = bounds(subZone, "blockBounds");
            drawRect(g, t, bounds, withAlpha(zoneColor, 18), withAlpha(zoneColor, 118), 1.1f);
            drawLabel(g, t, bounds.center(), "S" + index);
        }
    }

    private static void drawGateway(Graphics2D g, Transform t, BlockPoint point, Color color) {
        int x = t.x(point.x());
        int z = t.z(point.z());
        g.setColor(withAlpha(color, 230));
        g.fillRect(x - 6, z - 6, 12, 12);
        g.setColor(new Color(32, 35, 34, 230));
        g.setStroke(new BasicStroke(1.4f));
        g.drawRect(x - 6, z - 6, 12, 12);
    }

    private static String arrayLayoutItemLabel(JsonObject item, int fallbackIndex) {
        String itemId = string(item, "itemId");
        if (itemId.isBlank()) {
            itemId = "I" + fallbackIndex;
        }
        return trim(itemId, 12);
    }

    private static void arrayLayoutLoopSummary(Graphics2D g, JsonObject loopState) {
        int x = 820;
        int y = 90;
        g.setColor(new Color(32, 34, 34));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.drawString("array layout loop", x, y);
        y += 22;
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        g.drawString("state=" + trim(string(loopState, "stateId"), 36)
                + " status=" + trim(string(loopState, "status"), 22), x, y);
        y += 16;
        g.drawString("iteration=" + intValue(loopState, "iteration", 0)
                + " max=" + intValue(loopState, "maxArrayPlans", 0), x, y);
        y += 22;
        JsonObject zones = object(loopState, "functionalArrayZones");
        int zoneIndex = 0;
        for (JsonElement zoneElem : array(zones, "arrayZones")) {
            if (!zoneElem.isJsonObject() || y > HEIGHT - 55) {
                break;
            }
            zoneIndex++;
            JsonObject zone = zoneElem.getAsJsonObject();
            g.setColor(color(zoneIndex, 235));
            g.fillRect(x, y - 10, 10, 10);
            g.setColor(new Color(32, 34, 34));
            String prefix = "parent_composite".equals(string(zone, "zoneKind")) ? "P" : "Z";
            g.drawString(prefix + zoneIndex + " " + trim(string(zone, "arrayId"), 34)
                    + " items=" + array(zone, "items").size(), x + 16, y);
            y += 15;
            String parent = string(zone, "parentArrayId").isBlank() ? "" : " parent=" + trim(string(zone, "parentArrayId"), 16);
            g.drawString("  " + trim(string(zone, "zoneKind"), 18) + " "
                    + trim(string(zone, "plannerType"), 24) + parent
                    + " gateways=" + array(zone, "roadAccessPoints").size(), x, y);
            y += 18;
        }
    }

    private static void drawStructureClusterGroupRelations(Graphics2D g, Transform t, JsonObject candidateSet,
                                                           JsonObject group, Color groupColor) {
        Map<String, BlockPoint> pointsBySlot = new LinkedHashMap<>();
        for (JsonElement itemElem : array(group, "items")) {
            if (itemElem.isJsonObject()) {
                JsonObject item = itemElem.getAsJsonObject();
                pointsBySlot.put(string(item, "slotId"), point(item, "anchorBlock"));
            }
        }
        boolean drewRelation = false;
        for (JsonElement slotElem : array(object(candidateSet, "sourceDesignSlotPlan"), "slots")) {
            if (!slotElem.isJsonObject()) {
                continue;
            }
            JsonObject slot = slotElem.getAsJsonObject();
            BlockPoint source = pointsBySlot.get(string(slot, "slotId"));
            if (source == null) {
                continue;
            }
            for (JsonElement hintElem : array(slot, "relationHints")) {
                if (!hintElem.isJsonObject()) {
                    continue;
                }
                BlockPoint target = pointsBySlot.get(string(hintElem.getAsJsonObject(), "targetSlotId"));
                if (target != null) {
                    drawLine(g, t, source, target, withAlpha(groupColor, 138), 1.2f, false);
                    drewRelation = true;
                }
            }
        }
        if (drewRelation) {
            return;
        }
        BlockPoint previous = null;
        for (JsonElement itemElem : array(group, "items")) {
            if (!itemElem.isJsonObject()) {
                continue;
            }
            BlockPoint current = point(itemElem.getAsJsonObject(), "anchorBlock");
            if (previous != null) {
                drawLine(g, t, previous, current, withAlpha(groupColor, 96), 1.0f, true);
            }
            previous = current;
        }
    }

    private static void structureClusterGroupSummary(Graphics2D g, JsonObject candidateSet) {
        int x = 820;
        int y = 90;
        g.setColor(new Color(32, 34, 34));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.drawString("group candidate legend", x, y);
        y += 22;
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        g.drawString("bbox hidden; select one whole group", x, y);
        y += 20;
        int groupIndex = 0;
        for (JsonElement groupElem : array(candidateSet, "groupCandidates")) {
            if (!groupElem.isJsonObject() || y > HEIGHT - 70) {
                break;
            }
            JsonObject group = groupElem.getAsJsonObject();
            groupIndex++;
            Color groupColor = color(groupIndex, 235);
            String score = object(group, "scoreBreakdown").has("total")
                    ? String.format(java.util.Locale.ROOT, "%.2f",
                    object(group, "scoreBreakdown").get("total").getAsDouble())
                    : "";
            y = legendRow(g, x, y, groupColor, "G" + groupIndex + " score=" + score
                    + " items=" + array(group, "items").size());
            g.setColor(new Color(32, 34, 34));
            g.drawString("  " + trim(string(group, "groupCandidateId"), 46), x, y);
            y += 15;
            String risks = risksText(array(group, "risks"));
            if (!risks.isBlank()) {
                g.drawString("  risks=" + trim(risks, 44), x, y);
                y += 15;
            }
            y += 5;
        }
    }

    private static String slotLabel(JsonObject item) {
        String slotId = string(item, "slotId");
        if (slotId.isBlank()) {
            return "slot";
        }
        String[] parts = slotId.split("_");
        String label = parts.length == 0 ? slotId : parts[0];
        if ("residence".equals(label) && parts.length > 1) {
            label = "res";
        }
        return trim(label, 8);
    }

    private static String risksText(JsonArray risks) {
        StringBuilder builder = new StringBuilder();
        for (JsonElement elem : risks) {
            if (elem.isJsonNull()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(",");
            }
            builder.append(elem.getAsString());
        }
        return builder.toString();
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
            String template = string(item, "templateId");
            String text = template.isBlank() ? id : id + " " + template;
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

    private static BlockBounds firstBounds(JsonObject item, BlockBounds fallback, String... keys) {
        for (String key : keys) {
            if (hasBounds(item, key)) {
                return bounds(item, key);
            }
        }
        return fallback;
    }

    private static boolean hasBounds(JsonObject item, String key) {
        return item != null && item.has(key) && item.get(key).isJsonObject()
                && isBounds(item.getAsJsonObject(key));
    }

    private static boolean isBounds(JsonObject value) {
        return value != null && value.has("minX") && value.has("minZ")
                && value.has("maxX") && value.has("maxZ");
    }

    private static BlockBounds expand(BlockBounds bounds, int amount) {
        int normalized = Math.max(0, amount);
        return new BlockBounds(bounds.minX() - normalized, bounds.minZ() - normalized,
                bounds.maxX() + normalized, bounds.maxZ() + normalized);
    }

    private static BlockBounds union(BlockBounds left, BlockBounds right) {
        return new BlockBounds(Math.min(left.minX(), right.minX()), Math.min(left.minZ(), right.minZ()),
                Math.max(left.maxX(), right.maxX()), Math.max(left.maxZ(), right.maxZ()));
    }

    private static String dimensions(BlockBounds bounds) {
        return bounds.widthBlocks() + "x" + bounds.heightBlocks();
    }

    private static String shortStructureName(String structureId) {
        if (structureId == null || structureId.isBlank()) {
            return "structure";
        }
        int slash = structureId.lastIndexOf('/');
        return trim(slash >= 0 ? structureId.substring(slash + 1) : structureId, 18);
    }

    private static double edgeDistanceBlocks(BlockBounds left, BlockBounds right) {
        int gapX = axisGap(left.minX(), left.maxX(), right.minX(), right.maxX());
        int gapZ = axisGap(left.minZ(), left.maxZ(), right.minZ(), right.maxZ());
        return Math.hypot(gapX, gapZ);
    }

    private static int axisGap(int firstMin, int firstMax, int secondMin, int secondMax) {
        if (firstMax < secondMin) {
            return secondMin - firstMax - 1;
        }
        if (secondMax < firstMin) {
            return firstMin - secondMax - 1;
        }
        return 0;
    }

    private static String formatDistance(double distance) {
        if (Math.abs(distance - Math.rint(distance)) < 0.001d) {
            return Integer.toString((int) Math.rint(distance));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", distance);
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

    private static Transform detailTransform(BlockBounds bounds) {
        double sx = (WIDTH - PAD * 2) / (double) Math.max(1, bounds.widthBlocks());
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

    private static String firstString(JsonObject obj, String... keys) {
        for (String key : keys) {
            String value = string(obj, key);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
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

    private record PreviewGeometry(BlockBounds body, BlockBounds collision, BlockBounds mask) {
    }

    private record AnchorPreview(int index, JsonObject anchor, PreviewGeometry geometry) {
    }
}
