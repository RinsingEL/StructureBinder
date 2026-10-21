package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.*;

/** Terrain-cost multi-source growth inside a reserved rural domain, never beyond it. */
final class CityRuralMosaic {
    private static final int[][] DIRECTIONS={{1,0},{0,1},{-1,0},{0,-1}};
    static Map<Integer,Set<BlockPoint>> partition(Set<BlockPoint> domain,int requested,LandUseTerrainField terrain,long seed) {
        if(domain.isEmpty())return Map.of();
        List<BlockPoint> ordered=domain.stream().sorted(Comparator.comparingInt(BlockPoint::z).thenComparingInt(BlockPoint::x)).toList();
        List<BlockPoint> seeds=new ArrayList<>();seeds.add(ordered.get(Math.floorMod((int)seed,ordered.size())));
        Map<BlockPoint,Long> nearest=new HashMap<>();
        for(int i=1;i<Math.min(requested,domain.size());i++) {
            BlockPoint last=seeds.get(seeds.size()-1),best=null;long far=-1;
            for(var p:ordered) {
                long dx=(long)p.x()-last.x(),dz=(long)p.z()-last.z(),distance=Math.min(nearest.getOrDefault(p,Long.MAX_VALUE),dx*dx+dz*dz);
                nearest.put(p,distance);if(distance>far){far=distance;best=p;}
            }
            seeds.add(best);
        }
        Map<Long,LandUseTerrainField.Cell> cells=new HashMap<>();for(var c:terrain.cells())cells.put(key(c.cellX(),c.cellZ()),c);
        record Node(BlockPoint point,int owner,double cost) { }
        PriorityQueue<Node> queue=new PriorityQueue<>(Comparator.comparingDouble(Node::cost).thenComparingInt(Node::owner).thenComparingInt(n->n.point.z()).thenComparingInt(n->n.point.x()));
        for(int i=0;i<seeds.size();i++)queue.add(new Node(seeds.get(i),i,0));
        Map<BlockPoint,Integer> labels=new HashMap<>();Map<BlockPoint,Double> distances=new HashMap<>();
        while(!queue.isEmpty()) {
            Node n=queue.remove();if(labels.containsKey(n.point))continue;labels.put(n.point,n.owner);
            for(int[] d:DIRECTIONS) {
                var p=new BlockPoint(n.point.x()+d[0],n.point.z()+d[1]);if(!domain.contains(p)||labels.containsKey(p))continue;
                var a=cells.get(key(Math.floorDiv(n.point.x(),terrain.cellStepBlocks()),Math.floorDiv(n.point.z(),terrain.cellStepBlocks())));
                var b=cells.get(key(Math.floorDiv(p.x(),terrain.cellStepBlocks()),Math.floorDiv(p.z(),terrain.cellStepBlocks())));
                if(a==null||b==null||!a.sampled()||!b.sampled()||b.water()||Math.abs(a.elevation()-b.elevation())>8)continue;
                double noise=Math.sin((p.x()+seed%1000)*.035)+Math.cos((p.z()-seed%1000)*.047);
                double cost=n.cost+1+Math.abs(a.elevation()-b.elevation())*.6+b.slope()*.015+noise*.18;
                if(cost<distances.getOrDefault(p,Double.POSITIVE_INFINITY)){distances.put(p,cost);queue.add(new Node(p,n.owner,cost));}
            }
        }
        Map<Integer,Set<BlockPoint>> result=new TreeMap<>();
        for(var p:ordered) {
            Integer label=labels.get(p);if(label==null)continue;
            boolean boundary=false;
            for(int[] d:DIRECTIONS){Integer other=labels.get(new BlockPoint(p.x()+d[0],p.z()+d[1]));if(other!=null&&other<label){boundary=true;break;}}
            if(!boundary)result.computeIfAbsent(label,k->new LinkedHashSet<>()).add(p);
        }
        return result;
    }
    private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
}
