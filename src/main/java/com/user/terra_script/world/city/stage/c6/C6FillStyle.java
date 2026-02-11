package com.user.terra_script.world.city.stage.c6;

import java.util.Locale;

public enum C6FillStyle {
    PLAZA_RING,
    STREET_SPINE,
    EDGE_FOLLOW,
    CLUSTER_POISSON,
    GRID_RELAXED,
    TERRACE_BANDS,
    DECOR_BUFFER;

    public static C6FillStyle parseOrDefault(String raw, C6FillStyle fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (C6FillStyle value : values()) {
            if (value.name().equals(normalized)) return value;
        }
        return fallback;
    }
}
