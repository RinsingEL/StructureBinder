package com.user.terra_script.world.city.stage.c8.arrangement;

import java.util.Locale;

public enum ArrangementType {
    LINEAR_DOCK,
    COURTYARD,
    SPINE_BRANCH,
    RING,
    TERRACE_CHAIN;

    public static ArrangementType parseOrDefault(String raw, ArrangementType fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return ArrangementType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
