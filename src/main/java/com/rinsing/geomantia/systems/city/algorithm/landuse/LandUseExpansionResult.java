package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.Map;

public record LandUseExpansionResult(
        Map<BlockPoint, Claim> claims,
        Map<String, Integer> claimedBlocksByGroup,
        int contestedClaimCount,
        int blockedCandidateCount) {

    public LandUseExpansionResult {
        claims = Map.copyOf(claims);
        claimedBlocksByGroup = Map.copyOf(claimedBlocksByGroup);
    }

    public record Claim(String groupId, double cumulativeCost) {
    }
}
