package com.rinsing.geomantia.systems.city.domain.blueprint;

import java.util.List;

/** The complete, coordinate-free result of the single D4 city-design decision. */
public record CityBlueprint(
        String schemaVersion,
        String cityId,
        ArtifactRef sourceD3Ref,
        ArtifactRef catalogSnapshotRef,
        long generationSeed,
        DesignIntent designIntent,
        ProfileRef styleProfile,
        List<Group> groups,
        List<ArrayComposition> arrayCompositions,
        List<Relation> relations,
        ProfileRef roadProfile,
        ProfileRef surfaceDetailProfile,
        OutdoorPlan outdoorPlan) {

    public static final String SCHEMA_VERSION = "city_blueprint.v0.11";

    public CityBlueprint {
        groups = List.copyOf(groups);
        arrayCompositions = List.copyOf(arrayCompositions);
        relations = List.copyOf(relations);
    }

    public record ArtifactRef(String path, String schemaVersion, String contentHash) {
    }

    public record DesignIntent(String cityIdentity, String theme, List<String> functionalRoles) {
        public DesignIntent {
            functionalRoles = List.copyOf(functionalRoles);
        }
    }

    public record ProfileRef(String profileRef) {
    }

    public record Group(
            String groupId,
            GroupKind groupKind,
            List<String> preferredPatchRefs,
            PreferredPatchZone preferredPatchZone,
            PlacementRelation placementRelation,
            String role,
            GroupPriority priority,
            ExtentClass extentClass,
            DensityClass densityClass,
            String algorithmProfileRef,
            TerrainPolicy terrainPolicy,
            List<String> requiredStructureRefs,
            String fillPoolRef,
            ConnectionPlan connectionPlan,
            String compositionProfileRef,
            List<String> attachedFeatures) {
        public Group {
            preferredPatchRefs = List.copyOf(preferredPatchRefs);
            requiredStructureRefs = List.copyOf(requiredStructureRefs);
            attachedFeatures = List.copyOf(attachedFeatures);
        }
    }

    /** Places a whole Group array from patch or already-built Group relationships, never coordinates. */
    public record PlacementRelation(
            PlacementRelationKind kind,
            List<String> patchRefs,
            List<String> groupRefs) {
        public PlacementRelation {
            patchRefs = List.copyOf(patchRefs);
            groupRefs = List.copyOf(groupRefs);
        }
    }

    /** A parent array whose members are complete Group arrays with independent structure semantics. */
    public record ArrayComposition(
            String compositionId,
            String algorithmProfileRef,
            String centerGroupId,
            List<String> memberGroupIds) {
        public ArrayComposition {
            memberGroupIds = List.copyOf(memberGroupIds);
        }
    }

    /** Optional connection-only overrides. Null fields inherit the owning Group setting. */
    public record ConnectionPlan(
            String structurePoolRef,
            String algorithmProfileRef,
            DensityClass densityClass,
            ConnectionParameters parameters) {
        public ConnectionPlan {
            parameters = parameters == null ? ConnectionParameters.empty() : parameters;
        }
    }

    /** Semantic controls only; block spacing remains a program-derived value. */
    public record ConnectionParameters(
            ClusterShape clusterShape,
            SideMode sideMode,
            Boolean stagger,
            WidthClass widthClass) {
        public static ConnectionParameters empty() {
            return new ConnectionParameters(null, null, null, null);
        }

        public boolean emptyParameters() {
            return clusterShape == null && sideMode == null && stagger == null && widthClass == null;
        }
    }

    public record Relation(
            String fromGroupId,
            String toGroupId,
            RelationKind relationKind,
            RelationStrength strength,
            DistancePreference distancePreference,
            DirectionPreference directionPreference) {
    }

    public record OutdoorPlan(
            OutdoorMode mode,
            EnvelopeProfile envelopeProfile,
            String foundationProfileRef,
            List<SpatialGround> spatialGrounds,
            List<Landscape> landscapes) {
        public OutdoorPlan {
            spatialGrounds = List.copyOf(spatialGrounds);
            landscapes = List.copyOf(landscapes);
        }
    }

    /** One shared outdoor-space system owned by a whole structure group, never by one building. */
    public record SpatialGround(
            String sourceGroupId,
            SharedSpaceType sharedSpaceType,
            SpatialHierarchy hierarchyLevel,
            OutdoorMembership membership) {
    }

    public record Landscape(
            String landscapeId,
            String landscapeProfileRef,
            LandscapePurpose purpose,
            LandscapeOriginMode originMode,
            LandscapeOwner owner,
            LandscapePlacementDomain placementDomain,
            int instanceCount,
            int parcelCount,
            List<String> preferredPatchRefs,
            TerrainPolicy terrainPolicy,
            boolean required,
            FillSelection fillSelection) {
        public Landscape {
            preferredPatchRefs = List.copyOf(preferredPatchRefs);
        }
    }

    public record LandscapeOwner(String groupId, String requiredStructureRef) {
    }

    /** AI-selected semantic fill variants; geometry and block materials remain catalog-owned. */
    public record FillSelection(List<FillVariant> variants) {
        public FillSelection {
            variants = List.copyOf(variants);
        }
    }

    public record FillVariant(
            String fillProfileRef,
            double selectionWeight,
            List<RoleShare> roleShares,
            List<ContentWeight> contentWeights) {
        public FillVariant {
            roleShares = List.copyOf(roleShares);
            contentWeights = List.copyOf(contentWeights);
        }
    }

    public record RoleShare(String roleRef, RegionGrowthForm growthForm, double targetShare) {
    }

    public record ContentWeight(String contentRef, double weight) {
    }

    public enum GroupKind { STRUCTURE, LANDSCAPE }

    public enum GroupPriority { CORE, STANDARD, PERIPHERAL }

    public enum ExtentClass { SMALL, MEDIUM, LARGE }

    public enum DensityClass { SPARSE, BALANCED, DENSE }

    public enum ClusterShape { ORGANIC_COMPACT, GRID, COURTYARD, L_SHAPE, U_SHAPE }

    public enum SideMode { LEFT, RIGHT, BOTH }

    public enum WidthClass { NARROW, MEDIUM, WIDE }

    public enum PreferredPatchZone { CENTER, NORTH, EAST, SOUTH, WEST }

    public enum PlacementRelationKind { BETWEEN_PATCHES, ALONG_PATCH_BOUNDARY, BETWEEN_GROUPS }

    public enum TerrainPolicy { CONFORM, BALANCED, ASSERTIVE }

    public enum RelationKind { HIERARCHY, ADJACENCY, CONNECTION, BUFFER, DISTANCE, DIRECTION }

    public enum RelationStrength { HARD, SOFT }

    public enum DistancePreference { NONE, NEAR, FAR }

    public enum DirectionPreference { NONE, NORTH, EAST, SOUTH, WEST }

    public enum OutdoorMode { GENERATE, PRESERVE }

    public enum EnvelopeProfile { COMPACT, BALANCED, LOOSE }

    public enum SharedSpaceType { CIVIC_SQUARE, MARKET_STREET, RESIDENTIAL_COURT, FARMSTEAD, GENERAL_URBAN }

    public enum SpatialHierarchy { PRIMARY, SECONDARY, LOCAL }

    public enum GrowthBias { BALANCED, AWAY_FROM_REFERENCE, TOWARD_REFERENCE }

    public enum OutdoorMembership { URBAN, LANDSCAPE }

    public enum OutdoorIntensity { LOW, MEDIUM, HIGH }

    public enum LandscapeContinuity { CONTINUOUS, MULTI_PARCEL, PATCHY }

    public enum LandscapeGrowthRelation { AROUND_SOURCE, AWAY_FROM_REFERENCE, TOWARD_WATER, ALONG_WATER }

    public enum LandscapePurpose { FUNCTIONAL, COMPOSITIONAL, AMBIENT }

    public enum LandscapeOriginMode { ATTACHED, FREE_STANDING }

    public enum LandscapePlacementDomain { URBAN_RESIDUAL, FOUNDATION_EDGE, BETWEEN_GROUPS, ALONG_WATER }

    /** Frontier-expansion bias only; never a fixed geometry or mask. */
    public enum RegionGrowthForm { PATCH, CORRIDOR }

}
