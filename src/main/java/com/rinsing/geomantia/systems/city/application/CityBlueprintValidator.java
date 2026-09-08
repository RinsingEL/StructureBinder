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
                    "Blueprint cityId does not match the prepared context. Copy cityId from the active context; do not change the target city to bypass validation.");
        }
        compareRef(issues, blueprint.sourceD3Ref(), context.sourceD3Ref(),
                CityBlueprintReasonCode.CITY_BLUEPRINT_D3_STALE, "$.sourceD3Ref");
        compareRef(issues, blueprint.catalogSnapshotRef(), context.catalogSnapshotRef(),
                CityBlueprintReasonCode.CITY_BLUEPRINT_CATALOG_STALE, "$.catalogSnapshotRef");
        if (blueprint.groups().isEmpty()) {
            add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_GROUPS_EMPTY, "$.groups",
                    "Add at least one STRUCTURE group with requiredStructureRefs and a frozen fill pool, then assign exactly one CORE priority.");
        }

        Set<String> groupIds = new HashSet<>();
        int highestPriorityGroupCount = 0;
        double areaShareSum = 0.0;
        for (int index = 0; index < blueprint.groups().size(); index++) {
            CityBlueprint.Group group = blueprint.groups().get(index);
            String path = "$.groups[" + index + "]";
            if (!groupIds.add(group.groupId())) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_GROUP_ID_DUPLICATE, path + ".groupId",
                        "Rename the duplicate groupId and update references intended for that renamed group; do not remove its buildings. Duplicate: " + group.groupId());
            }
            if (group.groupKind() != CityBlueprint.GroupKind.STRUCTURE) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_GROUP_KIND_UNSUPPORTED, path + ".groupKind",
                        "Set groupKind=STRUCTURE for building groups. Express landscapes in outdoorPlan.landscapes using a frozen landscape profile; do not create a LANDSCAPE building group.");
            }
            if (group.priority() == CityBlueprint.GroupPriority.CORE) highestPriorityGroupCount++;
            if (!Double.isFinite(group.targetAreaShare()) || group.targetAreaShare() <= 0.0
                    || group.targetAreaShare() > 1.0) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_GROUP_AREA_SHARE_INVALID,
                        path + ".targetAreaShare", "Current targetAreaShare=" + group.targetAreaShare() + "; set a finite value greater than 0 and at most 1, then rebalance the group total to 1. Preserve the relative design priorities.");
            }
            areaShareSum += group.targetAreaShare();
            CityBlueprint.SpaceComposition composition = group.spaceComposition();
            double compositionSum = composition.buildingShare() + composition.landscapeShare()
                    + composition.openSpaceShare();
            if (!Double.isFinite(compositionSum) || composition.buildingShare() < 0.0
                    || composition.landscapeShare() < 0.0 || composition.openSpaceShare() < 0.0
                    || Math.abs(compositionSum - 1.0) > 1.0e-6) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_GROUP_SPACE_COMPOSITION_INVALID,
                        path + ".spaceComposition", "Current buildingShare=" + composition.buildingShare() + ", landscapeShare=" + composition.landscapeShare() + ", openSpaceShare=" + composition.openSpaceShare() + "; sum=" + compositionSum + ". Set finite non-negative shares and rebalance these three fields to total 1 within this group.");
            }
            for (int patchIndex = 0; patchIndex < group.preferredPatchRefs().size(); patchIndex++) {
                String patchRef = group.preferredPatchRefs().get(patchIndex);
                if (!context.patchRefs().contains(patchRef)) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_PREFERRED_PATCH_UNKNOWN,
                            path + ".preferredPatchRefs[" + patchIndex + "]", "Choose the intended existing patchRef from the current D3 context rather than inventing one; keep the local algorithm. Unknown D3 patch: " + patchRef);
                }
            }
            validatePlacementPatchRefs(issues, group.placementRelation(), context.patchRefs(), path);
            if (group.requiredStructureRefs().isEmpty()) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_REQUIRED_STRUCTURES_EMPTY,
                        path + ".requiredStructureRefs", "Add at least one requiredStructureRef from the frozen structure catalog; filling pools alone do not declare mandatory buildings.");
            }
            Set<String> uniqueRequiredRefs = new HashSet<>();
            for (String ref : group.requiredStructureRefs()) {
                if (!uniqueRequiredRefs.add(ref)) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                            path + ".requiredStructureRefs", "Keep one copy of each requiredStructureRef in this list; if multiple mandatory copies are intended, express them in separate groups. Duplicate: " + ref);
                }
                if (!catalog.structureRefs().contains(ref)) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_STRUCTURE_REF_UNKNOWN,
                            path + ".requiredStructureRefs", "Replace this reference with the intended building from the frozen structure catalog; do not discard required content. Unknown structureRef: " + ref);
                }
            }
            if (group.fillPools().isEmpty()) requireRef(issues, catalog.fillPoolRefs(), group.fillPoolRef(),
                    CityBlueprintReasonCode.CITY_BLUEPRINT_FILL_POOL_UNKNOWN, path + ".fillPoolRef");
            for (int poolIndex = 0; poolIndex < group.fillPools().size(); poolIndex++)
                requireRef(issues, catalog.fillPoolRefs(), group.fillPools().get(poolIndex).poolRef(),
                        CityBlueprintReasonCode.CITY_BLUEPRINT_FILL_POOL_UNKNOWN,
                        path + ".fillPools[" + poolIndex + "].poolRef");
            requireRef(issues, catalog.algorithmProfileRefs(), group.algorithmProfileRef(),
                    CityBlueprintReasonCode.CITY_BLUEPRINT_ALGORITHM_PROFILE_UNKNOWN,
                    path + ".algorithmProfileRef");
            if ("CENTER_SYMMETRIC".equals(catalog.algorithmsByProfileRef().get(group.algorithmProfileRef()))
                    && group.requiredStructureRefs().size() != 1) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_CENTER_SYMMETRIC_REQUIRED_COUNT_INVALID,
                        path + ".requiredStructureRefs",
                        "Current requiredStructureRefs count=" + group.requiredStructureRefs().size() + "; CENTER_SYMMETRIC requires exactly 1 center structure. Keep this algorithm: select one center and move other mandatory buildings into separate groups. Only optional buildings may become fill-pool candidates; changing the algorithm is an alternative only if multiple mandatory buildings must stay in this group.");
            }
            validateConnectionPlan(issues, group, catalog, path);
            requireRef(issues, catalog.compositionProfileRefs(), group.compositionProfileRef(),
                    CityBlueprintReasonCode.CITY_BLUEPRINT_COMPOSITION_PROFILE_UNKNOWN,
                    path + ".compositionProfileRef");
            if (!group.attachedFeatures().isEmpty()) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_ATTACHED_FEATURE_UNSUPPORTED,
                        path + ".attachedFeatures",
                        "Set attachedFeatures=[]; represent intended attached landscapes in outdoorPlan.landscapes with originMode=ATTACHED and an owner group.");
            }
        }
        if (highestPriorityGroupCount != 1) {
            add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_GROUP_PRIORITY_HIGHEST_COUNT_INVALID,
                    "$.groups", "Current CORE count=" + highestPriorityGroupCount + "; required count=1. Choose one group to retain or assign CORE; lower any other CORE priorities to STANDARD or PERIPHERAL. Preserve their buildings and algorithms.");
        }
        if (!Double.isFinite(areaShareSum) || Math.abs(areaShareSum - 1.0) > 1.0e-6) {
            add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_GROUP_AREA_SHARE_INVALID,
                    "$.groups", "Current sum(targetAreaShare)=" + areaShareSum + "; required total=1. Normalize positive shares by their total, preserving relative proportions; do not change algorithms or remove groups to fix the sum.");
        }

        java.util.Map<String, CityBlueprint.Group> groupsById = new HashMap<>();
        blueprint.groups().forEach(group -> groupsById.put(group.groupId(), group));
        validatePlacementGroupRefs(issues, blueprint.groups(), groupIds);
        validateArrayCompositions(issues, blueprint.arrayCompositions(), groupsById, catalog);

        Set<String> relatedGroupIds = new HashSet<>();
        for (int index = 0; index < blueprint.relations().size(); index++) {
            CityBlueprint.Relation relation = blueprint.relations().get(index);
            String path = "$.relations[" + index + "]";
            if (relation.strength() == CityBlueprint.RelationStrength.HARD
                    && relation.relationKind() == CityBlueprint.RelationKind.DIRECTION) {
                for (int earlier = 0; earlier < index; earlier++) {
                    CityBlueprint.Relation previous = blueprint.relations().get(earlier);
                    if (previous.strength() != CityBlueprint.RelationStrength.HARD
                            || previous.relationKind() != CityBlueprint.RelationKind.DIRECTION) continue;
                    boolean same = previous.fromGroupId().equals(relation.fromGroupId())
                            && previous.toGroupId().equals(relation.toGroupId());
                    boolean reversed = previous.fromGroupId().equals(relation.toGroupId())
                            && previous.toGroupId().equals(relation.fromGroupId());
                    var direction = reversed ? opposite(previous.directionPreference()) : previous.directionPreference();
                    if ((same || reversed) && direction != CityBlueprint.DirectionPreference.NONE
                            && opposite(direction) == relation.directionPreference())
                        add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_RELATION_CONTRADICTION,
                                path + ".directionPreference", "Conflicts with $.relations[" + earlier
                                        + "].directionPreference: the same pair cannot satisfy opposite HARD directions. "
                                        + "Revise one of these two relations; preserve the building groups.");
                }
            }
            relatedGroupIds.add(relation.fromGroupId());
            relatedGroupIds.add(relation.toGroupId());
            if (!groupIds.contains(relation.fromGroupId()) || !groupIds.contains(relation.toGroupId())) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_RELATION_ENDPOINT_UNKNOWN, path,
                        "Current endpoints=" + relation.fromGroupId() + " -> " + relation.toGroupId() + "; existing groupIds=" + groupIds + ". Correct only the invalid endpoint IDs, preserving the intended relation.");
            }
            if (relation.fromGroupId().equals(relation.toGroupId())) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_RELATION_SELF_REFERENCE, path,
                        "Both endpoints are " + relation.fromGroupId() + ". Choose the intended other existing group, or remove this redundant self-relation; do not remove the building group.");
            }
            boolean distance = relation.relationKind() == CityBlueprint.RelationKind.DISTANCE;
            boolean hasDistance = relation.distancePreference() != CityBlueprint.DistancePreference.NONE;
            if (distance != hasDistance) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_RELATION_DISTANCE_INVALID,
                        path + ".distancePreference",
                        "Current relationKind=" + relation.relationKind() + ", distancePreference=" + relation.distancePreference() + ". " + (distance ? "Keep DISTANCE and select NEAR or FAR according to the intended separation." : "Keep this relationKind and set distancePreference=NONE. Add a separate DISTANCE relation only if distance is also intended."));
            }
            boolean directional = relation.relationKind() == CityBlueprint.RelationKind.DIRECTION;
            boolean hasDirection = relation.directionPreference() != CityBlueprint.DirectionPreference.NONE;
            if (directional != hasDirection) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_RELATION_DIRECTION_INVALID,
                        path + ".directionPreference",
                        "Current relationKind=" + relation.relationKind() + ", directionPreference=" + relation.directionPreference() + ". " + (directional ? "Keep DIRECTION and select NORTH, SOUTH, EAST or WEST." : "Keep this relationKind and set directionPreference=NONE."));
            }
        }
        // Nearby relation-enabled groups may connect automatically after their initial arrays exist.
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
                        placement.kind() + " requires two distinct patchRefs and empty groupRefs. Keep the relation kind: choose two different current D3 patch refs and set groupRefs=[].");
            }
            for (int index = 0; index < placement.patchRefs().size(); index++) {
                if (!patchRefs.contains(placement.patchRefs().get(index))) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_PREFERRED_PATCH_UNKNOWN,
                            path + ".patchRefs[" + index + "]",
                            "Choose the intended existing patchRef from the current D3 context rather than inventing one; keep the local algorithm. Unknown D3 patch: " + placement.patchRefs().get(index));
                }
            }
        } else if (!placement.patchRefs().isEmpty() || placement.groupRefs().size() != 2
                || placement.groupRefs().get(0).equals(placement.groupRefs().get(1))) {
            add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_PLACEMENT_RELATION_INVALID, path,
                    "Keep BETWEEN_GROUPS: choose two different existing groupRefs other than this group, and set patchRefs=[].");
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
                            "Replace this endpoint with another existing groupId, excluding this group and the other endpoint. Invalid BETWEEN_GROUPS endpoint: " + ref);
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
                        path + ".compositionId", "Rename only the duplicated compositionId to a unique ID; preserve its intended member groups. Duplicate: " + composition.compositionId());
            }
            requireRef(issues, catalog.algorithmProfileRefs(), composition.algorithmProfileRef(),
                    CityBlueprintReasonCode.CITY_BLUEPRINT_ALGORITHM_PROFILE_UNKNOWN,
                    path + ".algorithmProfileRef");
            if (composition.memberGroupIds().isEmpty()) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_ARRAY_COMPOSITION_INVALID,
                        path + ".memberGroupIds",
                        "Add an existing groupId to memberGroupIds, distinct from centerGroupId. CENTER_SYMMETRIC requires an even member count.");
            }
            List<String> participants = new ArrayList<>();
            participants.add(composition.centerGroupId());
            participants.addAll(composition.memberGroupIds());
            Set<String> local = new HashSet<>();
            for (String groupId : participants) {
                CityBlueprint.Group group = groupsById.get(groupId);
                if (group == null) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_ARRAY_COMPOSITION_GROUP_UNKNOWN,
                            path, "Correct this participant ID to an existing blueprint groupId; do not invent a group reference. Unknown participant: " + groupId);
                    continue;
                }
                if (!local.add(groupId)) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_ARRAY_COMPOSITION_INVALID,
                            path, "Keep this group in only one slot: either centerGroupId or a single memberGroupIds entry. Preserve the building group. Repeated group: " + groupId);
                }
                if (!composedGroups.add(groupId)) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_ARRAY_COMPOSITION_GROUP_REUSED,
                            path, "Choose one parent composition for this group and remove its membership from the others, preserving the group. Reused group: " + groupId);
                }
            }
            CityBlueprint.Group center = groupsById.get(composition.centerGroupId());
            if (center != null && center.placementRelation() != null
                    && center.placementRelation().kind() == CityBlueprint.PlacementRelationKind.BETWEEN_GROUPS) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_ARRAY_COMPOSITION_INVALID,
                        path + ".centerGroupId",
                        "Keep this parent-array center by removing its BETWEEN_GROUPS placementRelation; if the between-groups placement is essential, choose a different centerGroupId.");
            }
            for (String memberId : composition.memberGroupIds()) {
                CityBlueprint.Group member = groupsById.get(memberId);
                if (member != null && member.placementRelation() != null) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_ARRAY_COMPOSITION_INVALID,
                            path + ".memberGroupIds",
                            "To retain parent membership, remove this member group placementRelation. If independent placement is essential, remove its parent membership instead, preserving the group: "
                                    + memberId);
                }
            }
            String algorithm = catalog.algorithmsByProfileRef().get(composition.algorithmProfileRef());
            if ("CENTER_SYMMETRIC".equals(algorithm) && (composition.memberGroupIds().size() & 1) != 0) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_ARRAY_COMPOSITION_INVALID,
                        path + ".memberGroupIds",
                        "Current memberGroupIds count=" + composition.memberGroupIds().size() + "; CENTER_SYMMETRIC requires an even count. Keep the algorithm by adding the intended matching group, or remove one group from this composition while preserving it in the blueprint.");
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
                    "Select foundationProfileRef from the current frozen foundationProfiles; preserve the intended terrain treatment. Unknown foundation profile: " + plan.foundationProfileRef());
        }
        if (plan.mode() == CityBlueprint.OutdoorMode.PRESERVE) {
            if (!plan.spatialGrounds().isEmpty() || !plan.landscapes().isEmpty()) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_MODE_INVALID,
                        "$.outdoorPlan", "Keep PRESERVE by setting spatialGrounds=[] and landscapes=[]; only choose GENERATE if you intend new outdoor surfaces or landscapes.");
            }
            return;
        }

        Set<String> coveredGroups = new HashSet<>();
        for (int index = 0; index < plan.spatialGrounds().size(); index++) {
            CityBlueprint.SpatialGround ground = plan.spatialGrounds().get(index);
            String path = "$.outdoorPlan.spatialGrounds[" + index + "]";
            if (!groupIds.contains(ground.sourceGroupId())) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_GROUP_REF_UNKNOWN,
                        path + ".sourceGroupId", "Correct this ID to an existing STRUCTURE groupId, preserving the intended ownership. Unknown group: " + ground.sourceGroupId());
            } else if (!coveredGroups.add(ground.sourceGroupId())) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_GROUND_COVERAGE_INVALID,
                        path + ".sourceGroupId", "Duplicate SpatialGround for sourceGroupId=" + ground.sourceGroupId() + ". Keep exactly one ground entry for this group, preserving the group itself.");
            }
        }
        if (!coveredGroups.equals(groupIds)) {
            add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_GROUND_COVERAGE_INVALID,
                    "$.outdoorPlan.spatialGrounds",
                    "Expected sourceGroupIds=" + groupIds + "; covered=" + coveredGroups + ". Add missing spatialGrounds and remove duplicate entries so each structure group has exactly one; keep building groups intact.");
        }

        Set<String> landscapeIds = new HashSet<>();
        for (int index = 0; index < plan.landscapes().size(); index++) {
            CityBlueprint.Landscape landscape = plan.landscapes().get(index);
            String path = "$.outdoorPlan.landscapes[" + index + "]";
            if (!landscapeIds.add(landscape.landscapeId())) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_LANDSCAPE_ID_DUPLICATE,
                        path + ".landscapeId", "Rename only the duplicated landscapeId to a unique ID; preserve its landscape settings. Duplicate: " + landscape.landscapeId());
            }
            CityBlueprintReferenceCatalog.LandscapeProfile landscapeProfile =
                    catalog.landscapeProfiles().get(landscape.landscapeProfileRef());
            if (landscapeProfile == null) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_LANDSCAPE_PROFILE_UNKNOWN,
                        path + ".landscapeProfileRef",
                        "Choose a matching landscapeProfileRef from the frozen landscapeProfiles instead of inventing a profile. Unknown landscape profile: " + landscape.landscapeProfileRef());
            }
            if (landscape.originMode() == CityBlueprint.LandscapeOriginMode.ATTACHED) {
                if (landscape.owner() == null || landscape.placementDomain() != null
                        || landscape.instanceCount() != 1) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_LANDSCAPE_SOURCE_REQUIRED,
                            path, "Current ownerPresent=" + (landscape.owner() != null) + ", placementDomainPresent=" + (landscape.placementDomain() != null) + ", instanceCount=" + landscape.instanceCount() + ". Keep ATTACHED: supply owner.groupId, remove placementDomain and set instanceCount=1. For multiple parcels, adjust parcelCount within the selected profile range.");
                } else {
                    CityBlueprint.Group ownerGroup = groupsById.get(landscape.owner().groupId());
                    if (ownerGroup == null || !landscape.owner().groupOwned()
                            && !ownerGroup.requiredStructureRefs()
                            .contains(landscape.owner().requiredStructureRef())) {
                        add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_GROUP_REF_UNKNOWN,
                                path + ".owner", "Set owner.groupId to an existing group; if owner.requiredStructureRef is present it must appear in that group requiredStructureRefs. Omit requiredStructureRef only when ownership is intentionally group-wide.");
                    }
                }
            } else {
                if (landscape.owner() != null || landscape.placementDomain() == null || landscape.required()) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_LANDSCAPE_SOURCE_REQUIRED,
                            path, "Current ownerPresent=" + (landscape.owner() != null) + ", placementDomainPresent=" + (landscape.placementDomain() != null) + ", required=" + landscape.required() + ". Keep FREE_STANDING: remove owner, supply placementDomain using the current schema, and set required=false. If this landscape is mandatory or belongs to a group, explicitly choose ATTACHED with owner.groupId and instanceCount=1, removing placementDomain.");
                }
            }
            if (landscape.instanceCount() <= 0 || landscape.parcelCount() <= 0) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID, path,
                        "Current instanceCount=" + landscape.instanceCount() + ", parcelCount=" + landscape.parcelCount() + ". Increase non-positive counts to positive integers; ATTACHED requires instanceCount=1 and parcelCount must remain within the profile range.");
            }
            if (landscapeProfile != null && (landscape.parcelCount() < landscapeProfile.parcelStyle().parcelCountMin()
                    || landscape.parcelCount() > landscapeProfile.parcelStyle().parcelCountMax())) {
                measured(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                        path + ".parcelCount", "parcelCount", landscape.parcelCount(),
                        landscapeProfile.parcelStyle().parcelCountMin(), landscapeProfile.parcelStyle().parcelCountMax(),
                        "Adjust this parcelCount to the author's inclusive range; preserve other landscapes.");
            }
            for (int patchIndex = 0; patchIndex < landscape.preferredPatchRefs().size(); patchIndex++) {
                String ref = landscape.preferredPatchRefs().get(patchIndex);
                if (!patchRefs.contains(ref)) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_PATCH_REF_UNKNOWN,
                            path + ".preferredPatchRefs[" + patchIndex + "]", "Choose the intended existing patchRef from the current D3 context rather than inventing one; keep the local algorithm. Unknown D3 patch: " + ref);
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
                    "Add at least one fillSelection.variants entry using a compatible frozen fillProfileRef, its declared roleShares, and positive selectionWeight.");
            return;
        }
        for (int index = 0; index < selection.variants().size(); index++) {
            CityBlueprint.FillVariant variant = selection.variants().get(index);
            String variantPath = path + ".variants[" + index + "]";
            CityBlueprintReferenceCatalog.LandscapeFillProfile profile =
                    catalog.landscapeFillProfiles().get(variant.fillProfileRef());
            if (profile == null) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                        variantPath + ".fillProfileRef", "Choose fillProfileRef from the frozen landscapeFillProfiles compatible with this landscape type; do not change landscape purpose to fix a reference typo. Unknown fill profile: "
                                + variant.fillProfileRef());
                continue;
            }
            if (landscapeProfile != null
                    && !profile.compatibleLandscapeTypes().contains(landscapeProfile.landscapeType())) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                        variantPath + ".fillProfileRef", "Selected fill profile is incompatible with landscape type. Choose another frozen fillProfileRef whose compatibleLandscapeTypes includes the current type; preserve the landscape profile. Current type: "
                                + landscapeProfile.landscapeType() + ".");
            }
            if (!Double.isFinite(variant.selectionWeight()) || variant.selectionWeight() <= 0.0) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                        variantPath + ".selectionWeight", "Current selectionWeight=" + variant.selectionWeight() + ". Replace with a finite positive weight (for example 1); these are relative weights, not shares that must sum to 1.");
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
                            sharePath + ".roleRef", "Current roleRef=" + share.roleRef() + "; choose one of the selected fill profile roles " + profile.roles().keySet() + ". Keep the profile if it matches the intended landscape.");
                } else if (!role.allowedGrowthForms().contains(share.growthForm())) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                            sharePath + ".growthForm",
                            "Current growthForm=" + share.growthForm() + "; select one of " + role.allowedGrowthForms() + " for this role, preserving the selected fill profile.");
                } else if (!Double.isFinite(share.targetShare()) || share.targetShare() <= 0.0
                        || share.targetShare() >= 1.0) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                            sharePath + ".targetShare", "Current targetShare=" + share.targetShare() + "; set a finite value strictly between 0 and 1, then rebalance this variant to total 1 within authored role ranges.");
                }
                shareSum += share.targetShare();
                aggregateShares.merge(share.roleRef(), share.targetShare(), Double::sum);
            }
            if (variant.roleShares().isEmpty() || !roleRefs.contains(profile.primaryRoleRef())) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                        variantPath + ".roleShares", "Add a roleShares stage for primaryRoleRef=" + profile.primaryRoleRef() + ", preserving existing intended stages and rebalancing their shares to 1.");
            }
            if (!roleRefs.equals(profile.roles().keySet())) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                        variantPath + ".roleShares",
                        "Current roles=" + roleRefs + "; required roles=" + profile.roles().keySet() + ". Include every role: add missing declared roles and correct undeclared roles, then rebalance shares within the profile limits.");
            }
            if (!Double.isFinite(shareSum) || Math.abs(shareSum - 1.0) > 1.0e-6) {
                measured(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                        variantPath + ".roleShares", "sum(targetShare)", shareSum, 1.0, 1.0,
                        "Adjust these stage shares together to sum to 1; preserve the authored role ranges. Sum tolerance is 0.000001.");
            }
            for (CityBlueprintReferenceCatalog.FillRole role : profile.roles().values()) {
                double aggregate = aggregateShares.getOrDefault(role.roleRef(), 0.0);
                if (!Double.isFinite(aggregate) || aggregate < role.minShare() || aggregate > role.maxShare()) {
                    measured(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                            variantPath + ".roleShares", "sum(targetShare where roleRef=" + role.roleRef() + ")",
                            aggregate, role.minShare(), role.maxShare(),
                            "Adjust only stages for this role and rebalance the local total to 1; do not change other districts.");
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
                            "Current contentRef=" + weight.contentRef() + ". Merge duplicate content weights into one entry; for an unknown ref choose from the selected fill profile allowedContentRefs, without inventing a content ID.");
                }
                if (!Double.isFinite(weight.weight()) || weight.weight() <= 0.0) {
                    add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_REFERENCE_INVALID,
                            weightPath + ".weight", "Current weight=" + weight.weight() + ". Replace with a finite positive weight (for example 1), preserving the desired relative content frequencies.");
                }
            }
        }
    }

    private static void validateGroupList(List<Issue> issues, List<String> refs, Set<String> groupIds,
                                          String path) {
        for (int index = 0; index < refs.size(); index++) {
            if (!groupIds.contains(refs.get(index))) {
                add(issues, CityBlueprintReasonCode.CITY_BLUEPRINT_OUTDOOR_GROUP_REF_UNKNOWN,
                        path + "[" + index + "]", "Correct this ID to an existing STRUCTURE groupId, preserving the intended ownership. Unknown group: " + refs.get(index));
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
                        path + "[" + index + "]", "Replace unknown or self groupRefs with the intended other existing groupIds; remove duplicate references without deleting groups.");
            }
        }
    }

    private static void validateConnectionPlan(List<Issue> issues, CityBlueprint.Group group,
                                               CityBlueprintReferenceCatalog catalog, String path) {
        CityBlueprint.ConnectionPlan plan = group.connectionPlan();
        if (plan == null) return;
        for (int poolIndex = 0; poolIndex < plan.structurePools().size(); poolIndex++)
            requireRef(issues, catalog.fillPoolRefs(), plan.structurePools().get(poolIndex).poolRef(),
                    CityBlueprintReasonCode.CITY_BLUEPRINT_FILL_POOL_UNKNOWN,
                    path + ".connectionPlan.structurePools[" + poolIndex + "].poolRef");
        if (plan.structurePoolRef() != null && plan.structurePools().isEmpty()) {
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
                            ? "Keep the current cluster algorithm: remove sideMode, stagger and widthClass; only clusterShape is accepted here."
                            : "Keep LINEAR: remove clusterShape; configure only sideMode, stagger and widthClass here.");
        }
    }

    private static void compareRef(List<Issue> issues, CityBlueprint.ArtifactRef actual,
                                   CityBlueprint.ArtifactRef expected, CityBlueprintReasonCode reason, String path) {
        if (!actual.equals(expected)) add(issues, reason, path, "Current reference=" + actual + "; expected=" + expected + ". Copy the complete expected reference from the current context; do not fabricate hashes or change the city design.");
    }

    private static void requireRef(List<Issue> issues, Set<String> allowed, String ref,
                                   CityBlueprintReasonCode reason, String path) {
        if (!allowed.contains(ref)) add(issues, reason, path, "Unknown catalog reference: " + ref + ". Replace this field with a matching ref from the current frozen catalog for this category; do not invent IDs or switch array algorithms solely to fix a reference typo.");
    }

    private static void add(List<Issue> issues, CityBlueprintReasonCode reason, String path, String message) {
        issues.add(new Issue(reason, path, message));
    }

    private static CityBlueprint.DirectionPreference opposite(CityBlueprint.DirectionPreference value) {
        return switch (value) {
            case NORTH -> CityBlueprint.DirectionPreference.SOUTH;
            case SOUTH -> CityBlueprint.DirectionPreference.NORTH;
            case EAST -> CityBlueprint.DirectionPreference.WEST;
            case WEST -> CityBlueprint.DirectionPreference.EAST;
            case NONE -> CityBlueprint.DirectionPreference.NONE;
        };
    }

    private static void measured(List<Issue> issues, CityBlueprintReasonCode reason, String path,
                                 String measurement, double actual, double minimum, double maximum, String instruction) {
        JsonObject constraint = new JsonObject();
        constraint.addProperty("measurement", measurement);
        if (Double.isFinite(actual)) constraint.addProperty("actual", actual);
        else constraint.addProperty("actualNonFinite", true);
        constraint.addProperty("minimumInclusive", minimum);
        constraint.addProperty("maximumInclusive", maximum);
        String direction = !Double.isFinite(actual) ? "Replace the non-finite value. " : actual < minimum ? "Increase this measurement. " : actual > maximum ? "Decrease this measurement. " : "";
        instruction = direction + instruction;
        constraint.addProperty("instruction", instruction);
        issues.add(new Issue(reason, path, measurement + " is " + actual + "; required range ["
                + minimum + ", " + maximum + "]. " + instruction, constraint));
    }

    public record ExpectedContext(String cityId, CityBlueprint.ArtifactRef sourceD3Ref,
                                  CityBlueprint.ArtifactRef catalogSnapshotRef, Set<String> patchRefs) {
        public ExpectedContext {
            patchRefs = Set.copyOf(patchRefs);
        }
    }

    public record Issue(CityBlueprintReasonCode reasonCode, String fieldPath, String message, JsonObject constraint) {
        public Issue(CityBlueprintReasonCode reasonCode, String fieldPath, String message) {
            this(reasonCode, fieldPath, message, null);
        }
        public JsonObject asJson() {
            JsonObject object = new JsonObject();
            object.addProperty("reasonCode", reasonCode.name());
            object.addProperty("fieldPath", fieldPath);
            object.addProperty("message", message);
            if (constraint != null) object.add("constraint", constraint.deepCopy());
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
