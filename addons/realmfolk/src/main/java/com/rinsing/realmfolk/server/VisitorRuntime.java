package com.rinsing.realmfolk.server;

import com.rinsing.realmfolk.design.*;
import com.rinsing.realmfolk.model.Society;
import net.minecraft.server.MinecraftServer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.rinsing.realmfolk.model.Society.*;
import static com.rinsing.realmfolk.server.SocietyRuntime.*;

/** A small persistent set of visitors, separate from residents and their beds. */
public final class VisitorRuntime {
    public static void populate(MinecraftServer server,Town town){
        Society s=society(server);long residents=s.citizens.values().stream().filter(c->c.town.equals(town.id)&&!c.visitor&&!c.dead).count();
        if(residents<4)return;
        var places=com.rinsing.realmfolk.life.VisitorPlaces.select(s,town);
        if(places.isEmpty())return;
        for(Citizen c:s.citizens.values())if(c.visitor&&c.town.equals(town.id)&&places.stream().noneMatch(p->p.id.equals(c.property))){
            Property place=places.get(Math.floorMod(c.id.hashCode(),places.size()));Point at=place.publicPoints.get(Math.floorMod(c.id.hashCode(),place.publicPoints.size()));
            c.property=place.id;c.workProperty=place.id;c.home=at;c.work=at;c.needsVisitorPlacement=true;dirty(server);
        }
        var settings=s.design.active.resolve(town.realm,PopulationPlanner.cityKey(town)).settings();
        for(int i=0;i<Math.min(4,1+residents/12);i++){
            String id=UUID.nameUUIDFromBytes(("visitor:"+town.id+":"+i).getBytes(StandardCharsets.UTF_8)).toString();if(s.citizens.containsKey(id))continue;
            Property place=places.get(i%places.size());Point at=place.publicPoints.get(i%place.publicPoints.size());
            Citizen c=new Citizen(id,settings.names.get(i%settings.names.size())+"（旅人）",town.id,place.id,"resident",at,at);
            c.visitor=true;c.workProperty=place.id;c.profession=DesignCodec.copy(s.design.active.professions.get("resident"),Design.Profession.class);
            c.profession.name="来访旅人";c.profession.capabilities.remove("commission");c.profession.capabilities.add("public_life");c.profession.workplaceTerms=List.of();
            c.bio="路过这里，看看街市，歇歇脚。";c.designRevision=s.design.revision;s.citizens.put(id,c);dirty(server);
        }
    }
}
