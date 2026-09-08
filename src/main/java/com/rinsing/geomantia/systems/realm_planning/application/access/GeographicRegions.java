package com.rinsing.geomantia.systems.realm_planning.application.access;

import com.google.gson.*;
import java.util.*;

/** Deterministic geographic partition of W cells, independent of political ownership. */
public final class GeographicRegions {
    public record Cell(int x, int z) implements Comparable<Cell> {
        public int compareTo(Cell other) { int c = Integer.compare(z, other.z); return c != 0 ? c : Integer.compare(x, other.x); }
        List<Cell> neighbors() { return List.of(new Cell(x-1,z), new Cell(x+1,z), new Cell(x,z-1), new Cell(x,z+1)); }
    }
    public record Region(String id, boolean ocean, Set<Cell> cells, Set<String> adjacentRegions) {}
    private final int step;
    private final Map<Cell, String> owners;
    private final Map<String, Region> regions;
    private GeographicRegions(int step, Map<Cell, String> owners, Map<String, Region> regions) {
        this.step = step; this.owners = Map.copyOf(owners); this.regions = Map.copyOf(regions);
    }
    public int step() { return step; }
    public Map<String, Region> regions() { return regions; }
    public String at(double x, double z) { return owners.getOrDefault(new Cell((int)Math.floor(x/step), (int)Math.floor(z/step)), ""); }
    public String at(Cell cell) { return owners.getOrDefault(cell, ""); }

    public static GeographicRegions build(JsonObject grid, int nearSeaBlocks, int oceanSpanBlocks) {
        int step = grid.get("cellStepBlocks").getAsInt();
        if (step <= 0 || nearSeaBlocks < 0 || oceanSpanBlocks < step) throw new IllegalArgumentException("GEOGRAPHIC_GRID_INVALID");
        TreeSet<Cell> land = new TreeSet<>(), all = new TreeSet<>();
        for (JsonElement value : grid.getAsJsonArray("cells")) {
            JsonObject v = value.getAsJsonObject();
            Cell c = new Cell(v.get("gridX").getAsInt(), v.get("gridZ").getAsInt());
            if (!all.add(c)) throw new IllegalArgumentException("GEOGRAPHIC_GRID_DUPLICATE_CELL: " + c);
            // Missing terrain measurements must not invent an open continent.
            double water = v.get("waterFrac").getAsDouble();
            if (!Double.isFinite(water) || water < 0 || water > 1) throw new IllegalArgumentException("GEOGRAPHIC_WATER_FRACTION_INVALID");
            if (water < 0.5) land.add(c);
        }
        Map<Cell,String> owners = new HashMap<>();
        TreeSet<Cell> remaining = new TreeSet<>(land);
        int continent = 0;
        while (!remaining.isEmpty()) {
            String id = "continent_" + (++continent);
            ArrayDeque<Cell> todo = new ArrayDeque<>(); todo.add(remaining.pollFirst());
            while (!todo.isEmpty()) { Cell c = todo.remove(); owners.put(c,id);
                for (Cell n : c.neighbors()) if (remaining.remove(n)) todo.add(n);
            }
        }
        // Multi-source breadth-first water distance; stable cell order breaks equal-distance ties.
        record Coast(Cell cell, int distance) {}
        ArrayDeque<Coast> coast = new ArrayDeque<>();
        for (Cell c : land) coast.add(new Coast(c,0));
        while (!coast.isEmpty()) {
            Coast source = coast.remove();
            if ((long)(source.distance()+1)*step > nearSeaBlocks) continue;
            for (Cell n : source.cell().neighbors()) if (all.contains(n) && !owners.containsKey(n)) {
                owners.put(n,owners.get(source.cell())); coast.add(new Coast(n,source.distance()+1));
            }
        }
        // Split distant water by bounded geographical sectors, then connected component inside each.
        // A channel or an island can never join disconnected pieces merely because they share a sector.
        int sector = Math.max(1, oceanSpanBlocks/step), ocean = 0;
        remaining = new TreeSet<>(all); remaining.removeAll(owners.keySet());
        while (!remaining.isEmpty()) {
            Cell start = remaining.pollFirst(); String id = "ocean_" + (++ocean);
            int sx = Math.floorDiv(start.x(),sector), sz = Math.floorDiv(start.z(),sector);
            ArrayDeque<Cell> todo = new ArrayDeque<>(); todo.add(start);
            while (!todo.isEmpty()) { Cell c = todo.remove(); owners.put(c,id);
                for (Cell n : c.neighbors()) if (Math.floorDiv(n.x(),sector)==sx && Math.floorDiv(n.z(),sector)==sz && remaining.remove(n)) todo.add(n);
            }
        }
        Map<String,Set<Cell>> cells = new TreeMap<>(); Map<String,Set<String>> adjacent = new TreeMap<>();
        for (Cell c : all) { String id = owners.get(c); cells.computeIfAbsent(id,k->new TreeSet<>()).add(c);
            Set<String> links = adjacent.computeIfAbsent(id,k->new TreeSet<>());
            for (Cell n : c.neighbors()) { String other = owners.get(n); if (other != null && !other.equals(id)) links.add(other); }
        }
        Map<String,Region> regions = new LinkedHashMap<>();
        cells.forEach((id,values)->regions.put(id,new Region(id,id.startsWith("ocean_"),Set.copyOf(values),Set.copyOf(adjacent.get(id)))));
        return new GeographicRegions(step,owners,regions);
    }

    public JsonObject asJson() {
        JsonObject json = new JsonObject(); json.addProperty("schema","geomantia_geographic_regions.v0.1"); json.addProperty("cellStepBlocks",step);
        JsonArray values = new JsonArray();
        for (Region region : new TreeMap<>(regions).values()) {
            JsonObject r = new JsonObject(); r.addProperty("regionId",region.id()); r.addProperty("kind",region.ocean()?"ocean":"continent_and_near_sea");
            JsonArray cells = new JsonArray(); for (Cell c : new TreeSet<>(region.cells())) { JsonObject v = new JsonObject(); v.addProperty("gridX",c.x()); v.addProperty("gridZ",c.z()); cells.add(v); }
            r.add("cells",cells); JsonArray links = new JsonArray(); new TreeSet<>(region.adjacentRegions()).forEach(links::add); r.add("adjacentRegions",links); values.add(r);
        }
        json.add("regions",values); return json;
    }
}
