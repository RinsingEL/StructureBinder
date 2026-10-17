package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.landuse.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import java.util.*;
import static com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan.*;

/** Applies village fallback appearance without replacing explicit city or district materials. */
public final class CityVillageRoadPlanner {
    public static final String DECORATION_SOURCE = "village_road_decoration::";
    private record Key(int x,int z,int offset) { }
    public record Result(CityLandUseSurfacePrintPlan surface, JsonObject report) { }

    public Result plan(CityLandUseSurfacePrintPlan source,
            LandUseSourceResolver.Resolution sources, LandUseAreaPlan landUse,
            List<LandUseAreaPlan.CorridorExclusion> reservations, CityVillageRoadSettings settings, CityScale scale) {
        JsonObject report=new JsonObject();report.addProperty("schema","city_village_road_report.v1");
        report.addProperty("scale",scale==null?"unknown":scale.contractName());
        JsonArray decisions=new JsonArray();report.add("decisions",decisions);
        boolean villageScale=scale==CityScale.HAMLET || scale==CityScale.VILLAGE;
        Set<String> explicitlySelected=new HashSet<>();
        var materials=sources.materialField();
        if(settings.enabled() && villageScale)for(var f:source.featureCells()) if(f.kind()==FeatureKind.ROAD_SLAB || f.kind()==FeatureKind.ROAD_STAIR) {
            for(String slot:List.of("roadSurface","roadStair","roadCurb","roadBase"))
                if(materials.at(slot,f.x(),f.z(),f.sourceId(),null)!=null)explicitlySelected.add(f.sourceId());
        }
        Map<String,LandUseSourceResolver.RoadBand> village=new TreeMap<>();
        for(var band:sources.roadBands()) if(settings.roadKinds().contains(band.roadKind())) {
            String reason=!settings.enabled()?"disabled":!villageScale?"not_village_scale":band.bridge()?"bridge":
                    explicitlySelected.contains(band.streetBandId())?"explicit_materials":"village_fallback";
            JsonObject decision=new JsonObject();decision.addProperty("sourceId",band.streetBandId());
            decision.addProperty("roadKind",band.roadKind());decision.addProperty("reason",reason);decisions.add(decision);
            if(reason.equals("village_fallback"))village.put(band.streetBandId(),band);
        }
        report.addProperty("appliedRoadCount",village.size());
        if(village.isEmpty()) return new Result(source,report);

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
        Map<String,CityMaterialField.Road> roads=new TreeMap<>();
        original.roads().forEach(r->roads.put(r.sourceId(),r));
        for(var band:sources.roadBands()) {
            Map<String,String> fallbacks=village.containsKey(band.streetBandId())
                    ?Map.of("roadStair",settings.stairBlockId(),"roadBase",settings.baseBlockId()):Map.of();
            roads.put(band.streetBandId(),new CityMaterialField.Road(band.streetBandId(),band.roadKind(),band.bounds(),fallbacks));
        }
        var field=new CityMaterialField(original.selections(),original.owners(),List.copyOf(roads.values()));
        var surface=new CityLandUseSurfacePrintPlanCodec().withComputedHash(new CityLandUseSurfacePrintPlan(source.schema(),
                source.cityId(),source.sourceLandUsePlanHash(),"",source.areas(),source.sharedBoundarySpans(),features,field));
        return new Result(surface,report);
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
