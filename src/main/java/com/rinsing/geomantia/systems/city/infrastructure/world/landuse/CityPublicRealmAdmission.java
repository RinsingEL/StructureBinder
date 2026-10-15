package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import java.util.*;

/** Admits one complete optional planting instance against generated terrain, before any owner writes it. */
final class CityPublicRealmAdmission {
    record Decision(String reasonCode, int x, int y, int z) {
        boolean accepted() { return reasonCode.equals("PUBLIC_REALM_ADMITTED"); }
    }
    private final Map<String,List<CityLandUseSurfacePrintPlan.FeatureCell>> instances=new HashMap<>();

    CityPublicRealmAdmission(CityLandUseSurfacePrintPlan plan) {
        for(var f:plan.featureCells()) {
            if(optional(f.sourceId()))instances.computeIfAbsent(f.sourceId(),ignored->new ArrayList<>()).add(f);
        }
    }
    static boolean optional(String source) {
        return source.contains("@") && (source.startsWith("public_realm::interior/")
                || source.startsWith("public_realm::fringe/"));
    }
    Decision evaluate(String source,CityLandUseChunkExecutor.ExecutionWorld world) {
        var group=Objects.requireNonNull(instances.get(source));
        int minX=group.stream().mapToInt(f->f.x()).min().orElseThrow(),maxX=group.stream().mapToInt(f->f.x()).max().orElseThrow();
        int minZ=group.stream().mapToInt(f->f.z()).min().orElseThrow(),maxZ=group.stream().mapToInt(f->f.z()).max().orElseThrow();
        int ground=group.get(0).targetSurfaceY();
        int minOffset=group.stream().mapToInt(f->f.surfaceOffset()).min().orElseThrow();
        int maxY=group.stream().mapToInt(f->f.targetSurfaceY()+f.surfaceOffset()).max().orElseThrow();
        // D7 loads the owner's halo asynchronously. Never pull a missing chunk from a worldgen worker.
        for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++)
            if(world.sampleAvailableColumn(x,z)==null)return new Decision("PUBLIC_REALM_CONTEXT_NOT_READY",x,ground,z);
        for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++) {
            if(!world.supportsPublicRealm(x,ground,z))
                return new Decision("PUBLIC_REALM_SUPPORT_MISSING",x,ground,z);
            for(int y=Math.max(ground+1,ground+minOffset);y<=maxY;y++) {
                if(!world.canReplacePublicRealm(x,y,z,false))
                    return new Decision("PUBLIC_REALM_VOLUME_OCCUPIED",x,y,z);
            }
        }
        for(var f:group)if(f.surfaceOffset()<=0
                && !world.canReplacePublicRealm(f.x(),f.targetSurfaceY()+f.surfaceOffset(),f.z(),true))
            return new Decision("PUBLIC_REALM_TARGET_OCCUPIED",f.x(),f.targetSurfaceY()+f.surfaceOffset(),f.z());
        if(minOffset<=1)for(var f:group)if(f.surfaceOffset()==minOffset) {
            int y=f.targetSurfaceY()+f.surfaceOffset()-1;
            // An authored soil layer may replace the existing natural roof of a cave without filling it.
            boolean existingSoil=minOffset<=0 && world.supportsPublicRealm(f.x(),y+1,f.z())
                    && world.canReplacePublicRealm(f.x(),y+1,f.z(),true);
            if(!existingSoil&&!world.supportsPublicRealm(f.x(),y,f.z()))
                return new Decision("PUBLIC_REALM_SUPPORT_MISSING",f.x(),y,f.z());
        }
        return new Decision("PUBLIC_REALM_ADMITTED",minX,ground,minZ);
    }
}
