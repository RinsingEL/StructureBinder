package com.rinsing.realmfolk.life;

import com.rinsing.realmfolk.model.Society.Point;

/** An instance of a physical facility or an explicitly authored activity position. */
public final class LivingSite {
    public String id="", kind="", block="minecraft:air", facing="north", use="building", room="", name="", adapter="stand";
    public Point object, approach;
    public double seatHeight=.5;
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
