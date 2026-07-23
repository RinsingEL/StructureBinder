package com.user.terra_script.domain.world.stage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.client.data.ScanResultHolder.RegionCache;
import com.user.terra_script.domain.world.scan.ScanPixel;
import com.user.terra_script.domain.world.scan.ScanRegion;
import com.user.terra_script.util.PreviewOverlayUtil;
import com.user.terra_script.util.TerrainFeatureComputer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class T1PreviewExporter {
    public static final int PREVIEW_SIZE = 512;

    private static final int SEA_LEVEL = 63;
    private static final double HILLSHADE_AZIMUTH_DEG = 315.0;
    private static final double HILLSHADE_ALTITUDE_DEG = 45.0;
    private static final int CONTOUR_INTERVAL = 20;
    private static final double CONTOUR_THRESHOLD = 1.2;

    private static final String IMG_HEIGHT = "T1_preview_height.png";
    private static final String IMG_HILLSHADE = "T1_preview_hillshade.png";
    private static final String IMG_SLOPE = "T1_preview_slope.png";
    private static final String IMG_BIOME = "T1_preview_biome.png";

    private T1PreviewExporter() {}

    public static JsonObject export(MinecraftServer server, ScanResultHolder holder, int regionId) throws Exception {
        JsonObject result = new JsonObject();
        if (server == null) {
            result.addProperty("generated", false);
            result.addProperty("reason", "server_missing");
            return result;
        }

        ScanPixel[][] map = resolveRegionMap(holder, regionId);
        if (map == null || map.length == 0 || map[0] == null || map[0].length == 0) {
            result.addProperty("generated", false);
            result.addProperty("reason", "region_map_missing");
            result.addProperty("region_id", regionId);
            return result;
        }

        int step = resolveRegionStep(holder, regionId);
        double[][] slopeRaw = TerrainFeatureComputer.computeSlope(map, step, false);
        double[][] slopeDegRaw = toSlopeDegrees(slopeRaw);

        double[][] heightDs = downsampleAverage(map, ValueField.HEIGHT);
        double[][] slopeDs = downsampleAverage(slopeDegRaw);
        BiomeClass[][] biomeDs = downsampleBiomeClass(map);
        double[][] hillshadeDs = buildHillshade(heightDs);

        BufferedImage heightImage = renderHeightWithContour(heightDs);
        BufferedImage hillshadeImage = renderHillshade(hillshadeDs);
        BufferedImage slopeImage = renderClassified(
                slopeDs,
                new double[]{5, 15, 30, 45},
                new int[]{0xFF4CAF50, 0xFFF4D35E, 0xFFF08A4B, 0xFFD1495B, 0xFF6A1B9A}
        );
        BufferedImage biomeImage = renderBiome(biomeDs);

        RegionCache gridCache = holder != null ? holder.regionCacheMap.get(regionId) : null;
        PreviewOverlayUtil.GridSpec gridSpec = new PreviewOverlayUtil.GridSpec();
        gridSpec.previewSize = PREVIEW_SIZE;
        gridSpec.originX = gridCache != null ? gridCache.minX : 0;
        gridSpec.originZ = gridCache != null ? gridCache.minZ : 0;
        gridSpec.widthBlocks = gridCache != null ? gridCache.w : (map.length * Math.max(1, step));
        gridSpec.heightBlocks = gridCache != null ? gridCache.h : (map[0].length * Math.max(1, step));
        gridSpec.sampleStepBlocks = Math.max(1, step);
        gridSpec.legendText = PreviewOverlayUtil.defaultLegendText(gridSpec);
        PreviewOverlayUtil.applyGridOverlay(heightImage, gridSpec);
        PreviewOverlayUtil.applyGridOverlay(hillshadeImage, gridSpec);
        PreviewOverlayUtil.applyGridOverlay(slopeImage, gridSpec);
        PreviewOverlayUtil.applyGridOverlay(biomeImage, gridSpec);

        JsonObject legends = new JsonObject();
        legends.add(IMG_HEIGHT, buildHeightLegend());
        legends.add(IMG_HILLSHADE, buildHillshadeLegend());
        legends.add(IMG_SLOPE, buildSlopeLegend());
        legends.add(IMG_BIOME, buildBiomeLegend());
        JsonObject gridMeta = PreviewOverlayUtil.buildGridMetadata(gridSpec);
        legends.getAsJsonObject(IMG_HEIGHT).add("grid", gridMeta.deepCopy());
        legends.getAsJsonObject(IMG_HILLSHADE).add("grid", gridMeta.deepCopy());
        legends.getAsJsonObject(IMG_SLOPE).add("grid", gridMeta.deepCopy());
        legends.getAsJsonObject(IMG_BIOME).add("grid", gridMeta.deepCopy());

        Path dir = server.getWorldPath(LevelResource.ROOT)
                .resolve("terra_script")
                .resolve("territories")
                .resolve("regions")
                .resolve("region_" + regionId);
        writePreviewSet(dir, heightImage, hillshadeImage, slopeImage, biomeImage, legends);

        result.addProperty("generated", true);
        result.addProperty("step", "T1");
        result.addProperty("region_id", regionId);
        result.addProperty("size", PREVIEW_SIZE);
        String relPrefix = "territories/regions/region_" + regionId + "/";
        result.addProperty("height", relPrefix + IMG_HEIGHT);
        result.addProperty("hillshade", relPrefix + IMG_HILLSHADE);
        result.addProperty("slope", relPrefix + IMG_SLOPE);
        result.addProperty("biome", relPrefix + IMG_BIOME);

        JsonObject legendPaths = new JsonObject();
        legendPaths.addProperty("height", relPrefix + toLegendFile(IMG_HEIGHT));
        legendPaths.addProperty("hillshade", relPrefix + toLegendFile(IMG_HILLSHADE));
        legendPaths.addProperty("slope", relPrefix + toLegendFile(IMG_SLOPE));
        legendPaths.addProperty("biome", relPrefix + toLegendFile(IMG_BIOME));
        result.add("legends", legendPaths);
        return result;
    }

    private static ScanPixel[][] resolveRegionMap(ScanResultHolder holder, int regionId) {
        if (holder == null || regionId <= 0) return null;
        RegionCache cache = holder.regionCacheMap.get(regionId);
        if (cache != null && cache.detailData != null && cache.detailData.length > 0 && cache.detailData[0] != null) {
            return cache.detailData;
        }

        ScanRegion region = null;
        if (holder.lastClusters != null) {
            region = holder.lastClusters.stream().filter(r -> r != null && r.id == regionId).findFirst().orElse(null);
        }
        if (region == null && holder.lastOceanRegions != null) {
            region = holder.lastOceanRegions.stream().filter(r -> r != null && r.id == regionId).findFirst().orElse(null);
        }
        if (region == null || holder.lastScanData == null || holder.lastScanData.length == 0 || holder.lastScanData[0] == null) {
            return null;
        }

        int step = Math.max(1, holder.scanStep);
        int radiusBlocks = holder.scanRadiusChunks * 16;
        int worldMinX = -radiusBlocks;
        int worldMinZ = -radiusBlocks;
        ScanPixel[][] global = holder.lastScanData;

        int gx0 = Math.max(0, (region.minX - worldMinX) / step);
        int gz0 = Math.max(0, (region.minZ - worldMinZ) / step);
        int gx1 = Math.min(global.length - 1, (region.maxX - worldMinX) / step);
        int gz1 = Math.min(global[0].length - 1, (region.maxZ - worldMinZ) / step);
        if (gx0 > gx1 || gz0 > gz1) return null;

        int w = gx1 - gx0 + 1;
        int h = gz1 - gz0 + 1;
        ScanPixel[][] out = new ScanPixel[w][h];
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
                out[x][z] = global[gx0 + x][gz0 + z];
            }
        }
        return out;
    }

    private static int resolveRegionStep(ScanResultHolder holder, int regionId) {
        if (holder == null) return 1;
        RegionCache cache = holder.regionCacheMap.get(regionId);
        if (cache != null && cache.step > 0) return cache.step;
        return Math.max(1, holder.scanStep);
    }

    private static BufferedImage renderHeightWithContour(double[][] heightDs) {
        BufferedImage image = new BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < PREVIEW_SIZE; x++) {
            for (int y = 0; y < PREVIEW_SIZE; y++) {
                double h = heightDs[x][y];
                int color = classifyHeightColor(h);
                double contourDelta = Math.abs(h - Math.round(h / CONTOUR_INTERVAL) * CONTOUR_INTERVAL);
                if (contourDelta <= CONTOUR_THRESHOLD) color = blend(color, 0xFF2A2A2A, 0.55);
                image.setRGB(x, y, color);
            }
        }
        return image;
    }

    private static BufferedImage renderHillshade(double[][] hillshadeDs) {
        BufferedImage image = new BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < PREVIEW_SIZE; x++) {
            for (int y = 0; y < PREVIEW_SIZE; y++) {
                int v = (int) Math.max(0, Math.min(255, Math.round(hillshadeDs[x][y] * 255.0)));
                int argb = 0xFF000000 | (v << 16) | (v << 8) | v;
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    private static BufferedImage renderClassified(double[][] values, double[] bins, int[] colors) {
        BufferedImage image = new BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < PREVIEW_SIZE; x++) {
            for (int y = 0; y < PREVIEW_SIZE; y++) {
                int idx = classify(values[x][y], bins);
                image.setRGB(x, y, colors[Math.max(0, Math.min(colors.length - 1, idx))]);
            }
        }
        return image;
    }

    private static BufferedImage renderBiome(BiomeClass[][] values) {
        BufferedImage image = new BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < PREVIEW_SIZE; x++) {
            for (int y = 0; y < PREVIEW_SIZE; y++) {
                image.setRGB(x, y, values[x][y].color);
            }
        }
        return image;
    }

    private static double[][] downsampleAverage(ScanPixel[][] map, ValueField field) {
        int srcW = map.length;
        int srcH = map[0].length;
        double[][] out = new double[PREVIEW_SIZE][PREVIEW_SIZE];
        for (int ox = 0; ox < PREVIEW_SIZE; ox++) {
            int x0 = mapStart(ox, srcW);
            int x1 = mapEnd(ox, srcW);
            for (int oy = 0; oy < PREVIEW_SIZE; oy++) {
                int y0 = mapStart(oy, srcH);
                int y1 = mapEnd(oy, srcH);
                double sum = 0.0;
                int count = 0;
                for (int x = x0; x < x1; x++) {
                    for (int y = y0; y < y1; y++) {
                        ScanPixel p = map[x][y];
                        if (p == null) continue;
                        sum += field == ValueField.HEIGHT ? p.height() : p.temperature();
                        count++;
                    }
                }
                out[ox][oy] = count > 0 ? (sum / count) : 0.0;
            }
        }
        return out;
    }

    private static double[][] downsampleAverage(double[][] values) {
        int srcW = values.length;
        int srcH = values[0].length;
        double[][] out = new double[PREVIEW_SIZE][PREVIEW_SIZE];
        for (int ox = 0; ox < PREVIEW_SIZE; ox++) {
            int x0 = mapStart(ox, srcW);
            int x1 = mapEnd(ox, srcW);
            for (int oy = 0; oy < PREVIEW_SIZE; oy++) {
                int y0 = mapStart(oy, srcH);
                int y1 = mapEnd(oy, srcH);
                double sum = 0.0;
                int count = 0;
                for (int x = x0; x < x1; x++) {
                    for (int y = y0; y < y1; y++) {
                        double v = values[x][y];
                        if (Double.isNaN(v) || Double.isInfinite(v)) continue;
                        sum += v;
                        count++;
                    }
                }
                out[ox][oy] = count > 0 ? (sum / count) : 0.0;
            }
        }
        return out;
    }

    private static BiomeClass[][] downsampleBiomeClass(ScanPixel[][] map) {
        int srcW = map.length;
        int srcH = map[0].length;
        BiomeClass[][] out = new BiomeClass[PREVIEW_SIZE][PREVIEW_SIZE];
        for (int ox = 0; ox < PREVIEW_SIZE; ox++) {
            int x0 = mapStart(ox, srcW);
            int x1 = mapEnd(ox, srcW);
            for (int oy = 0; oy < PREVIEW_SIZE; oy++) {
                int y0 = mapStart(oy, srcH);
                int y1 = mapEnd(oy, srcH);
                int[] counts = new int[BiomeClass.values().length];
                for (int x = x0; x < x1; x++) {
                    for (int y = y0; y < y1; y++) {
                        ScanPixel p = map[x][y];
                        if (p == null) continue;
                        counts[toBiomeClass(p.biomeId()).ordinal()]++;
                    }
                }
                int best = 0;
                for (int i = 1; i < counts.length; i++) if (counts[i] > counts[best]) best = i;
                out[ox][oy] = BiomeClass.values()[best];
            }
        }
        return out;
    }

    private static double[][] toSlopeDegrees(double[][] raw) {
        int w = raw.length;
        int h = raw[0].length;
        double[][] out = new double[w][h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                out[x][y] = Math.toDegrees(Math.atan(Math.max(0.0, raw[x][y])));
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

    private static int classifyHeightColor(double h) {
        if (h < SEA_LEVEL) return 0xFF1F4E79;
        if (h < 70) return 0xFFA7D08C;
        if (h < 110) return 0xFF70AD47;
        if (h < 160) return 0xFFC9B458;
        if (h < 220) return 0xFF8B5A2B;
        return 0xFFD9D9D9;
    }

    private static int classify(double v, double[] bins) {
        for (int i = 0; i < bins.length; i++) if (v <= bins[i]) return i;
        return bins.length;
    }

    private static int blend(int a, int b, double weightB) {
        double wb = Math.max(0.0, Math.min(1.0, weightB));
        double wa = 1.0 - wb;
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int r = (int) Math.round(ar * wa + br * wb);
        int g = (int) Math.round(ag * wa + bg * wb);
        int bl = (int) Math.round(ab * wa + bb * wb);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static BiomeClass toBiomeClass(String biomeIdRaw) {
        String b = biomeIdRaw == null ? "unknown" : biomeIdRaw.toLowerCase(Locale.ROOT);
        if (b.contains("nether") || b.contains("end")) return BiomeClass.NETHER_END;
        if (b.contains("ocean") || b.contains("river")) return BiomeClass.OCEAN_RIVER;
        if (b.contains("desert") || b.contains("badlands")) return BiomeClass.DESERT_BADLANDS;
        if (b.contains("jungle") || b.contains("bamboo")) return BiomeClass.JUNGLE;
        if (b.contains("forest") || b.contains("taiga") || b.contains("grove")) return BiomeClass.FOREST;
        if (b.contains("mountain") || b.contains("peak") || b.contains("hills") || b.contains("windswept") || b.contains("stony")) return BiomeClass.MOUNTAIN;
        if (b.contains("snow") || b.contains("ice") || b.contains("frozen")) return BiomeClass.SNOW;
        if (b.contains("plains") || b.contains("meadow") || b.contains("savanna")) return BiomeClass.PLAINS_GRASSLAND;
        return BiomeClass.OTHER;
    }

    private static JsonObject buildHeightLegend() {
        JsonObject legend = baseLegend(IMG_HEIGHT, "height");
        JsonArray bins = new JsonArray();
        bins.add(bin("<sea_level", Double.NEGATIVE_INFINITY, SEA_LEVEL, "#1F4E79"));
        bins.add(bin("sea_level_to_70", SEA_LEVEL, 70, "#A7D08C"));
        bins.add(bin("70_to_110", 70, 110, "#70AD47"));
        bins.add(bin("110_to_160", 110, 160, "#C9B458"));
        bins.add(bin("160_to_220", 160, 220, "#8B5A2B"));
        bins.add(bin(">220", 220, Double.POSITIVE_INFINITY, "#D9D9D9"));
        legend.add("bins", bins);

        JsonObject style = new JsonObject();
        style.addProperty("discrete", true);
        style.addProperty("saturation_scale", 1.0);
        JsonObject hillshade = new JsonObject();
        hillshade.addProperty("azimuth_deg", HILLSHADE_AZIMUTH_DEG);
        style.add("hillshade", hillshade);
        JsonObject blend = new JsonObject();
        blend.addProperty("elevation_weight", 0.6);
        blend.addProperty("hillshade_weight", 0.4);
        style.add("blend", blend);
        JsonObject contour = new JsonObject();
        contour.addProperty("enabled", true);
        contour.addProperty("interval", CONTOUR_INTERVAL);
        style.add("contour", contour);
        legend.add("style", style);
        return legend;
    }

    private static JsonObject buildHillshadeLegend() {
        JsonObject legend = baseLegend(IMG_HILLSHADE, "hillshade");
        JsonArray bins = new JsonArray();
        bins.add(bin("dark", 0.0, 0.25, "#202020"));
        bins.add(bin("mid_dark", 0.25, 0.5, "#555555"));
        bins.add(bin("mid_light", 0.5, 0.75, "#999999"));
        bins.add(bin("bright", 0.75, 1.0, "#E0E0E0"));
        legend.add("bins", bins);

        JsonObject style = new JsonObject();
        style.addProperty("discrete", true);
        style.addProperty("saturation_scale", 0.0);
        JsonObject hillshade = new JsonObject();
        hillshade.addProperty("azimuth_deg", HILLSHADE_AZIMUTH_DEG);
        hillshade.addProperty("altitude_deg", HILLSHADE_ALTITUDE_DEG);
        style.add("hillshade", hillshade);
        style.add("contour", null);
        legend.add("style", style);
        return legend;
    }

    private static JsonObject buildSlopeLegend() {
        JsonObject legend = baseLegend(IMG_SLOPE, "slope");
        JsonArray bins = new JsonArray();
        bins.add(bin("0_to_5_deg", 0, 5, "#4CAF50"));
        bins.add(bin("5_to_15_deg", 5, 15, "#F4D35E"));
        bins.add(bin("15_to_30_deg", 15, 30, "#F08A4B"));
        bins.add(bin("30_to_45_deg", 30, 45, "#D1495B"));
        bins.add(bin(">45_deg", 45, Double.POSITIVE_INFINITY, "#6A1B9A"));
        legend.add("bins", bins);

        JsonObject style = new JsonObject();
        style.addProperty("discrete", true);
        style.addProperty("saturation_scale", 1.0);
        style.add("hillshade", null);
        style.add("contour", null);
        legend.add("style", style);
        return legend;
    }

    private static JsonObject buildBiomeLegend() {
        JsonObject legend = baseLegend(IMG_BIOME, "biome");
        JsonArray bins = new JsonArray();
        for (BiomeClass c : BiomeClass.values()) {
            bins.add(bin(c.label, Double.NaN, Double.NaN, toHex(c.color)));
        }
        legend.add("bins", bins);

        JsonObject style = new JsonObject();
        style.addProperty("discrete", true);
        style.addProperty("saturation_scale", 0.7);
        style.add("hillshade", null);
        style.add("contour", null);
        legend.add("style", style);
        return legend;
    }

    private static JsonObject baseLegend(String image, String type) {
        JsonObject legend = new JsonObject();
        legend.addProperty("image", image);
        legend.addProperty("type", type);
        JsonArray resolution = new JsonArray();
        resolution.add(PREVIEW_SIZE);
        resolution.add(PREVIEW_SIZE);
        legend.add("resolution", resolution);
        legend.addProperty("downsample", "area_average");
        return legend;
    }

    private static JsonObject bin(String label, double min, double max, String colorHex) {
        JsonObject obj = new JsonObject();
        obj.addProperty("label", label);
        JsonArray range = new JsonArray();
        if (Double.isFinite(min)) range.add(min); else range.add("neg_inf");
        if (Double.isFinite(max)) range.add(max); else range.add("pos_inf");
        obj.add("range", range);
        if (colorHex != null) obj.addProperty("color", colorHex);
        return obj;
    }

    private static void writePreviewSet(
            Path dir,
            BufferedImage heightImage,
            BufferedImage hillshadeImage,
            BufferedImage slopeImage,
            BufferedImage biomeImage,
            JsonObject legends
    ) throws Exception {
        Files.createDirectories(dir);
        writeImage(dir.resolve(IMG_HEIGHT), heightImage);
        writeImage(dir.resolve(IMG_HILLSHADE), hillshadeImage);
        writeImage(dir.resolve(IMG_SLOPE), slopeImage);
        writeImage(dir.resolve(IMG_BIOME), biomeImage);

        writeLegend(dir.resolve(toLegendFile(IMG_HEIGHT)), legends.getAsJsonObject(IMG_HEIGHT));
        writeLegend(dir.resolve(toLegendFile(IMG_HILLSHADE)), legends.getAsJsonObject(IMG_HILLSHADE));
        writeLegend(dir.resolve(toLegendFile(IMG_SLOPE)), legends.getAsJsonObject(IMG_SLOPE));
        writeLegend(dir.resolve(toLegendFile(IMG_BIOME)), legends.getAsJsonObject(IMG_BIOME));
    }

    private static void writeLegend(Path path, JsonObject legend) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, legend.toString(), StandardCharsets.UTF_8);
    }

    private static void writeImage(Path path, BufferedImage image) throws Exception {
        Files.createDirectories(path.getParent());
        ImageIO.write(image, "png", path.toFile());
    }

    private static String toLegendFile(String image) {
        int dot = image.lastIndexOf('.');
        return dot > 0 ? image.substring(0, dot) + ".legend.json" : image + ".legend.json";
    }

    private static int mapStart(int out, int srcSize) {
        if (srcSize <= PREVIEW_SIZE) return Math.max(0, Math.min(srcSize - 1, out * srcSize / PREVIEW_SIZE));
        return (int) Math.floor(out * (srcSize / (double) PREVIEW_SIZE));
    }

    private static int mapEnd(int out, int srcSize) {
        if (srcSize <= PREVIEW_SIZE) return Math.min(srcSize, mapStart(out, srcSize) + 1);
        int end = (int) Math.floor((out + 1) * (srcSize / (double) PREVIEW_SIZE));
        return Math.max(mapStart(out, srcSize) + 1, Math.min(srcSize, end));
    }

    private static String toHex(int argb) {
        return String.format("#%06X", (argb & 0x00FFFFFF));
    }

    private enum ValueField { HEIGHT, TEMPERATURE }

    private enum BiomeClass {
        OCEAN_RIVER("ocean_river", desaturate(0xFF3B82F6, 0.7)),
        DESERT_BADLANDS("desert_badlands", desaturate(0xFFE9A03B, 0.7)),
        PLAINS_GRASSLAND("plains_grassland", desaturate(0xFF7FBF7F, 0.7)),
        FOREST("forest", desaturate(0xFF2E7D32, 0.7)),
        JUNGLE("jungle", desaturate(0xFF20A76B, 0.7)),
        MOUNTAIN("mountain", desaturate(0xFF8B8B8B, 0.7)),
        SNOW("snow", desaturate(0xFFF2F4F8, 0.7)),
        NETHER_END("nether_end", desaturate(0xFF3A2A52, 0.7)),
        OTHER("other", desaturate(0xFF8AA4B1, 0.7));

        final String label;
        final int color;

        BiomeClass(String label, int color) {
            this.label = label;
            this.color = color;
        }

        private static int desaturate(int argb, double saturationScale) {
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            float[] hsb = Color.RGBtoHSB(r, g, b, null);
            float sat = (float) Math.max(0.0, Math.min(1.0, hsb[1] * saturationScale));
            int rgb = Color.HSBtoRGB(hsb[0], sat, hsb[2]);
            return 0xFF000000 | (rgb & 0x00FFFFFF);
        }
    }
}

