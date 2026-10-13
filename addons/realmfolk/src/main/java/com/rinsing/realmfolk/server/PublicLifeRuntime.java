package com.rinsing.realmfolk.server;

import com.rinsing.realmfolk.design.*;
import com.rinsing.realmfolk.entity.CitizenEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import java.util.*;
import static com.rinsing.realmfolk.model.Society.*;
import static com.rinsing.realmfolk.server.SocietyRuntime.*;

public final class PublicLifeRuntime {
    private record Cached(long period,int tickPeriod,int revision,int citizens,int properties,PublicLifePlanner.Plan plan) {}
    private static MinecraftServer owner;
    private static final Map<String,Cached> CACHE=new HashMap<>();
    private static final Map<UUID,PublicVisitProgress> JOURNEYS=new HashMap<>();
    public static void clear(){owner=null;CACHE.clear();JOURNEYS.clear();}
    public static boolean standable(ServerLevel level,Point point){
        var p=pos(point);return level.hasChunkAt(p)&&level.getBlockState(p.below()).isCollisionShapeFullBlock(level,p.below())&&level.getBlockState(p).getCollisionShape(level,p).isEmpty()&&level.getBlockState(p.above()).getCollisionShape(level,p.above()).isEmpty()&&level.getFluidState(p).isEmpty();
    }
    public static PublicLifePlanner.Plan plan(MinecraftServer server,Town town){
        if(owner!=server){clear();owner=server;}
        var s=society(server);ServerLevel level=server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,new net.minecraft.resources.ResourceLocation(town.dimension)));
        if(level==null)return new PublicLifePlanner.Plan(Map.of(),List.of());
        long period=Math.floorDiv(level.getDayTime(),100);int tickPeriod=Math.floorDiv(server.getTickCount(),100);Cached cached=CACHE.get(town.id);
        if(cached!=null&&cached.period==period&&cached.tickPeriod==tickPeriod&&cached.revision==s.design.revision&&cached.citizens==s.citizens.size()&&cached.properties==s.properties.size())return cached.plan;
        Set<String> loaded=new HashSet<>();
        for(Citizen c:s.citizens.values())if(c.town.equals(town.id)&&level.getEntity(UUID.fromString(c.id)) instanceof CitizenEntity npc&&npc.isAlive()&&npc.getTarget()==null)loaded.add(c.id);
        var result=PublicLifePlanner.plan(s,town,s.design.active.resolve(town.realm,PopulationPlanner.cityKey(town)).settings(),level.getDayTime(),loaded);
        CACHE.put(town.id,new Cached(period,tickPeriod,s.design.revision,s.citizens.size(),s.properties.size(),result));return result;
    }
    public static PublicLifePlanner.Visit visit(CitizenEntity npc,Citizen c,Town town){
        ServerLevel level=(ServerLevel)npc.level();if(!PublicLifePlanner.eligibleCitizen(c,(int)Math.floorMod(level.getDayTime(),24000)))return null;
        var visit=plan(level.getServer(),town).visits().get(c.id);if(visit==null)return null;
        var society=society(level.getServer());Property destination=society.properties.get(visit.place());
        if(destination==null||destination.price>0||!"PUBLIC".equals(destination.access)||destination.owner.startsWith("player:")||!standable(level,visit.point()))return null;
        Property claim=society.propertyAt(town.dimension,visit.point()).orElse(null);
        if(claim!=null&&(!"PUBLIC".equals(claim.access)||claim.owner.startsWith("player:")||claim.price>0))return null;
        if(!JOURNEYS.computeIfAbsent(npc.getUUID(),id->new PublicVisitProgress()).allows(visit,level.getGameTime(),npc.blockPosition().distSqr(pos(visit.point()))))return null;
        return visit;
    }
}
