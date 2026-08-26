package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;

/** Template and array dimensions used to lay out buildings; this is not a preallocated function area. */
record CityGroupSpatialDemand(int minimumAreaBlocks,
                              int targetAreaBlocks,
                              int maximumAreaBlocks,
                              int roadReserveAreaBlocks,
                              int maximumTemplateSpanBlocks,
                              int formationSpanBlocks,
                              int formationWidthBlocks,
                              int formationLengthBlocks,
                              String primaryAxisDirection,
                              int plannedStructureCount,
                              int templateFootprintAreaBlocks,
                              int internalStreetAreaBlocks) {
    CityGroupSpatialDemand {
        if (minimumAreaBlocks <= 0 || targetAreaBlocks < minimumAreaBlocks
                || maximumAreaBlocks < targetAreaBlocks || roadReserveAreaBlocks < 0
                || maximumTemplateSpanBlocks <= 0 || formationSpanBlocks <= 0
                || formationWidthBlocks <= 0 || formationLengthBlocks <= 0
                || primaryAxisDirection == null || plannedStructureCount <= 0
                || templateFootprintAreaBlocks <= 0 || internalStreetAreaBlocks < 0) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_GROUP_SPATIAL_DEMAND_INVALID");
        }
    }

    JsonObject asJson() {
        JsonObject value = new JsonObject();
        value.addProperty("source", "TEMPLATE_ARRAY_LAYOUT_DEMAND");
        value.addProperty("minimumAreaBlocks", minimumAreaBlocks);
        value.addProperty("targetAreaBlocks", targetAreaBlocks);
        value.addProperty("maximumAreaBlocks", maximumAreaBlocks);
        value.addProperty("roadReserveAreaBlocks", roadReserveAreaBlocks);
        value.addProperty("maximumTemplateSpanBlocks", maximumTemplateSpanBlocks);
        value.addProperty("formationSpanBlocks", formationSpanBlocks);
        value.addProperty("formationWidthBlocks", formationWidthBlocks);
        value.addProperty("formationLengthBlocks", formationLengthBlocks);
        if (!primaryAxisDirection.isBlank()) value.addProperty("primaryAxisDirection", primaryAxisDirection);
        value.addProperty("plannedStructureCount", plannedStructureCount);
        value.addProperty("templateFootprintAreaBlocks", templateFootprintAreaBlocks);
        value.addProperty("internalStreetAreaBlocks", internalStreetAreaBlocks);
        return value;
    }
}
