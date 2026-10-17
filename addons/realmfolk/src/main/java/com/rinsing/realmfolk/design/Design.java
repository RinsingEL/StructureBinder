package com.rinsing.realmfolk.design;

import java.util.*;

/** Declarative content only. No expressions, scripts, class names or arbitrary actions. */
public final class Design {
    public int version = 2;
    public Map<String, Profession> professions = new LinkedHashMap<>();
    public Map<String, RealmRule> realms = new LinkedHashMap<>();
    public Map<String, String> realmBindings = new LinkedHashMap<>();
    public Map<String, Settings> cityTemplates = new LinkedHashMap<>();
    public Map<String, CityPlan> cities = new LinkedHashMap<>();

    public static final Set<String> CAPABILITIES = Set.of("conversation", "commission", "trade", "companion",
        "combat", "patrol", "witness", "law_enforcement", "rumors", "travel", "land_office", "public_life", "buyback", "gift");
    public static final int MAX_POPULATION=256;

    public static class Profession {
        public String name = "居民", appearance = "resident", bio = "今天过得怎么样？", equipment = "minecraft:air";
        public Set<String> capabilities = new LinkedHashSet<>();
        public List<String> workplaceTerms = new ArrayList<>();
        public Range health = new Range(30,30), attack = new Range(5,5), speed = new Range(.29,.29);
        public int restStart = 12500, restEnd = 23000, recruitFavor = 5, patrolRadius = 5;
        public Commission commission = new Commission();
        public String commissionPurpose="", goodsPurpose="";
        public List<Offer> offers = new ArrayList<>();
        public List<String> itemPools = new ArrayList<>();
        public int stockSlots = 18;
        public boolean has(String capability) { return capabilities.contains(capability); }
    }
    public static class Range {
        public double min, max;
        public Range() {}
        public Range(double min,double max) { this.min=min;this.max=max; }
    }
    public static class Commission {
        public String item = "minecraft:oak_log";
        public int count = 6, emeralds = 4, affection = 6, reputation = 2;
    }
    public static class Offer {
        public String item = "minecraft:bread";
        public int count = 4, emeralds = 1;
    }
    public static class Quota {
        public int min = 0, max = 256;
        public double weight = 1;
        public Quota() {}
        public Quota(int min,int max,double weight){this.min=min;this.max=max;this.weight=weight;}
    }
    /** Null inherits; a supplied map/list replaces that field as a whole. */
    public static class Settings {
        public Integer population, residentsPerHouse, saleHouseEvery, housePrice, workplaceCapacity;
        public String populationMode;
        public Double housingOccupancy;
        public List<PublicActivity> activities;
        public List<String> names, housingTerms;
        public Map<String,Double> traits;
        public Map<String,Quota> jobs;
        public Map<String,Integer> residentsByFunction;
    }
    public static class PublicActivity {
        public String id="market", name="逛集市";
        public int start=5000, end=8000, capacity=12, maxDistance=384;
        public double participation=.6;
        public List<String> placeTerms=new ArrayList<>();
    }
    public static class RealmRule {
        public String name = "通用文化";
        public Settings population = new Settings();
        public LawPolicy law = new LawPolicy();
    }
    public static class LawPolicy {
        public int witnessRadius=18, reportDelay=600, trespassGrace=200, guardRadius=28, chaseRadius=64,
            loseSightTicks=200, repeatedOffenses=3;
        public int theftUnit=1, damageFine=2, trespassFine=1, assaultFine=12, murderFine=32;
    }
    public static class KeyPerson {
        public String id, name, profession, bio="";
    }
    public static class CityPlan {
        public String template="default";
        public Settings overrides=new Settings();
        public List<KeyPerson> keyPeople=new ArrayList<>();
        public com.rinsing.realmfolk.economy.EconomyConfig.Selection economy;
        public List<FunctionAnswer> functionAnswers=new ArrayList<>();
    }
    /** Authored coverage decisions; not a second source of runtime profession settings. */
    public static class FunctionAnswer {
        public String functionTerm;
        public Boolean housing;
        public List<String> professions=new ArrayList<>();
        public String reason;
    }
    public static class Patch {
        public Map<String,Profession> professions=new LinkedHashMap<>();
        public Map<String,RealmRule> realms=new LinkedHashMap<>();
        public Map<String,String> realmBindings=new LinkedHashMap<>();
        public Map<String,Settings> cityTemplates=new LinkedHashMap<>();
        public Map<String,CityPlan> cities=new LinkedHashMap<>();
    }
    public record Resolved(Settings settings, List<KeyPerson> keyPeople, LawPolicy law) {}

    public CityPlan cityPlan(String city){return cities.getOrDefault(city,cities.getOrDefault(city.substring(city.lastIndexOf(':')+1),new CityPlan()));}

    public Resolved resolve(String realm,String city) {
        Settings result=new Settings();
        apply(result,realms.get("default").population);
        RealmRule rule=realms.get(realmBindings.getOrDefault(realm,"default"));
        apply(result,rule.population);
        CityPlan plan=cityPlan(city);
        apply(result,cityTemplates.get(plan.template));
        apply(result,plan.overrides);
        // Existing v2 worlds keep their explicit population plan until the author publishes housing mode.
        if(result.populationMode==null)result.populationMode="fixed";
        if(result.housingOccupancy==null)result.housingOccupancy=1.0;
        if(result.activities==null)result.activities=List.of();
        // 旧默认 128 格覆盖不了学院城 142–334 格的食堂与广场；新默认 384，老配置解析时迁移。
        result.activities=result.activities.stream().map(activity->{
            if(activity.maxDistance>128)return activity;
            PublicActivity migrated=DesignCodec.copy(activity,PublicActivity.class);
            migrated.maxDistance=new PublicActivity().maxDistance;
            return migrated;
        }).toList();
        return new Resolved(result,plan.keyPeople,rule.law);
    }
    private static void apply(Settings target,Settings source) {
        if(source.population!=null)target.population=source.population;
        if(source.populationMode!=null)target.populationMode=source.populationMode;
        if(source.housingOccupancy!=null)target.housingOccupancy=source.housingOccupancy;
        if(source.activities!=null)target.activities=source.activities;
        if(source.residentsPerHouse!=null)target.residentsPerHouse=source.residentsPerHouse;
        if(source.residentsByFunction!=null)target.residentsByFunction=source.residentsByFunction;
        if(source.saleHouseEvery!=null)target.saleHouseEvery=source.saleHouseEvery;
        if(source.housePrice!=null)target.housePrice=source.housePrice;
        if(source.workplaceCapacity!=null)target.workplaceCapacity=source.workplaceCapacity;
        if(source.names!=null)target.names=source.names;
        if(source.housingTerms!=null)target.housingTerms=source.housingTerms;
        if(source.traits!=null)target.traits=source.traits;
        if(source.jobs!=null)target.jobs=source.jobs;
    }
}
