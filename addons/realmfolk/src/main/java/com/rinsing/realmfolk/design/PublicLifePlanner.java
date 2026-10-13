package com.rinsing.realmfolk.design;

import com.rinsing.realmfolk.model.Society;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.rinsing.realmfolk.model.Society.*;
import static com.rinsing.realmfolk.design.Design.*;

/** Public visits change today's destination, never ownership, home, job or permanent identity. */
public final class PublicLifePlanner {
    public record Visit(String activity,String name,String place,Point point,long occasion) {}
    public record ActivitySummary(String id,String name,int start,int end,boolean scheduled,int eligiblePeople,int selectedPeople,int places,int points) {}
    public record Plan(Map<String,Visit> visits,List<ActivitySummary> activities) {}
    private record Slot(Property property,Point point) {}

    public static Plan plan(Society society,Town town,Settings settings,long dayTime,Set<String> available){
        Map<String,Visit> visits=new LinkedHashMap<>();Set<Point> used=new HashSet<>();List<ActivitySummary> summaries=new ArrayList<>();
        int time=(int)Math.floorMod(dayTime,24000);
        for(PublicActivity activity:settings.activities){
            int elapsed=Math.floorMod(time-activity.start,24000),duration=Math.floorMod(activity.end-activity.start,24000),selected=0;
            long day=Math.floorDiv(dayTime-elapsed,24000);
            List<Property> places=society.properties.values().stream().filter(p->p.town.equals(town.id)&&eligiblePlace(p,activity)).sorted(Comparator.comparing(p->p.id)).toList();
            List<Slot> slots=new ArrayList<>();for(Property p:places)for(Point point:p.publicPoints)slots.add(new Slot(p,point));
            List<Citizen> people=society.citizens.values().stream().filter(c->c.town.equals(town.id)&&available.contains(c.id)&&eligibleCitizen(c,time)&&!visits.containsKey(c.id))
                .sorted(Comparator.<Citizen>comparingDouble(c->unit(c.id+":"+activity.id+":"+day+":order")).thenComparing(c->c.id)).toList();
            boolean scheduled=elapsed<duration;
            if(scheduled)for(Citizen c:people){
                if(selected>=activity.capacity)break;
                String key=c.id+":"+activity.id+":"+day;
                if(unit(key+":join")>=activity.participation)continue;
                int spread=Math.min(400,duration/4);
                if(elapsed<(int)(unit(key+":arrive")*spread)||elapsed>=duration-(int)(unit(key+":leave")*spread))continue;
                Slot choice=slots.stream().filter(slot->!used.contains(slot.point)&&c.home.distanceSquared(slot.point)<=(double)activity.maxDistance*activity.maxDistance)
                    .min(Comparator.comparingDouble(slot->c.home.distanceSquared(slot.point))).orElse(null);
                if(choice==null)continue;
                visits.put(c.id,new Visit(activity.id,activity.name,choice.property.id,choice.point,day));used.add(choice.point);selected++;
            }
            summaries.add(new ActivitySummary(activity.id,activity.name,activity.start,activity.end,scheduled,people.size(),selected,places.size(),slots.size()));
        }
        return new Plan(Collections.unmodifiableMap(visits),List.copyOf(summaries));
    }
    public static boolean eligiblePlace(Property p,PublicActivity activity){return p.publicPoints!=null&&!p.publicPoints.isEmpty()&&p.terms!=null&&!Collections.disjoint(p.terms,activity.placeTerms)&&p.price==0&&"PUBLIC".equals(p.access)&&!p.owner.startsWith("player:");}
    public static boolean eligibleCitizen(Citizen c,int time){return !c.dead&&"realmfolk:citizen".equals(c.entityType)&&c.profession!=null&&c.profession.has("public_life")&&!c.profession.has("law_enforcement")&&c.leader.isBlank()&&c.mode.equals("HOME")&&!resting(c.profession,time);}
    public static boolean resting(Profession p,long time){return p.restStart<p.restEnd?time>=p.restStart&&time<p.restEnd:time>=p.restStart||time<p.restEnd;}
    private static double unit(String key){return (UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).getMostSignificantBits()>>>11)*0x1.0p-53;}
}
