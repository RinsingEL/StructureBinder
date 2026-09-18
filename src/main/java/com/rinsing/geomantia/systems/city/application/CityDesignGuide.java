package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Designer-facing explanation of capabilities actually present in this frozen catalog. */
final class CityDesignGuide {
    private CityDesignGuide() { }
    static JsonObject from(CityBlueprintReferenceCatalog catalog) {
        JsonObject guide = new JsonObject();
        guide.addProperty("semanticAuthority", "pack_author_annotations_only");
        guide.add("behaviorExamples", CityDesignExamples.index());
        guide.add("surfaceMaterials",CityMaterialSupport.guide(catalog));
        guide.addProperty("placementBoundary", "A chosen Patch locates the planned array within the preview boundary. Specify structureCount per leaf array; default uses extent and algorithm. CONTIGUOUS freezes touching template footprints and keeps survivors connected to their seed. The host freezes the array, then filters individual buildings for terrain/collisions without moving survivors or filling holes. Empty arrays remain visible in the review. No automatic buildings for area targets or connections. ADJACENCY places complete array envelopes nearby; CONNECTION only requests roads.");
        JsonArray algorithms = new JsonArray();
        catalog.algorithmsByProfileRef().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
            JsonObject algorithm = new JsonObject();
            algorithm.addProperty("algorithmProfileRef", entry.getKey());
            algorithm.addProperty("algorithm", entry.getValue());
            algorithm.addProperty("designUse", switch (entry.getValue()) {
                case "CONTIGUOUS" -> "Edge-connected template landscapes with zero internal street gap and an irregular compact outline. Use generous structureCount (hundreds when space permits, up to 1024); repetition is desirable. Use authored landscape templates and pools that permit repetition. Scale coverage boldly, not random offsets or rotations. Terrain/collision losses are reported; inspect actual retained connectivity and seams. Natural landscapes remain available separately.";
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
        guide.addProperty("composition", "Read scaleDesignTask first. Use arrayCompositions to arrange whole child groups with any catalog array algorithm. A member group may itself be the centerGroupId of another composition; no duplicate parents or cycles. Leaf groups are counted once. Preserve authored functions and templates, do not replace the design with district presets. HIERARCHY expresses functional parent/child relationships only; it does not order geometric compilation or override composition centers. Functional hierarchy itself must be acyclic. BETWEEN_GROUPS references and array composition dependencies must separately be acyclic. Road ports are computed by the host.");
        guide.addProperty("boundaryPlacement", "Use existing placementRelation ALONG_PATCH_BOUNDARY with two adjacent patchRefs: first is the placement side, second is the opposite side. For COMPACT/ORGANIC_COMPACT buildings follow actual shared edges; a non-symmetric parent composition with this center relation arranges whole child arrays along those edges. CENTER_SYMMETRIC retains its symmetry rather than bending members along a curved bank. Missing shared edges are reported, never silently replaced by ordinary placement. Water and terrain checks remain unchanged. Do not infer a bridge or require both banks.");
        guide.addProperty("landscapes", "Landscapes belong to function areas, independently of building survival. owner.groupId names the function group; omit legacy requiredStructureRef in new designs. Existing buildings are optional initial placement references, never lifetime owners. In the district intent explain whom each landscape serves, why it is located there and how people reach it; review these spatial relationships in the overview. Do not scatter isolated patches and declare success from names alone. Do not automatically build roads or claim access that was never designed. Use growth={seed:{x,z},targetCellCount,allowedLandformTypes} on required ATTACHED landscapes for independent size: choose a world-coordinate seed inside the design preview and copy landformType names from terrain data (empty list allows all otherwise admissible landforms). One cell is cellStepBlocks squared from the current terrain grid, not a chunk or a building; targetCellCount is the total per instance, distributed over parcelCount. This demand replaces building-area percentages. Cell shortage, clipped parcels and approximate content shares are preview feedback, not submission failures. Inspect actual area and shape as evidence; do not retry a nonempty initial district or edit landscape content during array expansion. The host preserves that initial shape and excludes later buildings and roads; a clipped field is reported, never allowed to overwrite a building or silently relocated.");
        guide.addProperty("roads", "Only explicit CONNECTION relations create main roads, and they must serve real destinations. Mere adjacency or a desire to fill a gap is not a road or bridge request.");
        guide.addProperty("recovery", "Submit each district once; correct invalid input or a wholly empty district only. Partial terrain gaps advance automatically. Inspect the overview, mark peripheral districts, then expand one district with ADJUST_ARRAY or OUTWARD_ARRAY. Keep other districts functional. Judge coherence visually, including across rivers; no touching or fixed distance gate. Finalize the viewed result directly when satisfied.");
        return guide;
    }
}
