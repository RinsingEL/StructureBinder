package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.CityTemplateTerrainPosePolicy;

/** Selects the terrain StructureStart experiment from the frozen template catalog policy. */
public final class CityTemplateTerrainStartPolicy {
    private CityTemplateTerrainStartPolicy() {
    }

    public static boolean usesStructureStart(String terrainPosePolicy) {
        return CityTemplateTerrainPosePolicy.usesStructureStart(terrainPosePolicy);
    }

    public static boolean usesStructureStart(CityReservationMaskRegistry.PlannedStructure planned) {
        if (planned == null || !planned.isTemplatePlacement()) {
            return false;
        }
        return usesStructureStart(terrainPosePolicy(planned));
    }

    private static String terrainPosePolicy(CityReservationMaskRegistry.PlannedStructure planned) {
        var templatePlan = planned.templatePlan();
        if (templatePlan.has("terrainPosePolicy") && !templatePlan.get("terrainPosePolicy").isJsonNull()) {
            return templatePlan.get("terrainPosePolicy").getAsString();
        }
        if (templatePlan.has("structureTemplate")
                && templatePlan.get("structureTemplate").isJsonObject()) {
            JsonObject nested = templatePlan.getAsJsonObject("structureTemplate");
            if (nested.has("terrainPosePolicy") && !nested.get("terrainPosePolicy").isJsonNull()) {
                return nested.get("terrainPosePolicy").getAsString();
            }
        }
        return "";
    }
}
