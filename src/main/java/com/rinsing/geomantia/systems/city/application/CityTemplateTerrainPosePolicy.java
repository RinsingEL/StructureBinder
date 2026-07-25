package com.rinsing.geomantia.systems.city.application;

/** Resolves the placement lifecycle frozen during catalog loading and D6 planning. */
public final class CityTemplateTerrainPosePolicy {
    public static final String STRUCTURE_START_BEARD_THIN = "structure_start_beard_thin";
    public static final String DATUM_POLICY_GENERATOR_BASE_HEIGHT =
            "generator_base_height_motion_blocking_no_leaves";

    private CityTemplateTerrainPosePolicy() {
    }

    public static String freezeForTemplate(String templateId, String templateRef, String terrainPosePolicy) {
        return STRUCTURE_START_BEARD_THIN;
    }

    public static boolean usesStructureStart(String terrainPosePolicy) {
        return STRUCTURE_START_BEARD_THIN.equals(terrainPosePolicy);
    }

    public static String templateDatumPolicy(String terrainPosePolicy) {
        if (!usesStructureStart(terrainPosePolicy)) {
            throw new IllegalArgumentException("CITY_TEMPLATE_TERRAIN_POLICY_REMOVED: " + terrainPosePolicy);
        }
        return DATUM_POLICY_GENERATOR_BASE_HEIGHT;
    }
}
