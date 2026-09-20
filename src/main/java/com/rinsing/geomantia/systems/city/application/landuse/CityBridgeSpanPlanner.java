package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import java.util.*;
import static com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan.*;

/** Recognizes continuous water crossings before chunk compilation; one span has one deck datum. */
final class CityBridgeSpanPlanner {
    private record Key(int x,int z,int layer) { }
    private CityBridgeSpanPlanner() { }
    static List<FeatureCell> plan(List<FeatureCell> features,List<LandUseSourceResolver.RoadBand> bands,
                                  LandUseTerrainField terrain) {
        Map<Key,FeatureCell> cells=new LinkedHashMap<>();
        features.forEach(f->cells.put(new Key(f.x(),f.z(),f.surfaceOffset()),f));
        Map<Long,LandUseTerrainField.Cell> samples=new HashMap<>();
        terrain.cells().forEach(c->samples.put(key(c.cellX(),c.cellZ()),c));
        for(var band:bands) {
            boolean horizontal=band.start().z()==band.end().z();
            if(!horizontal && band.start().x()!=band.end().x())continue;
            int start=horizontal?band.bounds().minX():band.bounds().minZ();
            int end=horizontal?band.bounds().maxX():band.bounds().maxZ();
            int acrossStart=horizontal?band.bounds().minZ():band.bounds().minX();
            int acrossEnd=horizontal?band.bounds().maxZ():band.bounds().maxX();
            for(int along=start;along<=end;along++) {
                var water=sample(samples,terrain,band,horizontal,along);
                if(water==null || !water.sampled() || !water.water())continue;
                int first=along,last=along,deck=(int)Math.ceil(water.elevation())+1;
                while(last<end) {
                    var next=sample(samples,terrain,band,horizontal,last+1);
                    if(next==null || !next.sampled())break;
                    if(!next.water()) {
                        // Two crossings sharing a one-block bank need one datum, not overlapping bridge heads.
                        var beyond=last+2<=end?sample(samples,terrain,band,horizontal,last+2):null;
                        if(beyond==null || !beyond.sampled() || !beyond.water())break;
                        deck=Math.max(deck,Math.floorDiv((int)Math.round(next.elevation())+2,4)*4);
                        last++;next=beyond;
                    }
                    last++;deck=Math.max(deck,(int)Math.ceil(next.elevation())+1);
                }
                int from=Math.max(start,first-1),to=Math.min(end,last+1);
                for(int bank:new int[]{from,to}) {
                    var ground=sample(samples,terrain,band,horizontal,bank);
                    if(ground!=null && ground.sampled() && !ground.water())
                        deck=Math.max(deck,Math.floorDiv((int)Math.round(ground.elevation())+2,4)*4);
                    int x=horizontal?bank:band.start().x(),z=horizontal?band.start().z():bank;
                    var frozen=cells.get(new Key(x,z,0));
                    if(frozen!=null && frozen.targetSurfaceY()!=null)deck=Math.max(deck,frozen.targetSurfaceY());
                }
                for(int a=from;a<=to;a++) {
                    for(int cross=acrossStart;cross<=acrossEnd;cross++) {
                        int x=horizontal?a:cross,z=horizontal?cross:a;
                        Key at=new Key(x,z,0);var existing=cells.get(at);
                        if(existing==null || !existing.sourceId().equals(band.streetBandId()))continue;
                        cells.put(at,new FeatureCell(existing.sourceId(),x,z,band.surfaceBlockId(),0,
                                FeatureKind.BRIDGE_DECK,HorizontalFacing.NONE,deck));
                        if(acrossEnd-acrossStart>=2 && (cross==acrossStart || cross==acrossEnd)) {
                            // Leave crossings open rather than putting bridge railings across another road.
                            int ox=horizontal?x:x+(cross==acrossStart?-1:1);
                            int oz=horizontal?z+(cross==acrossStart?-1:1):z;
                            var crossing=cells.get(new Key(ox,oz,0));
                            if(crossing==null || crossing.sourceId().equals(existing.sourceId())) {
                                String rail=band.bridgeRailBlockId().isBlank()?"minecraft:stone_brick_wall":band.bridgeRailBlockId();
                                cells.put(new Key(x,z,1),new FeatureCell(existing.sourceId(),x,z,rail,1,
                                        FeatureKind.BRIDGE_RAIL,HorizontalFacing.NONE,deck));
                            } else cells.remove(new Key(x,z,1));
                        }
                    }
                    for(int cross:new int[]{acrossStart-1,acrossEnd+1}) {
                        Key curb=new Key(horizontal?a:cross,horizontal?cross:a,0);
                        var old=cells.get(curb);
                        if(old!=null && old.sourceId().equals(band.streetBandId()) && old.kind()==FeatureKind.ROAD_STAIR)cells.remove(curb);
                    }
                }
                along=last;
            }
        }
        return List.copyOf(cells.values());
    }
    private static LandUseTerrainField.Cell sample(Map<Long,LandUseTerrainField.Cell> cells,LandUseTerrainField terrain,
            LandUseSourceResolver.RoadBand band,boolean horizontal,int along) {
        int x=horizontal?along:band.start().x(),z=horizontal?band.start().z():along;
        return cells.get(key(Math.floorDiv(x,terrain.cellStepBlocks()),Math.floorDiv(z,terrain.cellStepBlocks())));
    }
    private static long key(int x,int z) {return ((long)x<<32)^(z&0xffffffffL);}
}
