package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.*;

/** Shared cell edges, with first-side setback sized for the actual building/child array. */
final class CityPatchBoundaryGuide {
    static boolean requested(CityBlueprint.Group group) {
        return group.placementRelation() != null
                && group.placementRelation().kind() == CityBlueprint.PlacementRelationKind.ALONG_PATCH_BOUNDARY;
    }
    static List<BlockPoint> origins(CityBlueprint.Group group, Map<String, LandformPatchSummary> patches,
                                    int step, int span, BlockPoint preferred) {
        var relation = group.placementRelation();
        if (relation == null || relation.kind() != CityBlueprint.PlacementRelationKind.ALONG_PATCH_BOUNDARY
                || relation.patchRefs().size() != 2) return List.of();
        var first = patches.get(relation.patchRefs().get(0));
        var second = patches.get(relation.patchRefs().get(1));
        if (first == null || second == null) return List.of();
        Set<BlockPoint> secondCells = new HashSet<>();
        second.memberCells().forEach(c -> secondCells.add(new BlockPoint(c.blockMinX(), c.blockMinZ())));
        Set<BlockPoint> origins = new LinkedHashSet<>();
        int setback = Math.max(0, (span - step) / 2 + 2);
        for (var cell : first.memberCells()) for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            if (!secondCells.contains(new BlockPoint(cell.blockMinX()+d[0]*step, cell.blockMinZ()+d[1]*step))) continue;
            origins.add(new BlockPoint(cell.blockMinX()+step/2-d[0]*setback,cell.blockMinZ()+step/2-d[1]*setback));
        }
        return origins.stream().sorted(Comparator.comparingDouble((BlockPoint p) ->
                Math.hypot(p.x()-preferred.x(), p.z()-preferred.z())).thenComparingInt(BlockPoint::x)
                .thenComparingInt(BlockPoint::z)).toList();
    }
}
