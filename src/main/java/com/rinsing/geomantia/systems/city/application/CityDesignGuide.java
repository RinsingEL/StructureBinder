package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Designer-facing explanation of capabilities actually present in this frozen catalog. */
final class CityDesignGuide {
    private CityDesignGuide() { }
    static JsonObject from(CityBlueprintReferenceCatalog catalog) {
        JsonObject guide = new JsonObject();
        guide.addProperty("semanticAuthority", "pack_author_annotations_only");
        guide.addProperty("designGoal", "Compose a civilization whose structures have purposeful functions, coherent style, clear hierarchy and useful spatial relationships. Do not infer functions/styles from names or appearances.");
        guide.addProperty("workflow", "Review actual terrain and candidate capacities; choose authored structureRefs, core/fill roles, landscape and group relationships; submit one complete Blueprint revision. The host computes exact placement and downstream work. Review more candidates only when needed.");
        guide.addProperty("placementBoundary", "A chosen Patch locates the growth origin, not the whole district boundary. Preserve the chosen origin and required structures. Terrain adaptation and continuous growth are program-owned, never an excuse to substitute another design.");
        JsonArray algorithms = new JsonArray();
        catalog.algorithmsByProfileRef().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
            JsonObject algorithm = new JsonObject();
            algorithm.addProperty("algorithmProfileRef", entry.getKey());
            algorithm.addProperty("algorithm", entry.getValue());
            algorithm.addProperty("designUse", switch (entry.getValue()) {
                case "GRID" -> "Ordered rows of buildings with internal street clearance; suitable when regular blocks serve the design.";
                case "LINEAR" -> "Arrange buildings along a shared direction; consider a narrow terrain corridor or frontage.";
                case "COURTYARD" -> "Arrange around shared inner space; reserve enough room for the court and surrounding buildings.";
                case "CENTER_SYMMETRIC" -> "Commit the center first, then symmetric fill pairs; both sides need usable space.";
                case "COMPACT", "ORGANIC_COMPACT" -> "Grow a compact group from its chosen origin while respecting local terrain and clearance.";
                default -> throw new IllegalArgumentException("Unsupported design algorithm: " + entry.getValue());
            });
            algorithms.add(algorithm);
        });
        guide.add("availableArrayCapabilities", algorithms);
        guide.addProperty("composition", "Use arrayCompositions to relate complete child groups; choose a catalog composition profile for connectionPlan. Building arrays and inter-group composition profiles are different namespaces.");
        guide.addProperty("roads", "Only explicit CONNECTION relations create main roads, and they must serve real destinations. Mere adjacency or a desire to fill a gap is not a road or bridge request.");
        guide.addProperty("recovery", "On a design rejection use the exact field or local conflict evidence, preserve unchanged choices and resubmit a complete revision. Missing author metadata or content is a host blocker, not a design exercise.");
        return guide;
    }
}
