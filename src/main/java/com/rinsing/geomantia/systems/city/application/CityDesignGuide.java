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
        guide.addProperty("workflow", "Review terrain; submit designIntent (groupId, role, intent, preferredPatchRefs) through city_submit_d4_blueprint. Batch materialSelections per intended group to search authored metadata or select structureRefs/fillPoolRefs and receive capacity estimates. Design complete nested clusters using DRAFT, inspect planned/retained counts and gaps, revise locally, then FINAL. A cluster can contain many children in one call. Approximately 20 calls / 5 minutes is an efficiency goal, not a rejection limit.");
        guide.addProperty("placementBoundary", "A chosen Patch locates the planned array within the preview boundary. Specify structureCount per leaf array; default uses extent and algorithm. The host freezes the array, then filters individual buildings for terrain/collisions without moving survivors or filling holes. Empty arrays remain visible in the review. No automatic buildings for area targets or connections. ADJACENCY places complete array envelopes nearby; CONNECTION only requests roads.");
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
        guide.addProperty("composition", "Read scaleDesignTask first. Use arrayCompositions to arrange whole child groups with any catalog array algorithm. A member group may itself be the centerGroupId of another composition; no duplicate parents or cycles. Leaf groups are counted once. Preserve authored functions and templates, do not replace the design with district presets. Road ports are computed by the host.");
        guide.addProperty("boundaryPlacement", "Use existing placementRelation ALONG_PATCH_BOUNDARY with two adjacent patchRefs: first is the placement side, second is the opposite side. For COMPACT/ORGANIC_COMPACT buildings follow actual shared edges; a non-symmetric parent composition with this center relation arranges whole child arrays along those edges. CENTER_SYMMETRIC retains its symmetry rather than bending members along a curved bank. Missing shared edges are reported, never silently replaced by ordinary placement. Water and terrain checks remain unchanged. Do not infer a bridge or require both banks.");
        guide.addProperty("landscapes", "Design required landscape with its owner group before array fill. The host preserves that initial shape and excludes later buildings and roads; a clipped field is reported, never allowed to overwrite a building or silently relocated.");
        guide.addProperty("roads", "Only explicit CONNECTION relations create main roads, and they must serve real destinations. Mere adjacency or a desire to fill a gap is not a road or bridge request.");
        guide.addProperty("recovery", "On a design rejection preserve unchanged groups. Prefer baseBlueprintHash + blueprintPatch (replace-only JSON Pointer) to change only the reported group or relation. Do not change generationSeed. The host reuses successful exact-input array checkpoints; changed terrain, placement inputs or occupied dependencies invalidate them. Shared roads and final safety are always rechecked. Missing author metadata or program geometry failures are host blockers, not a redesign request.");
        return guide;
    }
}
