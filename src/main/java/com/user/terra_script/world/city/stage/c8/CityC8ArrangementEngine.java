package com.user.terra_script.world.city.stage.c8;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.user.terra_script.world.city.stage.CityHeightResolver;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import com.user.terra_script.world.city.stage.c2.CityC2ScanBinaryIO;
import com.user.terra_script.world.city.stage.c6.CityC6Stages;
import com.user.terra_script.world.city.stage.c7.CityC7Stages;
import com.user.terra_script.world.city.stage.c8.arrangement.ArrangementType;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CityC8ArrangementEngine {
    private static final Gson GSON = new GsonBuilder().create();
    private static final String C3_5_CATALOG_FILE = "config/structureTemplate/C3_5_StructureCatalog.preprocessed.json";

    private CityC8ArrangementEngine() {}

    public static SolveResult solve(
            CityC6Stages.BuildAreaSummary area,
            CityC6Stages.LayoutPlan plan,
            CityC7Stages.GroupArrangementDecision arrangement,
            CityStage1BinaryIO.HeightData heightData,
            CityC2ScanBinaryIO.C2ScanData c2ScanData
    ) {
        SolveResult result = new SolveResult();
        if (area == null || plan == null || arrangement == null || arrangement.selected_components == null || arrangement.selected_components.isEmpty()) {
            result.success = false;
            result.errors.add("missing_required_inputs");
            return result;
        }

        Catalog catalog = loadCatalog();
        Map<String, TemplateMeta> metaById = indexCatalog(catalog);
        ArrangementType type = ArrangementType.parseOrDefault(arrangement.arrangement_type, ArrangementType.RING);
        int spacing = readInt(arrangement.arrangement_params, "spacing", readStrategyInt(arrangement, "segment_spacing", 10));
        int maxPieces = Math.max(arrangement.selected_components.size(), arrangement.limits != null ? arrangement.limits.max_pieces : readInt(arrangement.arrangement_params, "max_pieces", 6));
        int maxDepth = Math.max(1, arrangement.limits != null ? arrangement.limits.max_depth : readInt(arrangement.arrangement_params, "max_depth", 2));

        CityC6Stages.PrimaryModule anchor = plan.primary_modules != null && !plan.primary_modules.isEmpty() ? plan.primary_modules.get(0) : null;
        int baseX = arrangement.seed != null && arrangement.seed.start_x != 0
                ? arrangement.seed.start_x
                : (anchor != null ? (int) Math.round(anchor.anchor.x) : (int) Math.round(area.centroid.x));
        int baseZ = arrangement.seed != null && arrangement.seed.start_z != 0
                ? arrangement.seed.start_z
                : (anchor != null ? (int) Math.round(anchor.anchor.z) : (int) Math.round(area.centroid.z));
        int width = Math.max(1, area.bbox.maxX - area.bbox.minX + 1);
        int height = Math.max(1, area.bbox.maxZ - area.bbox.minZ + 1);
        boolean longX = width >= height;
        if ("z".equalsIgnoreCase(readStrategyString(arrangement, "primary_axis", readStrategyString(arrangement, "spine_axis", "auto")))) {
            longX = false;
        } else if ("x".equalsIgnoreCase(readStrategyString(arrangement, "primary_axis", readStrategyString(arrangement, "spine_axis", "auto")))) {
            longX = true;
        }

        SolveContext ctx = new SolveContext(area, arrangement, metaById, spacing, maxPieces, maxDepth, heightData, c2ScanData);
        if (type == ArrangementType.LINEAR_DOCK) {
            solveLinearDock(ctx, arrangement, metaById, baseX, baseZ, longX);
            result.placements.addAll(ctx.nodes);
            result.errors.addAll(ctx.errors);
            result.warnings.addAll(ctx.warnings);
            result.success = result.errors.isEmpty() && !result.placements.isEmpty();
            return result;
        }

        List<SeedAnchor> seeds = solveSeedAnchors(type, arrangement, arrangement.selected_components, baseX, baseZ, spacing, longX);
        for (SeedAnchor seed : seeds) {
            if (seed == null || seed.component == null) continue;
            String rootTemplateId = arrangement.seed != null && arrangement.seed.start_template_id != null && !arrangement.seed.start_template_id.isBlank()
                    ? arrangement.seed.start_template_id
                    : seed.component.template_id;
            seed.component.template_id = rootTemplateId;
            TemplateMeta rootMeta = metaById.get(rootTemplateId);
            CityC8Stages.PlacementNode root = node(seed.component, seed.x, seed.z, seed.rotation, 0, null, "seed_anchor");
            root.node_id = "p" + ctx.nextId++;
            applyFootprint(root, rootMeta);
            int mark = ctx.nodes.size();
            ctx.nodes.add(root);
            ctx.occupied.add(pack(root.x, root.z));
            boolean ok = expandNode(ctx, root, rootMeta, 0, true);
            if (!ok) {
                rollbackTo(ctx, mark);
                if (seed.component.required) {
                    ctx.errors.add("required_seed_failed:" + safe(seed.component.component_id));
                    result.success = false;
                    result.errors.addAll(ctx.errors);
                    result.warnings.addAll(ctx.warnings);
                    return result;
                }
            }
        }
        result.placements.addAll(ctx.nodes);
        result.errors.addAll(ctx.errors);
        result.warnings.addAll(ctx.warnings);
        result.success = result.errors.isEmpty() && !result.placements.isEmpty();
        return result;
    }

    private static void solveLinearDock(
            SolveContext ctx,
            CityC7Stages.GroupArrangementDecision arrangement,
            Map<String, TemplateMeta> metaById,
            int baseX,
            int baseZ,
            boolean alongX
    ) {
        if (arrangement == null || arrangement.selected_components == null || arrangement.selected_components.isEmpty()) {
            ctx.errors.add("missing_linear_components");
            return;
        }

        CityC7Stages.SelectedComponent rootComponent = arrangement.selected_components.get(0);
        String rootTemplateId = arrangement.seed != null && arrangement.seed.start_template_id != null && !arrangement.seed.start_template_id.isBlank()
                ? arrangement.seed.start_template_id
                : rootComponent.template_id;
        rootComponent.template_id = rootTemplateId;

        Direction forward = resolveLinearForwardDirection(arrangement, alongX);
        int rootRotation = arrangement.seed != null && arrangement.seed.start_rotation != 0
                ? arrangement.seed.start_rotation
                : forward.rotation;

        CityC8Stages.PlacementNode root = node(rootComponent, baseX, baseZ, rootRotation, 0, null, "seed_anchor");
        root.node_id = "p" + ctx.nextId++;
        ctx.nodes.add(root);
        ctx.occupied.add(pack(root.x, root.z));

        TemplateMeta currentMeta = metaById.get(root.template_id);
        CityC8Stages.PlacementNode currentNode = root;
        int depthLimit = Math.max(1, ctx.maxDepth);
        int targetPieces = Math.max(1, ctx.maxPieces);

        for (int depth = 1; depth < targetPieces && depth <= depthLimit; depth++) {
            TemplateMeta nextMeta = chooseLinearNextMeta(currentMeta, forward, metaById, arrangement, depth);
            if (nextMeta == null) {
                ctx.warnings.add("linear_no_candidate:" + safe(currentNode.node_id) + ":" + safe(forward.nameLower));
                break;
            }

            int step = linearStep(currentMeta, nextMeta, forward, ctx.spacing);
            int nx = currentNode.x + forward.dx * step;
            int nz = currentNode.z + forward.dz * step;
            if (ctx.occupied.contains(pack(nx, nz))) {
                ctx.warnings.add("occupied_collision:" + nx + "," + nz);
                break;
            }
            if (!insideArea(ctx.area, nx, nz, nextMeta, forward)) {
                ctx.warnings.add("out_of_bounds:" + nx + "," + nz);
                break;
            }

            CityC7Stages.SelectedComponent component = linearComponentFor(arrangement, rootComponent, depth);
            CityC8Stages.PlacementNode child = node(component, nx, nz, forward.rotation, depth, currentNode.node_id, "linear_sequence_expand");
            child.node_id = "p" + ctx.nextId++;
            child.template_id = nextMeta.structure_id;
            child.role = nextMeta.piece_role;
            child.attach_to_component_id = currentNode.component_id;
            applyFootprint(child, nextMeta);
            if (!isPlacementFeasible(ctx, child, nextMeta, "linear")) {
                break;
            }
            ctx.nodes.add(child);
            ctx.occupied.add(pack(nx, nz));
            currentNode = child;
            currentMeta = nextMeta;
        }
    }

    private static List<SeedAnchor> solveSeedAnchors(
            ArrangementType type,
            CityC7Stages.GroupArrangementDecision arrangement,
            List<CityC7Stages.SelectedComponent> components,
            int baseX,
            int baseZ,
            int spacing,
            boolean alongX
    ) {
        List<SeedAnchor> out = new ArrayList<>();
        int start = -((components.size() - 1) * spacing) / 2;
        for (int i = 0; i < components.size(); i++) {
            CityC7Stages.SelectedComponent component = components.get(i);
            int x = baseX;
            int z = baseZ;
            int rotation = arrangement.seed != null ? arrangement.seed.start_rotation : (alongX ? 90 : 0);
            switch (type) {
                case LINEAR_DOCK -> {
                    int offset = start + i * spacing;
                    x = alongX ? baseX + offset : baseX;
                    z = alongX ? baseZ : baseZ + offset;
                    rotation = alongX ? 90 : 0;
                }
                case COURTYARD, RING -> {
                    double theta = i * ((Math.PI * 2.0) / Math.max(1, components.size()));
                    x = baseX + (int) Math.round(Math.cos(theta) * Math.max(8, spacing));
                    z = baseZ + (int) Math.round(Math.sin(theta) * Math.max(8, spacing));
                    rotation = ((int) Math.round(Math.toDegrees(theta)) + 180) % 360;
                }
                case SPINE_BRANCH -> {
                    int spineOffset = (i / 2) * spacing;
                    int branchOffset = (i % 2 == 0 ? -1 : 1) * Math.max(6, spacing / 2);
                    if (alongX) {
                        x = baseX + spineOffset;
                        z = i == 0 ? baseZ : baseZ + branchOffset;
                        rotation = i == 0 ? 0 : (branchOffset < 0 ? 270 : 90);
                    } else {
                        x = i == 0 ? baseX : baseX + branchOffset;
                        z = baseZ + spineOffset;
                        rotation = i == 0 ? 90 : (branchOffset < 0 ? 180 : 0);
                    }
                }
                case TERRACE_CHAIN -> {
                    int offset = i * spacing;
                    if (alongX) {
                        x = baseX + offset;
                        z = baseZ + i * 2;
                        rotation = 90;
                    } else {
                        x = baseX + i * 2;
                        z = baseZ + offset;
                        rotation = 0;
                    }
                }
            }
            out.add(new SeedAnchor(component, x, z, rotation));
        }
        return out;
    }

    private static List<TemplateMeta> collectNeighborCandidates(
            TemplateMeta current,
            Direction dir,
            Map<String, TemplateMeta> metaById,
            CityC7Stages.GroupArrangementDecision arrangement,
            int depth
    ) {
        List<TemplateMeta> candidates = new ArrayList<>();
        if (current == null) return candidates;
        if (current.allowed_neighbors != null && !current.allowed_neighbors.isEmpty()) {
            for (String allowed : current.allowed_neighbors) {
                TemplateMeta byId = metaById.get(allowed);
                if (byId != null) candidates.add(byId);
                else {
                    for (TemplateMeta meta : metaById.values()) {
                        if (meta.structure_id.contains(allowed) || meta.path.contains(allowed.toLowerCase(Locale.ROOT))) {
                            candidates.add(meta);
                        }
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            String arrangementType = arrangement != null ? safe(arrangement.arrangement_type).toLowerCase(Locale.ROOT) : "";
            for (TemplateMeta meta : metaById.values()) {
                if (meta == null || meta.structure_id == null || meta.structure_id.isBlank()) continue;
                if (arrangementType.contains("dock") || safe(arrangement != null ? arrangement.group_id : "").toLowerCase(Locale.ROOT).contains("port")) {
                    if (meta.path.contains("ship") || meta.path.contains("ocean") || meta.path.contains("beach") || meta.path.contains("lighthouse")) {
                        candidates.add(meta);
                    }
                } else {
                    if (sameFamily(current.path, meta.path)) candidates.add(meta);
                }
            }
        }
        candidates.sort((a, b) -> Double.compare(scoreNeighbor(b, dir, depth), scoreNeighbor(a, dir, depth)));
        return dedupeCandidates(candidates);
    }

    private static TemplateMeta chooseLinearNextMeta(
            TemplateMeta current,
            Direction dir,
            Map<String, TemplateMeta> metaById,
            CityC7Stages.GroupArrangementDecision arrangement,
            int depth
    ) {
        TemplateMeta fromComponent = null;
        if (arrangement != null && arrangement.selected_components != null && !arrangement.selected_components.isEmpty()) {
            int index = Math.min(depth, arrangement.selected_components.size() - 1);
            CityC7Stages.SelectedComponent selected = arrangement.selected_components.get(index);
            if (selected != null && selected.template_id != null) {
                fromComponent = metaById.get(selected.template_id);
            }
        }
        if (fromComponent != null) {
            return fromComponent;
        }
        if (current != null && current.structure_id != null) {
            TemplateMeta same = metaById.get(current.structure_id);
            if (same != null) {
                return same;
            }
        }
        List<TemplateMeta> candidates = collectNeighborCandidates(current, dir, metaById, arrangement, depth);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static boolean expandNode(
            SolveContext ctx,
            CityC8Stages.PlacementNode node,
            TemplateMeta meta,
            int depth,
            boolean isRoot
    ) {
        if (ctx.nodes.size() > ctx.maxPieces) {
            ctx.errors.add("max_pieces_exceeded");
            return false;
        }
        if (meta == null) {
            ctx.warnings.add("missing_catalog_meta:" + safe(node.template_id));
            return true;
        }

        List<String> dirs = !meta.connector_dirs.isEmpty() ? meta.connector_dirs : meta.jigsawFacing;
        if (isRoot) {
            dirs = allowedRootDirs(ctx.arrangement, dirs);
        }
        if (dirs.isEmpty()) {
            return canTerminate(meta, 0, isRoot);
        }
        if (depth >= ctx.maxDepth) {
            ctx.warnings.add("max_depth_reached:" + safe(node.node_id));
            return canTerminate(meta, 0, isRoot);
        }

        int successCount = 0;
        int branchIndex = 0;
        for (String dirRaw : dirs) {
            Direction dir = Direction.parse(dirRaw);
            if (dir == null) continue;
            if (successCount >= ctx.maxBranchPerDepth) break;
            int branchMark = ctx.nodes.size();
            CityC8Stages.PlacementNode child = tryCreateChild(ctx, node, meta, dir, depth, branchIndex);
            branchIndex++;
            if (child == null) {
                ctx.warnings.add("branch_rejected:" + safe(node.node_id) + ":" + safe(dir.nameLower));
                continue;
            }
            TemplateMeta childMeta = ctx.metaById.get(child.template_id);
            boolean childOk = expandNode(ctx, child, childMeta, depth + 1, false);
            if (!childOk) {
                rollbackTo(ctx, branchMark);
                continue;
            }
            successCount++;
            if (ctx.nodes.size() >= ctx.maxPieces) break;
        }
        return canTerminate(meta, successCount, isRoot);
    }

    private static CityC8Stages.PlacementNode tryCreateChild(
            SolveContext ctx,
            CityC8Stages.PlacementNode parent,
            TemplateMeta parentMeta,
            Direction dir,
            int depth,
            int branchIndex
    ) {
        List<TemplateMeta> candidates = collectNeighborCandidates(parentMeta, dir, ctx.metaById, ctx.arrangement, depth + 1);
        if (candidates.isEmpty()) return null;

        List<CandidatePlacement> feasible = new ArrayList<>();
        for (TemplateMeta nextMeta : candidates) {
            int step = Math.max(ctx.spacing, nextMeta.primarySpan() + 2);
            int nx = parent.x + dir.dx * step;
            int nz = parent.z + dir.dz * step;
            if (ctx.occupied.contains(pack(nx, nz))) {
                nx += dir.rightDx() * (branchIndex + 1) * 4;
                nz += dir.rightDz() * (branchIndex + 1) * 4;
            }
            CityC8Stages.PlacementNode child = new CityC8Stages.PlacementNode();
            child.node_id = "p" + ctx.nextId;
            child.component_id = parent.component_id;
            child.template_id = nextMeta.structure_id;
            child.role = nextMeta.piece_role;
            child.x = nx;
            child.z = nz;
            child.rotation = dir.rotation;
            child.level = depth + 1;
            child.attach_to_component_id = parent.component_id;
            child.parent_node_id = parent.node_id;
            child.placement_reason = "jigsaw_bfs_expand";
            applyFootprint(child, nextMeta);
            String reject = firstPlacementRejectReason(ctx, child, nextMeta);
            if (reject == null) {
                feasible.add(new CandidatePlacement(child, nextMeta, scoreNeighbor(nextMeta, dir, depth + 1)));
            } else {
                ctx.warnings.add(reject + ":" + safe(parent.node_id) + ":" + safe(nextMeta.structure_id));
            }
        }
        if (feasible.isEmpty()) return null;

        CandidatePlacement chosen = pickCandidate(ctx, feasible, parent, dir, depth);
        chosen.node.node_id = "p" + ctx.nextId++;
        ctx.nodes.add(chosen.node);
        ctx.occupied.add(pack(chosen.node.x, chosen.node.z));
        return chosen.node;
    }

    private static boolean canTerminate(TemplateMeta meta, int successCount, boolean isRoot) {
        String role = meta == null || meta.piece_role == null ? "" : meta.piece_role.toUpperCase(Locale.ROOT);
        if ("MIDDLE".equals(role)) return successCount > 0;
        if (isRoot && "START".equals(role)) return successCount > 0;
        return true;
    }

    private static List<String> allowedRootDirs(CityC7Stages.GroupArrangementDecision arrangement, List<String> fallback) {
        if (arrangement == null) return fallback;
        Object dirs = strategyMap(arrangement).get("forward_dirs");
        if (dirs instanceof List<?> list && !list.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (Object item : list) out.add(String.valueOf(item));
            return out;
        }
        String preferred = arrangement.seed != null ? arrangement.seed.start_connector_dir : null;
        if (preferred != null && !preferred.isBlank()) return List.of(preferred);
        return fallback;
    }

    private static int readStrategyInt(CityC7Stages.GroupArrangementDecision arrangement, String key, int fallback) {
        Object value = strategyMap(arrangement).get(key);
        if (value instanceof Number number) return number.intValue();
        try {
            return value != null ? Integer.parseInt(String.valueOf(value).trim()) : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String readStrategyString(CityC7Stages.GroupArrangementDecision arrangement, String key, String fallback) {
        Object value = strategyMap(arrangement).get(key);
        return value != null ? String.valueOf(value) : fallback;
    }

    private static Map<String, Object> strategyMap(CityC7Stages.GroupArrangementDecision arrangement) {
        if (arrangement == null || arrangement.arrangement_type == null) return Map.of();
        String type = arrangement.arrangement_type.toUpperCase(Locale.ROOT);
        if ("COURTYARD".equals(type) || "RING".equals(type)) return arrangement.courtyard != null ? arrangement.courtyard : Map.of();
        if ("SPINE_BRANCH".equals(type)) return arrangement.spine_branch != null ? arrangement.spine_branch : Map.of();
        if ("CLUSTER".equals(type)) return arrangement.cluster != null ? arrangement.cluster : Map.of();
        return arrangement.linear != null ? arrangement.linear : Map.of();
    }

    private static void rollbackTo(SolveContext ctx, int mark) {
        while (ctx.nodes.size() > mark) {
            CityC8Stages.PlacementNode removed = ctx.nodes.remove(ctx.nodes.size() - 1);
            if (removed != null) ctx.occupied.remove(pack(removed.x, removed.z));
        }
    }

    private static boolean insideArea(CityC6Stages.BuildAreaSummary area, int x, int z) {
        if (area == null || area.bbox == null) return false;
        int pad = 8;
        return x >= area.bbox.minX - pad && x <= area.bbox.maxX + pad
                && z >= area.bbox.minZ - pad && z <= area.bbox.maxZ + pad;
    }

    private static boolean insideArea(CityC6Stages.BuildAreaSummary area, int x, int z, TemplateMeta meta, Direction forward) {
        if (!insideArea(area, x, z)) return false;
        if (area == null || area.bbox == null || meta == null || forward == null) return true;

        int axisSpan = axisSpan(meta, forward);
        int crossSpan = crossSpan(meta, forward);
        int halfAxis = Math.max(1, axisSpan / 2);
        int halfCross = Math.max(1, crossSpan / 2);
        int pad = 4;
        int minX = x - (forward.dx != 0 ? halfAxis : halfCross);
        int maxX = x + (forward.dx != 0 ? halfAxis : halfCross);
        int minZ = z - (forward.dz != 0 ? halfAxis : halfCross);
        int maxZ = z + (forward.dz != 0 ? halfAxis : halfCross);
        return minX >= area.bbox.minX - pad && maxX <= area.bbox.maxX + pad
                && minZ >= area.bbox.minZ - pad && maxZ <= area.bbox.maxZ + pad;
    }

    private static double scoreNeighbor(TemplateMeta meta, Direction dir, int depth) {
        double score = 0.0;
        if (meta == null) return score;
        if ("START".equals(meta.piece_role)) score -= 0.3;
        if ("MIDDLE".equals(meta.piece_role)) score += 0.2;
        if ("SINGLE".equals(meta.piece_role)) score += 0.12;
        if (meta.connector_dirs.contains(dir.opposite().nameLower)) score += 0.35;
        if (meta.jigsawFacing.contains(dir.opposite().nameLower)) score += 0.18;
        if (meta.path.contains("ship")) score += 0.18;
        if (meta.path.contains("dock")) score += 0.14;
        if (meta.path.contains("lighthouse")) score += 0.08;
        score -= depth * 0.05;
        return score;
    }

    private static boolean sameFamily(String a, String b) {
        String sa = safe(a);
        String sb = safe(b);
        if (sa.contains("ship") && sb.contains("ship")) return true;
        if (sa.contains("beach") && sb.contains("beach")) return true;
        if (sa.contains("ocean") && sb.contains("ocean")) return true;
        if (sa.contains("village") && sb.contains("village")) return true;
        return false;
    }

    private static Map<String, TemplateMeta> indexCatalog(Catalog catalog) {
        Map<String, TemplateMeta> index = new LinkedHashMap<>();
        if (catalog == null || catalog.structures == null) return index;
        for (TemplateMeta meta : catalog.structures) {
            if (meta != null) {
                meta.jigsawFacing = meta.orientation != null && meta.orientation.jigsaw_facing != null
                        ? new ArrayList<>(meta.orientation.jigsaw_facing)
                        : new ArrayList<>();
            }
            if (meta != null && meta.structure_id != null) index.put(meta.structure_id, meta);
        }
        return index;
    }

    private static Catalog loadCatalog() {
        try {
            Path path = FMLPaths.GAMEDIR.get().resolve(C3_5_CATALOG_FILE);
            if (!Files.exists(path)) return null;
            return GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Catalog.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static CityC8Stages.PlacementNode node(CityC7Stages.SelectedComponent component, int x, int z, int rotation, int level, String parentNodeId, String reason) {
        CityC8Stages.PlacementNode node = new CityC8Stages.PlacementNode();
        node.component_id = component != null ? component.component_id : null;
        node.template_id = component != null ? component.template_id : null;
        node.role = component != null ? component.role : null;
        node.x = x;
        node.z = z;
        node.rotation = rotation;
        node.level = level;
        node.attach_to_component_id = component != null ? component.attach_to_component_id : null;
        node.parent_node_id = parentNodeId;
        node.placement_reason = reason;
        return node;
    }

    private static int readInt(Map<String, Object> params, String key, int fallback) {
        if (params == null || key == null || !params.containsKey(key) || params.get(key) == null) return fallback;
        Object value = params.get(key);
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String safe(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT);
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static Direction resolveLinearForwardDirection(CityC7Stages.GroupArrangementDecision arrangement, boolean alongX) {
        List<String> dirs = allowedRootDirs(arrangement, List.of(alongX ? "east" : "south"));
        if (dirs != null) {
            for (String dir : dirs) {
                Direction parsed = Direction.parse(dir);
                if (parsed != null) return parsed;
            }
        }
        return alongX ? Direction.EAST : Direction.SOUTH;
    }

    private static CityC7Stages.SelectedComponent linearComponentFor(
            CityC7Stages.GroupArrangementDecision arrangement,
            CityC7Stages.SelectedComponent fallback,
            int depth
    ) {
        if (arrangement == null || arrangement.selected_components == null || arrangement.selected_components.isEmpty()) {
            return fallback;
        }
        int index = Math.min(depth, arrangement.selected_components.size() - 1);
        CityC7Stages.SelectedComponent selected = arrangement.selected_components.get(index);
        return selected != null ? selected : fallback;
    }

    private static int linearStep(TemplateMeta current, TemplateMeta next, Direction forward, int spacing) {
        int currentSpan = current != null ? axisSpan(current, forward) : Math.max(6, spacing);
        int nextSpan = next != null ? axisSpan(next, forward) : currentSpan;
        int overlapBias = Math.max(2, spacing / 2);
        return Math.max(spacing, ((currentSpan + nextSpan) / 2) - overlapBias);
    }

    private static int axisSpan(TemplateMeta meta, Direction dir) {
        if (meta == null || meta.size == null || dir == null) return 0;
        return dir.dx != 0 ? meta.size.width : meta.size.length;
    }

    private static int crossSpan(TemplateMeta meta, Direction dir) {
        if (meta == null || meta.size == null || dir == null) return 0;
        return dir.dx != 0 ? meta.size.length : meta.size.width;
    }

    private static void applyFootprint(CityC8Stages.PlacementNode node, TemplateMeta meta) {
        if (node == null || meta == null || meta.size == null) return;
        FootprintBox box = computeFootprint(node.x, node.z, node.rotation, meta.size.length, meta.size.width);
        node.footprint_min_x = box.minX;
        node.footprint_min_z = box.minZ;
        node.footprint_max_x = box.maxX;
        node.footprint_max_z = box.maxZ;
    }

    private static String firstPlacementRejectReason(SolveContext ctx, CityC8Stages.PlacementNode node, TemplateMeta meta) {
        if (ctx.occupied.contains(pack(node.x, node.z))) return "occupied_collision";
        if (!insideArea(ctx.area, node.x, node.z, meta, Direction.fromRotation(node.rotation))) return "out_of_bounds";
        if (intersectsExisting(ctx.nodes, node)) return "footprint_collision";
        if (!terrainPasses(ctx, node)) return "terrain_rejected";
        return null;
    }

    private static boolean isPlacementFeasible(SolveContext ctx, CityC8Stages.PlacementNode node, TemplateMeta meta, String prefix) {
        String reject = firstPlacementRejectReason(ctx, node, meta);
        if (reject == null) return true;
        ctx.warnings.add(reject + ":" + prefix + ":" + safe(node.template_id));
        return false;
    }

    private static boolean intersectsExisting(List<CityC8Stages.PlacementNode> nodes, CityC8Stages.PlacementNode candidate) {
        if (candidate == null || candidate.footprint_min_x == null || candidate.footprint_min_z == null
                || candidate.footprint_max_x == null || candidate.footprint_max_z == null) {
            return false;
        }
        for (CityC8Stages.PlacementNode existing : nodes) {
            if (existing == null || existing.footprint_min_x == null || existing.footprint_min_z == null
                    || existing.footprint_max_x == null || existing.footprint_max_z == null) {
                continue;
            }
            boolean separated = candidate.footprint_max_x < existing.footprint_min_x
                    || candidate.footprint_min_x > existing.footprint_max_x
                    || candidate.footprint_max_z < existing.footprint_min_z
                    || candidate.footprint_min_z > existing.footprint_max_z;
            if (!separated) return true;
        }
        return false;
    }

    private static boolean terrainPasses(SolveContext ctx, CityC8Stages.PlacementNode node) {
        if (ctx.heightData == null || node == null || node.footprint_min_x == null || node.footprint_min_z == null
                || node.footprint_max_x == null || node.footprint_max_z == null) {
            return true;
        }
        int centerX = node.x;
        int centerZ = node.z;
        int[] xs = new int[]{node.footprint_min_x, node.footprint_max_x, centerX, node.footprint_min_x, node.footprint_max_x};
        int[] zs = new int[]{node.footprint_min_z, node.footprint_max_z, centerZ, node.footprint_max_z, node.footprint_min_z};
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < xs.length; i++) {
            int h = CityHeightResolver.resolveHeight(ctx.heightData, ctx.c2ScanData, xs[i], zs[i]);
            min = Math.min(min, h);
            max = Math.max(max, h);
        }
        return (max - min) <= ctx.maxTerrainDelta;
    }

    private static CandidatePlacement pickCandidate(
            SolveContext ctx,
            List<CandidatePlacement> candidates,
            CityC8Stages.PlacementNode parent,
            Direction dir,
            int depth
    ) {
        if (candidates.size() == 1) return candidates.get(0);
        double total = 0.0;
        for (CandidatePlacement candidate : candidates) total += Math.max(0.05, candidate.weight);
        long seed = (safe(ctx.arrangement != null ? ctx.arrangement.group_id : "") + "|" + safe(parent.node_id) + "|" + dir.nameLower + "|" + depth).hashCode();
        double pick = (Math.abs(seed) % 10000) / 10000.0 * total;
        double acc = 0.0;
        for (CandidatePlacement candidate : candidates) {
            acc += Math.max(0.05, candidate.weight);
            if (pick <= acc) return candidate;
        }
        return candidates.get(0);
    }

    private static List<TemplateMeta> dedupeCandidates(List<TemplateMeta> candidates) {
        List<TemplateMeta> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TemplateMeta candidate : candidates) {
            if (candidate == null || candidate.structure_id == null || !seen.add(candidate.structure_id)) continue;
            out.add(candidate);
        }
        return out;
    }

    private static FootprintBox computeFootprint(int originX, int originZ, int rotation, int length, int width) {
        int normalized = ((rotation % 360) + 360) % 360;
        return switch (normalized) {
            case 90 -> new FootprintBox(originX - length + 1, originZ, originX, originZ + width - 1);
            case 180 -> new FootprintBox(originX - width + 1, originZ - length + 1, originX, originZ);
            case 270 -> new FootprintBox(originX, originZ - width + 1, originX + length - 1, originZ);
            default -> new FootprintBox(originX, originZ, originX + width - 1, originZ + length - 1);
        };
    }

    private static final class SeedAnchor {
        final CityC7Stages.SelectedComponent component;
        final int x;
        final int z;
        final int rotation;

        private SeedAnchor(CityC7Stages.SelectedComponent component, int x, int z, int rotation) {
            this.component = component;
            this.x = x;
            this.z = z;
            this.rotation = rotation;
        }
    }

    private static final class SolveContext {
        final CityC6Stages.BuildAreaSummary area;
        final CityC7Stages.GroupArrangementDecision arrangement;
        final Map<String, TemplateMeta> metaById;
        final int spacing;
        final int maxPieces;
        final int maxDepth;
        final int maxBranchPerDepth;
        final CityStage1BinaryIO.HeightData heightData;
        final CityC2ScanBinaryIO.C2ScanData c2ScanData;
        final int maxTerrainDelta;
        final List<CityC8Stages.PlacementNode> nodes = new ArrayList<>();
        final Set<Long> occupied = new HashSet<>();
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        int nextId = 1;

        private SolveContext(
                CityC6Stages.BuildAreaSummary area,
                CityC7Stages.GroupArrangementDecision arrangement,
                Map<String, TemplateMeta> metaById,
                int spacing,
                int maxPieces,
                int maxDepth,
                CityStage1BinaryIO.HeightData heightData,
                CityC2ScanBinaryIO.C2ScanData c2ScanData
        ) {
            this.area = area;
            this.arrangement = arrangement;
            this.metaById = metaById;
            this.spacing = spacing;
            this.maxPieces = maxPieces;
            this.maxDepth = maxDepth;
            this.heightData = heightData;
            this.c2ScanData = c2ScanData;
            this.maxTerrainDelta = readStrategyInt(arrangement, "max_terrain_delta", 12);
            this.maxBranchPerDepth = arrangement != null && arrangement.limits != null
                    ? Math.max(1, arrangement.limits.max_branch_per_depth)
                    : 1;
        }
    }

    private record FootprintBox(int minX, int minZ, int maxX, int maxZ) {}

    private record CandidatePlacement(CityC8Stages.PlacementNode node, TemplateMeta meta, double weight) {}

    public static final class SolveResult {
        public boolean success = true;
        public List<CityC8Stages.PlacementNode> placements = new ArrayList<>();
        public List<String> errors = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
    }

    private static final class Catalog {
        List<TemplateMeta> structures = new ArrayList<>();
    }

    private static final class TemplateMeta {
        String structure_id;
        TemplateSize size = new TemplateSize();
        TemplateOrientation orientation = new TemplateOrientation();
        String piece_role;
        String path = "";
        List<String> connector_types = new ArrayList<>();
        List<String> connector_dirs = new ArrayList<>();
        List<String> allowed_neighbors = new ArrayList<>();
        List<String> jigsawFacing = new ArrayList<>();

        private int primarySpan() {
            return Math.max(6, Math.max(size.length, size.width) / 2);
        }
    }

    private static final class TemplateSize {
        int length;
        int width;
        int height;
    }

    private static final class TemplateOrientation {
        List<String> jigsaw_facing = new ArrayList<>();
    }

    private enum Direction {
        NORTH(0, -1, 0, "north"),
        SOUTH(0, 1, 180, "south"),
        EAST(1, 0, 90, "east"),
        WEST(-1, 0, 270, "west");

        final int dx;
        final int dz;
        final int rotation;
        final String nameLower;

        Direction(int dx, int dz, int rotation, String nameLower) {
            this.dx = dx;
            this.dz = dz;
            this.rotation = rotation;
            this.nameLower = nameLower;
        }

        static Direction parse(String raw) {
            String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "north", "n" -> NORTH;
                case "south", "s" -> SOUTH;
                case "east", "e" -> EAST;
                case "west", "w" -> WEST;
                default -> null;
            };
        }

        Direction opposite() {
            return switch (this) {
                case NORTH -> SOUTH;
                case SOUTH -> NORTH;
                case EAST -> WEST;
                case WEST -> EAST;
            };
        }

        int rightDx() {
            return switch (this) {
                case NORTH -> 1;
                case SOUTH -> -1;
                case EAST -> 0;
                case WEST -> 0;
            };
        }

        int rightDz() {
            return switch (this) {
                case NORTH -> 0;
                case SOUTH -> 0;
                case EAST -> 1;
                case WEST -> -1;
            };
        }

        static Direction fromRotation(int rotation) {
            int normalized = ((rotation % 360) + 360) % 360;
            return switch (normalized) {
                case 90 -> EAST;
                case 180 -> SOUTH;
                case 270 -> WEST;
                default -> NORTH;
            };
        }
    }
}
