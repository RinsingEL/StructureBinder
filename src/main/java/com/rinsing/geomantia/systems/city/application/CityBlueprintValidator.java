package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprintReasonCode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
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
        validateOutdoorPlan(issues, blueprint.outdoorPlan(), groupIds, context.patchRefs(), catalog);
        return new ValidationResult(issues.isEmpty(), List.copyOf(issues));
    }

    private static void validateOutdoorPlan(List<Issue> issues, CityBlueprint.OutdoorPlan plan,
                                            Set<String> groupIds, Set<String> patchRefs,
                                            CityBlueprintReferenceCatalog catalog) {
        if (!catalog.foundationProfiles().containsKey(plan.foundationProfileRef())) {
            add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_FOUNDATION_PROFILE_UNKNOWN,
                    "$.outdoorPlan.foundationProfileRef",
                    "Unknown foundation profile: " + plan.foundationProfileRef());
        }
        if (plan.mode() == CityBlueprint.OutdoorMode.PRESERVE) {
            if (!plan.spatialGrounds().isEmpty() || !plan.landscapes().isEmpty()) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_MODE_INVALID,
                        "$.outdoorPlan", "PRESERVE requires empty spatialGrounds and landscapes.");
            }
            return;
        }

        Set<String> coveredGroups = new HashSet<>();
        for (int index = 0; index < plan.spatialGrounds().size(); index++) {
            CityBlueprint.SpatialGround ground = plan.spatialGrounds().get(index);
            String path = "$.outdoorPlan.spatialGrounds[" + index + "]";
            if (!groupIds.contains(ground.sourceGroupId())) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_GROUP_REF_UNKNOWN,
                        path + ".sourceGroupId", "Unknown structure Group: " + ground.sourceGroupId());
            } else if (!coveredGroups.add(ground.sourceGroupId())) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_GROUND_COVERAGE_INVALID,
                        path + ".sourceGroupId", "A STRUCTURE Group must have exactly one SpatialGround.");
            }
        }
        if (!coveredGroups.equals(groupIds)) {
            add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_GROUND_COVERAGE_INVALID,
                    "$.outdoorPlan.spatialGrounds",
                    "GENERATE requires exactly one SpatialGround for every STRUCTURE Group.");
        }

        Set<String> landscapeIds = new HashSet<>();
        for (int index = 0; index < plan.landscapes().size(); index++) {
            CityBlueprint.Landscape landscape = plan.landscapes().get(index);
            String path = "$.outdoorPlan.landscapes[" + index + "]";
            if (!landscapeIds.add(landscape.landscapeId())) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_LANDSCAPE_ID_DUPLICATE,
                        path + ".landscapeId", "landscapeId must be unique: " + landscape.landscapeId());
            }
            CityBlueprintReferenceCatalog.LandscapeProfile landscapeProfile =
                    catalog.landscapeProfiles().get(landscape.landscapeProfileRef());
            if (landscapeProfile == null) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_LANDSCAPE_PROFILE_UNKNOWN,
                        path + ".landscapeProfileRef",
                        "Unknown landscape profile: " + landscape.landscapeProfileRef());
            }
            if (landscape.attachedGroupIds().isEmpty() && landscape.preferredPatchRefs().isEmpty()) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_LANDSCAPE_SOURCE_REQUIRED,
                        path, "A landscape needs at least one attached Group or preferred D3 patch.");
            }
            validateGroupList(issues, landscape.attachedGroupIds(), groupIds,
                    path + ".attachedGroupIds");
            validateReferenceGroups(issues, landscape.referenceGroupIds(), groupIds, null,
                    path + ".referenceGroupIds");
            for (int patchIndex = 0; patchIndex < landscape.preferredPatchRefs().size(); patchIndex++) {
                String ref = landscape.preferredPatchRefs().get(patchIndex);
                if (!patchRefs.contains(ref)) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_PATCH_REF_UNKNOWN,
                            path + ".preferredPatchRefs[" + patchIndex + "]", "Unknown D3 patch: " + ref);
                }
            }
            boolean requiresReferences = landscape.growthRelation()
                    == CityBlueprint.LandscapeGrowthRelation.AWAY_FROM_REFERENCE;
            if (requiresReferences != !landscape.referenceGroupIds().isEmpty()) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                        path + ".referenceGroupIds",
                        "AWAY_FROM_REFERENCE requires references; other landscape growth relations forbid them.");
            }
            validateFillSelection(issues, landscape, landscapeProfile, catalog, path + ".fillSelection");
        }
    }

    private static void validateFillSelection(List<Issue> issues, CityBlueprint.Landscape landscape,
                                              CityBlueprintReferenceCatalog.LandscapeProfile landscapeProfile,
                                              CityBlueprintReferenceCatalog catalog, String path) {
        CityBlueprint.FillSelection selection = landscape.fillSelection();
        if (selection == null || selection.variants().isEmpty()) {
            add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID, path,
                    "A landscape requires at least one fill variant.");
            return;
        }
        for (int index = 0; index < selection.variants().size(); index++) {
            CityBlueprint.FillVariant variant = selection.variants().get(index);
            String variantPath = path + ".variants[" + index + "]";
            CityBlueprintReferenceCatalog.LandscapeFillProfile profile =
                    catalog.landscapeFillProfiles().get(variant.fillProfileRef());
            if (profile == null) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                        variantPath + ".fillProfileRef", "Unknown landscape fill profile: "
                                + variant.fillProfileRef());
                continue;
            }
            if (landscapeProfile != null
                    && !profile.compatibleLandscapeTypes().contains(landscapeProfile.landscapeType())) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                        variantPath + ".fillProfileRef", "Fill profile is incompatible with landscape type "
                                + landscapeProfile.landscapeType() + ".");
            }
            if (!Double.isFinite(variant.selectionWeight()) || variant.selectionWeight() <= 0.0) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                        variantPath + ".selectionWeight", "selectionWeight must be positive and finite.");
            }
            Set<String> roleRefs = new HashSet<>();
            java.util.Map<String, Double> aggregateShares = new HashMap<>();
            double shareSum = 0.0;
            for (int roleIndex = 0; roleIndex < variant.roleShares().size(); roleIndex++) {
                CityBlueprint.RoleShare share = variant.roleShares().get(roleIndex);
                String sharePath = variantPath + ".roleShares[" + roleIndex + "]";
                CityBlueprintReferenceCatalog.FillRole role = profile.roles().get(share.roleRef());
                roleRefs.add(share.roleRef());
                if (role == null) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                            sharePath + ".roleRef", "roleRef is not declared by the selected fill profile.");
                } else if (!role.allowedGrowthForms().contains(share.growthForm())) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                            sharePath + ".growthForm",
                            "growthForm must be allowed by the selected fill role and only biases frontier growth.");
                } else if (!Double.isFinite(share.targetShare()) || share.targetShare() <= 0.0
                        || share.targetShare() >= 1.0) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                            sharePath + ".targetShare", "Each region-stage targetShare must be in (0,1).");
                }
                shareSum += share.targetShare();
                aggregateShares.merge(share.roleRef(), share.targetShare(), Double::sum);
            }
            if (variant.roleShares().isEmpty() || !roleRefs.contains(profile.primaryRoleRef())) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                        variantPath + ".roleShares", "roleShares must be non-empty and contain primaryRoleRef.");
            }
            if (!roleRefs.equals(profile.roles().keySet())) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                        variantPath + ".roleShares",
                        "Ordered region stages must contain every role declared by the selected fill profile.");
            }
            if (!Double.isFinite(shareSum) || Math.abs(shareSum - 1.0) > 1.0e-6) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                        variantPath + ".roleShares", "targetShare values must sum to 1.0.");
            }
            for (CityBlueprintReferenceCatalog.FillRole role : profile.roles().values()) {
                double aggregate = aggregateShares.getOrDefault(role.roleRef(), 0.0);
                if (!Double.isFinite(aggregate) || aggregate < role.minShare() || aggregate > role.maxShare()) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                            variantPath + ".roleShares",
                            "All stages for role " + role.roleRef()
                                    + " must aggregate within its configured share range.");
                }
            }
            Set<String> contentRefs = new HashSet<>();
            for (int contentIndex = 0; contentIndex < variant.contentWeights().size(); contentIndex++) {
                CityBlueprint.ContentWeight weight = variant.contentWeights().get(contentIndex);
                String weightPath = variantPath + ".contentWeights[" + contentIndex + "]";
                if (!contentRefs.add(weight.contentRef())
                        || !profile.allowedContentRefs().contains(weight.contentRef())) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                            weightPath + ".contentRef",
                            "contentRef must be unique and present in the fill profile whitelist.");
                }
                if (!Double.isFinite(weight.weight()) || weight.weight() <= 0.0) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                            weightPath + ".weight", "content weight must be positive and finite.");
                }
            }
        }
    }

    private static void validateGroupList(List<Issue> issues, List<String> refs, Set<String> groupIds,
                                          String path) {
        for (int index = 0; index < refs.size(); index++) {
            if (!groupIds.contains(refs.get(index))) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_GROUP_REF_UNKNOWN,
                        path + "[" + index + "]", "Unknown structure Group: " + refs.get(index));
            }
        }
    }

    private static void validateReferenceGroups(List<Issue> issues, List<String> refs, Set<String> groupIds,
                                                String self, String path) {
        Set<String> unique = new HashSet<>();
        for (int index = 0; index < refs.size(); index++) {
            String ref = refs.get(index);
            if (!unique.add(ref) || !groupIds.contains(ref) || ref.equals(self)) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                        path + "[" + index + "]", "Reference Groups must be unique, existing, and not self-referential.");
            }
        }
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
