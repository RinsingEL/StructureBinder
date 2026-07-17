package com.rinsing.geomantia.systems.city.infrastructure.world;

import java.util.Set;

/** Limits the StructureStart terrain experiment to the three inspected Stubbs building templates. */
public final class CityTemplateTerrainStartPolicy {
    private static final Set<String> EXPERIMENT_TEMPLATE_REFS = Set.of(
            "geomantia:city/stubbs/agriculture/windmill_01",
            "geomantia:city/stubbs/agriculture/barn_windmill_01",
            "geomantia:city/stubbs/commercial/small_butcher_shop_01");

    private CityTemplateTerrainStartPolicy() {
    }

    public static boolean usesStructureStart(String templateRef) {
        return templateRef != null && EXPERIMENT_TEMPLATE_REFS.contains(templateRef.trim());
    }

    public static boolean usesStructureStart(CityReservationMaskRegistry.PlannedStructure planned) {
        if (planned == null || !planned.isTemplatePlacement()) {
            return false;
        }
        return usesStructureStart(templateRef(planned));
    }

    private static String templateRef(CityReservationMaskRegistry.PlannedStructure planned) {
        var templatePlan = planned.templatePlan();
        if (templatePlan.has("templateRef") && !templatePlan.get("templateRef").isJsonNull()) {
            return templatePlan.get("templateRef").getAsString();
        }
        if (templatePlan.has("structureTemplate")
                && templatePlan.get("structureTemplate").isJsonObject()) {
            var nested = templatePlan.getAsJsonObject("structureTemplate");
            if (nested.has("templateRef") && !nested.get("templateRef").isJsonNull()) {
                return nested.get("templateRef").getAsString();
            }
        }
        return "";
    }
}
