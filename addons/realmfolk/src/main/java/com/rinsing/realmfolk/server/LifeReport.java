package com.rinsing.realmfolk.server;

import com.google.gson.*;
import com.rinsing.realmfolk.design.*;
import com.rinsing.realmfolk.entity.CitizenEntity;
import com.rinsing.realmfolk.life.*;
import net.minecraft.server.MinecraftServer;
import java.util.*;
import static com.rinsing.realmfolk.server.SocietyRuntime.*;

/** Read-only diagnostics. Every physical observation is taken from the running server. */
public final class LifeReport {
    public static JsonObject read(MinecraftServer server){
        var world=society(server);var root=new JsonObject();root.addProperty("dayTime",server.overworld().getDayTime());root.addProperty("raining",server.overworld().isRaining());
        root.addProperty("revision",world.design.revision);root.add("towns",DesignCodec.JSON.toJsonTree(new ArrayList<>(world.towns.values())));
        var buildings=new JsonArray();
        for(var p:world.properties.values()){
            var row=new JsonObject();row.addProperty("id",p.id);row.addProperty("town",p.town);row.addProperty("name",p.name);row.addProperty("facilitySource",p.facilitySource);
            row.add("bounds",DesignCodec.JSON.toJsonTree(p.bounds));row.add("terms",DesignCodec.JSON.toJsonTree(p.terms));row.add("entrances",DesignCodec.JSON.toJsonTree(p.entrances));
            row.add("facilities",DesignCodec.JSON.toJsonTree(p.livingSites));buildings.add(row);
        }
        root.add("buildings",buildings);var people=new JsonArray();
        for(var c:world.citizens.values()){
            var row=new JsonObject();row.addProperty("id",c.id);row.addProperty("name",c.name);row.addProperty("town",c.town);row.addProperty("role",c.role);row.addProperty("visitor",c.visitor);row.addProperty("dead",c.dead);
            row.addProperty("homeProperty",c.property);row.addProperty("workProperty",c.workProperty);row.addProperty("bedSite",c.bedSite);row.addProperty("workSite",c.workSite);
            row.add("home",DesignCodec.JSON.toJsonTree(c.home));row.add("work",DesignCodec.JSON.toJsonTree(c.work));
            var town=world.towns.get(c.town);var level=town==null?null:server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,new net.minecraft.resources.ResourceLocation(town.dimension)));
            var entity=level==null?null:level.getEntity(UUID.fromString(c.id));row.addProperty("loaded",entity!=null);
            if(entity instanceof CitizenEntity npc){row.addProperty("entityId",npc.getId());row.addProperty("activity",npc.activity());row.addProperty("sleeping",npc.isSleeping());row.addProperty("sitting",npc.isPassenger());row.addProperty("usingItem",npc.isUsingItem());row.addProperty("sheltered",!level.canSeeSky(npc.blockPosition()));row.add("position",DesignCodec.JSON.toJsonTree(point(npc.blockPosition())));}
            people.add(row);
        }
        root.add("citizens",people);return root;
    }
}
