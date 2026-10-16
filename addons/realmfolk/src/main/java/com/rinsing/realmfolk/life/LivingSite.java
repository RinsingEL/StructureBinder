package com.rinsing.realmfolk.life;

import com.rinsing.realmfolk.model.Society.Point;
import java.util.Set;

/** An instance of a physical facility or an explicitly authored activity position. */
public final class LivingSite {
    public String id="", kind="", block="minecraft:air", facing="north", use="building", room="", name="", adapter="stand";
    public Point object, approach;
    public double seatHeight=.5;
    public String behavior="", sourceRule="";
    public boolean authored;
    public Double surfaceHeight;
    /** World-oriented offsets from the physical anchor's centre; head/foot parts remain separate. */
    public double[] useOffset={0,0,0};
    public java.util.Map<String,String> requiredState=new java.util.LinkedHashMap<>();
    public java.util.List<Part> requiredParts=new java.util.ArrayList<>();
    public record Part(Point position,java.util.List<String> blocks,java.util.Map<String,String> state,boolean sameBlock) {}
    public double height(){return surfaceHeight==null?seatHeight:surfaceHeight;}
    /** Old exports predate the authored flag; explicit room/use/name survive rule adoption. */
    public void inheritAuthorMetadata(LivingSite previous){
        if(previous==null)return;
        boolean legacy=(previous.sourceRule==null||previous.sourceRule.isEmpty())&&
                ((previous.room!=null&&!previous.room.isBlank())||!Set.of("", "building").contains(previous.use==null?"":previous.use)
                        ||(previous.name!=null&&!previous.name.isBlank()&&!previous.name.equals(previous.kind)));
        if(previous.authored||legacy){authored=true;use=previous.use;room=previous.room;name=previous.name;}
    }
    public String behaviorId(){
        if(behavior!=null&&!behavior.isBlank())return behavior;
        return switch(adapter){case "bed"->"realmfolk:bed";case "marker_seat"->"realmfolk:seat";case "stand"->kind.equals("bed")?"realmfolk:bed":"realmfolk:work";default->adapter;};
    }
    public boolean available;
    /** Null in older saves means not yet checked; retain ownership until a loaded-world check. */
    public Boolean present;
    public boolean retainsClaim(){return !Boolean.FALSE.equals(present);}
    public boolean housing(){return kind.equals("bed")&&(use.equals("resident")||use.equals("building")||use.equals("staff"));}
    public LivingSite(){}
    public LivingSite(String id,String kind,Point object,Point approach,String block){this.id=id;this.kind=kind;this.object=object;this.approach=approach;this.block=block;}
    public static boolean withinUseDistance(double x,double y,double z,Point point){
        double dx=x-(point.x()+.5),dy=y-point.y(),dz=z-(point.z()+.5);
        return dx*dx+dy*dy+dz*dz<=3;
    }
}
