package com.rinsing.geomantia.systems.city.domain.landuse.rules;

import java.util.regex.Pattern;

public record LandUseSettings(String schema, boolean enabledInWorkflow, String profileId) {
    public static final String SCHEMA = "city_land_use_settings";
    public static final String DEFAULT_PROFILE_ID = "default";
    private static final Pattern PROFILE_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_.-]*");

    public LandUseSettings {
        if (!SCHEMA.equals(schema)) throw new IllegalArgumentException("LAND_USE_SETTINGS_SCHEMA_UNSUPPORTED");
        if (profileId == null || !PROFILE_ID_PATTERN.matcher(profileId).matches()) {
            throw new IllegalArgumentException("LAND_USE_PROFILE_ID_INVALID: " + profileId);
        }
    }
}
