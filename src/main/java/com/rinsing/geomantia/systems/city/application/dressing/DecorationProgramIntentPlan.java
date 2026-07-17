package com.rinsing.geomantia.systems.city.application.dressing;

import java.util.List;

public record DecorationProgramIntentPlan(String schemaVersion, String cityId, String catalogHash,
                                          String styleProfileId, String styleProfileHash,
                                          List<DecorationProgramIntent> programs) {
    public static final String SCHEMA = "city_decoration_program_plan.v0.3";
    public static final String LEGACY_SCHEMA = "city_decoration_program_plan.v0.2";

    public DecorationProgramIntentPlan {
        if (!SCHEMA.equals(schemaVersion) && !LEGACY_SCHEMA.equals(schemaVersion)) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_PLAN_SCHEMA_UNSUPPORTED: " + schemaVersion);
        }
        if (cityId == null || cityId.isBlank()) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_PLAN_CITY_ID_REQUIRED");
        }
        if (catalogHash == null || catalogHash.isBlank()) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_PLAN_CATALOG_HASH_REQUIRED");
        }
        if (styleProfileId == null || !styleProfileId.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("CITY_DECORATION_STYLE_PROFILE_ID_INVALID");
        }
        if (styleProfileHash == null || styleProfileHash.isBlank()) {
            throw new IllegalArgumentException("CITY_DECORATION_STYLE_PROFILE_HASH_REQUIRED");
        }
        programs = List.copyOf(programs);
        if (programs.isEmpty()) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAMS_REQUIRED");
        }
        if (programs.stream().map(DecorationProgramIntent::programId).distinct().count() != programs.size()) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_ID_DUPLICATE");
        }
    }

    public DecorationProgramIntentPlan(String schemaVersion, String cityId, String catalogHash,
                                       List<DecorationProgramIntent> programs) {
        this(schemaVersion, cityId, catalogHash, "direct_catalog", "legacy_internal", programs);
    }
}
