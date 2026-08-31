package com.rinsing.geomantia.systems.gis.preview;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Renders biome ids with one stable palette shared by W, T and D previews. */
public final class BiomeOverviewRenderer {
    private static final int LEGEND_WIDTH = 310;
    private static final int MAX_LEGEND_ENTRIES = 14;

    public Path render(List<Cell> cells, Path output, String title, int cellStepBlocks) throws IOException {
        Objects.requireNonNull(cells, "cells");
        Objects.requireNonNull(output, "output");
        if (cells.isEmpty()) {
            throw new IllegalArgumentException("Biome overview requires at least one cell.");
        }
        int minX = cells.stream().mapToInt(Cell::gridX).min().orElseThrow();
        int maxX = cells.stream().mapToInt(Cell::gridX).max().orElseThrow();
        int minZ = cells.stream().mapToInt(Cell::gridZ).min().orElseThrow();
        int maxZ = cells.stream().mapToInt(Cell::gridZ).max().orElseThrow();
        int widthCells = maxX - minX + 1;
        int heightCells = maxZ - minZ + 1;
        int scale = Math.max(1, Math.min(14, 920 / Math.max(widthCells, heightCells)));
        int mapWidth = widthCells * scale;
        int mapHeight = heightCells * scale;
        BufferedImage image = new BufferedImage(mapWidth + LEGEND_WIDTH,
                Math.max(390, mapHeight), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(238, 240, 239));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setColor(new Color(36, 39, 42));
            g.fillRect(0, 0, mapWidth, mapHeight);
            for (Cell cell : cells) {
                g.setColor(color(cell.biomeId()));
                g.fillRect((cell.gridX() - minX) * scale, (cell.gridZ() - minZ) * scale, scale, scale);
            }
            g.setColor(new Color(24, 28, 34));
            int x = mapWidth + 18;
            int y = 30;
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
            g.drawString(title == null || title.isBlank() ? "Biome overview" : title, x, y);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            y += 22;
            g.drawString("cell step: " + cellStepBlocks + " blocks", x, y);
            y += 18;
            g.drawString(widthCells + " x " + heightCells + " cells", x, y);
            y += 26;
            for (Map.Entry<String, Long> entry : histogram(cells).entrySet()) {
                g.setColor(color(entry.getKey()));
                g.fillRect(x, y - 11, 14, 14);
                g.setColor(new Color(24, 28, 34));
                g.drawString(shortLabel(entry.getKey()) + "  " + entry.getValue(), x + 21, y + 1);
                y += 22;
            }
        } finally {
            g.dispose();
        }
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        ImageIO.write(image, "png", output.toFile());
        return output;
    }

    public static Color color(String biomeId) {
        String id = normalize(biomeId);
        if (contains(id, "ocean", "river")) return new Color(58, 111, 168);
        if (contains(id, "beach", "shore")) return new Color(218, 204, 137);
        if (contains(id, "mushroom")) return new Color(181, 89, 160);
        if (contains(id, "swamp", "marsh", "mangrove")) return new Color(78, 112, 76);
        if (contains(id, "jungle")) return new Color(49, 131, 54);
        if (contains(id, "taiga")) return new Color(74, 123, 104);
        if (contains(id, "forest", "wood", "grove")) return new Color(72, 139, 72);
        if (contains(id, "desert", "badlands", "savanna")) return new Color(211, 174, 91);
        if (contains(id, "snow", "frozen", "ice")) return new Color(199, 220, 226);
        if (contains(id, "peak", "mountain", "windswept", "hill")) return new Color(132, 137, 126);
        if (contains(id, "plains", "meadow", "field", "grass")) return new Color(135, 181, 87);
        if (contains(id, "nether", "crimson")) return new Color(145, 55, 52);
        if (contains(id, "warped")) return new Color(49, 135, 137);
        if (contains(id, "end")) return new Color(201, 205, 126);
        int hash = id.hashCode();
        float hue = Math.floorMod(hash, 360) / 360.0F;
        float saturation = 0.35F + Math.floorMod(hash >>> 8, 24) / 100.0F;
        float brightness = 0.68F + Math.floorMod(hash >>> 16, 18) / 100.0F;
        return Color.getHSBColor(hue, saturation, Math.min(0.86F, brightness));
    }

    private static Map<String, Long> histogram(List<Cell> cells) {
        Map<String, Long> counts = new LinkedHashMap<>();
        cells.stream().map(cell -> normalize(cell.biomeId()))
                .forEach(id -> counts.merge(id, 1L, Long::sum));
        Map<String, Long> sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_LEGEND_ENTRIES)
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    private static String shortLabel(String id) {
        return id.length() <= 34 ? id : id.substring(0, 31) + "...";
    }

    private static boolean contains(String value, String... tokens) {
        for (String token : tokens) if (value.contains(token)) return true;
        return false;
    }

    private static String normalize(String biomeId) {
        return biomeId == null || biomeId.isBlank() ? "unknown" : biomeId.trim().toLowerCase(Locale.ROOT);
    }

    public record Cell(int gridX, int gridZ, String biomeId) {
    }
}
