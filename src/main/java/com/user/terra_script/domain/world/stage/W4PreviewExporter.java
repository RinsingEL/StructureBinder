package com.user.terra_script.domain.world.stage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.core.artifact.ArtifactKey;
import com.user.terra_script.core.stage.StageContext;
import com.user.terra_script.domain.world.scan.ScanPixel;
import com.user.terra_script.util.PreviewOverlayUtil;
import com.user.terra_script.util.TerrainFeatureComputer;
import net.minecraft.world.level.storage.LevelResource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class W4PreviewExporter {
    public static final int PREVIEW_SIZE = 512;

    private static final int SEA_LEVEL = 63;

    private static final String IMG_HEIGHT = "W4_preview_height.png";
    private static final String IMG_SLOPE = "W4_preview_slope.png";
    private static final String IMG_ROUGHNESS = "W4_preview_roughness.png";
    private static final String IMG_TEMPERATURE = "W4_preview_temperature.png";
    private static final String IMG_BIOME = "W4_preview_biome.png";

    private W4PreviewExporter() {}

    public static JsonObject export(StageContext ctx, ScanResultHolder holder) throws Exception {
        JsonObject result = new JsonObject();
        ScanPixel[][] map = holder.lastScanData;
        if (map == null || map.length == 0 || map[0] == null || map[0].length == 0) {
            result.addProperty("generated", false);
            result.addProperty("reason", "lastScanData_missing");
            return result;
        }

        int scanStep = Math.max(1, holder.scanStep);
        double[][] slopeRaw = TerrainFeatureComputer.computeSlope(map, scanStep, false);
        double[][] roughRaw = TerrainFeatureComputer.computeRoughness(map, scanStep, 2, false);
        double[][] slopeDegRaw = toSlopeDegrees(slopeRaw);

        double[][] heightDs = downsampleAverage(map, ValueField.HEIGHT);
        double[][] tempDs = downsampleAverage(map, ValueField.TEMPERATURE);
        double[][] slopeDs = downsampleAverage(slopeDegRaw);
        double[][] roughDs = downsampleAverage(roughRaw);
        BiomeClass[][] biomeDs = downsampleBiomeClass(map);

        PercentileRange slopeStretch = percentileRange(slopeDs, 5, 95);
        PercentileRange roughStretch = percentileRange(roughDs, 5, 95);
        PercentileRange tempStretch = percentileRange(tempDs, 5, 95);

        BufferedImage heightImage = renderHeight(heightDs);
        BufferedImage slopeImage = renderSlope(slopeDs);
        BufferedImage roughImage = renderClassified(
                roughDs,
                makeEqualBins(roughStretch.min, roughStretch.max, 5),
                new int[]{0xFF34516B, 0xFF4B7696, 0xFF679FC4, 0xFF8EC7DF, 0xFFD3ECF7}
        );
        BufferedImage tempImage = renderClassified(
                tempDs,
                makeEqualBins(tempStretch.min, tempStretch.max, 6),
                new int[]{0xFF3B4CC0, 0xFF6A9FD8, 0xFF9EDAE5, 0xFFF2E394, 0xFFF4A259, 0xFFD1495B}
        );
        BufferedImage biomeImage = renderBiome(biomeDs);

        PreviewOverlayUtil.GridSpec gridSpec = new PreviewOverlayUtil.GridSpec();
        gridSpec.previewSize = PREVIEW_SIZE;
        gridSpec.originX = -(holder.scanRadiusChunks * 16);
        gridSpec.originZ = -(holder.scanRadiusChunks * 16);
        gridSpec.widthBlocks = holder.lastScanData.length * Math.max(1, holder.scanStep);
        gridSpec.heightBlocks = holder.lastScanData[0].length * Math.max(1, holder.scanStep);
        gridSpec.sampleStepBlocks = Math.max(1, holder.scanStep);
        gridSpec.legendText = PreviewOverlayUtil.defaultLegendText(gridSpec);
        PreviewOverlayUtil.applyGridOverlay(heightImage, gridSpec);
        PreviewOverlayUtil.applyGridOverlay(slopeImage, gridSpec);
        PreviewOverlayUtil.applyGridOverlay(roughImage, gridSpec);
        PreviewOverlayUtil.applyGridOverlay(tempImage, gridSpec);
        PreviewOverlayUtil.applyGridOverlay(biomeImage, gridSpec);

        JsonObject legends = new JsonObject();
        legends.add(IMG_HEIGHT, buildHeightLegend());
        legends.add(IMG_SLOPE, buildSlopeLegend(slopeStretch));
        legends.add(IMG_ROUGHNESS, buildContinuousLegend("roughness", IMG_ROUGHNESS, roughStretch, 5));
        legends.add(IMG_TEMPERATURE, buildContinuousLegend("temperature", IMG_TEMPERATURE, tempStretch, 6));
        legends.add(IMG_BIOME, buildBiomeLegend());
        JsonObject gridMeta = PreviewOverlayUtil.buildGridMetadata(gridSpec);
        legends.getAsJsonObject(IMG_HEIGHT).add("grid", gridMeta.deepCopy());
        legends.getAsJsonObject(IMG_SLOPE).add("grid", gridMeta.deepCopy());
        legends.getAsJsonObject(IMG_ROUGHNESS).add("grid", gridMeta.deepCopy());
        legends.getAsJsonObject(IMG_TEMPERATURE).add("grid", gridMeta.deepCopy());
        legends.getAsJsonObject(IMG_BIOME).add("grid", gridMeta.deepCopy());

        Path w4Dir = ctx.artifacts.resolve(ctx.server, ctx.worldId, ArtifactKey.W4_TERRAIN_FACTS_DAT).getParent();
        writePreviewSet(w4Dir, heightImage, slopeImage, roughImage, tempImage, biomeImage, legends);
        if (ctx.server != null) {
            Path legacyDir = ctx.server.getWorldPath(LevelResource.ROOT).resolve("terra_script").resolve("world");
            writePreviewSet(legacyDir, heightImage, slopeImage, roughImage, tempImage, biomeImage, legends);
        }

        result.addProperty("generated", true);
        result.addProperty("size", PREVIEW_SIZE);
        result.addProperty("height", "world/W4/" + IMG_HEIGHT);
        result.addProperty("slope", "world/W4/" + IMG_SLOPE);
        result.addProperty("roughness", "world/W4/" + IMG_ROUGHNESS);
        result.addProperty("temperature", "world/W4/" + IMG_TEMPERATURE);
        result.addProperty("biome", "world/W4/" + IMG_BIOME);

        JsonObject legendPaths = new JsonObject();
        legendPaths.addProperty("height", "world/W4/" + toLegendFile(IMG_HEIGHT));
        legendPaths.addProperty("slope", "world/W4/" + toLegendFile(IMG_SLOPE));
        legendPaths.addProperty("roughness", "world/W4/" + toLegendFile(IMG_ROUGHNESS));
        legendPaths.addProperty("temperature", "world/W4/" + toLegendFile(IMG_TEMPERATURE));
        legendPaths.addProperty("biome", "world/W4/" + toLegendFile(IMG_BIOME));
        result.add("legends", legendPaths);
        return result;
    }

    private static BufferedImage renderHeight(double[][] heightDs) {
        BufferedImage image = new BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < PREVIEW_SIZE; x++) {
            for (int y = 0; y < PREVIEW_SIZE; y++) {
                image.setRGB(x, y, classifyHeightColor(heightDs[x][y]));
            }
        }
        return image;
    }

    private static BufferedImage renderSlope(double[][] slopeDegDs) {
        return renderClassified(
                slopeDegDs,
                new double[]{5, 15, 30, 45},
                new int[]{0xFF4CAF50, 0xFFF4D35E, 0xFFF08A4B, 0xFFD1495B, 0xFF6A1B9A}
        );
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

    private static int classifyHeightColor(double h) {
        if (h < SEA_LEVEL) return 0xFF1F4E79;
        if (h < 70) return 0xFFA7D08C;
        if (h < 110) return 0xFF70AD47;
        if (h < 160) return 0xFFC9B458;
        if (h < 220) return 0xFF8B5A2B;
        return 0xFFD9D9D9;
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

    private static PercentileRange percentileRange(double[][] values, double pMin, double pMax) {
        List<Double> list = new ArrayList<>(values.length * values[0].length);
        for (double[] row : values) for (double v : row) if (Double.isFinite(v)) list.add(v);
        if (list.isEmpty()) return new PercentileRange(0.0, 1.0, pMin, pMax);
        list.sort(Comparator.naturalOrder());
        double min = percentile(list, pMin);
        double max = percentile(list, pMax);
        if (max <= min) max = min + 1e-6;
        return new PercentileRange(min, max, pMin, pMax);
    }

    private static double percentile(List<Double> sorted, double p) {
        double pos = (p / 100.0) * (sorted.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) return sorted.get(lo);
        double t = pos - lo;
        return sorted.get(lo) * (1.0 - t) + sorted.get(hi) * t;
    }

    private static double[] makeEqualBins(double min, double max, int classes) {
        int cuts = Math.max(1, classes - 1);
        double[] bins = new double[cuts];
        double step = (max - min) / classes;
        for (int i = 0; i < cuts; i++) bins[i] = min + step * (i + 1);
        return bins;
    }

    private static int classify(double v, double[] bins) {
        for (int i = 0; i < bins.length; i++) if (v <= bins[i]) return i;
        return bins.length;
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
        JsonObject legend = baseLegend(IMG_HEIGHT, "height", null);
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
        style.add("hillshade", null);
        style.add("contour", null);
        legend.add("style", style);
        return legend;
    }

    private static JsonObject buildSlopeLegend(PercentileRange stretch) {
        JsonObject legend = baseLegend(IMG_SLOPE, "slope", stretch);
        JsonArray bins = new JsonArray();
        bins.add(bin("0_to_5_deg", 0, 5, "#4CAF50"));
        bins.add(bin("5_to_15_deg", 5, 15, "#F4D35E"));
        bins.add(bin("15_to_30_deg", 15, 30, "#F08A4B"));
        bins.add(bin("30_to_45_deg", 30, 45, "#D1495B"));
        bins.add(bin(">45_deg", 45, Double.POSITIVE_INFINITY, "#6A1B9A"));
        legend.add("bins", bins);

        JsonObject style = new JsonObject();
        style.addProperty("discrete", true);
        style.addProperty("unit", "degree");
        style.addProperty("saturation_scale", 1.0);
        style.add("hillshade", null);
        style.add("contour", null);
        legend.add("style", style);
        return legend;
    }

    private static JsonObject buildContinuousLegend(String type, String image, PercentileRange stretch, int classes) {
        JsonObject legend = baseLegend(image, type, stretch);
        JsonArray bins = new JsonArray();
        double[] cuts = makeEqualBins(stretch.min, stretch.max, classes);
        double prev = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < cuts.length; i++) {
            bins.add(bin("class_" + (i + 1), prev, cuts[i], null));
            prev = cuts[i];
        }
        bins.add(bin("class_" + classes, prev, Double.POSITIVE_INFINITY, null));
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
        JsonObject legend = baseLegend(IMG_BIOME, "biome", null);
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

    private static JsonObject baseLegend(String image, String type, PercentileRange stretch) {
        JsonObject legend = new JsonObject();
        legend.addProperty("image", image);
        legend.addProperty("type", type);
        JsonArray resolution = new JsonArray();
        resolution.add(PREVIEW_SIZE);
        resolution.add(PREVIEW_SIZE);
        legend.add("resolution", resolution);
        legend.addProperty("downsample", "area_average");

        JsonObject stretchObj = new JsonObject();
        if (stretch != null) {
            stretchObj.addProperty("mode", "percentile");
            stretchObj.addProperty("p_min", stretch.pMin);
            stretchObj.addProperty("p_max", stretch.pMax);
            stretchObj.addProperty("value_min", stretch.min);
            stretchObj.addProperty("value_max", stretch.max);
        } else {
            stretchObj.addProperty("mode", "fixed");
        }
        legend.add("stretch", stretchObj);
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
            BufferedImage slopeImage,
            BufferedImage roughImage,
            BufferedImage tempImage,
            BufferedImage biomeImage,
            JsonObject legends
    ) throws Exception {
        Files.createDirectories(dir);
        writeImage(dir.resolve(IMG_HEIGHT), heightImage);
        writeImage(dir.resolve(IMG_SLOPE), slopeImage);
        writeImage(dir.resolve(IMG_ROUGHNESS), roughImage);
        writeImage(dir.resolve(IMG_TEMPERATURE), tempImage);
        writeImage(dir.resolve(IMG_BIOME), biomeImage);

        writeLegend(dir.resolve(toLegendFile(IMG_HEIGHT)), legends.getAsJsonObject(IMG_HEIGHT));
        writeLegend(dir.resolve(toLegendFile(IMG_SLOPE)), legends.getAsJsonObject(IMG_SLOPE));
        writeLegend(dir.resolve(toLegendFile(IMG_ROUGHNESS)), legends.getAsJsonObject(IMG_ROUGHNESS));
        writeLegend(dir.resolve(toLegendFile(IMG_TEMPERATURE)), legends.getAsJsonObject(IMG_TEMPERATURE));
        writeLegend(dir.resolve(toLegendFile(IMG_BIOME)), legends.getAsJsonObject(IMG_BIOME));
    }

    private static void writeLegend(Path path, JsonObject legend) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, legend.toString(), StandardCharsets.UTF_8);
    }

    private static String toLegendFile(String image) {
        int dot = image.lastIndexOf('.');
        return dot > 0 ? image.substring(0, dot) + ".legend.json" : image + ".legend.json";
    }

    private static void writeImage(Path path, BufferedImage image) throws Exception {
        Files.createDirectories(path.getParent());
        ImageIO.write(image, "png", path.toFile());
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

    private record PercentileRange(double min, double max, double pMin, double pMax) {}

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

