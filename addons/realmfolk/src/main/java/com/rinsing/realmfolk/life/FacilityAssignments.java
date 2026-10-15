package com.rinsing.realmfolk.life;

import com.rinsing.realmfolk.model.Society;
import java.util.*;
import static com.rinsing.realmfolk.model.Society.*;

/** Stable claims; housing loss never removes a person or their relationships. */
public final class FacilityAssignments {
    public static LivingSite site(Property property,String id){
        return property==null||property.livingSites==null?null:property.livingSites.stream().filter(s->s.id.equals(id)).findFirst().orElse(null);
    }
    public static List<LivingSite> beds(Property p){return p.livingSites==null?List.of():p.livingSites.stream().filter(s->s.available&&s.housing()).sorted(Comparator.comparing(s->s.id)).toList();}
    public static List<LivingSite> jobs(Property p){return p.livingSites==null?List.of():p.livingSites.stream().filter(s->s.available&&s.kind.equals("work")).sorted(Comparator.comparing(s->s.id)).toList();}
    public static boolean repair(Society society){
        boolean changed=false;Set<String> beds=new HashSet<>(),jobs=new HashSet<>();
        var people=society.citizens.values().stream().filter(c->!c.dead&&!c.visitor&&c.entityType.equals("realmfolk:citizen"))
            .sorted(Comparator.comparing((Citizen c)->!c.keyPerson).thenComparing(c->c.id)).toList();
        // Existing valid claims win before new residents or migrations acquire vacant sites.
        for(Citizen c:people){
            Property home=society.properties.get(c.property),work=society.properties.get(c.workProperty);
            LivingSite bed=site(home,c.bedSite),job=site(work,c.workSite);
            if(bed!=null&&bed.retainsClaim()&&bed.housing())beds.add(home.id+"/"+bed.id);
            if(job!=null&&job.retainsClaim()&&job.kind.equals("work"))jobs.add(work.id+"/"+job.id);
        }
        Set<String> seenBeds=new HashSet<>(),seenJobs=new HashSet<>();
        for(Citizen c:people){
            Property home=society.properties.get(c.property);
            if(home!=null&&home.livingSites!=null){
                LivingSite bed=site(home,c.bedSite);
                if(bed==null||!bed.retainsClaim()||!bed.housing()||!seenBeds.add(home.id+"/"+bed.id)){
                    bed=beds(home).stream().filter(s->!beds.contains(home.id+"/"+s.id)).findFirst().orElse(null);
                    String id=bed==null?"":bed.id;if(!Objects.equals(id,c.bedSite)){c.bedSite=id;changed=true;}
                    if(bed!=null){beds.add(home.id+"/"+id);seenBeds.add(home.id+"/"+id);}
                }
                if(bed!=null&&bed.approach!=null&&!Objects.equals(c.home,bed.approach)){c.home=bed.approach;changed=true;}
            }
            Property work=society.properties.get(c.workProperty);
            if(work!=null&&work.livingSites!=null&&c.profession!=null&&!c.profession.workplaceTerms.isEmpty()){
                LivingSite job=site(work,c.workSite);
                if(job==null||!job.retainsClaim()||!job.kind.equals("work")||!seenJobs.add(work.id+"/"+job.id)){
                    job=jobs(work).stream().filter(s->!jobs.contains(work.id+"/"+s.id)).findFirst().orElse(null);
                    String id=job==null?"":job.id;if(!Objects.equals(id,c.workSite)){c.workSite=id;changed=true;}
                    if(job!=null){jobs.add(work.id+"/"+id);seenJobs.add(work.id+"/"+id);}
                }
                if(job!=null&&job.approach!=null&&!Objects.equals(c.work,job.approach)){c.work=job.approach;changed=true;}
            }
        }
        return changed;
    }
}
