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
        int highestPriorityGroupCount = 0;
        double areaShareSum = 0.0;
        for (int index = 0; index < blueprint.groups().size(); index++) {
            CityBlueprint.Group group = blueprint.groups().get(index);
            String path = "$.groups[" + index + "]";
            if (!groupIds.add(group.groupId())) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_GROUP_ID_DUPLICATE, path + ".groupId",
                        "groupId must be unique: " + group.groupId());
            }
            if (group.groupKind() != CityBlueprint.GroupKind.STRUCTURE) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_GROUP_KIND_UNSUPPORTED, path + ".groupKind",
                        "LANDSCAPE is reserved for case 04 and is not supported by the current blueprint schema.");
            }
            if (group.priority() == CityBlueprint.GroupPriority.CORE) highestPriorityGroupCount++;
            if (!Double.isFinite(group.targetAreaShare()) || group.targetAreaShare() <= 0.0
                    || group.targetAreaShare() > 1.0) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_GROUP_AREA_SHARE_INVALID,
                        path + ".targetAreaShare", "targetAreaShare must be in (0,1].");
            }
            areaShareSum += group.targetAreaShare();
            CityBlueprint.SpaceComposition composition = group.spaceComposition();
            double compositionSum = composition.buildingShare() + composition.landscapeShare()
                    + composition.openSpaceShare();
            if (!Double.isFinite(compositionSum) || composition.buildingShare() < 0.0
                    || composition.landscapeShare() < 0.0 || composition.openSpaceShare() < 0.0
                    || Math.abs(compositionSum - 1.0) > 1.0e-6) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_GROUP_SPACE_COMPOSITION_INVALID,
                        path + ".spaceComposition", "building/landscape/openSpace shares must be non-negative and sum to 1.");
            }
            for (int patchIndex = 0; patchIndex < group.preferredPatchRefs().size(); patchIndex++) {
                String patchRef = group.preferredPatchRefs().get(patchIndex);
                if (!context.patchRefs().contains(patchRef)) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_PREFERRED_PATCH_UNKNOWN,
                            path + ".preferredPatchRefs[" + patchIndex + "]", "Unknown D3 patch: " + patchRef);
                }
            }
            validatePlacementPatchRefs(issues, group.placementRelation(), context.patchRefs(), path);
            if (group.requiredStructureRefs().isEmpty()) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_REQUIRED_STRUCTURES_EMPTY,
                        path + ".requiredStructureRefs", "A STRUCTURE group needs at least one required structure.");
            }
            Set<String> uniqueRequiredRefs = new HashSet<>();
            for (String ref : group.requiredStructureRefs()) {
                if (!uniqueRequiredRefs.add(ref)) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                            path + ".requiredStructureRefs", "requiredStructureRefs must be unique: " + ref);
                }
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
            if ("CENTER_SYMMETRIC".equals(catalog.algorithmsByProfileRef().get(group.algorithmProfileRef()))
                    && group.requiredStructureRefs().size() != 1) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_CENTER_SYMMETRIC_REQUIRED_COUNT_INVALID,
                        path + ".requiredStructureRefs",
                        "CENTER_SYMMETRIC requires exactly one center structure; fill structures grow in atomic pairs.");
            }
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
        if (highestPriorityGroupCount != 1) {
            add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_GROUP_PRIORITY_HIGHEST_COUNT_INVALID,
                    "$.groups", "Exactly one group must have the highest priority CORE; pairwise conflicts still compare both groups.");
        }
        if (!Double.isFinite(areaShareSum) || Math.abs(areaShareSum - 1.0) > 1.0e-6) {
            add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_GROUP_AREA_SHARE_INVALID,
                    "$.groups", "All targetAreaShare values must sum to 1.0.");
        }

        java.util.Map<String, CityBlueprint.Group> groupsById = new HashMap<>();
        blueprint.groups().forEach(group -> groupsById.put(group.groupId(), group));
        validatePlacementGroupRefs(issues, blueprint.groups(), groupIds);
        validateArrayCompositions(issues, blueprint.arrayCompositions(), groupsById, catalog);

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
        validateOutdoorPlan(issues, blueprint.outdoorPlan(), groupIds, groupsById,
                context.patchRefs(), catalog);
        return new ValidationResult(issues.isEmpty(), List.copyOf(issues));
    }

    private static void validatePlacementPatchRefs(List<Issue> issues,
                                                   CityBlueprint.PlacementRelation placement,
                                                   Set<String> patchRefs,
                                                   String groupPath) {
        if (placement == null) return;
        String path = groupPath + ".placementRelation";
        boolean patchPlacement = placement.kind() == CityBlueprint.PlacementRelationKind.BETWEEN_PATCHES
                || placement.kind() == CityBlueprint.PlacementRelationKind.ALONG_PATCH_BOUNDARY;
        if (patchPlacement) {
            if (placement.patchRefs().size() != 2 || !placement.groupRefs().isEmpty()
                    || placement.patchRefs().get(0).equals(placement.patchRefs().get(1))) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_PLACEMENT_RELATION_INVALID, path,
                        placement.kind() + " requires two distinct patchRefs and empty groupRefs.");
            }
            for (int index = 0; index < placement.patchRefs().size(); index++) {
                if (!patchRefs.contains(placement.patchRefs().get(index))) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_PREFERRED_PATCH_UNKNOWN,
                            path + ".patchRefs[" + index + "]",
                            "Unknown D3 patch: " + placement.patchRefs().get(index));
                }
            }
        } else if (!placement.patchRefs().isEmpty() || placement.groupRefs().size() != 2
                || placement.groupRefs().get(0).equals(placement.groupRefs().get(1))) {
            add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_PLACEMENT_RELATION_INVALID, path,
                    "BETWEEN_GROUPS requires two distinct groupRefs and empty patchRefs.");
        }
    }

    private static void validatePlacementGroupRefs(List<Issue> issues,
                                                   List<CityBlueprint.Group> groups,
                                                   Set<String> groupIds) {
        for (int index = 0; index < groups.size(); index++) {
            CityBlueprint.Group group = groups.get(index);
            CityBlueprint.PlacementRelation placement = group.placementRelation();
            if (placement == null
                    || placement.kind() != CityBlueprint.PlacementRelationKind.BETWEEN_GROUPS) continue;
            String path = "$.groups[" + index + "].placementRelation.groupRefs";
            for (int refIndex = 0; refIndex < placement.groupRefs().size(); refIndex++) {
                String ref = placement.groupRefs().get(refIndex);
                if (!groupIds.contains(ref) || group.groupId().equals(ref)) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_PLACEMENT_RELATION_INVALID,
                            path + "[" + refIndex + "]",
                            "BETWEEN_GROUPS endpoints must name two other Blueprint groups: " + ref);
                }
            }
        }
    }

    private static void validateArrayCompositions(List<Issue> issues,
                                                  List<CityBlueprint.ArrayComposition> compositions,
                                                  java.util.Map<String, CityBlueprint.Group> groupsById,
                                                  CityBlueprintReferenceCatalog catalog) {
        Set<String> compositionIds = new HashSet<>();
        Set<String> composedGroups = new HashSet<>();
        for (int index = 0; index < compositions.size(); index++) {
            CityBlueprint.ArrayComposition composition = compositions.get(index);
            String path = "$.arrayCompositions[" + index + "]";
            if (!compositionIds.add(composition.compositionId())) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_ARRAY_COMPOSITION_ID_DUPLICATE,
                        path + ".compositionId", "compositionId must be unique: " + composition.compositionId());
            }
            requireRef(issues, catalog.algorithmProfileRefs(), composition.algorithmProfileRef(),
                    CityBlueprintReasonCode.CITY_BLUEPRINT_ALGORITHM_PROFILE_UNKNOWN,
                    path + ".algorithmProfileRef");
            if (composition.memberGroupIds().isEmpty()) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_ARRAY_COMPOSITION_INVALID,
                        path + ".memberGroupIds",
                        "A parent array composition requires at least one member Group.");
            }
            List<String> participants = new ArrayList<>();
            participants.add(composition.centerGroupId());
            participants.addAll(composition.memberGroupIds());
            Set<String> local = new HashSet<>();
            for (String groupId : participants) {
                CityBlueprint.Group group = groupsById.get(groupId);
                if (group == null) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_ARRAY_COMPOSITION_GROUP_UNKNOWN,
                            path, "Unknown array-composition Group: " + groupId);
                    continue;
                }
                if (!local.add(groupId)) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_ARRAY_COMPOSITION_INVALID,
                            path, "A Group cannot occupy two slots in one array composition: " + groupId);
                }
                if (!composedGroups.add(groupId)) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_ARRAY_COMPOSITION_GROUP_REUSED,
                            path, "A Group can belong to only one parent array composition: " + groupId);
                }
            }
            CityBlueprint.Group center = groupsById.get(composition.centerGroupId());
            if (center != null && center.placementRelation() != null
                    && center.placementRelation().kind() == CityBlueprint.PlacementRelationKind.BETWEEN_GROUPS) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_ARRAY_COMPOSITION_INVALID,
                        path + ".centerGroupId",
                        "A parent-array center cannot itself use BETWEEN_GROUPS placement.");
            }
            for (String memberId : composition.memberGroupIds()) {
                CityBlueprint.Group member = groupsById.get(memberId);
                if (member != null && member.placementRelation() != null) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_ARRAY_COMPOSITION_INVALID,
                            path + ".memberGroupIds",
                            "Parent-array members get their origin from the parent and cannot declare placementRelation: "
                                    + memberId);
                }
            }
            String algorithm = catalog.algorithmsByProfileRef().get(composition.algorithmProfileRef());
            if ("CENTER_SYMMETRIC".equals(algorithm) && (composition.memberGroupIds().size() & 1) != 0) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_ARRAY_COMPOSITION_INVALID,
                        path + ".memberGroupIds",
                        "CENTER_SYMMETRIC parent arrays require member Group pairs.");
            }
        }
    }

    private static void validateOutdoorPlan(List<Issue> issues, CityBlueprint.OutdoorPlan plan,
                                            Set<String> groupIds,
                                            java.util.Map<String, CityBlueprint.Group> groupsById,
                                            Set<String> patchRefs,
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
            if (landscape.originMode() == CityBlueprint.LandscapeOriginMode.ATTACHED) {
                if (landscape.owner() == null || landscape.placementDomain() != null
                        || landscape.instanceCount() != 1) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_LANDSCAPE_SOURCE_REQUIRED,
                            path, "ATTACHED requires owner, forbids placementDomain, and fixes instanceCount to 1.");
                } else {
                    CityBlueprint.Group ownerGroup = groupsById.get(landscape.owner().groupId());
                    if (ownerGroup == null || !landscape.owner().groupOwned()
                            && !ownerGroup.requiredStructureRefs()
                            .contains(landscape.owner().requiredStructureRef())) {
                        add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_GROUP_REF_UNKNOWN,
                                path + ".owner", "owner must identify its Group and, when present, a required structure.");
                    }
                }
            } else {
                if (landscape.owner() != null || landscape.placementDomain() == null || landscape.required()) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_LANDSCAPE_SOURCE_REQUIRED,
                            path, "FREE_STANDING forbids owner, requires placementDomain, and must be optional.");
                }
            }
            if (landscape.instanceCount() <= 0 || landscape.parcelCount() <= 0) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID, path,
                        "instanceCount and parcelCount must be positive exact values.");
            }
            if (landscapeProfile != null && (landscape.parcelCount() < landscapeProfile.parcelStyle().parcelCountMin()
                    || landscape.parcelCount() > landscapeProfile.parcelStyle().parcelCountMax())) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                        path + ".parcelCount", "parcelCount is outside the selected Profile range.");
            }
            for (int patchIndex = 0; patchIndex < landscape.preferredPatchRefs().size(); patchIndex++) {
                String ref = landscape.preferredPatchRefs().get(patchIndex);
                if (!patchRefs.contains(ref)) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_PATCH_REF_UNKNOWN,
                            path + ".preferredPatchRefs[" + patchIndex + "]", "Unknown D3 patch: " + ref);
                }
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
