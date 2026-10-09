package com.rinsing.geomantia.systems.city.application.outdoor;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.*;

/** Theme-independent facilities on an already resolved core-owned platform.
 * Plans exact supported footprints; neither expands platforms nor moves buildings.
 * An adapter resolves material and template references before calling this planner.
 */
public final class CityTerraceFeaturePlanner {
    private static final Comparator<BlockPoint> ORDER = Comparator.comparingInt(BlockPoint::z).thenComparingInt(BlockPoint::x);
    private static final int[][] DIRS = {{0,-1},{1,0},{0,1},{-1,0}};
    public record Fixture(String assetId, int width, int depth, int height, int groundPlane) {
        public Fixture {
            if (assetId == null || assetId.isBlank() || width < 1 || depth < 1 || height < 1 || groundPlane < 1)
                throw new IllegalArgumentException("TERRACE_FIXTURE_INVALID");
        }
    }
    public record Settings(String railBlock, String postBlock, String capBlock, int postSpacing,
                           int minimumExposure, int maximumCorners, int cornerSpacing,
                           Fixture corner, Fixture court) {
        public Settings {
            if (postSpacing < 4 || minimumExposure < 0 || maximumCorners < 0 || maximumCorners > 16 || cornerSpacing < 1)
                throw new IllegalArgumentException("TERRACE_FEATURE_SETTINGS_INVALID");
            railBlock = railBlock == null ? "" : railBlock;
            postBlock = postBlock == null ? "" : postBlock;
            capBlock = capBlock == null ? "" : capBlock;
        }
    }
    public record Block(String groupId, String kind, int x, int y, int z, String blockId) { }
    public record Placement(String placementId, String terraceId, String kind, String assetId,
                            BlockPoint originBlock, int rotation, int width, int depth, int height,
                            int surfaceY, int placementBaseY) { }
    public record Plan(List<Block> blocks, List<Placement> fixtures, List<BlockPoint> openings, int cornerCandidates) { }

