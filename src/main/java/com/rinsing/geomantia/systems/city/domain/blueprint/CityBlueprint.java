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
        List<Relation> relations,
        ProfileRef roadProfile,
        ProfileRef surfaceDetailProfile) {

    public static final String SCHEMA_VERSION = "city_blueprint.v0.4";

    public CityBlueprint {
        groups = List.copyOf(groups);
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

    public enum GroupKind { STRUCTURE, LANDSCAPE }

    public enum GroupPriority { CORE, STANDARD, PERIPHERAL }

    public enum ExtentClass { SMALL, MEDIUM, LARGE }

    public enum DensityClass { SPARSE, BALANCED, DENSE }

    public enum ClusterShape { ORGANIC_COMPACT, GRID, COURTYARD, L_SHAPE, U_SHAPE }

    public enum SideMode { LEFT, RIGHT, BOTH }

    public enum WidthClass { NARROW, MEDIUM, WIDE }

    public enum PreferredPatchZone { CENTER, NORTH, EAST, SOUTH, WEST }

    public enum TerrainPolicy { CONFORM, BALANCED, ASSERTIVE }

    public enum RelationKind { HIERARCHY, ADJACENCY, CONNECTION, BUFFER, DISTANCE, DIRECTION }

    public enum RelationStrength { HARD, SOFT }

    public enum DistancePreference { NONE, NEAR, FAR }

    public enum DirectionPreference { NONE, NORTH, EAST, SOUTH, WEST }
}
