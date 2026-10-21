package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.*;
import java.util.*;

/** Places complete envelopes using the frozen D3 field, with bounded deterministic searches. */
final class CityNaturalLayoutPlanner {
    record Slot(BlockPoint origin,BlockPoint facing) { }
    static List<Slot> line(CityBlueprint.NaturalLayout layout,LandUseTerrainField field,List<Integer> spans) {
        var line=CityTerrainLines.extract(field).stream().filter(l->l.id().equals(layout.lineRef())).findFirst()
                .orElseThrow(()->new IllegalArgumentException("CITY_TERRAIN_LINE_UNKNOWN:"+layout.lineRef()));
        List<Slot> out=new ArrayList<>();
        int perRow=(spans.size()+layout.rows()-1)/layout.rows();
        int maxSpan=spans.stream().mapToInt(i->i).max().orElse(1);
        double pitch=Math.max(layout.spacingBlocks(),maxSpan+8),total=0;
        double[] distances=new double[line.samples().size()];
        for(int i=1;i<distances.length;i++) {
            var a=line.samples().get(i-1).point();var b=line.samples().get(i).point();
            total+=Math.hypot(a.x()-b.x(),a.z()-b.z());distances[i]=total;
        }
        if(perRow>1&&(perRow-1)*pitch>total)throw new IllegalArgumentException("CITY_TERRAIN_LINE_TOO_SHORT:"+layout.lineRef()+": available="+Math.round(total));
        for(int i=0;i<spans.size();i++) {
            double at=i%perRow*pitch;int index=0;while(index+1<distances.length&&distances[index+1]<=at)index++;
            var sample=line.samples().get(index);double x=sample.point().x(),z=sample.point().z();
            if(index+1<distances.length) {
                double part=(at-distances[index])/(distances[index+1]-distances[index]);var next=line.samples().get(index+1).point();
                x+=(next.x()-x)*part;z+=(next.z()-z)*part;
            }
            int retreat=layout.setbackBlocks()+maxSpan/2+(i/perRow)*(maxSpan+8);
            int cx=(int)Math.round(x)-sample.normalX()*retreat,cz=(int)Math.round(z)-sample.normalZ()*retreat;
            out.add(new Slot(new BlockPoint(cx-spans.get(i)/2,cz-spans.get(i)/2),
                    new BlockPoint(cx+sample.normalX()*1000,cz+sample.normalZ()*1000)));
        }
        return List.copyOf(out);
    }

    static List<BlockPoint> scatter(CityBlueprint.NaturalLayout layout,LandUseTerrainField field,
                                   BlockPoint center,List<CityRectangleLayoutPlanner.Size> sizes,long seed) {
        List<LandUseTerrainField.Cell> choices=new ArrayList<>(field.cells().stream()
                .filter(c->c.sampled()&&!c.water()&&Double.isFinite(c.elevation())&&c.slope()<=12&&c.localRelief()<=12)
                .filter(c->Math.hypot(c.blockMinX()+c.cellStepBlocks()/2.0-center.x(),c.blockMinZ()+c.cellStepBlocks()/2.0-center.z())<=layout.radiusBlocks()).toList());
        // Prefer flatter land and proximity to water, with stable irregular sampling rather than a lattice.
        choices.sort(Comparator.comparingDouble((LandUseTerrainField.Cell c)->c.slope()*2+c.localRelief()
                +Math.min(64,c.waterDistance())*.04+unit(seed,c.cellX(),c.cellZ())*30)
                .thenComparingInt(LandUseTerrainField.Cell::cellZ).thenComparingInt(LandUseTerrainField.Cell::cellX));
        List<BlockBounds> occupied=new ArrayList<>();List<BlockPoint> result=new ArrayList<>();
        for(int i=0;i<sizes.size();i++) {
            var size=sizes.get(i);BlockBounds chosen=null;
            for(var c:choices) {
                int x=c.blockMinX()+(int)(unit(seed+i,c.cellX(),c.cellZ())*c.cellStepBlocks())-size.width()/2;
                int z=c.blockMinZ()+(int)(unit(seed-i-1,c.cellZ(),c.cellX())*c.cellStepBlocks())-size.depth()/2;
                BlockBounds box=new BlockBounds(x,z,x+size.width()-1,z+size.depth()-1);
                if(!field.planningBounds().contains(box.minX(),box.minZ())||!field.planningBounds().contains(box.maxX(),box.maxZ()))continue;
                if(occupied.stream().anyMatch(b->gap(b,box)<layout.spacingBlocks()))continue;
                var covering=field.cells().stream().filter(t->t.blockMinX()<=box.maxX()&&t.blockMinX()+t.cellStepBlocks()>box.minX()&&t.blockMinZ()<=box.maxZ()&&t.blockMinZ()+t.cellStepBlocks()>box.minZ()).toList();
                boolean dry=covering.stream().allMatch(t->t.sampled()&&!t.water()&&Double.isFinite(t.elevation())&&Math.abs(t.elevation()-c.elevation())<=8);
                long covered=covering.stream().mapToLong(t->(long)(Math.min(box.maxX()+1,t.blockMinX()+t.cellStepBlocks())-Math.max(box.minX(),t.blockMinX()))*(Math.min(box.maxZ()+1,t.blockMinZ()+t.cellStepBlocks())-Math.max(box.minZ(),t.blockMinZ()))).sum();
                if(!dry||covered!=(long)size.width()*size.depth())continue;chosen=box;break;
            }
            if(chosen==null)throw new IllegalArgumentException("CITY_SCATTER_CAPACITY_EXHAUSTED: placed="+result.size()+", requested="+sizes.size()+"; reduce units or spacing, or select a larger suitable site");
            occupied.add(chosen);result.add(new BlockPoint(chosen.minX(),chosen.minZ()));
        }
        return List.copyOf(result);
    }
    private static double gap(BlockBounds a,BlockBounds b){return Math.hypot(Math.max(0,Math.max(a.minX()-b.maxX()-1,b.minX()-a.maxX()-1)),Math.max(0,Math.max(a.minZ()-b.maxZ()-1,b.minZ()-a.maxZ()-1)));}
    private static double unit(long seed,int x,int z){long h=seed^x*0x9E3779B97F4A7C15L^z*0xBF58476D1CE4E5B9L;h=(h^(h>>>30))*0xBF58476D1CE4E5B9L;h=(h^(h>>>27))*0x94D049BB133111EBL;return ((h^(h>>>31))>>>11)*0x1.0p-53;}
}
