package com.rinsing.realmfolk.design;

import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.rinsing.realmfolk.design.Design.*;
import static com.rinsing.realmfolk.model.Society.*;
import com.rinsing.realmfolk.model.Society;
import com.rinsing.realmfolk.life.FacilityAssignments;

/** Pure, deterministic planner shared by preview, publication and discovery. Never edits its inputs. */
public final class PopulationPlanner {
    public record Sample(String name,String profession,String trait,String home,String workplace,boolean keyPerson) {}
    public record Summary(String town,String populationMode,double occupancy,int target,int existing,int created,int housingBuildings,int housingSlots,int freeHousingSlots,Map<String,Integer> professions,
                          Map<String,Integer> workplaceTerms,List<String> shortages,List<Sample> samples) {}
    public record Plan(List<Citizen> citizens,Summary summary) {}
    private record Bed(Property property,Point point,int index,String siteId) {}

    public static String cityKey(Town t){return t.planningKey!=null&&!t.planningKey.isBlank()?t.planningKey:t.sourceCity==null||t.sourceCity.isBlank()?t.id:t.sourceCity;}
    public static Plan plan(Design design,Society world,Town town,long seed,int revision){
        Resolved resolved=design.resolve(town.realm,cityKey(town));Settings settings=resolved.settings();
        List<Property> sites=world.properties.values().stream().filter(p->p.town.equals(town.id)).sorted(Comparator.comparing(p->p.id)).toList();
        List<Citizen> existing=world.citizens.values().stream().filter(c->c.town.equals(town.id)&&!c.visitor&&"realmfolk:citizen".equals(c.entityType)).toList();
        Map<String,Integer> counts=new TreeMap<>(),workers=new HashMap<>(),terms=new TreeMap<>();
        Set<Point> occupied=new HashSet<>();
        for(Citizen c:existing){counts.merge(c.role,1,Integer::sum);occupied.add(c.home);
            if(c.workSite!=null&&!c.workSite.isBlank())workers.merge(c.workProperty==null||c.workProperty.isBlank()?c.property:c.workProperty,1,Integer::sum);}
        List<Bed> beds=new ArrayList<>();int totalBeds=0,houses=0;
        for(Property p:sites){
            if(p.terms!=null)for(String term:p.terms)terms.merge(term,jobCapacity(p,settings),Integer::sum);
            if(p.slots==null||p.terms==null||Collections.disjoint(p.terms,settings.housingTerms)||p.price>0||p.owner.startsWith("player:"))continue;
            if(p.livingSites==null)continue;
            var physicalBeds=FacilityAssignments.beds(p);int n=physicalBeds.size();totalBeds+=n;if(n>0)houses++;
            for(int i=0;i<n;i++){var bed=physicalBeds.get(i);if(existing.stream().noneMatch(c->c.property.equals(p.id)&&bed.id.equals(c.bedSite))&&!occupied.contains(bed.approach))beds.add(new Bed(p,bed.approach,i,bed.id));}
        }
        int target=settings.populationMode.equals("housing")?Math.min(MAX_POPULATION,(int)Math.floor(totalBeds*settings.housingOccupancy)):settings.population;
        int freeBeds=beds.size();
        List<Citizen> added=new ArrayList<>();List<String> shortages=new ArrayList<>();
        if(settings.populationMode.equals("housing")&&totalBeds*settings.housingOccupancy>MAX_POPULATION)shortages.add("住宅推算超过单城 "+MAX_POPULATION+" 人保护上限");
        // Missing minima/key people reserve population capacity even when a workplace is not yet discovered.
        List<KeyPerson> missingKeys=resolved.keyPeople().stream().filter(k->existing.stream().noneMatch(c->k.id.equals(c.keyPersonId))).sorted(Comparator.comparing(k->k.id)).toList();
        Map<String,Integer> reserved=new TreeMap<>();
        settings.jobs.forEach((job,q)->reserved.put(job,Math.max(0,q.min-counts.getOrDefault(job,0))));
        Map<String,Integer> keyCounts=new HashMap<>();missingKeys.forEach(k->keyCounts.merge(k.profession,1,Integer::sum));
        keyCounts.forEach((job,n)->reserved.merge(job,n,Math::max));
        for(KeyPerson key:missingKeys){
            if(counts.getOrDefault(key.profession,0)>=settings.jobs.get(key.profession).max){shortages.add("关键人物职业名额已满: "+key.id);continue;}
            if(add(design,world,town,seed,revision,settings,target,beds,sites,workers,counts,added,existing.size(),key.profession,key))reserved.compute(key.profession,(k,n)->Math.max(0,n-1));
            else shortages.add("关键人物等待住房/岗位: "+key.id);
        }
        for(String job:new TreeSet<>(settings.jobs.keySet())){
            int need=Math.max(0,settings.jobs.get(job).min-counts.getOrDefault(job,0));
            // Keep a vacancy for an authored character instead of replacing them with a filler.
            long missing=missingKeys.stream().filter(k->k.profession.equals(job)&&added.stream().noneMatch(c->k.id.equals(c.keyPersonId))).count();
            for(int i=0;i<Math.max(0,need-missing);i++){
                if(!add(design,world,town,seed,revision,settings,target,beds,sites,workers,counts,added,existing.size(),job,null))break;
                reserved.compute(job,(k,n)->Math.max(0,n-1));
            }
        }
        while(!beds.isEmpty()&&existing.size()+added.size()+reserved.values().stream().mapToInt(Integer::intValue).sum()<target){
            Map<String,Double> eligible=new TreeMap<>();
            for(var entry:settings.jobs.entrySet()){
                String job=entry.getKey();Quota q=entry.getValue();
                if(q.weight>0&&counts.getOrDefault(job,0)+reserved.getOrDefault(job,0)<q.max&&workplace(design.professions.get(job),sites,workers,settings)!=null)eligible.put(job,q.weight);
            }
            if(eligible.isEmpty()){shortages.add("补员受职业权重、名额或可用工作地点限制；检查 jobs 与工作用途");break;}
            Bed bed=beds.get(0);String job=weighted(eligible,unit(seed+":"+town.id+":"+bed.property.id+":"+bed.index+":job"));
            if(!add(design,world,town,seed,revision,settings,target,beds,sites,workers,counts,added,existing.size(),job,null))break;
        }
        settings.jobs.forEach((job,q)->{int shortfall=q.min-counts.getOrDefault(job,0);if(shortfall>0)shortages.add(job+" 最低岗位缺 "+shortfall);});
        if(existing.size()+added.size()<target)shortages.add("人口目标尚缺 "+(target-existing.size()-added.size())+"；只使用已确认住房与工作地点");
        if(existing.size()>target)shortages.add("已有居民超出新目标，保留 "+(existing.size()-target)+" 人");
        List<Sample> samples=added.stream().limit(5).map(c->new Sample(c.name,c.profession.name,c.trait,c.property,c.workProperty,c.keyPerson)).toList();
        return new Plan(List.copyOf(added),new Summary(town.id,settings.populationMode,settings.housingOccupancy,target,existing.size(),added.size(),houses,totalBeds,freeBeds,counts,terms,shortages.stream().limit(32).toList(),samples));
    }
    private static boolean add(Design design,Society world,Town town,long seed,int revision,Settings settings,int target,List<Bed> beds,List<Property> sites,Map<String,Integer> workers,
                               Map<String,Integer> counts,List<Citizen> added,int existing,String job,KeyPerson key){
        if(beds.isEmpty()||existing+added.size()>=target)return false;
        Profession profile=design.professions.get(job);Property work=workplace(profile,sites,workers,settings);if(work==null)return false;
        Bed bed=beds.stream().min(Comparator.<Bed>comparingDouble(b->profile.workplaceTerms.isEmpty()
                ?world.citizens.values().stream().filter(c->!c.visitor&&c.property.equals(b.property.id)).count()+added.stream().filter(c->c.property.equals(b.property.id)).count()
                :b.point.distanceSquared(FacilityAssignments.jobs(work).get(0).approach))
                .thenComparing(b->b.property.id).thenComparing(Bed::siteId)).orElseThrow();
        beds.remove(bed);String id=UUID.nameUUIDFromBytes((seed+":"+town.id+":"+bed.property.id+":"+bed.siteId).getBytes(StandardCharsets.UTF_8)).toString();
        if(world.citizens.containsKey(id))return false; // Dead residents keep their identity; no silent resurrection.
        String name=key==null?settings.names.get((int)(unit(id+":name")*settings.names.size()))+"·"+id.substring(0,4):key.name;
        Property actual=profile.workplaceTerms.isEmpty()?bed.property:work;
        var station=profile.workplaceTerms.isEmpty()?null:FacilityAssignments.jobs(actual).stream()
                .filter(s->world.citizens.values().stream().noneMatch(c->actual.id.equals(c.workProperty)&&s.id.equals(c.workSite))
                        &&added.stream().noneMatch(c->actual.id.equals(c.workProperty)&&s.id.equals(c.workSite))).findFirst().orElse(null);
        Citizen c=new Citizen(id,name,town.id,bed.property.id,job,bed.point,station==null?bed.point:station.approach);
        c.bedSite=bed.siteId;c.workSite=station==null?"":station.id;
        c.workProperty=actual.id;c.keyPerson=key!=null;c.keyPersonId=key==null?"":key.id;
        c.profession=DesignCodec.copy(profile,Profession.class);c.bio=key!=null&&!key.bio.isBlank()?key.bio:profile.bio;
        c.trait=weighted(new TreeMap<>(settings.traits),unit(id+":trait"));c.designRevision=revision;
        c.health=roll(profile.health,id+":health");c.attack=roll(profile.attack,id+":attack");c.speed=roll(profile.speed,id+":speed");
        added.add(c);counts.merge(job,1,Integer::sum);if(!profile.workplaceTerms.isEmpty())workers.merge(actual.id,1,Integer::sum);return true;
    }
    private static Property workplace(Profession profile,List<Property> sites,Map<String,Integer> workers,Settings settings){
        if(profile.workplaceTerms.isEmpty())return sites.isEmpty()?null:sites.get(0);
        return sites.stream().filter(p->p.price==0&&!p.owner.startsWith("player:")&&p.terms!=null&&!Collections.disjoint(p.terms,profile.workplaceTerms)&&workers.getOrDefault(p.id,0)<jobCapacity(p,settings))
                .min(Comparator.<Property>comparingDouble(p->workers.getOrDefault(p.id,0)/(double)jobCapacity(p,settings)).thenComparing(p->p.id)).orElse(null);
    }
    private static int jobCapacity(Property p,Settings settings){return Math.min(settings.workplaceCapacity,FacilityAssignments.jobs(p).size());}
    public static int housingCapacity(Settings settings,List<String> terms){
        // A multi-use building contributes once. Explicit matching capacities use the maximum, not a sum.
        return settings.residentsByFunction==null?settings.residentsPerHouse:terms.stream()
            .filter(settings.housingTerms::contains).filter(settings.residentsByFunction::containsKey)
            .mapToInt(settings.residentsByFunction::get).max().orElse(settings.residentsPerHouse);
    }
    public static boolean protectedCitizen(Society s,Citizen c){
        return c.keyPerson||c.dead||!c.leader.isBlank()||!c.mode.equals("HOME")||s.players.values().stream().anyMatch(j->j.affection.containsKey(c.id)||j.quests.keySet().stream().anyMatch(k->k.endsWith(":"+c.id))||j.offenses.stream().anyMatch(o->c.id.equals(o.witness)));
    }
    private static double roll(Range range,String key){return range.min+(range.max-range.min)*unit(key);}
    private static double unit(String key){return (UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).getMostSignificantBits()>>>11)*0x1.0p-53;}
    private static String weighted(Map<String,Double> weights,double value){double at=value*weights.values().stream().mapToDouble(Double::doubleValue).sum();String last="";for(var entry:weights.entrySet()){last=entry.getKey();at-=entry.getValue();if(at<0)return last;}return last;}
}
