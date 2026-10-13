package com.rinsing.realmfolk.design;

import java.util.*;
import java.util.function.Predicate;
import static com.rinsing.realmfolk.design.Design.*;

public final class DesignValidator {
    private final List<String> errors=new ArrayList<>();
    private final Predicate<String> itemExists;
    public DesignValidator(Predicate<String> itemExists){this.itemExists=itemExists;}
    public List<String> validate(Design d){
        errors.clear();
        require(d.version==2,"version must be 2");
        require(d.professions.size()<=4096&&!d.professions.isEmpty(),"professions: 1..4096 templates");
        require(d.realms.size()<=64&&d.realms.containsKey("default"),"realms requires default; maximum 64");
        require(d.cityTemplates.size()<=128&&d.cityTemplates.containsKey("default"),"cityTemplates requires default; maximum 128");
        require(d.cities.size()<=512&&d.realmBindings.size()<=512,"maximum 512 city plans / realm bindings");
        for(var entry:d.professions.entrySet()){
            String path="professions."+entry.getKey();Profession p=entry.getValue();id(entry.getKey(),path);
            text(p.name,1,48,path+".name");text(p.bio,0,600,path+".bio");
            text(p.commissionPurpose,0,600,path+".commissionPurpose");text(p.goodsPurpose,0,600,path+".goodsPurpose");
            require(Set.of("resident","farmer","smith","ranger","guard","merchant","mayor").contains(p.appearance),path+": unsupported appearance");
            require(CAPABILITIES.containsAll(p.capabilities),path+": unsupported capabilities "+difference(p.capabilities,CAPABILITIES));
            require(p.has("conversation"),path+": conversation is required for this NPC engine");
            if(p.has("law_enforcement")){require(p.has("combat")&&p.has("witness"),path+": law_enforcement requires combat + witness");require(!p.has("companion"),path+": law_enforcement conflicts with companion");}
            require(!(p.has("travel")&&p.has("companion")),path+": travel conflicts with companion");
            list(p.workplaceTerms,0,32,80,path+".workplaceTerms");
            range(p.health,1,200,path+".health");range(p.attack,0,40,path+".attack");range(p.speed,.05,.6,path+".speed");
            number(p.restStart,0,23999,path+".restStart");number(p.restEnd,0,23999,path+".restEnd");require(p.restStart!=p.restEnd,path+": rest interval cannot cover the entire day");
            number(p.recruitFavor,-100,100,path+".recruitFavor");number(p.patrolRadius,0,24,path+".patrolRadius");item(p.equipment,path+".equipment",true);
            item(p.commission.item,path+".commission.item",false);number(p.commission.count,1,64,path+".commission.count");number(p.commission.emeralds,0,64,path+".commission.emeralds");
            number(p.commission.affection,0,30,path+".commission.affection");number(p.commission.reputation,0,20,path+".commission.reputation");
            require(p.offers.size()<=12,path+": maximum 12 legacy offers");
            list(p.itemPools,0,16,64,path+".itemPools");number(p.stockSlots,1,54,path+".stockSlots");
            for(Offer offer:p.offers){item(offer.item,path+".offers.item",false);number(offer.count,1,64,path+".offers.count");number(offer.emeralds,1,64,path+".offers.emeralds");}
        }
        for(var entry:d.realms.entrySet()){
            String path="realms."+entry.getKey();id(entry.getKey(),path);settings(entry.getValue().population,d,path+".population");
            LawPolicy l=entry.getValue().law;text(entry.getValue().name,1,80,path+".name");
            number(l.witnessRadius,1,32,path+".law.witnessRadius");number(l.reportDelay,20,12000,path+".law.reportDelay");number(l.trespassGrace,20,2400,path+".law.trespassGrace");
            number(l.guardRadius,1,64,path+".law.guardRadius");number(l.chaseRadius,l.guardRadius,128,path+".law.chaseRadius");number(l.loseSightTicks,20,2400,path+".law.loseSightTicks");number(l.repeatedOffenses,2,20,path+".law.repeatedOffenses");
            for(int value:new int[]{l.theftUnit,l.damageFine,l.trespassFine,l.assaultFine,l.murderFine})number(value,1,64,path+".law.fine");
        }
        d.realmBindings.forEach((realm,rule)->{text(realm,1,160,"realm binding id");require(d.realms.containsKey(rule),"Unknown realm rule: "+rule);});
        d.cityTemplates.forEach((key,value)->{id(key,"cityTemplates");settings(value,d,"cityTemplates."+key);});
        d.cities.forEach((key,plan)->{
            text(key,1,240,"city id");require(d.cityTemplates.containsKey(plan.template),"Unknown city template: "+plan.template);settings(plan.overrides,d,"cities."+key);
            require(plan.keyPeople.size()<=16,"cities."+key+": maximum 16 individually authored key people");Set<String> seen=new HashSet<>();
            require(plan.functionAnswers.size()<=256,"cities."+key+": maximum 256 function rows");Set<String> functions=new HashSet<>();
            for(var row:plan.functionAnswers){text(row.functionTerm,1,80,"functionTerm");require(functions.add(row.functionTerm),"Duplicate function row: "+row.functionTerm);require(row.housing!=null,"function housing must be explicit");text(row.reason,1,600,"function reason");list(row.professions,0,64,64,"function professions");row.professions.forEach(id->require(d.professions.containsKey(id),"Unknown function profession: "+id));}
            for(KeyPerson p:plan.keyPeople){id(p.id,"key person id");require(seen.add(p.id),"Duplicate key person id: "+p.id);text(p.name,1,48,"key person name");text(p.bio,0,600,"key person bio");require(d.professions.containsKey(p.profession),"Unknown key profession: "+p.profession);}
        });
        if(d.realms.containsKey("default")){
            Settings base=d.realms.get("default").population;
            require(base.population!=null&&base.residentsPerHouse!=null&&base.saleHouseEvery!=null&&base.housePrice!=null&&base.workplaceCapacity!=null&&base.names!=null&&base.housingTerms!=null&&base.traits!=null&&base.jobs!=null,"default realm population must provide all settings");
        }
        if(errors.isEmpty()){
            Set<String> realms=new TreeSet<>(d.realmBindings.keySet());realms.add("__unbound__");
            Set<String> cities=new TreeSet<>(d.cities.keySet());cities.add("__unplanned__");
            // Only explicitly relevant combinations plus each realm default; avoid a realm x city Cartesian expansion.
            for(String realm:realms)resolved(d.resolve(realm,"__unplanned__"),"realm "+realm);
            for(String city:cities)resolved(d.resolve("__unbound__",city),"city "+city);
        }
        return List.copyOf(errors);
    }
    private void resolved(Resolved r,String path){
        int limit=r.settings().populationMode.equals("housing")?MAX_POPULATION:r.settings().population;
        int min=r.settings().jobs.values().stream().mapToInt(q->q.min).sum();require(min<=limit,path+": sum of minimum jobs exceeds population limit");
        Map<String,Long> keys=new HashMap<>();r.keyPeople().forEach(k->keys.merge(k.profession,1L,Long::sum));
        keys.forEach((job,n)->require(r.settings().jobs.containsKey(job)&&n<=r.settings().jobs.get(job).max,path+": key people need profession quota "+job));
        int seats=r.settings().jobs.entrySet().stream().mapToInt(e->Math.max(e.getValue().min,keys.getOrDefault(e.getKey(),0L).intValue())).sum();
        require(seats<=limit,path+": key people and job minima exceed population limit");
    }
    private void settings(Settings s,Design d,String path){
        if(s.populationMode!=null)require(Set.of("housing","fixed").contains(s.populationMode),path+": populationMode must be housing or fixed");
        if(s.housingOccupancy!=null)require(Double.isFinite(s.housingOccupancy)&&s.housingOccupancy>=0&&s.housingOccupancy<=1,path+": housingOccupancy must be 0..1");
        if(s.activities!=null){
            require(s.activities.size()<=8,path+": maximum 8 public activities");Set<String> ids=new HashSet<>();
            for(PublicActivity a:s.activities){
                id(a.id,path+".activity.id");require(ids.add(a.id),path+": duplicate activity id");text(a.name,1,32,path+".activity.name");
                number(a.start,0,23999,path+".activity.start");number(a.end,0,23999,path+".activity.end");require(a.start!=a.end,path+": activity interval cannot cover entire day");
                number(a.capacity,1,32,path+".activity.capacity");number(a.maxDistance,16,256,path+".activity.maxDistance");
                require(Double.isFinite(a.participation)&&a.participation>=0&&a.participation<=1,path+": activity participation must be 0..1");list(a.placeTerms,1,32,80,path+".activity.placeTerms");
            }
        }
        if(s.population!=null)number(s.population,0,256,path+".population");if(s.residentsPerHouse!=null)number(s.residentsPerHouse,1,8,path+".residentsPerHouse");
        if(s.residentsByFunction!=null){require(s.residentsByFunction.size()<=128,path+": maximum 128 housing capacity rules");s.residentsByFunction.forEach((term,n)->{text(term,1,80,path+".residentsByFunction");number(n,1,8,path+".residentsByFunction."+term);});}
        if(s.saleHouseEvery!=null)require(s.saleHouseEvery==0||s.saleHouseEvery>=2&&s.saleHouseEvery<=256,path+".saleHouseEvery: 0 or 2..256");
        if(s.housePrice!=null)number(s.housePrice,1,1024,path+".housePrice");if(s.workplaceCapacity!=null)number(s.workplaceCapacity,1,16,path+".workplaceCapacity");
        if(s.names!=null)list(s.names,1,256,32,path+".names");if(s.housingTerms!=null)list(s.housingTerms,1,32,80,path+".housingTerms");
        if(s.traits!=null){require(!s.traits.isEmpty()&&s.traits.size()<=32,path+": traits requires 1..32 entries");s.traits.forEach((key,w)->{text(key,1,32,path+".trait");require(Double.isFinite(w)&&w>0&&w<=1000,path+": trait weight must be >0 and <=1000");});}
        if(s.jobs!=null){require(!s.jobs.isEmpty()&&s.jobs.size()<=64,path+": jobs requires 1..64 entries");s.jobs.forEach((job,q)->{
            require(d.professions.containsKey(job),path+": unknown profession "+job);number(q.min,0,256,path+".min");number(q.max,q.min,256,path+".max");require(Double.isFinite(q.weight)&&q.weight>=0&&q.weight<=1000,path+": weight must be 0..1000");});}
    }
    private void item(String id,String path,boolean air){require(id!=null&&id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")&&itemExists.test(id)&&(air||!id.equals("minecraft:air")),path+": unknown/invalid item "+id);}
    private void range(Range r,double min,double max,String path){require(Double.isFinite(r.min)&&Double.isFinite(r.max)&&r.min>=min&&r.max<=max&&r.min<=r.max,path+": invalid range");}
    private void list(List<String> values,int min,int max,int length,String path){require(values.size()>=min&&values.size()<=max,path+": invalid length");values.forEach(v->text(v,1,length,path));}
    private void id(String id,String path){require(id!=null&&id.matches("[a-z][a-z0-9_.-]{0,63}"),path+": expected stable identifier [a-z][a-z0-9_.-]{0,63}");}
    private void text(String value,int min,int max,String path){require(value!=null&&value.length()>=min&&value.length()<=max,path+": invalid text length");}
    private void number(int n,int min,int max,String path){require(n>=min&&n<=max,path+": expected "+min+".."+max);}
    private void require(boolean valid,String message){if(!valid&&errors.size()<64)errors.add(message);}
    private static Set<String> difference(Set<String> values,Set<String> allowed){var result=new TreeSet<>(values);result.removeAll(allowed);return result;}
}
