package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.*;

/** Deterministic D3 feature edges. Unknown cells never invent a shore or a cliff. */
public final class CityTerrainLines {
    private CityTerrainLines() { }
    public record Sample(BlockPoint point, int normalX, int normalZ, double elevation) { }
    public record Line(String id, String kind, List<Sample> samples) {
        public Line { samples=List.copyOf(samples); }
    }
    private record Vertex(int x,int z) implements Comparable<Vertex> {
        public int compareTo(Vertex v) {int d=Integer.compare(z,v.z);return d==0?Integer.compare(x,v.x):d;}
    }
    private record Edge(Vertex a,Vertex b,Sample sample) { }

    public static List<Line> extract(LandUseTerrainField field) {
        Map<Long,LandUseTerrainField.Cell> cells=new HashMap<>();
        for(var c:field.cells())if(c.sampled()&&Double.isFinite(c.elevation()))cells.put(key(c.cellX(),c.cellZ()),c);
        Map<String,List<Edge>> edges=new TreeMap<>();
        int step=field.cellStepBlocks();
        for(var c:field.cells()) {
            if(!c.sampled()||c.water()||!Double.isFinite(c.elevation()))continue;
            for(int[] d:List.of(new int[]{1,0},new int[]{0,1},new int[]{-1,0},new int[]{0,-1})) {
                var n=cells.get(key(c.cellX()+d[0],c.cellZ()+d[1]));if(n==null)continue;
                int x=c.blockMinX(),z=c.blockMinZ();
                Vertex a,b;
                if(d[0]!=0){int bx=x+(d[0]>0?step:0);a=new Vertex(bx,z);b=new Vertex(bx,z+step);}
                else {int bz=z+(d[1]>0?step:0);a=new Vertex(x,bz);b=new Vertex(x+step,bz);}
                String kind=null;
                if(n.water())kind="SHORE";
                else if(c.elevation()-n.elevation()>=8)kind="CLIFF";
                else if(c.elevation()>n.elevation() && Math.floor(c.elevation()/8)>Math.floor(n.elevation()/8))
                    kind="CONTOUR_"+(int)(Math.floor(c.elevation()/8)*8);
                if(kind!=null)edges.computeIfAbsent(kind,k->new ArrayList<>()).add(new Edge(a,b,
                        new Sample(new BlockPoint((a.x+b.x)/2,(a.z+b.z)/2),d[0],d[1],c.elevation())));
            }
        }
        List<Line> out=new ArrayList<>();
        for(var entry:edges.entrySet()) {
            List<Edge> all=entry.getValue();all.sort(Comparator.comparing(Edge::a).thenComparing(Edge::b));
            Map<Vertex,List<Integer>> incident=new TreeMap<>();
            for(int i=0;i<all.size();i++){incident.computeIfAbsent(all.get(i).a,k->new ArrayList<>()).add(i);incident.computeIfAbsent(all.get(i).b,k->new ArrayList<>()).add(i);}
            Set<Integer> used=new HashSet<>();
            // Branches split into separate selectable lines; do not jump between disconnected banks.
            List<Vertex> starts=new ArrayList<>(incident.keySet());starts.sort(Comparator.comparingInt((Vertex v)->incident.get(v).size()==2?1:0).thenComparing(v->v));
            for(Vertex start:starts)for(int first:incident.get(start)) {
                if(used.contains(first))continue;
                List<Sample> samples=new ArrayList<>();Vertex at=start;int index=first;
                while(used.add(index)) {
                    Edge edge=all.get(index);samples.add(edge.sample);at=edge.a.equals(at)?edge.b:edge.a;
                    if(incident.get(at).size()!=2)break;
                    int next=-1;for(int i:incident.get(at))if(!used.contains(i)){next=i;break;}
                    if(next<0)break;index=next;
                }
                if(samples.size()<2)continue;
                String id=entry.getKey().toLowerCase(Locale.ROOT)+"_"+start.x+"_"+start.z+"_"+first;
                out.add(new Line(id,entry.getKey(),samples));
            }
        }
        return List.copyOf(out);
    }

    public static JsonObject describe(LandUseTerrainField field) {
        JsonObject result=new JsonObject();result.addProperty("schema","city_terrain_lines.v1");
        result.addProperty("cellStepBlocks",field.cellStepBlocks());JsonArray lines=new JsonArray();
        for(var line:extract(field)) {
            JsonObject item=new JsonObject();item.addProperty("lineRef",line.id);item.addProperty("kind",line.kind);
            var first=line.samples.get(0).point;var last=line.samples.get(line.samples.size()-1).point;
            item.addProperty("description",line.kind+" 从 ("+first.x()+","+first.z()+") 到 ("+last.x()+","+last.z()+")，法向指向水面或低地");
            item.addProperty("lengthBlocks",line.samples.size()*field.cellStepBlocks());
            int midX=(first.x()+last.x())/2,midZ=(first.z()+last.z())/2;
            var center=field.planningBounds().center();
            item.addProperty("location",(midZ<center.z()?"北":"南")+(midX<center.x()?"西":"东"));
            item.addProperty("sampleCount",line.samples.size());lines.add(item);
        }
        result.add("lines",lines);result.addProperty("instruction","按 lineRef 选择真实 D3 岸线、等高线或崖边。法向指向水面/低地，建筑退到陆地/高地侧。断开的岸段分别选择，不跨未知地形补线；D3 粗线不能替代实际落位安全检查。");return result;
    }
    private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
}
