package com.rinsing.realmfolk.server;

import com.rinsing.realmfolk.Realmfolk;
import com.rinsing.realmfolk.data.SocietyData;
import net.minecraftforge.fml.loading.FMLPaths;
import java.nio.file.*;
import java.io.*;
import java.util.*;

public final class PopulationConfig {
    public int version=1;
    public int residentsPerHouse=2;
    public int maxCitizensPerTown=64;
    public int saleHouseEvery=5;
    public Map<String,String> functionRoles=new LinkedHashMap<>();
    public List<String> names=new ArrayList<>();
    public List<KeyPerson> keyPeople=new ArrayList<>();
    public Map<String,String> templateRoles=new LinkedHashMap<>();
    public Map<String,String> entityTypesByTemplate=new LinkedHashMap<>();
    public static class KeyPerson { public String cityId,name,role,bio; }
    public static PopulationConfig current;
    public static void load(){
        Path file=FMLPaths.CONFIGDIR.get().resolve("realmfolk/population.json");
        try {
            Files.createDirectories(file.getParent());
            if(!Files.exists(file))try(InputStream in=PopulationConfig.class.getResourceAsStream("/realmfolk/population.json")){Files.copy(Objects.requireNonNull(in),file);}
            PopulationConfig parsed=SocietyData.JSON.fromJson(Files.readString(file),PopulationConfig.class);
            parsed.validate();current=parsed;
        }catch(Exception ex){throw new IllegalStateException("Realmfolk 人口配置无效："+file,ex);}
    }
    public void validate(){
        if(version!=1||residentsPerHouse<1||residentsPerHouse>8||maxCitizensPerTown<1||maxCitizensPerTown>256||saleHouseEvery<0||saleHouseEvery==1||names==null||names.isEmpty()||names.stream().anyMatch(n->n==null||n.isBlank()))throw new IllegalArgumentException("人口配置版本、容量或姓名表无效");
        Set<String> roles=Set.of("resident","farmer","smith","guard","ranger","merchant","mayor");
        for(String role:functionRoles.values())if(!roles.contains(role))throw new IllegalArgumentException("未知角色："+role);
        for(String role:templateRoles.values())if(!roles.contains(role))throw new IllegalArgumentException("未知模板角色："+role);
        for(String type:entityTypesByTemplate.values())if(!net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.containsKey(new net.minecraft.resources.ResourceLocation(type)))throw new IllegalArgumentException("未知实体："+type);
        for(KeyPerson person:keyPeople)if(person.cityId==null||person.cityId.isBlank()||person.name==null||person.name.isBlank()||!roles.contains(person.role))throw new IllegalArgumentException("关键人物需要有效城市、姓名和角色");
    }
}
