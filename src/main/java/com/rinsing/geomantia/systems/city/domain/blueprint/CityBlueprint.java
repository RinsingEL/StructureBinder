package com.rinsing.geomantia.systems.city.domain.blueprint;

import java.util.List;

/** The complete, coordinate-free result of the single D4 city-design decision. */
public record CityBlueprint(
        String schema,
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

    public static final String SCHEMA = "city_blueprint";

    public CityBlueprint {
        groups = List.copyOf(groups);
        arrayCompositions = List.copyOf(arrayCompositions);
        relations = List.copyOf(relations);
    }

    public record ArtifactRef(String path, String schema, String contentHash) {
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
            List<String> attachedFeatures,
            double targetAreaShare,
            SpaceComposition spaceComposition,
            ExpansionPolicy expansionPolicy,
            BuildingGreeneryPolicy buildingGreeneryPolicy,
            List<WeightedPool> fillPools) {
        public Group(String groupId,
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
                     List<String> attachedFeatures,
                     double targetAreaShare,
                     SpaceComposition spaceComposition,
                     ExpansionPolicy expansionPolicy, BuildingGreeneryPolicy buildingGreeneryPolicy) {
            this(groupId, groupKind, preferredPatchRefs, preferredPatchZone, placementRelation, role,
                    priority, extentClass, densityClass, algorithmProfileRef, terrainPolicy,
                    requiredStructureRefs, fillPoolRef, connectionPlan, compositionProfileRef,
                    attachedFeatures, targetAreaShare, spaceComposition, expansionPolicy,
                    buildingGreeneryPolicy, List.of());
        }

        public Group(String groupId,
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
                     List<String> attachedFeatures,
                     double targetAreaShare,
                     SpaceComposition spaceComposition,
                     ExpansionPolicy expansionPolicy) {
            this(groupId, groupKind, preferredPatchRefs, preferredPatchZone, placementRelation, role,
                    priority, extentClass, densityClass, algorithmProfileRef, terrainPolicy,
                    requiredStructureRefs, fillPoolRef, connectionPlan, compositionProfileRef,
                    attachedFeatures, targetAreaShare, spaceComposition, expansionPolicy,
                    BuildingGreeneryPolicy.none());
        }

        public Group(String groupId,
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
            this(groupId, groupKind, preferredPatchRefs, preferredPatchZone, placementRelation, role, priority,
                    extentClass, densityClass, algorithmProfileRef, terrainPolicy, requiredStructureRefs,
                    fillPoolRef, connectionPlan, compositionProfileRef, attachedFeatures, 0.0,
                    SpaceComposition.defaultUrban(), ExpansionPolicy.defaultPolicy(),
                    BuildingGreeneryPolicy.none());
        }

        public Group {
            fillPools = fillPools == null ? List.of() : List.copyOf(fillPools);
            if ((fillPoolRef == null || fillPoolRef.isBlank()) && !fillPools.isEmpty())
                fillPoolRef = fillPools.get(0).poolRef();
            preferredPatchRefs = List.copyOf(preferredPatchRefs);
            requiredStructureRefs = List.copyOf(requiredStructureRefs);
            attachedFeatures = List.copyOf(attachedFeatures);
            if (spaceComposition == null) spaceComposition = SpaceComposition.defaultUrban();
            if (expansionPolicy == null) expansionPolicy = ExpansionPolicy.defaultPolicy();
            if (buildingGreeneryPolicy == null) buildingGreeneryPolicy = BuildingGreeneryPolicy.none();
        }
    }

    /** Group-level intent only. Per-building geometry is derived before D4 placement commits. */
    public record BuildingGreeneryPolicy(
            GreeneryCoverage coverage,
            GreeneryPatternPreference patternPreference,
            GreeneryDensityPreference densityPreference) {
        public static BuildingGreeneryPolicy none() {
            return new BuildingGreeneryPolicy(GreeneryCoverage.NONE,
                    GreeneryPatternPreference.TEMPLATE_DEFAULT,
                    GreeneryDensityPreference.TEMPLATE_DEFAULT);
        }
    }

    /** AI-declared functional-zone share; geometry is always derived from the GIS review grid. */
    public record SpaceComposition(double buildingShare, double landscapeShare, double openSpaceShare) {
        public SpaceComposition {
            if (!Double.isFinite(buildingShare) || !Double.isFinite(landscapeShare)
                    || !Double.isFinite(openSpaceShare)) {
                throw new IllegalArgumentException("Space composition shares must be finite.");
            }
        }

        public static SpaceComposition defaultUrban() {
            return new SpaceComposition(1.0, 0.0, 0.0);
        }
    }

    /** AI may explicitly stop relation/outward growth; omitted policy defaults to growth enabled. */
    public record ExpansionPolicy(boolean allowOutwardExpansion,
                                  boolean allowRelationConnection,
                                  boolean stopWhenTargetReached) {
        public static ExpansionPolicy defaultPolicy() {
            return new ExpansionPolicy(true, true, true);
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

    public record WeightedPool(String poolRef, double weight) { }

    /** Optional connection-only overrides. Null fields inherit the owning Group setting. */
    public record ConnectionPlan(
            String structurePoolRef,
            String algorithmProfileRef,
            DensityClass densityClass,
            ConnectionParameters parameters,
            List<WeightedPool> structurePools) {
        public ConnectionPlan(String structurePoolRef, String algorithmProfileRef,
                              DensityClass densityClass, ConnectionParameters parameters) {
            this(structurePoolRef, algorithmProfileRef, densityClass, parameters, List.of());
        }
        public ConnectionPlan {
            structurePools = structurePools == null ? List.of() : List.copyOf(structurePools);
            if (structurePoolRef == null && !structurePools.isEmpty()) structurePoolRef = structurePools.get(0).poolRef();
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

    /** A blank structure ref means that the whole function-area Group owns the Landscape. */
    public record LandscapeOwner(String groupId, String requiredStructureRef) {
        public LandscapeOwner {
            requiredStructureRef = requiredStructureRef == null ? "" : requiredStructureRef;
        }

        public boolean groupOwned() {
            return requiredStructureRef.isBlank();
        }
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

    public enum GreeneryCoverage { NONE, SPARSE, BALANCED, LUSH }

    public enum GreeneryPatternPreference { TEMPLATE_DEFAULT, FREEFORM, FIELD_GRID, MIXED }

    public enum GreeneryDensityPreference { TEMPLATE_DEFAULT, LOW, MEDIUM, HIGH }

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
