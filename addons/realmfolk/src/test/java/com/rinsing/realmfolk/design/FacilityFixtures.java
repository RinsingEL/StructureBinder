package com.rinsing.realmfolk.design;

import com.rinsing.realmfolk.life.LivingSite;
import com.rinsing.realmfolk.model.Society.Property;
import java.util.ArrayList;

/** Explicit, synthetic physical capacities for pure planner tests, never game acceptance evidence. */
final class FacilityFixtures {
    static void physical(Property p,int workCount){
        p.livingSites=new ArrayList<>();
        for(int i=0;i<p.slots.size();i++){
            var at=p.slots.get(i);var bed=new LivingSite("bed:"+i,"bed",at.offset(0,0,1),at,"minecraft:red_bed");bed.available=true;p.livingSites.add(bed);
        }
        for(int i=0;i<workCount;i++){
            var at=p.work.offset(i,0,0);var job=new LivingSite("work:"+i,"work",at.offset(0,0,1),at,"minecraft:crafting_table");job.available=true;p.livingSites.add(job);
        }
    }
}
