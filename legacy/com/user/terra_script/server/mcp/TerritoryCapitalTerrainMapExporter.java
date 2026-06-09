package com.user.terra_script.server.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.user.terra_script.territory.io.TerritoryResultRepository;
import com.user.terra_script.world.TerritoryManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class TerritoryCapitalTerrainMapExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int DEFAULT_IMAGE_SIZE = 512;
    private static final int DEFAULT_RADIUS_BLOCKS = 1024;

    private TerritoryCapitalTerrainMapExporter() {}

    public static JsonObject export(MinecraftServer server, JsonObject req) throws Exception {
        if (server == null) throw new IllegalArgumentException("server is null");
        String territoryId = stringValue(req, "territory_id", null);
        if (territoryId == null || territoryId.isBlank()) {
            throw new IllegalArgumentException("territory_id is required");
        }

        TerritoryManager.TerritoryConfig cfg = resolveConfig(territoryId);
        String artifactTerritoryId = cfg != null && cfg.id != null && !cfg.id.isBlank() ? cfg.id : territoryId;
        int requestedCenterX = intValue(req, "center_x", cfg != null ? cfg.capitalX : 0);
        int requestedCenterZ = intValue(req, "center_z", cfg != null ? cfg.capitalZ : 0);
        int radiusBlocks = clamp(intValue(req, "radius_blocks", DEFAULT_RADIUS_BLOCKS), 64, 4096);
        int imageSize = clamp(intValue(req, "image_size", DEFAULT_IMAGE_SIZE), 128, 2048);
        boolean explicitCenter = req != null && req.has("center_x") && req.has("center_z");

        byte[] dat = TerritoryResultRepository.readT4Dat(server, artifactTerritoryId)
                .orElseThrow(() -> new FileNotFoundException("T4 dat not found for territory_id: " + artifactTerritoryId));
        TerritoryController.DecodedT4 decoded = TerritoryController.decodeT4Dat(dat);
        TerritoryController.WindowSelection selection = TerritoryController.selectWindow(
                decoded.records,
                requestedCenterX,
                requestedCenterZ,
                radiusBlocks,
                !explicitCenter
        );
        if (selection.records.isEmpty()) {
            throw new IllegalArgumentException("No T4 cells matched the requested capital terrain window.");
        }

        Set<Long> ownedChunks = ownedChunks(server, artifactTerritoryId);
        BufferedImage image = render(selection, decoded.step, ownedChunks, imageSize, radiusBlocks);

        Path dir = outputDir(server, artifactTerritoryId);
        Files.createDirectories(dir);
        Path imagePath = dir.resolve("T4_capital_terrain_map.png");
        Path legendPath = dir.resolve("T4_capital_terrain_map.legend.json");
        ImageIO.write(image, "png", imagePath.toFile());

        JsonObject legend = legend(territoryId, artifactTerritoryId, cfg, decoded, selection, imagePath, legendPath, imageSize, radiusBlocks);
        Files.writeString(legendPath, GSON.toJson(legend), StandardCharsets.UTF_8);

        JsonObject out = new JsonObject();
        out.addProperty("status", "exported");
        out.addProperty("step", "T4_CAPITAL_TERRAIN_MAP");
        out.addProperty("territory_id", artifactTerritoryId);
        if (!artifactTerritoryId.equals(territoryId)) {
            out.addProperty("requested_territory_id", territoryId);
        }
        out.addProperty("source", "T4_dat");
        out.add("window", legend.getAsJsonObject("window"));
        out.add("artifacts", legend.getAsJsonObject("artifacts"));
        if (selection.reanchored) {
            com.google.gson.JsonArray warnings = new com.google.gson.JsonArray();
            warnings.add("requested_center_outside_t4_coverage");
            warnings.add("center_reanchored_to_nearest_t4_cell");
            out.add("warnings", warnings);
        }
        return out;
    }

    static BufferedImage render(
            TerritoryController.WindowSelection selection,
            int sampleStep,
            Set<Long> ownedChunks,
            int imageSize,
            int radiusBlocks
    ) {
        BufferedImage image = new BufferedImage(imageSize, imageSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

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
            if (px < -cellPixels || pz < -cellPixels || px >= imageSize + cellPixels || pz >= imageSize + cellPixels) {
                continue;
            }
            Color color = terrainColor(r, minHeight, heightRange);
            if (ownedChunks != null && !ownedChunks.isEmpty() && !ownedChunks.contains(ChunkPos.asLong(r.x >> 4, r.z >> 4))) {
                color = blend(color, new Color(34, 34, 34), 0.55);
            }
            g.setColor(color);
            g.fillRect(px, pz, cellPixels, cellPixels);
        }

        drawGrid(g, imageSize);
        drawCenter(g, imageSize);
        drawFrame(g, imageSize, selection, radiusBlocks);
        g.dispose();
        return image;
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

    private static JsonObject legend(
            String requestedTerritoryId,
            String artifactTerritoryId,
            TerritoryManager.TerritoryConfig cfg,
            TerritoryController.DecodedT4 decoded,
            TerritoryController.WindowSelection selection,
            Path imagePath,
            Path legendPath,
            int imageSize,
            int radiusBlocks
    ) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "territory_capital_terrain_map");
        root.addProperty("territory_id", artifactTerritoryId);
        if (!artifactTerritoryId.equals(requestedTerritoryId)) {
            root.addProperty("requested_territory_id", requestedTerritoryId);
        }
        if (cfg != null) {
            JsonObject territory = new JsonObject();
            territory.addProperty("id", cfg.id);
            territory.addProperty("territory_id", cfg.territoryId);
            territory.addProperty("name", cfg.name);
            territory.addProperty("continent_id", cfg.selectedContinentId);
            territory.addProperty("capital_x", cfg.capitalX);
            territory.addProperty("capital_z", cfg.capitalZ);
            root.add("territory", territory);
        }
        JsonObject window = new JsonObject();
        window.addProperty("center_x", selection.centerX);
        window.addProperty("center_z", selection.centerZ);
        window.addProperty("requested_center_x", selection.requestedCenterX);
        window.addProperty("requested_center_z", selection.requestedCenterZ);
        window.addProperty("radius_blocks", radiusBlocks);
        window.addProperty("image_size", imageSize);
        window.addProperty("sample_step", decoded.step);
        window.addProperty("matched_cells", selection.records.size());
        window.addProperty("reanchored", selection.reanchored);
        if (selection.reanchored) {
            window.addProperty("nearest_distance_blocks", round3(selection.nearestDistanceBlocks));
        }
        root.add("window", window);

        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("terrain_map", normalizePath(imagePath));
        artifacts.addProperty("legend", normalizePath(legendPath));
        root.add("artifacts", artifacts);

        JsonObject style = new JsonObject();
        style.addProperty("water", "height<=62 blue");
        style.addProperty("land", "height green-brown ramp");
        style.addProperty("slope", "steeper cells shift warmer/darker");
        style.addProperty("outside_territory", "darkened overlay when T3 chunks are available");
        root.add("style", style);
        return root;
    }

    private static TerritoryManager.TerritoryConfig resolveConfig(String territoryId) {
        TerritoryManager.ensureLoaded();
        TerritoryManager.TerritoryConfig cfg = TerritoryManager.getTerritoryConfig(territoryId);
        if (cfg != null) return cfg;
        for (TerritoryManager.TerritoryConfig candidate : TerritoryManager.getRegisteredFactions()) {
            if (candidate == null) continue;
            if (territoryId.equals(candidate.territoryId)) return candidate;
        }
        return null;
    }

    private static Set<Long> ownedChunks(MinecraftServer server, String territoryId) {
        Optional<TerritoryResultRepository.T3DatData> dat = TerritoryResultRepository.readT3Dat(server, territoryId);
        if (dat.isEmpty()) return Set.of();
        Set<Long> out = new HashSet<>();
        if (dat.get().claimedChunks != null) out.addAll(dat.get().claimedChunks);
        if (dat.get().wildChunks != null) out.addAll(dat.get().wildChunks);
        return out;
    }

    private static Path outputDir(MinecraftServer server, String territoryId) {
        String worldId = String.valueOf(server.overworld().getSeed());
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("terra_script")
                .resolve(worldId)
                .resolve("territory")
                .resolve(safeTerritoryId(territoryId))
                .resolve("T4");
    }

    private static void drawGrid(Graphics2D g, int imageSize) {
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(255, 255, 255, 38));
        for (int i = 0; i <= imageSize; i += Math.max(32, imageSize / 8)) {
            g.drawLine(i, 0, i, imageSize);
            g.drawLine(0, i, imageSize, i);
        }
    }

    private static void drawCenter(Graphics2D g, int imageSize) {
        int c = imageSize / 2;
        int arm = Math.max(8, imageSize / 42);
        g.setStroke(new BasicStroke(Math.max(2f, imageSize / 220f)));
        g.setColor(new Color(255, 230, 70, 235));
        g.drawLine(c - arm, c, c + arm, c);
        g.drawLine(c, c - arm, c, c + arm);
    }

    private static void drawFrame(Graphics2D g, int imageSize, TerritoryController.WindowSelection selection, int radiusBlocks) {
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(255, 255, 255, 170));
        g.drawRect(1, 1, imageSize - 3, imageSize - 3);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(11, imageSize / 46)));
        String label = "T4 capital terrain / center " + selection.centerX + "," + selection.centerZ + " / r=" + radiusBlocks;
        int labelWidth = Math.min(imageSize - 16, label.length() * Math.max(6, imageSize / 78) + 12);
        g.setColor(new Color(0, 0, 0, 130));
        g.fillRect(8, 8, labelWidth, Math.max(20, imageSize / 24));
        g.setColor(Color.WHITE);
        g.drawString(label, 14, Math.max(23, imageSize / 22));
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

    private static String stringValue(JsonObject json, String key, String fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) return fallback;
        return json.get(key).getAsString();
    }

    private static int intValue(JsonObject json, String key, int fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) return fallback;
        return json.get(key).getAsInt();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static String safeTerritoryId(String territoryId) {
        if (territoryId == null) return "unknown";
        String cleaned = territoryId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return cleaned.isBlank() ? "unknown" : cleaned;
    }

    private static String normalizePath(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}
