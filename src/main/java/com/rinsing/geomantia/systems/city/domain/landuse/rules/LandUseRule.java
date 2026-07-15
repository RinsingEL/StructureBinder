package com.rinsing.geomantia.systems.city.domain.landuse.rules;

import com.rinsing.geomantia.systems.city.domain.landuse.BoundaryPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.VegetationPolicy;

import java.util.List;

public record LandUseRule(
        String ruleRef,
        String landUseType,
        List<String> semanticTerms,
        double footprintMultiplier,
        int extraAreaBlocks,
        int minAreaBlocks,
        int maxAreaBlocks,
        double actionBudget,
        double baseStepCost,
        double slopeCost,
        double reliefCost,
        double waterCost,
        double forestAffinity,
        double competitionWeight,
        boolean mergeSameType,
        SurfacePolicy surfacePolicy,
        VegetationPolicy vegetationPolicy,
        BoundaryPolicy boundaryPolicy,
        String decorationPolicy) {

    public LandUseRule {
        if (ruleRef == null || ruleRef.isBlank()) throw new IllegalArgumentException("ruleRef is required");
        if (landUseType == null || landUseType.isBlank()) throw new IllegalArgumentException("landUseType is required");
        semanticTerms = List.copyOf(semanticTerms == null ? List.of() : semanticTerms);
        if (footprintMultiplier < 0 || extraAreaBlocks < 0 || minAreaBlocks < 0 || maxAreaBlocks < minAreaBlocks) {
            throw new IllegalArgumentException("Invalid LandUse area rule");
        }
        if (actionBudget <= 0 || baseStepCost <= 0) throw new IllegalArgumentException("Invalid LandUse cost rule");
        decorationPolicy = decorationPolicy == null ? "" : decorationPolicy;
    }

    public int preferredArea(int footprintArea) {
        int target = (int) Math.round(footprintArea * footprintMultiplier) + extraAreaBlocks;
        return Math.max(minAreaBlocks, Math.min(maxAreaBlocks, target));
    }
}
