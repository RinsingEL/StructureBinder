package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record LandUseExpansionResult(
        Map<BlockPoint, Claim> claims,
        Map<String, Integer> claimedBlocksByGroup,
        Map<String, Integer> claimedBlocksByGrowthRegion,
        Map<String, List<BlockPoint>> effectiveSeedPointsByGroup,
        Map<String, ExpansionOrigin> expansionOriginsByGroup,
        int contestedClaimCount,
        int blockedCandidateCount) {

    public LandUseExpansionResult(Map<BlockPoint, Claim> claims,
                                  Map<String, Integer> claimedBlocksByGroup,
                                  Map<String, Integer> claimedBlocksByGrowthRegion,
                                  Map<String, List<BlockPoint>> effectiveSeedPointsByGroup,
                                  int contestedClaimCount,
                                  int blockedCandidateCount) {
        this(claims, claimedBlocksByGroup, claimedBlocksByGrowthRegion, effectiveSeedPointsByGroup, Map.of(),
                contestedClaimCount, blockedCandidateCount);
    }

    public LandUseExpansionResult(Map<BlockPoint, Claim> claims,
                                  Map<String, Integer> claimedBlocksByGroup,
                                  Map<String, Integer> claimedBlocksByGrowthRegion,
                                  int contestedClaimCount,
                                  int blockedCandidateCount) {
        this(claims, claimedBlocksByGroup, claimedBlocksByGrowthRegion, Map.of(),
                contestedClaimCount, blockedCandidateCount);
    }

    public LandUseExpansionResult(Map<BlockPoint, Claim> claims,
                                  Map<String, Integer> claimedBlocksByGroup,
                                  int contestedClaimCount,
                                  int blockedCandidateCount) {
        this(claims, claimedBlocksByGroup, Map.of(), contestedClaimCount, blockedCandidateCount);
    }

    public LandUseExpansionResult {
        claims = Collections.unmodifiableMap(new LinkedHashMap<>(claims));
        claimedBlocksByGroup = Map.copyOf(claimedBlocksByGroup);
        claimedBlocksByGrowthRegion = Map.copyOf(claimedBlocksByGrowthRegion);
        Map<String, List<BlockPoint>> normalizedSeeds = new LinkedHashMap<>();
        (effectiveSeedPointsByGroup == null ? Map.<String, List<BlockPoint>>of()
                : effectiveSeedPointsByGroup).forEach((groupId, points) ->
                normalizedSeeds.put(groupId, List.copyOf(points == null ? List.of() : points)));
        effectiveSeedPointsByGroup = Map.copyOf(normalizedSeeds);
        expansionOriginsByGroup = Map.copyOf(expansionOriginsByGroup == null
                ? Map.of() : expansionOriginsByGroup);
    }

    public record Claim(String groupId, double cumulativeCost) {
    }

    public record ExpansionOrigin(OriginKind kind,
                                  String parentGroupId,
                                  BlockPoint start,
                                  BlockPoint sourceFrontier) {
        public ExpansionOrigin {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(start, "start");
            parentGroupId = parentGroupId == null ? "" : parentGroupId;
            if (kind == OriginKind.ROOT_SOURCE) {
                if (!parentGroupId.isBlank() || sourceFrontier != null) {
                    throw new IllegalArgumentException("LAND_USE_EXPANSION_ROOT_ORIGIN_INVALID");
                }
            } else {
                long expected = kind == OriginKind.PARENT_PARCEL_ROAD_GAP ? 2L : 1L;
                if (parentGroupId.isBlank() || sourceFrontier == null
                        || manhattan(start, sourceFrontier) != expected) {
                    throw new IllegalArgumentException("LAND_USE_EXPANSION_PARENT_ORIGIN_INVALID");
                }
            }
        }

        private static long manhattan(BlockPoint first, BlockPoint second) {
            return Math.abs((long) first.x() - second.x())
                    + Math.abs((long) first.z() - second.z());
        }
    }

    public enum OriginKind {
        ROOT_SOURCE,
        PARENT_PARCEL_INTERFACE,
        PARENT_PARCEL_ROAD_GAP
    }
}
