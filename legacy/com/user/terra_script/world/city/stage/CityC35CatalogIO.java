package com.user.terra_script.world.city.stage;

import com.google.gson.Gson;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CityC35CatalogIO {
    public static final String CATALOG_FILE = "C3_5_StructureCatalog.preprocessed.json";
    public static final String FUNCTION_ENUM_FILE = "C3_5_FunctionEnumTable.json";

    private CityC35CatalogIO() {}

    public static Path catalogPath() {
        return FMLPaths.GAMEDIR.get().resolveSibling("template_output").resolve(CATALOG_FILE);
    }

    public static Path functionEnumPath() {
        return FMLPaths.GAMEDIR.get().resolveSibling("template_output").resolve(FUNCTION_ENUM_FILE);
    }

    public static <T> T loadCatalog(Gson gson, Class<T> type) throws Exception {
        Path path = catalogPath();
        boolean exists = Files.exists(path);
        System.out.println("[C35] loadCatalog path=" + path + " exists=" + exists + " type=" + type.getSimpleName());
        if (!exists) return null;
        return gson.fromJson(Files.readString(path, StandardCharsets.UTF_8), type);
    }

    public static class StructureCatalog {
        public String step;
        public boolean ok;
        public String city_id;
        public int catalog_version;
        public long generated_at_epoch_ms;
        public List<String> function_enum_table = new ArrayList<>();
        public List<CatalogStructure> structures = new ArrayList<>();
    }

    public static class CatalogStructure {
        public String structure_id;
        public Size size = new Size();
        public Orientation orientation = new Orientation();
        public String piece_role;
        public Map<String, Double> style_score = new LinkedHashMap<>();
        public List<FunctionCandidate> function_candidates = new ArrayList<>();
        public String namespace;
        public String path;
        public String size_tier;
        public List<String> connector_types = new ArrayList<>();
        public List<String> connector_dirs = new ArrayList<>();
        public List<String> allowed_neighbors = new ArrayList<>();
        public String landing_hint;
        public String growth_axis;
        public String vertical_role;
        public int vertical_clearance;
        public String preset_pool;
        public PoolSource preset_pool_source = new PoolSource();
        public FunctionDefinition function_definition = new FunctionDefinition();
        public PlacementSpec placement = new PlacementSpec();
        public ConstraintSpec constraints = new ConstraintSpec();
        public List<ConnectorSpec> connectors = new ArrayList<>();
        public Map<String, Double> weight_profile = new LinkedHashMap<>();
        public TagSource tag_source = new TagSource();
        public String notes;
    }

    public static class Size {
        public int length;
        public int width;
        public int height;
    }

    public static class Orientation {
        public List<String> jigsaw_facing = new ArrayList<>();
        public String entry_facing;
        public List<Integer> rotations = new ArrayList<>();
    }

    public static class FunctionCandidate {
        public String function;
        public double score;
    }

    public static class PoolSource {
        public String source_type;
        public String pool_id;
        public String evidence;
    }

    public static class FunctionDefinition {
        public String primary_function;
        public List<String> secondary_functions = new ArrayList<>();
        public String semantic_role;
        public String source;
    }

    public static class PlacementSpec {
        public String origin_mode;
        public Vec3i origin_offset = new Vec3i();
        public Footprint footprint = new Footprint();
        public VerticalRange clearance = new VerticalRange();
        public List<ProbePoint> terrain_probe_points = new ArrayList<>();
        public List<EntryPoint> entry_points = new ArrayList<>();
    }

    public static class Vec3i {
        public int x;
        public int y;
        public int z;
    }

    public static class Footprint {
        public int min_x;
        public int min_z;
        public int max_x;
        public int max_z;
    }

    public static class VerticalRange {
        public int min_y;
        public int max_y;
    }

    public static class ProbePoint {
        public int x;
        public int z;
    }

    public static class EntryPoint {
        public int x;
        public int y;
        public int z;
        public String facing;
        public String kind;
    }

    public static class ConstraintSpec {
        public List<Integer> allowed_rotations = new ArrayList<>();
        public double max_slope;
        public int max_height_delta;
        public boolean avoid_water;
        public boolean avoid_lava;
        public int min_edge_buffer;
        public boolean requires_solid_base;
    }

    public static class ConnectorSpec {
        public String id;
        public Vec3i local_pos = new Vec3i();
        public String facing;
        public String type;
        public String socket;
        public String pool;
        public List<String> connect_to_pools = new ArrayList<>();
        public boolean required;
        public int max_connections;
    }

    public static List<String> connectorPoolRefs(ConnectorSpec connector) {
        if (connector == null) return List.of();
        List<String> resolved = new ArrayList<>();
        if (connector.connect_to_pools != null) {
            for (String poolId : connector.connect_to_pools) {
                if (poolId != null && !poolId.isBlank() && !resolved.contains(poolId)) {
                    resolved.add(poolId);
                }
            }
        }
        if (!resolved.isEmpty()) return resolved;
        if (connector.pool != null && !connector.pool.isBlank()) {
            resolved.add(connector.pool);
        }
        return resolved;
    }

    public static class TagSource {
        public boolean scanner;
        public String preset_rule;
        public boolean manual_override;
    }
}
