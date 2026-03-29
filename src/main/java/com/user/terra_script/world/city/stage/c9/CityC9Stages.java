package com.user.terra_script.world.city.stage.c9;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.user.terra_script.world.StructureInjector;
import com.user.terra_script.world.city.stage.c6.CityC6Stages;
import com.user.terra_script.world.city.stage.c8.CityC8Stages;
import com.user.terra_script.world.city.stage.c9.CityC9BuildQueue.BuildQueue;
import com.user.terra_script.world.city.stage.c9.CityC9BuildQueue.QueueSummary;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CityC9Stages {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final String C9_PLACEMENT_FILE = "C9_Placement.json";
    public static final String C9_DECORATION_FILE = "C9_DecorationPlan.json";

    private CityC9Stages() {}

    public static class C9Result {
        public C9Placement placement;
        public C9Decoration decoration;
        public BuildQueue queue;
        public QueueSummary queue_summary;
    }

    public static class C9Placement {
        public String step = "C9";
        public boolean ok = true;
        public String city_id;
        public long generated_at_epoch_ms;
        public String mode = Mode.ENQUEUE.name().toLowerCase();
        public boolean apply_blocks;
        public int max_blocks;
        public int changed_blocks_total;
        public int processed_areas;
        public int enqueued_tasks_count;
        public int applied_tasks_count;
        public List<PlacementItem> items = new ArrayList<>();
    }

    public static class PlacementItem {
        public String build_area_id;
        public int build_area_numeric_id;
        public String foundation_type;
        public int base_y;
        public int scanned_blocks;
        public int changed_blocks;
        public int planned_nodes;
        public int placed_structures;
        public List<PlacedStructure> structures = new ArrayList<>();
    }

    public static class PlacedStructure {
        public String node_id;
        public String template_id;
        public int x;
        public int y;
        public int z;
        public int rotation;
        public Integer build_order;
        public boolean terminalized;
        public boolean placed;
        public String reason;
    }

    public static class C9Decoration {
        public String step = "C9";
        public boolean ok = true;
        public String city_id;
        public long generated_at_epoch_ms;
        public List<DecorationItem> items = new ArrayList<>();
    }

    public static class DecorationItem {
        public String build_area_id;
        public String strategy;
        public String note;
    }

    public enum Mode {
        DRY_RUN,
        ENQUEUE,
        APPLY_NOW;

        public static Mode parse(String raw, Boolean deprecatedApplyBlocks) {
            if (raw != null && !raw.isBlank()) {
                try {
                    return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (deprecatedApplyBlocks != null) {
                return deprecatedApplyBlocks ? ENQUEUE : DRY_RUN;
            }
            return ENQUEUE;
        }
    }

    public static C9Result generate(
            String cityId,
            CityC6Stages.C6Summary c6Summary,
            Map<Long, Integer> c6Index,
            CityC8Stages.C8Plan c8Plan,
            Mode mode,
            int maxBlocks,
            BuildQueue existingQueue
    ) {
        C9Result out = new C9Result();
        out.placement = new C9Placement();
        out.decoration = new C9Decoration();
        out.placement.city_id = cityId;
        out.decoration.city_id = cityId;
        out.placement.generated_at_epoch_ms = System.currentTimeMillis();
        out.decoration.generated_at_epoch_ms = out.placement.generated_at_epoch_ms;
        out.placement.mode = (mode != null ? mode : Mode.ENQUEUE).name().toLowerCase(java.util.Locale.ROOT);
        out.placement.apply_blocks = mode == Mode.APPLY_NOW;
        out.placement.max_blocks = Math.max(1, maxBlocks);

        if (c6Summary == null || c6Index == null || c6Index.isEmpty() || c8Plan == null || c8Plan.foundations == null) {
            out.placement.ok = false;
            out.decoration.ok = false;
            return out;
        }

        Map<Integer, CityC8Stages.FoundationItem> foundationByArea = new HashMap<>();
        for (CityC8Stages.FoundationItem item : c8Plan.foundations) {
            if (item == null) continue;
            foundationByArea.put(item.build_area_numeric_id, item);
        }

        Map<Integer, Set<Long>> blocksByArea = new HashMap<>();
        for (Map.Entry<Long, Integer> e : c6Index.entrySet()) {
            if (e == null || e.getKey() == null || e.getValue() == null) continue;
            blocksByArea.computeIfAbsent(e.getValue(), k -> new HashSet<>()).add(e.getKey());
        }

        List<CityC6Stages.BuildAreaSummary> areas = c6Summary.areas != null ? c6Summary.areas : Collections.emptyList();
        areas.sort(Comparator.comparing(a -> a.build_area_id));

        int plannedTaskCount = 0;
        CityC8Stages.C8Plan executablePlan = new CityC8Stages.C8Plan();
        executablePlan.city_id = c8Plan.city_id;
        executablePlan.generated_at_epoch_ms = c8Plan.generated_at_epoch_ms;
        executablePlan.version = c8Plan.version;
        for (CityC6Stages.BuildAreaSummary area : areas) {
            if (area == null) continue;
            CityC8Stages.FoundationItem foundation = foundationByArea.get(area.build_area_numeric_id);
            if (foundation == null) continue;

            Set<Long> areaBlocks = blocksByArea.getOrDefault(area.build_area_numeric_id, Collections.emptySet());
            if (areaBlocks.isEmpty()) continue;

            PlacementItem p = new PlacementItem();
            p.build_area_id = area.build_area_id;
            p.build_area_numeric_id = area.build_area_numeric_id;
            p.foundation_type = foundation.foundation_type;
            p.base_y = foundation.base_y;
            p.scanned_blocks = areaBlocks.size();
            p.planned_nodes = foundation.placements != null ? foundation.placements.size() : 0;
            p.changed_blocks = 0;
            CityC8Stages.FoundationItem executableFoundation = copyFoundationWithoutPlacements(foundation);
            if (foundation.placements != null) {
                for (CityC8Stages.PlacementNode node : foundation.placements) {
                    if (node == null) continue;
                    boolean insideArea = footprintInsideArea(areaBlocks, node);
                    if (insideArea) {
                        executableFoundation.placements.add(node);
                        plannedTaskCount++;
                    }
                    recordStructureResult(
                            p,
                            node,
                            node.y > 0 ? node.y : foundation.base_y,
                            false,
                            insideArea
                                    ? (mode == Mode.DRY_RUN ? "dry_run_planned" : "queued_for_build")
                                    : "runtime_out_of_area"
                    );
                }
            }
            out.placement.items.add(p);
            executablePlan.foundations.add(executableFoundation);

            DecorationItem d = new DecorationItem();
            d.build_area_id = area.build_area_id;
            d.strategy = inferDecorStrategy(foundation.foundation_type);
            d.note = "Generated from C8 foundation type";
            out.decoration.items.add(d);
        }

        out.placement.processed_areas = out.placement.items.size();
        out.placement.changed_blocks_total = 0;
        out.placement.enqueued_tasks_count = mode == Mode.DRY_RUN ? 0 : plannedTaskCount;
        out.placement.applied_tasks_count = 0;
        out.queue = mode == Mode.DRY_RUN
                ? CityC9BuildQueue.filtered(existingQueue, null)
                : CityC9BuildQueue.upsertFromPlan(existingQueue, cityId, executablePlan, null);
        out.queue_summary = CityC9BuildQueue.summarize(out.queue, null);
        return out;
    }

    public static void save(Path cityDir, C9Result result) throws Exception {
        if (cityDir == null || result == null) return;
        Files.createDirectories(cityDir);
        if (result.placement != null) {
            Files.writeString(cityDir.resolve(C9_PLACEMENT_FILE), GSON.toJson(result.placement), StandardCharsets.UTF_8);
        }
        if (result.decoration != null) {
            Files.writeString(cityDir.resolve(C9_DECORATION_FILE), GSON.toJson(result.decoration), StandardCharsets.UTF_8);
        }
    }

    public static C9Placement loadPlacement(Path cityDir) throws Exception {
        Path file = cityDir.resolve(C9_PLACEMENT_FILE);
        if (!Files.exists(file)) return null;
        return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), C9Placement.class);
    }

    public static C9Decoration loadDecoration(Path cityDir) throws Exception {
        Path file = cityDir.resolve(C9_DECORATION_FILE);
        if (!Files.exists(file)) return null;
        return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), C9Decoration.class);
    }

    private static int placeArea(
            ServerLevel level,
            Set<Long> areaBlocks,
            CityC8Stages.FoundationItem foundation,
            int budget,
            PlacementItem resultItem
    ) {
        if (areaBlocks == null || areaBlocks.isEmpty() || budget <= 0) return 0;
        int changed = 0;
        int targetY = level != null ? Math.max(level.getMinBuildHeight() + 1, foundation.base_y) : foundation.base_y;
        Set<Long> set = areaBlocks;

        if (foundation.placements != null && !foundation.placements.isEmpty()) {
            List<CityC8Stages.PlacementNode> orderedNodes = new ArrayList<>(foundation.placements);
            boolean legacyPlan = orderedNodes.stream().anyMatch(node -> node == null || node.build_order == null);
            orderedNodes.sort(Comparator
                    .comparing((CityC8Stages.PlacementNode node) -> node != null && node.build_order != null ? node.build_order : Integer.MAX_VALUE)
                    .thenComparing(node -> node != null && node.node_id != null ? node.node_id : ""));
            System.out.println("[C9] placements branch build_area=" + foundation.build_area_id
                    + " dry_run=" + (level == null)
                    + " count=" + orderedNodes.size()
                    + " legacy=" + legacyPlan);
            Map<String, CityC8Stages.PlacementNode> byId = new LinkedHashMap<>();
            for (CityC8Stages.PlacementNode node : orderedNodes) {
                if (node != null && node.node_id != null) byId.put(node.node_id, node);
            }
            Map<String, PlacedNodeState> placedStates = new LinkedHashMap<>();
            Set<String> terminatedBranchRoots = new HashSet<>();

            for (CityC8Stages.PlacementNode node : orderedNodes) {
                if (node == null || node.template_id == null || node.template_id.isBlank()) continue;
                if (isDescendantOfAny(node, byId, terminatedBranchRoots)) {
                    recordStructureResult(resultItem, node, targetY, false, "skipped_terminalized_branch");
                    continue;
                }
                PlacedStructure placed = new PlacedStructure();
                placed.node_id = node.node_id;
                placed.template_id = node.template_id;
                placed.x = node.x;
                placed.y = node.y > 0 ? node.y : targetY;
                placed.z = node.z;
                placed.rotation = node.rotation;
                placed.build_order = node.build_order;
                placed.terminalized = node.terminalized;
                boolean ok = false;
                String rejectReason = null;
                if (level != null && !legacyPlan) {
                    rejectReason = validateRuntimePlacement(node, areaBlocks, byId, placedStates);
                }
                if (level != null && rejectReason == null) {
                    StructureInjector.TemplateSnapshot snapshot = shouldCaptureSnapshot(node)
                            ? StructureInjector.captureTemplateSnapshot(level, node.template_id, new BlockPos(node.x, placed.y, node.z), toRotation(node.rotation))
                            : null;
                    System.out.println("[C9] placing template=" + node.template_id
                            + " node=" + node.node_id
                            + " pos=(" + node.x + "," + placed.y + "," + node.z + ")"
                            + " rot=" + node.rotation);
                    ok = StructureInjector.spawnStructureAtBlock(
                            level,
                            node.template_id,
                            new BlockPos(node.x, placed.y, node.z),
                            toRotation(node.rotation),
                            true
                    );
                    if (ok) {
                        placedStates.put(node.node_id, new PlacedNodeState(node, placed.y, snapshot));
                    }
                }
                if (level != null && rejectReason != null) {
                    String fallbackReason = tryTerminalizeAncestor(level, node, byId, placedStates, terminatedBranchRoots);
                    placed.placed = false;
                    placed.reason = fallbackReason != null ? fallbackReason : rejectReason;
                } else if (level != null && !ok) {
                    String fallbackReason = tryTerminalizeAncestor(level, node, byId, placedStates, terminatedBranchRoots);
                    placed.placed = false;
                    placed.reason = fallbackReason != null ? fallbackReason : "structure_place_failed";
                } else {
                    placed.placed = ok;
                    placed.reason = level != null
                            ? (ok ? "placed_from_c8_plan" : "structure_place_failed")
                            : "dry_run_planned";
                }
                System.out.println("[C9] structure entry node=" + placed.node_id
                        + " dry_run=" + (level == null)
                        + " placed=" + placed.placed
                        + " reason=" + placed.reason);
                if (resultItem != null) {
                    resultItem.structures.add(placed);
                    if (ok) resultItem.placed_structures++;
                }
            }
            return changed;
        }

        if (level == null) {
            System.out.println("[C9] no placements for build_area=" + foundation.build_area_id + " in dry-run fallback mode");
            return 0;
        }

        for (long key : areaBlocks) {
            if (changed >= budget) break;
            int x = unpackX(key);
            int z = unpackZ(key);
            BlockPos floorPos = new BlockPos(x, targetY - 1, z);
            BlockPos topPos = new BlockPos(x, targetY, z);

            if ("TERRACE".equals(foundation.foundation_type)) {
                int ring = (Math.abs(x + z) % 5);
                BlockPos terracePos = new BlockPos(x, targetY + (ring / 2), z);
                if (!level.getBlockState(terracePos).is(Blocks.STONE_BRICKS)) {
                    level.setBlock(terracePos, Blocks.STONE_BRICKS.defaultBlockState(), 3);
                    changed++;
                }
                if (changed >= budget) break;
                continue;
            }

            if (!level.getBlockState(floorPos).is(Blocks.STONE)) {
                level.setBlock(floorPos, Blocks.STONE.defaultBlockState(), 3);
                changed++;
                if (changed >= budget) break;
            }
            if (!level.getBlockState(topPos).is(Blocks.STONE_BRICKS)) {
                level.setBlock(topPos, Blocks.STONE_BRICKS.defaultBlockState(), 3);
                changed++;
                if (changed >= budget) break;
            }

            if ("PLATFORM_WITH_RETAINING_WALL".equals(foundation.foundation_type) && isBoundary(set, x, z)) {
                BlockPos wallPos = new BlockPos(x, targetY + 1, z);
                if (!level.getBlockState(wallPos).is(Blocks.COBBLESTONE_WALL)) {
                    level.setBlock(wallPos, Blocks.COBBLESTONE_WALL.defaultBlockState(), 3);
                    changed++;
                }
            }
        }
        return changed;
    }

    private static void recordStructureResult(
            PlacementItem resultItem,
            CityC8Stages.PlacementNode node,
            int targetY,
            boolean placedFlag,
            String reason
    ) {
        if (resultItem == null || node == null) return;
        PlacedStructure placed = new PlacedStructure();
        placed.node_id = node.node_id;
        placed.template_id = node.template_id;
        placed.x = node.x;
        placed.y = node.y > 0 ? node.y : targetY;
        placed.z = node.z;
        placed.rotation = node.rotation;
        placed.build_order = node.build_order;
        placed.terminalized = node.terminalized;
        placed.placed = placedFlag;
        placed.reason = reason;
        resultItem.structures.add(placed);
    }

    private static boolean shouldCaptureSnapshot(CityC8Stages.PlacementNode node) {
        return node != null
                && node.fallback_terminal_template_id != null
                && !node.fallback_terminal_template_id.isBlank();
    }

    private static String validateRuntimePlacement(
            CityC8Stages.PlacementNode node,
            Set<Long> areaBlocks,
            Map<String, CityC8Stages.PlacementNode> byId,
            Map<String, PlacedNodeState> placedStates
    ) {
        if (node == null) return "missing_node";
        if (!footprintInsideArea(areaBlocks, node)) return "runtime_out_of_area";
        if (node.parent_node_id != null && !node.parent_node_id.isBlank() && !placedStates.containsKey(node.parent_node_id)) {
            return "missing_parent_before_build";
        }
        for (PlacedNodeState state : placedStates.values()) {
            if (state == null || state.node == null) continue;
            if (state.node.node_id != null && state.node.node_id.equals(node.parent_node_id)) continue;
            if (intersects(node, state.node)) return "runtime_footprint_collision";
        }
        if (node.parent_node_id != null && byId != null) {
            CityC8Stages.PlacementNode parent = byId.get(node.parent_node_id);
            if (parent != null
                    && parent.terminalized
                    && parent.fallback_terminal_template_id != null
                    && !parent.fallback_terminal_template_id.isBlank()) {
                return "parent_already_terminalized";
            }
            if (parent != null && node.incoming_parent_connector_x != null && node.incoming_child_connector_x != null) {
                int dx = node.incoming_child_connector_x - node.incoming_parent_connector_x;
                int dz = node.incoming_child_connector_z - node.incoming_parent_connector_z;
                if (Math.abs(dx) + Math.abs(dz) != 1) return "runtime_connector_mismatch";
            }
        }
        return null;
    }

    private static boolean intersects(CityC8Stages.PlacementNode a, CityC8Stages.PlacementNode b) {
        if (a == null || b == null
                || a.footprint_min_x == null || a.footprint_min_z == null || a.footprint_max_x == null || a.footprint_max_z == null
                || b.footprint_min_x == null || b.footprint_min_z == null || b.footprint_max_x == null || b.footprint_max_z == null) {
            return false;
        }
        boolean separated = a.footprint_max_x < b.footprint_min_x
                || a.footprint_min_x > b.footprint_max_x
                || a.footprint_max_z < b.footprint_min_z
                || a.footprint_min_z > b.footprint_max_z;
        return !separated;
    }

    private static String tryTerminalizeAncestor(
            ServerLevel level,
            CityC8Stages.PlacementNode failedNode,
            Map<String, CityC8Stages.PlacementNode> byId,
            Map<String, PlacedNodeState> placedStates,
            Set<String> terminatedBranchRoots
    ) {
        CityC8Stages.PlacementNode cursor = failedNode;
        while (cursor != null && cursor.parent_node_id != null && !cursor.parent_node_id.isBlank()) {
            PlacedNodeState parentState = placedStates.get(cursor.parent_node_id);
            CityC8Stages.PlacementNode parentNode = byId.get(cursor.parent_node_id);
            if (parentState != null && parentNode != null && shouldCaptureSnapshot(parentNode)) {
                if (parentState.snapshot == null) return "terminalize_snapshot_missing";
                boolean restored = StructureInjector.restoreTemplateSnapshot(level, parentState.snapshot);
                if (!restored) return "terminalize_restore_failed";

                BlockPos origin = new BlockPos(
                        parentNode.fallback_terminal_x != null ? parentNode.fallback_terminal_x : parentNode.x,
                        parentState.y,
                        parentNode.fallback_terminal_z != null ? parentNode.fallback_terminal_z : parentNode.z
                );
                boolean replaced = StructureInjector.spawnStructureAtBlock(
                        level,
                        parentNode.fallback_terminal_template_id,
                        origin,
                        toRotation(parentNode.fallback_terminal_rotation != null ? parentNode.fallback_terminal_rotation : parentNode.rotation),
                        true
                );
                if (!replaced) return "terminalize_replace_failed";

                parentNode.template_id = parentNode.fallback_terminal_template_id;
                if (parentNode.fallback_terminal_x != null) parentNode.x = parentNode.fallback_terminal_x;
                if (parentNode.fallback_terminal_z != null) parentNode.z = parentNode.fallback_terminal_z;
                if (parentNode.fallback_terminal_rotation != null) parentNode.rotation = parentNode.fallback_terminal_rotation;
                parentNode.terminalized = true;
                placedStates.put(parentNode.node_id, new PlacedNodeState(parentNode, parentState.y, null));
                terminatedBranchRoots.add(parentNode.node_id);
                return "terminalized_parent_after_runtime_reject";
            }
            cursor = parentNode;
        }
        if (failedNode != null && failedNode.parent_node_id != null) {
            terminatedBranchRoots.add(failedNode.parent_node_id);
        }
        return null;
    }

    private static boolean isDescendantOfAny(
            CityC8Stages.PlacementNode node,
            Map<String, CityC8Stages.PlacementNode> byId,
            Set<String> roots
    ) {
        if (node == null || byId == null || roots == null || roots.isEmpty()) return false;
        CityC8Stages.PlacementNode cursor = node;
        while (cursor != null && cursor.parent_node_id != null && !cursor.parent_node_id.isBlank()) {
            if (roots.contains(cursor.parent_node_id)) return true;
            cursor = byId.get(cursor.parent_node_id);
        }
        return false;
    }

    private static Rotation toRotation(int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        return switch (normalized) {
            case 90 -> Rotation.CLOCKWISE_90;
            case 180 -> Rotation.CLOCKWISE_180;
            case 270 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static boolean isBoundary(Set<Long> set, int x, int z) {
        int[][] dirs = new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            if (!set.contains(packBlock(x + d[0], z + d[1]))) return true;
        }
        return false;
    }

    private static CityC8Stages.FoundationItem copyFoundationWithoutPlacements(CityC8Stages.FoundationItem source) {
        CityC8Stages.FoundationItem copy = new CityC8Stages.FoundationItem();
        if (source == null) return copy;
        copy.plot_id = source.plot_id;
        copy.build_area_id = source.build_area_id;
        copy.build_area_numeric_id = source.build_area_numeric_id;
        copy.group_id = source.group_id;
        copy.anchor_module_id = source.anchor_module_id;
        copy.arrangement_type = source.arrangement_type;
        copy.arrangement_params.putAll(source.arrangement_params);
        copy.foundation_type = source.foundation_type;
        copy.strategy = source.strategy;
        copy.base_y = source.base_y;
        copy.delta_height = source.delta_height;
        copy.selected_template = source.selected_template;
        copy.function_role = source.function_role;
        copy.interaction_role = source.interaction_role;
        copy.top_k_templates.addAll(source.top_k_templates);
        copy.fallback_chain.addAll(source.fallback_chain);
        copy.landing_hint = source.landing_hint;
        copy.growth_axis = source.growth_axis;
        copy.vertical_role = source.vertical_role;
        copy.vertical_clearance = source.vertical_clearance;
        copy.vertical_capable = source.vertical_capable;
        copy.vertical_mode_hint = source.vertical_mode_hint;
        copy.terrain_impact_extent.minX = source.terrain_impact_extent.minX;
        copy.terrain_impact_extent.minZ = source.terrain_impact_extent.minZ;
        copy.terrain_impact_extent.maxX = source.terrain_impact_extent.maxX;
        copy.terrain_impact_extent.maxZ = source.terrain_impact_extent.maxZ;
        copy.supports.addAll(source.supports);
        copy.terrain_metrics.height_min = source.terrain_metrics.height_min;
        copy.terrain_metrics.height_max = source.terrain_metrics.height_max;
        copy.terrain_metrics.height_avg = source.terrain_metrics.height_avg;
        copy.terrain_metrics.height_p50 = source.terrain_metrics.height_p50;
        copy.terrain_metrics.slope_avg = source.terrain_metrics.slope_avg;
        copy.terrain_metrics.edge_n = source.terrain_metrics.edge_n;
        copy.terrain_metrics.edge_e = source.terrain_metrics.edge_e;
        copy.terrain_metrics.edge_s = source.terrain_metrics.edge_s;
        copy.terrain_metrics.edge_w = source.terrain_metrics.edge_w;
        copy.arrangement_success = source.arrangement_success;
        copy.arrangement_errors.addAll(source.arrangement_errors);
        copy.arrangement_warnings.addAll(source.arrangement_warnings);
        return copy;
    }

    private static boolean footprintInsideArea(Set<Long> areaBlocks, CityC8Stages.PlacementNode node) {
        if (areaBlocks == null || areaBlocks.isEmpty() || node == null) return false;
        int minX = node.footprint_min_x != null ? node.footprint_min_x : node.x;
        int minZ = node.footprint_min_z != null ? node.footprint_min_z : node.z;
        int maxX = node.footprint_max_x != null ? node.footprint_max_x : node.x;
        int maxZ = node.footprint_max_z != null ? node.footprint_max_z : node.z;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!areaBlocks.contains(packBlock(x, z))) return false;
            }
        }
        return true;
    }

    private static String inferDecorStrategy(String foundationType) {
        if ("TERRACE".equals(foundationType)) return "SLOPE_GARDEN";
        if ("PLATFORM_WITH_RETAINING_WALL".equals(foundationType)) return "WALL_TORCHES";
        if ("PLATFORM".equals(foundationType)) return "PLAZA_LANTERNS";
        return "NATURAL_SCATTER";
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) key;
    }

    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private record PlacedNodeState(
            CityC8Stages.PlacementNode node,
            int y,
            StructureInjector.TemplateSnapshot snapshot
    ) {}
}
