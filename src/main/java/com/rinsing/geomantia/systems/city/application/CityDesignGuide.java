package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Designer-facing explanation of capabilities actually present in this frozen catalog. */
final class CityDesignGuide {
    private static final String HANDBOOK = loadHandbook();
    private CityDesignGuide() { }
    private static String loadHandbook() {
        try (var input = CityDesignGuide.class.getResourceAsStream("/geomantia/city_design_handbook.md")) {
            if (input == null) throw new IllegalStateException("City design handbook resource is missing");
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot read city design handbook", failure);
        }
    }
    static JsonObject from(CityBlueprintReferenceCatalog catalog) {
        JsonObject guide = new JsonObject();
        guide.addProperty("semanticAuthority", "pack_author_annotations_only");
        guide.addProperty("designGoal", "Design comes first: act as a bold, terrain-aware city designer, not a submission validator. Compose a civilization whose structures have purposeful functions, coherent style, clear hierarchy and useful spatial relationships. Use available arrays, nesting and deliberate counts ambitiously where the terrain and chosen assets support the idea. A valid compilation is the start of visual judgement, not the design goal. Do not infer functions/styles from names or appearances.");
        guide.addProperty("workflow", "D4 四阶段：city_d4_overview 确定总览与功能区意图；city_d4_district 逐区设计、看图评价与确认；city_d4_integrate 必须实际用向外阵列改善区际关系并重看总览；city_d4_finalize 确认当前版本。宿主保存并组合各阶段产物，遵循 d4Workflow 的当前任务。规模建议服务于设计，不是为了效率省略阶段的依据。");
        JsonObject loop = new JsonObject();
        loop.addProperty("districtInitial", "Inspect terrain BEFORE choosing a composition. Read this city's function, scaleDesignTask and authored material pool; select a suitable Top Patch and state the district's purpose and spatial idea. Design one functional district, including multiple child arrays where useful, with districtDesign. The host retains previous districts. A functional district need not equal a single leaf group. The saved overview determines district order.");
        loop.addProperty("scaleAndNesting", "Use scaleDesignTask's scale-specific requirements. Its suggested initial array range is a starting estimate, NOT a cap, target to tick off or reason to stop refining. Larger counts, additional purposeful arrays and richer nesting are welcome when the preview needs them. CITY and LARGE_CITY need a substantial composed main body, not just several unrelated arrays or token nesting. Choose structureCount deliberately using selected NBT sizes and capacity estimates. Compose children around an intended shared space, frontage, landmark or activity; do not mechanically repeat one recipe, always use COMPACT, or inflate nesting depth for its own sake. Small settlements may stay simple. No new hard area or retained-building threshold.");
        loop.addProperty("districtRefinement", "GOAL: buildings must jointly form usable, legible spaces appropriate to the district. ACTION: request local designReview images (up to 3 groupIds); compare the intended court, frontage, shared space or activity with the actual arrangement. Adjust counts, child/parent parameters, orientation, templates or adjacent arrays to repair weak results. CHECK: describe which buildings enclose or face which space, how entrances meet it, and whether the intended depth and hierarchy survive. A list of algorithms or successful placements is not an assessment. If terrain removes an important anchor or frontage, try a local repair preserving its purpose before accepting the loss. Re-view changed results; preserve good parts, not weak geometry merely because it compiled.");
        loop.addProperty("cityRefinement", "GOAL: turn individually designed districts into a coherent city. ACTION: after local reviews, request the overview and identify isolated districts, long empty connecting segments and unfinished district edges. For intended urban links, actively design purposeful outward/adjacent arrays from an existing district toward the target, using available placement and relation capabilities; choose functions and authored templates that extend the two districts. This is a design task, not merely an optional paragraph about whether additions were considered. CHECK AFTER COMPILATION: did the new frontage actually extend toward the target and shorten the empty segment? A new isolated node or a street growing perpendicular to the intended link does not complete the task. Revise origin, direction, count or composition and re-view until the identified link improves within terrain and tool limits. Road connectivity alone is not urban continuity. Keep visible productive fields, water, public space and evidenced terrain barriers; do not fill every gap. Existing well-connected areas need no forced edits. Review changed locals and the overview before city_d4_finalize; report any unresolved separation honestly.");
        loop.addProperty("judgement", "Assess visible outcomes, not submission completion. Report intent, observed spatial evidence, the actual revision and its visible effect (or concrete evidence for keeping an already suitable arrangement). Counts, nesting depth, all buildings surviving and roads connecting are insufficient alone. Do not explain missing buildings or long empty segments away as deliberate breathing room or terrain transition without matching preview and placement evidence. No invented terrain obstacles or unbuilt decorations. When thin blocks, weak internal organization or missing transitions remain unresolved after an adjustment, read the matching behaviorExamples case with designExample before continuing; inspect before/after images and transfer the method, not coordinates or counts. The host records assessments; it does not certify beauty. No compulsory edit count or new rejection budget.");
        guide.add("designLoop", loop);
        guide.addProperty("behaviorHandbook", HANDBOOK);
        guide.add("behaviorExamples", CityDesignExamples.index());
        guide.add("surfaceMaterials",CityMaterialSupport.guide(catalog));
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
        guide.addProperty("composition", "Read scaleDesignTask first. Use arrayCompositions to arrange whole child groups with any catalog array algorithm. A member group may itself be the centerGroupId of another composition; no duplicate parents or cycles. Leaf groups are counted once. Preserve authored functions and templates, do not replace the design with district presets. HIERARCHY expresses functional parent/child relationships only; it does not order geometric compilation or override composition centers. Functional hierarchy itself must be acyclic. BETWEEN_GROUPS references and array composition dependencies must separately be acyclic. Road ports are computed by the host.");
        guide.addProperty("boundaryPlacement", "Use existing placementRelation ALONG_PATCH_BOUNDARY with two adjacent patchRefs: first is the placement side, second is the opposite side. For COMPACT/ORGANIC_COMPACT buildings follow actual shared edges; a non-symmetric parent composition with this center relation arranges whole child arrays along those edges. CENTER_SYMMETRIC retains its symmetry rather than bending members along a curved bank. Missing shared edges are reported, never silently replaced by ordinary placement. Water and terrain checks remain unchanged. Do not infer a bridge or require both banks.");
        guide.addProperty("landscapes", "Design required landscape with its owner group before array fill. Use growth={seed:{x,z},targetCellCount,allowedLandformTypes} on required ATTACHED landscapes for independent size: choose a world-coordinate seed inside the design preview and copy landformType names from terrain data (empty list allows all otherwise admissible landforms). One cell is cellStepBlocks squared from the current terrain grid, not a chunk or a building; targetCellCount is the total per instance, distributed over parcelCount. This demand replaces building-area percentages. Cell shortage, clipped parcels and approximate content shares are preview feedback, not submission failures. Inspect actual area and shape, then adjust the seed/count/content if needed. The host preserves that initial shape and excludes later buildings and roads; a clipped field is reported, never allowed to overwrite a building or silently relocated.");
        guide.addProperty("roads", "Only explicit CONNECTION relations create main roads, and they must serve real destinations. Mere adjacency or a desire to fill a gap is not a road or bridge request.");
        guide.addProperty("recovery", "On a design rejection resubmit the current districtDesign or integrationDesign with corrections. The host preserves other saved districts and generationSeed. Reopen an earlier district via city_d4_overview.reopenDistrictId when necessary. The host reuses successful exact-input array checkpoints; changed terrain, placement inputs or occupied dependencies invalidate them. Shared roads and final safety are always rechecked. Missing author metadata or program geometry failures are host blockers, not a redesign request.");
        return guide;
    }
}
