package com.user.terra_script.world.city.stage.c8;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.user.terra_script.world.city.stage.CityC35CatalogIO;
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
            applyConnectorMetadata(root, rootMeta);
            int mark = ctx.nodes.size();
            ctx.nodes.add(root);
            ctx.nodeById.put(root.node_id, root);
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
        assignBuildOrder(ctx.nodes);
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
        TemplateMeta rootMeta = metaById.get(root.template_id);
        applyFootprint(root, rootMeta);
        applyConnectorMetadata(root, rootMeta);
        ctx.nodes.add(root);
        ctx.nodeById.put(root.node_id, root);
        ctx.occupied.add(pack(root.x, root.z));

        TemplateMeta currentMeta = rootMeta;
        CityC8Stages.PlacementNode currentNode = root;
        int depthLimit = Math.max(1, ctx.maxDepth);
        int targetPieces = Math.max(1, ctx.maxPieces);

        for (int depth = 1; depth < targetPieces && depth <= depthLimit; depth++) {
            TemplateMeta nextMeta = chooseLinearNextMeta(currentMeta, forward, metaById, arrangement, depth);
            if (nextMeta == null) {
                ctx.warnings.add("linear_no_candidate:" + safe(currentNode.node_id) + ":" + safe(forward.nameLower));
                break;
            }

            ConnectorView parentConnector = pickLinearConnector(currentMeta, currentNode.rotation, forward);
            if (parentConnector == null) {
                ctx.warnings.add("linear_missing_connector:" + safe(currentNode.node_id) + ":" + safe(forward.nameLower));
                break;
            }
            CityC7Stages.SelectedComponent component = linearComponentFor(arrangement, rootComponent, depth);
            CandidatePlacement chosen = choosePreciseCandidate(ctx, currentNode, parentConnector, nextMeta, component, depth, "linear_sequence_expand");
            if (chosen == null) break;
            chosen.node.node_id = "p" + ctx.nextId++;
            ctx.nodes.add(chosen.node);
            ctx.nodeById.put(chosen.node.node_id, chosen.node);
            ctx.occupied.add(pack(chosen.node.x, chosen.node.z));
            currentNode = chosen.node;
            currentMeta = chosen.meta;
        }
        assignBuildOrder(ctx.nodes);
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
            ConnectorView sourceConnector,
            Map<String, TemplateMeta> metaById,
            CityC7Stages.GroupArrangementDecision arrangement,
            int depth
    ) {
        List<TemplateMeta> candidates = new ArrayList<>();
        if (current == null) return candidates;
        String requiredSocket = sourceConnector != null && sourceConnector.socket != null ? safe(sourceConnector.socket) : "";
        Set<String> allowedPools = sourceConnector != null && sourceConnector.connectToPools != null
                ? new HashSet<>(sourceConnector.connectToPools)
                : Set.of();
        if (current.allowed_neighbors != null && !current.allowed_neighbors.isEmpty()) {
            for (String allowed : current.allowed_neighbors) {
                TemplateMeta byId = metaById.get(allowed);
                if (byId != null && connectorCompatible(byId, dir, requiredSocket, allowedPools)) candidates.add(byId);
                else {
                    for (TemplateMeta meta : metaById.values()) {
                        if ((meta.structure_id.contains(allowed) || meta.path.contains(allowed.toLowerCase(Locale.ROOT)))
                                && connectorCompatible(meta, dir, requiredSocket, allowedPools)) {
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
                if (!connectorCompatible(meta, dir, requiredSocket, allowedPools)) continue;
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
        List<TemplateMeta> candidates = collectNeighborCandidates(current, dir, null, metaById, arrangement, depth);
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

        List<ConnectorView> connectors = resolveConnectorViews(meta, node.rotation);
        if (connectors.isEmpty()) {
            return canTerminate(meta, 0, isRoot);
        }
        if (depth >= ctx.maxDepth) {
            ctx.warnings.add("max_depth_reached:" + safe(node.node_id));
            return canTerminate(meta, 0, isRoot);
        }

        int successCount = 0;
        int branchIndex = 0;
        List<String> rootDirs = isRoot ? allowedRootDirs(ctx.arrangement, List.of()) : List.of();
        for (ConnectorView connector : connectors) {
            Direction dir = connector.direction;
            if (dir == null) continue;
            if (isRoot && !rootDirs.isEmpty() && !rootDirs.contains(dir.nameLower)) continue;
            if (successCount >= ctx.maxBranchPerDepth) break;
            int branchMark = ctx.nodes.size();
            CityC8Stages.PlacementNode child = tryCreateChild(ctx, node, meta, connector, depth, branchIndex);
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
        if (canTerminate(meta, successCount, isRoot)) return true;
        if (applyFallbackTerminal(ctx, node, "terminalized_after_branch_failure")) return true;
        return false;
    }

    private static CityC8Stages.PlacementNode tryCreateChild(
            SolveContext ctx,
            CityC8Stages.PlacementNode parent,
            TemplateMeta parentMeta,
            ConnectorView connector,
            int depth,
            int branchIndex
    ) {
        Direction dir = connector.direction;
        List<TemplateMeta> candidates = collectNeighborCandidates(parentMeta, dir, connector, ctx.metaById, ctx.arrangement, depth + 1);
        if (candidates.isEmpty()) return null;

        List<CandidatePlacement> feasible = new ArrayList<>();
        for (TemplateMeta nextMeta : candidates) {
            CandidatePlacement placement = choosePreciseCandidate(ctx, parent, connector, nextMeta, null, depth, "jigsaw_bfs_expand");
            if (placement != null) {
                feasible.add(placement.withWeight(scoreNeighbor(nextMeta, dir, depth + 1)));
                continue;
            }
            if (branchIndex > 0) {
                ctx.warnings.add("connector_alignment_failed:" + safe(parent.node_id) + ":" + safe(nextMeta.structure_id) + ":" + branchIndex);
            }
        }
        if (feasible.isEmpty()) return null;

        CandidatePlacement chosen = pickCandidate(ctx, feasible, parent, dir, depth);
        chosen.node.node_id = "p" + ctx.nextId++;
        ctx.nodes.add(chosen.node);
        ctx.nodeById.put(chosen.node.node_id, chosen.node);
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

    private static List<ConnectorView> resolveConnectorViews(TemplateMeta meta, int nodeRotation) {
        List<ConnectorView> out = new ArrayList<>();
        if (meta != null && meta.connectors != null && !meta.connectors.isEmpty()) {
            CityC35CatalogIO.Vec3i originOffset = meta.placement != null && meta.placement.origin_offset != null
                    ? meta.placement.origin_offset
                    : new CityC35CatalogIO.Vec3i();
            for (CityC35CatalogIO.ConnectorSpec connector : meta.connectors) {
                if (connector == null) continue;
                Direction local = Direction.parse(connector.facing);
                if (local == null) continue;
                int localX = connector.local_pos != null ? connector.local_pos.x - originOffset.x : 0;
                int localZ = connector.local_pos != null ? connector.local_pos.z - originOffset.z : 0;
                out.add(new ConnectorView(
                        connector.id,
                        localX,
                        localZ,
                        local,
                        rotate(local, nodeRotation),
                        connector.socket,
                        connector.connect_to_pools != null ? new ArrayList<>(connector.connect_to_pools) : List.of(),
                        connector.required,
                        Math.max(1, connector.max_connections)
                ));
            }
        }
        if (!out.isEmpty()) return out;

        List<String> fallback = meta != null && meta.connector_dirs != null && !meta.connector_dirs.isEmpty()
                ? meta.connector_dirs
                : (meta != null ? meta.jigsawFacing : List.of());
        for (String dirRaw : fallback) {
            Direction dir = Direction.parse(dirRaw);
            if (dir != null) out.add(new ConnectorView(dir.nameLower, 0, 0, dir, dir, "", List.of(), false, 1));
        }
        return out;
    }

    private static boolean connectorCompatible(TemplateMeta candidate, Direction incomingDir, String requiredSocket, Set<String> allowedPools) {
        if (candidate == null) return false;
        if (!allowedPools.isEmpty()) {
            String pool = safe(candidate.preset_pool);
            boolean poolOk = allowedPools.stream().anyMatch(pool::equalsIgnoreCase);
            if (!poolOk) return false;
        }
        if (incomingDir == null) return true;
        if (candidate.constraints != null && candidate.constraints.allowed_rotations != null && !candidate.constraints.allowed_rotations.isEmpty()) {
            for (Integer rotation : candidate.constraints.allowed_rotations) {
                if (rotation != null && hasMatchingConnector(candidate, incomingDir, requiredSocket, rotation)) return true;
            }
        }
        if (hasMatchingLegacyDirection(candidate, incomingDir)) return true;
        return candidate.connectors == null || candidate.connectors.isEmpty();
    }

    private static boolean hasMatchingConnector(TemplateMeta candidate, Direction incomingDir, String requiredSocket, int rotation) {
        return matchChildConnectors(candidate, incomingDir, requiredSocket, rotation).stream().findFirst().isPresent();
    }

    private static boolean hasMatchingLegacyDirection(TemplateMeta candidate, Direction incomingDir) {
        if (candidate == null || incomingDir == null) return false;
        String needed = incomingDir.opposite().nameLower;
        if (candidate.connector_dirs != null && candidate.connector_dirs.stream().anyMatch(needed::equalsIgnoreCase)) return true;
        return candidate.jigsawFacing != null && candidate.jigsawFacing.stream().anyMatch(needed::equalsIgnoreCase);
    }

    private static int resolveCandidateRotation(TemplateMeta candidate, Direction incomingDir, ConnectorView sourceConnector) {
        List<Integer> rotations = candidate.constraints != null && candidate.constraints.allowed_rotations != null && !candidate.constraints.allowed_rotations.isEmpty()
                ? candidate.constraints.allowed_rotations
                : (candidate.orientation != null && candidate.orientation.rotations != null && !candidate.orientation.rotations.isEmpty()
                ? candidate.orientation.rotations
                : List.of(0, 90, 180, 270));
        String requiredSocket = sourceConnector != null && sourceConnector.socket != null ? safe(sourceConnector.socket) : "";
        for (Integer rotation : rotations) {
            if (rotation == null) continue;
            if (hasMatchingConnector(candidate, incomingDir, requiredSocket, rotation)) return rotation;
        }
        return incomingDir != null ? incomingDir.rotation : 0;
    }

    private static List<ResolvedConnectorMatch> matchChildConnectors(TemplateMeta candidate, Direction incomingDir, String requiredSocket, int forcedRotation) {
        if (candidate == null || candidate.connectors == null || candidate.connectors.isEmpty() || incomingDir == null) return List.of();
        List<ResolvedConnectorMatch> matches = new ArrayList<>();
        for (ConnectorView connector : resolveConnectorViews(candidate, forcedRotation)) {
            if (connector == null || connector.direction != incomingDir.opposite()) continue;
            if (!requiredSocket.isBlank() && !requiredSocket.equals(safe(connector.socket))) continue;
            matches.add(new ResolvedConnectorMatch(connector, forcedRotation));
        }
        return matches;
    }

    private static CandidatePlacement choosePreciseCandidate(
            SolveContext ctx,
            CityC8Stages.PlacementNode parent,
            ConnectorView parentConnector,
            TemplateMeta nextMeta,
            CityC7Stages.SelectedComponent componentOverride,
            int depth,
            String placementReason
    ) {
        if (parent == null || parentConnector == null || nextMeta == null) return null;
        String requiredSocket = parentConnector.socket != null ? safe(parentConnector.socket) : "";
        for (ResolvedConnectorMatch match : candidateConnectorMatches(nextMeta, parentConnector, requiredSocket)) {
            int[] parentWorld = connectorWorldPos(parent, parentConnector);
            int[] childLocal = rotateLocal(match.connector.localX, match.connector.localZ, match.rotation);
            int childWorldX = parentWorld[0] + parentConnector.direction.dx;
            int childWorldZ = parentWorld[1] + parentConnector.direction.dz;
            int childOriginX = childWorldX - childLocal[0];
            int childOriginZ = childWorldZ - childLocal[1];

            CityC8Stages.PlacementNode child = new CityC8Stages.PlacementNode();
            child.node_id = "p" + ctx.nextId;
            child.component_id = componentOverride != null && componentOverride.component_id != null
                    ? componentOverride.component_id
                    : parent.component_id;
            child.template_id = nextMeta.structure_id;
            child.role = nextMeta.piece_role;
            child.x = childOriginX;
            child.z = childOriginZ;
            child.rotation = match.rotation;
            child.level = depth + 1;
            child.attach_to_component_id = parent.component_id;
            child.parent_node_id = parent.node_id;
            child.placement_reason = placementReason;
            child.incoming_parent_connector_id = parentConnector.id;
            child.incoming_child_connector_id = match.connector.id;
            child.incoming_parent_connector_x = parentWorld[0];
            child.incoming_parent_connector_z = parentWorld[1];
            child.incoming_child_connector_x = childWorldX;
            child.incoming_child_connector_z = childWorldZ;
            child.incoming_connector_dir = parentConnector.direction.nameLower;
            applyFootprint(child, nextMeta);
            applyConnectorMetadata(child, nextMeta);
            String reject = firstPlacementRejectReason(ctx, child, nextMeta);
            if (reject != null) {
                ctx.warnings.add(reject + ":" + safe(parent.node_id) + ":" + safe(nextMeta.structure_id));
                continue;
            }
            TerminalPlacement fallback = computeTerminalFallback(ctx, parent, parentConnector, child, nextMeta);
            if (fallback != null) {
                child.fallback_terminal_template_id = fallback.templateId;
                child.fallback_terminal_x = fallback.x;
                child.fallback_terminal_z = fallback.z;
                child.fallback_terminal_rotation = fallback.rotation;
            }
            return new CandidatePlacement(child, nextMeta, scoreNeighbor(nextMeta, parentConnector.direction, depth + 1));
        }
        return null;
    }

    private static List<ResolvedConnectorMatch> candidateConnectorMatches(TemplateMeta candidate, ConnectorView parentConnector, String requiredSocket) {
        List<Integer> rotations = candidate.constraints != null && candidate.constraints.allowed_rotations != null && !candidate.constraints.allowed_rotations.isEmpty()
                ? candidate.constraints.allowed_rotations
                : (candidate.orientation != null && candidate.orientation.rotations != null && !candidate.orientation.rotations.isEmpty()
                ? candidate.orientation.rotations
                : List.of(0, 90, 180, 270));
        List<ResolvedConnectorMatch> matches = new ArrayList<>();
        for (Integer rotation : rotations) {
            if (rotation == null) continue;
            matches.addAll(matchChildConnectors(candidate, parentConnector.direction, requiredSocket, rotation));
        }
        return matches;
    }

    private static TerminalPlacement computeTerminalFallback(
            SolveContext ctx,
            CityC8Stages.PlacementNode parent,
            ConnectorView parentConnector,
            CityC8Stages.PlacementNode child,
            TemplateMeta childMeta
    ) {
        if (parent == null || parentConnector == null || child == null || childMeta == null) return null;
        String role = safe(childMeta.piece_role).toUpperCase(Locale.ROOT);
        if ("END".equals(role) || "SINGLE".equals(role)) return null;

        List<TemplateMeta> candidates = new ArrayList<>();
        for (TemplateMeta meta : ctx.metaById.values()) {
            if (meta == null || meta.structure_id == null || meta.structure_id.equals(childMeta.structure_id)) continue;
            String candidateRole = safe(meta.piece_role).toUpperCase(Locale.ROOT);
            if (!"END".equals(candidateRole) && !"SINGLE".equals(candidateRole)) continue;
            if (!safe(meta.preset_pool).equalsIgnoreCase(safe(childMeta.preset_pool)) && !sameFamily(childMeta.path, meta.path)) continue;
            if (!connectorCompatible(meta, parentConnector.direction, safe(parentConnector.socket), Set.of())) continue;
            candidates.add(meta);
        }
        candidates.sort((a, b) -> Double.compare(scoreTerminalCandidate(b, childMeta), scoreTerminalCandidate(a, childMeta)));
        for (TemplateMeta candidate : candidates) {
            CandidatePlacement terminalCandidate = choosePreciseCandidate(ctx, parent, parentConnector, candidate, null, Math.max(0, child.level - 1), "terminal_fallback");
            if (terminalCandidate == null) continue;
            if (terminalCandidate.node.x == child.x && terminalCandidate.node.z == child.z
                    && terminalCandidate.node.rotation == child.rotation
                    && safe(terminalCandidate.node.template_id).equals(safe(child.template_id))) {
                continue;
            }
            return new TerminalPlacement(
                    terminalCandidate.node.template_id,
                    terminalCandidate.node.x,
                    terminalCandidate.node.z,
                    terminalCandidate.node.rotation
            );
        }
        return null;
    }

    private static double scoreTerminalCandidate(TemplateMeta candidate, TemplateMeta current) {
        double score = 0.0;
        String role = safe(candidate != null ? candidate.piece_role : "").toUpperCase(Locale.ROOT);
        if ("END".equals(role)) score += 0.35;
        if ("SINGLE".equals(role)) score += 0.20;
        if (candidate != null && current != null && safe(candidate.preset_pool).equalsIgnoreCase(safe(current.preset_pool))) score += 0.25;
        if (candidate != null && current != null && sameFamily(candidate.path, current.path)) score += 0.15;
        return score;
    }

    private static boolean applyFallbackTerminal(SolveContext ctx, CityC8Stages.PlacementNode node, String reason) {
        if (ctx == null || node == null || node.fallback_terminal_template_id == null || node.fallback_terminal_template_id.isBlank()
                || node.fallback_terminal_x == null || node.fallback_terminal_z == null || node.fallback_terminal_rotation == null) {
            return false;
        }
        TemplateMeta terminalMeta = ctx.metaById.get(node.fallback_terminal_template_id);
        if (terminalMeta == null) return false;

        long oldPacked = pack(node.x, node.z);
        ctx.occupied.remove(oldPacked);
        int oldX = node.x;
        int oldZ = node.z;
        String oldTemplate = node.template_id;
        int oldRotation = node.rotation;
        Integer oldMinX = node.footprint_min_x;
        Integer oldMinZ = node.footprint_min_z;
        Integer oldMaxX = node.footprint_max_x;
        Integer oldMaxZ = node.footprint_max_z;

        node.template_id = node.fallback_terminal_template_id;
        node.role = terminalMeta.piece_role;
        node.x = node.fallback_terminal_x;
        node.z = node.fallback_terminal_z;
        node.rotation = node.fallback_terminal_rotation;
        node.terminalized = true;
        node.placement_reason = reason;
        applyFootprint(node, terminalMeta);
        applyConnectorMetadata(node, terminalMeta);

        String reject = firstPlacementRejectReasonSkippingNode(ctx, node, terminalMeta);
        if (reject != null) {
            node.template_id = oldTemplate;
            node.x = oldX;
            node.z = oldZ;
            node.rotation = oldRotation;
            node.footprint_min_x = oldMinX;
            node.footprint_min_z = oldMinZ;
            node.footprint_max_x = oldMaxX;
            node.footprint_max_z = oldMaxZ;
            node.terminalized = false;
            ctx.occupied.add(oldPacked);
            ctx.warnings.add(reject + ":terminalize:" + safe(node.node_id));
            return false;
        }
        ctx.occupied.add(pack(node.x, node.z));
        return true;
    }

    private static int[] connectorWorldPos(CityC8Stages.PlacementNode node, ConnectorView connector) {
        int[] rotated = rotateLocal(connector.localX, connector.localZ, node.rotation);
        return new int[]{node.x + rotated[0], node.z + rotated[1]};
    }

    private static ConnectorView pickLinearConnector(TemplateMeta currentMeta, int rotation, Direction forward) {
        for (ConnectorView connector : resolveConnectorViews(currentMeta, rotation)) {
            if (connector != null && connector.direction == forward) return connector;
        }
        return null;
    }

    private static void applyConnectorMetadata(CityC8Stages.PlacementNode node, TemplateMeta meta) {
        if (node == null) return;
        node.outgoing_connector_ids.clear();
        for (ConnectorView connector : resolveConnectorViews(meta, node.rotation)) {
            if (connector == null || connector.id == null || connector.id.isBlank()) continue;
            if (node.incoming_child_connector_id != null && node.incoming_child_connector_id.equals(connector.id)) continue;
            node.outgoing_connector_ids.add(connector.id);
        }
    }

    private static void assignBuildOrder(List<CityC8Stages.PlacementNode> nodes) {
        if (nodes == null) return;
        for (int i = 0; i < nodes.size(); i++) {
            CityC8Stages.PlacementNode node = nodes.get(i);
            if (node != null) node.build_order = i;
        }
    }

    private static Direction rotate(Direction base, int rotation) {
        if (base == null) return null;
        int normalized = ((rotation % 360) + 360) % 360;
        int turns = normalized / 90;
        Direction current = base;
        for (int i = 0; i < turns; i++) {
            current = switch (current) {
                case NORTH -> Direction.EAST;
                case EAST -> Direction.SOUTH;
                case SOUTH -> Direction.WEST;
                case WEST -> Direction.NORTH;
            };
        }
        return current;
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
            if (removed != null) {
                ctx.occupied.remove(pack(removed.x, removed.z));
                if (removed.node_id != null) ctx.nodeById.remove(removed.node_id);
            }
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
            Catalog catalog = CityC35CatalogIO.loadCatalog(GSON, Catalog.class);
            if (catalog == null) {
                System.out.println("[C8] loadCatalog result=null");
                return null;
            }
            catalog.ok = catalog.ok && catalog.structures != null;
            System.out.println("[C8] loadCatalog ok=" + catalog.ok + " structure_count=" + (catalog.structures != null ? catalog.structures.size() : 0));
            return catalog;
        } catch (Exception ignored) {
            System.out.println("[C8] loadCatalog exception=" + ignored.getClass().getSimpleName() + " msg=" + ignored.getMessage());
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
        if (node == null || meta == null) return;
        FootprintBox box = computeFootprint(node, meta);
        node.footprint_min_x = box.minX;
        node.footprint_min_z = box.minZ;
        node.footprint_max_x = box.maxX;
        node.footprint_max_z = box.maxZ;
    }

    private static String firstPlacementRejectReason(SolveContext ctx, CityC8Stages.PlacementNode node, TemplateMeta meta) {
        if (ctx.occupied.contains(pack(node.x, node.z))) return "occupied_collision";
        if (!insideArea(ctx.area, node.x, node.z, meta, Direction.fromRotation(node.rotation))) return "out_of_bounds";
        if (intersectsExisting(ctx.nodes, node)) return "footprint_collision";
        if (!terrainPasses(ctx, node, meta)) return "terrain_rejected";
        return null;
    }

    private static String firstPlacementRejectReasonSkippingNode(SolveContext ctx, CityC8Stages.PlacementNode node, TemplateMeta meta) {
        if (ctx.occupied.contains(pack(node.x, node.z))) return "occupied_collision";
        if (!insideArea(ctx.area, node.x, node.z, meta, Direction.fromRotation(node.rotation))) return "out_of_bounds";
        if (intersectsExistingSkippingNode(ctx.nodes, node)) return "footprint_collision";
        if (!terrainPasses(ctx, node, meta)) return "terrain_rejected";
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

    private static boolean intersectsExistingSkippingNode(List<CityC8Stages.PlacementNode> nodes, CityC8Stages.PlacementNode candidate) {
        if (candidate == null) return false;
        for (CityC8Stages.PlacementNode existing : nodes) {
            if (existing == null || existing == candidate) continue;
            if (existing.node_id != null && existing.node_id.equals(candidate.node_id)) continue;
            boolean separated = candidate.footprint_max_x == null || candidate.footprint_min_x == null
                    || candidate.footprint_max_z == null || candidate.footprint_min_z == null
                    || existing.footprint_min_x == null || existing.footprint_min_z == null
                    || existing.footprint_max_x == null || existing.footprint_max_z == null
                    || candidate.footprint_max_x < existing.footprint_min_x
                    || candidate.footprint_min_x > existing.footprint_max_x
                    || candidate.footprint_max_z < existing.footprint_min_z
                    || candidate.footprint_min_z > existing.footprint_max_z;
            if (!separated) return true;
        }
        return false;
    }

    private static boolean terrainPasses(SolveContext ctx, CityC8Stages.PlacementNode node, TemplateMeta meta) {
        if (ctx.heightData == null || node == null || node.footprint_min_x == null || node.footprint_min_z == null
                || node.footprint_max_x == null || node.footprint_max_z == null) {
            return true;
        }
        List<ProbeWorldPoint> probes = resolveProbePoints(node, meta);
        if (probes.isEmpty()) return true;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (ProbeWorldPoint probe : probes) {
            int h = CityHeightResolver.resolveHeight(ctx.heightData, ctx.c2ScanData, probe.x, probe.z);
            min = Math.min(min, h);
            max = Math.max(max, h);
            if (meta.constraints != null && meta.constraints.avoid_water && !isLand(ctx.c2ScanData, probe.x, probe.z)) return false;
        }
        int allowedDelta = meta.constraints != null && meta.constraints.max_height_delta > 0
                ? meta.constraints.max_height_delta
                : ctx.maxTerrainDelta;
        if ((max - min) > allowedDelta) return false;

        if (meta.constraints != null && meta.constraints.max_slope > 0) {
            double span = Math.max(1.0, Math.hypot(
                    Math.max(1, node.footprint_max_x - node.footprint_min_x + 1),
                    Math.max(1, node.footprint_max_z - node.footprint_min_z + 1)
            ));
            double approxSlope = (max - min) / span;
            if (approxSlope > meta.constraints.max_slope) return false;
        }
        return true;
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

    private static FootprintBox computeFootprint(CityC8Stages.PlacementNode node, TemplateMeta meta) {
        if (meta.placement != null && meta.placement.footprint != null) {
            CityC35CatalogIO.Vec3i originOffset = meta.placement.origin_offset != null ? meta.placement.origin_offset : new CityC35CatalogIO.Vec3i();
            CityC35CatalogIO.Footprint footprint = meta.placement.footprint;
            int[][] corners = new int[][]{
                    {footprint.min_x - originOffset.x, footprint.min_z - originOffset.z},
                    {footprint.max_x - originOffset.x, footprint.min_z - originOffset.z},
                    {footprint.min_x - originOffset.x, footprint.max_z - originOffset.z},
                    {footprint.max_x - originOffset.x, footprint.max_z - originOffset.z}
            };
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (int[] corner : corners) {
                int[] rotated = rotateLocal(corner[0], corner[1], node.rotation);
                int worldX = node.x + rotated[0];
                int worldZ = node.z + rotated[1];
                minX = Math.min(minX, worldX);
                minZ = Math.min(minZ, worldZ);
                maxX = Math.max(maxX, worldX);
                maxZ = Math.max(maxZ, worldZ);
            }
            return new FootprintBox(minX, minZ, maxX, maxZ);
        }
        int normalized = ((node.rotation % 360) + 360) % 360;
        int length = meta.size != null ? meta.size.length : 1;
        int width = meta.size != null ? meta.size.width : 1;
        return switch (normalized) {
            case 90 -> new FootprintBox(node.x - length + 1, node.z, node.x, node.z + width - 1);
            case 180 -> new FootprintBox(node.x - width + 1, node.z - length + 1, node.x, node.z);
            case 270 -> new FootprintBox(node.x, node.z - width + 1, node.x + length - 1, node.z);
            default -> new FootprintBox(node.x, node.z, node.x + width - 1, node.z + length - 1);
        };
    }

    private static List<ProbeWorldPoint> resolveProbePoints(CityC8Stages.PlacementNode node, TemplateMeta meta) {
        List<ProbeWorldPoint> out = new ArrayList<>();
        if (meta != null && meta.placement != null && meta.placement.terrain_probe_points != null && !meta.placement.terrain_probe_points.isEmpty()) {
            CityC35CatalogIO.Vec3i originOffset = meta.placement.origin_offset != null ? meta.placement.origin_offset : new CityC35CatalogIO.Vec3i();
            for (CityC35CatalogIO.ProbePoint probe : meta.placement.terrain_probe_points) {
                if (probe == null) continue;
                int[] rotated = rotateLocal(probe.x - originOffset.x, probe.z - originOffset.z, node.rotation);
                out.add(new ProbeWorldPoint(node.x + rotated[0], node.z + rotated[1]));
            }
            return out;
        }
        out.add(new ProbeWorldPoint(node.x, node.z));
        if (node.footprint_min_x != null && node.footprint_min_z != null) out.add(new ProbeWorldPoint(node.footprint_min_x, node.footprint_min_z));
        if (node.footprint_max_x != null && node.footprint_max_z != null) out.add(new ProbeWorldPoint(node.footprint_max_x, node.footprint_max_z));
        return out;
    }

    private static boolean isLand(CityC2ScanBinaryIO.C2ScanData data, int worldX, int worldZ) {
        if (data == null || data.map == null || data.map.length == 0 || data.map[0] == null) return true;
        int step = Math.max(1, data.step);
        int ix = (int) Math.round((worldX - data.originX) / (double) step);
        int iz = (int) Math.round((worldZ - data.originZ) / (double) step);
        if (ix < 0 || iz < 0 || ix >= data.map.length || iz >= data.map[0].length) return true;
        return data.map[ix][iz] == null || data.map[ix][iz].isLand();
    }

    private static int[] rotateLocal(int localX, int localZ, int rotation) {
        int normalized = ((rotation % 360) + 360) % 360;
        return switch (normalized) {
            case 90 -> new int[]{-localZ, localX};
            case 180 -> new int[]{-localX, -localZ};
            case 270 -> new int[]{localZ, -localX};
            default -> new int[]{localX, localZ};
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
        final Map<String, CityC8Stages.PlacementNode> nodeById = new HashMap<>();
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

    private record CandidatePlacement(CityC8Stages.PlacementNode node, TemplateMeta meta, double weight) {
        private CandidatePlacement withWeight(double value) {
            return new CandidatePlacement(node, meta, value);
        }
    }

    private record ConnectorView(
            String id,
            int localX,
            int localZ,
            Direction localDirection,
            Direction direction,
            String socket,
            List<String> connectToPools,
            boolean required,
            int maxConnections
    ) {}

    private record ResolvedConnectorMatch(ConnectorView connector, int rotation) {}

    private record TerminalPlacement(String templateId, int x, int z, int rotation) {}

    private record ProbeWorldPoint(int x, int z) {}

    public static final class SolveResult {
        public boolean success = true;
        public List<CityC8Stages.PlacementNode> placements = new ArrayList<>();
        public List<String> errors = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
    }

    private static final class Catalog {
        String step;
        boolean ok;
        String city_id;
        int catalog_version;
        long generated_at_epoch_ms;
        List<String> function_enum_table = new ArrayList<>();
        List<TemplateMeta> structures = new ArrayList<>();
    }

    private static final class TemplateMeta extends CityC35CatalogIO.CatalogStructure {
        List<String> jigsawFacing = new ArrayList<>();

        private int primarySpan() {
            if (placement != null && placement.footprint != null) {
                int width = Math.max(1, placement.footprint.max_x - placement.footprint.min_x + 1);
                int depth = Math.max(1, placement.footprint.max_z - placement.footprint.min_z + 1);
                return Math.max(6, Math.max(width, depth) / 2);
            }
            return Math.max(6, Math.max(size.length, size.width) / 2);
        }
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
