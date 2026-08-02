package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprintReasonCode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CityBlueprintValidator {
    public ValidationResult validate(CityBlueprint blueprint, ExpectedContext context,
                                     CityBlueprintReferenceCatalog catalog) {
        List<Issue> issues = new ArrayList<>();
        if (!context.cityId().equals(blueprint.cityId())) {
            add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_CITY_MISMATCH, "$.cityId",
                    "Blueprint cityId does not match the prepared context.");
        }
        compareRef(issues, blueprint.sourceD3Ref(), context.sourceD3Ref(),
                CityBlueprintReasonCode.CITY_BLUEPRINT_D3_STALE, "$.sourceD3Ref");
        compareRef(issues, blueprint.catalogSnapshotRef(), context.catalogSnapshotRef(),
                CityBlueprintReasonCode.CITY_BLUEPRINT_CATALOG_STALE, "$.catalogSnapshotRef");
        if (blueprint.groups().isEmpty()) {
            add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_GROUPS_EMPTY, "$.groups",
                    "At least one STRUCTURE group is required.");
        }

        Set<String> groupIds = new HashSet<>();
        for (int index = 0; index < blueprint.groups().size(); index++) {
            CityBlueprint.Group group = blueprint.groups().get(index);
            String path = "$.groups[" + index + "]";
            if (!groupIds.add(group.groupId())) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_GROUP_ID_DUPLICATE, path + ".groupId",
                        "groupId must be unique: " + group.groupId());
            }
            if (group.groupKind() != CityBlueprint.GroupKind.STRUCTURE) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_GROUP_KIND_UNSUPPORTED, path + ".groupKind",
                        "LANDSCAPE is reserved for case 04 and is not supported by v0.2.");
            }
            for (int patchIndex = 0; patchIndex < group.preferredPatchRefs().size(); patchIndex++) {
                String patchRef = group.preferredPatchRefs().get(patchIndex);
                if (!context.patchRefs().contains(patchRef)) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_PREFERRED_PATCH_UNKNOWN,
                            path + ".preferredPatchRefs[" + patchIndex + "]", "Unknown D3 patch: " + patchRef);
                }
            }
            if (group.requiredStructureRefs().isEmpty()) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_REQUIRED_STRUCTURES_EMPTY,
                        path + ".requiredStructureRefs", "A STRUCTURE group needs at least one required structure.");
            }
            for (String ref : group.requiredStructureRefs()) {
                if (!catalog.structureRefs().contains(ref)) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_STRUCTURE_REF_UNKNOWN,
                            path + ".requiredStructureRefs", "Unknown structureRef: " + ref);
                }
            }
            requireRef(issues, catalog.fillPoolRefs(), group.fillPoolRef(),
                    CityBlueprintReasonCode.CITY_BLUEPRINT_FILL_POOL_UNKNOWN, path + ".fillPoolRef");
            requireRef(issues, catalog.algorithmProfileRefs(), group.algorithmProfileRef(),
                    CityBlueprintReasonCode.CITY_BLUEPRINT_ALGORITHM_PROFILE_UNKNOWN,
                    path + ".algorithmProfileRef");
            validateConnectionPlan(issues, group, catalog, path);
            requireRef(issues, catalog.compositionProfileRefs(), group.compositionProfileRef(),
                    CityBlueprintReasonCode.CITY_BLUEPRINT_COMPOSITION_PROFILE_UNKNOWN,
                    path + ".compositionProfileRef");
            if (!group.attachedFeatures().isEmpty()) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_ATTACHED_FEATURE_UNSUPPORTED,
                        path + ".attachedFeatures",
                        "attachedFeatures must be empty until case 04 defines LANDSCAPE ownership.");
            }
        }

        for (int index = 0; index < blueprint.relations().size(); index++) {
            CityBlueprint.Relation relation = blueprint.relations().get(index);
            String path = "$.relations[" + index + "]";
            if (!groupIds.contains(relation.fromGroupId()) || !groupIds.contains(relation.toGroupId())) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_RELATION_ENDPOINT_UNKNOWN, path,
                        "Both relation endpoints must name existing groups.");
            }
            if (relation.fromGroupId().equals(relation.toGroupId())) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_RELATION_SELF_REFERENCE, path,
                        "A relation cannot point to the same group.");
            }
            boolean distance = relation.relationKind() == CityBlueprint.RelationKind.DISTANCE;
            boolean hasDistance = relation.distancePreference() != CityBlueprint.DistancePreference.NONE;
            if (distance != hasDistance) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_RELATION_DISTANCE_INVALID,
                        path + ".distancePreference",
                        "DISTANCE requires NEAR or FAR; other relation kinds require NONE.");
            }
            boolean directional = relation.relationKind() == CityBlueprint.RelationKind.DIRECTION;
            boolean hasDirection = relation.directionPreference() != CityBlueprint.DirectionPreference.NONE;
            if (directional != hasDirection) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_RELATION_DIRECTION_INVALID,
                        path + ".directionPreference",
                        "DIRECTION requires a compass preference; other relation kinds require NONE.");
            }
        }
        requireRef(issues, catalog.styleProfileRefs(), blueprint.styleProfile().profileRef(),
                CityBlueprintReasonCode.CITY_BLUEPRINT_STYLE_PROFILE_UNKNOWN, "$.styleProfile.profileRef");
        requireRef(issues, catalog.roadProfileRefs(), blueprint.roadProfile().profileRef(),
                CityBlueprintReasonCode.CITY_BLUEPRINT_ROAD_PROFILE_UNKNOWN, "$.roadProfile.profileRef");
        requireRef(issues, catalog.surfaceDetailProfileRefs(), blueprint.surfaceDetailProfile().profileRef(),
                CityBlueprintReasonCode.CITY_BLUEPRINT_SURFACE_DETAIL_PROFILE_UNKNOWN,
                "$.surfaceDetailProfile.profileRef");
        return new ValidationResult(issues.isEmpty(), List.copyOf(issues));
    }

    private static void validateConnectionPlan(List<Issue> issues, CityBlueprint.Group group,
                                               CityBlueprintReferenceCatalog catalog, String path) {
        CityBlueprint.ConnectionPlan plan = group.connectionPlan();
        if (plan == null) return;
        if (plan.structurePoolRef() != null) {
            requireRef(issues, catalog.fillPoolRefs(), plan.structurePoolRef(),
                    CityBlueprintReasonCode.CITY_BLUEPRINT_FILL_POOL_UNKNOWN,
                    path + ".connectionPlan.structurePoolRef");
        }
        if (plan.algorithmProfileRef() != null) {
            requireRef(issues, catalog.algorithmProfileRefs(), plan.algorithmProfileRef(),
                    CityBlueprintReasonCode.CITY_BLUEPRINT_ALGORITHM_PROFILE_UNKNOWN,
                    path + ".connectionPlan.algorithmProfileRef");
        }
        String profileRef = plan.algorithmProfileRef() == null
                ? group.algorithmProfileRef() : plan.algorithmProfileRef();
        String algorithm = catalog.algorithmsByProfileRef().get(profileRef);
        CityBlueprint.ConnectionParameters parameters = plan.parameters();
        if (algorithm == null || parameters.emptyParameters()) return;
        boolean cluster = !"LINEAR".equals(algorithm);
        boolean hasCluster = parameters.clusterShape() != null;
        boolean hasBand = parameters.sideMode() != null || parameters.stagger() != null
                || parameters.widthClass() != null;
        if (cluster && hasBand || !cluster && hasCluster) {
            add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_CONNECTION_PARAMETERS_INVALID,
                    path + ".connectionPlan.parameters",
                    cluster
                            ? "The resolved compound_cluster planner only accepts clusterShape."
                            : "The resolved guide_line_dual_side planner only accepts sideMode, stagger and widthClass.");
        }
    }

    private static void compareRef(List<Issue> issues, CityBlueprint.ArtifactRef actual,
                                   CityBlueprint.ArtifactRef expected, CityBlueprintReasonCode reason, String path) {
        if (!actual.equals(expected)) add(issues, reason, path, "Artifact reference does not match the frozen context.");
    }

    private static void requireRef(List<Issue> issues, Set<String> allowed, String ref,
                                   CityBlueprintReasonCode reason, String path) {
        if (!allowed.contains(ref)) add(issues, reason, path, "Unknown catalog reference: " + ref);
    }

    private static void add(List<Issue> issues, CityBlueprintReasonCode reason, String path, String message) {
        issues.add(new Issue(reason, path, message));
    }

    public record ExpectedContext(String cityId, CityBlueprint.ArtifactRef sourceD3Ref,
                                  CityBlueprint.ArtifactRef catalogSnapshotRef, Set<String> patchRefs) {
        public ExpectedContext {
            patchRefs = Set.copyOf(patchRefs);
        }
    }

    public record Issue(CityBlueprintReasonCode reasonCode, String fieldPath, String message) {
        public JsonObject asJson() {
            JsonObject object = new JsonObject();
            object.addProperty("reasonCode", reasonCode.name());
            object.addProperty("fieldPath", fieldPath);
            object.addProperty("message", message);
            return object;
        }
    }

    public record ValidationResult(boolean valid, List<Issue> issues) {
        public JsonObject asJson() {
            JsonObject object = new JsonObject();
            object.addProperty("valid", valid);
            JsonArray array = new JsonArray();
            issues.forEach(issue -> array.add(issue.asJson()));
            object.add("issues", array);
            return object;
        }
    }
}
