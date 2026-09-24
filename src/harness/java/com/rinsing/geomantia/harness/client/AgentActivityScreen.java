package com.rinsing.geomantia.harness.client;
import com.rinsing.geomantia.systems.provider.application.*;

import com.rinsing.geomantia.harness.systems.provider.application.AgentActivityEvent;
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
    private boolean reasoningOnly;
    private boolean draggingScrollbar;
    private double scrollbarGrabOffset;
    private List<DisplayLine> cachedLines = List.of();
    private int cachedWidth = -1;
    private boolean cachedReasoningOnly;

    AgentActivityScreen(Screen parent) {
        super(Component.translatable("gui.geomantia.agent_activity.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int controlsY = height - 28;
        addRenderableWidget(Button.builder(Component.literal("总日志"), button -> {
            reasoningOnly = false; scrollFromBottom = 0; draggingScrollbar = false;
        }).bounds(16, 27, 85, 20).build());
        addRenderableWidget(Button.builder(Component.literal("模型思考"), button -> {
            reasoningOnly = true; scrollFromBottom = 0; draggingScrollbar = false;
        }).bounds(106, 27, 85, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.geomantia.agent_activity.refresh"),
                        button -> ProviderSettingsClient.requestActivity())
                .bounds(16, controlsY, 72, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.geomantia.agent_activity.back"),
                        button -> onClose())
                .bounds(width - 88, controlsY, 72, 20).build());
        ProviderSettingsClient.requestActivity();
    }

    void receive(List<AgentActivityEvent> values) {
        List<AgentActivityEvent> incoming = values == null ? List.of() : List.copyOf(values);
        if (!events.equals(incoming)) {
            int previousLines = displayLines(lineWidth()).size();
            events = incoming;
            cachedWidth = -1;
            int addedLines = displayLines(lineWidth()).size() - previousLines;
            if (scrollFromBottom > 0) scrollFromBottom = Math.max(0, scrollFromBottom + addedLines);
        }
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
        graphics.drawCenteredString(font, Component.literal(reasoningOnly ? "Agent 过程 · 模型思考" : "Agent 过程 · 总日志"), width / 2, 10, TEXT);
        int left = 16;
        int top = 52;
        int right = width - 16;
        int bottom = height - 36;
        graphics.fill(left, top, right, bottom, BORDER);
        graphics.fill(left + 1, top + 1, right - 1, bottom - 1, PANEL);

        List<DisplayLine> lines = displayLines(lineWidth());
        int visible = visibleLines();
        int maximumScroll = Math.max(0, lines.size() - visible);
        scrollFromBottom = Math.min(scrollFromBottom, maximumScroll);
        int start = Math.max(0, lines.size() - visible - scrollFromBottom);
        int end = Math.min(lines.size(), start + visible);
        int y = 0;
        graphics.enableScissor(left + 2, top + 2, right - 12, bottom - 2);
        graphics.pose().pushPose();
        graphics.pose().translate(left + 8, top + 8, 0);
        graphics.pose().scale(textScale(), textScale(), 1);
        if (lines.isEmpty()) {
            graphics.drawString(font, (reasoningOnly ? Component.literal("尚未收到模型思考；仅显示接口实际返回的内容")
                            : Component.translatable("gui.geomantia.agent_activity.empty")),
                    0, y, MUTED, false);
        } else {
            for (int index = start; index < end; index++) {
                DisplayLine line = lines.get(index);
                graphics.drawString(font, line.text(), 0, y, line.color(), false);
                y += 11;
            }
        }
        graphics.pose().popPose();
        graphics.disableScissor();
        int trackTop = 56;
        int trackBottom = height - 40;
        int trackHeight = Math.max(1, trackBottom - trackTop);
        int thumbHeight = thumbHeight(lines.size(), visible, trackHeight);
        int thumbTop = trackTop + (maximumScroll == 0 ? 0
                : (int) Math.round((trackHeight - thumbHeight) * (1.0 - (double) scrollFromBottom / maximumScroll)));
        graphics.fill(right - 10, trackTop, right - 3, trackBottom, 0xFF303840);
        graphics.fill(right - 10, thumbTop, right - 3, thumbTop + thumbHeight,
                draggingScrollbar ? TEXT : BORDER);
        graphics.drawString(font, Component.translatable("gui.geomantia.agent_activity.hint"),
                96, height - 23, MUTED, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private List<DisplayLine> displayLines(int lineWidth) {
        if (cachedWidth == lineWidth && cachedReasoningOnly == reasoningOnly) return cachedLines;
        List<DisplayLine> result = new ArrayList<>();
        for (AgentActivityEvent event : events) {
            if (reasoningOnly != event.kind().equals("reasoning")) continue;
            int color = color(event.kind());
            String prefix = "[" + time(event.occurredAt()) + "] " + label(event.kind()) + " ";
            List<FormattedCharSequence> wrapped = font.split(Component.literal(prefix + event.message()), lineWidth);
            for (FormattedCharSequence line : wrapped) result.add(new DisplayLine(line, color));
        }
        cachedWidth = lineWidth;
        cachedReasoningOnly = reasoningOnly;
        cachedLines = List.copyOf(result);
        return cachedLines;
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
            case "model", "model_delta" -> "模型输出";
            case "reasoning" -> "模型思考";
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

    private float textScale() {
        return reasoningOnly ? 0.8F : 1.0F;
    }

    private int lineWidth() {
        return Math.max(1, (int) ((width - 56) / textScale()));
    }

    private int visibleLines() {
        return Math.max(1, (int) ((height - 106) / (11 * textScale())));
    }

    private int maximumScroll() {
        return Math.max(0, displayLines(lineWidth()).size() - visibleLines());
    }

    private static int thumbHeight(int total, int visible, int trackHeight) {
        return Math.min(trackHeight, Math.max(16, trackHeight * visible / Math.max(1, total)));
    }

    private void dragScrollbar(double mouseY) {
        int trackHeight = Math.max(1, height - 96);
        int thumb = thumbHeight(displayLines(lineWidth()).size(), visibleLines(), trackHeight);
        double fraction = Math.max(0, Math.min(1,
                (mouseY - 56 - scrollbarGrabOffset) / Math.max(1, trackHeight - thumb)));
        scrollFromBottom = (int) Math.round(maximumScroll() * (1 - fraction));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= width - 26 && mouseX < width - 19
                && mouseY >= 56 && mouseY < height - 40) {
            int maximum = maximumScroll();
            if (maximum == 0) return true;
            scrollFromBottom = Math.min(scrollFromBottom, maximum);
            int trackHeight = Math.max(1, height - 96);
            int thumb = thumbHeight(displayLines(lineWidth()).size(), visibleLines(), trackHeight);
            double thumbTop = 56 + (trackHeight - thumb) * (1.0 - (double) scrollFromBottom / maximum);
            scrollbarGrabOffset = mouseY >= thumbTop && mouseY < thumbTop + thumb
                    ? mouseY - thumbTop : thumb / 2.0;
            draggingScrollbar = true;
            dragScrollbar(mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar && button == 0) {
            dragScrollbar(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scrollFromBottom = Math.max(0, Math.min(maximumScroll(),
                scrollFromBottom + (delta > 0.0D ? 3 : delta < 0.0D ? -3 : 0)));
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
