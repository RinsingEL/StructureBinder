package com.rinsing.geomantia.platform;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.rinsing.geomantia.GeomantiaMod;
import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.application.refresh.GisRefreshService;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshPriority;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshResult;
import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegionStore;
import com.rinsing.geomantia.systems.gis.adapter.minecraft.MinecraftPriorAtlasSampler;
import com.rinsing.geomantia.systems.gis.testsupport.GisTestCase;
import com.rinsing.geomantia.systems.gis.testsupport.GisTestRunner;
import com.rinsing.geomantia.systems.realm_planning.RealmPlanningService;
import com.rinsing.geomantia.systems.realm_planning.WorldSurveyRunner;
import com.rinsing.geomantia.systems.realm_planning.WorldSurveySettingsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Path;

@Mod.EventBusSubscriber(modid = GeomantiaMod.MOD_ID)
public final class GisPlatformEvents {
    private static final int DEFAULT_REALM_CELL_STEP_BLOCKS = WorldSurveyRunner.DEFAULT_CELL_STEP_BLOCKS;

    private GisPlatformEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("geomantia")
                .then(Commands.literal("gis")
                        .then(Commands.literal("refresh")
                                .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> refresh(ctx, SampleMode.PRIOR,
                                                GisSampleConfig.defaults().cellStepBlocks()))
                                        .then(Commands.argument("sampleMode", StringArgumentType.word())
                                                .executes(ctx -> refresh(ctx,
                                                        SampleMode.fromContractName(StringArgumentType.getString(ctx,
                                                                "sampleMode")),
                                                        GisSampleConfig.defaults().cellStepBlocks()))
                                                .then(Commands.argument("cellStepBlocks", IntegerArgumentType.integer(
                                                                GisSampleConfig.MIN_CELL_STEP_BLOCKS,
                                                                GisSampleConfig.MAX_CELL_STEP_BLOCKS))
                                                        .executes(ctx -> refresh(ctx,
                                                                SampleMode.fromContractName(
                                                                        StringArgumentType.getString(ctx,
                                                                                "sampleMode")),
                                                                IntegerArgumentType.getInteger(ctx,
                                                                        "cellStepBlocks")))))))
                        .then(Commands.literal("test_run")
                                .then(Commands.argument("caseId", StringArgumentType.word())
                                        .executes(GisPlatformEvents::testRun))))
                .then(Commands.literal("realm")
                        .then(Commands.literal("status")
                                .executes(GisPlatformEvents::realmStatus))
                        .then(Commands.literal("acceptance")
                                .executes(ctx -> realmAcceptance(ctx, DEFAULT_REALM_CELL_STEP_BLOCKS)))));
    }

    private static int refresh(CommandContext<CommandSourceStack> ctx, SampleMode sampleMode, int cellStepBlocks) {
        try {
            CommandSourceStack source = ctx.getSource();
            ServerLevel level = source.getLevel();
            int radiusChunks = IntegerArgumentType.getInteger(ctx, "radiusChunks");
            Path debugRoot = debugRoot(source.getServer());
            GisSampleConfig sampleConfig = GisSampleConfig.defaults().withCellStepBlocks(cellStepBlocks);
            GisRefreshService service = new GisRefreshService(sampleConfig, GisClassifierConfig.defaults(),
                    new AtlasRegionStore(sampleConfig), new MinecraftPriorAtlasSampler(level));
            BlockPos center = BlockPos.containing(source.getPosition());
            RefreshResult result = service.refresh(level.dimension().location().toString(),
                    center.getX(), center.getZ(), radiusChunks, sampleMode,
                    RefreshPriority.DEBUG, debugRoot);
            source.sendSuccess(() -> Component.literal("GIS refresh completed: "
                    + result.runDirectory().toAbsolutePath()), false);
            return result.job().status().contractName().equals("completed") ? 1 : 0;
        } catch (Exception ex) {
            ctx.getSource().sendFailure(Component.literal("GIS refresh failed: " + ex.getMessage()));
            return 0;
        }
    }

    private static int testRun(CommandContext<CommandSourceStack> ctx) {
        try {
            String caseId = StringArgumentType.getString(ctx, "caseId");
            GisTestRunner runner = new GisTestRunner(GisSampleConfig.defaults(), GisClassifierConfig.defaults());
            var report = runner.runCase(GisTestCase.byId(caseId), debugRoot(ctx.getSource().getServer()));
            ctx.getSource().sendSuccess(() -> Component.literal("GIS test " + caseId + " passed="
                    + report.passed() + " runId=" + report.runId()), false);
            return report.passed() ? 1 : 0;
        } catch (Exception ex) {
            ctx.getSource().sendFailure(Component.literal("GIS test failed: " + ex.getMessage()));
            return 0;
        }
    }

    private static int realmStatus(CommandContext<CommandSourceStack> ctx) {
        try {
            var response = RealmPlanningServices.forServer(ctx.getSource().getServer()).status();
            ctx.getSource().sendSuccess(() -> Component.literal("Realm planning ready, latestRun="
                    + response.get("runId").getAsString()), false);
            return 1;
        } catch (Exception ex) {
            ctx.getSource().sendFailure(Component.literal("Realm status failed: " + ex.getMessage()));
            return 0;
        }
    }

    private static int realmAcceptance(CommandContext<CommandSourceStack> ctx, int cellStepBlocks) {
        try {
            CommandSourceStack source = ctx.getSource();
            ServerLevel level = source.getLevel();
            Path realmRoot = realmDebugRoot(source.getServer());
            int planningRadiusBlocks = WorldSurveySettingsConfig.loadOrCreate(
                    worldSurveySettingsConfigPath(source.getServer())).planningRadiusBlocks();
            WorldSurveyRunner.Config config = new WorldSurveyRunner.Config(
                    "",
                    level.dimension().location().toString(),
                    Long.toString(level.getSeed()),
                    level.getWorldBorder().getSize(),
                    0,
                    0,
                    planningRadiusBlocks,
                    cellStepBlocks,
                    RealmPlanningService.DEFAULT_MICRO_SAMPLE_STRIDE_BLOCKS,
                    WorldSurveyRunner.DEFAULT_LOCAL_SLOPE_RADIUS_BLOCKS,
                    SampleMode.PRIOR,
                    WorldSurveyRunner.ResumePolicy.USE_CACHE
            );
            ServerPlayer player = source.getEntity() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
            var survey = new WorldSurveyRunner(realmRoot, GisClassifierConfig.defaults()).run(config,
                    new MinecraftPriorAtlasSampler(level), WorldSurveyChatProgress.forPlayer(player));
            var response = RealmPlanningServices.forServer(source.getServer()).runAcceptance(survey, 3, null, true);
            boolean passed = response.get("passed").getAsBoolean();
            String runId = response.get("runId").getAsString();
            String runDirectory = response.getAsJsonObject("artifacts").get("runDirectory").getAsString();
            source.sendSuccess(() -> Component.literal("Realm W/T acceptance passed=" + passed
                    + " runId=" + runId + " dir=" + runDirectory), false);
            return passed ? 1 : 0;
        } catch (Exception ex) {
            ctx.getSource().sendFailure(Component.literal("Realm W/T acceptance failed: " + ex.getMessage()));
            return 0;
        }
    }

    private static Path debugRoot(MinecraftServer server) {
        return server.getServerDirectory().toPath().resolve("gis_debug");
    }

    private static Path realmDebugRoot(MinecraftServer server) {
        return server.getServerDirectory().toPath().resolve("realm_debug");
    }

    private static Path worldSurveySettingsConfigPath(MinecraftServer server) {
        return server.getServerDirectory().toPath().resolve("config").resolve("geomantia")
                .resolve("world_survey.json");
    }
}
