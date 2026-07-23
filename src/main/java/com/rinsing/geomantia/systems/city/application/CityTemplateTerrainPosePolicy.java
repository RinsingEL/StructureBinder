package com.rinsing.geomantia.systems.city.application;

/** Resolves the placement lifecycle frozen during catalog loading and D6 planning. */
public final class CityTemplateTerrainPosePolicy {
    public static final String GEOMANTIA_NAMESPACE_PREFIX = "geomantia:";
    public static final String DIRECT_TEMPLATE = "direct_template";
    public static final String STRUCTURE_START_BEARD_THIN = "structure_start_beard_thin";
    public static final String DATUM_POLICY_WORLDGEN_SURFACE = "worldgen_surface_motion_blocking_no_leaves";
    public static final String DATUM_POLICY_GENERATOR_BASE_HEIGHT =
            "generator_base_height_motion_blocking_no_leaves";

    private CityTemplateTerrainPosePolicy() {
    }

    public static String freeze(String terrainPosePolicy) {
        return terrainPosePolicy == null || terrainPosePolicy.isBlank()
                ? DIRECT_TEMPLATE : terrainPosePolicy.trim();
    }

    public static String freezeForTemplate(String templateId, String templateRef, String terrainPosePolicy) {
        return isGeomantiaTemplate(templateId, templateRef)
                ? STRUCTURE_START_BEARD_THIN : freeze(terrainPosePolicy);
    }

    public static boolean isGeomantiaTemplate(String templateId, String templateRef) {
        return hasGeomantiaNamespace(templateId) || hasGeomantiaNamespace(templateRef);
    }

    public static boolean usesStructureStart(String terrainPosePolicy) {
        return STRUCTURE_START_BEARD_THIN.equals(freeze(terrainPosePolicy));
    }

    public static String templateDatumPolicy(String terrainPosePolicy) {
        return usesStructureStart(terrainPosePolicy)
                ? DATUM_POLICY_GENERATOR_BASE_HEIGHT : DATUM_POLICY_WORLDGEN_SURFACE;
    }

    private static boolean hasGeomantiaNamespace(String value) {
        return value != null && value.trim().startsWith(GEOMANTIA_NAMESPACE_PREFIX);
    }
}
