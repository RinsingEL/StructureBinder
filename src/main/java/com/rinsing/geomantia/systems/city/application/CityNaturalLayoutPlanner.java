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
            int retreat=layout.setbackBlocks()+maxSpan/2+(i/perRow)*(maxSpan+8);
            int span=spans.get(i);Slot chosen=null;
            // The fixed retreat is only a first guess. A stair-stepped shore gives axis-aligned edge normals that can run
            // along a diagonal coast into the water, bays and spits can put the far side in water, and coarse D3 cells
            // hide wet edges. So: retreat along a normal averaged over nearby samples, then step further landward and
            // slide a little along the line until the whole footprint is dry, inside the plan and clear of earlier slots.
            int slideLimit=(int)Math.min(pitch/2,Math.max(0,total-i%perRow*pitch));
            search:
            for(int slide=0;slide<=slideLimit;slide+=4)
                for(int extra=0;extra<=maxSpan+field.cellStepBlocks()*2;extra+=2) {
                    double at=Math.min(total,i%perRow*pitch+slide);
                    int index=0;while(index+1<distances.length&&distances[index+1]<=at)index++;
                    double x=line.samples().get(index).point().x(),z=line.samples().get(index).point().z();
                    if(index+1<distances.length&&distances[index+1]>distances[index]) {
                        double part=(at-distances[index])/(distances[index+1]-distances[index]);var next=line.samples().get(index+1).point();
                        x+=(next.x()-x)*part;z+=(next.z()-z)*part;
                    }
                    double nx=0,nz=0;
                    for(int k=Math.max(0,index-NORMAL_WINDOW);k<=Math.min(line.samples().size()-1,index+NORMAL_WINDOW);k++){nx+=line.samples().get(k).normalX();nz+=line.samples().get(k).normalZ();}
                    double length=Math.hypot(nx,nz);
                    if(length<1e-9){nx=line.samples().get(index).normalX();nz=line.samples().get(index).normalZ();length=1;}
                    nx/=length;nz/=length;
                    int cx=(int)Math.round(x-nx*(retreat+extra)),cz=(int)Math.round(z-nz*(retreat+extra));
                    BlockBounds box=new BlockBounds(cx-span/2,cz-span/2,cx-span/2+span-1,cz-span/2+span-1);
                    if(!dryFootprint(field,box,LINE_WATER_MARGIN))continue;
                    boolean clash=false;for(int j=0;j<out.size()&&!clash;j++)clash=overlaps(out.get(j),spans.get(j),box);
                    if(clash)continue;
                    chosen=new Slot(new BlockPoint(box.minX(),box.minZ()),new BlockPoint((int)Math.round(cx+nx*1000),(int)Math.round(cz+nz*1000)));
                    break search;
                }
            if(chosen==null)throw new IllegalArgumentException("CITY_TERRAIN_LINE_NO_DRY_SLOT:"+layout.lineRef()+": unit="+i
                    +"; the landward side of this line is water or too narrow here; reduce rows or units, or choose another line");
            out.add(chosen);
        }
        return List.copyOf(out);
    }
    /** Extra blocks around a terrain-line footprint that must also be dry: coarse D3 cells can hide a wet edge. */
    private static final int LINE_WATER_MARGIN=2;
    /** Samples on each side averaged into the retreat direction, so one stair step does not decide it. */
    private static final int NORMAL_WINDOW=3;
    /** Every block of the box (plus margin) lies on sampled, dry, finite cells, and the footprint itself is not a cliff. */
    static boolean dryFootprint(LandUseTerrainField field,BlockBounds box,int margin) {
        BlockBounds wide=new BlockBounds(box.minX()-margin,box.minZ()-margin,box.maxX()+margin,box.maxZ()+margin);
        if(!field.planningBounds().contains(wide.minX(),wide.minZ())||!field.planningBounds().contains(wide.maxX(),wide.maxZ()))return false;
        int step=field.cellStepBlocks();double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;
        for(int cz=Math.floorDiv(wide.minZ(),step);cz<=Math.floorDiv(wide.maxZ(),step);cz++)
            for(int cx=Math.floorDiv(wide.minX(),step);cx<=Math.floorDiv(wide.maxX(),step);cx++) {
                var cell=field.cellAt(cx*step,cz*step);
                if(cell.isEmpty())return false;var c=cell.get();
                if(!c.sampled()||c.water()||!Double.isFinite(c.elevation()))return false;
                boolean inside=c.blockMinX()<=box.maxX()&&c.blockMinX()+step>box.minX()&&c.blockMinZ()<=box.maxZ()&&c.blockMinZ()+step>box.minZ();
                if(inside){low=Math.min(low,c.elevation());high=Math.max(high,c.elevation());}
            }
        return high-low<=8;
    }
    private static boolean overlaps(Slot slot,int span,BlockBounds box) {
        int x=slot.origin().x(),z=slot.origin().z();
        return x<=box.maxX()&&x+span-1>=box.minX()&&z<=box.maxZ()&&z+span-1>=box.minZ();
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
