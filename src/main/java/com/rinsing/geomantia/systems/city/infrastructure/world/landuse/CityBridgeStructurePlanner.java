package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan.FeatureKind;
import java.util.*;

/** Bridge supports use the same columns as railing posts, independent of chunk iteration order. */
final class CityBridgeStructurePlanner {
    private CityBridgeStructurePlanner() { }
    static Set<CityLandUseChunkCompiler.FeatureOperation> posts(CityLandUseChunkCompiler.ChunkFragment fragment) {
        var context=new HashSet<>(fragment.gradingFeatureOperations());context.addAll(fragment.featureOperations());
        Map<String,List<CityLandUseChunkCompiler.FeatureOperation>> byBridge=new HashMap<>();
        for(var op:context)if(op.kind()==FeatureKind.BRIDGE_RAIL)
            byBridge.computeIfAbsent(op.sourceId(),ignored->new ArrayList<>()).add(op);
        Set<CityLandUseChunkCompiler.FeatureOperation> result=new HashSet<>();
        for(var entry:byBridge.entrySet()) {
            var rails=entry.getValue();
            int minX=rails.stream().mapToInt(CityLandUseChunkCompiler.FeatureOperation::x).min().orElseThrow();
            int maxX=rails.stream().mapToInt(CityLandUseChunkCompiler.FeatureOperation::x).max().orElseThrow();
            int minZ=rails.stream().mapToInt(CityLandUseChunkCompiler.FeatureOperation::z).min().orElseThrow();
            int maxZ=rails.stream().mapToInt(CityLandUseChunkCompiler.FeatureOperation::z).max().orElseThrow();
            boolean horizontal=maxX-minX>=maxZ-minZ;
            var road=fragment.materialField().roads().stream().filter(r->r.sourceId().equals(entry.getKey())).findFirst();
            if(road.isPresent())horizontal=road.get().bounds().maxX()-road.get().bounds().minX()
                    >=road.get().bounds().maxZ()-road.get().bounds().minZ();
            Set<Integer> coordinates=new HashSet<>();
            for(var rail:rails)coordinates.add(horizontal?rail.x():rail.z());
            int phase=Math.floorMod(entry.getKey().hashCode(),7);
            for(var rail:rails) {
                int along=horizontal?rail.x():rail.z();
                if(Math.floorMod(along,7)==phase || !coordinates.contains(along-1) || !coordinates.contains(along+1))
                    result.add(rail);
            }
        }
        return Set.copyOf(result);
    }
}
