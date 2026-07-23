package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.Map;

public record LandUseExpansionResult(
        Map<BlockPoint, Claim> claims,
        Map<String, Integer> claimedBlocksByGroup,
        Map<String, Integer> claimedBlocksByGrowthRegion,
        int contestedClaimCount,
        int blockedCandidateCount) {

    public LandUseExpansionResult(Map<BlockPoint, Claim> claims,
                                  Map<String, Integer> claimedBlocksByGroup,
                                  int contestedClaimCount,
                                  int blockedCandidateCount) {
        this(claims, claimedBlocksByGroup, Map.of(), contestedClaimCount, blockedCandidateCount);
    }

    public LandUseExpansionResult {
        claims = Map.copyOf(claims);
        claimedBlocksByGroup = Map.copyOf(claimedBlocksByGroup);
        claimedBlocksByGrowthRegion = Map.copyOf(claimedBlocksByGrowthRegion);
    }

    public record Claim(String groupId, double cumulativeCost) {
    }
}
