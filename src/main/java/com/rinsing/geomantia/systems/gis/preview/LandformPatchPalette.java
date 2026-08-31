package com.rinsing.geomantia.systems.gis.preview;

import java.awt.Color;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Fixed landform colors shared by T and D patch previews. */
public final class LandformPatchPalette {
    public static final String SCHEMA = "landform_patch_palette";
    private static final Map<String, String> COLORS = colors();

    private LandformPatchPalette() {
    }

    public static Map<String, String> colorsByType() {
        return COLORS;
    }

    public static String hex(String type) {
        String normalized = type == null ? "unknown" : type.trim().toLowerCase(Locale.ROOT);
        return COLORS.getOrDefault(normalized, COLORS.get("unknown"));
    }

    public static Color color(String type) {
        return Color.decode(hex(type));
    }

    private static Map<String, String> colors() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("water", "#2196F3");
        result.put("shore", "#FFEB3B");
        result.put("plain", "#4CAF50");
        result.put("terrace", "#8BC34A");
        result.put("slope", "#FF9800");
        result.put("cliff", "#795548");
        result.put("ridge", "#9C27B0");
        result.put("valley", "#00BCD4");
        result.put("basin", "#607D8B");
        result.put("unknown", "#9E9E9E");
        return Collections.unmodifiableMap(result);
    }
}
