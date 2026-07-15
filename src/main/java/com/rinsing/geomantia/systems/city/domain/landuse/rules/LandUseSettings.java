package com.rinsing.geomantia.systems.city.domain.landuse.rules;

public record LandUseSettings(String schemaVersion, boolean enabledInWorkflow, String profileId) {
    public static final String SCHEMA = "city_land_use_settings.v0.1";
    public static final String DEFAULT_PROFILE_ID = "default_v0_1";

    public LandUseSettings {
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("LAND_USE_SETTINGS_SCHEMA_UNSUPPORTED");
        if (profileId == null || profileId.isBlank()) throw new IllegalArgumentException("profileId is required");
        if (!DEFAULT_PROFILE_ID.equals(profileId)) {
            throw new IllegalArgumentException("LAND_USE_RULE_PROFILE_UNSUPPORTED: " + profileId);
        }
    }
}
