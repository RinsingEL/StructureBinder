package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.algorithm.landuse.CityDistrictPlanner;
import com.rinsing.geomantia.systems.city.domain.model.*;
import java.util.*;
import static com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan.*;

/** Reserved public spaces consume a configurable fixture palette and four designed planting beds. */
public final class CityDesignedPublicSpacePlanner {
    public record Palette(String flower,String hedge,String trunk,String leaves,String border,String bench,String lampPost,String lamp,String monument) {
        public Palette {for(String b:List.of(flower,hedge,trunk,leaves,border,bench,lampPost,lamp,monument))
            if(!b.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+"))throw new IllegalArgumentException("CITY_PUBLIC_SPACE_PALETTE_INVALID");}
        public static Palette defaults(){return new Palette("minecraft:allium","minecraft:oak_leaves","minecraft:oak_log",
                "minecraft:azalea_leaves","minecraft:stone_brick_stairs","minecraft:dark_oak_stairs",
                "minecraft:dark_oak_fence","minecraft:lantern","minecraft:chiseled_stone_bricks");}
        public static Palette load() {
            var path=java.nio.file.Path.of("config","geomantia","city_public_space_fixtures.json");
            try(var reader=java.nio.file.Files.isRegularFile(path)?java.nio.file.Files.newBufferedReader(path)
                    :new java.io.InputStreamReader(Objects.requireNonNull(CityDesignedPublicSpacePlanner.class.getResourceAsStream(
                            "/data/geomantia/city/public_space_fixtures.json")),java.nio.charset.StandardCharsets.UTF_8)) {
                return new com.google.gson.Gson().fromJson(reader,Palette.class);
            }catch(java.io.IOException e){throw new IllegalStateException("CITY_PUBLIC_SPACE_PALETTE_UNREADABLE",e);}
        }
    }
    private record Key(int x,int z,int offset) { }
    public List<FeatureCell> plan(CityLandUseSurfacePrintPlan source,List<CityDistrictPlanner.Result.PublicSpace> spaces,
                                Set<BlockPoint> available,boolean greenery,Palette palette) {
        Map<Key,FeatureCell> cells=new LinkedHashMap<>();for(var f:source.featureCells())cells.put(new Key(f.x(),f.z(),f.surfaceOffset()),f);
        Map<BlockPoint,Integer> heights=new HashMap<>();
        for(var a:source.areas())if(a.recipe() instanceof UniformRecipe u)for(var s:u.platformSpans())for(int x=s.minX();x<=s.maxX();x++)heights.put(new BlockPoint(x,s.z()),s.targetY());
        for(var f:source.featureCells())if(f.surfaceOffset()==0&&f.targetSurfaceY()!=null)heights.put(new BlockPoint(f.x(),f.z()),f.targetSurfaceY());
        Set<BlockPoint> blocked=new HashSet<>();
        for(var f:source.featureCells())if(!f.sourceId().startsWith(CityPublicGreeneryPlanner.SOURCE)&&!f.sourceId().startsWith(CitySharedTerracePlanner.SOURCE)
                ||f.kind()==FeatureKind.TERRACE_RAIL)for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++)blocked.add(new BlockPoint(f.x()+dx,f.z()+dz));
        for(var space:spaces) {
            String id=CityPublicGreeneryPlanner.SOURCE+space.owner()+"::"+space.purpose();var b=space.bounds();var center=b.center();
            boolean garden=space.purpose().toLowerCase(Locale.ROOT).contains("garden");
            for(var p:available)if(b.contains(p.x(),p.z())) {
                cells.remove(new Key(p.x(),p.z(),1));
                if(Math.abs(p.x()-center.x())<=2||Math.abs(p.z()-center.z())<=2)
                    emit(cells,id,p,0,source.materialField().at("accessSurface",p.x(),p.z(),"minecraft:polished_andesite"),FeatureKind.GREEN_PATH,HorizontalFacing.NONE,null);
            }
            if(greenery)for(int sx:List.of(-1,1))for(int sz:List.of(-1,1)) {
                int x0=sx<0?b.minX()+4:center.x()+5,x1=sx<0?center.x()-5:b.maxX()-4;
                int z0=sz<0?b.minZ()+4:center.z()+5,z1=sz<0?center.z()-5:b.maxZ()-4;
                if(!garden){if(sx<0)x1=Math.min(x1,x0+4);else x0=Math.max(x0,x1-4);if(sz<0)z1=Math.min(z1,z0+6);else z0=Math.max(z0,z1-6);}
                for(int z=z0;z<=z1;z++)for(int x=x0;x<=x1;x++) {
                    var p=new BlockPoint(x,z);if(!usable(p,available,blocked))continue;
                    if(x==x0||x==x1||z==z0||z==z1) {
                        var facing=x==x0?HorizontalFacing.WEST:x==x1?HorizontalFacing.EAST:z==z0?HorizontalFacing.NORTH:HorizontalFacing.SOUTH;
                        emit(cells,id,p,1,palette.border(),FeatureKind.GREEN_FIXTURE,facing,null);
                    }else if(x==x0+1||x==x1-1||z==z0+1||z==z1-1)
                        emit(cells,id,p,1,palette.hedge(),FeatureKind.GREEN_PLANT,HorizontalFacing.NONE,null);
                    else if(Math.floorMod(x-x0,3)==0&&Math.floorMod(z-z0,3)==0)
                        emit(cells,id,p,1,palette.flower(),FeatureKind.GREEN_PLANT,HorizontalFacing.NONE,null);
                }
                var tree=new BlockPoint(sx<0?x0+3:x1-3,sz<0?z0+3:z1-3);Integer y=heights.get(tree);
                List<BlockPoint> canopy=new ArrayList<>();for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++)canopy.add(new BlockPoint(tree.x()+dx,tree.z()+dz));
                if(y!=null&&canopy.stream().allMatch(p->usable(p,available,blocked)&&Objects.equals(heights.get(p),y))) {
                    for(var p:canopy)cells.remove(new Key(p.x(),p.z(),1));
                    for(int dy=1;dy<=4;dy++)emit(cells,id,tree,dy,palette.trunk(),FeatureKind.GREEN_FIXTURE,HorizontalFacing.NONE,y);
                    for(var p:canopy)if(!p.equals(tree))emit(cells,id,p,4,palette.leaves(),FeatureKind.GREEN_PLANT,HorizontalFacing.NONE,y);
                    for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)emit(cells,id,new BlockPoint(tree.x()+dx,tree.z()+dz),5,palette.leaves(),FeatureKind.GREEN_PLANT,HorizontalFacing.NONE,y);
                }
            }
            // Furniture survives disabling plants and never consumes the actual road footprint.
            for(int side:List.of(-1,1)) {
                int z=side<0?b.minZ()+2:b.maxZ()-2;
                for(int dx=-1;dx<=1;dx++){var p=new BlockPoint(center.x()+6+dx,z);
                    if(usable(p,available,blocked))emit(cells,id,p,1,palette.bench(),FeatureKind.GREEN_FIXTURE,side<0?HorizontalFacing.SOUTH:HorizontalFacing.NORTH,null);}
                var lamp=new BlockPoint(center.x()-6,z);
                if(usable(lamp,available,blocked)) {
                    emit(cells,id,lamp,1,palette.lampPost(),FeatureKind.GREEN_FIXTURE,HorizontalFacing.NONE,null);
                    emit(cells,id,lamp,2,palette.lampPost(),FeatureKind.GREEN_FIXTURE,HorizontalFacing.NONE,null);
                    emit(cells,id,lamp,3,palette.lamp(),FeatureKind.GREEN_FIXTURE,HorizontalFacing.NONE,null);
                }
            }
            for(var p:List.of(center,new BlockPoint(center.x()+8,center.z()+8),new BlockPoint(center.x()-8,center.z()-8))) {
                if(!b.contains(p.x(),p.z())||!usable(p,available,blocked)||!heights.containsKey(p))continue;
                for(int dy=1;dy<=3;dy++)emit(cells,id,p,dy,palette.monument(),FeatureKind.GREEN_FIXTURE,HorizontalFacing.NONE,heights.get(p));
                break;
            }
        }
        return cells.values().stream().sorted(Comparator.comparingInt(FeatureCell::z).thenComparingInt(FeatureCell::x).thenComparingInt(FeatureCell::surfaceOffset)).toList();
    }
    private static boolean usable(BlockPoint p,Set<BlockPoint> available,Set<BlockPoint> blocked){return available.contains(p)&&!blocked.contains(p);}
    private static void emit(Map<Key,FeatureCell> cells,String id,BlockPoint p,int dy,String block,FeatureKind kind,HorizontalFacing facing,Integer y){
        Key key=new Key(p.x(),p.z(),dy);var previous=cells.get(key);
        if(previous!=null&&!previous.sourceId().startsWith(CityPublicGreeneryPlanner.SOURCE))return;
        cells.put(key,new FeatureCell(id,p.x(),p.z(),block,dy,kind,facing,y));
    }
}
