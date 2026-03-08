package com.user.terra_script.world.city.stage.c4;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.user.terra_script.domain.world.scan.ScanPixel;
import com.user.terra_script.util.TerrainFeatureComputer;
import com.user.terra_script.world.city.stage.c2.CityC2ScanBinaryIO;
import com.user.terra_script.world.city.stage.CityGroupPathUtil;
import com.user.terra_script.world.city.stage.c2.CityC3OwnershipIO;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class CityC5GroupTerrainPreviewExporter {
    private static final int PREVIEW_SIZE = 512;
    private static final int PREVIEW_PADDING = 24;
    private static final int GRID_STEP_BLOCKS = 10;
    private static final int SEA_LEVEL = 63;
    private static final double HILLSHADE_AZIMUTH_DEG = 315.0;
    private static final double HILLSHADE_ALTITUDE_DEG = 45.0;

    private CityC5GroupTerrainPreviewExporter() {}

    public static JsonObject export(
            MinecraftServer server,
            String cityId,
            CitySemanticStages.C5Groups groups,
            CityC2ScanBinaryIO.C2ScanData scanData,
            CityC3OwnershipIO.OwnershipData ownershipData
    ) throws Exception {
        JsonObject out = new JsonObject();
        if (server == null
                || cityId == null
                || cityId.isBlank()
                || groups == null
                || groups.groups == null
                || groups.groups.isEmpty()
                || scanData == null
                || scanData.map == null
                || scanData.map.length == 0
                || scanData.map[0] == null
                || ownershipData == null
                || ownershipData.owner == null) {
            out.addProperty("generated", false);
            out.addProperty("reason", "invalid_input");
            return out;
        }

        GroupCodeMapping codeMapping = buildGroupCodeMap(groups, ownershipData);
        double[][] heightRaw = extractHeight(scanData.map);
        double[][] roughRaw = TerrainFeatureComputer.computeRoughness(scanData.map, scanData.step, 2, false);
        double[][] hillshadeRaw = buildHillshade(heightRaw);

        Path cityDir = server.getWorldPath(LevelResource.ROOT)
                .resolve("terra_script")
                .resolve("cities")
                .resolve(cityId);
        Files.createDirectories(cityDir);

        JsonArray items = new JsonArray();
        for (int code = 1; code <= groups.groups.size(); code++) {
            BBox bbox = computeGroupBBox(ownershipData, codeMapping, code);
            if (bbox == null) continue;
            int groupCells = countCellsWithCode(ownershipData, codeMapping, code, bbox);
            if (groupCells <= 0) continue;

            String groupId = codeMapping.codeToGroupId.getOrDefault(code, "group_" + code);
            Path groupDir = CityGroupPathUtil.resolveGroupDir(cityDir, groupId);
            double[] heightRange = localRange(heightRaw, ownershipData, scanData, codeMapping, code, bbox);
            double[] roughRange = localRange(roughRaw, ownershipData, scanData, codeMapping, code, bbox);
            double[] hillRange = localRange(hillshadeRaw, ownershipData, scanData, codeMapping, code, bbox);

            RenderContext context = RenderContext.fromBBox(bbox);
            BufferedImage heightImg = renderLocalHeightColor(heightRaw, ownershipData, scanData, codeMapping, code, bbox, heightRange, context);
            BufferedImage roughImg = renderLocalGrayscale(roughRaw, ownershipData, scanData, codeMapping, code, bbox, roughRange, context);
            BufferedImage hillImg = renderLocalGrayscale(hillshadeRaw, ownershipData, scanData, codeMapping, code, bbox, hillRange, context);

            drawGridAndLabel(heightImg, groupId, bbox, context);
            drawGridAndLabel(roughImg, groupId, bbox, context);
            drawGridAndLabel(hillImg, groupId, bbox, context);

            String heightFile = "height.png";
            String roughFile = "roughness.png";
            String hillFile = "hillshade.png";
            ImageIO.write(heightImg, "png", groupDir.resolve(heightFile).toFile());
            ImageIO.write(roughImg, "png", groupDir.resolve(roughFile).toFile());
            ImageIO.write(hillImg, "png", groupDir.resolve(hillFile).toFile());
            JsonObject terrainLegend = new JsonObject();
            terrainLegend.addProperty("group_id", groupId);
            terrainLegend.addProperty("height_image", heightFile);
            terrainLegend.addProperty("roughness_image", roughFile);
            terrainLegend.addProperty("hillshade_image", hillFile);
            terrainLegend.addProperty("grid_step_blocks", GRID_STEP_BLOCKS);
            Files.writeString(groupDir.resolve("terrain.legend.json"), terrainLegend.toString());

            JsonObject item = new JsonObject();
            item.addProperty("group_id", groupId);
            item.addProperty("code", code);
            item.addProperty("height_image", CityGroupPathUtil.relativeGroupPath(cityId, groupId, heightFile));
            item.addProperty("roughness_image", CityGroupPathUtil.relativeGroupPath(cityId, groupId, roughFile));
            item.addProperty("hillshade_image", CityGroupPathUtil.relativeGroupPath(cityId, groupId, hillFile));
            item.addProperty("legend", CityGroupPathUtil.relativeGroupPath(cityId, groupId, "terrain.legend.json"));
            item.addProperty("grid_step_blocks", GRID_STEP_BLOCKS);
            item.addProperty("bbox_min_x", bbox.minX);
            item.addProperty("bbox_min_z", bbox.minZ);
            item.addProperty("bbox_width", bbox.width());
            item.addProperty("bbox_height", bbox.height());
            item.addProperty("height_min", round3(heightRange[0]));
            item.addProperty("height_max", round3(heightRange[1]));
            item.addProperty("roughness_min", round3(roughRange[0]));
            item.addProperty("roughness_max", round3(roughRange[1]));
            item.addProperty("hillshade_min", round3(hillRange[0]));
            item.addProperty("hillshade_max", round3(hillRange[1]));
            items.add(item);
        }

        out.addProperty("generated", true);
        out.addProperty("type", "city_c5_group_terrain_previews");
        out.addProperty("resolution", PREVIEW_SIZE);
        out.add("groups", items);
        return out;
    }

    private static GroupCodeMapping buildGroupCodeMap(CitySemanticStages.C5Groups groups, CityC3OwnershipIO.OwnershipData ownershipData) {
        int maxDistrictId = 0;
        for (int x = 0; x < ownershipData.width; x++) {
            for (int z = 0; z < ownershipData.height; z++) {
                maxDistrictId = Math.max(maxDistrictId, ownershipData.owner[x][z]);
            }
        }
        GroupCodeMapping mapping = new GroupCodeMapping();
        mapping.districtToCode = new int[Math.max(1, maxDistrictId + 1)];
        int code = 1;
        for (CitySemanticStages.ModuleGroup group : groups.groups) {
            if (group == null) continue;
            mapping.codeToGroupId.put(code, group.group_id == null || group.group_id.isBlank() ? ("group_" + code) : group.group_id);
            if (group.district_numeric_ids != null) {
                for (Integer districtId : group.district_numeric_ids) {
                    if (districtId == null || districtId < 0) continue;
                    if (districtId >= mapping.districtToCode.length) {
                        int[] grown = new int[districtId + 16];
                        System.arraycopy(mapping.districtToCode, 0, grown, 0, mapping.districtToCode.length);
                        mapping.districtToCode = grown;
                    }
                    mapping.districtToCode[districtId] = code;
                }
            }
            code++;
        }
        return mapping;
    }

    private static BBox computeGroupBBox(CityC3OwnershipIO.OwnershipData ownershipData, GroupCodeMapping mapping, int code) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int ox = 0; ox < ownershipData.width; ox++) {
            for (int oz = 0; oz < ownershipData.height; oz++) {
                int districtId = ownershipData.owner[ox][oz];
                if (!isDistrictInCode(mapping, districtId, code)) continue;
                int wx = ownershipData.originX + ox;
                int wz = ownershipData.originZ + oz;
                if (wx < minX) minX = wx;
                if (wz < minZ) minZ = wz;
                if (wx > maxX) maxX = wx;
                if (wz > maxZ) maxZ = wz;
            }
        }
        if (minX == Integer.MAX_VALUE) return null;
        return new BBox(minX, minZ, maxX, maxZ);
    }

    private static int countCellsWithCode(
            CityC3OwnershipIO.OwnershipData ownershipData,
            GroupCodeMapping mapping,
            int code,
            BBox bbox
    ) {
        int count = 0;
        for (int wx = bbox.minX; wx <= bbox.maxX; wx++) {
            int ox = wx - ownershipData.originX;
            if (ox < 0 || ox >= ownershipData.width) continue;
            for (int wz = bbox.minZ; wz <= bbox.maxZ; wz++) {
                int oz = wz - ownershipData.originZ;
                if (oz < 0 || oz >= ownershipData.height) continue;
                if (isDistrictInCode(mapping, ownershipData.owner[ox][oz], code)) count++;
            }
        }
        return count;
    }

    private static double[] localRange(
            double[][] values,
            CityC3OwnershipIO.OwnershipData ownershipData,
            CityC2ScanBinaryIO.C2ScanData scanData,
            GroupCodeMapping mapping,
            int code,
            BBox bbox
    ) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int wx = bbox.minX; wx <= bbox.maxX; wx++) {
            int ox = wx - ownershipData.originX;
            if (ox < 0 || ox >= ownershipData.width) continue;
            for (int wz = bbox.minZ; wz <= bbox.maxZ; wz++) {
                int oz = wz - ownershipData.originZ;
                if (oz < 0 || oz >= ownershipData.height) continue;
                int districtId = ownershipData.owner[ox][oz];
                if (!isDistrictInCode(mapping, districtId, code)) continue;
                double v = sampleRaw(values, scanData, wx, wz);
                if (Double.isNaN(v) || Double.isInfinite(v)) continue;
                if (v < min) min = v;
                if (v > max) max = v;
            }
        }
        if (!Double.isFinite(min) || !Double.isFinite(max) || max <= min) {
            return new double[]{0.0, 1.0};
        }
        return new double[]{min, max};
    }

    private static BufferedImage renderLocalGrayscale(
            double[][] values,
            CityC3OwnershipIO.OwnershipData ownershipData,
            CityC2ScanBinaryIO.C2ScanData scanData,
            GroupCodeMapping mapping,
            int code,
            BBox bbox,
            double[] range,
            RenderContext context
    ) {
        BufferedImage image = new BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_ARGB);
        double min = range[0];
        double max = range[1];
        double span = Math.max(1e-9, max - min);

        Graphics2D g = image.createGraphics();
        g.setColor(new Color(15, 15, 15));
        g.fillRect(0, 0, PREVIEW_SIZE, PREVIEW_SIZE);
        g.dispose();

        for (int wx = bbox.minX; wx <= bbox.maxX; wx++) {
            int ox = wx - ownershipData.originX;
            if (ox < 0 || ox >= ownershipData.width) continue;
            for (int wz = bbox.minZ; wz <= bbox.maxZ; wz++) {
                int oz = wz - ownershipData.originZ;
                if (oz < 0 || oz >= ownershipData.height) continue;
                int districtId = ownershipData.owner[ox][oz];
                if (!isDistrictInCode(mapping, districtId, code)) continue;

                double value = sampleRaw(values, scanData, wx, wz);
                double n = (value - min) / span;
                int gray = (int) Math.round(Math.max(0.0, Math.min(1.0, n)) * 255.0);
                int rgb = 0xFF000000 | (gray << 16) | (gray << 8) | gray;

                int px0 = context.worldToPxX(wx);
                int px1 = context.worldToPxX(wx + 1);
                int pz0 = context.worldToPxZ(wz);
                int pz1 = context.worldToPxZ(wz + 1);
                int w = Math.max(1, px1 - px0);
                int h = Math.max(1, pz1 - pz0);
                fillRect(image, px0, pz0, w, h, rgb);
            }
        }
        return image;
    }

    private static BufferedImage renderLocalHeightColor(
            double[][] heightValues,
            CityC3OwnershipIO.OwnershipData ownershipData,
            CityC2ScanBinaryIO.C2ScanData scanData,
            GroupCodeMapping mapping,
            int code,
            BBox bbox,
            double[] range,
            RenderContext context
    ) {
        BufferedImage image = new BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(15, 15, 15));
        g.fillRect(0, 0, PREVIEW_SIZE, PREVIEW_SIZE);
        g.dispose();

        double min = range[0];
        double max = range[1];
        double span = Math.max(1e-9, max - min);
        for (int wx = bbox.minX; wx <= bbox.maxX; wx++) {
            int ox = wx - ownershipData.originX;
            if (ox < 0 || ox >= ownershipData.width) continue;
            for (int wz = bbox.minZ; wz <= bbox.maxZ; wz++) {
                int oz = wz - ownershipData.originZ;
                if (oz < 0 || oz >= ownershipData.height) continue;
                int districtId = ownershipData.owner[ox][oz];
                if (!isDistrictInCode(mapping, districtId, code)) continue;

                double h = sampleRaw(heightValues, scanData, wx, wz);
                int rgb = classifyHeightColor(h, min, max, span);
                int px0 = context.worldToPxX(wx);
                int px1 = context.worldToPxX(wx + 1);
                int pz0 = context.worldToPxZ(wz);
                int pz1 = context.worldToPxZ(wz + 1);
                int w = Math.max(1, px1 - px0);
                int hpx = Math.max(1, pz1 - pz0);
                fillRect(image, px0, pz0, w, hpx, rgb);
            }
        }
        return image;
    }

    private static int classifyHeightColor(double h, double min, double max, double span) {
        if (h < SEA_LEVEL) {
            double depth = Math.max(0.0, Math.min(1.0, (SEA_LEVEL - h) / 20.0));
            return blendColor(0xFF4F81BD, 0xFF1F4E79, depth);
        }
        double n = (h - min) / span;
        n = Math.max(0.0, Math.min(1.0, n));
        // Compress local gradient so adjacent areas are easier to read.
        n = 0.5 + (n - 0.5) * 0.60;
        n = Math.max(0.0, Math.min(1.0, n));
        if (n < 0.35) return lerpByT(0xFFA7D08C, 0xFF70AD47, n / 0.35);
        if (n < 0.65) return lerpByT(0xFF70AD47, 0xFFC9B458, (n - 0.35) / 0.30);
        if (n < 0.85) return lerpByT(0xFFC9B458, 0xFF8B5A2B, (n - 0.65) / 0.20);
        return lerpByT(0xFF8B5A2B, 0xFFD9D9D9, (n - 0.85) / 0.15);
    }

    private static int lerpByT(int a, int b, double t) {
        return blendColor(a, b, Math.max(0.0, Math.min(1.0, t)));
    }

    private static int blendColor(int a, int b, double t) {
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int r = (int) Math.round(ar + (br - ar) * t);
        int g = (int) Math.round(ag + (bg - ag) * t);
        int bl = (int) Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static void fillRect(BufferedImage image, int x, int y, int w, int h, int rgb) {
        int minX = Math.max(0, x);
        int minY = Math.max(0, y);
        int maxX = Math.min(PREVIEW_SIZE, x + w);
        int maxY = Math.min(PREVIEW_SIZE, y + h);
        for (int px = minX; px < maxX; px++) {
            for (int py = minY; py < maxY; py++) {
                image.setRGB(px, py, rgb);
            }
        }
    }

    private static void drawGridAndLabel(BufferedImage image, String groupId, BBox bbox, RenderContext context) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(220, 40, 40, 165));

        int startX = floorToMultiple(bbox.minX, GRID_STEP_BLOCKS);
        int startZ = floorToMultiple(bbox.minZ, GRID_STEP_BLOCKS);
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        int lastXLabelPx = Integer.MIN_VALUE / 2;
        int lastZLabelPx = Integer.MIN_VALUE / 2;
        for (int wx = startX; wx <= bbox.maxX; wx += GRID_STEP_BLOCKS) {
            int px = context.worldToPxX(wx);
            g.drawLine(px, context.top(), px, context.bottom());
            if (px - lastXLabelPx >= 26) {
                drawCoordLabel(g, Integer.toString(wx), px + 2, context.top() + 12);
                lastXLabelPx = px;
            }
        }
        for (int wz = startZ; wz <= bbox.maxZ; wz += GRID_STEP_BLOCKS) {
            int pz = context.worldToPxZ(wz);
            g.drawLine(context.left(), pz, context.right(), pz);
            if (pz - lastZLabelPx >= 18) {
                drawCoordLabel(g, Integer.toString(wz), context.left() + 2, pz - 2);
                lastZLabelPx = pz;
            }
        }

        String text = groupId == null || groupId.isBlank() ? "group_unknown" : groupId;
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text);
        int h = fm.getHeight();
        int boxX = 12;
        int boxY = 12;
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRoundRect(boxX, boxY, w + 16, h + 8, 8, 8);
        g.setColor(new Color(255, 255, 255, 230));
        g.drawString(text, boxX + 8, boxY + h - 2);
        g.dispose();
    }

    private static void drawCoordLabel(Graphics2D g, String text, int x, int yBaseline) {
        if (text == null || text.isBlank()) return;
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text);
        int h = fm.getHeight();
        int boxX = x - 1;
        int boxY = yBaseline - fm.getAscent();
        g.setColor(new Color(0, 0, 0, 140));
        g.fillRoundRect(boxX, boxY, w + 3, h, 4, 4);
        g.setColor(new Color(255, 110, 110, 245));
        g.drawString(text, x, yBaseline);
    }

    private static int floorToMultiple(int value, int step) {
        int mod = Math.floorMod(value, step);
        return value - mod;
    }

    private static boolean isDistrictInCode(GroupCodeMapping mapping, int districtId, int code) {
        return districtId >= 0
                && districtId < mapping.districtToCode.length
                && mapping.districtToCode[districtId] == code;
    }

    private static double sampleRaw(double[][] values, CityC2ScanBinaryIO.C2ScanData scanData, int worldX, int worldZ) {
        int sx = toScanIndex(worldX, scanData.originX, scanData.step, values.length);
        int sz = toScanIndex(worldZ, scanData.originZ, scanData.step, values[0].length);
        return values[sx][sz];
    }

    private static int toScanIndex(int worldCoord, int origin, int step, int length) {
        if (length <= 1) return 0;
        double local = (worldCoord - origin) / (double) Math.max(1, step);
        int idx = (int) Math.round(local);
        return Math.max(0, Math.min(length - 1, idx));
    }

    private static double[][] extractHeight(ScanPixel[][] map) {
        int srcW = map.length;
        int srcH = map[0].length;
        double[][] out = new double[srcW][srcH];
        for (int x = 0; x < srcW; x++) {
            for (int z = 0; z < srcH; z++) {
                ScanPixel p = map[x][z];
                out[x][z] = p != null ? p.height() : 0.0;
            }
        }
        return out;
    }

    private static double[][] buildHillshade(double[][] elevation) {
        int w = elevation.length;
        int h = elevation[0].length;
        double[][] out = new double[w][h];
        double azimuth = Math.toRadians(HILLSHADE_AZIMUTH_DEG);
        double zenith = Math.toRadians(90.0 - HILLSHADE_ALTITUDE_DEG);
        for (int x = 0; x < w; x++) {
            int xm = Math.max(0, x - 1);
            int xp = Math.min(w - 1, x + 1);
            for (int y = 0; y < h; y++) {
                int ym = Math.max(0, y - 1);
                int yp = Math.min(h - 1, y + 1);
                double dzdx = (elevation[xp][y] - elevation[xm][y]) * 0.5;
                double dzdy = (elevation[x][yp] - elevation[x][ym]) * 0.5;
                double slope = Math.atan(Math.sqrt(dzdx * dzdx + dzdy * dzdy));
                double aspect = Math.atan2(dzdy, -dzdx);
                if (aspect < 0) aspect += Math.PI * 2.0;
                double shade = Math.cos(zenith) * Math.cos(slope)
                        + Math.sin(zenith) * Math.sin(slope) * Math.cos(azimuth - aspect);
                out[x][y] = Math.max(0.0, Math.min(1.0, shade));
            }
        }
        return out;
    }
    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static final class GroupCodeMapping {
        int[] districtToCode;
        Map<Integer, String> codeToGroupId = new HashMap<>();
    }

    private static final class BBox {
        final int minX;
        final int minZ;
        final int maxX;
        final int maxZ;

        BBox(int minX, int minZ, int maxX, int maxZ) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
        }

        int width() {
            return Math.max(1, maxX - minX + 1);
        }

        int height() {
            return Math.max(1, maxZ - minZ + 1);
        }
    }

    private static final class RenderContext {
        final BBox bbox;
        final double scale;
        final int offsetX;
        final int offsetY;

        private RenderContext(BBox bbox, double scale, int offsetX, int offsetY) {
            this.bbox = bbox;
            this.scale = scale;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }

        static RenderContext fromBBox(BBox bbox) {
            double innerW = Math.max(1, PREVIEW_SIZE - PREVIEW_PADDING * 2);
            double innerH = Math.max(1, PREVIEW_SIZE - PREVIEW_PADDING * 2);
            double scaleX = innerW / Math.max(1.0, bbox.width());
            double scaleY = innerH / Math.max(1.0, bbox.height());
            double scale = Math.max(1e-6, Math.min(scaleX, scaleY));
            int drawW = (int) Math.round(bbox.width() * scale);
            int drawH = (int) Math.round(bbox.height() * scale);
            int offsetX = (PREVIEW_SIZE - drawW) / 2;
            int offsetY = (PREVIEW_SIZE - drawH) / 2;
            return new RenderContext(bbox, scale, offsetX, offsetY);
        }

        int worldToPxX(int worldX) {
            double local = (worldX - bbox.minX) * scale;
            return clamp((int) Math.round(offsetX + local), 0, PREVIEW_SIZE - 1);
        }

        int worldToPxZ(int worldZ) {
            double local = (worldZ - bbox.minZ) * scale;
            return clamp((int) Math.round(offsetY + local), 0, PREVIEW_SIZE - 1);
        }

        int left() {
            return clamp(offsetX, 0, PREVIEW_SIZE - 1);
        }

        int right() {
            return clamp((int) Math.round(offsetX + bbox.width() * scale), 0, PREVIEW_SIZE - 1);
        }

        int top() {
            return clamp(offsetY, 0, PREVIEW_SIZE - 1);
        }

        int bottom() {
            return clamp((int) Math.round(offsetY + bbox.height() * scale), 0, PREVIEW_SIZE - 1);
        }

        private static int clamp(int v, int min, int max) {
            if (v < min) return min;
            return Math.min(max, v);
        }
    }
}
