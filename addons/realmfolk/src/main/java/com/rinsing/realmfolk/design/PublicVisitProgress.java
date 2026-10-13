package com.rinsing.realmfolk.design;

/** A failed walk yields to the ordinary routine for this occurrence; it never teleports the citizen. */
public final class PublicVisitProgress {
    private Object assignment;
    private long started,lastProgress;
    private double nearest;
    private boolean blocked;
    public boolean allows(Object next,long tick,double distanceSquared){
        if(!next.equals(assignment)){
            assignment=next;started=lastProgress=tick;nearest=distanceSquared;blocked=false;
        }
        if(blocked)return false;
        if(distanceSquared<=3){lastProgress=tick;nearest=distanceSquared;return true;}
        if(distanceSquared<nearest-1){nearest=distanceSquared;lastProgress=tick;}
        if(tick-lastProgress>=400||tick-started>=1800)blocked=true;
        return !blocked;
    }
}
