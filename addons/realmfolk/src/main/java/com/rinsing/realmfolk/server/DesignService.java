package com.rinsing.realmfolk.server;

import com.google.gson.JsonParser;
import com.rinsing.realmfolk.design.*;
import com.rinsing.realmfolk.model.Society;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import java.nio.file.*;
import java.util.*;
import static com.rinsing.realmfolk.server.SocietyRuntime.*;

public final class DesignService {
    public static boolean itemExists(String id){return ForgeRegistries.ITEMS.containsKey(new ResourceLocation(id));}
    public static void start(MinecraftServer server){
        Society s=society(server);if(s.design==null)s.design=new DesignState();
        if(s.design.active==null){
            Path file=FMLPaths.CONFIGDIR.get().resolve("realmfolk/design.json");
            try{
                Design initial;
                if(Files.exists(file))initial=DesignCodec.decode(JsonParser.parseString(Files.readString(file)),Design.class);
                else {initial=DesignCodec.defaults();migrateConfig(initial);Files.createDirectories(file.getParent());Files.writeString(file,DesignCodec.JSON.toJson(initial));}
                List<String> errors=new DesignValidator(DesignService::itemExists).validate(initial);if(!errors.isEmpty())throw new IllegalArgumentException(String.join("; ",errors));
                s.design.active=initial;s.design.revision=1;
            }catch(Exception e){throw new IllegalStateException("Realmfolk 城市设计初始配置无效: "+file,e);}
        }
        for(var town:s.towns.values())if((town.sourceCity==null||town.sourceCity.isBlank())&&town.id.startsWith("provider_"))town.sourceCity=town.id.substring(town.id.lastIndexOf(':')+1);
        for(var town:s.towns.values())if((town.planningKey==null||town.planningKey.isBlank())&&town.id.startsWith("provider_")&&town.id.contains(":"))town.planningKey=town.id;
        for(Society.Citizen c:s.citizens.values())profile(server,c);
        for(Society.Citizen c:s.citizens.values())if(c.keyPerson&&c.keyPersonId.isBlank()){
            var town=s.towns.get(c.town);if(town==null)continue;
            s.design.active.resolve(town.realm,PopulationPlanner.cityKey(town)).keyPeople().stream().filter(k->k.name.equals(c.name)&&k.profession.equals(c.role)).findFirst().ifPresent(k->c.keyPersonId=k.id);
        }
        dirty(server);
        if(Boolean.getBoolean("realmfolk.designer"))DesignerMcp.start(server);
    }
    private static void migrateConfig(Design d){
        PopulationConfig old=PopulationConfig.current;if(old==null)return;
        var s=d.realms.get("default").population;s.population=old.maxCitizensPerTown;s.residentsPerHouse=old.residentsPerHouse;s.saleHouseEvery=old.saleHouseEvery;s.names=new ArrayList<>(old.names);
        old.functionRoles.forEach((term,job)->{if(job.equals("resident")){if(!s.housingTerms.contains(term))s.housingTerms.add(term);}else if(!d.professions.get(job).workplaceTerms.contains(term))d.professions.get(job).workplaceTerms.add(term);});
        for(int i=0;i<old.keyPeople.size();i++){var oldKey=old.keyPeople.get(i);var p=new Design.KeyPerson();p.id="legacy_"+i;p.name=oldKey.name;p.profession=oldKey.role;p.bio=oldKey.bio==null?"":oldKey.bio;d.cities.computeIfAbsent(oldKey.cityId,k->new Design.CityPlan()).keyPeople.add(p);}
    }
    public static Design active(MinecraftServer server){return society(server).design.active;}
    public static Design.LawPolicy law(MinecraftServer server,String realm){Design d=active(server);return d.realms.get(d.realmBindings.getOrDefault(realm,"default")).law;}
    public static Design.Profession profile(MinecraftServer server,Society.Citizen c){
        if(c.profession==null){Design d=active(server);Design.Profession p=d.professions.getOrDefault(c.role,d.professions.get("resident"));if(p==null)p=DesignCodec.defaults().professions.get("resident");
            c.profession=DesignCodec.copy(p,Design.Profession.class);c.health=30;c.attack=5;c.speed=.29;c.workProperty=c.property;c.keyPersonId="";c.trait="";c.designRevision=society(server).design.revision;dirty(server);}
        return c.profession;
    }
    public static DesignTransactions transactions(MinecraftServer server){return new DesignTransactions(society(server),server.overworld().getSeed(),DesignService::itemExists,EconomyService.config(server));}
    public static void populate(MinecraftServer server){
        if(System.getProperty("realmfolk.autoPopulate","true").equals("false"))return;
        Society s=society(server);
        for(var town:s.towns.values()){
            // A city under active authoring stays still until publication/discard. Stale drafts do not block discovery.
            if(s.design.drafts.values().stream().anyMatch(d->d.baseRevision==s.design.revision&&d.towns.contains(town.id)))continue;
            var plan=PopulationPlanner.plan(s.design.active,s,town,server.overworld().getSeed(),s.design.revision);
            for(var c:plan.citizens()){s.citizens.put(c.id,c);var property=s.properties.get(c.property);if(property.owner.isBlank())property.owner=c.id;dirty(server);}
            VisitorRuntime.populate(server,town);
        }
    }
}
