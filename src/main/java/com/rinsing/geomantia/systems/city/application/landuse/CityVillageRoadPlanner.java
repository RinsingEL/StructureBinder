package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.domain.blueprint.CitySurfaceMaterials;
import com.rinsing.geomantia.systems.city.domain.landuse.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import java.util.*;
import static com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan.*;

/** Compiles village appearance after city material overrides, before chunk ownership is assigned. */
public final class CityVillageRoadPlanner {
    public static final String DECORATION_SOURCE = "village_road_decoration::";
    private record Key(int x,int z,int offset) { }

    public CityLandUseSurfacePrintPlan apply(CityLandUseSurfacePrintPlan source,
            LandUseSourceResolver.Resolution sources, LandUseAreaPlan landUse,
            List<LandUseAreaPlan.CorridorExclusion> reservations, CityVillageRoadSettings settings) {
        if (!settings.enabled()) return source;
        Map<String,LandUseSourceResolver.RoadBand> village=new TreeMap<>();
        for(var band:sources.roadBands()) if(!band.bridge() && settings.roadKinds().contains(band.roadKind()))
            village.put(band.streetBandId(),band);
        if(village.isEmpty()) return source;

        long seed=mix(settings.seed() ^ source.cityId().hashCode());
        Map<Key,FeatureCell> cells=new HashMap<>();
        for(var f:source.featureCells()) {
            var road=village.get(f.sourceId());
            if(road!=null && (f.kind()==FeatureKind.ROAD_SLAB || f.kind()==FeatureKind.ROAD_STAIR)) {
                // Curbs are the stair cells outside the carriageway, not its graded stair runs.
                if(!road.bounds().contains(f.x(),f.z())) continue;
                String block=f.kind()==FeatureKind.ROAD_STAIR?settings.stairBlockId():surface(settings,seed,f.x(),f.z());
                f=new FeatureCell(f.sourceId(),f.x(),f.z(),block,f.surfaceOffset(),f.kind(),f.facing(),f.targetSurfaceY());
            }
            cells.put(new Key(f.x(),f.z(),f.surfaceOffset()),f);
        }

        Set<BlockPoint> blocked=new HashSet<>();
        sources.materialField().owners().forEach(owner -> addBounds(blocked,owner.bounds()));
        if(sources.district()!=null) blocked.addAll(sources.district().structures());
        for(var area:landUse.areas()) {
            for(var span:area.memberSpans()) for(int x=span.minX();x<=span.maxX();x++) blocked.add(new BlockPoint(x,span.z()));
            area.structureFootprintExclusions().forEach(b->addBounds(blocked,b));
        }
        sources.landscapeCapacityDomains().values().forEach(blocked::addAll);
        reservations.forEach(r->addBounds(blocked,r.blockBounds()));
        landUse.corridorExclusions().forEach(r->addBounds(blocked,r.blockBounds()));
        sources.roadBands().forEach(b->addBounds(blocked,b.bounds()));
        for(var f:cells.values()) if(!f.sourceId().startsWith(CityPublicGreeneryPlanner.SOURCE))
            blocked.add(new BlockPoint(f.x(),f.z()));

        Set<BlockPoint> decorations=new HashSet<>();
        if(settings.decorationsEnabled()) for(var band:village.values()) {
            boolean alongX=band.start().z()==band.end().z();
            var b=band.bounds();
            int first=(alongX?b.minX():b.minZ())+settings.endClearanceBlocks();
            int last=(alongX?b.maxX():b.maxZ())-settings.endClearanceBlocks();
            for(int station=first;station<=last;station++) for(int side:new int[]{-1,1}) {
                int phase=side<0?0:settings.spacingBlocks()/2;
                if(Math.floorMod((long)station+seed,settings.spacingBlocks())!=phase) continue;
                int edge=alongX?(side<0?b.minZ():b.maxZ()):(side<0?b.minX():b.maxX());
                int cross=edge+side*settings.edgeOffsetBlocks();
                int x=alongX?station:cross,z=alongX?cross:station;
                long random=mix(seed ^ ((long)x<<32) ^ (z&0xffffffffL));
                if((random>>>11)*0x1.0p-53>=settings.chance()) continue;
                var variant=variant(settings,mix(random));
                List<FeatureCell> candidate=new ArrayList<>();
                boolean clear=true;
                String id=DECORATION_SOURCE+x+"_"+z+"::"+variant.id();
                for(var block:variant.blocks()) {
                    int bx=x+(alongX?block.along():side*block.outward());
                    int bz=z+(alongX?side*block.outward():block.along());
                    BlockPoint point=new BlockPoint(bx,bz);
                    if(!landUse.planningBounds().contains(bx,bz) || blocked.contains(point)
                            || decorations.stream().anyMatch(p->Math.abs((long)p.x()-bx)+Math.abs((long)p.z()-bz)<settings.spacingBlocks()/2)) {
                        clear=false;break;
                    }
                    candidate.add(new FeatureCell(id,bx,bz,block.blockId(),block.height(),
                            FeatureKind.GREEN_PLANT,HorizontalFacing.NONE));
                }
                if(!clear) continue;
                for(var f:candidate) {
                    // Replace small automatic public plants with the selected roadside motif.
                    cells.put(new Key(f.x(),f.z(),f.surfaceOffset()),f);
                    decorations.add(new BlockPoint(f.x(),f.z()));
                }
            }
        }
        var features=cells.values().stream().sorted(Comparator.comparingInt(FeatureCell::z)
                .thenComparingInt(FeatureCell::x).thenComparingInt(FeatureCell::surfaceOffset)).toList();
        // Freeze stair/base choices too: runtime transitions must not re-read edited configuration.
        CityMaterialField original=sources.materialField();
        Map<String,Map<String,String>> roads=new TreeMap<>(original.selections().roads());
        for(var band:village.values()) {
            Map<String,String> slots=new TreeMap<>(roads.getOrDefault(band.roadKind(),Map.of()));
            slots.put("roadStair",settings.stairBlockId());slots.put("roadBase",settings.baseBlockId());
            roads.put(band.roadKind(),slots);
        }
        var selection=new CitySurfaceMaterials(original.selections().defaults(),original.selections().groups(),roads,original.selections().landscapes());
        var field=new CityMaterialField(selection,original.owners(),sources.roadBands().stream()
                .map(b->new CityMaterialField.Road(b.streetBandId(),b.roadKind(),b.bounds())).toList());
        return new CityLandUseSurfacePrintPlanCodec().withComputedHash(new CityLandUseSurfacePrintPlan(source.schema(),
                source.cityId(),source.sourceLandUsePlanHash(),"",source.areas(),source.sharedBoundarySpans(),features,field));
    }
    private static String surface(CityVillageRoadSettings s,long seed,int x,int z) {
        int choice=Math.floorMod(mix(seed ^ ((long)x<<32) ^ (z&0xffffffffL)),s.surfacePalette().stream().mapToInt(CityVillageRoadSettings.WeightedBlock::weight).sum());
        for(var block:s.surfacePalette()) {choice-=block.weight();if(choice<0)return block.blockId();}
        throw new AssertionError();
    }
    private static CityVillageRoadSettings.Variant variant(CityVillageRoadSettings s,long random) {
        int choice=Math.floorMod(random,s.variants().stream().mapToInt(CityVillageRoadSettings.Variant::weight).sum());
        for(var variant:s.variants()){choice-=variant.weight();if(choice<0)return variant;}
        throw new AssertionError();
    }
    private static void addBounds(Set<BlockPoint> cells,BlockBounds b) {
        for(int z=b.minZ();z<=b.maxZ();z++)for(int x=b.minX();x<=b.maxX();x++)cells.add(new BlockPoint(x,z));
    }
    private static long mix(long value) {
        value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;
        value=(value^(value>>>27))*0x94d049bb133111ebL;
        return value^(value>>>31);
    }
}
