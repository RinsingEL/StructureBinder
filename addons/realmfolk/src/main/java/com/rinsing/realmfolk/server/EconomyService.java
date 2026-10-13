package com.rinsing.realmfolk.server;

import com.google.gson.JsonParser;
import com.rinsing.realmfolk.design.*;
import com.rinsing.realmfolk.economy.*;
import com.rinsing.realmfolk.model.Society;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLPaths;
import java.nio.file.*;
import static com.rinsing.realmfolk.server.SocietyRuntime.*;

public final class EconomyService {
    private static MinecraftServer owner;
    private static EconomyConfig config;
    public static void start(MinecraftServer server){
        Path path=FMLPaths.CONFIGDIR.get().resolve("realmfolk/economy.json");
        try{
            if(!Files.exists(path)){Files.createDirectories(path.getParent());Files.writeString(path,DesignCodec.JSON.toJson(EconomyConfig.defaults()));}
            EconomyConfig loaded=DesignCodec.decode(JsonParser.parseString(Files.readString(path)),EconomyConfig.class);
            loaded.validate(DesignService::itemExists);validateDesign(loaded,DesignService.active(server));
            owner=server;config=loaded;
            Society s=society(server);
            if(s.economyVersion==0){
                // One-time capability migration, preserving UUIDs, relationships, schedules and custom names.
                s.design.active.professions.values().forEach(EconomyService::migrate);
                s.citizens.values().stream().filter(c->"realmfolk:citizen".equals(c.entityType)).forEach(c->migrate(c.profession));
                s.economyVersion=1;dirty(server);
            }
        }catch(Exception e){throw new IllegalStateException("Realmfolk 物品池配置无效: "+path+": "+e.getMessage(),e);}
    }
    private static void migrate(Design.Profession p){if(p==null)return;p.capabilities.add("gift");if(p.has("trade"))p.capabilities.add("buyback");else p.stockSlots=Math.min(p.stockSlots,4);}
    public static EconomyConfig config(MinecraftServer server){if(owner!=server||config==null)throw new IllegalStateException("Economy is not ready");return config;}
    public static void stop(){owner=null;config=null;ShopService.clear();}
    public static void validateDesign(EconomyConfig cfg,Design design){
        for(var p:design.professions.values())for(String id:p.itemPools)EconomyConfig.require(cfg.pools.containsKey(id),"unknown profession item pool: "+id);
        design.cities.forEach((id,plan)->{if(plan.economy!=null)cfg.validate(plan.economy);});
    }
    public static Inventory inventory(MinecraftServer server,Society.Citizen citizen){
        Society s=society(server);var old=citizen.inventory;
        var inv=Economy.ensure(config(server),Economy.selection(config(server),s.design.active,s.towns.get(citizen.town)),citizen,server.overworld().getSeed(),server.overworld().getGameTime());
        if(old!=inv)dirty(server);return inv;
    }
    public static String money(MinecraftServer server,long amount){return amount+" "+config(server).currency;}
    public static long legacy(MinecraftServer server,int units){return (long)units*config(server).legacyUnit;}
}
