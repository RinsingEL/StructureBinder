package com.rinsing.realmfolk.life;

import com.rinsing.realmfolk.model.Society;
import com.rinsing.realmfolk.design.PopulationPlanner;
import java.util.*;
import static com.rinsing.realmfolk.model.Society.*;

public final class VisitorPlaces {
    public static List<Property> select(Society society,Town town){
        var settings=society.design.active.resolve(town.realm,PopulationPlanner.cityKey(town)).settings();
        Set<String> uses=new HashSet<>();settings.activities.forEach(a->uses.addAll(a.placeTerms));
        return society.properties.values().stream().filter(p->p.town.equals(town.id)&&p.publicPoints!=null&&!p.publicPoints.isEmpty()
            &&p.terms!=null&&!Collections.disjoint(p.terms,uses)&&p.access.equals("PUBLIC")&&p.price==0&&!p.owner.startsWith("player:"))
            .sorted(Comparator.comparing(p->p.id)).toList();
    }
}
