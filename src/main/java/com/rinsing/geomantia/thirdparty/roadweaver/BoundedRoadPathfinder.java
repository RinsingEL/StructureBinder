/*
 * Adapted from RoadWeaver BasicAStarPathfinder, revision b48bfab.
 * Copyright (c) 2025 shiroha-233. MIT; see META-INF/licenses/roadweaver/LICENSE.
 * Geomantia changes: pure read-only terrain interface, exact endpoints, bounded domain and
 * budget, cardinal full-width clearance, deterministic queue order, stale-node rejection.
 */
package com.rinsing.geomantia.thirdparty.roadweaver;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.*;

/** Does not discover destinations, enqueue work, access Minecraft, or place blocks. */
public final class BoundedRoadPathfinder {
    public interface Terrain {
        double height(int x, int z);
        boolean blocked(int x, int z);
        default double penalty(int x, int z) { return 0; }
    }
    public record Result(List<BlockPoint> path, String reason, int expanded) {
        public boolean success() { return !path.isEmpty(); }
    }
    private record Node(BlockPoint point, Node parent, double g, double f) {}
    public Result find(BlockPoint start, BlockPoint end, BlockBounds domain, int step, int halfWidth,
                       int budget, Terrain terrain) {
        if (step < 1 || halfWidth < 0 || budget < 1 || !domain.contains(start.x(),start.z())
                || !domain.contains(end.x(),end.z())) throw new IllegalArgumentException("ROAD_SEARCH_ARGUMENT_INVALID");
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f)
                .thenComparingInt(n -> n.point.x()).thenComparingInt(n -> n.point.z()));
        Map<BlockPoint,Double> costs = new HashMap<>();
        if (!clear(start,start,domain,halfWidth,terrain) || !clear(end,end,domain,halfWidth,terrain))
            return new Result(List.of(), "ENDPOINT_BLOCKED",0);
        open.add(new Node(start,null,0,distance(start,end))); costs.put(start,0.0);
        int expanded=0;
        while(!open.isEmpty() && expanded<budget) {
            if(Thread.currentThread().isInterrupted()) return new Result(List.of(),"CANCELLED",expanded);
            Node current=open.remove();
            if(current.g>costs.getOrDefault(current.point,Double.POSITIVE_INFINITY)) continue;
            expanded++;
            if(current.point.equals(end)) {
                List<BlockPoint> path=new ArrayList<>();
                for(Node n=current;n!=null;n=n.parent) path.add(n.point);
                Collections.reverse(path); return new Result(List.copyOf(path),"CONNECTED",expanded);
            }
            for(int[] offset:new int[][]{{step,0},{0,step},{-step,0},{0,-step}}) {
                int dx=offset[0],dz=offset[1];
                if(dx!=0 && current.point.z()==end.z() && Integer.signum(end.x()-current.point.x())==Integer.signum(dx)
                        && Math.abs(end.x()-current.point.x())<step) dx=end.x()-current.point.x();
                if(dz!=0 && current.point.x()==end.x() && Integer.signum(end.z()-current.point.z())==Integer.signum(dz)
                        && Math.abs(end.z()-current.point.z())<step) dz=end.z()-current.point.z();
                // Align the lattice to the destination even when neither coordinate starts on it.
                if(dx!=0 && Math.abs(end.x()-current.point.x())<step && Integer.signum(end.x()-current.point.x())==Integer.signum(dx)) dx=end.x()-current.point.x();
                if(dz!=0 && Math.abs(end.z()-current.point.z())<step && Integer.signum(end.z()-current.point.z())==Integer.signum(dz)) dz=end.z()-current.point.z();
                BlockPoint next=new BlockPoint(current.point.x()+dx,current.point.z()+dz);
                if(!clear(current.point,next,domain,halfWidth,terrain)) continue;
                double elevation=Math.abs(terrain.height(next.x(),next.z())-terrain.height(current.point.x(),current.point.z()));
                double g=current.g+distance(current.point,next)+elevation*4+terrain.penalty(next.x(),next.z());
                if(!Double.isFinite(g) || g>=costs.getOrDefault(next,Double.POSITIVE_INFINITY)) continue;
                costs.put(next,g);open.add(new Node(next,current,g,g+distance(next,end)));
            }
        }
        return new Result(List.of(),open.isEmpty()?"NO_ROUTE":"SEARCH_BUDGET_EXHAUSTED",expanded);
    }
    private static boolean clear(BlockPoint a,BlockPoint b,BlockBounds bounds,int radius,Terrain terrain) {
        int minX=Math.min(a.x(),b.x())-radius,maxX=Math.max(a.x(),b.x())+radius;
        int minZ=Math.min(a.z(),b.z())-radius,maxZ=Math.max(a.z(),b.z())+radius;
        if(!bounds.contains(minX,minZ)||!bounds.contains(maxX,maxZ))return false;
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)if(terrain.blocked(x,z))return false;
        return true;
    }
    private static int distance(BlockPoint a,BlockPoint b) { return Math.abs(a.x()-b.x())+Math.abs(a.z()-b.z()); }
}
