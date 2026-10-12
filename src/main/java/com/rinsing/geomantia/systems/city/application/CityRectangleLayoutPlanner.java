package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.model.*;
import java.util.*;

/** Packs complete child envelopes; a courtyard reserves public ground instead of a center building. */
public final class CityRectangleLayoutPlanner {
    public record Size(int width,int depth) {
        public Size {if(width<1||depth<1)throw new IllegalArgumentException("CITY_RECTANGLE_EMPTY_ENVELOPE");}
    }
    public static List<BlockPoint> plan(String algorithm,CityBlueprint.RectangleLayout layout,BlockPoint anchor,
                                       int directionDegrees,List<Size> sizes) {
        if(sizes.isEmpty())return List.of();
        if(Math.floorMod(directionDegrees,90)!=0)throw new IllegalArgumentException("CITY_RECTANGLE_CARDINAL_AXIS_REQUIRED");
        int gap=layout.gapBlocks();List<BlockPoint> out=new ArrayList<>();
        if("COURTYARD".equals(algorithm)) {
            if(sizes.size()>4)throw new IllegalArgumentException("CITY_RECTANGLE_COURTYARD_FOUR_SIDES: nest complete arrays for additional members");
            int left=anchor.x()-layout.courtWidth()/2,top=anchor.z()-layout.courtDepth()/2;
            for(int i=0;i<sizes.size();i++){
                var s=sizes.get(i);int x,z;
                if(i==0){x=anchor.x()-s.width()/2;z=top-gap-s.depth();}
                else if(i==1){x=left-gap-s.width();z=anchor.z()-s.depth()/2;}
                else if(i==2){x=left+layout.courtWidth()+gap;z=anchor.z()-s.depth()/2;}
                else{x=anchor.x()-s.width()/2;z=top+layout.courtDepth()+gap;}
                out.add(new BlockPoint(x,z));
            }
            for(int attempt=0;attempt<512;attempt++){
                boolean collision=false;
                for(int i=0;i<out.size();i++)for(int j=0;j<i;j++){
                    var a=out.get(i);var b=out.get(j);var sa=sizes.get(i);var sb=sizes.get(j);
                    if(a.x()<b.x()+sb.width()+gap&&a.x()+sa.width()+gap>b.x()&&a.z()<b.z()+sb.depth()+gap&&a.z()+sa.depth()+gap>b.z()){
                        collision=true;out.set(i,i==1?new BlockPoint(a.x()-1,a.z()):i==2?new BlockPoint(a.x()+1,a.z()):new BlockPoint(a.x(),a.z()+1));}
                }
                if(!collision)return List.copyOf(out);
            }
            throw new IllegalArgumentException("CITY_RECTANGLE_PACKING_BUDGET");
        }
        if(!Set.of("GRID","LINEAR").contains(algorithm))throw new IllegalArgumentException("CITY_RECTANGLE_ALGORITHM_UNSUPPORTED:"+algorithm);
        int columns="LINEAR".equals(algorithm)?sizes.size():layout.columns(),rows=(sizes.size()+columns-1)/columns;
        boolean vertical=Math.floorMod(directionDegrees,180)!=0;
        int[] widths=new int[columns],depths=new int[rows];
        for(int i=0;i<sizes.size();i++){var s=sizes.get(i);widths[i%columns]=Math.max(widths[i%columns],vertical?s.depth():s.width());depths[i/columns]=Math.max(depths[i/columns],vertical?s.width():s.depth());}
        for(int i=0;i<sizes.size();i++){
            var s=sizes.get(i);int x=0,z=0;
            for(int c=0;c<i%columns;c++)x+=widths[c]+gap;for(int r=0;r<i/columns;r++)z+=depths[r]+gap;
            if("LINEAR".equals(algorithm)){int extra=depths[0]-(vertical?s.width():s.depth());z+=switch(layout.crossAlign()){case "END"->extra;case "CENTER"->extra/2;default->0;};}
            // Opt-in: mixed-size GRID members sit centered in their cell instead of hugging its corner.
            else if("CENTER".equals(layout.crossAlign())){x+=(widths[i%columns]-(vertical?s.depth():s.width()))/2;z+=(depths[i/columns]-(vertical?s.width():s.depth()))/2;}
            int dx=vertical?z:x,dz=vertical?x:z;
            if(Set.of(180,270).contains(Math.floorMod(directionDegrees,360))){dx=-dx-s.width();dz=-dz-s.depth();}
            out.add(new BlockPoint(anchor.x()+dx,anchor.z()+dz));
        }
        return List.copyOf(out);
    }
    public static BlockBounds court(CityBlueprint.RectangleLayout layout,BlockPoint center){
        int x=center.x()-layout.courtWidth()/2,z=center.z()-layout.courtDepth()/2;
        return new BlockBounds(x,z,x+layout.courtWidth()-1,z+layout.courtDepth()-1);
    }
}
