package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.*;

/** Coarse exterior of occupied districts. Enclosed natural land does not become pavement. */
public final class CityDistrictBoundary {
    private static final int[][] DIRECTIONS = {{0,-1},{1,0},{0,1},{-1,0}};
    public record Edge(String side, BlockPoint from, BlockPoint to) {}

    public List<Edge> outline(List<BlockBounds> districts, int margin, int step) {
        Domain domain = domain(districts, margin, step);
        Set<BlockPoint> occupied=domain.occupied(), exterior=domain.exterior();
        return edges(occupied, exterior, step);
    }

    public Set<BlockPoint> envelope(List<BlockBounds> districts, int step, BlockBounds planningBounds) {
        if(districts.isEmpty()) return Set.of();
        Domain domain=domain(districts,0,step);
        Set<BlockPoint> result=new HashSet<>();
        for(int tz=domain.minZ();tz<=domain.maxZ();tz++) for(int tx=domain.minX();tx<=domain.maxX();tx++) {
            if(domain.exterior().contains(new BlockPoint(tx,tz))) continue;
            for(int z=Math.max(planningBounds.minZ(),tz*step);z<=Math.min(planningBounds.maxZ(),(tz+1)*step-1);z++)
                for(int x=Math.max(planningBounds.minX(),tx*step);x<=Math.min(planningBounds.maxX(),(tx+1)*step-1);x++)
                    result.add(new BlockPoint(x,z));
        }
        return result;
    }

    private static Domain domain(List<BlockBounds> districts,int margin,int step) {
        if (districts.isEmpty() || margin < 0 || step < 1)
            throw new IllegalArgumentException("CITY_DISTRICT_BOUNDARY_INPUT_REQUIRED");
        Set<BlockPoint> occupied = new HashSet<>();
        for (BlockBounds bounds : districts) {
            for (int z = Math.floorDiv(bounds.minZ()-margin, step); z <= Math.floorDiv(bounds.maxZ()+margin,step); z++)
                for (int x = Math.floorDiv(bounds.minX()-margin,step); x <= Math.floorDiv(bounds.maxX()+margin,step); x++)
                    occupied.add(new BlockPoint(x,z));
        }
        int minX=occupied.stream().mapToInt(BlockPoint::x).min().orElseThrow()-1;
        int maxX=occupied.stream().mapToInt(BlockPoint::x).max().orElseThrow()+1;
        int minZ=occupied.stream().mapToInt(BlockPoint::z).min().orElseThrow()-1;
        int maxZ=occupied.stream().mapToInt(BlockPoint::z).max().orElseThrow()+1;
        // Flood only outside: ponds, gardens and courtyards inside the envelope get no inner walls.
        Set<BlockPoint> exterior=new HashSet<>();
        ArrayDeque<BlockPoint> queue=new ArrayDeque<>();
        BlockPoint start=new BlockPoint(minX,minZ); exterior.add(start); queue.add(start);
        while(!queue.isEmpty()) {
            BlockPoint p=queue.removeFirst();
            for(int[] d:DIRECTIONS) {
                BlockPoint q=new BlockPoint(p.x()+d[0],p.z()+d[1]);
                if(q.x()<minX || q.x()>maxX || q.z()<minZ || q.z()>maxZ || occupied.contains(q)) continue;
                if(exterior.add(q)) queue.addLast(q);
            }
        }
        return new Domain(occupied,exterior,minX,maxX,minZ,maxZ);
    }

    private record Domain(Set<BlockPoint> occupied,Set<BlockPoint> exterior,int minX,int maxX,int minZ,int maxZ) {}

    private static List<Edge> edges(Set<BlockPoint> occupied,Set<BlockPoint> exterior,int step) {
        Map<String,SortedSet<Integer>> runs=new TreeMap<>();
        for(BlockPoint p:occupied) for(int direction=0;direction<4;direction++) {
            int[] d=DIRECTIONS[direction];
            if(!exterior.contains(new BlockPoint(p.x()+d[0],p.z()+d[1]))) continue;
            int fixed=(direction==0?p.z():direction==1?p.x()+1:direction==2?p.z()+1:p.x())*step;
            int along=(direction%2==0?p.x():p.z())*step;
            runs.computeIfAbsent(direction+":"+fixed, ignored->new TreeSet<>()).add(along);
        }
        List<Edge> edges=new ArrayList<>();
        for(var entry:runs.entrySet()) {
            String[] key=entry.getKey().split(":");
            int direction=Integer.parseInt(key[0]), fixed=Integer.parseInt(key[1]);
            Integer first=null, last=null;
            for(int coordinate:entry.getValue()) {
                if(last!=null && coordinate!=last+step) {
                    edges.add(edge(direction,fixed,first,last+step)); first=null;
                }
                if(first==null) first=coordinate;
                last=coordinate;
            }
            edges.add(edge(direction,fixed,first,last+step));
        }
        edges.sort(Comparator.comparing(Edge::side).thenComparingInt(e->e.from().z())
                .thenComparingInt(e->e.from().x()));
        return List.copyOf(edges);
    }

    private static Edge edge(int direction,int fixed,int first,int last) {
        return switch(direction) {
            case 0 -> new Edge("north",new BlockPoint(first,fixed),new BlockPoint(last,fixed));
            case 1 -> new Edge("east",new BlockPoint(fixed,first),new BlockPoint(fixed,last));
            case 2 -> new Edge("south",new BlockPoint(last,fixed),new BlockPoint(first,fixed));
            default -> new Edge("west",new BlockPoint(fixed,last),new BlockPoint(fixed,first));
        };
    }
}
