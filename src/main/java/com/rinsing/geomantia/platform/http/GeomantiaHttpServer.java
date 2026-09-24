package com.rinsing.geomantia.platform.http;

import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.GeomantiaMod;
import com.rinsing.geomantia.platform.WorldScopedPlanningPaths;
import com.rinsing.geomantia.systems.provider.application.PlanningHost;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Mod.EventBusSubscriber(modid = GeomantiaMod.MOD_ID)
public final class GeomantiaHttpServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DEFAULT_PORT = 5000;
    private static HttpServer httpServer;
    private static ExecutorService httpExecutor;
    private static RealmPlanningHttpController activeRealmController;

    private GeomantiaHttpServer() {
    }

    public static synchronized String retryCityFromMap(net.minecraft.server.level.ServerPlayer player,
                                                       String runId, String cityId) {
        if (!player.hasPermissions(2) && !player.server.isSingleplayerOwner(player.getGameProfile()))
            return "no_permission";
        if (activeRealmController == null) return "unavailable";
        try {
            return activeRealmController.retryCityFromMap(player.server, runId, cityId) ? "submitted" : "state_changed";
        } catch (IllegalArgumentException exception) {
            return "state_changed";
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Map city retry failed: {}", exception.getClass().getSimpleName());
            return "failed";
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        start(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        stop();
    }

    private static synchronized void start(MinecraftServer minecraftServer) {
        if (httpServer != null) {
            return;
        }
        int port = Integer.getInteger("geomantia.apiPort", DEFAULT_PORT);
        HttpServer createdServer = null;
        ExecutorService createdExecutor = null;
        RealmPlanningHttpController createdRealmController = null;
        try {
            var extensions = com.rinsing.geomantia.platform.PlanningExtensionRegistration.collect(minecraftServer);
            createdServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            PlanningSessionHttpController planningController = new PlanningSessionHttpController(minecraftServer);
            createdServer.createContext("/planning/", planningController::handle);
            GisHttpController controller = new GisHttpController(minecraftServer);
            createdRealmController = new RealmPlanningHttpController(minecraftServer);
            RealmPlanningHttpController realmController = createdRealmController;
            createdServer.createContext("/gis/status", controller::handleStatus).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/gis/refresh", controller::handleRefresh).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/gis/test_run", controller::handleTestRun).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/gis/chunk_generation_benchmark/start",
                    controller::handleChunkGenerationBenchmarkStart).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/gis/chunk_generation_benchmark/status",
                    controller::handleChunkGenerationBenchmarkStatus).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/status", realmController::handleStatus).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/w/refresh", realmController::handleWRefresh).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/t1/prepare", realmController::handleT1Prepare).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/t2/select_coordinate", realmController::handleT2SelectCoordinate).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/t3/expand", realmController::handleT3Expand).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/t4/build_registry", realmController::handleT4BuildRegistry).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/t4/patch_planning/create", realmController::handleT4PatchPlanningCreate).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/t4/patch_planning/select_capital", realmController::handleT4PatchPlanningSelectCapital).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/t4/patch_planning/add_city", realmController::handleT4PatchPlanningAddCity).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/t4/patch_planning/finalize", realmController::handleT4PatchPlanningFinalize).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/patch_explorer/open", realmController::handlePatchExplorerOpen).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/patch_explorer/show_candidates", realmController::handlePatchExplorerShowCandidates).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/patch_explorer/select_candidate", realmController::handlePatchExplorerSelectCandidate).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/acceptance/run", realmController::handleAcceptance).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/tag_audit", realmController::handleTagAudit).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/debug/command", realmController::handleDebugCommand).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/plan_d2", realmController::handleCityPlanD2).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/design_queue/refresh",
                    realmController::handleCityDesignQueueRefresh).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/design_queue/status",
                    realmController::handleCityDesignQueueStatus).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/plan_d3", realmController::handleCityPlanD3).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/review_d3_site", realmController::handleCityReviewD3Site).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/prepare_d4_blueprint_context",
                    realmController::handleCityPrepareD4BlueprintContext).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/submit_d4_blueprint",
                    realmController::handleCitySubmitD4Blueprint).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/post_d4_auto_compile_status",
                    realmController::handleCityPostD4AutoCompileStatus).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/post_d4_auto_compile_retry",
                    realmController::handleCityPostD4AutoCompileRetry).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/compile_d4_blueprint",
                    realmController::handleCityCompileD4Blueprint).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/plan_d4_candidates", realmController::handleCityPlanD4Candidates).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/plan_d4_array_candidates", realmController::handleCityPlanD4ArrayCandidates).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/create_d4_design_loop_state", realmController::handleCityCreateD4DesignLoopState).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/read_d4_design_loop_state", realmController::handleCityReadD4DesignLoopState).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/append_d4_design_loop_round", realmController::handleCityAppendD4DesignLoopRound).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/write_d4_design_loop_state", realmController::handleCityWriteD4DesignLoopState).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/create_d4_array_layout_loop", realmController::handleCityCreateD4ArrayLayoutLoop).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/execute_d4_array_layout_item", realmController::handleCityExecuteD4ArrayLayoutItem).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/query_d4_array_expansion_space", realmController::handleCityQueryD4ArrayExpansionSpace).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/plan_d4_array_expansion_candidates", realmController::handleCityPlanD4ArrayExpansionCandidates).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/select_d4_array_expansion_candidate", realmController::handleCitySelectD4ArrayExpansionCandidate).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/finalize_d4_array_layout_loop", realmController::handleCityFinalizeD4ArrayLayoutLoop).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/query_structure_catalog", realmController::handleCityQueryStructureCatalog).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/query_template_metadata", realmController::handleCityQueryTemplateMetadata).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/plan_d4_structure_cluster_groups", realmController::handleCityPlanD4StructureClusterGroups).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/select_d4_candidates", realmController::handleCitySelectD4Candidates).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/select_d4_structure_cluster_group", realmController::handleCitySelectD4StructureClusterGroup).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/create_d4_candidate_session", realmController::handleCityCreateD4CandidateSession).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/plan_d4_next_candidates", realmController::handleCityPlanD4NextCandidates).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/select_d4_candidate", realmController::handleCitySelectD4Candidate).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/finalize_d4_candidate_session", realmController::handleCityFinalizeD4CandidateSession).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/plan_d4", realmController::handleCityPlanD4).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/plan_d5", realmController::handleCityPlanD5).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/execute_d5", realmController::handleCityExecuteD5).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/plan_d6", realmController::handleCityPlanD6).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/plan_land_use", realmController::handleCityPlanLandUse).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/execute_d7", realmController::handleCityExecuteD7).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/query_worldgen_observations",
                    realmController::handleCityQueryWorldgenObservations).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/plan_city_walls", realmController::handleCityPlanCityWalls).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/execute_city_walls", realmController::handleCityExecuteCityWalls).getFilters().add(planningController.ownershipFilter());
            createdServer.createContext("/realm/city/run_workflow", realmController::handleCityRunWorkflow).getFilters().add(planningController.ownershipFilter());
            createdExecutor = Executors.newFixedThreadPool(8, runnable -> {
                Thread thread = new Thread(runnable);
                thread.setDaemon(true);
                thread.setName("Geomantia-API");
                return thread;
            });
            createdServer.setExecutor(createdExecutor);
            createdServer.start();
            httpServer = createdServer;
            httpExecutor = createdExecutor;
            activeRealmController = realmController;
            PlanningHost.start(
                    minecraftServer.getServerDirectory().toPath(),
                    WorldScopedPlanningPaths.realmDebugRoot(minecraftServer), port,
                    minecraftServer.overworld().getSeed(), extensions);
            LOGGER.info("Geomantia GIS API server started on 127.0.0.1:{}.", port);
        } catch (IOException | RuntimeException ex) {
            LOGGER.error("Failed to start Geomantia GIS API server.", ex);
            if (createdServer != null) {
                createdServer.stop(0);
            }
            shutdownExecutor(createdExecutor);
            if (createdRealmController != null) {
                createdRealmController.close();
            }
            httpServer = null;
            httpExecutor = null;
            activeRealmController = null;
        }
    }

    private static synchronized void stop() {
        // Signal world-bound workers before waiting for any provider/runtime shutdown.
        if (activeRealmController != null) {
            activeRealmController.close();
        }
        PlanningHost.stop();
        HttpServer server = httpServer;
        ExecutorService executor = httpExecutor;
        RealmPlanningHttpController realmController = activeRealmController;
        httpServer = null;
        httpExecutor = null;
        activeRealmController = null;
        if (server == null && executor == null && realmController == null) {
            return;
        }
        if (server != null) {
            server.stop(0);
        }
        shutdownExecutor(executor);
        LOGGER.info("Geomantia GIS API server stopped.");
    }

    private static void shutdownExecutor(ExecutorService executor) {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
