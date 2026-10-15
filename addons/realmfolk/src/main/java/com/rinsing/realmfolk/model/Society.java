package com.rinsing.realmfolk.model;

import java.util.*;

/** Save model deliberately independent of Minecraft; all mutations occur on the server thread. */
public final class Society {
    public int version = 2;
    public Map<String, Town> towns = new LinkedHashMap<>();
    public Map<String, Property> properties = new LinkedHashMap<>();
    public Map<String, Citizen> citizens = new LinkedHashMap<>();
    public Map<String, Journal> players = new LinkedHashMap<>();
    public Map<String, String> placedBy = new HashMap<>();
    public Set<String> imported = new HashSet<>();
    public boolean demoCreated;
    public int economyVersion;
    public com.rinsing.realmfolk.design.DesignState design = new com.rinsing.realmfolk.design.DesignState();

    public record Point(int x, int y, int z) {
        public double distanceSquared(Point p) { return Math.pow(x-p.x,2)+Math.pow(y-p.y,2)+Math.pow(z-p.z,2); }
        public Point offset(int dx, int dy, int dz) { return new Point(x+dx,y+dy,z+dz); }
    }
    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public Bounds {
            if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("倒置的财产边界");
        }
        public boolean contains(Point p) { return p.x>=minX && p.x<=maxX && p.y>=minY && p.y<=maxY && p.z>=minZ && p.z<=maxZ; }
        public long volume() { return (long)(maxX-minX+1)*(maxY-minY+1)*(maxZ-minZ+1); }
    }
    public static class Town {
        public String id, realm, name, dimension = "minecraft:overworld";
        public Point center;
        public String sourceCity = "";
        public String planningKey = "";
        public String nextTown = "";
        public Point danger;
        public Town(String id, String realm, String name, Point center) { this.id=id; this.realm=realm; this.name=name; this.center=center; }
    }
    public static class Property {
        public String id, town, name, owner = "";
        public String access = "PUBLIC";
        public Bounds bounds;
        public Point home, work;
        public int price;
        public String templateId = "";
        public List<String> terms = new ArrayList<>();
        public List<Point> slots = new ArrayList<>();
        /** Null means an old/uninspected property; an empty list means inspected with no facilities. */
        public List<com.rinsing.realmfolk.life.LivingSite> livingSites;
        public List<Point> entrances=new ArrayList<>();
        public String facilitySource="";
        /** Public approach points outside the building, discovered only in loaded terrain. */
        public List<Point> publicPoints;
        public Property() {} // Gson must run field initializers when reading older saves.
        public Property(String id, String town, String name, Bounds bounds, Point home) {
            this.id=id; this.town=town; this.name=name; this.bounds=bounds; this.home=home; this.work=home;
        }
        public boolean owns(String player) { return owner.equals("player:"+player); }
    }
    public static class Citizen {
        public String id, name, town, property, role, entityType = "realmfolk:citizen";
        public String bio = "今天过得怎么样？", leader = "", mode = "HOME", targetTown = "";
        public Point home, work;
        public boolean spawned, dead, keyPerson;
        public String bedSite="", workSite="";
        public boolean visitor;
        public boolean needsVisitorPlacement;
        public long lastRoutineTick;
        public String workProperty = "", keyPersonId = "", trait = "";
        public com.rinsing.realmfolk.design.Design.Profession profession;
        public double health = 30, attack = 5, speed = .29;
        public int designRevision;
        public com.rinsing.realmfolk.economy.Inventory inventory;
        public Citizen() {} // Older saves omit facility claims and visitor state.
        public Citizen(String id, String name, String town, String property, String role, Point home, Point work) {
            this.id=id; this.name=name; this.town=town; this.property=property; this.role=role; this.home=home; this.work=work;
        }
    }
    public static class Journal {
        public long balance;
        public Map<String,com.rinsing.realmfolk.quest.ObjectiveTasks.Progress> objectiveTasks = new LinkedHashMap<>();
        public Map<String,Integer> reputation = new HashMap<>(), affection = new HashMap<>();
        public Map<String,String> quests = new HashMap<>();
        public Set<String> knownTowns = new HashSet<>(), rewards = new HashSet<>();
        public List<Offense> offenses = new ArrayList<>();
        public int favor(String npc) { return affection.getOrDefault(npc,0); }
        public int standing(String realm) { return reputation.getOrDefault(realm,0); }
        public void like(String npc, int delta) { affection.put(npc,clamp(favor(npc)+delta)); }
        public void reputation(String realm, int delta) { reputation.put(realm,clamp(standing(realm)+delta)); }
        private static int clamp(int n) { return Math.max(-100,Math.min(100,n)); }
    }
    public enum Crime { THEFT, DAMAGE, TRESPASS, ASSAULT, MURDER }
    public static class Offense {
        public String id = UUID.randomUUID().toString(), realm, property, witness, stolenStack = "";
        public Crime crime;
        public Point source;
        public long tick;
        public int fine;
        public boolean reported, settled;
    }
    public Journal journal(String player) { return players.computeIfAbsent(player,k->new Journal()); }
    public Optional<Property> propertyAt(String dimension, Point point) {
        return properties.values().stream().filter(p->towns.containsKey(p.town)
            && towns.get(p.town).dimension.equals(dimension) && p.bounds.contains(point))
            .min(Comparator.comparingLong(p->p.bounds.volume()));
    }
    public static String blockKey(String dimension, Point p) { return dimension+":"+p.x+","+p.y+","+p.z; }
}
