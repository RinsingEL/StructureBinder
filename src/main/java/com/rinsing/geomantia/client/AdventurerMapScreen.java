package com.rinsing.geomantia.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.rinsing.geomantia.systems.realm_planning.application.map.AdventurerMapSnapshot;
import com.rinsing.geomantia.systems.realm_planning.application.map.AdventurerMapSnapshot.CityNode;
import com.rinsing.geomantia.systems.realm_planning.application.map.AdventurerMapSnapshot.CoarseMap;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

public final class AdventurerMapScreen extends Screen {
    private static final int PANEL_BACKGROUND = 0xE0151A1F;
    private static final int PANEL_BORDER = 0xFF6C727A;
    private static final int MAP_BACKGROUND = 0xFF202B2A;
    private static final int MAP_GRID = 0x443F6E68;
    private static final int TEXT_PRIMARY = 0xFFF0F0F0;
    private static final int TEXT_MUTED = 0xFFAAAEB3;
    private static final int STATUS_GOOD = 0xFF69C779;
    private static final int STATUS_WARNING = 0xFFE5B95C;
    private static final int STATUS_ERROR = 0xFFE06B6B;
    private static final int PLAYER_MARKER = 0xFFFFF36A;
    private static final int[] TERRAIN_COLORS = {
            0xFF202B2A, 0xFF315E83, 0xFF789CB2, 0xFF6E9252,
            0xFF3F7047, 0xFFC6AA62, 0xFFB86B3C, 0xFF777A76,
            0xFF687548, 0xFFD9E7E8, 0xFF8B815F, 0xFF6E9252
    };
    private static final int[] REALM_COLORS = {
            0xFFD95F59, 0xFF5C88D8, 0xFFD2A64D, 0xFF7FB267,
            0xFF9A70C7, 0xFF53A7A0, 0xFFC8769B, 0xFFA7764B
    };

    private AdventurerMapSnapshot snapshot;
    private boolean loading;
    private boolean debugLayer;
    private double zoom = 1.0D;
    private Button debugButton;
    private DynamicTexture mapTexture;
    private ResourceLocation mapTextureLocation;
    private int automaticRefreshTicks;

    AdventurerMapScreen(AdventurerMapSnapshot snapshot) {
        super(Component.translatable("gui.geomantia.adventurer_map.title"));
        this.snapshot = snapshot == null ? AdventurerMapSnapshot.empty() : snapshot;
    }

