package com.user.terra_script.world.city.stage.c8;

import com.user.terra_script.world.city.stage.CityC35CatalogIO;
import com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityVanillaJigsawAdapterServiceTest {
    @Test
    void syntheticConnectorIdsAreRejectedByContract() {
        assertTrue(CityVanillaJigsawAdapterService.isSyntheticConnectorId("next_start_g_market_02_south_1"));
        assertFalse(CityVanillaJigsawAdapterService.isSyntheticConnectorId("jigsaw_south_4_0_8"));
    }

    @Test
    void selectedRotationIsRecordedButIgnoredByVanillaAdapter() {
        CityVanillaJigsawAdapterService.SolveResult result = CityVanillaJigsawAdapterService.solve(
                null,
                "city_demo",
                null,
                List.of(),
                null,
                "jigsaw_south_4_0_8",
                "minecraft:village/taiga/houses/taiga_butcher_shop_1",
                "south",
                180
        );

        assertEquals(180, result.selected_rotation);
        assertTrue(result.warnings.contains("selected_rotation_ignored_by_vanilla_jigsaw"));
        assertEquals("missing_server_level", result.reject_reason);
    }

    @Test
    void runtimeConnectorIdKeepsRealFrontAndTemplateLocalPosition() {
        assertEquals(
                "jigsaw_north_7_1_0",
                CityVanillaJigsawAdapterService.runtimeConnectorId(Direction.NORTH, 7, 1, 0)
        );
        assertFalse(CityVanillaJigsawAdapterService.isSyntheticConnectorId("jigsaw_up_6_1_8"));
    }

    @Test
    void frontHelpersSeparateHorizontalAndVerticalJigsaws() {
        assertTrue(CityVanillaJigsawAdapterService.isHorizontalFront("north"));
        assertFalse(CityVanillaJigsawAdapterService.isHorizontalFront("up"));
        assertTrue(CityVanillaJigsawAdapterService.isVerticalFront("up"));
        assertTrue(CityVanillaJigsawAdapterService.isVerticalFront(Direction.DOWN));
        assertFalse(CityVanillaJigsawAdapterService.isVerticalFront(Direction.WEST));
    }

    @Test
    void unrotateLocalRestoresTemplateCoordinatesFromPlacedRotation() {
        assertArrayEquals(new int[]{7, 0}, CityVanillaJigsawAdapterService.unrotateLocal(0, 7, Rotation.CLOCKWISE_90));
        assertArrayEquals(new int[]{7, 0}, CityVanillaJigsawAdapterService.unrotateLocal(-7, 0, Rotation.CLOCKWISE_180));
        assertArrayEquals(new int[]{7, 0}, CityVanillaJigsawAdapterService.unrotateLocal(0, -7, Rotation.COUNTERCLOCKWISE_90));
    }

    @Test
    void manualSummarySeparatesFailureStages() {
        assertEquals(
                "target/name",
                CityVanillaJigsawAdapterService.summarizeManualCandidates(
                        List.of(manualCandidate(false, false, false, false, false)),
                        false,
                        false
                ).first_blocker_stage
        );
        assertEquals(
                "attach",
                CityVanillaJigsawAdapterService.summarizeManualCandidates(
                        List.of(manualCandidate(true, false, false, false, false)),
                        false,
                        false
                ).first_blocker_stage
        );
        assertEquals(
                "bounds/area",
                CityVanillaJigsawAdapterService.summarizeManualCandidates(
                        List.of(manualCandidate(true, true, false, false, false)),
                        false,
                        false
                ).first_blocker_stage
        );
        assertEquals(
                "terrain",
                CityVanillaJigsawAdapterService.summarizeManualCandidates(
                        List.of(manualCandidate(true, true, true, false, true)),
                        false,
                        false
                ).first_blocker_stage
        );
        assertEquals(
                "vanilla_empty_stub",
                CityVanillaJigsawAdapterService.summarizeManualCandidates(
                        List.of(manualCandidate(true, true, true, false, false)),
                        false,
                        false
                ).first_blocker_stage
        );
    }

    @Test
    void verticalPlaceholderReturnsDedicatedPendingReason() {
        CityVanillaJigsawAdapterService.SolveResult result = CityVerticalJigsawPlaceholderService.pending(
                new CityVanillaJigsawAdapterService.SolveResult(),
                null
        );

        assertFalse(result.ok);
        assertEquals("vertical_jigsaw_solver_pending", result.reject_reason);
        assertTrue(result.warnings.contains("vertical_jigsaw_routed_to_placeholder_solver"));
        assertEquals("vertical_solver_pending", result.debug.first_blocker_stage);
        assertEquals("vertical_solver_pending", result.debug.manual_attach_summary.first_blocker_stage);
    }

    @Test
    void templatesWithoutExplicitProbePointsNoLongerUseBoundsCornerFallback() throws Exception {
        CityC35CatalogIO.CatalogStructure meta = new CityC35CatalogIO.CatalogStructure();
        meta.structure_id = "test:no_probe";
        meta.placement = new CityC35CatalogIO.PlacementSpec();
        meta.placement.terrain_probe_points.clear();

        Method method = CityVanillaJigsawAdapterService.class.getDeclaredMethod(
                "resolveProbePoints",
                CityC35CatalogIO.CatalogStructure.class,
                BlockPos.class,
                Rotation.class,
                BoundingBox.class
        );
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Object> probes = (List<Object>) method.invoke(
                null,
                meta,
                new BlockPos(10, 64, 20),
                Rotation.NONE,
                new BoundingBox(10, 64, 20, 18, 70, 30)
        );

        assertNotNull(probes);
        assertTrue(probes.isEmpty());
    }

    @Test
    void explicitTerrainProbeChecksAreTemporarilyDisabled() throws Exception {
        CityC35CatalogIO.CatalogStructure meta = new CityC35CatalogIO.CatalogStructure();
        meta.structure_id = "test:explicit_probe";
        meta.placement = new CityC35CatalogIO.PlacementSpec();
        meta.placement.terrain_probe_points.add(new CityC35CatalogIO.ProbePoint());
        meta.placement.terrain_probe_points.get(0).x = 0;
        meta.placement.terrain_probe_points.get(0).z = 0;
        meta.placement.terrain_probe_points.add(new CityC35CatalogIO.ProbePoint());
        meta.placement.terrain_probe_points.get(1).x = 8;
        meta.placement.terrain_probe_points.get(1).z = 10;
        meta.constraints.max_height_delta = 1;
        meta.constraints.max_slope = 0.05;

        int[][] heights = new int[16][16];
        heights[0][0] = 64;
        heights[8][10] = 80;
        CityStage1BinaryIO.HeightData heightData = new CityStage1BinaryIO.HeightData(0, 0, 16, 16, heights);

        Method method = CityVanillaJigsawAdapterService.class.getDeclaredMethod(
                "terrainRejected",
                CityStage1BinaryIO.HeightData.class,
                Class.forName("com.user.terra_script.world.city.stage.c2.CityC2ScanBinaryIO$C2ScanData"),
                CityC35CatalogIO.CatalogStructure.class,
                BlockPos.class,
                Rotation.class,
                BoundingBox.class
        );
        method.setAccessible(true);

        boolean rejected = (boolean) method.invoke(
                null,
                heightData,
                null,
                meta,
                new BlockPos(0, 64, 0),
                Rotation.NONE,
                new BoundingBox(0, 64, 0, 8, 72, 10)
        );

        assertFalse(rejected);
    }

    private static CityVanillaJigsawAdapterService.ManualChildConnectorCandidate manualCandidate(
            boolean matchesTarget,
            boolean canAttach,
            boolean insideArea,
            boolean footprintCollision,
            boolean terrainRejected
    ) {
        CityVanillaJigsawAdapterService.ManualChildConnectorCandidate candidate = new CityVanillaJigsawAdapterService.ManualChildConnectorCandidate();
        candidate.matches_parent_target = matchesTarget;
        candidate.can_attach_to_parent = canAttach;
        candidate.inside_area = insideArea;
        candidate.footprint_collision = footprintCollision;
        candidate.terrain_rejected = terrainRejected;
        candidate.viable = matchesTarget && canAttach && insideArea && !footprintCollision && !terrainRejected;
        return candidate;
    }
}
