package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.domain.model.BuildOperationPlan;
import com.rinsing.geomantia.systems.city.domain.model.WorldMutationReport;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.forge.ForgeAdapter;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class WorldEditMutationBackend implements WorldMutationBackend {
    @Override
    public WorldMutationReport execute(ServerLevel level, BuildOperationPlan plan, Path serverRoot) {
        List<WorldMutationReport.OperationResult> results = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int changedBlocks = 0;
        int executed = 0;
        int skipped = 0;
        int failed = 0;

        if (level == null) {
            failures.add("ServerLevel is required.");
            return report(plan.cityId(), false, changedBlocks, executed, skipped, 1, results, warnings, failures);
        }

        World world = ForgeAdapter.adapt(level);
        Set<BlockPos> protectedRoadPositions = new LinkedHashSet<>();
        try (EditSession editSession = WorldEdit.getInstance()
                .newEditSessionBuilder()
                .world(world)
                .build()) {
            for (BuildOperationPlan.Operation operation : plan.operations()) {
                try {
                    OperationOutcome outcome = executeOperation(level, editSession, plan, operation, serverRoot,
                            protectedRoadPositions);
                    changedBlocks += outcome.changedBlocks();
                    switch (outcome.status()) {
                        case "executed" -> executed++;
                        case "skipped" -> skipped++;
                        default -> failed++;
                    }
                    if (outcome.warning() != null && !outcome.warning().isBlank()) {
                        warnings.add(outcome.warning());
                    }
                    if (outcome.failure() != null && !outcome.failure().isBlank()) {
                        failures.add(outcome.failure());
                    }
                    results.add(new WorldMutationReport.OperationResult(
                            operation.operationId(), outcome.status(), outcome.changedBlocks(), outcome.reason()));
                } catch (Exception ex) {
                    failed++;
                    failures.add(operation.operationId() + ": " + ex.getMessage());
                    results.add(new WorldMutationReport.OperationResult(
                            operation.operationId(), "failed", 0, ex.getMessage()));
                }
            }
            editSession.flushSession();
        }
        return report(plan.cityId(), true, changedBlocks, executed, skipped, failed, results, warnings, failures);
    }

    private OperationOutcome executeOperation(ServerLevel level, EditSession editSession,
                                              BuildOperationPlan plan,
                                              BuildOperationPlan.Operation operation,
                                              Path serverRoot,
                                              Set<BlockPos> protectedRoadPositions) throws Exception {
        return switch (operation.operationType()) {
            case "clearVegetation" -> clearVegetation(level, editSession, operation);
            case "surfaceFill", "surfaceReplace", "carveBuffer" ->
                    surface(level, editSession, operation, protectedRoadPositions);
            case "pasteTemplate" -> pasteTemplate(level, editSession, plan, operation, serverRoot);
            default -> OperationOutcome.skipped("Unsupported operationType: " + operation.operationType());
        };
    }

    private OperationOutcome clearVegetation(ServerLevel level, EditSession editSession,
                                             BuildOperationPlan.Operation operation) throws Exception {
        CorridorSample samples = corridorPositions(level, operation.polyline(), operation.widthBlocks(), true);
        Set<BlockPos> positions = samples.positions();
        int changed = 0;
        for (BlockPos surface : positions) {
            int minY = Math.max(level.getMinBuildHeight(), surface.getY());
            int maxY = Math.min(level.getMaxBuildHeight() - 1, surface.getY() + 24);
            for (int y = maxY; y >= minY; y--) {
                BlockPos pos = new BlockPos(surface.getX(), y, surface.getZ());
                net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                if (!softClearable(state)) {
                    continue;
                }
                if (editSession.setBlock(BlockVector3.at(pos.getX(), pos.getY(), pos.getZ()), BlockTypes.AIR.getDefaultState())) {
                    changed++;
                }
            }
        }
        return samples.skippedWaterColumns() > 0
                ? OperationOutcome.executedWithWarning(changed, "Cleared soft vegetation.",
                "Skipped " + samples.skippedWaterColumns() + " water columns for " + operation.operationId() + ".")
                : OperationOutcome.executed(changed, "Cleared soft vegetation.");
    }

    private OperationOutcome surface(ServerLevel level, EditSession editSession,
                                     BuildOperationPlan.Operation operation,
                                     Set<BlockPos> protectedRoadPositions) throws Exception {
        Optional<BlockState> material = material(operation.material());
        if (material.isEmpty()) {
            return OperationOutcome.failed("Unknown material: " + operation.material());
        }
        boolean skipWaterColumns = operation.operationType().equals("surfaceFill");
        CorridorSample samples = corridorPositions(level, operation.polyline(), operation.widthBlocks(), skipWaterColumns);
        Set<BlockPos> positions = samples.positions();
        int changed = 0;
        for (BlockPos pos : positions) {
            boolean preserveRoad = operation.operationType().equals("surfaceReplace")
                    || operation.operationType().equals("carveBuffer");
            if (preserveRoad && (protectedRoadPositions.contains(pos) || isRoadSurface(level.getBlockState(pos)))) {
                continue;
            }
            if (editSession.setBlock(BlockVector3.at(pos.getX(), pos.getY(), pos.getZ()), material.get())) {
                changed++;
            }
        }
        if (operation.operationType().equals("surfaceFill")) {
            protectedRoadPositions.addAll(positions);
        }
        return samples.skippedWaterColumns() > 0
                ? OperationOutcome.executedWithWarning(changed, "Applied surface material " + operation.material() + ".",
                "Skipped " + samples.skippedWaterColumns() + " water columns for " + operation.operationId() + ".")
                : OperationOutcome.executed(changed, "Applied surface material " + operation.material() + ".");
    }

    private OperationOutcome pasteTemplate(ServerLevel level, EditSession editSession, BuildOperationPlan plan,
                                           BuildOperationPlan.Operation operation,
                                           Path serverRoot) throws Exception {
        Path template = templatePath(serverRoot, plan.templateDirectory(), operation.templateId());
        if (template == null || !Files.exists(template)) {
            return OperationOutcome.skipped("Missing template: " + operation.templateId());
        }
        ClipboardFormat format = ClipboardFormats.findByFile(template.toFile());
        if (format == null) {
            return OperationOutcome.failed("Unsupported template format: " + template.getFileName());
        }
        try (InputStream input = Files.newInputStream(template);
             ClipboardReader reader = format.getReader(input)) {
            Clipboard clipboard = reader.read();
            int pasteY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    operation.anchorBlock().x(), operation.anchorBlock().z());
            Operations.complete(new ClipboardHolder(clipboard)
                    .createPaste(editSession)
                    .to(BlockVector3.at(operation.anchorBlock().x(), pasteY, operation.anchorBlock().z()))
                    .ignoreAirBlocks(false)
                    .build());
            return OperationOutcome.executed(clipboard.getRegion().getArea(), "Pasted template " + operation.templateId() + ".");
        } catch (IOException ex) {
            return OperationOutcome.failed("Could not read template " + operation.templateId() + ": " + ex.getMessage());
        }
    }

    private CorridorSample corridorPositions(ServerLevel level, List<BlockPoint> polyline, int width,
                                             boolean skipWaterColumns) {
        Set<BlockPos> result = new LinkedHashSet<>();
        if (polyline.size() < 2) {
            return new CorridorSample(result, 0);
        }
        int radius = Math.max(0, width / 2);
        int skippedWaterColumns = 0;
        for (int i = 1; i < polyline.size(); i++) {
            BlockPoint a = polyline.get(i - 1);
            BlockPoint b = polyline.get(i);
            int steps = Math.max(Math.abs(b.x() - a.x()), Math.abs(b.z() - a.z()));
            steps = Math.max(1, steps);
            for (int s = 0; s <= steps; s++) {
                int x = a.x() + Math.round((b.x() - a.x()) * (s / (float) steps));
                int z = a.z() + Math.round((b.z() - a.z()) * (s / (float) steps));
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (dx * dx + dz * dz > radius * radius + radius) {
                            continue;
                        }
                        int px = x + dx;
                        int pz = z + dz;
                        if (skipWaterColumns && isWaterColumn(level, px, pz)) {
                            skippedWaterColumns++;
                            continue;
                        }
                        findTerrainSurface(level, px, pz).ifPresent(y -> result.add(new BlockPos(px, y, pz)));
                    }
                }
            }
        }
        return new CorridorSample(result, skippedWaterColumns);
    }

    private boolean isWaterColumn(ServerLevel level, int x, int z) {
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
        surfaceY = Math.max(level.getMinBuildHeight(), Math.min(level.getMaxBuildHeight() - 1, surfaceY));
        for (int y = surfaceY; y >= Math.max(level.getMinBuildHeight(), surfaceY - 4); y--) {
            if (level.getFluidState(new BlockPos(x, y, z)).is(net.minecraft.tags.FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    private Optional<Integer> findTerrainSurface(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
        y = Math.max(level.getMinBuildHeight(), Math.min(level.getMaxBuildHeight() - 1, y));
        for (int scanY = y; scanY >= level.getMinBuildHeight(); scanY--) {
            BlockPos pos = new BlockPos(x, scanY, z);
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            if (state.isAir() || vegetationClearable(state)) {
                continue;
            }
            if (!state.getFluidState().isEmpty()) {
                continue;
            }
            return Optional.of(scanY);
        }
        return Optional.empty();
    }

    private Optional<BlockState> material(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            return Optional.empty();
        }
        Block block = ForgeRegistries.BLOCKS.getValue(location);
        if (block == null || block == Blocks.AIR) {
            return Optional.empty();
        }
        return Optional.of(ForgeAdapter.adapt(block.defaultBlockState()));
    }

    private boolean softClearable(net.minecraft.world.level.block.state.BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        if (protectedBlock(state)) {
            return false;
        }
        return vegetationClearable(state)
                || state.canBeReplaced();
    }

    private boolean vegetationClearable(net.minecraft.world.level.block.state.BlockState state) {
        if (state == null || state.isAir() || protectedBlock(state)) {
            return false;
        }
        return state.is(BlockTags.LEAVES)
                || state.is(BlockTags.LOGS)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.WART_BLOCKS)
                || state.is(BlockTags.MUSHROOM_GROW_BLOCK)
                || state.getBlock() instanceof LeavesBlock
                || state.getBlock() instanceof VineBlock
                || state.getBlock() instanceof BushBlock
                || state.getBlock() instanceof DoublePlantBlock
                || state.getBlock() instanceof BambooStalkBlock
                || state.getBlock() instanceof SugarCaneBlock;
    }

    private boolean protectedBlock(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.BEDROCK)
                || state.is(Blocks.BARRIER)
                || state.is(Blocks.COMMAND_BLOCK)
                || state.is(Blocks.CHAIN_COMMAND_BLOCK)
                || state.is(Blocks.REPEATING_COMMAND_BLOCK)
                || state.is(Blocks.STRUCTURE_BLOCK)
                || state.is(Blocks.END_PORTAL_FRAME);
    }

    private boolean isRoadSurface(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.GRAVEL) || state.is(Blocks.COARSE_DIRT);
    }

    private Path templatePath(Path serverRoot, String templateDirectory, String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return null;
        }
        Path root = serverRoot.resolve(templateDirectory == null ? "geomantia_templates/d5" : templateDirectory);
        Path schem = root.resolve(templateId + ".schem");
        if (Files.exists(schem)) {
            return schem;
        }
        Path schematic = root.resolve(templateId + ".schematic");
        if (Files.exists(schematic)) {
            return schematic;
        }
        return schem;
    }

    private WorldMutationReport report(String cityId, boolean executed, int changedBlocks,
                                       int executedOperations, int skippedOperations, int failedOperations,
                                       List<WorldMutationReport.OperationResult> results,
                                       List<String> warnings, List<String> failures) {
        return new WorldMutationReport(
                WorldMutationReport.CURRENT_SCHEMA_VERSION,
                cityId,
                "worldedit",
                executed,
                false,
                changedBlocks,
                executedOperations,
                skippedOperations,
                failedOperations,
                results,
                warnings,
                failures);
    }

    private record CorridorSample(Set<BlockPos> positions, int skippedWaterColumns) {
    }

    private record OperationOutcome(String status, int changedBlocks, String reason, String warning, String failure) {
        static OperationOutcome executed(int changedBlocks, String reason) {
            return new OperationOutcome("executed", changedBlocks, reason, "", "");
        }

        static OperationOutcome executedWithWarning(int changedBlocks, String reason, String warning) {
            return new OperationOutcome("executed", changedBlocks, reason, warning, "");
        }

        static OperationOutcome skipped(String reason) {
            return new OperationOutcome("skipped", 0, reason, reason, "");
        }

        static OperationOutcome failed(String reason) {
            return new OperationOutcome("failed", 0, reason, "", reason);
        }
    }
}