    public Plan plan(String groupId, Map<BlockPoint,Integer> platform, Map<BlockPoint,Integer> allPlatforms,
                     Map<BlockPoint,Integer> surroundingGround, List<BlockBounds> buildings,
                     List<BlockBounds> roads, Settings settings) {
        if (platform.isEmpty()) return new Plan(List.of(), List.of(), List.of(), 0);
        Set<BlockPoint> blocked = new HashSet<>();
        for (var b : buildings) raster(blocked, b, 1, platform.keySet());
        Set<BlockPoint> roadSpace = new HashSet<>();
        for (var b : roads) raster(roadSpace, b, 2, platform.keySet());
        blocked.addAll(roadSpace);
        List<BlockPoint> boundary = platform.keySet().stream().filter(p -> outside(p, platform).size() > 0).sorted(ORDER).toList();
        List<BlockPoint> openings = boundary.stream().filter(roadSpace::contains).toList();
        List<Placement> fixtures = new ArrayList<>();
        List<BlockPoint> centers = new ArrayList<>();
        int candidates = 0;
        if (settings.corner() != null && settings.maximumCorners() > 0) {
            List<Placement> options=new ArrayList<>();
            for (var p : boundary) {
                List<Integer> faces = outside(p, platform);
                if (faces.size() != 2 || Math.floorMod(faces.get(0)-faces.get(1),2) == 0) continue;
                int rotation = cornerRotation(faces);
                if (rotation < 0) continue;
                candidates++;
                var f = settings.corner();
                int w = rotation % 180 == 0 ? f.width() : f.depth();
                int d = rotation % 180 == 0 ? f.depth() : f.width();
                int x = faces.contains(3) ? p.x() : p.x()-w+1;
                int z = faces.contains(0) ? p.z() : p.z()-d+1;
                BlockPoint center = new BlockPoint(x+w/2,z+d/2);
                Integer y = fits(x,z,w,d,platform,blocked);
                if (y == null || exposure(p, y, surroundingGround, allPlatforms, platform) < settings.minimumExposure()) continue;
                options.add(placement(groupId,"CORNER",f,x,z,rotation,w,d,y,0));
            }
            long cx=Math.round(platform.keySet().stream().mapToInt(BlockPoint::x).average().orElse(0));
            long cz=Math.round(platform.keySet().stream().mapToInt(BlockPoint::z).average().orElse(0));
            while(!options.isEmpty() && fixtures.size()<settings.maximumCorners()) {
                // Spread facilities across the perimeter; scan order must not favour one side.
                options.sort(Comparator.<Placement>comparingLong(v->{
                    int x=v.originBlock().x()+v.width()/2,z=v.originBlock().z()+v.depth()/2;
                    if(centers.isEmpty())return (x-cx)*(x-cx)+(z-cz)*(z-cz);
                    return centers.stream().mapToLong(p->{long dx=x-p.x(),dz=z-p.z();return dx*dx+dz*dz;}).min().orElse(0);
                }).reversed().thenComparing(v->v.originBlock(),ORDER));
                var v=options.remove(0);var center=new BlockPoint(v.originBlock().x()+v.width()/2,v.originBlock().z()+v.depth()/2);
                if(centers.stream().anyMatch(c -> Math.abs(c.x()-center.x())+Math.abs(c.z()-center.z())<settings.cornerSpacing()))continue;
                if(fits(v.originBlock().x(),v.originBlock().z(),v.width(),v.depth(),platform,blocked)==null)continue;
                fixtures.add(placement(groupId,"CORNER",settings.corner(),v.originBlock().x(),v.originBlock().z(),v.rotation(),v.width(),v.depth(),v.surfaceY(),fixtures.size()));
                reserve(blocked,v.originBlock().x(),v.originBlock().z(),v.width(),v.depth(),1);centers.add(center);
            }
        }
        if (settings.court() != null) {
            var f = settings.court();
            Set<BlockPoint> free = new HashSet<>(platform.keySet()); free.removeAll(blocked);
            Map<BlockPoint,Integer> clearance = distanceFromEdge(free);
            List<BlockPoint> ranked = free.stream().sorted(Comparator
                    .<BlockPoint>comparingInt(p -> clearance.getOrDefault(p,0)).reversed().thenComparing(ORDER)).toList();
            for (var p : ranked) {
                if (clearance.get(p) < Math.max(f.width(),f.depth())/2+1) break;
                int x=p.x()-f.width()/2,z=p.z()-f.depth()/2;
                Integer y = fits(x,z,f.width(),f.depth(),platform,blocked);
                if (y == null) continue;
                fixtures.add(placement(groupId,"COURT",f,x,z,0,f.width(),f.depth(),y,fixtures.size()));
                reserve(blocked,x,z,f.width(),f.depth(),1); break;
            }
        }
        List<Block> blocks = new ArrayList<>();
        for (var p : boundary) {
            int y = platform.get(p);
            if (blocked.contains(p) || exposure(p,y,surroundingGround,allPlatforms,platform) < settings.minimumExposure()) continue;
            boolean post = !settings.postBlock().isEmpty() && Math.floorMod(p.x()+p.z(),settings.postSpacing()) == 0;
            if (post) {
                for (int dy=1;dy<=3;dy++) blocks.add(new Block(groupId,"EDGE_POST",p.x(),y+dy,p.z(),settings.postBlock()));
                if (!settings.capBlock().isEmpty()) blocks.add(new Block(groupId,"EDGE_CAP",p.x(),y+4,p.z(),settings.capBlock()));
            } else if (!settings.railBlock().isEmpty()) blocks.add(new Block(groupId,"EDGE_RAIL",p.x(),y+1,p.z(),settings.railBlock()));
        }
        return new Plan(List.copyOf(blocks),List.copyOf(fixtures),List.copyOf(openings),candidates);
    }
    private static Placement placement(String id,String kind,Fixture f,int x,int z,int r,int w,int d,int y,int n) {
        return new Placement(id+"/"+kind.toLowerCase(Locale.ROOT)+"/"+n,id,kind,f.assetId(),new BlockPoint(x,z),r,w,d,f.height(),y,y+1-f.groundPlane());
    }
    private static List<Integer> outside(BlockPoint p,Map<BlockPoint,Integer> platform) {
        List<Integer> out = new ArrayList<>();
        for (int i=0;i<4;i++) if (!platform.containsKey(new BlockPoint(p.x()+DIRS[i][0],p.z()+DIRS[i][1]))) out.add(i);
        return out;
    }
    private static int cornerRotation(List<Integer> f) {
        if(f.contains(0)&&f.contains(3))return 0;
        if(f.contains(0)&&f.contains(1))return 90;
        if(f.contains(1)&&f.contains(2))return 180;
        if(f.contains(2)&&f.contains(3))return 270;
        return -1;
    }
    private static int exposure(BlockPoint p,int y,Map<BlockPoint,Integer> ground,Map<BlockPoint,Integer> all,Map<BlockPoint,Integer> own) {
        int result = -1;
        for (int i : outside(p,own)) {
            var q = new BlockPoint(p.x()+DIRS[i][0],p.z()+DIRS[i][1]);
            Integer neighbour = all.get(q);
            if(neighbour!=null&&neighbour>=y)continue;
            result = Math.max(result,Math.max(0,y-(neighbour==null?ground.getOrDefault(q,y):neighbour)));
        }
        return result;
    }
    private static Integer fits(int x,int z,int w,int d,Map<BlockPoint,Integer> mask,Set<BlockPoint> blocked) {
        Integer datum = null;
        for(int zz=z;zz<z+d;zz++)for(int xx=x;xx<x+w;xx++) {
            var p=new BlockPoint(xx,zz);Integer y=mask.get(p);
            if(y==null || blocked.contains(p) || datum!=null&&!datum.equals(y))return null;
            datum=y;
        }
        return datum;
    }
    private static void raster(Set<BlockPoint> target,BlockBounds b,int margin,Set<BlockPoint> mask) {
        for(int z=b.minZ()-margin;z<=b.maxZ()+margin;z++)for(int x=b.minX()-margin;x<=b.maxX()+margin;x++) {
            var p=new BlockPoint(x,z);if(mask.contains(p))target.add(p);
        }
    }
    private static void reserve(Set<BlockPoint> set,int x,int z,int w,int d,int margin) {
        for(int zz=z-margin;zz<z+d+margin;zz++)for(int xx=x-margin;xx<x+w+margin;xx++)set.add(new BlockPoint(xx,zz));
    }
    private static Map<BlockPoint,Integer> distanceFromEdge(Set<BlockPoint> free) {
        Map<BlockPoint,Integer> distance=new HashMap<>();ArrayDeque<BlockPoint> queue=new ArrayDeque<>();
        for(var p:free.stream().sorted(ORDER).toList())if(Arrays.stream(DIRS).anyMatch(d -> !free.contains(new BlockPoint(p.x()+d[0],p.z()+d[1])))) {
            distance.put(p,1);queue.add(p);
        }
        while(!queue.isEmpty()) {
            var p=queue.removeFirst();
            for(var d:DIRS){var q=new BlockPoint(p.x()+d[0],p.z()+d[1]);if(free.contains(q)&&!distance.containsKey(q)){distance.put(q,distance.get(p)+1);queue.add(q);}}
        }
        return distance;
    }
}
