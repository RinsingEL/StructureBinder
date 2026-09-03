package com.rinsing.geomantia.client;

import com.rinsing.geomantia.systems.provider.application.AgentActivityEvent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public final class AgentActivityScreen extends Screen {
    private static final int PANEL = 0xE0181D23;
    private static final int BORDER = 0xFF68717A;
    private static final int TEXT = 0xFFF0F0F0;
    private static final int MUTED = 0xFFAEB3B8;
    private static final int GOOD = 0xFF69C779;
    private static final int WARN = 0xFFE5B95C;
    private static final int ERROR = 0xFFE06B6B;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final Screen parent;
    private List<AgentActivityEvent> events = List.of();
    private int refreshTicks;
    private int scrollFromBottom;

    AgentActivityScreen(Screen parent) {
        super(Component.translatable("gui.geomantia.agent_activity.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int controlsY = height - 28;
        addRenderableWidget(Button.builder(Component.translatable("gui.geomantia.agent_activity.refresh"),
                        button -> ProviderSettingsClient.requestActivity())
                .bounds(16, controlsY, 72, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.geomantia.agent_activity.back"),
                        button -> onClose())
                .bounds(width - 88, controlsY, 72, 20).build());
        ProviderSettingsClient.requestActivity();
    }

    void receive(List<AgentActivityEvent> values) {
        events = values == null ? List.of() : List.copyOf(values);
        refreshTicks = 0;
    }

    @Override
    public void tick() {
        super.tick();
        if (++refreshTicks >= 20) {
            refreshTicks = 0;
            ProviderSettingsClient.requestActivity();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 10, TEXT);
        int left = 16;
        int top = 28;
        int right = width - 16;
        int bottom = height - 36;
        graphics.fill(left, top, right, bottom, BORDER);
        graphics.fill(left + 1, top + 1, right - 1, bottom - 1, PANEL);

        List<DisplayLine> lines = displayLines(Math.max(80, right - left - 20));
        int visible = Math.max(1, (bottom - top - 18) / 11);
        int maximumScroll = Math.max(0, lines.size() - visible);
        scrollFromBottom = Math.min(scrollFromBottom, maximumScroll);
        int start = Math.max(0, lines.size() - visible - scrollFromBottom);
        int end = Math.min(lines.size(), start + visible);
        int y = top + 8;
        if (lines.isEmpty()) {
            graphics.drawString(font, Component.translatable("gui.geomantia.agent_activity.empty"),
                    left + 8, y, MUTED, false);
        } else {
            for (int index = start; index < end; index++) {
                DisplayLine line = lines.get(index);
                graphics.drawString(font, line.text(), left + 8, y, line.color(), false);
                y += 11;
            }
        }
        graphics.drawString(font, Component.translatable("gui.geomantia.agent_activity.hint"),
                96, height - 23, MUTED, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private List<DisplayLine> displayLines(int lineWidth) {
        List<DisplayLine> result = new ArrayList<>();
        for (AgentActivityEvent event : events) {
            int color = color(event.kind());
            String prefix = "[" + time(event.occurredAt()) + "] " + label(event.kind()) + " ";
            List<FormattedCharSequence> wrapped = font.split(Component.literal(prefix + event.message()), lineWidth);
            for (FormattedCharSequence line : wrapped) result.add(new DisplayLine(line, color));
        }
        return result;
    }

    private static String time(String value) {
        try {
            return TIME.format(Instant.parse(value));
        } catch (DateTimeParseException exception) {
            return "--:--:--";
        }
    }

    private static String label(String kind) {
        return switch (kind) {
            case "model" -> "思路";
            case "tool" -> "调用";
            case "result" -> "结果";
            case "progress" -> "推进";
            case "waiting" -> "等待";
            case "error" -> "错误";
            case "stage" -> "阶段";
            default -> "系统";
        };
    }

    private static int color(String kind) {
        return switch (kind) {
            case "progress", "result" -> GOOD;
            case "error" -> ERROR;
            case "tool", "waiting", "stage" -> WARN;
            default -> TEXT;
        };
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scrollFromBottom = Math.max(0, scrollFromBottom + (delta > 0.0D ? 3 : -3));
        return true;
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record DisplayLine(FormattedCharSequence text, int color) {
    }
}
