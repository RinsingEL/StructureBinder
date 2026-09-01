package com.rinsing.geomantia.client;

import com.rinsing.geomantia.systems.realm_planning.application.map.AdventurerMapSnapshot;
import com.rinsing.geomantia.systems.realm_planning.application.map.AdventurerMapSnapshot.CityNode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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

    private AdventurerMapSnapshot snapshot;
    private boolean loading;
    private boolean debugLayer;
    private double zoom = 1.0D;
    private Button debugButton;

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
        debugButton = addRenderableWidget(Button.builder(debugLabel(), button -> {
                    debugLayer = !debugLayer;
                    button.setMessage(debugLabel());
                })
                .bounds(94, controlsY, 112, 20).build());
        addRenderableWidget(Button.builder(Component.literal("−"), button -> zoom = Math.max(0.5D, zoom / 1.25D))
                .bounds(212, controlsY, 24, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), button -> zoom = Math.min(4.0D, zoom * 1.25D))
                .bounds(240, controlsY, 24, 20).build());
        refresh();
    }

    void receiveSnapshot(AdventurerMapSnapshot snapshot) {
        this.snapshot = snapshot == null ? AdventurerMapSnapshot.empty() : snapshot;
        this.loading = false;
    }

    private void refresh() {
        loading = true;
        AdventurerMapClient.requestSnapshot();
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
        int centerX = (left + right) / 2;
        int centerY = (top + bottom) / 2;
        if (debugLayer) {
            for (int x = centerX; x < right; x += 32) graphics.fill(x, top, x + 1, bottom, MAP_GRID);
            for (int x = centerX; x > left; x -= 32) graphics.fill(x, top, x + 1, bottom, MAP_GRID);
            for (int y = centerY; y < bottom; y += 32) graphics.fill(left, y, right, y + 1, MAP_GRID);
            for (int y = centerY; y > top; y -= 32) graphics.fill(left, y, right, y + 1, MAP_GRID);
        }

        int availableHalfWidth = Math.max(1, (right - left) / 2 - 12);
        int availableHalfHeight = Math.max(1, (bottom - top) / 2 - 12);
        int initialRadiusBlocks = Math.max(1, snapshot.initialActivityRadiusBlocks());
        int maxAbsX = initialRadiusBlocks;
        int maxAbsZ = initialRadiusBlocks;
        for (CityNode node : snapshot.cityNodes()) {
            maxAbsX = Math.max(maxAbsX, Math.abs(node.blockX()));
            maxAbsZ = Math.max(maxAbsZ, Math.abs(node.blockZ()));
        }
        double baseScale = Math.min((double) availableHalfWidth / maxAbsX,
                (double) availableHalfHeight / maxAbsZ);
        double scale = baseScale * zoom;

        int initialLeft = centerX - (int) Math.round(initialRadiusBlocks * scale);
        int initialTop = centerY - (int) Math.round(initialRadiusBlocks * scale);
        int initialRight = centerX + (int) Math.round(initialRadiusBlocks * scale);
        int initialBottom = centerY + (int) Math.round(initialRadiusBlocks * scale);
        drawOutline(graphics, initialLeft, initialTop, initialRight, initialBottom, 0xFF5A8F69);
        graphics.fill(centerX - 2, centerY, centerX + 3, centerY + 1, 0xFFD7D7D7);
        graphics.fill(centerX, centerY - 2, centerX + 1, centerY + 3, 0xFFD7D7D7);
        graphics.drawString(font, Component.literal("0,0"), centerX + 4, centerY + 4, TEXT_MUTED, false);

        for (CityNode node : snapshot.cityNodes()) {
            int x = centerX + (int) Math.round(node.blockX() * scale);
            int y = centerY + (int) Math.round(node.blockZ() * scale);
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

        graphics.drawString(font, Component.translatable("gui.geomantia.adventurer_map.initial_area"),
                left + 6, bottom - 14, 0xFF77B788, false);
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
}