    @Override
    protected void init() {
        int controlsY = this.height - 28;
        addRenderableWidget(Button.builder(Component.translatable("gui.geomantia.adventurer_map.close"),
                        button -> onClose())
                .bounds(this.width - 76, controlsY, 60, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.geomantia.adventurer_map.refresh"),
                        button -> refresh())
                .bounds(16, controlsY, 72, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.geomantia.provider_settings.title"),
                        button -> ProviderSettingsClient.open(this))
                .bounds(94, controlsY, 108, 20).build());
        debugButton = addRenderableWidget(Button.builder(debugLabel(), button -> {
                    debugLayer = !debugLayer;
                    button.setMessage(debugLabel());
                })
                .bounds(208, controlsY, 112, 20).build());
        addRenderableWidget(Button.builder(Component.literal("−"), button -> zoom = Math.max(0.5D, zoom / 1.25D))
                .bounds(326, controlsY, 24, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), button -> zoom = Math.min(4.0D, zoom * 1.25D))
                .bounds(354, controlsY, 24, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.geomantia.agent_activity.open"),
                        button -> ProviderSettingsClient.openActivity(this))
                .bounds(384, controlsY, 92, 20).build());
        rebuildMapTexture();
        refresh();
    }

    void receiveSnapshot(AdventurerMapSnapshot snapshot) {
        this.snapshot = snapshot == null ? AdventurerMapSnapshot.empty() : snapshot;
        this.loading = false;
        rebuildMapTexture();
    }

    private void refresh() {
        loading = true;
        automaticRefreshTicks = 0;
        AdventurerMapClient.requestSnapshot();
    }

    @Override
    public void tick() {
        super.tick();
        if (!loading && ++automaticRefreshTicks >= 100) {
            refresh();
        }
    }

    private Component debugLabel() {
        return Component.translatable(debugLayer
                ? "gui.geomantia.adventurer_map.debug_on"
                : "gui.geomantia.adventurer_map.debug_off");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 12, TEXT_PRIMARY);

        int mapLeft = 16;
        int mapTop = 32;
        int sidebarWidth = Math.min(248, Math.max(184, width / 3));
        int mapRight = Math.max(mapLeft + 120, width - sidebarWidth - 24);
        int contentBottom = Math.max(mapTop + 100, height - 36);
        int sidebarLeft = mapRight + 8;

        drawPanel(graphics, mapLeft, mapTop, mapRight, contentBottom, MAP_BACKGROUND);
        drawMap(graphics, mapLeft + 2, mapTop + 2, mapRight - 2, contentBottom - 2, mouseX, mouseY);
        drawPanel(graphics, sidebarLeft, mapTop, width - 16, contentBottom, PANEL_BACKGROUND);
        drawStatusPanel(graphics, sidebarLeft + 10, mapTop + 10, width - 26);

        if (loading) {
            graphics.drawString(font, Component.translatable("gui.geomantia.adventurer_map.loading"),
                    sidebarLeft + 10, contentBottom - 16, STATUS_WARNING, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawMap(GuiGraphics graphics, int left, int top, int right, int bottom, int mouseX, int mouseY) {
        MapTransform transform = mapTransform(left, top, right, bottom);
        int centerX = transform.screenX(0.0D);
        int centerY = transform.screenY(0.0D);
        graphics.enableScissor(left, top, right, bottom);
        CoarseMap coarseMap = snapshot.coarseMap();
        if (coarseMap.available() && mapTextureLocation != null) {
            int textureLeft = transform.screenX(coarseMap.minBlockX());
            int textureTop = transform.screenY(coarseMap.minBlockZ());
            int textureRight = transform.screenX(coarseMap.maxBlockX());
            int textureBottom = transform.screenY(coarseMap.maxBlockZ());
            graphics.blit(mapTextureLocation, textureLeft, textureTop, 0.0F, 0.0F,
                    Math.max(1, textureRight - textureLeft), Math.max(1, textureBottom - textureTop),
                    coarseMap.width(), coarseMap.height());
        }
        if (debugLayer) {
            for (int x = centerX; x < right; x += 32) graphics.fill(x, top, x + 1, bottom, MAP_GRID);
            for (int x = centerX; x > left; x -= 32) graphics.fill(x, top, x + 1, bottom, MAP_GRID);
            for (int y = centerY; y < bottom; y += 32) graphics.fill(left, y, right, y + 1, MAP_GRID);
            for (int y = centerY; y > top; y -= 32) graphics.fill(left, y, right, y + 1, MAP_GRID);
        }

        int initialRadiusBlocks = Math.max(1, snapshot.initialActivityRadiusBlocks());
        int initialLeft = transform.screenX(-initialRadiusBlocks);
        int initialTop = transform.screenY(-initialRadiusBlocks);
        int initialRight = transform.screenX(initialRadiusBlocks);
        int initialBottom = transform.screenY(initialRadiusBlocks);
        drawOutline(graphics, initialLeft, initialTop, initialRight, initialBottom, 0xFF5A8F69);
        graphics.fill(centerX - 2, centerY, centerX + 3, centerY + 1, 0xFFD7D7D7);
        graphics.fill(centerX, centerY - 2, centerX + 1, centerY + 3, 0xFFD7D7D7);
        graphics.drawString(font, Component.literal("0,0"), centerX + 4, centerY + 4, TEXT_MUTED, false);

        for (CityNode node : snapshot.cityNodes()) {
            int x = transform.screenX(node.blockX());
            int y = transform.screenY(node.blockZ());
            if (x < left + 2 || x > right - 2 || y < top + 2 || y > bottom - 2) continue;
            int radius = node.current() ? 5 : "capital".equals(node.role()) ? 4 : 3;
            int color = nodeColor(node);
            graphics.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, color);
            if (node.current()) drawOutline(graphics, x - radius - 2, y - radius - 2,
                    x + radius + 2, y + radius + 2, 0xFFFFFFFF);
            if (mouseX >= x - radius - 2 && mouseX <= x + radius + 2
                    && mouseY >= y - radius - 2 && mouseY <= y + radius + 2) {
                Component tooltip = Component.literal(node.citySeedId() + "  [" + node.blockX() + ", "
                        + node.blockZ() + "]\n").append(statusComponent(node.status()));
                graphics.renderTooltip(font, tooltip, mouseX, mouseY);
            }
        }

        drawPlayerMarker(graphics, transform, left, top, right, bottom);
        graphics.disableScissor();

        graphics.drawString(font, Component.translatable("gui.geomantia.adventurer_map.initial_area"),
                left + 6, bottom - 14, 0xFF77B788, false);
    }

    private MapTransform mapTransform(int left, int top, int right, int bottom) {
        CoarseMap map = snapshot.coarseMap();
        double minX;
        double minZ;
        double maxX;
        double maxZ;
        if (map.available()) {
            minX = map.minBlockX();
            minZ = map.minBlockZ();
            maxX = map.maxBlockX();
            maxZ = map.maxBlockZ();
        } else {
            int radius = Math.max(1, snapshot.initialActivityRadiusBlocks());
            minX = -radius;
            minZ = -radius;
            maxX = radius;
            maxZ = radius;
            for (CityNode node : snapshot.cityNodes()) {
                minX = Math.min(minX, node.blockX());
                minZ = Math.min(minZ, node.blockZ());
                maxX = Math.max(maxX, node.blockX());
                maxZ = Math.max(maxZ, node.blockZ());
            }
        }
        double worldWidth = Math.max(1.0D, maxX - minX);
        double worldHeight = Math.max(1.0D, maxZ - minZ);
        double scale = Math.min((right - left - 16.0D) / worldWidth,
                (bottom - top - 16.0D) / worldHeight) * zoom;
        return new MapTransform((minX + maxX) * 0.5D, (minZ + maxZ) * 0.5D,
                (left + right) * 0.5D, (top + bottom) * 0.5D, Math.max(0.00001D, scale));
    }

    private void drawPlayerMarker(GuiGraphics graphics, MapTransform transform,
                                  int left, int top, int right, int bottom) {
        if (minecraft == null || minecraft.player == null) return;
        CoarseMap map = snapshot.coarseMap();
        String dimensionId = minecraft.player.level().dimension().location().toString();
        if (map.available() && !map.dimensionId().isBlank() && !map.dimensionId().equals(dimensionId)) return;
        int x = transform.screenX(minecraft.player.getX());
        int y = transform.screenY(minecraft.player.getZ());
        if (x < left || x > right || y < top || y > bottom) return;
        graphics.fill(x - 3, y - 3, x + 4, y + 4, 0xFF1A1A1A);
        graphics.fill(x - 2, y - 2, x + 3, y + 3, PLAYER_MARKER);
        graphics.fill(x, y - 4, x + 1, y - 2, PLAYER_MARKER);
        graphics.drawString(font, Component.translatable("gui.geomantia.adventurer_map.player_position",
                        (int) Math.floor(minecraft.player.getX()), (int) Math.floor(minecraft.player.getZ())),
                Math.min(right - 92, x + 6), Math.max(top + 2, y - 4), TEXT_PRIMARY, true);
    }

    private void rebuildMapTexture() {
        releaseMapTexture();
        CoarseMap map = snapshot.coarseMap();
        if (!map.available() || minecraft == null) return;
        NativeImage image = new NativeImage(map.width(), map.height(), true);
        byte[] terrainCodes = map.terrainCodes();
        byte[] realmCodes = map.realmCodes();
        for (int row = 0; row < map.height(); row++) {
            for (int column = 0; column < map.width(); column++) {
                int index = row * map.width() + column;
                int terrainCode = Math.min(TERRAIN_COLORS.length - 1, Byte.toUnsignedInt(terrainCodes[index]));
                int color = TERRAIN_COLORS[terrainCode];
                int realmCode = Byte.toUnsignedInt(realmCodes[index]);
                if (realmCode > 0 && realmCode <= map.realmIds().size()) {
                    String realmId = map.realmIds().get(realmCode - 1);
                    int realmColor = REALM_COLORS[Math.floorMod(realmId.hashCode(), REALM_COLORS.length)];
                    color = blend(color, realmColor, 0.28D);
                }
                image.setPixelRGBA(column, row, argbToAbgr(color));
            }
        }
        mapTexture = new DynamicTexture(image);
        mapTextureLocation = minecraft.getTextureManager().register("geomantia/adventurer_map", mapTexture);
    }

    private void releaseMapTexture() {
        if (mapTextureLocation != null && minecraft != null) {
            minecraft.getTextureManager().release(mapTextureLocation);
        } else if (mapTexture != null) {
            mapTexture.close();
        }
        mapTexture = null;
        mapTextureLocation = null;
    }

    private static int blend(int base, int overlay, double amount) {
        double inverse = 1.0D - amount;
        int red = (int) Math.round(((base >> 16) & 0xff) * inverse + ((overlay >> 16) & 0xff) * amount);
        int green = (int) Math.round(((base >> 8) & 0xff) * inverse + ((overlay >> 8) & 0xff) * amount);
        int blue = (int) Math.round((base & 0xff) * inverse + (overlay & 0xff) * amount);
        return 0xff000000 | red << 16 | green << 8 | blue;
    }

    private static int argbToAbgr(int color) {
        return color & 0xff00ff00 | color >> 16 & 0xff | (color & 0xff) << 16;
    }

    @Override
    public void removed() {
        releaseMapTexture();
        super.removed();
    }

    private void drawStatusPanel(GuiGraphics graphics, int x, int y, int right) {
        int line = y;
        graphics.drawString(font, Component.translatable("gui.geomantia.adventurer_map.world_planning"),
                x, line, TEXT_PRIMARY, false);
        line += 16;

        graphics.drawString(font, labelValue("gui.geomantia.adventurer_map.w_status",
                statusComponent(snapshot.wStatus())), x, line, statusColor(snapshot.wStatus()), false);
        line += 12;
        drawProgress(graphics, x, line, right, snapshot.wProgressPercent());
        line += 14;

        Component tValue = snapshot.tStage().isBlank()
                ? statusComponent(snapshot.tStatus())
                : Component.empty().append(stageComponent(snapshot.tStage())).append(Component.literal(" · "))
                        .append(statusComponent(snapshot.tStatus()));
        graphics.drawString(font, labelValue("gui.geomantia.adventurer_map.t_status", tValue),
                x, line, statusColor(snapshot.tStatus()), false);
        line += 16;

        graphics.drawString(font, labelValue("gui.geomantia.adventurer_map.current_realm",
                valueOrDash(snapshot.currentRealmName())), x, line, TEXT_PRIMARY, false);
        line += 12;
        graphics.drawString(font, labelValue("gui.geomantia.adventurer_map.current_city",
                valueOrDash(snapshot.currentCityId())), x, line, TEXT_PRIMARY, false);
        line += 12;
        graphics.drawString(font, labelValue("gui.geomantia.adventurer_map.city_status",
                statusComponent(snapshot.cityStatus())), x, line, statusColor(snapshot.cityStatus()), false);
        line += 16;

        graphics.drawString(font, Component.translatable("gui.geomantia.adventurer_map.city_counts",
                snapshot.completedCityCount(), snapshot.remainingCityCount()), x, line, TEXT_MUTED, false);
        line += 16;

        if (debugLayer) {
            graphics.drawString(font, Component.translatable("gui.geomantia.adventurer_map.debug_title"),
                    x, line, STATUS_WARNING, false);
            line += 12;
            graphics.drawString(font, Component.literal("runId: " + valueOrDash(snapshot.runId()).getString()),
                    x, line, TEXT_MUTED, false);
            line += 12;
            graphics.drawString(font, Component.literal("W phase: " + valueOrDash(snapshot.wPhase()).getString()),
                    x, line, TEXT_MUTED, false);
            line += 12;
            graphics.drawString(font, Component.literal("realmId: " + valueOrDash(snapshot.currentRealmId()).getString()),
                    x, line, TEXT_MUTED, false);
            line += 12;
            graphics.drawString(font, Component.literal("nodes: " + snapshot.cityNodes().size()),
                    x, line, TEXT_MUTED, false);
        }
    }

    private void drawProgress(GuiGraphics graphics, int left, int y, int right, double percent) {
        int width = Math.max(20, right - left);
        graphics.fill(left, y, left + width, y + 6, 0xFF30363D);
        int fill = (int) Math.round(width * Math.max(0.0D, Math.min(100.0D, percent)) / 100.0D);
        graphics.fill(left, y, left + fill, y + 6, STATUS_GOOD);
        graphics.drawString(font, Component.literal(String.format(Locale.ROOT, "%.1f%%", percent)),
                left, y + 8, TEXT_MUTED, false);
    }

    private static void drawPanel(GuiGraphics graphics, int left, int top, int right, int bottom, int color) {
        graphics.fill(left, top, right, bottom, PANEL_BORDER);
        graphics.fill(left + 1, top + 1, right - 1, bottom - 1, color);
    }

    private static void drawOutline(GuiGraphics graphics, int left, int top, int right, int bottom, int color) {
        graphics.fill(left, top, right + 1, top + 1, color);
        graphics.fill(left, bottom, right + 1, bottom + 1, color);
        graphics.fill(left, top, left + 1, bottom + 1, color);
        graphics.fill(right, top, right + 1, bottom + 1, color);
    }

    private static int nodeColor(CityNode node) {
        if (node.current()) return 0xFFE2BC55;
        return switch (node.status()) {
            case "waiting_for_generation" -> 0xFF71A8E0;
            case "needs_agent" -> STATUS_ERROR;
            case "post_d4_running" -> 0xFFB78BE2;
            case "waiting_for_agent" -> STATUS_WARNING;
            default -> "capital".equals(node.role()) ? 0xFFD89A52 : 0xFFB7B7B7;
        };
    }

    private static int statusColor(String status) {
        return switch (status) {
            case "completed", "waiting_for_generation" -> STATUS_GOOD;
            case "error", "needs_agent", "failed" -> STATUS_ERROR;
            case "running", "pending", "waiting_for_agent", "post_d4_running" -> STATUS_WARNING;
            default -> TEXT_MUTED;
        };
    }

    private static Component labelValue(String labelKey, Component value) {
        return Component.translatable(labelKey).append(Component.literal(": ")).append(value);
    }

    private static Component valueOrDash(String value) {
        return Component.literal(value == null || value.isBlank() ? "—" : value);
    }

    private static Component statusComponent(String status) {
        String safe = status == null || status.isBlank() ? "not_started" : status;
        return Component.translatable("gui.geomantia.adventurer_map.status." + safe);
    }

    private static Component stageComponent(String stage) {
        String safe = stage == null ? "" : stage.toLowerCase(Locale.ROOT);
        return Component.translatable("gui.geomantia.adventurer_map.stage." + safe);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record MapTransform(double worldCenterX, double worldCenterZ,
                                double screenCenterX, double screenCenterY, double scale) {
        int screenX(double blockX) {
            return (int) Math.round(screenCenterX + (blockX - worldCenterX) * scale);
        }

        int screenY(double blockZ) {
            return (int) Math.round(screenCenterY + (blockZ - worldCenterZ) * scale);
        }
    }
}
