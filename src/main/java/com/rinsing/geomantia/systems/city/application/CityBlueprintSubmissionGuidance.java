package com.rinsing.geomantia.systems.city.application;

/** Repair advice for submission envelope errors; never changes validation or retry accounting. */
final class CityBlueprintSubmissionGuidance {
    private CityBlueprintSubmissionGuidance() {}

    static String instruction(String error) {
        return switch (error) {
            case "CITY_BLUEPRINT_INPUT_EXACTLY_ONE_REQUIRED" ->
                    "Submit exactly one of cityBlueprint or blueprintPatch, including for FINAL. To finalize an unchanged draft, use the current baseDraftHash plus one replace operation setting an existing mutable field to its current value. Do not submit just a hash.";
            case "CITY_BLUEPRINT_PATCH_SIZE_INVALID" ->
                    "blueprintPatch must contain 1 to 128 replace operations. For unchanged FINAL, use one replace operation with the field's current value; do not use an empty array. For larger revisions submit a full cityBlueprint without base hashes.";
            case "CITY_BLUEPRINT_PATCH_REQUIRES_EXACT_SHARES", "CITY_BLUEPRINT_PROPORTION_MODE_INVALID" ->
                    "For blueprintPatch set proportionMode=EXACT_SHARES and retain normalized draft shares. RELATIVE_WEIGHTS is available only for a full cityBlueprint; do not renormalize unrelated groups in a patch.";
            case "CITY_BLUEPRINT_PATCH_BASE_EXACTLY_ONE_REQUIRED" ->
                    "Use only baseDraftHash for a current draft patch, or only baseBlueprintHash for an accepted blueprint patch; never send both. Copy the hash of the revision actually inspected.";
            case "CITY_BLUEPRINT_PATCH_REQUIRED_WITH_BASE_HASH" ->
                    "For a full cityBlueprint remove both base hashes. For a local revision use blueprintPatch and the matching current base hash, removing cityBlueprint.";
            case "CITY_BLUEPRINT_PATCH_BASE_STALE" ->
                    "Inspect the returned current revision and hash, then reconstruct the intended local patch against that revision. Do not invent a hash or replay a stale patch blindly.";
            case "CITY_BLUEPRINT_PATCH_REPLACE_REQUIRED" ->
                    "Each operation must contain exactly op=replace, path and value. To add/remove list members, replace the existing whole list; to add/remove object fields, replace the existing parent object or submit a full blueprint.";
            case "CITY_BLUEPRINT_PATCH_PATH_INVALID", "CITY_BLUEPRINT_PATCH_INDEX_INVALID", "CITY_BLUEPRINT_PATCH_PATH_NOT_FOUND" ->
                    "Use a JSON Pointer to an existing field in the current draft, with zero-based array indices. Escape ~ as ~0 and / as ~1. To introduce a missing field, replace its existing parent object or submit a full blueprint.";
            case "CITY_BLUEPRINT_PATCH_IDENTITY_LOCKED" ->
                    "Remove patch operations on schema, cityId, sourceD3Ref, catalogSnapshotRef or generationSeed. These identify the frozen design context; modify only design fields.";
            case "CITY_BLUEPRINT_SUBMISSION_MODE_INVALID" ->
                    "Set submissionMode=DRAFT to preview and revise, or FINAL to accept the inspected design. Keep the blueprint payload and other choices unchanged.";
            default -> "Correct the indicated field using the current submission schema and frozen catalog; preserve unrelated groups and inspect the current draft before resubmitting.";
        };
    }
}
