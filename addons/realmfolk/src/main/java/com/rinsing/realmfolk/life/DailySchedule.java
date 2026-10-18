package com.rinsing.realmfolk.life;

import com.rinsing.realmfolk.design.Design.Profession;

/** Time is world time, so unloading a city does not freeze its residents' day. */
public final class DailySchedule {
    public enum Phase { SLEEP, MEAL, SOCIAL, WORK }
    public static Phase phase(String identity,Profession job,long dayTime){
        int offset=Math.floorMod(identity.hashCode(),321)-160;
        int time=(int)Math.floorMod(dayTime-offset,24000);
        if(in(time,job.restStart,job.restEnd))return Phase.SLEEP;
        if(in(time,5000,7000))return job.servesMeals?Phase.WORK:Phase.MEAL;
        if(in(time,10000,12300))return Phase.SOCIAL;
        return Phase.WORK;
    }
    public static boolean in(int time,int start,int end){return Math.floorMod(time-start,24000)<Math.floorMod(end-start,24000);}
}
