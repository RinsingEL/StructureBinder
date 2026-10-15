package com.rinsing.realmfolk.life;

import com.rinsing.realmfolk.model.Society;
import java.util.*;
import java.util.function.Predicate;
import static com.rinsing.realmfolk.model.Society.*;

public final class ShelterPlanner {
    public static Point choose(Society world,Citizen citizen,Point from,Predicate<Point> indoors,Set<Point> occupied){
        if(!citizen.visitor&&indoors.test(citizen.home))return citizen.home;
        Set<Point> jobs=new HashSet<>();world.citizens.values().stream().filter(c->!c.dead&&!c.visitor&&c.workSite!=null&&!c.workSite.isBlank()).forEach(c->jobs.add(c.work));
        return world.properties.values().stream().filter(p->p.town.equals(citizen.town)&&p.access.equals("PUBLIC")&&p.price==0&&!p.owner.startsWith("player:")&&p.livingSites!=null)
            .flatMap(p->p.livingSites.stream()).filter(s->s.available&&(s.kind.equals("work")||s.kind.equals("meeting")||s.kind.equals("seat")))
            .map(s->s.approach).filter(Objects::nonNull).distinct().filter(p->!jobs.contains(p)&&!occupied.contains(p)&&indoors.test(p))
            .min(Comparator.comparingDouble((Point p)->p.distanceSquared(from)).thenComparingInt(Point::x).thenComparingInt(Point::z)).orElse(null);
    }
}
